/**
 * ─── markdownRenderer.test.ts ──────────────────────────────────────
 *
 * WHAT THIS TESTS:
 *   Visible: The rendered markdown output the user reads in the chat.
 *   Behind the scenes: the markdown-it configuration — which plugins
 *            are active, how code blocks are wrapped, how links open.
 *
 * LAYER: 1 — Unit Tests (string in → HTML string out)
 *
 * WHY THESE MATTER:
 *   Every AI response goes through renderMarkdown(). If it silently
 *   breaks (strips code blocks, mangles links, injects raw HTML),
 *   the user sees garbled output. These tests catch regressions.
 *
 * NOTE: We test the HTML output structure, not pixel-perfect rendering.
 * Visual layout is the CSS's job — we verify that the correct elements
 * and attributes are present in the generated HTML.
 *
 * @see markdownRenderer.ts — the source file these tests validate
 * @see MessageList.tsx — the component that calls renderMarkdown()
 */

import { describe, it, expect } from "vitest";
import { renderMarkdown, renderInlineMarkdown } from "../../../src/rendering/markdownRenderer";

// ═══════════════════════════════════════════════════════════════════════
//  BASIC MARKDOWN RENDERING
// ═══════════════════════════════════════════════════════════════════════

describe("renderMarkdown — basic markdown", () => {
    it("renders plain text wrapped in a paragraph", () => {
        // VISIBLE: plain text appears as a normal paragraph.
        const html = renderMarkdown("Hello world");
        expect(html).toContain("<p>");
        expect(html).toContain("Hello world");
    });

    it("renders **bold** text", () => {
        const html = renderMarkdown("This is **bold** text");
        expect(html).toContain("<strong>bold</strong>");
    });

    it("renders *italic* text", () => {
        const html = renderMarkdown("This is *italic* text");
        expect(html).toContain("<em>italic</em>");
    });

    it("renders headers", () => {
        const html = renderMarkdown("# Title\n## Subtitle");
        expect(html).toContain("<h1>");
        expect(html).toContain("Title");
        expect(html).toContain("<h2>");
        expect(html).toContain("Subtitle");
    });

    it("renders unordered lists", () => {
        const html = renderMarkdown("- item 1\n- item 2\n- item 3");
        expect(html).toContain("<ul>");
        expect(html).toContain("<li>");
        expect(html).toContain("item 1");
    });

    it("renders ordered lists", () => {
        const html = renderMarkdown("1. first\n2. second");
        expect(html).toContain("<ol>");
        expect(html).toContain("<li>");
    });

    it("converts single newlines to <br> (breaks: true)", () => {
        // BEHIND THE SCENES: markdown-it's `breaks: true` setting
        // converts single \n to <br>. This matches how chat messages
        // typically work — users expect line breaks to display.
        const html = renderMarkdown("line one\nline two");
        expect(html).toContain("<br>");
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  CODE BLOCKS — the custom fence renderer
// ═══════════════════════════════════════════════════════════════════════

describe("renderMarkdown — code blocks", () => {
    it("wraps fenced code blocks in a ymm-code-block div", () => {
        // VISIBLE: code blocks get a wrapper div that the React
        // component uses to inject copy/bookmark buttons.
        const html = renderMarkdown("```\nconst x = 1;\n```");
        expect(html).toContain('class="ymm-code-block"');
    });

    it("includes a data-code attribute for clipboard copy", () => {
        // BEHIND THE SCENES: the raw code is base64-encoded in a
        // data attribute so the copy button can grab the unformatted
        // code without parsing highlighted HTML.
        const html = renderMarkdown("```\nconst x = 1;\n```");
        expect(html).toContain("data-code=");
    });

    it("includes the language label when specified", () => {
        // VISIBLE: the user sees "kotlin" or "python" in the top-right
        // corner of the code block so they know what language it is.
        const html = renderMarkdown("```kotlin\nfun hello() = println(\"hi\")\n```");
        expect(html).toContain('class="ymm-code-lang"');
        expect(html).toContain("kotlin");
    });

    it("applies hljs language class to the code element", () => {
        // BEHIND THE SCENES: highlight.js applies syntax coloring
        // based on the language class.
        const html = renderMarkdown("```javascript\nconst x = 1;\n```");
        expect(html).toContain("language-javascript");
    });

    it("renders code blocks without a language (auto-detection)", () => {
        // VISIBLE: code blocks without a language hint still render
        // with syntax highlighting via auto-detection.
        const html = renderMarkdown("```\nconst x = 1;\n```");
        expect(html).toContain('class="ymm-code-block"');
        expect(html).toContain("<code");
    });

    it("includes the code header div with actions container", () => {
        // BEHIND THE SCENES: the header div is where MessageList.tsx
        // injects copy and bookmark buttons via useEffect.
        const html = renderMarkdown("```python\nprint('hi')\n```");
        expect(html).toContain('class="ymm-code-header"');
        expect(html).toContain('class="ymm-code-actions"');
    });

    it("renders inline code with backticks", () => {
        // VISIBLE: inline code like `variable` gets a <code> tag.
        const html = renderMarkdown("Use the `sendCommand` function");
        expect(html).toContain("<code>");
        expect(html).toContain("sendCommand");
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  LINKS — target="_blank" for JCEF safety
// ═══════════════════════════════════════════════════════════════════════

describe("renderMarkdown — links", () => {
    it("adds target=_blank to all links", () => {
        // BEHIND THE SCENES: inside JCEF, clicking a link would navigate
        // the embedded browser away from the chat. target="_blank" opens
        // links in the user's default browser instead.
        const html = renderMarkdown("[Google](https://google.com)");
        expect(html).toContain('target="_blank"');
    });

    it("adds rel=noopener to all links (security)", () => {
        // BEHIND THE SCENES: rel="noopener" prevents the opened page
        // from accessing window.opener — standard security practice.
        const html = renderMarkdown("[link](https://example.com)");
        expect(html).toContain('rel="noopener"');
    });

    it("auto-links URLs when linkify is enabled", () => {
        // VISIBLE: URLs in AI responses become clickable links
        // automatically, even without markdown link syntax.
        const html = renderMarkdown("Visit https://example.com for more");
        expect(html).toContain("<a ");
        expect(html).toContain("https://example.com");
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  SECURITY: HTML injection prevention
// ═══════════════════════════════════════════════════════════════════════

describe("renderMarkdown — security", () => {
    it("does NOT render raw HTML tags (html: false)", () => {
        // BEHIND THE SCENES: AI responses should never inject raw HTML
        // into the DOM. The `html: false` setting ensures all HTML
        // entities are escaped.
        const html = renderMarkdown("<script>alert('xss')</script>");
        expect(html).not.toContain("<script>");
        // The tags should be escaped
        expect(html).toContain("&lt;script&gt;");
    });

    it("escapes HTML in inline content", () => {
        const html = renderMarkdown("Use <div> elements");
        expect(html).not.toContain("<div>");
        expect(html).toContain("&lt;div&gt;");
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  renderInlineMarkdown — no block elements
// ═══════════════════════════════════════════════════════════════════════

describe("renderInlineMarkdown", () => {
    it("renders inline formatting without wrapping in <p>", () => {
        // BEHIND THE SCENES: used for system messages and single-line
        // previews where <p> tags would break the layout.
        const html = renderInlineMarkdown("This is **bold** and *italic*");
        expect(html).not.toContain("<p>");
        expect(html).toContain("<strong>bold</strong>");
        expect(html).toContain("<em>italic</em>");
    });

    it("renders inline code", () => {
        const html = renderInlineMarkdown("Use `sendCommand` here");
        expect(html).toContain("<code>");
        expect(html).toContain("sendCommand");
    });
});
