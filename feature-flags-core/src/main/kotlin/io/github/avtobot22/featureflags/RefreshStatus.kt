package io.github.avtobot22.featureflags

public sealed interface RefreshStatus {
    public data object Idle : RefreshStatus
    public data object Running : RefreshStatus
    public data class Finished(public val result: RefreshResult) : RefreshStatus
}
