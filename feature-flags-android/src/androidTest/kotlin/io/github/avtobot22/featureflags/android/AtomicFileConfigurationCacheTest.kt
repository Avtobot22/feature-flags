package io.github.avtobot22.featureflags.android

import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.avtobot22.featureflags.CacheClock
import io.github.avtobot22.featureflags.CacheMode
import io.github.avtobot22.featureflags.CacheRecord
import io.github.avtobot22.featureflags.CacheRestoreResult
import io.github.avtobot22.featureflags.CacheSettings
import io.github.avtobot22.featureflags.CacheUnavailable
import io.github.avtobot22.featureflags.ConfigOrigin
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.RefreshResult
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AtomicFileConfigurationCacheTest {
    private lateinit var directory: File
    private val scope = "endpoint-v1/context-a"
    private val document = """{"featureToggles":[{"feature":"будущий.флаг","enabled":true}]}"""

    @Before
    fun prepareDirectory() {
        directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            UUID.randomUUID().toString(),
        )
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun exactUtf8EnvelopeSurvivesNewAdapterAndAtomicReplacement() = runBlocking {
        val storage = AtomicFileConfigurationCache(directory)
        assertNull(storage.read(scope))
        val first = CacheRecord(scope, 123, document)
        storage.write(first)
        assertEquals(first, AtomicFileConfigurationCache(directory).read(scope))
        val second = first.copy(document = """{"featureToggles":[]}""", receivedAtEpochMillis = 124)
        storage.write(second)
        assertEquals(second, AtomicFileConfigurationCache(directory).read(scope))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun scopePathsAreHashedAndClearDoesNotAffectOtherScope() = runBlocking {
        val storage = AtomicFileConfigurationCache(directory)
        val first = CacheRecord("../../outside", 1, document)
        val second = first.copy(scope = "other")
        storage.write(first)
        storage.write(second)
        assertTrue(directory.listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.flags")) })
        storage.clear(first.scope)
        assertNull(storage.read(first.scope))
        assertEquals(second, storage.read(second.scope))
        storage.clear(first.scope)
    }

    @Test
    fun corruptDocumentAndMetadataAndTruncationAreRejected() = runBlocking {
        val storage = AtomicFileConfigurationCache(directory)
        val record = CacheRecord(scope, 100, document)
        for (position in listOf(0, 4, 8 + 4 + scope.toByteArray().size, -1)) {
            storage.write(record)
            val file = directory.listFiles()!!.single()
            val bytes = file.readBytes()
            val index = if (position == -1) bytes.lastIndex else position
            bytes[index] = (bytes[index].toInt() xor 1).toByte()
            file.writeBytes(bytes)
            expectIoFailure { storage.read(scope) }
        }
        storage.write(record)
        directory.listFiles()!!.single().writeBytes(byteArrayOf(1, 2))
        expectIoFailure { storage.read(scope) }
    }

    @Test
    fun oversizedRecordsAndFailedNewWriteLeavePreviousRecordReadable() = runBlocking {
        val storage = AtomicFileConfigurationCache(directory, 100)
        val record = CacheRecord(scope, 100, "small")
        storage.write(record)
        expectIoFailure { storage.write(record.copy(document = "x".repeat(101))) }
        assertEquals(record, storage.read(scope))
        if (Build.VERSION.SDK_INT < 30) return@runBlocking
        val file = directory.listFiles()!!.single()
        File(file.path + ".new").mkdirs()
        File(file.path + ".new/blocker").writeText("block")
        expectIoFailure { storage.write(record.copy(document = "replacement")) }
        assertEquals(record, storage.read(scope))
    }

    @Test
    fun failedFinalCommitAndFailedOrphanDeletionAreReported() = runBlocking {
        val storage = AtomicFileConfigurationCache(directory)
        val record = CacheRecord(scope, 100, "small")
        storage.write(record)
        val file = directory.listFiles()!!.single()
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        File(file, "blocker").writeText("block")
        expectIoFailure { storage.write(record.copy(document = "replacement")) }
        directory.deleteRecursively()
        directory.mkdirs()
        val orphan = File(file.path + ".new")
        orphan.mkdirs()
        File(orphan, "blocker").writeText("block")
        expectIoFailure { storage.clear(scope) }
    }

    @Test
    fun boundedReadsRejectStoredOversizedDocument() = runBlocking {
        val record = CacheRecord(scope, 100, "x".repeat(101))
        AtomicFileConfigurationCache(directory, 200).write(record)
        expectIoFailure { AtomicFileConfigurationCache(directory, 100).read(scope) }
    }

    @Test
    fun interruptedAtomicWriteAndLegacyBackupRecoverLastCompleteRecord() = runBlocking {
        val storage = AtomicFileConfigurationCache(directory)
        val record = CacheRecord(scope, 100, document)
        storage.write(record)
        val file = directory.listFiles()!!.single()
        AtomicFile(file).startWrite().use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(record, AtomicFileConfigurationCache(directory).read(scope))
        assertTrue(file.renameTo(File(file.path + ".bak")))
        file.writeText("partial")
        assertEquals(record, AtomicFileConfigurationCache(directory).read(scope))
    }

    @Test
    fun systemRestoresRealJsonAfterRestartAndRefreshExplicitlyReplacesIt() = runBlocking {
        var remote = document
        fun system() = FeatureFlagsSystem(
            FeatureFlagsSource { remote },
            JsonConfigurationCodec,
            FallbackPolicy.Never,
            cache = CacheSettings(
                AtomicFileConfigurationCache(directory),
                scope,
                10,
                30,
                CacheMode.REMOTE_ONLY,
                CacheClock { 100 },
            ),
        )
        val first = system()
        assertTrue(first.manager.refresh() is RefreshResult.Applied)
        remote = """{"featureToggles":[]}"""
        val second = system()
        val restore = requireNotNull(second.cache).restore()
        assertTrue(restore is CacheRestoreResult.Applied)
        assertEquals(ConfigOrigin.CACHE, (restore as CacheRestoreResult.Applied).result.origin)
        assertTrue(second.reader.get(Feature.boolean("будущий.флаг")))
        second.manager.refresh()
        assertFalse(second.reader.get(Feature.boolean("будущий.флаг")))
        assertEquals(remote, AtomicFileConfigurationCache(directory).read(scope)!!.document)
    }

    @Test
    fun incompatibleVersionIsUnavailableWithoutPublishing() = runBlocking {
        val storage = AtomicFileConfigurationCache(directory)
        storage.write(CacheRecord(scope, 100, document, 2))
        val system = FeatureFlagsSystem(
            FeatureFlagsSource { error("Must not load remote") },
            JsonConfigurationCodec,
            FallbackPolicy.Never,
            cache = CacheSettings(storage, scope, 10, clock = CacheClock { 100 }),
        )
        assertEquals(
            CacheRestoreResult.Unavailable(CacheUnavailable.Incompatible),
            requireNotNull(system.cache).restore(),
        )
        assertFalse(system.reader.get(Feature.boolean("будущий.флаг")))
    }

    private suspend fun expectIoFailure(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
    }
}
