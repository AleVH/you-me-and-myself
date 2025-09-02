// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/Confidence.kt
package com.youmeandmyself.context.orchestrator

/**
 * Purpose: standardized confidence for downstream decisions.
 */
@JvmInline
value class Confidence private constructor(val value: Int) {
    companion object {
        fun of(percent: Int) = Confidence(percent.coerceIn(0, 100))
        val LOW = of(33)
        val MEDIUM = of(66)
        val HIGH = of(90)
    }
}
