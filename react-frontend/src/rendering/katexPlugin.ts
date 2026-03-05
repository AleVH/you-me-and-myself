/**
 * markdown-it KaTeX plugin — Math rendering for AI chat responses.
 *
 * ## What This Plugin Does
 *
 * Registers inline and block rules with markdown-it to detect math delimiters
 * and render them using KaTeX. AI models (Claude, GPT, Gemini) use various
 * delimiter styles, so we support all common ones:
 *
 * | Delimiter       | Context         | displayMode |
 * |-----------------|-----------------|-------------|
 * | `$...$`         | inline          | false       |
 * | `$$...$$`       | inline or block | true        |
 * | `\(...\)`       | inline          | false       |
 * | `\[...\]`       | inline or block | true        |
 *
 * ## How It Works
 *
 * **Inline ruler** handles all four delimiter styles when they appear within
 * paragraph text. It checks for `$$` and `\[` first (longer match wins) to
 * avoid misinterpreting display math as two inline math blocks.
 *
 * **Block ruler** handles the multi-line case where `$$` or `\[` is on its
 * own line, with content on subsequent lines, closed by `$$` or `\]` on its
 * own line. This is the classic LaTeX-style freestanding block.
 *
 * ## False-Positive Protection
 *
 * - Escaped delimiters (`\$`, `\\(`, `\\[`) are skipped
 * - Single `$` followed by digits only (e.g., `$5`, `$10.99`) is skipped
 *   — this is the **currency protection** that prevents "$500 budget" from
 *   being parsed as math
 * - Empty content between delimiters is skipped
 * - Brace nesting `{}` is tracked — don't close on `$` inside braces
 *   — this prevents `${x + y}$` from closing prematurely at the `}`
 *
 * ## Ported From
 *
 * This is a TypeScript port of the legacy `initKaTeX()` function from
 * `chat.js` (Phase 4B). Same logic, same delimiter handling, same
 * false-positive protection — just typed and modular.
 *
 * @see markdownRenderer.ts — where this plugin is registered
 * @see https://katex.org/ — KaTeX documentation
 */

import type MarkdownIt from "markdown-it";
import type StateInline from "markdown-it/lib/rules_inline/state_inline.mjs";
import type StateBlock from "markdown-it/lib/rules_block/state_block.mjs";
import katex from "katex";

// ═══════════════════════════════════════════════════════════════════════
//  HELPER: SCAN FOR CLOSING DELIMITER
// ═══════════════════════════════════════════════════════════════════════

/**
 * Scan forward in a string for a closing math delimiter.
 *
 * Handles:
 * - Brace nesting: `{` increments depth, `}` decrements. Closing delimiter
 *   is only recognized at nesting depth 0.
 * - Backslash escapes: `\$` skips the dollar sign. But `\)` and `\]` are
 *   NOT skipped when they're the actual closing delimiter we're looking for.
 *
 * @param src The full source string
 * @param start Index to start scanning (after the opening delimiter)
 * @param max Maximum index to scan
 * @param closeDelim The closing delimiter string to look for
 * @returns Index of the first character of the closing delimiter, or -1
 */
function scanForClose(
    src: string,
    start: number,
    max: number,
    closeDelim: string,
): number {
    let pos = start;
    let nestDepth = 0;
    const closeLen = closeDelim.length;

    while (pos <= max - closeLen) {
        const ch = src.charCodeAt(pos);

        // Track brace nesting — don't close on $ inside { }
        if (ch === 0x7b /* { */) {
            nestDepth++;
            pos++;
            continue;
        }
        if (ch === 0x7d /* } */) {
            nestDepth = Math.max(0, nestDepth - 1);
            pos++;
            continue;
        }

        // Handle backslash escapes
        if (ch === 0x5c /* \ */ && pos + 1 < max) {
            const nextChar = src.charAt(pos + 1);

            // Special case: \) and \] ARE closing delimiters, not escapes
            if (closeDelim === "\\)" && nextChar === ")") {
                if (nestDepth === 0) return pos;
            } else if (closeDelim === "\\]" && nextChar === "]") {
                if (nestDepth === 0) return pos;
            } else {
                // Regular escape — skip both characters
                pos += 2;
                continue;
            }
        }

        // Check for non-backslash closing delimiter ($ or $$)
        if (src.startsWith(closeDelim, pos) && closeDelim[0] !== "\\") {
            if (nestDepth === 0) return pos;
        }

        pos++;
    }

    return -1;
}

// ═══════════════════════════════════════════════════════════════════════
//  RENDERERS
// ═══════════════════════════════════════════════════════════════════════

/**
 * Render a math token to HTML using KaTeX.
 *
 * @param content The raw LaTeX content between delimiters
 * @param displayMode true for centered block math, false for inline
 * @returns HTML string from KaTeX, or a fallback error display
 */
