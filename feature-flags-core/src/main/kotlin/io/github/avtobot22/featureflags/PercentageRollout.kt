package io.github.avtobot22.featureflags

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Stable percentage-v1 rule with 0.01% precision; a shared group deliberately aligns different flags. */
public class PercentageRollout(public val basisPoints: Int, public val group: String) {
    init {
        require(basisPoints in 0..BUCKET_COUNT) { "Percentage must be between 0 and 10000 basis points." }
        require(group.isNotEmpty() && group.hasValidUnicode()) { "Expected a nonempty Unicode group." }
    }

    public fun includes(context: EvaluationContext): Boolean {
        val key = context.targetingKey ?: return false
        return bucket(key) < basisPoints
    }

    internal fun bucket(key: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("feature-flags/percentage-v1".toByteArray(Charsets.US_ASCII))
        for (value in listOf(group, key)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val prefix = ByteBuffer.wrap(digest.digest()).int.toLong() and UNSIGNED_INT_MASK
        return (prefix % BUCKET_COUNT).toInt()
    }

    private companion object {
        const val BUCKET_COUNT = 10_000
        const val UNSIGNED_INT_MASK = 0xffff_ffffL
    }
}
