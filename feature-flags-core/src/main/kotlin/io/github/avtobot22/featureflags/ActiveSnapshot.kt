package io.github.avtobot22.featureflags

internal data class RawToggle(
    val key: String,
    val enabled: Boolean,
    val parameters: ImmutableParameters = ImmutableParameters.EMPTY,
)

internal data class ActiveSnapshot(
    val revision: Long,
    val origin: ConfigOrigin,
    val flags: Map<String, RawToggle>,
)
