// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/Detector.kt
package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.project.Project

/**
 * Purpose: contract for any detector (language, frameworks, etc).
 * Implementation guidelines:
 * - Must be index-aware (avoid heavy work during dumb mode).
 * - Must respect cancellation (check isActive if using coroutines).
 */
interface Detector {
    val name: String
    suspend fun isApplicable(request: ContextRequest): Boolean
    suspend fun detect(project: Project, request: ContextRequest): List<ContextSignal>
}
