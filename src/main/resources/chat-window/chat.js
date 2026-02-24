/**
 * you-me-and-myself — Chat Interface Logic
 *
 * Phase 4B. Extracted from ChatBrowserComponent.kt into a standalone JS file.
 *
 * Initialization order:
 *   1. markdown-it initialized with highlight.js integration
 *   2. highlight.js configured
 *   3. Custom fence renderer installed (language labels + copy button)
 *   4. KaTeX math rendering plugin installed (if library available)
 *   5. Mermaid diagram support configured (if library available)
 *   6. Footnotes plugin installed (built-in, no library needed)
 *   7. Collapsible sections plugin installed (built-in, no library needed)
 *   8. Chat functions registered (addMessage, showThinking, etc.)
 *   9. Readiness signal sent to Kotlin
 *
 * ## Rich Rendering — Pluggable Architecture
 *
 * Each rendering feature follows this pattern:
 *   - Check if the library exists (window.katex, window.mermaid, etc.)
 *   - If yes: install markdown-it plugin or post-render hook
 *   - If no: log a warning, skip — no errors, no broken UI
 *
 * To add a new rendering feature:
 *   1. Drop the library JS file in src/main/resources/chat-window/
 *   2. Add a {{PLACEHOLDER}} in chat.html
 *   3. Add loadOptionalResource() call in ChatBrowserComponent.kt
 *   4. Add an initXxx() function here that checks for the library
 *   5. Call initXxx() in the DOMContentLoaded handler
 *
 * To remove a feature:
 *   - Delete the library file. Everything else no-ops gracefully.
 *
 * Bridge:
 *   window.sendToKotlin(message) — injected by ChatBrowserComponent at load time.
 *   The placeholder "/* __KOTLIN_BRIDGE__ *​/" is replaced with the actual
 *   JBCefJSQuery.inject() output.
 *
 * Message protocol (Kotlin → JS):
 *   addMessage(role, text, timestamp, exchangeId)  — render a chat message
 *   showThinking()                                  — show "Thinking..." indicator
 *   hideThinking()                                  — remove thinking indicator
 *   clearChat()                                     — wipe all messages
 *   setTurnCounterVisible(visible)                  — toggle turn counter display
 *   collapseAll() / expandAll()                     — bulk toggle responses
 *
 * Message protocol (JS → Kotlin):
 *   "READY"                        — DOM initialized, safe to call JS functions
 *   "COPY:<code>"                  — clipboard fallback when navigator.clipboard fails
 *   "BOOKMARK:<exchangeId>"        — user clicked bookmark star
 *   "UNBOOKMARK:<exchangeId>"      — user clicked active bookmark star
 */