function renderMath(content: string, displayMode: boolean): string {
    try {
        const html = katex.renderToString(content, {
            throwOnError: false,
            displayMode,
        });
        return displayMode ? `<div class="ymm-math-block">${html}</div>` : html;
    } catch (err) {
        // Fallback: show the raw LaTeX in a styled error block
        console.warn("[YMM] KaTeX render error:", err);
        const escaped = content
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
        return displayMode
            ? `<pre class="ymm-math-error">${escaped}</pre>`
            : `<code class="ymm-math-error">${escaped}</code>`;
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  PLUGIN ENTRY POINT
// ═══════════════════════════════════════════════════════════════════════

/**
 * Register the KaTeX plugin with a markdown-it instance.
 *
 * Adds two rules:
 * - `math_inline` — inline ruler for all four delimiter styles
 * - `math_block` — block ruler for freestanding $$ and \[ blocks
 *
 * @param md The markdown-it instance to extend
 */
export function katexPlugin(md: MarkdownIt): void {
    // ── Inline Ruler ───────────────────────────────────────────────────
    //
    // Handles all four delimiter styles within paragraph text:
    // $...$, $$...$$, \(...\), \[...\]

    md.inline.ruler.after(
        "escape",
        "math_inline",
        (state: StateInline, silent: boolean): boolean => {
            const src = state.src;
            const pos = state.pos;
            const max = state.posMax;
            const ch = src.charCodeAt(pos);

            let openDelim: string;
            let closeDelim: string;
            let isDisplay: boolean;

            if (ch === 0x24 /* $ */) {
                // Check for escaped dollar: \$
                if (pos > 0 && src.charCodeAt(pos - 1) === 0x5c /* \ */) return false;

                // $$ (display) or $ (inline)?
                if (pos + 1 < max && src.charCodeAt(pos + 1) === 0x24) {
                    openDelim = "$$";
                    closeDelim = "$$";
                    isDisplay = true;
                } else {
                    openDelim = "$";
                    closeDelim = "$";
                    isDisplay = false;

                    // Currency protection: $5, $10.99, $500 — skip these
                    const afterDollar = src.slice(pos + 1);
                    if (/^(\d+([.,]\d+)?)(\s|$|\)|,|;|\.(?!\w))/.test(afterDollar)) {
                        return false;
                    }
                }
            } else if (ch === 0x5c /* \ */) {
                if (pos + 1 >= max) return false;
                const nextChar = src.charAt(pos + 1);

                if (nextChar === "(") {
                    openDelim = "\\(";
                    closeDelim = "\\)";
                    isDisplay = false;
                } else if (nextChar === "[") {
                    openDelim = "\\[";
                    closeDelim = "\\]";
                    isDisplay = true;
                } else {
                    return false;
                }

                // Check for escaped backslash: \\( or \\[
                if (pos > 0 && src.charCodeAt(pos - 1) === 0x5c) return false;
            } else {
                return false;
            }

            const start = pos + openDelim.length;
            if (start >= max) return false;

            // Scan for closing delimiter
            const closePos = scanForClose(src, start, max, closeDelim);
            if (closePos < 0) return false;

            const content = src.slice(start, closePos).trim();
            if (!content) return false;

            if (!silent) {
                const tokenType = isDisplay ? "math_display_inline" : "math_inline";
                const token = state.push(tokenType, "math", 0);
                token.content = content;
                token.markup = openDelim;
            }

            state.pos = closePos + closeDelim.length;
            return true;
        },
    );

    // ── Block Ruler ────────────────────────────────────────────────────
    //
    // Handles freestanding math blocks where $$ or \[ is on its own line:
    //   $$
    //   \int_{-\infty}^{\infty} e^{-x^2} dx = \sqrt{\pi}
    //   $$

    md.block.ruler.after(
        "fence",
        "math_block",
        (
            state: StateBlock,
            startLine: number,
            endLine: number,
            silent: boolean,
        ): boolean => {
            const startPos = state.bMarks[startLine] + state.tShift[startLine];
            const maxPos = state.eMarks[startLine];
            const lineText = state.src.slice(startPos, maxPos).trim();

            // Check for opening delimiter on its own line
            let closeDelim: string;
            if (lineText === "$$") {
                closeDelim = "$$";
            } else if (lineText === "\\[") {
                closeDelim = "\\]";
            } else {
                return false;
            }

            if (silent) return true;

            // Scan forward for closing delimiter on its own line
            let closeLine = startLine + 1;
            let content = "";

            while (closeLine < endLine) {
                const lineStart = state.bMarks[closeLine] + state.tShift[closeLine];
                const lineEnd = state.eMarks[closeLine];
                const line = state.src.slice(lineStart, lineEnd).trim();

                if (line === closeDelim) {
                    // Found the closing delimiter
                    break;
                }
                content += (content ? "\n" : "") + line;
                closeLine++;
            }

            // If we hit endLine without finding close, no match
            if (closeLine >= endLine) return false;

            content = content.trim();
            if (!content) return false;

            const token = state.push("math_block", "math", 0);
            token.content = content;
            token.markup = lineText;
            token.map = [startLine, closeLine + 1];
            token.block = true;

            state.line = closeLine + 1;
            return true;
        },
        { alt: ["paragraph", "reference", "blockquote", "list"] },
    );

    // ── Render Rules ───────────────────────────────────────────────────

    md.renderer.rules.math_inline = (tokens, idx) =>
        renderMath(tokens[idx].content, false);

    md.renderer.rules.math_display_inline = (tokens, idx) =>
        renderMath(tokens[idx].content, true);

    md.renderer.rules.math_block = (tokens, idx) =>
        renderMath(tokens[idx].content, true);
}