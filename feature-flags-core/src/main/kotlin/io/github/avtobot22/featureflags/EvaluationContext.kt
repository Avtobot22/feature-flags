package io.github.avtobot22.featureflags

/** Caller-owned stable key; null excludes the subject from every percentage rule, including 100%. */
public class EvaluationContext(public val targetingKey: String? = null) {
    init {
        require(targetingKey == null || targetingKey.isNotEmpty()) { "Targeting key must not be empty." }
        require(targetingKey == null || targetingKey.hasValidUnicode()) { "Invalid targeting key encoding." }
    }

    override fun toString(): String = "EvaluationContext(targetingKey=<redacted>)"
}

internal fun String.hasValidUnicode(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        if (character.isHighSurrogate() || character.isLowSurrogate()) {
            if (!character.isHighSurrogate() || index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
            index++
        }
        index++
    }

    return true
}
