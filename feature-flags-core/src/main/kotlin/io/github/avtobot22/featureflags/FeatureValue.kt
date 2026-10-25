package io.github.avtobot22.featureflags

public data class FeatureValue<out P>(
    public val enabled: Boolean,
    public val params: P,
)
