package io.github.avtobot22.featureflags

/** A complete, immutable configuration; evaluation must be thread-safe and perform no I/O. */
public interface Configuration {
    /** Nonnegative number of distinct flag keys; isEmpty must agree with this count. */
    public val flagCount: Int
    public val isEmpty: Boolean
        get() = flagCount == 0

    /** Uses defaults for an absent key and propagates definition and decoder exceptions. */
    public fun <T> evaluate(definition: FlagDefinition<T>): T

    /** Context-free implementations may retain this default; targeting implementations must override it. */
    public fun <T> evaluate(definition: FlagDefinition<T>, context: EvaluationContext): T = evaluate(definition)
}
