package io.github.avtobot22.featureflags

/** Storage operations must atomically replace a complete record and isolate records by scope. */
public interface ConfigurationCache {
    public suspend fun read(scope: String): CacheRecord?
    public suspend fun write(record: CacheRecord)
    public suspend fun clear(scope: String)
}

/** The original validated document is decoded again on restore; configuration objects are never serialized. */
public data class CacheRecord(
    public val scope: String,
    public val receivedAtEpochMillis: Long,
    public val document: String,
    public val formatVersion: Int = CURRENT_FORMAT_VERSION,
) {
    public companion object {
        public const val CURRENT_FORMAT_VERSION: Int = 1
    }
}
