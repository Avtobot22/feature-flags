package io.github.avtobot22.featureflags

internal class CountingSource(private val response: suspend () -> String) : FeatureFlagsSource {
    var calls: Int = 0
        private set

    override suspend fun loadJson(): String {
        calls++
        return response()
    }
}

internal val loaderCodec = ConfigurationCodec { json ->
    when (json) {
        "empty" -> RawConfiguration(emptyList())
        "first" -> RawConfiguration(listOf(RawToggle("first", true)))
        "second" -> RawConfiguration(listOf(RawToggle("second", true)))
        else -> throw IllegalArgumentException("Invalid configuration.")
    }
}

internal fun loaderSystem(
    remote: FeatureFlagsSource,
    local: FeatureFlagsSource? = null,
    fallback: FallbackPolicy = FallbackPolicy.OnRemoteEmpty,
    codec: ConfigurationCodec = loaderCodec,
): FeatureFlagsSystem = FeatureFlagsSystem(remote, codec, fallback, local)
