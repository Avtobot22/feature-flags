package io.github.avtobot22.featureflags

import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotFeatureFlagsReaderTest {
    @Test
    fun `before initialization definitions return explicit defaults without decoding`() {
        val reader: FeatureFlagsReader = SnapshotFeatureFlagsReader(ActiveSnapshotStore())
        val defaults = ReaderParams(12)
        val definition = Feature.config("flag", defaultEnabled = true, defaultParams = defaults) { _, _ ->
            error("Absent flag must not decode")
        }

        assertFalse(reader.get(Feature.boolean("flag")))
        assertTrue(reader.get(Feature.boolean("flag", default = true)))
        assertTrue(reader.get(definition).enabled)
        assertSame(defaults, reader.get(definition).params)
    }

    @Test
    fun `removed configured flag returns original defaults without decoding`() {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        val defaults = ReaderParams(12)
        var calls = 0
        val definition = Feature.config("flag", defaultEnabled = true, defaultParams = defaults) { _, _ ->
            calls++
            ReaderParams(20)
        }
        store.replaceAll(listOf(toggle(2)), ConfigOrigin.REMOTE)
        assertEquals(ReaderParams(20), reader.get(definition).params)
        store.replaceAll(emptyList(), ConfigOrigin.BUNDLED_ASSET)

        assertSame(defaults, reader.get(definition).params)
        assertTrue(reader.get(definition).enabled)
        assertEquals(1, calls)
    }

    @Test
    fun `disabled configured flag retains decoded parameters`() {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        store.replaceAll(listOf(toggle(5)), ConfigOrigin.REMOTE)

        assertEquals(FeatureValue(false, ReaderParams(5)), reader.get(configuredFlag()))
    }

    @Test
    fun `new definitions can read unknown keys using exact case`() {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        store.replaceAll(listOf(toggle(2, "new"), toggle(3, "New")), ConfigOrigin.REMOTE)

        assertEquals(FeatureValue(true, ReaderParams(2)), reader.get(configuredFlag("new")))
        assertEquals(FeatureValue(false, ReaderParams(3)), reader.get(configuredFlag("New")))
        assertEquals(FeatureValue(false, ReaderParams(-1)), reader.get(configuredFlag("NEW")))
    }

    @Test
    fun `get propagates decoder failure and cancellation without changing readiness`() {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        store.replaceAll(listOf(toggle(2)), ConfigOrigin.REMOTE)
        val failure = IllegalStateException("Decoder failed")
        val cancellation = CancellationException("Cancelled")
        val broken = Feature.config("flag", defaultParams = Unit) { _, _ -> throw failure }
        val cancelled = Feature.config("flag", defaultParams = Unit) { _, _ -> throw cancellation }

        assertSame(failure, assertFailsWith<IllegalStateException> { reader.get(broken) })
        assertSame(cancellation, assertFailsWith<CancellationException> { reader.get(cancelled) })
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), reader.readiness.value)
        assertTrue(reader.get(Feature.boolean("flag")))
    }

    @Test
    fun `missing parameters provide defaults and retain bounds validation`() {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        store.replaceAll(listOf(RawToggle("flag", true)), ConfigOrigin.REMOTE)
        val definition = Feature.config("flag", defaultParams = ReaderParams(9)) { parameters, defaults ->
            assertEquals("default", parameters.string("name", "default"))
            assertTrue(parameters.boolean("switch", true))
            assertFailsWith<IllegalArgumentException> { parameters.int("invalid", 1, min = 2, max = 1) }
            ReaderParams(parameters.int("generation", defaults.generation, min = 20))
        }

        assertEquals(FeatureValue(true, ReaderParams(9)), reader.get(definition))
    }
}
