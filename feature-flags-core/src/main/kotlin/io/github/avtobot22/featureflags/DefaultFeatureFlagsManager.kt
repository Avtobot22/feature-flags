package io.github.avtobot22.featureflags

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultFeatureFlagsManager(
    private val remote: FeatureFlagsSource,
    private val codec: ConfigurationCodec,
    private val fallback: FallbackPolicy,
    private val local: FeatureFlagsSource?,
    private val store: ActiveSnapshotStore,
) : FeatureFlagsManager {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)
    override val refreshStatus: StateFlow<RefreshStatus> = mutableStatus.asStateFlow()

    override suspend fun refresh(): RefreshResult = mutex.withLock {
        currentCoroutineContext().ensureActive()
        mutableStatus.value = RefreshStatus.Running
        var finished = false
        try {
            val result = try {
                loadAndPublish()
            } catch (failure: RefreshFailed) {
                currentCoroutineContext().ensureActive()
                RefreshResult.Failure(failure.error)
            }
            mutableStatus.value = RefreshStatus.Finished(result)
            finished = true
            result
        } finally {
            if (!finished) mutableStatus.value = RefreshStatus.Idle
        }
    }

    private suspend fun loadAndPublish(): RefreshResult.Applied {
        val remoteConfiguration = load(remote, ConfigOrigin.REMOTE)
        val useLocal = remoteConfiguration.isEmpty && fallback == FallbackPolicy.OnRemoteEmpty
        val origin = if (useLocal) ConfigOrigin.BUNDLED_ASSET else ConfigOrigin.REMOTE
        val configuration = if (useLocal) load(checkNotNull(local), origin) else remoteConfiguration
        val count = configuration.flagCount
        currentCoroutineContext().ensureActive()
        val snapshot = store.replaceAll(configuration, origin)
        return RefreshResult.Applied(snapshot.revision, origin, count)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadJson(source: FeatureFlagsSource, origin: ConfigOrigin): String {
        currentCoroutineContext().ensureActive()
        return try {
            source.loadJson()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val error = if (origin == ConfigOrigin.REMOTE) {
                RefreshError.Transport(failure)
            } else {
                RefreshError.LocalSource(failure)
            }
            throw RefreshFailed(error)
        }
    }

    private suspend fun load(source: FeatureFlagsSource, origin: ConfigOrigin): Configuration {
        val json = loadJson(source, origin)
        currentCoroutineContext().ensureActive()
        val configuration = try {
            codec.decode(json)
        } catch (failure: IllegalArgumentException) {
            currentCoroutineContext().ensureActive()
            throw RefreshFailed(RefreshError.InvalidConfiguration(origin, failure))
        }
        currentCoroutineContext().ensureActive()
        val count = configuration.flagCount
        require(count >= 0 && configuration.isEmpty == (count == 0)) { "Inconsistent configuration size." }
        currentCoroutineContext().ensureActive()
        return configuration
    }
}

private class RefreshFailed(val error: RefreshError) : Exception()
