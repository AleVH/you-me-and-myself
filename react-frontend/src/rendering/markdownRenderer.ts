/**
 * Markdown rendering engine for the chat interface.
 *
 * ## What This Module Does
 *
 * Configures a `markdown-it` instance with syntax highlighting (via highlight.js)
 * and exports a single `renderMarkdown()` function. All components that need to
 * render AI responses import from here — there's one renderer, one configuration.
 *
 * ## Why a Separate Module?
 *
 * 1. **Single source of truth** — markdown-it configuration lives in one place.
 *    If we change a setting (e.g., linkify, breaks), it applies everywhere.
 * 2. **Plugin extensibility** — R3 adds KaTeX, Mermaid, footnotes, and collapsible
 *    sections. Each plugin registers here without touching component code.
 * 3. **Testability** — the renderer can be unit tested in isolation.
 * 4. **Performance** — the markdown-it instance is created once at module load,
 *    not on every render. highlight.js languages are registered once.
 *
 * ## Syntax Highlighting
 *
 * Uses highlight.js with auto-detection. When a fenced code block specifies a
 * language (e.g., ```kotlin), highlight.js uses that hint. When no language is
 * specified, it attempts auto-detection from the code content.
 *
 * highlight.js ships ~190 languages. We import the full bundle for broad coverage
 * since Vite tree-shakes unused code and the single-file output inlines everything
 * anyway. If bundle size becomes a concern, switch to importing individual languages.
 *
 * ## Security
 *
 * HTML in markdown is DISABLED (`html: false`). AI responses should never inject
 * raw HTML into the DOM. All output goes through markdown-it's sanitization.
 *
 * @see MessageList.tsx — the main consumer of this module
 * @see https://github.com/markdown-it/markdown-it — markdown-it documentation
 * @see https://highlightjs.org/ — highlight.js documentation
 */

import MarkdownIt from "markdown-it";
import { escapeHtml } from "markdown-it/lib/common/utils.mjs";
import hljs from "highlight.js";
import footnotePlugin from "markdown-it-footnote";
import { katexPlugin } from "./katexPlugin";
import { collapsiblePlugin } from "./collapsiblePlugin";

// ═══════════════════════════════════════════════════════════════════════
//  MARKDOWN-IT INSTANCE
// ═══════════════════════════════════════════════════════════════════════

/**
 * Pre-configured markdown-it instance.
 *
 * Created once at module load time. All calls to `renderMarkdown()` use
 * this same instance — no per-render allocation.
 *
 * Configuration choices:
 * - `html: false` — security: never render raw HTML from AI responses
 * - `linkify: true` — auto-detect URLs and make them clickable
 * - `breaks: true` — convert single newlines to <br>, matching how chat
 *   messages typically expect line breaks to work
 * - `typographer: false` — don't convert quotes/dashes; preserve code accuracy
 * - `highlight` — delegate to highlight.js for fenced code blocks
 */
const md: MarkdownIt = new MarkdownIt({
    html: false,
    linkify: true,
    breaks: true,
    typographer: false,

    /**
     * Syntax highlighting callback for fenced code blocks.
     *
     * markdown-it calls this for every ```lang ... ``` block. We delegate
     * to highlight.js which handles language detection and tokenization.
     *
     * @param code The raw code content (no fences, no language tag)
     * @param lang The language hint from the fence (e.g., "kotlin", "python").
     *             Empty string if no language was specified.
     * @returns HTML string with <span> elements for syntax tokens.
     *          Returns empty string on failure — markdown-it falls back to
     *          escaping the code as plain text.
     */
    highlight(code: string, lang: string): string {
        // If a language was specified and highlight.js knows it, use it
        if (lang && hljs.getLanguage(lang)) {
            try {
                const result = hljs.highlight(code, { language: lang });
                return result.value;
            } catch {
                // Fall through to auto-detection
            }
        }

        // No language specified or unknown language — try auto-detection.
        // Auto-detection is slower but handles cases where the AI doesn't
        // specify a language in the fence.
        try {
            const result = hljs.highlightAuto(code);
            return result.value;
        } catch {
            // Complete failure — return empty string, markdown-it will escape it
            return "";
        }
    },
});

// ═══════════════════════════════════════════════════════════════════════
//  PLUGINS (R3)
//  Registered before custom rendering rules so plugins can set up their
//  own rules, then our fence override wraps everything.
// ═══════════════════════════════════════════════════════════════════════

/**
 * KaTeX — math rendering with currency protection and brace nesting.
 * Supports $, $$, \(, \[ delimiters.
 */
md.use(katexPlugin);

/**
 * Footnotes — [^1] references with definitions at the bottom.
 * AI models sometimes use footnotes for citations or clarifications.
 */
md.use(footnotePlugin);

/**
 * Collapsible sections — ```details fenced blocks become <details><summary>.
 * MUST be registered AFTER the fence rule is set up (below), because it
 * wraps the fence renderer. We register it at the end of this file.
 */

