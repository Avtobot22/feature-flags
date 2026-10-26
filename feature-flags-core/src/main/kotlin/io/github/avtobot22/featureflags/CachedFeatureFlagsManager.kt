package io.github.avtobot22.featureflags

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CachedFeatureFlagsManager(
    private val remote: FeatureFlagsSource,
    private val codec: ConfigurationCodec,
    private val fallback: FallbackPolicy,
    private val local: FeatureFlagsSource?,
    private val store: ActiveSnapshotStore,
    private val settings: CacheSettings,
) : FeatureFlagsManager, FeatureFlagsCache {
    private val refreshMutex = Mutex()
    private val storageMutex = Mutex()
    private val publicationGuard = Any()
    private var invalidated = false
    private val mutableStatus = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)
    override val refreshStatus: StateFlow<RefreshStatus> = mutableStatus.asStateFlow()

    override suspend fun refresh(): RefreshResult = refreshMutex.withLock {
        currentCoroutineContext().ensureActive()
        mutableStatus.value = RefreshStatus.Running
        var finished = false
        try {
            val result = try {
                checkCacheSession(isInvalidated)
                refreshConfiguration()
            } catch (failure: CacheRefreshFailed) {
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

    override suspend fun restore(): CacheRestoreResult = refreshMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (isInvalidated) return@withLock CacheRestoreResult.Unavailable(CacheUnavailable.Invalidated)
        val cached = readCache()
        when (cached) {
            is CachedRead.Unavailable -> CacheRestoreResult.Unavailable(cached.reason)
            is CachedRead.Loaded -> try {
                CacheRestoreResult.Applied(publish(cached.configuration, ConfigOrigin.CACHE, cached.age))
            } catch (_: CacheRefreshFailed) {
                CacheRestoreResult.Unavailable(CacheUnavailable.Invalidated)
            }
        }
    }

    override suspend fun invalidate(): CacheInvalidationResult {
        synchronized(publicationGuard) {
            invalidated = true
            store.clear()
        }
        return storageMutex.withLock {
            cacheBoundary({ CacheInvalidationResult.Failure(it) }) {
                settings.storage.clear(settings.scope)
                CacheInvalidationResult.Cleared
            }
        }
    }

    private suspend fun refreshConfiguration(): RefreshResult {
        val cached = if (settings.mode == CacheMode.CACHE_FIRST) readCache() else null
        return if (cached is CachedRead.Loaded && cached.age.freshness == ConfigurationFreshness.FRESH) {
            publish(cached.configuration, ConfigOrigin.CACHE, cached.age)
        } else {
            refreshRemote()
        }
    }

    private suspend fun refreshRemote(): RefreshResult {
        val loaded = try {
            load(remote, ConfigOrigin.REMOTE)
        } catch (failure: CacheRefreshFailed) {
            if (failure.error is RefreshError.Transport && settings.mode != CacheMode.REMOTE_ONLY) {
                val cached = readCache()
                if (cached is CachedRead.Loaded) return publish(cached.configuration, ConfigOrigin.CACHE, cached.age)
            }
            throw failure
        }
        val receivedAt = settings.clock.nowEpochMillis()
        require(receivedAt >= 0) { "Cache clock must return a nonnegative epoch time." }
        val useLocal = loaded.configuration.isEmpty && fallback == FallbackPolicy.OnRemoteEmpty
        val origin = if (useLocal) ConfigOrigin.BUNDLED_ASSET else ConfigOrigin.REMOTE
        val configuration = if (useLocal) load(checkNotNull(local), origin).configuration else loaded.configuration
        val age = if (useLocal) null else ConfigurationAge(receivedAt, 0, ConfigurationFreshness.FRESH)
        val result = publish(configuration, origin, age)
        val record = CacheRecord(settings.scope, receivedAt, loaded.document)
        return result.copy(cacheWrite = save(record))
    }

    private suspend fun publish(
        configuration: Configuration,
        origin: ConfigOrigin,
        age: ConfigurationAge?,
    ): RefreshResult.Applied {
        currentCoroutineContext().ensureActive()
        return synchronized(publicationGuard) {
            checkCacheSession(isInvalidated)
            val snapshot = store.replaceAll(configuration, origin, age)
            RefreshResult.Applied(snapshot.revision, origin, configuration.flagCount, age)
        }
    }

    private suspend fun save(record: CacheRecord): CacheWriteResult = storageMutex.withLock {
        currentCoroutineContext().ensureActive()
        checkCacheSession(isInvalidated)
        cacheBoundary({ CacheWriteResult.Failure(it) }) {
            settings.storage.write(record)
            CacheWriteResult.Saved
        }
    }

    private suspend fun readCache(): CachedRead = storageMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (isInvalidated) return@withLock CachedRead.Unavailable(CacheUnavailable.Invalidated)
        val stored = cacheBoundary<StoredRecord>({ StoredRecord.Failure(it) }) {
            StoredRecord.Value(settings.storage.read(settings.scope))
        }
        if (stored is StoredRecord.Failure) {
            return@withLock CachedRead.Unavailable(CacheUnavailable.Failure(stored.cause))
        }
        val record = (stored as StoredRecord.Value).record
            ?: return@withLock CachedRead.Unavailable(CacheUnavailable.Missing)
        if (record.formatVersion != CacheRecord.CURRENT_FORMAT_VERSION || record.scope != settings.scope) {
            return@withLock CachedRead.Unavailable(CacheUnavailable.Incompatible)
        }
        val initialAge = cacheAge(record, settings)
        if (initialAge is CacheAgeCheck.Unavailable) return@withLock CachedRead.Unavailable(initialAge.reason)
        val configuration = try {
            decode(record.document, ConfigOrigin.CACHE)
        } catch (failure: CacheRefreshFailed) {
            val error = failure.error as RefreshError.InvalidConfiguration
            return@withLock CachedRead.Unavailable(CacheUnavailable.Failure(error.cause))
        }
        when (val age = cacheAge(record, settings)) {
            is CacheAgeCheck.Unavailable -> CachedRead.Unavailable(age.reason)
            is CacheAgeCheck.Allowed -> CachedRead.Loaded(configuration, age.age)
        }
    }

    private suspend fun load(source: FeatureFlagsSource, origin: ConfigOrigin): LoadedDocument {
        val json = cacheBoundary({ throw CacheRefreshFailed(sourceError(origin, it)) }) { source.loadJson() }
        currentCoroutineContext().ensureActive()
        checkCacheSession(isInvalidated)
        return LoadedDocument(json, decode(json, origin))
    }

    private suspend fun decode(json: String, origin: ConfigOrigin): Configuration {
        currentCoroutineContext().ensureActive()
        val configuration = try {
            codec.decode(json)
        } catch (failure: IllegalArgumentException) {
            currentCoroutineContext().ensureActive()
            throw CacheRefreshFailed(RefreshError.InvalidConfiguration(origin, failure))
        }
        currentCoroutineContext().ensureActive()
        val count = configuration.flagCount
        require(count >= 0 && configuration.isEmpty == (count == 0)) { "Inconsistent configuration size." }
        return configuration
    }

    private val isInvalidated: Boolean
        get() = synchronized(publicationGuard) { invalidated }
}

