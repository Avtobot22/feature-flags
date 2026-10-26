package io.github.avtobot22.featureflags

/** Causes are diagnostic only and may contain source data; do not log them without redaction. */
public sealed interface RefreshError {
    public data class Transport(public val cause: Exception) : RefreshError
    public data class LocalSource(public val cause: Exception) : RefreshError
    public data class InvalidConfiguration(
        public val origin: ConfigOrigin,
        public val cause: IllegalArgumentException,
    ) : RefreshError
}
