package io.github.avtobot22.featureflags

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(30)
class SnapshotConcurrencyTest {
    @Test
    fun `get keeps enabled and parameters from one snapshot across publication`() {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        val evaluating = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        store.replaceAll(listOf(toggle(2)), ConfigOrigin.REMOTE)
        val definition = object : FlagDefinition<FeatureValue<ReaderParams>> {
            override val key: String = "flag"
            override fun evaluate(input: EvaluationInput): FeatureValue<ReaderParams> {
                evaluating.countDown()
                assertTrue(proceed.await(5, TimeUnit.SECONDS))
                return FeatureValue(input.enabled, ReaderParams(input.parameters.int("generation", -1)))
            }
        }
        try {
            val reading = executor.submit<FeatureValue<ReaderParams>> { reader.get(definition) }
            assertTrue(evaluating.await(5, TimeUnit.SECONDS))
            store.replaceAll(listOf(toggle(3)), ConfigOrigin.REMOTE)
            proceed.countDown()

            assertEquals(FeatureValue(true, ReaderParams(2)), reading.get(5, TimeUnit.SECONDS))
            assertEquals(FeatureValue(false, ReaderParams(3)), reader.get(configuredFlag()))
        } finally {
            proceed.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent writers allocate every revision once without partial snapshots`() {
        val store = ActiveSnapshotStore()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val writers = (0 until 4).map { writer ->
                executor.submit<List<Long>> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    (0 until 250).map { sequence ->
                        val generation = writer * 250 + sequence
                        val snapshot = store.replaceAll(
                            listOf(toggle(generation), toggle(generation, "other")),
                            ConfigOrigin.REMOTE,
                        )
                        assertEquals(setOf("flag", "other"), snapshot.configuration.rawFlags.keys)
                        val flags = snapshot.configuration.rawFlags
                        assertEquals(generation, flags.getValue("flag").parameters.int("generation", -1))
                        assertEquals(generation, flags.getValue("other").parameters.int("generation", -1))
                        snapshot.revision
                    }
                }
            }
            start.countDown()
            val revisions = writers.flatMap { it.get(10, TimeUnit.SECONDS) }

            assertEquals((1L..1000L).toList(), revisions.sorted())
            assertEquals(1000L, store.snapshots.value?.revision)
            assertEquals(Readiness.Ready(1000, ConfigOrigin.REMOTE), store.readiness.value)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `thousands of reads during full replacements preserve result and snapshot integrity`() {
        val store = ActiveSnapshotStore()
        val reader = SnapshotFeatureFlagsReader(store)
        store.replaceAll(listOf(toggle(0), toggle(0, "other")), ConfigOrigin.REMOTE)
        val executor = Executors.newFixedThreadPool(4)
        val boundary = CyclicBarrier(4)
        try {
            val tasks = (0 until 4).map { participant ->
                executor.submit {
                    repeat(1000) { iteration ->
                        boundary.await(5, TimeUnit.SECONDS)
                        if (participant < 2) {
                            val generation = participant * 1000 + iteration
                            store.replaceAll(
                                listOf(toggle(generation), toggle(generation, "other")),
                                ConfigOrigin.REMOTE,
                            )
                        } else {
                            repeat(4) {
                                val value = reader.get(configuredFlag())
                                assertEquals(value.params.generation % 2 == 0, value.enabled)
                                val snapshot = requireNotNull(store.snapshots.value)
                                assertEquals(setOf("flag", "other"), snapshot.configuration.rawFlags.keys)
                                val first = snapshot.configuration.rawFlags.getValue("flag")
                                val second = snapshot.configuration.rawFlags.getValue("other")
                                assertEquals(first.enabled, second.enabled)
                                assertEquals(
                                    first.parameters.int("generation", -1),
                                    second.parameters.int("generation", -1),
                                )
                            }
                        }
                        boundary.await(5, TimeUnit.SECONDS)
                    }
                }
            }
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(2001L, store.snapshots.value?.revision)
        } finally {
            executor.shutdownNow()
        }
    }
}
