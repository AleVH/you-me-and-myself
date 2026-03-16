/**
 * Frontend logging utility — routes logs to the IDE via the bridge.
 *
 * ## Why This Exists
 *
 * `console.log` is dead inside JCEF. The JetBrains embedded Chromium doesn't
 * expose a developer tools console in production. Every `console.log` call in
 * the React frontend is silently swallowed — the developer never sees it.
 *
 * This module replaces all `console.log/warn/error` calls with a bridge-aware
 * logger that sends a `FRONTEND_LOG` command to the Kotlin backend. The backend
 * routes it to `Dev.info/warn/error`, which writes to `idea.log` with a
 * `react.{source}` tag. Now frontend logs appear in the IDE's standard log file.
 *
 * ## Usage
 *
 *   import { log } from "../utils/log";
 *   log.info("useBridge", "CHAT_RESULT received", { exchangeId: "abc" });
 *   log.warn("useBridge", "conversationId mismatch", { tab: "x", backend: "y" });
 *   log.error("useBridge", "Failed to parse event", { raw: json });
 *
 * ## Dev Mode Fallback
 *
 * When running via `npm run dev` (Vite dev server), `window.__ymm_cefQuery`
 * doesn't exist. In that case, logs fall back to native `console.log/warn/error`
 * so you still see output in the browser's DevTools.
 *
 * ## Tag Format in idea.log
 *
 *   react.useBridge — CHAT_RESULT received {exchangeId: abc}
 *   react.ChatApp — mounted with 3 tabs
 *
 * @see BridgeMessage.FrontendLog — Kotlin command class
 * @see BridgeDispatcher.handleFrontendLog — Kotlin handler
 * @see transport.ts — sendCommand used under the hood
 */

import { sendCommand } from "../bridge/transport";
import type { FrontendLogCommand } from "../bridge/types";
import { CommandType } from "../bridge/types";

/**
 * Check if we're running inside JCEF (production) or a browser (dev).
 *
 * In JCEF mode, logs go through the bridge to idea.log.
 * In dev mode, logs go to the browser console.
 */
function isJcefMode(): boolean {
    return typeof window.__ymm_cefQuery === "function";
}

/**
 * Format optional context data as a compact string for log messages.
 * Keeps idea.log lines readable without JSON bloat.
 *
 * @param context Optional key-value pairs to append to the log message
 * @returns Formatted string like " {key1: val1, key2: val2}" or empty string
 */
function formatContext(context?: Record<string, unknown>): string {
    if (!context || Object.keys(context).length === 0) return "";
    const pairs = Object.entries(context)
        .map(([k, v]) => `${k}=${typeof v === "string" ? v : JSON.stringify(v)}`)
        .join(", ");
    return ` {${pairs}}`;
}

/**
 * Send a log entry through the bridge to the Kotlin backend.
 *
 * In JCEF mode: sends a FRONTEND_LOG command via the bridge transport.
 * In dev mode: falls back to the corresponding native console method.
 *
 * @param level Log severity: "INFO", "WARN", or "ERROR"
 * @param source The React component or module name (e.g., "useBridge", "ChatApp")
 * @param message Human-readable log message
 * @param context Optional structured data to append
 */
function sendLog(
    level: "INFO" | "WARN" | "ERROR",
    source: string,
    message: string,
    context?: Record<string, unknown>,
): void {
    const contextStr = formatContext(context);
    const fullMessage = `${message}${contextStr}`;

    if (isJcefMode()) {
        // Production: route through bridge → Dev.info/warn/error → idea.log
        const command: FrontendLogCommand = {
            type: CommandType.FRONTEND_LOG,
            level,
            message: fullMessage,
            source,
        };
        sendCommand(command);
    } else {
        // Dev mode: fall back to browser console so Vite dev server shows output
        const tag = `[YMM react.${source}]`;
        switch (level) {
            case "INFO":
                console.log(tag, fullMessage);
                break;
            case "WARN":
                console.warn(tag, fullMessage);
                break;
            case "ERROR":
                console.error(tag, fullMessage);
                break;
        }
    }
}

/**
 * Frontend logger — the public API.
 *
 * Usage:
 *   log.info("useBridge", "CHAT_RESULT handled", { exchangeId });
 *   log.warn("useBridge", "Mismatch detected", { tab, backend });
 *   log.error("useBridge", "Event parse failed", { raw });
 */
export const log = {
    /**
     * Log an informational message (maps to Dev.info in idea.log).
     * Use for normal operational events: "handler registered", "tab switched", etc.
     */
    info(source: string, message: string, context?: Record<string, unknown>): void {
        sendLog("INFO", source, message, context);
    },

    /**
     * Log a warning (maps to Dev.warn in idea.log).
     * Use for unexpected but non-fatal conditions: mismatches, fallbacks, limits reached.
     */
    warn(source: string, message: string, context?: Record<string, unknown>): void {
        sendLog("WARN", source, message, context);
    },

    /**
     * Log an error (maps to Dev.error in idea.log).
     * Use for actual failures: parse errors, missing state, handler exceptions.
     */
    error(source: string, message: string, context?: Record<string, unknown>): void {
        sendLog("ERROR", source, message, context);
    },
};
