package io.github.avtobot22.featureflags

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureFlagsCacheConcurrencyTest {
    @Test
    fun `invalidate immediately blocks late remote publication and saving`() = runTest {
        val storage = MemoryConfigurationCache()
        val response = CompletableDeferred<String>()
        val remote = CountingSource { response.await() }
        val system = cachedSystem(storage, remote)
        val refresh = async { system.manager.refresh() }
        testScheduler.runCurrent()

        assertEquals(CacheInvalidationResult.Cleared, requireNotNull(system.cache).invalidate())
        assertFalse(refresh.isCompleted)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        response.complete("first")
        assertEquals(RefreshResult.Failure(RefreshError.Invalidated), refresh.await())
        assertEquals(0, storage.writes)
        assertFalse(storage.records.containsKey(CACHE_SCOPE))
    }

    @Test
    fun `invalidate between publication and completed disk write removes memory then durable record`() = runTest {
        val storage = MemoryConfigurationCache()
        val completeWrite = CompletableDeferred<Unit>()
        storage.beforeWrite = { completeWrite.await() }
        val system = cachedSystem(storage)
        val refresh = async { system.manager.refresh() }
        testScheduler.runCurrent()
        assertTrue(system.reader.get(Feature.boolean("second")))
        val invalidate = async { requireNotNull(system.cache).invalidate() }
        testScheduler.runCurrent()
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        assertFalse(invalidate.isCompleted)
        completeWrite.complete(Unit)
        assertIs<RefreshResult.Applied>(refresh.await())
        assertEquals(CacheInvalidationResult.Cleared, invalidate.await())
        assertFalse(storage.records.containsKey(CACHE_SCOPE))
        assertEquals(RefreshResult.Failure(RefreshError.Invalidated), system.manager.refresh())
    }

    @Test
    fun `cancel before publication retains memory and disk despite source suppression`() = runTest {
        for (suppress in listOf(false, true)) {
            val storage = MemoryConfigurationCache()
            var pause = false
            val response = CompletableDeferred<String>()
            val remote = CountingSource {
                if (!pause) {
                    "first"
                } else if (suppress) {
                    withContext(NonCancellable) { response.await() }
                } else {
                    response.await()
                }
            }
            val system = cachedSystem(storage, remote)
            system.manager.refresh()
            val previous = system.reader.readiness.value
            pause = true
            val refresh = async { system.manager.refresh() }
            testScheduler.runCurrent()
            refresh.cancel()
            response.complete("second")
            refresh.join()
            assertTrue(refresh.isCancelled)
            assertEquals(previous, system.reader.readiness.value)
            assertEquals("first", storage.records[CACHE_SCOPE]!!.document)
            assertEquals(1, storage.writes)
            assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
        }
    }

    @Test
    fun `cancel after publication preserves new memory and old durable record`() = runTest {
        val storage = MemoryConfigurationCache()
        storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "first")
        storage.beforeWrite = { awaitCancellation() }
        val system = cachedSystem(storage)
        val refresh = async { system.manager.refresh() }
        testScheduler.runCurrent()
        assertTrue(system.reader.get(Feature.boolean("second")))
        refresh.cancelAndJoin()
        assertTrue(system.reader.get(Feature.boolean("second")))
        assertEquals("first", storage.records[CACHE_SCOPE]!!.document)
        assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
    }

    @Test
    fun `restore and refresh serialize and cancelled waiter does not change running refresh`() = runTest {
        val storage = MemoryConfigurationCache()
        storage.records[CACHE_SCOPE] = CacheRecord(CACHE_SCOPE, 100, "first")
        val response = CompletableDeferred<String>()
        val system = cachedSystem(storage, CountingSource { response.await() })
        val refresh = async { system.manager.refresh() }
        testScheduler.runCurrent()
        val cancelledRestore = async { requireNotNull(system.cache).restore() }
        testScheduler.runCurrent()
        cancelledRestore.cancelAndJoin()
        assertEquals(0, storage.reads)
        assertEquals(RefreshStatus.Running, system.manager.refreshStatus.value)
        val restore = async { requireNotNull(system.cache).restore() }
        testScheduler.runCurrent()
        assertFalse(restore.isCompleted)
        response.complete("second")
        assertIs<RefreshResult.Applied>(refresh.await())
        val restored = assertIs<CacheRestoreResult.Applied>(restore.await()).result
        assertEquals(2, restored.revision)
        assertEquals(ConfigOrigin.CACHE, restored.origin)
        assertTrue(system.reader.get(Feature.boolean("second")))
    }

    @Test
    fun `cancel inside codec prevents publication and cache write`() = runTest {
        val storage = MemoryConfigurationCache()
        val operation = async {
            val job = currentCoroutineContext().job
            val codec = ConfigurationCodec {
                job.cancel(CancellationException("cancel decode"))
                loaderCodec.decode(it)
            }
            cachedSystem(storage, codec = codec).manager.refresh()
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(0, storage.writes)
    }
}
