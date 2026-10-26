package io.github.avtobot22.featureflags

/** Connects an independent in-memory reader and manager without loading data or launching coroutines. */
public class FeatureFlagsSystem(
    remote: FeatureFlagsSource,
    codec: ConfigurationCodec,
    fallback: FallbackPolicy,
    local: FeatureFlagsSource? = null,
    cache: CacheSettings? = null,
) {
    public val reader: FeatureFlagsReader
    public val manager: FeatureFlagsManager
    public val cache: FeatureFlagsCache?

    init {
        require(fallback != FallbackPolicy.OnRemoteEmpty || local != null) {
            "OnRemoteEmpty requires a local source."
        }
        val store = ActiveSnapshotStore()
        reader = SnapshotFeatureFlagsReader(store)
        val cachedManager = cache?.let { CachedFeatureFlagsManager(remote, codec, fallback, local, store, it) }
        manager = cachedManager ?: DefaultFeatureFlagsManager(remote, codec, fallback, local, store)
        this.cache = cachedManager
    }
}
