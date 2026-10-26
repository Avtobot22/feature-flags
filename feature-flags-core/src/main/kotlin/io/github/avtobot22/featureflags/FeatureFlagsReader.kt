package io.github.avtobot22.featureflags

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface FeatureFlagsReader {
    /**
     * Evaluates one snapshot synchronously without I/O; separate calls are not a transaction.
     * Missing flags use definition defaults. Evaluation exceptions propagate unchanged.
     */
    public fun <T> get(definition: FlagDefinition<T>): T

    /**
     * Emits the current value and subsequent unequal results using equals. Intermediate snapshots may be conflated.
     * Collection performs no loading; evaluation exceptions propagate to that collector.
     */
    public fun <T> observe(definition: FlagDefinition<T>): Flow<T>

    public val readiness: StateFlow<Readiness>
}
