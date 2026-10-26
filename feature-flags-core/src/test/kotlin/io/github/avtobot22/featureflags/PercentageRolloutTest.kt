package io.github.avtobot22.featureflags

import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PercentageRolloutTest {
    @Test
    fun `fixed independently computed SHA256 vectors pin encoding and unsigned buckets`() {
        val vectors = listOf(
            Triple("guest-v1", "user-1", 2462),
            Triple("guest-v1", "user-2", 770),
            Triple("guest-v2", "user-1", 5393),
            Triple("группа/🚀", "пользователь/🙂", 6644),
            Triple("ab", "c", 579),
            Triple("a", "bc", 312),
            Triple("g", "e\u0301", 6890),
            Triple("g", "é", 5515),
        )
        for ((group, key, bucket) in vectors) {
            assertEquals(bucket, PercentageRollout(5000, group).bucket(key))
            assertFalse(PercentageRollout(bucket, group).includes(EvaluationContext(key)))
            assertTrue(PercentageRollout(bucket + 1, group).includes(EvaluationContext(key)))
        }
    }

    @Test
    fun `zero full and missing key have explicit behavior`() {
        val subject = EvaluationContext("subject")
        assertFalse(PercentageRollout(0, "group").includes(subject))
        assertTrue(PercentageRollout(10000, "group").includes(subject))
        for (percentage in listOf(0, 1, 5000, 10000)) {
            assertFalse(PercentageRollout(percentage, "group").includes(EvaluationContext()))
        }
        assertFailsWith<IllegalArgumentException> { EvaluationContext("") }
        assertFailsWith<IllegalArgumentException> { EvaluationContext("\uD800") }
        assertFailsWith<IllegalArgumentException> { EvaluationContext("\uDC00") }
        assertFailsWith<IllegalArgumentException> { PercentageRollout(1, "\uD800") }
        assertFailsWith<IllegalArgumentException> { PercentageRollout(1, "") }
        assertFailsWith<IllegalArgumentException> { PercentageRollout(-1, "group") }
        assertFailsWith<IllegalArgumentException> { PercentageRollout(10001, "group") }
        assertFalse(EvaluationContext("private-key").toString().contains("private-key"))
    }

    @Test
    fun `growing threshold retains all members and recreated rules retain stable groups`() {
        val low = PercentageRollout(2000, "group")
        val high = PercentageRollout(7000, "group")
        var lowCount = 0
        var highCount = 0
        repeat(5000) { index ->
            val context = EvaluationContext("subject-$index")
            val includedLow = low.includes(context)
            val includedHigh = high.includes(context)
            assertTrue(!includedLow || includedHigh)
            assertEquals(includedLow, PercentageRollout(2000, "group").includes(context))
            if (includedLow) lowCount++
            if (includedHigh) highCount++
        }
        assertTrue(lowCount in 800..1200)
        assertTrue(highCount in 3200..3800)
    }

    @Test
    fun `shared rule is stable under concurrent evaluation`() {
        val rule = PercentageRollout(2500, "guest-v1")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..1000).map { executor.submit<Boolean> { rule.includes(EvaluationContext("user-1")) } }
            results.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
