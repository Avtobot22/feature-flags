package io.github.avtobot22.featureflags

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class SnapshotFeatureFlagsReader(private val store: ActiveSnapshotStore) : FeatureFlagsReader {
    override val readiness: StateFlow<Readiness> = store.readiness

    override fun <T> get(definition: FlagDefinition<T>): T {
        val snapshot = store.snapshots.value
        return definition.evaluate(snapshot.toInput(definition.key))
    }

    override fun <T> observe(definition: FlagDefinition<T>): Flow<T> = store.snapshots
        .map { definition.evaluate(it.toInput(definition.key)) }
        .distinctUntilChanged()
}

private fun ActiveSnapshot?.toInput(key: String): EvaluationInput {
    val toggle = this?.flags?.get(key)
    return SnapshotEvaluationInput(toggle)
}

private class SnapshotEvaluationInput(toggle: RawToggle?) : EvaluationInput {
    override val present: Boolean = toggle != null
    override val enabled: Boolean = toggle?.enabled ?: false
    override val parameters: ParameterReader = toggle?.parameters ?: ImmutableParameters.EMPTY
}
