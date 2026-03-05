package com.youmeandmyself.ai.chat.bridge

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.youmeandmyself.dev.Dev
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.browser.CefFrame

/**
 * JCEF-specific bridge transport — wires the browser to the [BridgeDispatcher].
 *
 * ## What This Class Does
 *
 * Implements the two-way communication channel between the React frontend
 * (running inside JCEF) and the Kotlin backend:
 *
 * ### JS → Kotlin (Commands)
 *
 * 1. React calls `window.__ymm_cefQuery(jsonString)` (a global function)
 * 2. That function calls JCEF's `cefQuery()` handler (registered by this class)
 * 3. The handler deserializes the JSON into a [BridgeMessage.Command]
 * 4. The command is passed to [BridgeDispatcher.dispatch]
 *
 * ### Kotlin → JS (Events)
 *
 * 1. [BridgeDispatcher] calls [sendEventToFrontend] with a JSON string
 * 2. This class wraps it in a JS call: `window.__ymm_bridgeReceive(json)`
 * 3. JCEF's `executeJavaScript` injects it into the browser context
 * 4. React's transport layer picks it up and routes to event listeners
 *
 * ## Lifecycle
 *
 * Create this AFTER the JBCefBrowser is created but BEFORE the HTML is loaded.
 * The JS→Kotlin query handler must be registered before React tries to use it.
 *
 * The `injectQueryFunction()` call happens after page load (via CefLoadHandler)
 * to inject `window.__ymm_cefQuery` as a global. React's transport.ts checks
 * for this function to detect JCEF mode vs dev mode.
 *
 * Call [dispose] when the chat panel is closed to clean up the JCEF query.
 *
 * ## Swappability
 *
 * If the frontend moves out of JCEF, replace this class with a new transport
 * (e.g., WebSocketBridgeTransport). The dispatcher and contract don't change.
 *
 * @param browser The JCEF browser instance (from ChatBrowserComponent or ReactChatPanel)
 * @param dispatcher The bridge dispatcher that routes commands to the orchestrator
 */
class JcefBridgeTransport(
    private val browser: JBCefBrowser,
    private val dispatcher: BridgeDispatcher
) {
    private val log = Dev.logger(JcefBridgeTransport::class.java)

    /**
     * JCEF query handler — receives JSON strings from the JavaScript side.
     *
     * JBCefJSQuery creates a `window.cefQuery_XXXXXX()` function in the browser.
     * We wrap it with `window.__ymm_cefQuery()` for a stable, predictable name
     * that the React transport can reference.
     */
    @Suppress("DEPRECATION", "removal")
    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser).also { query ->
        query.addHandler { jsonString ->
            handleIncomingCommand(jsonString)
            // Return null = no response needed (we send events asynchronously)
            null
        }
    }

    /**
     * Flag to track if the query function has been injected.
     *
     * Injection happens once after page load. Multiple loads (e.g., refresh)
     * re-inject automatically via the CefLoadHandler.
     */
    @Volatile
    private var injected = false

    init {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectQueryFunction()
                }
            }
        }, browser.cefBrowser)

        Dev.info(log, "jcef_transport.init", "browser" to browser.hashCode())
    }

    // ── JS → Kotlin ──────────────────────────────────────────────────

    /**
     * Handle an incoming JSON command from the frontend.
     *
     * Deserializes the JSON string into a [BridgeMessage.Command] and
     * dispatches it. Invalid JSON or unknown types are logged and ignored.
     *
     * @param jsonString Raw JSON from the JCEF query handler
     */
    private fun handleIncomingCommand(jsonString: String) {
        Dev.info(log, "jcef_transport.received",
            "length" to jsonString.length,
            "preview" to Dev.preview(jsonString, 200)
        )

        val command = BridgeMessage.parseCommand(jsonString)
        if (command != null) {
            dispatcher.dispatch(command)
        } else {
            Dev.warn(log, "jcef_transport.parse_failed", null,
                "json" to Dev.preview(jsonString, 300)
            )
        }
    }

    /**
     * Inject the `window.__ymm_cefQuery` global function into the browser.
     *
     * This wraps JCEF's auto-generated cefQuery function (which has a random name)
     * with a stable, predictable name that the React transport references.
     *
     * Also sets `window.__ymm_bridgeReady` check — if React already set it to true,
     * Kotlin knows the frontend is ready to receive events.
     *
     * The injection script:
     * ```javascript
     * window.__ymm_cefQuery = function(json) {
     *     // JCEF's generated cefQuery call
     *     window.cefQuery_12345({ request: json, ... })
     * }
     * ```
     */
    private fun injectQueryFunction() {
        val injectionCode = jsQuery.inject("json")

        // Build the wrapper function that React's transport.ts calls
        val script = """
            (function() {
                window.__ymm_cefQuery = function(json) {
                    $injectionCode
                };
                console.log('[YMM Bridge] __ymm_cefQuery injected');
            })();
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        injected = true

        Dev.info(log, "jcef_transport.query_injected")

        // Signal to React that the command channel is operational.
        // React waits for this before sending REQUEST_PROVIDERS (or any command).
        sendEventToFrontend("""{"type":"BRIDGE_READY"}""")
    }

    // ── Kotlin → JS ──────────────────────────────────────────────────

    /**
     * Send an event to the React frontend.
     *
     * This is the [EventSender] callback given to the [BridgeDispatcher].
     * Wraps the JSON string in a call to `window.__ymm_bridgeReceive()`,
     * which React's JcefTransport registered as a global.
     *
     * ## Escaping
     *
     * The JSON string must be properly escaped for injection into JavaScript.
     * We use single-quote wrapping and escape any single quotes in the JSON.
     * This avoids issues with double quotes inside JSON strings.
     *
     * @param jsonString Serialized event JSON from [BridgeMessage.serializeEvent]
     */
    fun sendEventToFrontend(jsonString: String) {
        // Escape for safe injection into a JS string literal.
        // Replace backslashes first (before they multiply), then single quotes,
        // then newlines/carriage returns.
        val escaped = jsonString
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val script = "window.__ymm_bridgeReceive && window.__ymm_bridgeReceive('$escaped');"
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)

        Dev.info(log, "jcef_transport.sent",
            "length" to jsonString.length,
            "preview" to Dev.preview(jsonString, 200)
        )
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Clean up the JCEF query handler.
     *
     * Call when the chat panel is disposed. After this, commands from
     * the frontend will be silently dropped (the query handler is gone).
     */
    fun dispose() {
        jsQuery.dispose()
        Dev.info(log, "jcef_transport.disposed")
    }
}
