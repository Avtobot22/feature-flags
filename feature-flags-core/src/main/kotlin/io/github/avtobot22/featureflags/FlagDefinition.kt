package io.github.avtobot22.featureflags

/** Keys are case-sensitive; evaluation must be deterministic and perform no I/O. */
public interface FlagDefinition<out T> {
    public val key: String

    public fun evaluate(input: EvaluationInput): T
}
