package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.CacheClock
import io.github.avtobot22.featureflags.CacheRecord
import io.github.avtobot22.featureflags.CacheRestoreResult
import io.github.avtobot22.featureflags.CacheSettings
import io.github.avtobot22.featureflags.CacheUnavailable
import io.github.avtobot22.featureflags.ConfigurationCache
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.RefreshError
import io.github.avtobot22.featureflags.RefreshResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonFeatureFlagsCacheTest {
    @Test
    fun `restart restores disabled parameters and unknown flags from exact original document`() = runBlocking {
        val document = checkNotNull(javaClass.getResource("/fixtures/valid-nonempty.json")).readText().replace(
            "\"enabled\":true,\"parameters\":{\"depth\":2",
            "\"enabled\":false,\"parameters\":{\"depth\":5",
        )
        val storage = DocumentCache()
        var remote = document
        val first = system(storage, FeatureFlagsSource { remote })
        first.manager.refresh()
        assertEquals(document, storage.record!!.document)
        val restarted = system(storage, FeatureFlagsSource { remote })
        assertIs<CacheRestoreResult.Applied>(requireNotNull(restarted.cache).restore())
        assertFalse(restarted.reader.get(QuizmanFlags.prefetch).enabled)
        assertEquals(5, restarted.reader.get(QuizmanFlags.prefetch).params.depth)
        assertTrue(restarted.reader.get(Feature.boolean("future.unknown")))
        remote = """{"featureToggles":[]}"""
        restarted.manager.refresh()
        assertFalse(restarted.reader.get(Feature.boolean("future.unknown")))
        assertEquals(QuizmanFlags.PrefetchParams(2, 8), restarted.reader.get(QuizmanFlags.prefetch).params)
    }

    @Test
    fun `strict validation rejects corrupt stored and remote documents without replacing warm state`() = runBlocking {
        val valid = checkNotNull(javaClass.getResource("/fixtures/valid-nonempty.json")).readText()
        val duplicates = checkNotNull(javaClass.getResource("/fixtures/duplicates.json")).readText()
        for (invalid in listOf(duplicates, "{}", "", "{", """{"featureToggles":1}""")) {
            val storage = DocumentCache()
            var remote = valid
            val system = system(storage, FeatureFlagsSource { remote })
            system.manager.refresh()
            val previous = system.reader.readiness.value
            val goodRecord = storage.record
            storage.record = checkNotNull(goodRecord).copy(document = invalid)
            val unavailable = assertIs<CacheRestoreResult.Unavailable>(requireNotNull(system.cache).restore())
            assertIs<IllegalArgumentException>(assertIs<CacheUnavailable.Failure>(unavailable.reason).cause)
            assertEquals(previous, system.reader.readiness.value)
            assertTrue(system.reader.get(Feature.boolean("future.unknown")))
            storage.record = goodRecord
            remote = invalid
            val failure = assertIs<RefreshResult.Failure>(system.manager.refresh())
            assertIs<RefreshError.InvalidConfiguration>(failure.error)
            assertEquals(previous, system.reader.readiness.value)
            assertEquals(goodRecord, storage.record)
        }
    }

    private fun system(
        storage: ConfigurationCache,
        remote: FeatureFlagsSource,
    ): FeatureFlagsSystem = FeatureFlagsSystem(
        remote,
        JsonConfigurationCodec,
        FallbackPolicy.Never,
        cache = CacheSettings(storage, "endpoint-v1/context", 10, clock = CacheClock { 100 }),
    )
}

private class DocumentCache : ConfigurationCache {
    var record: CacheRecord? = null
    override suspend fun read(scope: String): CacheRecord? = record?.takeIf { it.scope == scope }
    override suspend fun write(record: CacheRecord) {
        this.record = record
    }
    override suspend fun clear(scope: String) {
        if (record?.scope == scope) record = null
    }
}