private data class LoadedDocument(val document: String, val configuration: Configuration)

private sealed interface CachedRead {
    data class Loaded(val configuration: Configuration, val age: ConfigurationAge) : CachedRead
    data class Unavailable(val reason: CacheUnavailable) : CachedRead
}

private class CacheRefreshFailed(val error: RefreshError) : Exception()

private fun sourceError(origin: ConfigOrigin, cause: Exception): RefreshError =
    if (origin == ConfigOrigin.REMOTE) RefreshError.Transport(cause) else RefreshError.LocalSource(cause)

private sealed interface StoredRecord {
    data class Value(val record: CacheRecord?) : StoredRecord
    data class Failure(val cause: Exception) : StoredRecord
}

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> cacheBoundary(failure: (Exception) -> T, block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    return try {
        val value = block()
        currentCoroutineContext().ensureActive()
        value
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (cause: Exception) {
        currentCoroutineContext().ensureActive()
        failure(cause)
    }
}

private sealed interface CacheAgeCheck {
    data class Allowed(val age: ConfigurationAge) : CacheAgeCheck
    data class Unavailable(val reason: CacheUnavailable) : CacheAgeCheck
}

private fun cacheAge(record: CacheRecord, settings: CacheSettings): CacheAgeCheck {
    val now = settings.clock.nowEpochMillis()
    if (record.receivedAtEpochMillis < 0 || now < record.receivedAtEpochMillis) {
        return CacheAgeCheck.Unavailable(CacheUnavailable.ClockRollback)
    }
    val ageMillis = now - record.receivedAtEpochMillis
    return if (ageMillis > settings.maxAgeMillis) {
        CacheAgeCheck.Unavailable(CacheUnavailable.Expired)
    } else {
        val freshness = if (ageMillis <= settings.ttlMillis) {
            ConfigurationFreshness.FRESH
        } else {
            ConfigurationFreshness.STALE
        }
        CacheAgeCheck.Allowed(ConfigurationAge(record.receivedAtEpochMillis, ageMillis, freshness))
    }
}

private fun checkCacheSession(invalidated: Boolean) {
    if (invalidated) throw CacheRefreshFailed(RefreshError.Invalidated)
}
