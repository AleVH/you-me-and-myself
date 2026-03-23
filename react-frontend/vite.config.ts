/**
 * Vite build configuration for the you-me-and-myself React chat frontend.
 *
 * ## Why Single-File Output?
 *
 * JCEF's `browser.loadHTML(htmlString)` loads HTML from a string, not a URL.
 * It doesn't resolve relative paths — no `<script src="...">`, no `<link href="...">`.
 * Everything must be inlined into a single HTML string.
 *
 * vite-plugin-singlefile handles this: it takes Vite's output (JS chunks, CSS files)
 * and inlines them all into index.html as `<script>` and `<style>` blocks.
 *
 * ## Build Output
 *
 * After `npm run build`, the output is a single file:
 *   dist/index.html — contains ALL JS, CSS, and assets inline
 *
 * This file is then copied to:
 *   src/main/resources/react-chat/index.html
 * where ReactChatPanel.kt reads it at startup.
 *
 * ## Dev Mode
 *
 * `npm run dev` starts a Vite dev server with hot reload. The React app detects
 * that it's NOT in JCEF (no `window.__ymm_cefQuery`) and uses MockTransport
 * instead, allowing full UI development without running the IntelliJ plugin.
 */
/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";
import path from "node:path";

export default defineConfig({
    plugins: [react(), viteSingleFile()],

    /**
     * Build fingerprint — injected as a global constant at compile time.
     * Format: "HH:MM:SS" (local time of the build).
     *
     * Displayed in the UI (ChatApp bottom-right) so you can always verify
     * that the plugin is running the latest build, not a cached version.
     * If the timestamp is stale, you know JCEF is serving a cached bundle.
     */
    define: {
        __BUILD_TIMESTAMP__: JSON.stringify(
            new Date().toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" })
        ),
        __BUILD_DATE__: JSON.stringify(
            new Date().toISOString().slice(0, 10)
        ),
    },

    /**
     * Vitest configuration — test runner for the React frontend.
     *
     * Tests are organized into 3 layers (projects) that run and report
     * separately, so the output is grouped by severity:
     *
     *   Layer 1 — Logic:  pure functions, no DOM. Failures = critical.
     *   Layer 2 — UI:     React rendering + interaction. Failures = visible bugs.
     *   Layer 3 — State:  useBridge hook. Failures = plumbing issues.
     *
     * All projects share this Vite config (TypeScript, plugins, define).
     * Tests are NOT included in the production bundle — Vite only
     * bundles what's reachable from main.tsx.
     */
    test: {
        projects: [
            {
                /**
                 * Layer 1 — Logic: pure functions, no DOM, no React.
                 *
                 * Validates calculations, data transformations, event wiring,
                 * and protocol logic. Highest-criticality failures — if these
                 * break, the core data pipeline is wrong.
                 *
                 * Files: accumulator, transport, markdownRenderer
                 */
                test: {
                    name: "Layer 1 — Logic",
                    environment: "jsdom",
                    setupFiles: ["./tests/test-setup.ts"],
                    include: ["tests/layer-1-logic/**/*.test.{ts,tsx}"],
                    css: false,
                    globals: true,
                },
            },
            {
                /**
                 * Layer 2 — UI: React component rendering + user interaction.
                 *
                 * Renders components in a simulated browser (jsdom), clicks
                 * buttons, types text, and verifies what appears on screen.
                 * Failures mean the user sees something broken.
                 *
                 * Files: ContextDial, ContextLever, SummaryDial, InputBar
                 */
                test: {
                    name: "Layer 2 — UI",
                    environment: "jsdom",
                    setupFiles: ["./tests/test-setup.ts"],
                    include: ["tests/layer-2-components/**/*.test.{ts,tsx}"],
                    css: false,
                    globals: true,
                },
            },
            {
                /**
                 * Layer 3 — State: useBridge hook and state management.
                 *
                 * Validates the central state machine — tab switching, message
                 * routing, metrics accumulation, provider management. NOT YET
                 * WRITTEN — placeholder for Phase 4.
                 *
                 * Files: useBridge (future)
                 */
                test: {
                    name: "Layer 3 — State",
                    environment: "jsdom",
                    setupFiles: ["./tests/test-setup.ts"],
                    include: ["tests/layer-3-state/**/*.test.{ts,tsx}"],
                    css: false,
                    globals: true,
                },
            },
        ],
    },

    build: {
        /**
         * Purpose: output the single-file build directly to the plugin resources folder
         * so the plugin always loads the latest UI without any manual copy step.
         */
        outDir: path.resolve(__dirname, "../src/main/resources/react-chat"),

        /**
         * Purpose: remove stale build artifacts so you never load old UI by accident.
         * Only keep this true if that folder is dedicated to the built frontend output.
         */
        emptyOutDir: true,

        target: "esnext",
        sourcemap: false,
        chunkSizeWarningLimit: 2000,
    },
});