package io.github.avtobot22.featureflags

internal class ImmutableParameters private constructor(reader: ParameterReader) : ParameterReader by reader {
    companion object {
        val EMPTY: ImmutableParameters = ImmutableParameters(EmptyParameters)

        fun trusted(reader: ParameterReader): ImmutableParameters = ImmutableParameters(reader)
    }
}

private object EmptyParameters : ParameterReader {
    override fun int(name: String, default: Int, min: Int?, max: Int?): Int {
        require(min == null || max == null || min <= max) { "Minimum must not exceed maximum." }
        return default
    }

    override fun boolean(name: String, default: Boolean): Boolean = default

    override fun string(name: String, default: String): String = default
}
