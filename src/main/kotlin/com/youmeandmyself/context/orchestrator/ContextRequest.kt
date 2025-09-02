// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/ContextRequest.kt
package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.project.Project

/**
 * Purpose: describes what context we want and constraints for gathering it.
 * Notes:
 * - scopePaths: optionally restrict search; empty = whole project.
 * - maxMillis: hard ceiling for the entire orchestration run.
 */
data class ContextRequest(
    val project: Project,
    val scopePaths: List<String> = emptyList(),
    val wantLanguage: Boolean = true,
    val wantFrameworks: Boolean = true,
    val wantProjectStructure: Boolean = true,
    val wantRelevantFiles: Boolean = true,
    val maxMillis: Long = 1500L // keep M2 snappy; we can tune later
)
