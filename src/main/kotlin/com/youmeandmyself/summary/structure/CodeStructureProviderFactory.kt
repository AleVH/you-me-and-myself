// File: src/main/kotlin/com/youmeandmyself/summary/structure/CodeStructureProviderFactory.kt
package com.youmeandmyself.summary.structure

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev

/**
 * Factory that provides the [CodeStructureProvider].
 *
 * ============================================================================
 * !! CRITICAL DESIGN PRINCIPLE — DO NOT VIOLATE !!
 * ============================================================================
 *
 * This factory does NOT route by language. The IDE's Structure View API is
 * language-agnostic: if the IDE supports a language, the Structure View
 * works for it. If the IDE doesn't support it, the provider returns
 * empty results for that file.
 *
 * There is NO language whitelist, NO normalizeLanguage(), NO language-specific
 * branching. Adding such code is a FLAGRANT VIOLATION of the plugin's
 * foundational design principle: the IDE handles language concerns, the
 * plugin consumes results.
 *
 * Any language the IDE supports works automatically. No plugin changes
 * needed. Ever.
 * ============================================================================
 *
 * ## Why a Factory
 *
 * - Encapsulates the decision of whether to provide or not (dumb mode check).
 * - Consumers never reference a concrete provider directly.
 * - If a better detection strategy emerges, swap the implementation here.
 *
 * ## Dumb Mode
 *
 * During indexing, PSI is not available. The IDE itself disables PSI-dependent
 * features during this window, and so do we — same as every other plugin
 * and the IDE itself. When [get] returns null, callers skip summarization
 * and use raw content instead. No fallback, no regex, no workarounds.
 *
 * ## Usage
 *
 * ```kotlin
 * val provider = CodeStructureProviderFactory.getInstance(project).get()
 *     ?: return // Dumb mode — skip summarization
 * val elements = provider.detectElements(file, DetectionScope.All)
 * ```
 *
 * @param project The IntelliJ project this factory belongs to.
 */
@Service(Service.Level.PROJECT)
class CodeStructureProviderFactory(private val project: Project) {

    private val log = Logger.getInstance(CodeStructureProviderFactory::class.java)

    /**
     * Single provider instance — language-agnostic, reused for all files
     * regardless of language. Created once per project lifetime.
     */
    private val provider by lazy { IdeStructureProvider(project) }

    /**
     * Returns the structure provider, or null if unavailable (dumb mode).
     *
     * No language parameter. The provider works with ANY language the IDE
     * supports via its Structure View API. The IDE decides what it can
     * handle — we don't second-guess it.
     *
     * @return A [CodeStructureProvider] or null if the IDE is indexing.
     */
    fun get(): CodeStructureProvider? {
        // During indexing, PSI is incomplete — skip summarization entirely.
        // Same behavior as every other PSI-dependent IDE feature, and
        // the same behavior as the IDE itself.
        if (DumbService.isDumb(project)) {
            Dev.info(log, "structure.factory.dumb_mode",
                "reason" to "IDE is indexing — PSI unavailable, same as IDE itself"
            )
            return null
        }

        Dev.info(log, "structure.factory.provider_ready")
        return provider
    }

    companion object {
        fun getInstance(project: Project): CodeStructureProviderFactory =
            project.getService(CodeStructureProviderFactory::class.java)
    }
}
