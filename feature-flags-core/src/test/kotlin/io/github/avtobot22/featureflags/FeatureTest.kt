package io.github.avtobot22.featureflags

import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureTest {
    @Test
    fun `boolean definition preserves exact case-sensitive key`() {
        assertEquals("dashboard.guestMode", Feature.boolean("dashboard.guestMode").key)
        assertEquals("Dashboard.GuestMode", Feature.boolean("Dashboard.GuestMode").key)
    }

    @Test
    fun `missing boolean uses explicit default regardless of input enabled`() {
        assertFalse(Feature.boolean("flag").evaluate(input(present = false, enabled = true)))
        assertTrue(Feature.boolean("flag", default = true).evaluate(input(present = false)))
    }

    @Test
    fun `present boolean uses enabled regardless of definition default`() {
        assertTrue(Feature.boolean("flag").evaluate(input(enabled = true)))
        assertFalse(Feature.boolean("flag", default = true).evaluate(input()))
    }

    @Test
    fun `missing config returns same defaults without invoking decoder`() {
        val defaults = Params(2)
        val definition = Feature.config(
            key = "flag",
            defaultEnabled = true,
            defaultParams = defaults,
            decoder = ParametersDecoder { _, _ -> error("Must not decode absent flag") },
        )

        val actual = definition.evaluate(input(present = false))

        assertTrue(actual.enabled)
        assertSame(defaults, actual.params)
    }

    @Test
    fun `present config passes single defaults object to decoder`() {
        val defaults = Params(2)
        var calls = 0
        val definition = Feature.config(
            key = "flag",
            defaultParams = defaults,
            decoder = ParametersDecoder { parameters, actualDefaults ->
                assertSame(defaults, actualDefaults)
                assertSame(UnusedParameters, parameters)
                calls++
                actualDefaults.copy(depth = 5)
            },
        )

        assertEquals(FeatureValue(false, Params(5)), definition.evaluate(input()))
        assertEquals(1, calls)
    }

    @Test
    fun `custom decoder exception propagates without fallback`() {
        val failure = IllegalStateException("Decoder failed")
        val definition = Feature.config("flag", defaultParams = Params(2)) { _, _ -> throw failure }

        assertSame(failure, assertFailsWith<IllegalStateException> { definition.evaluate(input()) })
    }

    @Test
    fun `custom decoder cancellation propagates`() {
        val cancellation = CancellationException("Caller cancelled")
        val definition = Feature.config("flag", defaultParams = Params(2)) { _, _ -> throw cancellation }

        assertSame(cancellation, assertFailsWith<CancellationException> { definition.evaluate(input()) })
    }

    private fun input(present: Boolean = true, enabled: Boolean = false): EvaluationInput = object : EvaluationInput {
        override val present: Boolean = present
        override val enabled: Boolean = enabled
        override val parameters: ParameterReader = UnusedParameters
    }

    private data class Params(val depth: Int)

    private object UnusedParameters : ParameterReader {
        override fun int(name: String, default: Int, min: Int?, max: Int?): Int = error("Unexpected parameter read")

        override fun boolean(name: String, default: Boolean): Boolean = error("Unexpected parameter read")

        override fun string(name: String, default: String): String = error("Unexpected parameter read")
    }
}
