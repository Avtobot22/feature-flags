package io.github.avtobot22.featureflags

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureFlagsCacheTest {
    @Test
    fun `cache settings are explicit and construction reads neither clock nor storage`() {
        val storage = MemoryConfigurationCache()
        val remote = CountingSource { "first" }
        val system = cachedSystem(storage, remote, now = { error("Must not read clock") })
        assertEquals(0, storage.reads)
        assertEquals(0, remote.calls)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        assertNull(loaderSystem(remote, fallback = FallbackPolicy.Never).cache)
        for ((ttl, maxAge) in listOf(-1L to 0L, 2L to 1L)) {
            assertFailsWith<IllegalArgumentException> { CacheSettings(storage, CACHE_SCOPE, ttl, maxAge) }
        }
        assertFailsWith<IllegalArgumentException> { CacheSettings(storage, " ", 0) }
    }

    @Test
    fun `remote saves exact document and a new system restores without network or refresh status change`() = runTest {
        val storage = MemoryConfigurationCache()
        val remote = CountingSource { "first" }
        val system = cachedSystem(storage, remote)
        val result = assertIs<RefreshResult.Applied>(system.manager.refresh())
        assertEquals(CacheWriteResult.Saved, result.cacheWrite)
        assertEquals(ConfigurationAge(100, 0, ConfigurationFreshness.FRESH), result.age)
        assertEquals(CacheRecord(CACHE_SCOPE, 100, "first"), storage.records[CACHE_SCOPE])

        val restored = cachedSystem(storage, remote, mode = CacheMode.REMOTE_ONLY, now = { 111 })
        val restore = assertIs<CacheRestoreResult.Applied>(requireNotNull(restored.cache).restore()).result
        assertEquals(ConfigOrigin.CACHE, restore.origin)
        assertEquals(ConfigurationAge(100, 11, ConfigurationFreshness.STALE), restore.age)
        assertEquals(Readiness.Ready(1, ConfigOrigin.CACHE, restore.age), restored.reader.readiness.value)
        assertEquals(RefreshStatus.Idle, restored.manager.refreshStatus.value)
        assertTrue(restored.reader.get(Feature.boolean("first")))
        assertEquals(1, remote.calls)
        assertEquals(1, storage.writes)
    }

    @Test
    fun `restore rejects unusable records on cold and warm systems`() = runTest {
        val records = listOf(
            null to CacheUnavailable.Missing,
            CacheRecord(CACHE_SCOPE, 50, "first") to CacheUnavailable.Expired,
            CacheRecord(CACHE_SCOPE, 101, "first") to CacheUnavailable.ClockRollback,
            CacheRecord(CACHE_SCOPE, -1, "first") to CacheUnavailable.ClockRollback,
            CacheRecord("other", 100, "first") to CacheUnavailable.Incompatible,
            CacheRecord(CACHE_SCOPE, 100, "first", 2) to CacheUnavailable.Incompatible,
        )
        for (warm in listOf(false, true)) {
            for ((record, expected) in records) {
                val storage = MemoryConfigurationCache()
                val system = cachedSystem(storage)
                if (warm) system.manager.refresh()
                storage.records.clear()
                if (record != null) storage.records[CACHE_SCOPE] = record
                val previous = system.reader.readiness.value
                assertEquals(CacheRestoreResult.Unavailable(expected), requireNotNull(system.cache).restore())
                assertEquals(previous, system.reader.readiness.value)
            }
            val storage = MemoryConfigurationCache()
            val system = cachedSystem(storage)
            if (warm) system.manager.refresh()
            storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "invalid")
            val previous = system.reader.readiness.value
            assertIs<CacheUnavailable.Failure>(
                assertIs<CacheRestoreResult.Unavailable>(requireNotNull(system.cache).restore()).reason,
            )
            assertEquals(previous, system.reader.readiness.value)
        }
    }

    @Test
    fun `ttl and maximum age have inclusive boundaries with overflow safe rollback rejection`() = runTest {
        for ((now, freshness) in listOf(
            100L to ConfigurationFreshness.FRESH,
            110L to ConfigurationFreshness.FRESH,
            111L to ConfigurationFreshness.STALE,
            130L to ConfigurationFreshness.STALE,
        )) {
            val storage = MemoryConfigurationCache()
            storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "first")
            val system = cachedSystem(storage, now = { now })
            val result = assertIs<CacheRestoreResult.Applied>(requireNotNull(system.cache).restore()).result
            assertEquals(freshness, result.age!!.freshness)
            assertEquals(now - 100, result.age.ageMillis)
        }
        val storage = MemoryConfigurationCache()
        storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 0, "first")
        val system = cachedSystem(storage, now = { Long.MAX_VALUE }, ttl = 0, maxAge = Long.MAX_VALUE)
        assertEquals(
            Long.MAX_VALUE,
            assertIs<CacheRestoreResult.Applied>(requireNotNull(system.cache).restore()).result.age!!.ageMillis,
        )
        val rollback = cachedSystem(storage, now = { Long.MIN_VALUE }, ttl = 0, maxAge = Long.MAX_VALUE)
        assertEquals(
            CacheRestoreResult.Unavailable(CacheUnavailable.ClockRollback),
            requireNotNull(rollback.cache).restore(),
        )
    }

    @Test
    fun `cache age is rechecked after decoder crosses ttl maximum age or clock rollback`() = runTest {
        for (afterDecode in listOf(111L, 131L, 99L)) {
            var now = 110L
            val storage = MemoryConfigurationCache()
            storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "first")
            val codec = ConfigurationCodec {
                if (it == "first") now = afterDecode
                loaderCodec.decode(it)
            }
            val system = cachedSystem(storage, now = { now }, codec = codec)
            val restored = requireNotNull(system.cache).restore()
            when (afterDecode) {
                111L -> assertEquals(
                    ConfigurationFreshness.STALE,
                    assertIs<CacheRestoreResult.Applied>(restored).result.age!!.freshness,
                )
                131L -> assertEquals(CacheRestoreResult.Unavailable(CacheUnavailable.Expired), restored)
                else -> assertEquals(CacheRestoreResult.Unavailable(CacheUnavailable.ClockRollback), restored)
            }
            now = 110
            val remote = CountingSource { "second" }
            val cacheFirst = cachedSystem(storage, remote, CacheMode.CACHE_FIRST, now = { now }, codec = codec)
            assertEquals(ConfigOrigin.REMOTE, assertIs<RefreshResult.Applied>(cacheFirst.manager.refresh()).origin)
            assertEquals(1, remote.calls)
        }
    }

    @Test
    fun `asset error after empty remote does not replace lkg and cached empty never chooses asset`() = runTest {
        val storage = MemoryConfigurationCache()
        storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "first")
        for (invalid in listOf(false, true)) {
            val local = CountingSource { if (invalid) "invalid" else throw IOException("asset missing") }
            val system = cachedSystem(
                storage,
                CountingSource { "empty" },
                fallback = FallbackPolicy.OnRemoteEmpty,
                local = local,
            )
            assertIs<RefreshResult.Failure>(system.manager.refresh())
            assertEquals("first", storage.records[CACHE_SCOPE]!!.document)
            assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        }
        storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "empty")
        val local = CountingSource { error("Must not use assets for cached empty") }
        val system = cachedSystem(
            storage,
            CountingSource { throw IOException("offline") },
            fallback = FallbackPolicy.OnRemoteEmpty,
            local = local,
        )
        assertEquals(0, assertIs<CacheRestoreResult.Applied>(requireNotNull(system.cache).restore()).result.flagCount)
        assertEquals(ConfigOrigin.CACHE, assertIs<RefreshResult.Applied>(system.manager.refresh()).origin)
        assertEquals(0, local.calls)
    }

    @Test
    fun `all modes reach remote unless cache first finds fresh valid data`() = runTest {
        for (mode in CacheMode.entries) {
            for (document in listOf("first", "invalid")) {
                for (received in listOf(100L, 89L, 69L)) {
                    val storage = MemoryConfigurationCache()
                    storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, received, document)
                    val remote = CountingSource { "second" }
                    val system = cachedSystem(storage, remote, mode)
                    val result = assertIs<RefreshResult.Applied>(system.manager.refresh())
                    val fromCache = mode == CacheMode.CACHE_FIRST && received == 100L && document == "first"
                    assertEquals(if (fromCache) ConfigOrigin.CACHE else ConfigOrigin.REMOTE, result.origin)
                    assertEquals(if (fromCache) 0 else 1, remote.calls)
                    assertEquals(if (fromCache) 0 else 1, storage.writes)
                }
            }
            val storage = MemoryConfigurationCache()
            val remote = CountingSource { "second" }
            assertIs<RefreshResult.Applied>(cachedSystem(storage, remote, mode).manager.refresh())
            assertEquals(1, remote.calls)
        }
    }

    @Test
    fun `transport fallback permits fresh and bounded stale only and remote only never falls back`() = runTest {
        val transport = IOException("offline")
        for (mode in CacheMode.entries) {
            for (received in listOf(100L, 89L, 70L, 69L)) {
                val storage = MemoryConfigurationCache()
                storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, received, "first")
                val system = cachedSystem(storage, CountingSource { throw transport }, mode)
                val result = system.manager.refresh()
                if (mode != CacheMode.REMOTE_ONLY && received >= 70) {
                    assertEquals(ConfigOrigin.CACHE, assertIs<RefreshResult.Applied>(result).origin)
                    assertTrue(system.reader.get(Feature.boolean("first")))
                } else {
                    assertSame(
                        transport,
                        assertIs<RefreshError.Transport>(assertIs<RefreshResult.Failure>(result).error).cause,
                    )
                    assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
                }
                assertEquals(0, storage.writes)
            }
        }
    }

    @Test
    fun `invalid remote never falls back and cache storage failures retain original transport failure`() = runTest {
        for (mode in listOf(CacheMode.NETWORK_FIRST, CacheMode.CACHE_FIRST, CacheMode.REMOTE_ONLY)) {
            val storage = MemoryConfigurationCache()
            storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 89, "first")
            val system = cachedSystem(storage, CountingSource { "invalid" }, mode)
            val error = assertIs<RefreshResult.Failure>(system.manager.refresh()).error
            assertEquals(ConfigOrigin.REMOTE, assertIs<RefreshError.InvalidConfiguration>(error).origin)
            assertEquals(0, storage.writes)
            assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        }
        val storage = MemoryConfigurationCache()
        storage.readFailure = IOException("disk")
        val transport = IOException("network")
        val system = cachedSystem(storage, CountingSource { throw transport })
        assertSame(
            transport,
            assertIs<RefreshError.Transport>(assertIs<RefreshResult.Failure>(system.manager.refresh()).error).cause,
        )
        assertSame(
            storage.readFailure,
            assertIs<CacheUnavailable.Failure>(
                assertIs<CacheRestoreResult.Unavailable>(requireNotNull(system.cache).restore()).reason,
            ).cause,
        )
    }

    @Test
    fun `empty remote replaces cache even when assets are selected and assets never become lkg`() = runTest {
        for (fallback in listOf(FallbackPolicy.Never, FallbackPolicy.OnRemoteEmpty)) {
            val storage = MemoryConfigurationCache()
            storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "first")
            val local = CountingSource { "second" }
            val system = cachedSystem(storage, CountingSource { "empty" }, fallback = fallback, local = local)
            val applied = assertIs<RefreshResult.Applied>(system.manager.refresh())
            assertEquals(
                if (fallback == FallbackPolicy.Never) ConfigOrigin.REMOTE else ConfigOrigin.BUNDLED_ASSET,
                applied.origin,
            )
            assertEquals("empty", storage.records[CACHE_SCOPE]!!.document)
            val restored = cachedSystem(storage)
            assertEquals(
                0,
                assertIs<CacheRestoreResult.Applied>(requireNotNull(restored.cache).restore()).result.flagCount,
            )
            assertFalse(restored.reader.get(Feature.boolean("second")))
        }
    }

    @Test
    fun `write error is separate from applied snapshot and prior durable record survives`() = runTest {
        val storage = MemoryConfigurationCache()
        storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "first")
        val failure = IOException("disk full")
        storage.writeFailure = failure
        val system = cachedSystem(storage)
        val applied = assertIs<RefreshResult.Applied>(system.manager.refresh())
        assertSame(failure, assertIs<CacheWriteResult.Failure>(applied.cacheWrite).cause)
        assertTrue(system.reader.get(Feature.boolean("second")))
        assertEquals("first", storage.records[CACHE_SCOPE]!!.document)
        assertEquals(RefreshStatus.Finished(applied), system.manager.refreshStatus.value)
    }

    @Test
    fun `freshness does not tick or expire active memory and reads never touch storage`() = runTest {
        var now = 100L
        val storage = MemoryConfigurationCache()
        val system = cachedSystem(storage, now = { now })
        system.manager.refresh()
        now = 1000
        val reads = storage.reads
        assertTrue(system.reader.get(Feature.boolean("second")))
        assertEquals(
            ConfigurationFreshness.FRESH,
            assertIs<Readiness.Ready>(system.reader.readiness.value).age!!.freshness,
        )
        assertEquals(reads, storage.reads)
    }

    @Test
    fun `scope isolates restore and invalidation and clear failure still closes system and removes memory`() = runTest {
        val storage = MemoryConfigurationCache()
        val first = cachedSystem(storage)
        first.manager.refresh()
        val second = cachedSystem(storage, scope = "other")
        assertEquals(CacheRestoreResult.Unavailable(CacheUnavailable.Missing), requireNotNull(second.cache).restore())
        second.manager.refresh()
        assertEquals(CacheInvalidationResult.Cleared, requireNotNull(first.cache).invalidate())
        assertEquals(Readiness.NotInitialized, first.reader.readiness.value)
        assertFalse(first.reader.get(Feature.boolean("second")))
        assertEquals(RefreshResult.Failure(RefreshError.Invalidated), first.manager.refresh())
        assertEquals(
            CacheRestoreResult.Unavailable(CacheUnavailable.Invalidated),
            requireNotNull(first.cache).restore(),
        )
        assertTrue(storage.records.containsKey("other"))
        storage.clearFailure = IOException("cannot delete")
        assertIs<CacheInvalidationResult.Failure>(requireNotNull(second.cache).invalidate())
        assertEquals(Readiness.NotInitialized, second.reader.readiness.value)
        assertEquals(RefreshResult.Failure(RefreshError.Invalidated), second.manager.refresh())
    }

    @Test
    fun `storage cancellation and codec bugs propagate`() = runTest {
        val storage = MemoryConfigurationCache()
        storage.readFailure = CancellationException("cancel read")
        val system = cachedSystem(storage, mode = CacheMode.CACHE_FIRST)
        assertFailsWith<CancellationException> { system.manager.refresh() }
        assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
        storage.readFailure = null
        val error = IllegalStateException("codec bug")
        val broken = cachedSystem(storage, codec = ConfigurationCodec { throw error })
        assertSame(error, assertFailsWith<IllegalStateException> { broken.manager.refresh() })
        assertEquals(Readiness.NotInitialized, broken.reader.readiness.value)
    }
}
