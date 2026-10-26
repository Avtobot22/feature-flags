package io.github.avtobot22.featureflags

internal const val CACHE_SCOPE = "test-environment/endpoint-v1/context-a"

internal class MemoryConfigurationCache : ConfigurationCache {
    val records = mutableMapOf<String, CacheRecord>()
    var reads = 0
    var writes = 0
    var clears = 0
    var readFailure: Exception? = null
    var writeFailure: Exception? = null
    var clearFailure: Exception? = null
    var beforeWrite: suspend () -> Unit = {}
    var beforeClear: suspend () -> Unit = {}

    override suspend fun read(scope: String): CacheRecord? {
        reads++
        readFailure?.let { throw it }
        return records[scope]
    }

    override suspend fun write(record: CacheRecord) {
        writes++
        beforeWrite()
        writeFailure?.let { throw it }
        records[record.scope] = record
    }

    override suspend fun clear(scope: String) {
        clears++
        beforeClear()
        clearFailure?.let { throw it }
        records.remove(scope)
    }
}

@Suppress("LongParameterList")
internal fun cachedSystem(
    storage: ConfigurationCache,
    remote: FeatureFlagsSource = CountingSource { "second" },
    mode: CacheMode = CacheMode.NETWORK_FIRST,
    now: () -> Long = { 100 },
    scope: String = CACHE_SCOPE,
    fallback: FallbackPolicy = FallbackPolicy.Never,
    local: FeatureFlagsSource? = null,
    codec: ConfigurationCodec = loaderCodec,
    ttl: Long = 10,
    maxAge: Long = 30,
): FeatureFlagsSystem = FeatureFlagsSystem(
    remote,
    codec,
    fallback,
    local,
    CacheSettings(storage, scope, ttl, maxAge, mode, CacheClock(now)),
)
