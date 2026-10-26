package io.github.avtobot22.featureflags

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureFlagsRefreshConcurrencyTest {
    @Test
    fun `concurrent refreshes serialize remote decode local and publish`() = runTest {
        val remoteResponse = CompletableDeferred<String>()
        val localResponse = CompletableDeferred<String>()
        val events = mutableListOf<String>()
        var remoteCalls = 0
        val remote = CountingSource {
            remoteCalls++
            events += "remote$remoteCalls"
            if (remoteCalls == 1) remoteResponse.await() else "second"
        }
        val local = CountingSource {
            events += "local"
            localResponse.await()
        }
        val codec = ConfigurationCodec {
            events += "decode:$it"
            loaderCodec.decode(it)
        }
        val system = loaderSystem(remote, local, codec = codec)
        val first = async { system.manager.refresh() }
        val second = async { system.manager.refresh() }
        testScheduler.runCurrent()
        assertEquals(listOf("remote1"), events)
        remoteResponse.complete("empty")
        testScheduler.runCurrent()
        assertEquals(listOf("remote1", "decode:empty", "local"), events)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        localResponse.complete("first")

        assertEquals(RefreshResult.Applied(1, ConfigOrigin.BUNDLED_ASSET, 1), first.await())
        assertEquals(RefreshResult.Applied(2, ConfigOrigin.REMOTE, 1), second.await())
        assertEquals(listOf("remote1", "decode:empty", "local", "decode:first", "remote2", "decode:second"), events)
        assertTrue(system.reader.get(Feature.boolean("second")))
    }

    @Test
    fun `cancelling remote and local loads retains previous snapshot and allows retry`() = runTest {
        for (cancelLocal in listOf(false, true)) {
            var pause = false
            var remoteCalls = 0
            val remote = CountingSource {
                remoteCalls++
                when {
                    !pause -> "first"
                    cancelLocal -> "empty"
                    else -> awaitCancellation()
                }
            }
            val local = CountingSource { awaitCancellation() }
            val system = loaderSystem(remote, local)
            system.manager.refresh()
            pause = true
            val attempt = async { system.manager.refresh() }
            testScheduler.runCurrent()
            assertEquals(RefreshStatus.Running, system.manager.refreshStatus.value)

            attempt.cancelAndJoin()

            assertTrue(attempt.isCancelled)
            assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
            assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
            assertTrue(system.reader.get(Feature.boolean("first")))
            assertEquals(if (cancelLocal) 1 else 0, local.calls)
            pause = false
            assertEquals(RefreshResult.Applied(2, ConfigOrigin.REMOTE, 1), system.manager.refresh())
            assertEquals(3, remoteCalls)
        }
    }

    @Test
    fun `cancelled waiter never calls source or resets running status`() = runTest {
        val response = CompletableDeferred<String>()
        val remote = CountingSource { response.await() }
        val system = loaderSystem(remote, fallback = FallbackPolicy.Never)
        val first = async { system.manager.refresh() }
        val waiting = async { system.manager.refresh() }
        testScheduler.runCurrent()

        waiting.cancelAndJoin()

        assertEquals(RefreshStatus.Running, system.manager.refreshStatus.value)
        assertEquals(1, remote.calls)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        response.complete("first")
        assertIs<RefreshResult.Applied>(first.await())
        assertEquals(1, remote.calls)
    }

    @Test
    fun `source and codec cancellation exceptions propagate unchanged`() = runTest {
        val cancellation = CancellationException("Cancelled boundary")
        for (fromCodec in listOf(false, true)) {
            val remote = CountingSource { if (fromCodec) "first" else throw cancellation }
            val codec = ConfigurationCodec { throw cancellation }
            val system = loaderSystem(remote, fallback = FallbackPolicy.Never, codec = codec)

            assertSame(cancellation, assertFailsWith<CancellationException> { system.manager.refresh() })
            assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
            assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        }
    }

    @Test
    fun `source suppressing cancellation cannot publish or decode its returned document`() = runTest {
        var decoded = false
        val codec = ConfigurationCodec {
            decoded = true
            loaderCodec.decode(it)
        }
        val remote = CountingSource {
            currentCoroutineContext().cancel()
            "first"
        }
        val system = loaderSystem(remote, fallback = FallbackPolicy.Never, codec = codec)
        val attempt = launch { system.manager.refresh() }
        attempt.join()

        assertTrue(attempt.isCancelled)
        assertEquals(false, decoded)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
    }

    @Test
    fun `cancellation during synchronous codec prevents commit`() = runTest {
        val remote = CountingSource { "first" }
        var cancelAttempt: () -> Unit = {}
        val system = loaderSystem(
            remote,
            fallback = FallbackPolicy.Never,
            codec = ConfigurationCodec {
                loaderCodec.decode(it).also { cancelAttempt() }
            },
        )
        val attempt = launch {
            val context = currentCoroutineContext()
            cancelAttempt = { context.cancel() }
            system.manager.refresh()
        }
        attempt.join()

        assertTrue(attempt.isCancelled)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
    }
}
