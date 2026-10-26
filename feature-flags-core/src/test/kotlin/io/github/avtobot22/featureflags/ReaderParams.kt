package io.github.avtobot22.featureflags

internal data class ReaderParams(val generation: Int)

internal fun configuredFlag(key: String = "flag"): FlagDefinition<FeatureValue<ReaderParams>> = Feature.config(
    key = key,
    defaultParams = ReaderParams(-1),
    decoder = ParametersDecoder { reader, defaults -> ReaderParams(reader.int("generation", defaults.generation)) },
)

internal fun toggle(generation: Int, key: String = "flag"): RawToggle = RawToggle(
    key = key,
    enabled = generation % 2 == 0,
    parameters = ImmutableParameters.trusted(GenerationParameters(generation)),
)

private class GenerationParameters(private val generation: Int) : ParameterReader {
    override fun int(name: String, default: Int, min: Int?, max: Int?): Int {
        require(min == null || max == null || min <= max)
        return if (name == "generation") generation.coerceIn(min ?: Int.MIN_VALUE, max ?: Int.MAX_VALUE) else default
    }

    override fun boolean(name: String, default: Boolean): Boolean = default

    override fun string(name: String, default: String): String = default
}

internal val Configuration.rawFlags: Map<String, RawToggle>
    get() = (this as RawConfiguration).flags
