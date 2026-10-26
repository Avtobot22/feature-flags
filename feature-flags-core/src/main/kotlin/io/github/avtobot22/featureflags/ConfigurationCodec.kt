package io.github.avtobot22.featureflags

public fun interface ConfigurationCodec {
    /**
     * Validates the entire document without publishing it or evaluating definitions.
     *
     * @throws IllegalArgumentException if the document is malformed or violates the configuration schema.
     */
    public fun decode(json: String): Configuration
}
