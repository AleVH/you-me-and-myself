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
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";
import path from "node:path";

export default defineConfig({
    plugins: [react(), viteSingleFile()],
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