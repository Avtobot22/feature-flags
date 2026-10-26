package io.github.avtobot22.featureflags

public fun interface CacheClock {
    public fun nowEpochMillis(): Long
}

public enum class CacheMode {
    NETWORK_FIRST,
    CACHE_FIRST,
    REMOTE_ONLY,
}

/**
 * Scope must include environment, endpoint/codec version and every context affecting the response.
 * Stale use is allowed only up to maxAgeMillis; future timestamps are rejected after clock rollback.
 */
public class CacheSettings(
    public val storage: ConfigurationCache,
    public val scope: String,
    public val ttlMillis: Long,
    public val maxAgeMillis: Long = ttlMillis,
    public val mode: CacheMode = CacheMode.NETWORK_FIRST,
    public val clock: CacheClock = CacheClock(System::currentTimeMillis),
) {
    init {
        require(scope.isNotBlank()) { "Cache scope must not be blank." }
        require(ttlMillis >= 0 && maxAgeMillis >= ttlMillis) { "Expected 0 <= TTL <= maximum cache age." }
    }
}
