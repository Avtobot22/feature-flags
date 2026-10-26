package io.github.avtobot22.featureflags

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ActiveSnapshotStore {
    private val mutableSnapshots = MutableStateFlow<ActiveSnapshot?>(null)
    val snapshots: StateFlow<ActiveSnapshot?> = mutableSnapshots.asStateFlow()
    val readiness: StateFlow<Readiness> = SnapshotReadiness(snapshots)

    fun replaceAll(toggles: Collection<RawToggle>, origin: ConfigOrigin): ActiveSnapshot {
        return replaceAll(RawConfiguration(toggles), origin)
    }

    fun replaceAll(configuration: Configuration, origin: ConfigOrigin): ActiveSnapshot {
        while (true) {
            val previous = mutableSnapshots.value
            val revision = Math.addExact(previous?.revision ?: 0L, 1L)
            val next = ActiveSnapshot(revision, origin, configuration)
            if (mutableSnapshots.compareAndSet(previous, next)) return next
        }
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class SnapshotReadiness(private val snapshots: StateFlow<ActiveSnapshot?>) : StateFlow<Readiness> {
    override val value: Readiness
        get() = snapshots.value.toReadiness()

    override val replayCache: List<Readiness>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Readiness>): Nothing = snapshots.collect {
        collector.emit(it.toReadiness())
    }
}

private fun ActiveSnapshot?.toReadiness(): Readiness =
    if (this == null) Readiness.NotInitialized else Readiness.Ready(revision, origin)
