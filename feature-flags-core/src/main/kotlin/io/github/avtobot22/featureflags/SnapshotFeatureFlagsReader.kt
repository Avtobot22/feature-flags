package io.github.avtobot22.featureflags

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class SnapshotFeatureFlagsReader(
    private val store: ActiveSnapshotStore,
    private val context: EvaluationContext = EvaluationContext(),
) : FeatureFlagsReader {
    override val readiness: StateFlow<Readiness> = store.readiness

    override fun <T> get(definition: FlagDefinition<T>): T {
        val snapshot = store.snapshots.value
        return snapshot.evaluate(definition, context)
    }

    override fun <T> observe(definition: FlagDefinition<T>): Flow<T> = store.snapshots
        .map { it.evaluate(definition, context) }
        .distinctUntilChanged()

    override fun withContext(context: EvaluationContext): FeatureFlagsReader =
        SnapshotFeatureFlagsReader(store, context)
}

private fun <T> ActiveSnapshot?.evaluate(definition: FlagDefinition<T>, context: EvaluationContext): T =
    if (this == null) {
        definition.evaluate(SnapshotEvaluationInput(null))
    } else {
        configuration.evaluate(definition, context)
    }
