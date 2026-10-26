package io.github.avtobot22.featureflags

import java.util.Collections

internal class RawConfiguration(toggles: Collection<RawToggle>) : Configuration {
    val flags: Map<String, RawToggle>

    init {
        val prepared = toggles.toList()
        val keys = HashSet<String>()
        require(prepared.all { keys.add(it.key) }) { "Duplicate feature key." }
        flags = Collections.unmodifiableMap(prepared.associateBy { it.key })
    }

    override val flagCount: Int
        get() = flags.size

    override fun <T> evaluate(definition: FlagDefinition<T>): T =
        definition.evaluate(SnapshotEvaluationInput(flags[definition.key]))
}

internal class SnapshotEvaluationInput(toggle: RawToggle?) : EvaluationInput {
    override val present: Boolean = toggle != null
    override val enabled: Boolean = toggle?.enabled ?: false
    override val parameters: ParameterReader = toggle?.parameters ?: ImmutableParameters.EMPTY
}
