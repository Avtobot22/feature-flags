package io.github.avtobot22.featureflags

/** All properties must describe the same flag state throughout an evaluation. */
public interface EvaluationInput {
    public val present: Boolean

    public val enabled: Boolean

    public val parameters: ParameterReader
}
