package io.github.avtobot22.featureflags

import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ActiveSnapshotStoreTest {
    @Test
    fun `empty first publication initializes readiness without collectors`() {
        val store = ActiveSnapshotStore()
        assertNull(store.snapshots.value)
        assertEquals(Readiness.NotInitialized, store.readiness.value)
        assertEquals(listOf(Readiness.NotInitialized), store.readiness.replayCache)

        val snapshot = store.replaceAll(emptyList(), ConfigOrigin.REMOTE)

        assertEquals(1L, snapshot.revision)
        assertEquals(emptyMap(), snapshot.flags)
        assertSame(snapshot, store.snapshots.value)
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), store.readiness.value)
        assertEquals(listOf(store.readiness.value), store.readiness.replayCache)
    }

    @Test
    fun `full replacement removes previous keys and retains unknown case-sensitive keys`() {
        val store = ActiveSnapshotStore()
        store.replaceAll(listOf(RawToggle("old", true)), ConfigOrigin.REMOTE)

        val snapshot = store.replaceAll(
            listOf(RawToggle("new", true), RawToggle("New", false)),
            ConfigOrigin.BUNDLED_ASSET,
        )

        assertEquals(setOf("new", "New"), snapshot.flags.keys)
        assertEquals(ConfigOrigin.BUNDLED_ASSET, snapshot.origin)
        assertEquals(2L, snapshot.revision)
    }

    @Test
    fun `duplicate rejection retains snapshot metadata and readiness`() {
        val store = ActiveSnapshotStore()
        val previous = store.replaceAll(listOf(RawToggle("flag", true)), ConfigOrigin.REMOTE)
        val readiness = store.readiness.value

        assertFailsWith<IllegalArgumentException> {
            store.replaceAll(listOf(RawToggle("flag", false), RawToggle("flag", true)), ConfigOrigin.BUNDLED_ASSET)
        }

        assertSame(previous, store.snapshots.value)
        assertEquals(readiness, store.readiness.value)
    }

    @Test
    fun `duplicate first publication leaves store uninitialized`() {
        val store = ActiveSnapshotStore()

        assertFailsWith<IllegalArgumentException> {
            store.replaceAll(listOf(RawToggle("flag", true), RawToggle("flag", false)), ConfigOrigin.REMOTE)
        }

        assertNull(store.snapshots.value)
        assertEquals(Readiness.NotInitialized, store.readiness.value)
    }

    @Test
    fun `equal publications advance revision and can change origin`() {
        val store = ActiveSnapshotStore()
        val data = listOf(RawToggle("flag", true))
        store.replaceAll(data, ConfigOrigin.REMOTE)
        val second = store.replaceAll(data, ConfigOrigin.REMOTE)
        val third = store.replaceAll(data, ConfigOrigin.BUNDLED_ASSET)

        assertEquals(2L, second.revision)
        assertEquals(3L, third.revision)
        assertEquals(Readiness.Ready(3, ConfigOrigin.BUNDLED_ASSET), store.readiness.value)
    }

    @Test
    fun `publication copies mutable input map values and collection`() {
        val store = ActiveSnapshotStore()
        val input = linkedMapOf("flag" to RawToggle("flag", true))
        val first = store.replaceAll(input.values, ConfigOrigin.REMOTE)
        input["flag"] = RawToggle("flag", false)
        input["added"] = RawToggle("added", true)
        input.clear()

        assertEquals(mapOf("flag" to RawToggle("flag", true)), first.flags)
        val list = mutableListOf(RawToggle("other", false))
        val second = store.replaceAll(list, ConfigOrigin.REMOTE)
        list.clear()
        assertEquals(setOf("other"), second.flags.keys)
        assertEquals(setOf("flag"), first.flags.keys)
    }

    @Test
    fun `published map rejects mutation even through mutable cast`() {
        val snapshot = ActiveSnapshotStore().replaceAll(listOf(RawToggle("flag", true)), ConfigOrigin.REMOTE)

        assertFailsWith<UnsupportedOperationException> {
            (snapshot.flags as MutableMap)["injected"] = RawToggle("injected", true)
        }
    }

    @Test
    fun `preparation cancellation preserves previous snapshot`() {
        val store = ActiveSnapshotStore()
        val previous = store.replaceAll(listOf(RawToggle("flag", true)), ConfigOrigin.REMOTE)
        val cancellation = CancellationException("Preparation cancelled")
        val interrupted = object : AbstractCollection<RawToggle>() {
            override val size: Int = 2
            override fun iterator(): Iterator<RawToggle> = throw cancellation
        }

        val actual = assertFailsWith<CancellationException> {
            store.replaceAll(interrupted, ConfigOrigin.BUNDLED_ASSET)
        }
        assertSame(cancellation, actual)
        assertSame(previous, store.snapshots.value)
        assertEquals(Readiness.Ready(1, ConfigOrigin.REMOTE), store.readiness.value)
    }
}
