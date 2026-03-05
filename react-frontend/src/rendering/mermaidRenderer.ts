/**
 * Mermaid diagram rendering for AI chat responses.
 *
 * ## Why This Is Separate From markdownRenderer.ts
 *
 * Mermaid rendering is **asynchronous** — it needs to parse the diagram
 * definition, lay it out, and generate SVG. markdown-it's pipeline is
 * synchronous, so we can't render Mermaid inline during markdown processing.
 *
 * Instead, we use a two-phase approach:
 *
 * 1. **Detection** (in markdownRenderer.ts) — the existing fence rule
 *    already handles `mermaid` code blocks. They get a `.ymm-code-block`
 *    wrapper with the language label "mermaid" and `data-code` containing
 *    the raw diagram definition.
 *
 * 2. **Post-render** (this module) — after React inserts the markdown HTML
 *    into the DOM, `renderMermaidBlocks()` scans for mermaid code blocks
 *    and replaces them with rendered SVG diagrams.
 *
 * ## How It's Called
 *
 * MessageList.tsx calls `renderMermaidBlocks(containerRef)` in a useEffect
 * after messages change. It finds all `.ymm-code-block` elements where the
 * language label says "mermaid" and replaces the `<pre><code>` with an SVG.
 *
 * ## Error Handling
 *
 * If Mermaid fails to parse a diagram (invalid syntax), the code block stays
 * as-is — the user sees the raw mermaid code with syntax highlighting. An
 * error message is appended below the code block so the user knows rendering
 * failed.
 *
 * ## Performance
 *
 * Mermaid is initialized once on first use (lazy). Each diagram is rendered
 * individually. Already-rendered blocks are marked with `data-mermaid-rendered`
 * to avoid re-processing on subsequent renders.
 *
 * @see MessageList.tsx — calls renderMermaidBlocks() in useEffect
 * @see markdownRenderer.ts — fence rule that creates the code block HTML
 */

import mermaid from "mermaid";

// ═══════════════════════════════════════════════════════════════════════
//  INITIALIZATION
// ═══════════════════════════════════════════════════════════════════════

/** Whether mermaid.initialize() has been called. */
let initialized = false;

/**
 * Initialize Mermaid with dark theme matching IntelliJ Darcula.
 *
 * Called lazily on first render request. Subsequent calls are no-ops.
 * We disable startOnLoad because we control rendering manually.
 */
function ensureInitialized(): void {
    if (initialized) return;

    mermaid.initialize({
        startOnLoad: false,
        theme: "dark",
        themeVariables: {
            // Match the IntelliJ Darcula color palette
            primaryColor: "#264f78",
            primaryTextColor: "#d4d4d4",
            primaryBorderColor: "#555",
            lineColor: "#808080",
            secondaryColor: "#2d2d2d",
            tertiaryColor: "#1e1e1e",
            fontFamily: "system-ui, -apple-system, sans-serif",
            fontSize: "13px",
        },
        // Security: disable dangerous diagram types in an IDE context
        securityLevel: "strict",
    });

    initialized = true;
}

// ═══════════════════════════════════════════════════════════════════════
//  COUNTER FOR UNIQUE IDS
// ═══════════════════════════════════════════════════════════════════════

/**
 * Monotonically increasing counter for generating unique Mermaid container IDs.
 * Mermaid requires a unique ID for each diagram it renders.
 */
let renderCounter = 0;

// ═══════════════════════════════════════════════════════════════════════
//  PUBLIC API
// ═══════════════════════════════════════════════════════════════════════

/**
 * Scan a DOM container for mermaid code blocks and render them as SVG diagrams.
 *
 * Call this after React inserts markdown HTML into the DOM. It finds all
 * `.ymm-code-block` elements where the language is "mermaid" and replaces
 * the code display with a rendered SVG diagram.
 *
 * Already-rendered blocks (marked with `data-mermaid-rendered="true"`) are
 * skipped to avoid re-processing on subsequent React renders.
 *
 * @param container The DOM element to scan for mermaid code blocks.
 *                  Typically the message list ref.
 */
export async function renderMermaidBlocks(
    container: HTMLElement,
): Promise<void> {
    // Find all code blocks — check the language label for "mermaid"
    const codeBlocks = container.querySelectorAll(".ymm-code-block");

    for (const block of codeBlocks) {
        // Skip already-rendered blocks
        if (block.getAttribute("data-mermaid-rendered") === "true") continue;

        // Check if this is a mermaid block by looking at the language label
        const langLabel = block.querySelector(".ymm-code-lang");
        if (!langLabel || langLabel.textContent?.trim().toLowerCase() !== "mermaid") {
            continue;
        }

        // Get the raw diagram definition from the data-code attribute
        const encodedCode = block.getAttribute("data-code");
        if (!encodedCode) continue;

        let diagramDef: string;
        try {
            diagramDef = decodeURIComponent(escape(atob(encodedCode)));
        } catch {
            continue;
        }

        if (!diagramDef.trim()) continue;

        // Initialize mermaid on first use
        ensureInitialized();

        // Generate a unique ID for this diagram
        const diagramId = `ymm-mermaid-${++renderCounter}`;

        try {
            // Pre-validate the diagram. If it's invalid, mermaid.render() would
            // show its own global error overlay (the red mascot). By parsing
            // first with suppressErrors, we catch bad diagrams ourselves and
            // show our own inline error instead.
            const parseResult = await mermaid.parse(diagramDef.trim(), {
                suppressErrors: true,
            });

            if (!parseResult) {
                // Invalid diagram — show our own error, skip render
                throw new Error("Invalid diagram syntax");
            }

            // Render the diagram to SVG
            const { svg } = await mermaid.render(diagramId, diagramDef.trim());

            // Create a container for the rendered diagram
            const diagramContainer = document.createElement("div");
            diagramContainer.className = "ymm-mermaid-diagram";
            diagramContainer.innerHTML = svg;

            // Replace the code block content (pre/code) with the diagram,
            // but keep the header bar (language label + action buttons)
            const pre = block.querySelector("pre");
            if (pre) {
                pre.replaceWith(diagramContainer);
            }

            // Mark as rendered so we don't re-process
            block.setAttribute("data-mermaid-rendered", "true");

            // Update the language label to indicate it's rendered
            langLabel.textContent = "mermaid ✓";
        } catch (err) {
            // Rendering failed — keep the code block as-is, add error note
            console.warn("[YMM] Mermaid render error:", err);

            const errorNote = document.createElement("div");
            errorNote.className = "ymm-mermaid-error";
            errorNote.textContent =
                "⚠ The AI model generated an invalid Mermaid diagram. "
                + "This is not a plugin error — the diagram syntax in the response "
                + "could not be rendered. You can copy the code and fix it manually.";
            block.appendChild(errorNote);

            // Mark as rendered (failed) so we don't retry
            block.setAttribute("data-mermaid-rendered", "true");
        }
    }
}