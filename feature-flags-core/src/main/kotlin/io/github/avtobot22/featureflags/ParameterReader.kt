package io.github.avtobot22.featureflags

public interface ParameterReader {
    /**
     * Missing, null, or invalid values return [default] unchanged; valid numbers clamp to inclusive [min]/[max].
     * JSON integers exclude strings, fractions, exponents, and values outside Int.
     *
     * @throws IllegalArgumentException if both bounds are set and [min] is greater than [max].
     */
    public fun int(name: String, default: Int, min: Int? = null, max: Int? = null): Int

    public fun boolean(name: String, default: Boolean): Boolean

    public fun string(name: String, default: String): String
}
