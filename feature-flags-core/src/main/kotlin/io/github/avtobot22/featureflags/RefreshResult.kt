package io.github.avtobot22.featureflags

public sealed interface RefreshResult {
    public data class Applied(
        public val revision: Long,
        public val origin: ConfigOrigin,
        public val flagCount: Int,
    ) : RefreshResult

    public data class Failure(public val error: RefreshError) : RefreshResult
}
