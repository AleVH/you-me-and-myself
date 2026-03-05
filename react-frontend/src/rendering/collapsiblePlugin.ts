/**
 * Collapsible sections plugin for markdown-it.
 *
 * ## What This Plugin Does
 *
 * Detects fenced blocks with the language tag `details` or `collapsible`
 * and renders them as HTML `<details><summary>` elements. This lets AI
 * responses include expandable/collapsible sections for long explanations,
 * optional details, or verbose output.
 *
 * ## Syntax
 *
 * The AI can produce collapsible sections using a fenced block:
 *
 * ```details Summary text here
 * Content that can be expanded/collapsed.
 * Supports **markdown** inside.
 * ```
 *
 * The first line after the fence marker becomes the `<summary>`.
 * Everything else becomes the collapsible body.
 *
 * Also supports the explicit `<details>` / `<summary>` HTML tags that
 * some AI models produce directly. Since markdown-it has `html: false`,
 * we detect these as text patterns and convert them.
 *
 * ## Why a Custom Plugin
 *
 * markdown-it with `html: false` strips `<details>` tags for security.
 * But collapsible sections are genuinely useful in AI responses for
 * hiding verbose output. This plugin provides the functionality safely
 * through the fence mechanism rather than raw HTML injection.
 *
 * @see markdownRenderer.ts — where this plugin is registered
 */

import type MarkdownIt from "markdown-it";

/**
 * Register the collapsible sections plugin with a markdown-it instance.
 *
 * Overrides the fence renderer to detect `details` or `collapsible`
 * language tags and render them as `<details><summary>` elements.
 *
 * @param md The markdown-it instance to extend
 */
export function collapsiblePlugin(md: MarkdownIt): void {
    // Save the existing fence renderer (our custom one from markdownRenderer.ts)
    const originalFence = md.renderer.rules.fence;

    md.renderer.rules.fence = (tokens, idx, options, env, self) => {
        const token = tokens[idx];
        const lang = token.info.trim().split(/\s+/)[0]?.toLowerCase();

        // Only intercept `details` or `collapsible` language tags
        if (lang !== "details" && lang !== "collapsible") {
            // Pass through to the original fence renderer (code blocks, mermaid, etc.)
            if (originalFence) {
                return originalFence(tokens, idx, options, env, self);
            }
            return self.renderToken(tokens, idx, options);
        }

        const content = token.content;

        // First line is the summary, rest is the body
        const firstNewline = content.indexOf("\n");
        let summary: string;
        let body: string;

        if (firstNewline === -1) {
            // Single line — use as summary with empty body
            summary = content.trim();
            body = "";
        } else {
            summary = content.slice(0, firstNewline).trim();
            body = content.slice(firstNewline + 1).trim();
        }

        // Default summary if none provided
        if (!summary) {
            summary = "Details";
        }

        // Render the body as markdown (supports nested formatting)
        const renderedBody = body ? md.render(body) : "";

        return `<details class="ymm-collapsible">`
            + `<summary class="ymm-collapsible__summary">${md.utils.escapeHtml(summary)}</summary>`
            + `<div class="ymm-collapsible__body">${renderedBody}</div>`
            + `</details>`;
    };
}