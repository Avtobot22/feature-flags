package io.github.avtobot22.featureflags

public sealed interface FallbackPolicy {
    public data object Never : FallbackPolicy

    /** Uses the local source only after a valid, empty remote configuration, never after a failure. */
    public data object OnRemoteEmpty : FallbackPolicy
}