(function () {
    "use strict";

    var isInitialized = false;

    // =====================================================================
    // BRIDGE — replaced by Kotlin at load time
    // =====================================================================

    window.sendToKotlin = function (message) {
        /* __KOTLIN_BRIDGE__ */
    };

    // =====================================================================
    // STATE
    // =====================================================================

    /** Running exchange count. Incremented each time addMessage("user",...) is called. */
    var turnCount = 0;

    /** Whether turn counters are visible. Toggled via setTurnCounterVisible(). */
    var showTurnCounters = true;

    /** Whether all responses are collapsed. Toggled via collapseAll()/expandAll(). */
    var allCollapsed = false;

    /** Map of exchangeId → { bookmarked: bool }. Driven by Kotlin via setBookmarkState(). */
    var bookmarkStates = {};

    /** Counter for code blocks — gives each a unique ID for copy functionality. */
    var codeBlockCounter = 0;

    /** Counter for mermaid diagrams — gives each a unique ID for rendering. */
    var mermaidCounter = 0;

    /** Whether mermaid library is available and initialized. */
    var mermaidReady = false;

    // =====================================================================
    // INITIALIZATION
    // =====================================================================

    document.addEventListener("DOMContentLoaded", function () {
        console.log("[YMM] DOMContentLoaded fired");

        if (isInitialized) {
            console.log("[YMM] Already initialized, skipping");
            return;
        }
        isInitialized = true;

        // Step 1: Initialize markdown-it with highlight.js integration
        initMarkdownIt();

        // Step 2: Configure highlight.js
        initHighlightJs();

        // Step 3: Install custom fence renderer (code blocks + mermaid interception)
        installFenceRenderer();

        // Step 4: Install KaTeX math rendering (if library available)
        initKaTeX();

        // Step 5: Configure Mermaid diagrams (if library available)
        initMermaid();

        // Step 6: Install footnotes plugin (built-in)
        initFootnotes();

        // Step 7: Install collapsible sections plugin (built-in)
        initCollapsible();

        // Step 8: Signal readiness to Kotlin
        window.YMM_READY = true;
        window.sendToKotlin("READY");
        console.log("[YMM] Initialization complete, YMM_READY=true");
    });

    // =====================================================================
    // MARKDOWN-IT SETUP
    // =====================================================================

    function initMarkdownIt() {
        if (typeof window.markdownit !== "undefined") {
            window.md = window.markdownit({
                html: true, // needed for <details>/<summary> collapsible sections
                linkify: true,
                breaks: true,
                highlight: function (str, lang) {
                    // Mermaid blocks are handled by the fence renderer, not highlighted
                    if (lang && lang.toLowerCase() === "mermaid") return str;

                    if (lang && window.hljs && window.hljs.getLanguage(lang)) {
                        try {
                            return window.hljs.highlight(str, { language: lang }).value;
                        } catch (e) {
                            console.warn("[YMM] highlight failed for", lang, e);
                        }
                    }
                    if (window.hljs) {
                        try {
                            return window.hljs.highlightAuto(str).value;
                        } catch (e) {
                            console.warn("[YMM] highlightAuto failed", e);
                        }
                    }
                    return escapeHtml(str);
                }
            });
            console.log("[YMM] markdown-it initialized with highlight support");
        } else {
            console.error("[YMM] markdown-it library not loaded!");
            window.md = {
                render: function (text) {
                    return escapeHtml(text);
                }
            };
        }
    }

    function initHighlightJs() {
        if (window.hljs) {
            window.hljs.configure({ ignoreUnescapedHTML: true });
            console.log("[YMM] highlight.js configured");
        } else {
            console.warn("[YMM] highlight.js not loaded — syntax highlighting disabled");
        }
    }

    // =====================================================================
    // CUSTOM FENCE RENDERER — language label + copy button + mermaid
    // =====================================================================

    function installFenceRenderer() {
        if (!window.md || !window.md.renderer) return;

        window.md.renderer.rules.fence = function (tokens, idx, options) {
            var token = tokens[idx];
            var rawCode = token.content || "";
            var rawLang = (token.info || "").trim().toLowerCase();

            // ── Mermaid interception ──
            // Instead of rendering as a code block, emit a placeholder div.
            // After the message is added to the DOM, postRenderMermaid() finds
            // these divs and calls mermaid.render() on them.
            if (rawLang === "mermaid" && mermaidReady) {
                var mermaidId = "mermaid-" + (mermaidCounter++);
                return '<div class="mermaid-container">' +
                    '<div class="mermaid-diagram" id="' + mermaidId + '">' +
                    escapeHtml(rawCode) +
                    '</div></div>';
            }

            var langLabel = rawLang ? rawLang.toUpperCase() : "CODE";
            var blockId = "code-block-" + (codeBlockCounter++);

            var highlightedCode = "";
            if (options.highlight) {
                highlightedCode = options.highlight(rawCode, rawLang) || escapeHtml(rawCode);
            } else {
                highlightedCode = escapeHtml(rawCode);
            }

            var encodedCode = btoa(unescape(encodeURIComponent(rawCode)));

            return '<div class="code-block" id="' + blockId + '" data-code="' + encodedCode + '">' +
                '<div class="code-block-header">' +
                '<span class="lang-label">' + escapeHtml(langLabel) + '</span>' +
                '<button class="copy-btn" onclick="window.copyCodeBlock(\'' + blockId + '\')" title="Copy code">' +
                '<span class="copy-icon">⧉</span><span class="copy-text">Copy</span>' +
                '</button>' +
                '</div>' +
                '<pre><code class="hljs ' + escapeAttr(rawLang) + '">' + highlightedCode + '</code></pre>' +
                '</div>';
        };

        console.log("[YMM] Custom fence renderer installed");
    }

    // =====================================================================
    // KATEX — Math rendering
    //
    // Pluggable: if window.katex is not available, this is a no-op.
    //
    // Supported delimiter styles:
    //
    //   Delimiter         Context        displayMode
    //   ─────────────     ──────────     ───────────
    //   $...$             inline         false        (standard TeX)
    //   $$...$$           inline/block   true         (standard TeX)
    //   \(...\)           inline         false        (LaTeX, used by Claude/GPT)
    //   \[...\]           inline/block   true         (LaTeX, used by Claude/GPT)
    //
    // Implementation:
    //
    //   INLINE RULER handles: $, $$, \(, \[
    //     These all appear within paragraph text. The ruler checks for each
    //     delimiter type and scans for its matching closing delimiter.
    //     $$ and \[ produce displayMode:true tokens.
    //
    //   BLOCK RULER handles: $$ and \[ on their own lines (multi-line)
    //     Classic freestanding math blocks where the delimiters are on
    //     separate lines from the content.
    //
    // False-positive protection:
    //   - Escaped delimiters (\$, \\(, \\[) are skipped
    //   - Single $ followed by digits (currency: $5, $10.99) is skipped
    //   - Empty content between delimiters is skipped
    //   - Brace nesting {} is tracked (don't close on $ inside braces)
    // =====================================================================

    function initKaTeX() {
        if (typeof window.katex === "undefined") {
            console.warn("[YMM] KaTeX not loaded — math rendering disabled");
            return;
        }
        if (!window.md || !window.md.inline) {
            console.warn("[YMM] Cannot install KaTeX — markdown-it not available");
            return;
        }

        // ── Helper: scan for closing delimiter in source string ──
        // Handles brace nesting and backslash escapes.
        // Returns index of first char of closing delimiter, or -1 if not found.

        function scanForClose(src, start, max, closeDelim) {
            var end = start;
            var nestDepth = 0;
            var closeLen = closeDelim.length;

            while (end <= max - closeLen) {
                var ch = src.charCodeAt(end);

                // Track brace nesting
                if (ch === 0x7B /* { */) { nestDepth++; end++; continue; }
                if (ch === 0x7D /* } */) { nestDepth = Math.max(0, nestDepth - 1); end++; continue; }

                // Handle backslash
                if (ch === 0x5C /* \ */ && end + 1 < max) {
                    var nextCh = src.charAt(end + 1);

                    // Check if this backslash starts our closing delimiter
                    if (closeDelim === "\\)" && nextCh === ")") {
                        if (nestDepth === 0) return end;
                        end += 2; continue;
                    }
                    if (closeDelim === "\\]" && nextCh === "]") {
                        if (nestDepth === 0) return end;
                        end += 2; continue;
                    }

                    // Regular escape — skip both chars
                    end += 2;
                    continue;
                }

                // Check for $ and $$ closing delimiters
                if (ch === 0x24 /* $ */ && nestDepth === 0) {
                    if (closeDelim === "$$" && end + 1 < max && src.charCodeAt(end + 1) === 0x24) {
                        return end;
                    }
                    if (closeDelim === "$") {
                        // Make sure it's not $$ (which would be a different delimiter)
                        if (end + 1 < max && src.charCodeAt(end + 1) === 0x24) {
                            end++; continue; // skip — this is $$
                        }
                        return end;
                    }
                }

                end++;
            }
            return -1;
        }

        // ── Inline ruler: handles $, $$, \(, \[ within paragraphs ──

        window.md.inline.ruler.after("escape", "math_inline", function (state, silent) {
            var src = state.src;
            var pos = state.pos;
            var max = state.posMax;
            var ch = src.charCodeAt(pos);

            var openDelim, closeDelim, isDisplay;

            if (ch === 0x5C /* \ */) {
                // Check for \( or \[
                if (pos + 1 >= max) return false;
                var next = src.charCodeAt(pos + 1);

                // Make sure this backslash isn't itself escaped (\\)
                if (pos > 0 && src.charCodeAt(pos - 1) === 0x5C) return false;

                if (next === 0x28 /* ( */) {
                    openDelim = "\\("; closeDelim = "\\)"; isDisplay = false;
                } else if (next === 0x5B /* [ */) {
                    openDelim = "\\["; closeDelim = "\\]"; isDisplay = true;
                } else {
                    return false;
                }
            } else if (ch === 0x24 /* $ */) {
                // Check for escaped dollar
                if (pos > 0 && src.charCodeAt(pos - 1) === 0x5C) return false;

                if (pos + 1 < max && src.charCodeAt(pos + 1) === 0x24) {
                    openDelim = "$$"; closeDelim = "$$"; isDisplay = true;
                } else {
                    openDelim = "$"; closeDelim = "$"; isDisplay = false;

                    // Currency false-positive check: $5, $10.99, etc.
                    var afterDollar = src.slice(pos + 1);
                    if (/^(\d+([.,]\d+)?)(\s|$|\)|,|;|\.(?!\w))/.test(afterDollar)) return false;
                }
            } else {
                return false;
            }

            var start = pos + openDelim.length;
            if (start >= max) return false;

            // Scan for closing delimiter
            var closePos = scanForClose(src, start, max, closeDelim);
            if (closePos < 0) return false;

            var content = src.slice(start, closePos).trim();
            if (!content) return false;

            if (!silent) {
                var tokenType = isDisplay ? "math_display_inline" : "math_inline";
                var token = state.push(tokenType, "math", 0);
                token.content = content;
                token.markup = openDelim;
            }

            state.pos = closePos + closeDelim.length;
            return true;
        });

        // ── Renderer: inline math — displayMode: false ──

        window.md.renderer.rules.math_inline = function (tokens, idx) {
            try {
                return window.katex.renderToString(tokens[idx].content, {
                    throwOnError: false,
                    displayMode: false
                });
            } catch (e) {
                console.warn("[YMM] KaTeX inline render error:", e);
                return '<code class="katex-error">' + escapeHtml(tokens[idx].content) + '</code>';
            }
        };

        // ── Renderer: display math within a paragraph — displayMode: true ──

        window.md.renderer.rules.math_display_inline = function (tokens, idx) {
            try {
                return '<div class="katex-block">' +
                    window.katex.renderToString(tokens[idx].content, {
                        throwOnError: false,
                        displayMode: true
                    }) +
                    '</div>';
            } catch (e) {
                console.warn("[YMM] KaTeX display render error:", e);
                return '<pre class="katex-error">' + escapeHtml(tokens[idx].content) + '</pre>';
            }
        };

        // ── Block ruler: freestanding $$ or \[ on their own lines ──
        //
        // Handles multi-line math:
        //   $$                    \[
        //   content               content
        //   $$                    \]
        //
        // And single-line freestanding:
        //   $$ content $$         \[ content \]

        window.md.block.ruler.after("fence", "math_block", function (state, startLine, endLine, silent) {
            var pos = state.bMarks[startLine] + state.tShift[startLine];
            var max = state.eMarks[startLine];
            var lineText = state.src.slice(pos, max);

            // Detect which delimiter style
            var openDelim, closeDelim;
            if (lineText.substr(0, 2) === "$$") {
                openDelim = "$$"; closeDelim = "$$";
            } else if (lineText.substr(0, 2) === "\\[") {
                openDelim = "\\["; closeDelim = "\\]";
            } else {
                return false;
            }

            var afterOpen = lineText.slice(openDelim.length).trim();

            // Check for single-line freestanding: $$ content $$ or \[ content \]
            if (afterOpen && afterOpen.endsWith(closeDelim)) {
                var mathContent = afterOpen.slice(0, -closeDelim.length).trim();
                if (!mathContent) return false;

                if (silent) return true;
                var token = state.push("math_block", "math", 0);
                token.content = mathContent;
                token.markup = openDelim;
                token.map = [startLine, startLine + 1];
                state.line = startLine + 1;
                return true;
            }

            // Opening delimiter with no closing on same line
            if (afterOpen) return false;

            // Scan subsequent lines for closing delimiter
            var nextLine = startLine + 1;
            while (nextLine < endLine) {
                pos = state.bMarks[nextLine] + state.tShift[nextLine];
                max = state.eMarks[nextLine];
                var line = state.src.slice(pos, max).trim();

                if (line === closeDelim) {
                    if (silent) return true;
                    var content = "";
                    for (var i = startLine + 1; i < nextLine; i++) {
                        content += state.src.slice(
                            state.bMarks[i] + state.tShift[i],
                            state.eMarks[i]
                        ) + "\n";
                    }
                    var token = state.push("math_block", "math", 0);
                    token.content = content.trim();
                    token.markup = openDelim;
                    token.map = [startLine, nextLine + 1];
                    state.line = nextLine + 1;
                    return true;
                }
                nextLine++;
            }

            return false;
        });

        // ── Renderer: block math — displayMode: true ──

        window.md.renderer.rules.math_block = function (tokens, idx) {
            try {
                return '<div class="katex-block">' +
                    window.katex.renderToString(tokens[idx].content, {
                        throwOnError: false,
                        displayMode: true
                    }) +
                    '</div>';
            } catch (e) {
                console.warn("[YMM] KaTeX block render error:", e);
                return '<pre class="katex-error">' + escapeHtml(tokens[idx].content) + '</pre>';
            }
        };

        console.log("[YMM] KaTeX math rendering installed ($, $$, \\(, \\[)");
    }

    // =====================================================================
    // MERMAID — Diagram rendering
    //
    // Pluggable: if window.mermaid is not available, this is a no-op.
    //
    // Strategy:
    // 1. installFenceRenderer() intercepts ```mermaid blocks and emits
    //    placeholder divs with class "mermaid-diagram"
    // 2. After addMessage() inserts the HTML into the DOM, postRenderMermaid()
    //    finds unrendered mermaid divs and calls mermaid.render()
    // 3. The rendered SVG replaces the placeholder content
    //
    // This two-phase approach is necessary because mermaid.render() needs
    // the element to be in the DOM.
    // =====================================================================

    function initMermaid() {
        if (typeof window.mermaid === "undefined") {
            console.warn("[YMM] Mermaid not loaded — diagram rendering disabled");
            return;
        }

        try {
            window.mermaid.initialize({
                startOnLoad: false,   // We render manually after DOM insertion
                theme: "dark",
                themeVariables: {
                    darkMode: true,
                    background: "#1e1e1e",
                    primaryColor: "#264f78",
                    primaryTextColor: "#cccccc",
                    primaryBorderColor: "#444444",
                    lineColor: "#666666",
                    secondaryColor: "#2d2d2d",
                    tertiaryColor: "#333333",
                    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                    fontSize: "13px"
                },
                securityLevel: "strict",
                flowchart: { htmlLabels: true, curve: "basis" },
                sequence: { useMaxWidth: true }
            });
            mermaidReady = true;
            console.log("[YMM] Mermaid initialized with dark theme");
        } catch (e) {
            console.error("[YMM] Mermaid initialization failed:", e);
            mermaidReady = false;
        }
    }

    /**
     * Post-render hook: find unrendered mermaid diagrams in the DOM and render them.
     *
     * Called after addMessage() inserts HTML. Finds all .mermaid-diagram elements
     * that haven't been rendered yet (no data-processed attribute) and calls
     * mermaid.render() on each.
     *
     * @param {HTMLElement} container — the element to search within (usually the exchange div)
     */
    function postRenderMermaid(container) {
        if (!mermaidReady) return;

        var diagrams = container.querySelectorAll(".mermaid-diagram:not([data-processed])");
        if (diagrams.length === 0) return;

        diagrams.forEach(function (el) {
            var id = el.id;
            var definition = el.textContent.trim();
            el.setAttribute("data-processed", "true");

            try {
                // mermaid.render is async in v10+
                if (typeof window.mermaid.render === "function") {
                    window.mermaid.render("svg-" + id, definition).then(function (result) {
                        el.innerHTML = result.svg;
                        el.classList.add("mermaid-rendered");
                    }).catch(function (err) {
                        console.error("[YMM] Mermaid render failed for " + id + ":", err);
                        el.innerHTML = '<pre class="mermaid-error">' + escapeHtml(definition) + '</pre>' +
                            '<div class="mermaid-error-msg">Diagram rendering failed: ' + escapeHtml(String(err)) + '</div>';
                    });
                }
            } catch (e) {
                console.error("[YMM] Mermaid render error:", e);
                el.innerHTML = '<pre class="mermaid-error">' + escapeHtml(definition) + '</pre>';
            }
        });
    }

    // =====================================================================
    // FOOTNOTES — [^1] references and [^1]: definitions
    //
    // Built-in: no external library needed.
    //
    // Syntax:
    //   Here is a claim[^1] and another[^note].
    //
    //   [^1]: Source for the first claim.
    //   [^note]: Source for the second claim.
    //
    // Rendered as superscript links that scroll to footnote definitions
    // at the bottom of the message.
    // =====================================================================

    function initFootnotes() {
        if (!window.md) return;

        // We implement footnotes as a post-processing step on the rendered HTML.
        // This is simpler and more robust than adding markdown-it rules, because
        // footnote definitions can appear anywhere in the source.

        var originalRender = window.md.render.bind(window.md);

        window.md.render = function (src) {
            // Extract footnote definitions: [^id]: text
            var definitions = {};
            var cleanSrc = src.replace(/^\[\^([^\]]+)\]:\s*(.+)$/gm, function (match, id, text) {
                definitions[id] = text.trim();
                return ""; // Remove definition from source
            });

            // Render the cleaned source
            var html = originalRender(cleanSrc);

            // If no footnotes found, return as-is
            var ids = Object.keys(definitions);
            if (ids.length === 0) return html;

            // Replace [^id] references with superscript links
            var footnoteIndex = 0;
            var usedFootnotes = [];
            html = html.replace(/\[\^([^\]]+)\]/g, function (match, id) {
                if (definitions[id] !== undefined) {
                    footnoteIndex++;
                    usedFootnotes.push({ index: footnoteIndex, id: id, text: definitions[id] });
                    return '<sup class="footnote-ref"><a href="#fn-' + escapeAttr(id) + '" id="fnref-' + escapeAttr(id) + '">' + footnoteIndex + '</a></sup>';
                }
                return match; // Not a defined footnote, leave as-is
            });

            // Append footnote section
            if (usedFootnotes.length > 0) {
                html += '<hr class="footnotes-sep" />';
                html += '<section class="footnotes"><ol class="footnotes-list">';
                usedFootnotes.forEach(function (fn) {
                    html += '<li id="fn-' + escapeAttr(fn.id) + '" class="footnote-item"><p>' +
                        originalRender(fn.text).replace(/<\/?p>/g, '') +
                        ' <a href="#fnref-' + escapeAttr(fn.id) + '" class="footnote-backref">↩</a></p></li>';
                });
                html += '</ol></section>';
            }

            return html;
        };

        console.log("[YMM] Footnotes plugin installed");
    }

    // =====================================================================
    // COLLAPSIBLE SECTIONS — :::details / :::
    //
    // Built-in: no external library needed.
    //
    // Syntax:
    //   :::details Click to expand
    //   Hidden content here.
    //   Can be **markdown**.
    //   :::
    //
    // Rendered as <details><summary>Click to expand</summary>...</details>
    //
    // Also supports raw HTML <details> tags since we enabled html:true
    // in markdown-it config.
    // =====================================================================

    function initCollapsible() {
        if (!window.md) return;

        // Install as a block rule that detects :::details ... ::: blocks
        window.md.block.ruler.before("fence", "collapsible", function (state, startLine, endLine, silent) {
            var pos = state.bMarks[startLine] + state.tShift[startLine];
            var max = state.eMarks[startLine];
            var lineText = state.src.slice(pos, max);

            // Must start with :::details
            if (!lineText.match(/^:::details\s*(.*)?$/)) return false;

            var summary = lineText.replace(/^:::details\s*/, "").trim() || "Details";

            // Find closing :::
            var nextLine = startLine + 1;
            while (nextLine < endLine) {
                pos = state.bMarks[nextLine] + state.tShift[nextLine];
                max = state.eMarks[nextLine];
                if (state.src.slice(pos, max).trim() === ":::") {
                    break;
                }
                nextLine++;
            }

            if (nextLine >= endLine) return false; // No closing :::

            if (silent) return true;

            // Emit tokens
            var tokenOpen = state.push("collapsible_open", "details", 1);
            tokenOpen.markup = ":::details";
            tokenOpen.map = [startLine, nextLine + 1];
            tokenOpen.attrSet("class", "collapsible-section");

            var tokenSummary = state.push("collapsible_summary", "", 0);
            tokenSummary.content = summary;

            // Parse the inner content as markdown
            var oldParent = state.parentType;
            var oldLineMax = state.lineMax;
            state.parentType = "collapsible";
            state.lineMax = nextLine;
            state.md.block.tokenize(state, startLine + 1, nextLine);
            state.parentType = oldParent;
            state.lineMax = oldLineMax;

            var tokenClose = state.push("collapsible_close", "details", -1);
            tokenClose.markup = ":::";

            state.line = nextLine + 1;
            return true;
        });

        // Renderers
        window.md.renderer.rules.collapsible_open = function () {
            return '<details class="collapsible-section">';
        };

        window.md.renderer.rules.collapsible_summary = function (tokens, idx) {
            return '<summary>' + escapeHtml(tokens[idx].content) + '</summary>';
        };

        window.md.renderer.rules.collapsible_close = function () {
            return '</details>';
        };

        console.log("[YMM] Collapsible sections plugin installed");
    }

    // =====================================================================
    // CHAT FUNCTIONS — called from Kotlin via executeWhenReady()
    // =====================================================================

    /**
     * Add a message to the chat.
     *
     * When role is "user", a new exchange wrapper is created with a turn counter.
     * The user bubble goes inside it.
     *
     * When role is "assistant", the response is appended to the most recent
     * exchange wrapper. After DOM insertion, post-render hooks run (Mermaid).
     *
     * @param {string} role        — "user" or "assistant"
     * @param {string} text        — message content (markdown for assistant, plain for user)
     * @param {string} timestamp   — display time (e.g. "14:30")
     * @param {string} exchangeId  — unique exchange ID for bookmarking (optional, falls back to generated)
     */
    window.addMessage = function (role, text, timestamp, exchangeId) {
        if (role === "assistant") {
            hideThinking();
        }

        var chatContainer = document.getElementById("chatContainer");
        if (!chatContainer) {
            console.error("[YMM] chatContainer not found");
            return;
        }

        // Hide empty state if present
        var emptyState = document.getElementById("emptyState");
        if (emptyState) emptyState.classList.add("hidden");

        // Show toolbar once we have messages
        var toolbar = document.getElementById("chatToolbar");
        if (toolbar) toolbar.classList.remove("hidden");

        if (role === "user") {
            // --- New exchange: create wrapper with turn counter ---
            turnCount++;
            var exId = exchangeId || ("ex-" + turnCount + "-" + Date.now());

            var exchange = document.createElement("div");
            exchange.className = "exchange";
            exchange.setAttribute("data-exchange-id", exId);
            exchange.setAttribute("data-turn", turnCount);

            // Exchange header: turn counter only (star is in footer, after the response)
            var header = document.createElement("div");
            header.className = "exchange-header";

            var counter = document.createElement("span");
            counter.className = "turn-counter" + (showTurnCounters ? "" : " hidden");
            counter.textContent = "#" + turnCount;

            header.appendChild(counter);
            exchange.appendChild(header);

            // User message bubble
            var msg = document.createElement("div");
            msg.className = "message user";
            msg.textContent = text || "";

            if (timestamp) {
                var ts = document.createElement("div");
                ts.className = "timestamp";
                ts.textContent = timestamp;
                msg.appendChild(ts);
            }

            exchange.appendChild(msg);
            chatContainer.appendChild(exchange);

        } else {
            // --- Assistant response: append to current exchange ---
            var currentExchange = chatContainer.querySelector(".exchange:last-child");

            var msg = document.createElement("div");
            msg.className = "message assistant";

            // Generate a unique ID for this assistant message (for collapse targeting)
            var msgId = "assistant-msg-" + turnCount;
            msg.id = msgId;

            // Render markdown
            if (window.md) {
                msg.innerHTML = window.md.render(text || "");
            } else {
                msg.innerHTML = escapeHtml(text || "");
            }

            if (currentExchange) {
                currentExchange.appendChild(msg);

                // Post-render hooks: Mermaid diagrams need to be in the DOM first
                postRenderMermaid(currentExchange);

                // Exchange footer: timestamp + bookmark star + collapse toggle
                var footer = document.createElement("div");
                footer.className = "exchange-footer";

                var footerTs = document.createElement("div");
                footerTs.className = "timestamp";
                footerTs.textContent = timestamp || "";

                var footerRight = document.createElement("div");
                footerRight.className = "exchange-footer-right";

                // Bookmark star — stars the exchange (response)
                var exId = currentExchange.getAttribute("data-exchange-id") || "";
                var star = document.createElement("button");
                star.className = "bookmark-star";
                star.title = "Bookmark this exchange";
                star.textContent = "☆";
                star.setAttribute("data-exchange-id", exId);
                star.onclick = function () {
                    toggleBookmark(exId, star);
                };

                // Restore bookmark state if set by Kotlin before render
                if (bookmarkStates[exId] && bookmarkStates[exId].bookmarked) {
                    star.textContent = "★";
                    star.classList.add("bookmarked");
                }

                var collapseBtn = document.createElement("button");
                collapseBtn.className = "collapse-toggle";
                collapseBtn.textContent = "▲ Collapse";
                collapseBtn.onclick = function () {
                    toggleCollapse(msgId, collapseBtn);
                };

                footerRight.appendChild(star);
                footerRight.appendChild(collapseBtn);
                footer.appendChild(footerTs);
                footer.appendChild(footerRight);
                currentExchange.appendChild(footer);

                // If "collapse all" is active, collapse this new message too
                if (allCollapsed) {
                    msg.classList.add("collapsed");
                    collapseBtn.textContent = "▼ Expand";
                }
            } else {
                // Standalone assistant message (no preceding user message — e.g. system)
                if (timestamp) {
                    var ts = document.createElement("div");
                    ts.className = "timestamp";
                    ts.textContent = timestamp;
                    msg.appendChild(ts);
                }
                chatContainer.appendChild(msg);

                // Post-render hooks for standalone messages
                postRenderMermaid(msg);
            }
        }

        // Scroll to bottom
        chatContainer.scrollTop = chatContainer.scrollHeight;
    };

    /**
     * Show "Thinking..." indicator while waiting for AI response.
     */
    window.showThinking = function () {
        var chatContainer = document.getElementById("chatContainer");
        if (!chatContainer) return;

        if (document.getElementById("thinking")) return;

        var bubble = document.createElement("div");
        bubble.id = "thinking";
        bubble.className = "message assistant thinking";
        bubble.innerHTML = '<span class="thinking-dots">Thinking<span>.</span><span>.</span><span>.</span></span>';
        chatContainer.appendChild(bubble);
        chatContainer.scrollTop = chatContainer.scrollHeight;
    };

    /**
     * Hide the "Thinking..." indicator.
     */
    function hideThinking() {
        var t = document.getElementById("thinking");
        if (t) t.remove();
    }
    window.hideThinking = hideThinking;

    /**
     * Clear all messages from the chat container. Resets turn count.
     */
    window.clearChat = function () {
        var chatContainer = document.getElementById("chatContainer");
        if (chatContainer) {
            chatContainer.innerHTML = "";
        }
        turnCount = 0;
        allCollapsed = false;

        // Show empty state again
        var emptyState = document.getElementById("emptyState");
        if (emptyState) emptyState.classList.remove("hidden");

        // Hide toolbar
        var toolbar = document.getElementById("chatToolbar");
        if (toolbar) toolbar.classList.add("hidden");

        // Reset collapse all button
        updateCollapseAllBtn();
    };

    // =====================================================================
    // EXPAND / COLLAPSE
    // =====================================================================

    function toggleCollapse(msgId, btn) {
        var msg = document.getElementById(msgId);
        if (!msg) return;

        if (msg.classList.contains("collapsed")) {
            msg.classList.remove("collapsed");
            btn.textContent = "▲ Collapse";
        } else {
            msg.classList.add("collapsed");
            btn.textContent = "▼ Expand";
        }
    }

    window.collapseAll = function () {
        allCollapsed = true;
        var messages = document.querySelectorAll(".message.assistant:not(.thinking)");
        messages.forEach(function (msg) {
            msg.classList.add("collapsed");
        });
        var buttons = document.querySelectorAll(".collapse-toggle");
        buttons.forEach(function (btn) {
            btn.textContent = "▼ Expand";
        });
        updateCollapseAllBtn();
    };

    window.expandAll = function () {
        allCollapsed = false;
        var messages = document.querySelectorAll(".message.assistant.collapsed");
        messages.forEach(function (msg) {
            msg.classList.remove("collapsed");
        });
        var buttons = document.querySelectorAll(".collapse-toggle");
        buttons.forEach(function (btn) {
            btn.textContent = "▲ Collapse";
        });
        updateCollapseAllBtn();
    };

    function updateCollapseAllBtn() {
        var btn = document.getElementById("collapseAllBtn");
        if (!btn) return;
        btn.textContent = allCollapsed ? "▼ Expand All" : "▲ Collapse All";
    }

    window.toggleCollapseAll = function () {
        if (allCollapsed) {
            window.expandAll();
        } else {
            window.collapseAll();
        }
    };

    // =====================================================================
    // TURN COUNTER
    // =====================================================================

    window.setTurnCounterVisible = function (visible) {
        showTurnCounters = visible;
        var counters = document.querySelectorAll(".turn-counter");
        counters.forEach(function (el) {
            if (visible) {
                el.classList.remove("hidden");
            } else {
                el.classList.add("hidden");
            }
        });
    };

    // =====================================================================
    // BOOKMARKS
    // =====================================================================

    function toggleBookmark(exchangeId, star) {
        var isBookmarked = star.classList.contains("bookmarked");

        if (isBookmarked) {
            star.textContent = "☆";
            star.classList.remove("bookmarked");
            bookmarkStates[exchangeId] = { bookmarked: false };
            window.sendToKotlin("UNBOOKMARK:" + exchangeId);
        } else {
            star.textContent = "★";
            star.classList.add("bookmarked");
            bookmarkStates[exchangeId] = { bookmarked: true };
            window.sendToKotlin("BOOKMARK:" + exchangeId);
        }
    }

    window.setBookmarkState = function (exchangeId, bookmarked) {
        bookmarkStates[exchangeId] = { bookmarked: bookmarked };

        var exchange = document.querySelector('.exchange[data-exchange-id="' + exchangeId + '"]');
        if (exchange) {
            var star = exchange.querySelector(".bookmark-star");
            if (star) {
                star.textContent = bookmarked ? "★" : "☆";
                if (bookmarked) {
                    star.classList.add("bookmarked");
                } else {
                    star.classList.remove("bookmarked");
                }
            }
        }
    };

    // =====================================================================
    // COPY TO CLIPBOARD
    // =====================================================================

    window.copyCodeBlock = function (blockId) {
        var block = document.getElementById(blockId);
        if (!block) {
            console.error("[YMM] Code block not found:", blockId);
            return;
        }

        var encodedCode = block.getAttribute("data-code");
        if (!encodedCode) {
            console.error("[YMM] No code data found for block:", blockId);
            return;
        }

        var rawCode;
        try {
            rawCode = decodeURIComponent(escape(atob(encodedCode)));
        } catch (e) {
            console.error("[YMM] Failed to decode code:", e);
            return;
        }

        navigator.clipboard.writeText(rawCode).then(function () {
            showCopyFeedback(block);
        }).catch(function (err) {
            console.error("[YMM] Clipboard write failed:", err);
            window.sendToKotlin("COPY:" + rawCode);
            showCopyFeedback(block);
        });
    };

    function showCopyFeedback(block) {
        var btn = block.querySelector(".copy-btn");
        if (!btn) return;

        var originalHTML = btn.innerHTML;
        btn.innerHTML = '<span class="copy-icon">✓</span><span class="copy-text">Copied!</span>';
        btn.classList.add("copied");

        setTimeout(function () {
            btn.innerHTML = originalHTML;
            btn.classList.remove("copied");
        }, 2000);
    }

    // =====================================================================
    // METRICS WIDGET
    // =====================================================================

    window.updateMetrics = function (data) {
        var widget = document.getElementById("metricsWidget");
        if (!widget) return;

        widget.classList.remove("hidden");

        var html = '';
        if (data.model) {
            html += '<span class="metric"><span class="metric-label">Model:</span> <span class="metric-value">' + escapeHtml(data.model) + '</span></span>';
        }
        if (data.totalTokens != null) {
            html += '<span class="metric"><span class="metric-value">' + formatTokens(data.promptTokens || 0) + '</span>';
            html += '<span class="metric-label">→</span>';
            html += '<span class="metric-value">' + formatTokens(data.completionTokens || 0) + '</span>';
            html += '<span class="metric-label">(' + formatTokens(data.totalTokens) + ')</span></span>';
        }
        if (data.estimatedCost != null) {
            html += '<span class="metric"><span class="metric-label">~$</span><span class="metric-value">' + data.estimatedCost + '</span></span>';
        }

        widget.innerHTML = html;
    };

    window.hideMetrics = function () {
        var widget = document.getElementById("metricsWidget");
        if (widget) widget.classList.add("hidden");
    };

    // =====================================================================
    // UTILITIES
    // =====================================================================

    function escapeHtml(text) {
        var div = document.createElement("div");
        div.textContent = text || "";
        return div.innerHTML;
    }
    window.escapeHtml = escapeHtml;

    function escapeAttr(text) {
        return (text || "").replace(/&/g, "&amp;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
    }

    function formatTokens(n) {
        if (n == null) return "—";
        if (n >= 1000000) return (n / 1000000).toFixed(1) + "M";
        if (n >= 1000) return (n / 1000).toFixed(1) + "k";
        return String(n);
    }

})();