// ═══════════════════════════════════════════════════════════════════════
//  CUSTOM RENDERING RULES
// ═══════════════════════════════════════════════════════════════════════

/**
 * Override the default fence (code block) renderer to add:
 * - A language label in the top-right corner
 * - A wrapper div with a CSS class for the copy button (added by React)
 * - The `data-lang` attribute for the copy button to reference
 *
 * markdown-it's default fence renderer just outputs `<pre><code>...</code></pre>`.
 * We wrap it in a `<div class="ymm-code-block">` with metadata attributes
 * that React components use for the copy button and language display.
 */
md.renderer.rules.fence = (tokens, idx, options) => {
    const token = tokens[idx];

    // Extract the language from the info string (e.g., "kotlin" from ```kotlin)
    const langRaw = token.info.trim();
    const lang = langRaw ? langRaw.split(/\s+/)[0] : "";

    // Get the highlighted HTML. If the highlight function already ran
    // (via the `highlight` option above), token content is raw code.
    // We need to run highlighting here because markdown-it passes the
    // result of the highlight function as the inner HTML.
    let highlighted: string;
    if (options.highlight) {
        highlighted = options.highlight(token.content, lang, "") || escapeHtml(token.content);
    } else {
        highlighted = escapeHtml(token.content);
    }

    // Build the language label (shown in top-right of code block)
    const langLabel = lang ? `<span class="ymm-code-lang">${escapeHtml(lang)}</span>` : "";

    // Wrap in our custom structure with a header bar:
    // <div class="ymm-code-block" data-code="...">
    //   <div class="ymm-code-header">
    //     <span class="ymm-code-lang">kotlin</span>
    //     <div class="ymm-code-actions">
    //       <!-- Copy and Star buttons injected by MessageList.tsx useEffect -->
    //     </div>
    //   </div>
    //   <pre><code class="hljs language-kotlin">...highlighted...</code></pre>
    // </div>
    //
    // The data-code attribute holds the raw (unescaped) code for clipboard copy.
    // We base64-encode it to avoid issues with quotes and special characters
    // in the HTML attribute value.
    const encodedCode = btoa(unescape(encodeURIComponent(token.content)));
    const langClass = lang ? ` language-${escapeHtml(lang)}` : "";

    return `<div class="ymm-code-block" data-code="${encodedCode}">`
        + `<div class="ymm-code-header">`
        + langLabel
        + `<div class="ymm-code-actions"></div>`
        + `</div>`
        + `<pre><code class="hljs${langClass}">${highlighted}</code></pre>`
        + `</div>`;
};

/**
 * Override link rendering to open links in an external browser.
 *
 * Inside JCEF, clicking a link would navigate the embedded browser away
 * from the chat — breaking everything. We add target="_blank" so links
 * open in the user's default browser instead.
 */
const defaultLinkOpen =
    md.renderer.rules.link_open ||
    function (tokens, idx, options, _env, self) {
        return self.renderToken(tokens, idx, options);
    };

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    // Add target="_blank" to all links
    const targetIdx = tokens[idx].attrIndex("target");
    if (targetIdx < 0) {
        tokens[idx].attrPush(["target", "_blank"]);
    } else {
        tokens[idx].attrs![targetIdx][1] = "_blank";
    }

    // Also add rel="noopener" for security
    const relIdx = tokens[idx].attrIndex("rel");
    if (relIdx < 0) {
        tokens[idx].attrPush(["rel", "noopener"]);
    }

    return defaultLinkOpen(tokens, idx, options, env, self);
};

// ═══════════════════════════════════════════════════════════════════════
//  COLLAPSIBLE PLUGIN (must be registered AFTER the fence rule above)
//  It wraps the fence renderer to intercept `details` / `collapsible`
//  language tags while passing everything else through to our code block
//  renderer.
// ═══════════════════════════════════════════════════════════════════════

md.use(collapsiblePlugin);

// ═══════════════════════════════════════════════════════════════════════
//  PUBLIC API
// ═══════════════════════════════════════════════════════════════════════

/**
 * Render a markdown string to HTML.
 *
 * This is the single entry point for all markdown rendering in the app.
 * Components call this and set the result as `dangerouslySetInnerHTML`.
 *
 * @param text Raw markdown text (typically from an AI response)
 * @returns HTML string safe for insertion into the DOM.
 *          "Safe" because html option is disabled — no raw HTML passes through.
 *
 * @example
 * ```tsx
 * <div dangerouslySetInnerHTML={{ __html: renderMarkdown(message.content) }} />
 * ```
 */
export function renderMarkdown(text: string): string {
    return md.render(text);
}

/**
 * Render inline markdown (no block elements like paragraphs or headers).
 *
 * Useful for rendering markdown in contexts where block elements would
 * break layout, like system messages or single-line previews.
 *
 * @param text Raw markdown text
 * @returns HTML string with only inline elements (no <p>, <h1>, etc.)
 */
export function renderInlineMarkdown(text: string): string {
    return md.renderInline(text);
}