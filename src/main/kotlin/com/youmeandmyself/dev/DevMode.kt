package com.youmeandmyself.dev

/**
 * Controls access to hidden development and testing features.
 *
 * Dev mode is enabled by setting the system property:
 *   -Dymm.devMode=true
 *
 * This is used for:
 * - Test commands (/dev-*) for manually triggering correction flow scenarios
 * - Debug UI elements that shouldn't be visible to end users
 * - Verbose logging that would be too noisy in production
 *
 * ## How to Enable
 *
 * In IntelliJ run configuration, add VM option:
 *   -Dymm.devMode=true
 *
 * Or via command line when running the IDE:
 *   ./idea.sh -Dymm.devMode=true
 *
 * ## Security Note
 *
 * This is not a security boundary - it's just to prevent users from
 * accidentally triggering test features. Anyone who knows the property
 * name can enable it. Don't put anything sensitive behind this flag.
 */
object DevMode {

    private const val PROPERTY_NAME = "ymm.devMode"

    /**
     * Check if dev mode is currently enabled.
     *
     * Called by:
     * - ChatPanel to recognize dev test commands
     * - Any future dev-only UI elements
     *
     * @return true if -Dymm.devMode=true is set
     */
    fun isEnabled(): Boolean {
        return System.getProperty(PROPERTY_NAME)?.lowercase() == "true"
    }

    /**
     * List of available dev commands for help text.
     */
    val availableCommands = listOf(
        "/dev-scenario1" to "Test known format response (no correction UI)",
        "/dev-scenario2" to "Test heuristic response with correction option",
        "/dev-scenario3" to "Test low confidence response (immediate dialog)",
        "/dev-error" to "Test error response parsing",
        "/dev-status" to "Show current dev mode status and config",
        "/dev-summary-test" to "Test summary pipeline on current file (dry-run, no API call)",
        "/dev-summary-mock" to "Write a mock FILE_SUMMARY through the save pipeline (no API call)",
        "/dev-summarize" to "Request summary for current file (makes API call if not dry-run)",
        "/dev-summary-status" to "Show summary system status (mode, budget, queue)",
        "/dev-summary-stop" to "Cancel all queued summaries and disable summarization",
        "/dev-git-test" to "Test git branch detection step by step",
        "/dev-context-test" to "Test full IDE context capture (file, tabs, language, module, branch)"
    )

    /**
     * Format help text for dev commands.
     */
    fun helpText(): String = buildString {
        appendLine("🔧 YMM Dev Mode Commands")
        appendLine()
        availableCommands.forEach { (cmd, desc) ->
            appendLine("  $cmd")
            appendLine("    $desc")
            appendLine()
        }
    }
}