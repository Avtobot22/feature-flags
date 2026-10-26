package io.github.avtobot22.featureflags

public fun interface FeatureFlagsSource {
    /** Loads a complete document without publishing it; implementations own any blocking I/O dispatch. */
    public suspend fun loadJson(): String
}
