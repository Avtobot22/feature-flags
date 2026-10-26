package io.github.avtobot22.featureflags

import kotlinx.coroutines.flow.StateFlow

public interface FeatureFlagsManager {
    /**
     * Serializes complete refresh attempts and publishes only a validated configuration.
     * Cancellation before publication retains the active snapshot; after commit the new snapshot stays active.
     */
    public suspend fun refresh(): RefreshResult

    /** Cancellation or an unexpected codec exception resets the running attempt to Idle. */
    public val refreshStatus: StateFlow<RefreshStatus>
}
