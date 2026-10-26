package io.github.avtobot22.featureflags

/** A complete, immutable configuration; evaluation must be thread-safe and perform no I/O. */
public interface Configuration {
    public val isEmpty: Boolean

    /** Uses defaults for an absent key and propagates definition and decoder exceptions. */
    public fun <T> evaluate(definition: FlagDefinition<T>): T
}
