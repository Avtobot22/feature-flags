package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.EvaluationInput
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureValue
import io.github.avtobot22.featureflags.FlagDefinition
import io.github.avtobot22.featureflags.ParameterReader
import io.github.avtobot22.featureflags.ParametersDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonParametersTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        value = [
            "VAL-01 missing | {} | 2",
            "VAL-02 null | {\"depth\":null} | 2",
            "VAL-03 string | {\"depth\":\"5\"} | 2",
            "VAL-04 fraction | {\"depth\":2.5} | 2",
            "VAL-05 exponent | {\"depth\":2e0} | 2",
            "VAL-06 overflow | {\"depth\":2147483648} | 2",
            "VAL-07 below min | {\"depth\":-1} | 0",
            "VAL-08 valid | {\"depth\":5} | 5",
        ],
        delimiter = '|',
    )
    fun `strict depth follows numeric contract`(case: String, json: String, expected: Int) {
        assertEquals(expected, JsonParameters.decode(json).int("depth", default = 2, min = 0), case)
    }

    @ParameterizedTest
    @CsvSource("0, 1", "8, 8")
    fun `VAL-09 and VAL-10 concurrent limit clamps only valid numbers`(number: Int, expected: Int) {
        val parameters = JsonParameters.decode("{\"maxConcurrent\":$number}")

        assertEquals(expected, parameters.int("maxConcurrent", default = 8, min = 1))
    }

    @Test
    fun `VAL-11 disabled config retains decoded parameters`() {
        val definition = Feature.config(
            key = "dashboard.prefetch",
            defaultParams = PrefetchParams(2, 8),
            decoder = ParametersDecoder { parameters, defaults ->
                PrefetchParams(
                    depth = parameters.int("depth", default = defaults.depth, min = 0),
                    maxConcurrent = parameters.int("maxConcurrent", default = defaults.maxConcurrent, min = 1),
                )
            },
        )
        val input = object : EvaluationInput {
            override val present: Boolean = true
            override val enabled: Boolean = false
            override val parameters: ParameterReader = JsonParameters.decode("{\"depth\":5}")
        }

        assertEquals(FeatureValue(false, PrefetchParams(5, 8)), definition.evaluate(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["2.0", "true", "false", "[]", "{}", "-2147483649", "1e100", "\"-5\""])
    fun `non integer values use default`(raw: String) {
        assertEquals(2, JsonParameters.decode("{\"depth\":$raw}").int("depth", default = 2, min = 0))
    }

    @ParameterizedTest
    @CsvSource("-2147483648, -2147483648", "2147483647, 2147483647", "0, 0", "-0, 0")
    fun `full Int range is accepted`(raw: String, expected: Int) {
        assertEquals(expected, JsonParameters.decode("{\"value\":$raw}").int("value", default = 1))
    }

    @Test
    fun `valid numbers clamp to maximum and lower bound`() {
        val parameters = JsonParameters.decode("{\"value\":10}")

        assertEquals(8, parameters.int("value", default = 0, max = 8))
        assertEquals(12, parameters.int("value", default = 0, min = 12))
        assertEquals(8, parameters.int("value", default = 0, min = 8, max = 8))
    }

    @Test
    fun `missing and invalid defaults are returned unchanged outside bounds`() {
        for (json in listOf("{}", "null", "{\"value\":\"bad\"}")) {
            val parameters = JsonParameters.decode(json)
            assertEquals(-1, parameters.int("value", default = -1, min = 0))
            assertEquals(20, parameters.int("value", default = 20, max = 10))
        }
    }

    @Test
    fun `inverted bounds fail even when parameter is missing`() {
        for (json in listOf("{}", "null", "{\"value\":5}")) {
            assertFailsWith<IllegalArgumentException> {
                JsonParameters.decode(json).int("value", default = 2, min = 10, max = 0)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "{}", "{\"value\":null}", "{\"value\":[]}", "{\"value\":{}}"])
    fun `missing null and structured values return typed defaults`(json: String) {
        val parameters = JsonParameters.decode(json)

        assertEquals(2, parameters.int("value", default = 2))
        assertTrue(parameters.boolean("value", default = true))
        assertEquals("default", parameters.string("value", default = "default"))
    }

    @Test
    fun `booleans and strings are read without coercion`() {
        val parameters = JsonParameters.decode("""{"boolean":true,"text":"hello","false":false,"empty":""}""")

        assertTrue(parameters.boolean("boolean", default = false))
        assertFalse(parameters.boolean("false", default = true))
        assertFalse(parameters.boolean("text", default = false))
        assertEquals("hello", parameters.string("text", default = "default"))
        assertEquals("", parameters.string("empty", default = "default"))
        assertEquals("default", parameters.string("boolean", default = "default"))
        assertFalse(JsonParameters.decode("""{"value":"true"}""").boolean("value", default = false))
        assertEquals("default", JsonParameters.decode("""{"value":5}""").string("value", default = "default"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["[]", "true", "5", "\"text\"", "{", "{\"value\":}", "null trailing"])
    fun `malformed or non object parameters fail at adapter boundary`(json: String) {
        assertFailsWith<IllegalArgumentException> { JsonParameters.decode(json) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"x\":foo}", "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":01}", "{\"x\":+1}", "{\"x\":1e}",
            "{\"x\":[foo]}", "{\"x\":{\"nested\":NaN}}", "{unquoted:true}", "{\"x\":--1}", "{\"x\":TRUE}",
            "{\"depth\":foo,\"depth\":5}", "{\"x\":[NaN],\"x\":true}",
            "{\"x\":{\"y\":01},\"x\":null}",
        ],
    )
    fun `invalid JSON primitive syntax is rejected at any depth`(json: String) {
        assertFailsWith<IllegalArgumentException> { JsonParameters.decode(json) }
    }

    @Test
    fun `unknown nested parameters do not affect known reads`() {
        val parameters = JsonParameters.decode("""{"depth":5,"unknown":{"items":[1,null,true]}}""")

        assertEquals(5, parameters.int("depth", default = 2))
    }

    @Test
    fun `valid duplicate parameter keys remain accepted`() {
        val parameters = JsonParameters.decode("""{"depth":1,"depth":5,"x":[0,1e2],"x":true}""")

        assertEquals(5, parameters.int("depth", default = 2))
        assertTrue(parameters.boolean("x", default = false))
    }

    @Test
    fun `valid nested JSON numbers remain accepted without integer coercion`() {
        val parameters = JsonParameters.decode("""{"x":[-2,0.5,1E+3,1e-9,null,true,false,"foo"],"depth":2.0}""")

        assertEquals(7, parameters.int("depth", default = 7))
    }

    @Test
    fun `raw control characters in strings and keys are rejected`() {
        for (character in '\u0000'..'\u001f') {
            assertFailsWith<IllegalArgumentException> { JsonParameters.decode("{\"x\":\"raw${character}value\"}") }
            assertFailsWith<IllegalArgumentException> { JsonParameters.decode("{\"raw${character}key\":true}") }
        }
    }

    @Test
    fun `escaped controls quotes and backslashes remain valid`() {
        val parameters = JsonParameters.decode("""{"text":"line\n\t\u0001\"\\end","key\n":"value"}""")

        assertEquals("line\n\t\u0001\"\\end", parameters.string("text", default = "default"))
        assertEquals("value", parameters.string("key\n", default = "default"))
        assertTrue(JsonParameters.decode("{\n\t\"value\":true\r\n}").boolean("value", default = false))
    }

    @Test
    fun `Quizman catalogue evaluates defaults and configured values through fake reader`() {
        val absent = FakeReader(emptyMap())
        for (definition in listOf(
            QuizmanFlags.guestMode,
            QuizmanFlags.parallax,
            QuizmanFlags.hero,
            QuizmanFlags.forceRefreshButton,
        )) {
            assertFalse(absent.get(definition))
        }
        assertEquals(FeatureValue(false, QuizmanFlags.PrefetchParams(2, 8)), absent.get(QuizmanFlags.prefetch))
        assertEquals(FeatureValue(false, QuizmanFlags.PaginationParams(20)), absent.get(QuizmanFlags.pagination))

        val reader = FakeReader(
            mapOf(
                "dashboard.guestMode" to testInput(enabled = true),
                "dashboard.pagination" to testInput(parameters = """{"pageSize":0}"""),
            ),
        )
        assertTrue(reader.get(QuizmanFlags.guestMode))
        assertEquals(FeatureValue(false, QuizmanFlags.PaginationParams(1)), reader.get(QuizmanFlags.pagination))
    }

    private class FakeReader(private val inputs: Map<String, EvaluationInput>) {
        fun <T> get(definition: FlagDefinition<T>): T =
            definition.evaluate(inputs[definition.key] ?: testInput(present = false))
    }

    private companion object {
        fun testInput(
            present: Boolean = true,
            enabled: Boolean = false,
            parameters: String = "null",
        ): EvaluationInput = object : EvaluationInput {
            override val present: Boolean = present
            override val enabled: Boolean = enabled
            override val parameters: ParameterReader = JsonParameters.decode(parameters)
        }
    }

    private data class PrefetchParams(val depth: Int, val maxConcurrent: Int)
}
