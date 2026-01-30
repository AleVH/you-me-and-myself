// File: src/main/kotlin/com/youmeandmyself/context/provider/SynopsisProvider.kt

package com.youmeandmyself.context.provider

import com.intellij.openapi.project.Project

/**
 * Contract for a component capable of generating a concise synopsis of a file's header/body.
 * Implementations should be thread-safe and side-effect free (stateless); configuration is injected.
 */
interface SynopsisProvider {
    /**
     * Generate a synopsis for the given source.
     *
     * @param project         IntelliJ project (for logging/diagnostics if needed)
     * @param path            Absolute path (string) for observability/telemetry
     * @param languageId      Optional language id to tailor prompts
     * @param sourceText      Raw text fed to the model (already trimmed/sampled by caller)
     * @param maxTokens       Upper bound for model output (provider should enforce)
     * @return                A concise synopsis string (never null). Throw on irrecoverable errors.
     */
    suspend fun generateSynopsis(
        project: Project,
        path: String,
        languageId: String?,
        sourceText: String,
        maxTokens: Int
    ): String
}
