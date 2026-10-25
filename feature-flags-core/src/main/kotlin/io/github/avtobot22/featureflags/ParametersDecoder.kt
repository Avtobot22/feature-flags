package io.github.avtobot22.featureflags

public fun interface ParametersDecoder<P> {
    /** Must not mutate [defaults]; exceptions propagate unchanged. */
    public fun decode(reader: ParameterReader, defaults: P): P
}
