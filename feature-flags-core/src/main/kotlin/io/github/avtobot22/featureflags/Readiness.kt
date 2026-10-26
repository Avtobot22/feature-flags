package io.github.avtobot22.featureflags

public sealed interface Readiness {
    public data object NotInitialized : Readiness

    /** [revision] counts successful local publications, including empty or equal configurations. */
    public data class Ready(
        public val revision: Long,
        public val origin: ConfigOrigin,
        public val age: ConfigurationAge? = null,
    ) : Readiness
}
