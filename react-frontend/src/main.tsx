/**
 * React entry point for the you-me-and-myself chat interface.
 *
 * Mounts the root App component on the #root element defined in index.html.
 * StrictMode is enabled for development warnings but has no effect in production.
 */
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./index.css";

/**
 * highlight.js theme — github-dark matches the IntelliJ Darcula aesthetic.
 * Imported here (entry point) so it's available globally for all components
 * that render syntax-highlighted code blocks via markdownRenderer.ts.
 */
import "highlight.js/styles/github-dark.css";

/**
 * KaTeX stylesheet — required for math rendering (R3).
 * Includes font declarations and layout rules for rendered math.
 */
import "katex/dist/katex.min.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
        <App />
    </React.StrictMode>,
);