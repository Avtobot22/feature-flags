package io.github.avtobot22.featureflags

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureFlagsLoadingTest {
    @Test
    fun `construction and observation perform no loading and systems own independent stores`() = runTest {
        val source = CountingSource { "first" }
        val first = loaderSystem(source, fallback = FallbackPolicy.Never)
        val second = loaderSystem(source, fallback = FallbackPolicy.Never)

        assertEquals(0, source.calls)
        assertEquals(Readiness.NotInitialized, first.reader.readiness.value)
        assertEquals(RefreshStatus.Idle, first.manager.refreshStatus.value)
        assertFalse(first.reader.observe(Feature.boolean("first")).first())
        assertEquals(0, source.calls)
        first.manager.refresh()
        assertTrue(first.reader.get(Feature.boolean("first")))
        assertFalse(second.reader.get(Feature.boolean("first")))
        assertEquals(Readiness.NotInitialized, second.reader.readiness.value)
    }

    @Test
    fun `on remote empty requires local source at construction`() {
        assertFailsWith<IllegalArgumentException> {
            loaderSystem(CountingSource { "first" })
        }
    }

    @Test
    fun `nonempty remote applies without calling local and advances revision for equal data`() = runTest {
        val remote = CountingSource { "first" }
        val local = CountingSource { error("Must not load local") }
        val system = loaderSystem(remote, local)

        val applied = system.manager.refresh()

        assertEquals(RefreshResult.Applied(1, ConfigOrigin.REMOTE, 1), applied)
        assertEquals(RefreshStatus.Finished(applied), system.manager.refreshStatus.value)
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
        assertTrue(system.reader.get(Feature.boolean("first")))
        assertEquals(0, local.calls)
        assertEquals(RefreshResult.Applied(2, ConfigOrigin.REMOTE, 1), system.manager.refresh())
        assertEquals(2, remote.calls)
    }

    @Test
    fun `valid remote empty loads nonempty local and empty local initializes readiness`() = runTest {
        for (json in listOf("first", "empty")) {
            val remote = CountingSource { "empty" }
            val local = CountingSource { json }
            val system = loaderSystem(remote, local)

            val count = if (json == "empty") 0 else 1
            assertEquals(RefreshResult.Applied(1, ConfigOrigin.BUNDLED_ASSET, count), system.manager.refresh())
            assertEquals(1, local.calls)
            assertEquals(Readiness.Ready(1, ConfigOrigin.BUNDLED_ASSET), system.reader.readiness.value)
            assertEquals(count == 1, system.reader.get(Feature.boolean("first")))
        }
    }

    @Test
    fun `never policy applies remote empty and removes old keys without reading local`() = runTest {
        var json = "first"
        val local = CountingSource { error("Must not load local") }
        val system = loaderSystem(CountingSource { json }, local, FallbackPolicy.Never)
        system.manager.refresh()
        json = "empty"

        assertEquals(RefreshResult.Applied(2, ConfigOrigin.REMOTE, 0), system.manager.refresh())
        assertFalse(system.reader.get(Feature.boolean("first")))
        assertEquals(0, local.calls)
    }

    @Test
    fun `remote failure preserves readiness and values and never invokes fallback`() = runTest {
        val failure = IOException("Transport failed")
        var fail = false
        val local = CountingSource { "second" }
        val remote = CountingSource { if (fail) throw failure else "first" }
        val system = loaderSystem(remote, local)
        system.manager.refresh()
        fail = true

        val result = assertIs<RefreshResult.Failure>(system.manager.refresh())

        assertSame(failure, assertIs<RefreshError.Transport>(result.error).cause)
        assertEquals(RefreshStatus.Finished(result), system.manager.refreshStatus.value)
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
        assertTrue(system.reader.get(Feature.boolean("first")))
        assertEquals(0, local.calls)
    }

    @Test
    fun `invalid remote is a configuration failure without fallback or first publication`() = runTest {
        val local = CountingSource { "first" }
        val system = loaderSystem(CountingSource { "invalid" }, local)

        val failure = assertIs<RefreshResult.Failure>(system.manager.refresh())
        val error = assertIs<RefreshError.InvalidConfiguration>(failure.error)

        assertEquals(ConfigOrigin.REMOTE, error.origin)
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        assertEquals(0, local.calls)
    }

    @Test
    fun `missing local and invalid local preserve previous snapshot and identify the failing boundary`() = runTest {
        val missing = IOException("Missing bundled source")
        for (invalid in listOf(false, true)) {
            var remoteJson = "first"
            val local = CountingSource { if (invalid) "invalid" else throw missing }
            val system = loaderSystem(CountingSource { remoteJson }, local)
            system.manager.refresh()
            remoteJson = "empty"

            val failure = assertIs<RefreshResult.Failure>(system.manager.refresh())

            if (invalid) {
                val error = assertIs<RefreshError.InvalidConfiguration>(failure.error)
                assertEquals(ConfigOrigin.BUNDLED_ASSET, error.origin)
            } else {
                assertSame(missing, assertIs<RefreshError.LocalSource>(failure.error).cause)
            }
            assertTrue(system.reader.get(Feature.boolean("first")))
            assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
        }
    }

    @Test
    fun `remote and local publications fully replace previous configuration`() = runTest {
        var json = "first"
        val system = loaderSystem(CountingSource { json }, CountingSource { "first" })
        system.manager.refresh()
        json = "second"
        system.manager.refresh()

        assertFalse(system.reader.get(Feature.boolean("first")))
        assertTrue(system.reader.get(Feature.boolean("second")))
        json = "empty"
        system.manager.refresh()
        assertTrue(system.reader.get(Feature.boolean("first")))
        assertFalse(system.reader.get(Feature.boolean("second")))
        assertEquals(Readiness.Ready(3, ConfigOrigin.BUNDLED_ASSET), system.reader.readiness.value)
    }

    @Test
    fun `running status leaves old configuration readable without blocking`() = runTest {
        val response = CompletableDeferred<String>()
        var pause = false
        val remote = CountingSource { if (pause) response.await() else "first" }
        val system = loaderSystem(remote, fallback = FallbackPolicy.Never)
        system.manager.refresh()
        pause = true
        val running = async { system.manager.refresh() }
        testScheduler.runCurrent()

        assertEquals(RefreshStatus.Running, system.manager.refreshStatus.value)
        assertTrue(system.reader.get(Feature.boolean("first")))
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), system.reader.readiness.value)
        response.complete("second")
        assertEquals(RefreshResult.Applied(2, ConfigOrigin.REMOTE, 1), running.await())
    }

    @Test
    fun `inconsistent configuration size fails before fallback or publication`() = runTest {
        for (count in listOf(-1, 1)) {
            val configuration = object : Configuration {
                override val flagCount: Int = count
                override val isEmpty: Boolean = true
                override fun <T> evaluate(definition: FlagDefinition<T>): T = error("Must not evaluate")
            }
            val local = CountingSource { "first" }
            val system = loaderSystem(
                CountingSource { "custom" },
                local,
                codec = ConfigurationCodec { configuration },
            )

            assertFailsWith<IllegalArgumentException> { system.manager.refresh() }
            assertEquals(0, local.calls)
            assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
            assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
        }
    }

    @Test
    fun `codec programming error propagates resets status and releases mutex for retry`() = runTest {
        val failure = IllegalStateException("Codec bug")
        var broken = true
        val codec = ConfigurationCodec { if (broken) throw failure else loaderCodec.decode(it) }
        val system = loaderSystem(CountingSource { "first" }, fallback = FallbackPolicy.Never, codec = codec)

        assertSame(failure, assertFailsWith<IllegalStateException> { system.manager.refresh() })
        assertEquals(Readiness.NotInitialized, system.reader.readiness.value)
        assertEquals(RefreshStatus.Idle, system.manager.refreshStatus.value)
        broken = false
        assertIs<RefreshResult.Applied>(system.manager.refresh())
    }
}
