package io.github.avtobot22.featureflags

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureFlagsObservationTest {
    @Test
    fun `observe emits default then unequal values and filters unrelated publications`() = runTest {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        val values = mutableListOf<FeatureValue<ReaderParams>>()
        val collector = backgroundScope.launch { reader.observe(configuredFlag()).toList(values) }
        testScheduler.runCurrent()
        assertEquals(listOf(FeatureValue(false, ReaderParams(-1))), values)

        store.replaceAll(listOf(toggle(2)), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()
        store.replaceAll(listOf(toggle(2), toggle(7, "unrelated")), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()
        store.replaceAll(listOf(toggle(2), toggle(7, "unrelated")), ConfigOrigin.BUNDLED_ASSET)
        testScheduler.runCurrent()
        assertEquals(2, values.size)
        assertEquals(Readiness.Ready(3, ConfigOrigin.BUNDLED_ASSET), reader.readiness.value)

        store.replaceAll(listOf(toggle(3)), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()
        assertEquals(FeatureValue(false, ReaderParams(3)), values.last())
        assertEquals(3, values.size)
        collector.cancelAndJoin()
    }

    @Test
    fun `new subscription starts with current value after publication`() = runTest {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        store.replaceAll(listOf(toggle(4)), ConfigOrigin.REMOTE)

        assertEquals(FeatureValue(true, ReaderParams(4)), reader.observe(configuredFlag()).first())
    }

    @Test
    fun `collectors are independent and cancellation does not alter active state`() = runTest {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        val cancelled = backgroundScope.launch { reader.observe(Feature.boolean("flag")).toList(first) }
        backgroundScope.launch { reader.observe(Feature.boolean("flag")).toList(second) }
        testScheduler.runCurrent()
        store.replaceAll(listOf(toggle(2)), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()
        val snapshot = store.snapshots.value
        cancelled.cancelAndJoin()
        assertSame(snapshot, store.snapshots.value)
        assertTrue(reader.get(Feature.boolean("flag")))

        store.replaceAll(listOf(toggle(3)), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()

        assertEquals(listOf(false, true), first)
        assertEquals(listOf(false, true, false), second)
        assertEquals(Readiness.Ready(2, ConfigOrigin.REMOTE), reader.readiness.value)
    }

    @Test
    fun `decoder failure terminates only its collector and preserves store`() = runTest {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        val failure = IllegalStateException("Decoder failed")
        val definition = Feature.config("flag", defaultParams = Unit) { _, _ -> throw failure }
        val healthy = mutableListOf<Boolean>()
        var actual: Throwable? = null
        val broken = backgroundScope.launch {
            reader.observe(definition).catch { actual = it }.collect {}
        }
        backgroundScope.launch { reader.observe(Feature.boolean("flag")).toList(healthy) }
        testScheduler.runCurrent()

        store.replaceAll(listOf(toggle(2)), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()
        assertSame(failure, actual)
        assertTrue(broken.isCompleted)
        store.replaceAll(listOf(toggle(3)), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()

        assertEquals(listOf(false, true, false), healthy)
        assertEquals(Readiness.Ready(2, ConfigOrigin.REMOTE), reader.readiness.value)
    }

    @Test
    fun `decoder cancellation propagates from observation unchanged`() = runTest {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        store.replaceAll(listOf(toggle(2)), ConfigOrigin.REMOTE)
        val cancellation = CancellationException("Decoder cancelled")
        val definition = Feature.config("flag", defaultParams = Unit) { _, _ -> throw cancellation }

        assertSame(cancellation, assertFailsWith<CancellationException> { reader.observe(definition).first() })
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), reader.readiness.value)
    }

    @Test
    fun `readiness replays metadata and remains correct after collector cancellation`() = runTest {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        val states = mutableListOf<Readiness>()
        val collector = backgroundScope.launch { reader.readiness.toList(states) }
        testScheduler.runCurrent()
        store.replaceAll(emptyList(), ConfigOrigin.REMOTE)
        testScheduler.runCurrent()
        store.replaceAll(emptyList(), ConfigOrigin.BUNDLED_ASSET)
        testScheduler.runCurrent()
        collector.cancelAndJoin()
        store.replaceAll(emptyList(), ConfigOrigin.REMOTE)

        assertEquals(
            listOf(
                Readiness.NotInitialized,
                Readiness.Ready(1, ConfigOrigin.REMOTE),
                Readiness.Ready(2, ConfigOrigin.BUNDLED_ASSET),
            ),
            states,
        )
        assertEquals(Readiness.Ready(3, ConfigOrigin.REMOTE), reader.readiness.value)
        assertEquals(listOf(reader.readiness.value), reader.readiness.replayCache)
        assertEquals(reader.readiness.value, reader.readiness.first())
    }

    @Test
    fun `slow observer may skip intermediate snapshots but receives latest value`() = runTest {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        val values = mutableListOf<FeatureValue<ReaderParams>>()
        backgroundScope.launch { reader.observe(configuredFlag()).toList(values) }
        testScheduler.runCurrent()

        repeat(10) { store.replaceAll(listOf(toggle(it)), ConfigOrigin.REMOTE) }
        testScheduler.runCurrent()

        assertEquals(FeatureValue(false, ReaderParams(9)), values.last())
        assertEquals(Readiness.Ready(10, ConfigOrigin.REMOTE), reader.readiness.value)
    }
}
