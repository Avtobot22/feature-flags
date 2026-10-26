package io.github.avtobot22.featureflags.json

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFailsWith

class JsonConfigurationValidationTest {
    @ParameterizedTest
    @ValueSource(strings = ["duplicates.json", "invalid-enabled.json"])
    fun `PAR-05 and PAR-08 invalid schema fixtures reject the entire response`(fixture: String) {
        val json = checkNotNull(javaClass.getResource("/fixtures/$fixture")).readText()

        assertFailsWith<IllegalArgumentException> { JsonConfigurationCodec.decode(json) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}", "{\"featureToggles\":null}", "{\"featureToggles\":{}}", "{\"featureToggles\":true}",
            "{\"featureToggles\":0}", "{\"featureToggles\":\"[]\"}", "null", "[]", "true", "0", "\"text\"",
        ],
    )
    fun `PAR-03 and PAR-04 require a root object and mandatory array`(json: String) {
        assertFailsWith<IllegalArgumentException> { JsonConfigurationCodec.decode(json) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}", "{\"feature\":\"x\"}", "{\"enabled\":true}", "{\"feature\":null,\"enabled\":true}",
            "{\"feature\":42,\"enabled\":true}", "{\"feature\":true,\"enabled\":true}",
            "{\"feature\":[],\"enabled\":true}", "{\"feature\":{},\"enabled\":true}",
            "{\"feature\":\"x\",\"enabled\":\"true\"}", "{\"feature\":\"x\",\"enabled\":null}",
            "{\"feature\":\"x\",\"enabled\":1}", "{\"feature\":\"x\",\"enabled\":[]}",
            "{\"feature\":\"x\",\"enabled\":{}}", "null", "[]", "0", "\"text\"", "true",
        ],
    )
    fun `PAR-05 PAR-06 and PAR-07 reject missing and incorrectly typed required toggle fields`(toggle: String) {
        assertFailsWith<IllegalArgumentException> {
            JsonConfigurationCodec.decode("""{"featureToggles":[$toggle]}""")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["[]", "true", "0", "\"object\""])
    fun `PAR-12 only object or null parameters are accepted`(parameters: String) {
        assertFailsWith<IllegalArgumentException> {
            JsonConfigurationCodec.decode(
                """{"featureToggles":[{"feature":"x","enabled":false,"parameters":$parameters}]}""",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "{", "{\"featureToggles\":[}", "{\"featureToggles\":[],}", "{\"featureToggles\":[]} trailing",
            "{\"featureToggles\":[{\"feature\":\"x\",\"enabled\":TRUE}]}",
            "{\"featureToggles\":[],\"extra\":NaN}", "{\"featureToggles\":[],\"extra\":{\"x\":01}}",
            "{\"featureToggles\":[],\"extra\":[foo]}",
            "{\"featureToggles\":[],\"extra\":foo,\"extra\":null}",
            "{\"featureToggles\":[],\"extra\":\"raw\ncontrol\"}",
            "{\"featureToggles\":[{\"feature\":\"x\",\"enabled\":true,\"parameters\":{\"x\":NaN,\"x\":1}}]}",
            "{\"featureToggles\":[{\"feature\":\"x\",\"enabled\":true,\"parameters\":{\"x\":\"\\q\"}}]}",
        ],
    )
    fun `PAR-16 malformed JSON is rejected including hidden and unknown values`(json: String) {
        assertFailsWith<IllegalArgumentException> { JsonConfigurationCodec.decode(json) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"featureToggles\":[{\"feature\":\"x\",\"enabled\":true},{\"feature\":\"\\u0078\",\"enabled\":false}]}",
            "{\"featureToggles\":[{\"feature\":\"x\",\"enabled\":true},{\"feature\":\"bad\",\"enabled\":0}]}",
        ],
    )
    fun `late invalid entries and escaped duplicate keys reject the complete document`(json: String) {
        assertFailsWith<IllegalArgumentException> { JsonConfigurationCodec.decode(json) }
    }
}
