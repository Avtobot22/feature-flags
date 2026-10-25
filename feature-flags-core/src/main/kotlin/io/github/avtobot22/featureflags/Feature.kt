package io.github.avtobot22.featureflags

public object Feature {
    public fun boolean(key: String, default: Boolean = false): FlagDefinition<Boolean> = BooleanFlag(key, default)

    /**
     * An absent flag returns defaults without invoking [decoder]; a disabled flag still decodes parameters.
     * [defaultParams] is shared across evaluations and must remain immutable.
     */
    public fun <P> config(
        key: String,
        defaultEnabled: Boolean = false,
        defaultParams: P,
        decoder: ParametersDecoder<P>,
    ): FlagDefinition<FeatureValue<P>> = ConfiguredFlag(key, defaultEnabled, defaultParams, decoder)
}

private class BooleanFlag(
    override val key: String,
    private val default: Boolean,
) : FlagDefinition<Boolean> {
    override fun evaluate(input: EvaluationInput): Boolean = if (input.present) input.enabled else default
}

private class ConfiguredFlag<P>(
    override val key: String,
    private val defaultEnabled: Boolean,
    private val defaultParams: P,
    private val decoder: ParametersDecoder<P>,
) : FlagDefinition<FeatureValue<P>> {
    override fun evaluate(input: EvaluationInput): FeatureValue<P> = if (input.present) {
        FeatureValue(input.enabled, decoder.decode(input.parameters, defaultParams))
    } else {
        FeatureValue(defaultEnabled, defaultParams)
    }
}
