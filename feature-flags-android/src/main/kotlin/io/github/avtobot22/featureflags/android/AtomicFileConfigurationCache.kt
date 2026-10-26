package io.github.avtobot22.featureflags.android

import android.util.AtomicFile
import io.github.avtobot22.featureflags.CacheRecord
import io.github.avtobot22.featureflags.ConfigurationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Stores one checksummed envelope per scope in an app-owned private directory, preferably noBackupFilesDir.
 * One live system owns each directory/scope; cross-process or concurrent system ownership is unsupported.
 */
public class AtomicFileConfigurationCache(
    private val directory: File,
    private val maxDocumentBytes: Int = DEFAULT_MAX_DOCUMENT_BYTES,
) : ConfigurationCache {
    init {
        require(maxDocumentBytes > 0) { "Document size limit must be positive." }
    }

    override suspend fun read(scope: String): CacheRecord? = withContext(Dispatchers.IO) {
        val file = file(scope)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        val digestStream = DigestInputStream(file.openRead(), MessageDigest.getInstance("SHA-256"))
        val record = DataInputStream(digestStream).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Invalid cache envelope.")
            val version = input.readInt()
            val storedScope = input.readBytes(MAX_SCOPE_BYTES).toString(Charsets.UTF_8)
            val receivedAt = input.readLong()
            val bytes = input.readBytes(maxDocumentBytes)
            val expectedChecksum = digestStream.messageDigest.digest()
            digestStream.on(false)
            val checksum = ByteArray(CHECKSUM_BYTES).also(input::readFully)
            if (!MessageDigest.isEqual(checksum, expectedChecksum) || input.read() != -1) {
                throw IOException("Corrupt cache envelope.")
            }
            CacheRecord(storedScope, receivedAt, bytes.toString(Charsets.UTF_8), version)
        }
        currentCoroutineContext().ensureActive()
        record
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun write(record: CacheRecord): Unit = withContext(Dispatchers.IO) {
        val bytes = record.document.toByteArray(Charsets.UTF_8)
        val scopeBytes = record.scope.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxDocumentBytes || scopeBytes.size > MAX_SCOPE_BYTES) {
            throw IOException("Cache record exceeds size limit.")
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create cache directory.")
        currentCoroutineContext().ensureActive()
        val file = file(record.scope)
        val stream = file.startWrite()
        try {
            val digestStream = DigestOutputStream(stream, MessageDigest.getInstance("SHA-256"))
            val output = DataOutputStream(digestStream)
            output.writeInt(MAGIC)
            output.writeInt(record.formatVersion)
            output.writeBytes(scopeBytes)
            output.writeLong(record.receivedAtEpochMillis)
            output.writeBytes(bytes)
            val checksum = digestStream.messageDigest.digest()
            digestStream.on(false)
            output.write(checksum)
            output.flush()
            currentCoroutineContext().ensureActive()
            stream.fd.sync()
            file.finishWrite(stream)
            if (File(file.baseFile.path + ".new").exists() || File(file.baseFile.path + ".bak").exists() ||
                !file.baseFile.isFile
            ) {
                throw IOException("Cannot commit cache record.")
            }
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    override suspend fun clear(scope: String): Unit = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val file = file(scope)
        file.delete()
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() ||
            File(file.baseFile.path + ".new").exists()
        ) {
            throw IOException("Cannot remove cache record.")
        }
    }

    private fun file(scope: String): AtomicFile {
        val name = digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.flags"))
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun DataInputStream.readBytes(limit: Int): ByteArray {
        val size = readInt()
        if (size < 0 || size > limit) throw IOException("Invalid cache field size.")
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeBytes(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        const val MAGIC = 0x46464348
        const val CHECKSUM_BYTES = 32
        const val MAX_SCOPE_BYTES = 4096
        const val DEFAULT_MAX_DOCUMENT_BYTES = 1024 * 1024
    }
}
