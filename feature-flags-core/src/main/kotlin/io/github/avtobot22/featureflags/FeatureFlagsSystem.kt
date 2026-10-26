package io.github.avtobot22.featureflags

/** Connects an independent in-memory reader and manager without loading data or launching coroutines. */
public class FeatureFlagsSystem(
    remote: FeatureFlagsSource,
    codec: ConfigurationCodec,
    fallback: FallbackPolicy,
    local: FeatureFlagsSource? = null,
) {
    public val reader: FeatureFlagsReader
    public val manager: FeatureFlagsManager

    init {
        require(fallback != FallbackPolicy.OnRemoteEmpty || local != null) {
            "OnRemoteEmpty requires a local source."
        }
        val store = ActiveSnapshotStore()
        reader = SnapshotFeatureFlagsReader(store)
        manager = DefaultFeatureFlagsManager(remote, codec, fallback, local, store)
    }
}
