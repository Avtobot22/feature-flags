package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.CacheClock
import io.github.avtobot22.featureflags.CacheMode
import io.github.avtobot22.featureflags.CacheRecord
import io.github.avtobot22.featureflags.CacheRestoreResult
import io.github.avtobot22.featureflags.CacheSettings
import io.github.avtobot22.featureflags.ConfigOrigin
import io.github.avtobot22.featureflags.ConfigurationCache
import io.github.avtobot22.featureflags.EvaluationContext
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.Readiness
import io.github.avtobot22.featureflags.RefreshError
import io.github.avtobot22.featureflags.RefreshResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonRolloutIntegrationTest {
    @Test
    fun `context views perform no loading and observations remain bound across publications`() = runTest {
        var calls = 0
        var json = document()
        val source = FeatureFlagsSource {
            calls++
            json
        }
        val system = system(source)
        val included = system.reader.withContext(EvaluationContext("user-1"))
        val excluded = included.withContext(EvaluationContext("user-3"))
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        backgroundScope.launch { included.observe(flag).toList(first) }
        backgroundScope.launch { excluded.observe(flag).toList(second) }
        testScheduler.runCurrent()
        assertEquals(0, calls)
        assertFalse(included.get(flag))
        system.manager.refresh()
        testScheduler.runCurrent()
        assertTrue(included.get(flag))
        assertFalse(excluded.get(flag))
        assertFalse(system.reader.get(flag))
        assertEquals(listOf(false, true), first)
        assertEquals(listOf(false), second)

        json = document(points = 10000)
        system.manager.refresh()
        testScheduler.runCurrent()
        json = document(points = 10000, enabled = false)
        system.manager.refresh()
        testScheduler.runCurrent()
        assertEquals(listOf(false, true, false), first)
        assertEquals(listOf(false, true, false), second)
        assertEquals(Readiness.Ready(3, ConfigOrigin.REMOTE), included.readiness.value)
        assertEquals(included.readiness.value, excluded.readiness.value)
        assertEquals(3, calls)
    }

    @Test
    fun `restart restores shared rules and evaluates a different subject without persisting outcomes`() = runTest {
        val storage = MemoryCache()
        val settings = settings(storage)
        val original = system(FeatureFlagsSource { document() }, settings)
        original.manager.refresh()
        assertTrue(original.reader.withContext(EvaluationContext("user-1")).get(flag))
        assertEquals(document(), storage.record?.document)
        assertFalse(storage.record.toString().contains("user-1"))
        var calls = 0
        val remote = FeatureFlagsSource {
            calls++
            error("Offline")
        }
        val restarted = system(remote, settings)
        assertIs<CacheRestoreResult.Applied>(restarted.cache?.restore())
        assertFalse(restarted.reader.withContext(EvaluationContext("user-3")).get(flag))
        assertTrue(restarted.reader.withContext(EvaluationContext("user-1")).get(flag))
        assertFalse(restarted.reader.get(flag))
        assertEquals(0, calls)
        assertEquals(ConfigOrigin.CACHE, (restarted.reader.readiness.value as Readiness.Ready).origin)
    }

    @Test
    fun `invalid remote rules retain previous memory and disk without cache or asset fallback`() = runTest {
        val storage = MemoryCache()
        var json = document()
        var localCalls = 0
        val system = FeatureFlagsSystem(
            remote = FeatureFlagsSource { json },
            codec = JsonRolloutConfigurationCodec,
            fallback = FallbackPolicy.OnRemoteEmpty,
            local = FeatureFlagsSource {
                localCalls++
                document(points = 10000)
            },
            cache = settings(storage),
        )
        val reader = system.reader.withContext(EvaluationContext("user-1"))
        system.manager.refresh()
        val saved = storage.record
        json = document(points = 10001)
        val failure = assertIs<RefreshResult.Failure>(system.manager.refresh())
        assertIs<RefreshError.InvalidConfiguration>(failure.error)
        assertTrue(reader.get(flag))
        assertEquals(saved, storage.record)
        assertEquals(0, localCalls)
        assertEquals(1, (reader.readiness.value as Readiness.Ready).revision)
    }

    @Test
    fun `offline stale cache can retain enablement until remote kill switch is received`() = runTest {
        val storage = MemoryCache()
        var now = 1000L
        var offline = false
        var enabled = true
        val source = FeatureFlagsSource {
            if (offline) throw IOException("Offline")
            document(enabled = enabled)
        }
        val settings = settings(storage, clock = CacheClock { now }, maxAge = 200)
        val system = system(source, settings)
        val reader = system.reader.withContext(EvaluationContext("user-1"))
        system.manager.refresh()
        now += 150
        offline = true
        enabled = false
        val stale = assertIs<RefreshResult.Applied>(system.manager.refresh())
        assertEquals(ConfigOrigin.CACHE, stale.origin)
        assertTrue(reader.get(flag))
        offline = false
        system.manager.refresh()
        assertFalse(reader.get(flag))
        val restarted = system(source, settings)
        assertIs<CacheRestoreResult.Applied>(restarted.cache?.restore())
        assertFalse(restarted.reader.withContext(EvaluationContext("user-1")).get(flag))
    }

    @Test
    fun `critical cache policy rejects stale restoration and invalidation clears every context view`() = runTest {
        val storage = MemoryCache()
        var now = 1000L
        val settings = settings(storage, clock = CacheClock { now }, maxAge = 100)
        val original = system(FeatureFlagsSource { document() }, settings)
        val reader = original.reader.withContext(EvaluationContext("user-1"))
        original.manager.refresh()
        now += 101
        val restarted = system(FeatureFlagsSource { throw IOException("Offline") }, settings)
        assertIs<CacheRestoreResult.Unavailable>(restarted.cache?.restore())
        assertIs<RefreshResult.Failure>(restarted.manager.refresh())
        assertFalse(restarted.reader.withContext(EvaluationContext("user-1")).get(flag))
        assertTrue(reader.get(flag))
        original.cache?.invalidate()
        assertFalse(reader.get(flag))
        assertEquals(Readiness.NotInitialized, reader.readiness.value)
        assertEquals(null, storage.record)
    }

    private fun settings(
        storage: ConfigurationCache,
        clock: CacheClock = CacheClock { 1000L },
        maxAge: Long = 200,
    ): CacheSettings = CacheSettings(storage, "production/flags-v2/shared", 100, maxAge, CacheMode.NETWORK_FIRST, clock)

    private fun system(remote: FeatureFlagsSource, cache: CacheSettings? = null): FeatureFlagsSystem =
        FeatureFlagsSystem(remote, JsonRolloutConfigurationCodec, FallbackPolicy.Never, cache = cache)

    private fun document(points: Int = 2500, enabled: Boolean = true): String =
        """{"schemaVersion":2,"flags":[{"feature":"flag","enabled":$enabled,
            "rollout":{"kind":"percentage","basisPoints":$points,"group":"guest-v1"}}]}"""

    private val flag = Feature.boolean("flag")
}

private class MemoryCache : ConfigurationCache {
    var record: CacheRecord? = null
    override suspend fun read(scope: String): CacheRecord? = record?.takeIf { it.scope == scope }
    override suspend fun write(record: CacheRecord) { this.record = record }
    override suspend fun clear(scope: String) { if (record?.scope == scope) record = null }
}
