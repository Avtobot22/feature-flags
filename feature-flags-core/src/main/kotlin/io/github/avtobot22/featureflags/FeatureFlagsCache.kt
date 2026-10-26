package io.github.avtobot22.featureflags

public interface FeatureFlagsCache {
    /** Restores a permitted record without loading remote or assets; does not change refreshStatus. */
    public suspend fun restore(): CacheRestoreResult

    /**
     * Permanently closes this system and immediately removes its active configuration.
     * In-flight requests cannot publish or save afterwards. Create a new system for the next context.
     */
    public suspend fun invalidate(): CacheInvalidationResult
}

public sealed interface CacheRestoreResult {
    public data class Applied(public val result: RefreshResult.Applied) : CacheRestoreResult
    public data class Unavailable(public val reason: CacheUnavailable) : CacheRestoreResult
}

public sealed interface CacheUnavailable {
    public data object Missing : CacheUnavailable
    public data object Expired : CacheUnavailable
    public data object ClockRollback : CacheUnavailable
    public data object Incompatible : CacheUnavailable
    public data object Invalidated : CacheUnavailable
    public data class Failure(public val cause: Exception) : CacheUnavailable
}

public sealed interface CacheInvalidationResult {
    public data object Cleared : CacheInvalidationResult
    public data class Failure(public val cause: Exception) : CacheInvalidationResult
}

public sealed interface CacheWriteResult {
    public data object NotRequested : CacheWriteResult
    public data object Saved : CacheWriteResult
    public data class Failure(public val cause: Exception) : CacheWriteResult
}

public enum class ConfigurationFreshness {
    FRESH,
    STALE,
}

/** Age and freshness describe publication time; no timer or background expiry changes the active snapshot. */
public data class ConfigurationAge(
    public val receivedAtEpochMillis: Long,
    public val ageMillis: Long,
    public val freshness: ConfigurationFreshness,
)
