/**
 * Token usage metrics bar.
 *
 * ## What It Shows
 *
 * Displays the token breakdown from the most recent exchange:
 * - Model name (e.g., "gpt-4", "gemini-pro")
 * - Prompt tokens (input)
 * - Completion tokens (output)
 * - Total tokens
 * - Estimated cost (if available)
 *
 * ## Visibility
 *
 * Only rendered when metrics data is available (after the first exchange
 * that includes token usage). Some providers don't return token counts —
 * in that case, fields show "—" instead of numbers.
 *
 * ## Design Principle
 *
 * "No surprises" — users always know what each conversation costs.
 * This bar is the primary cost visibility mechanism in the chat UI.
 *
 * @see BridgeMessage.UpdateMetricsEvent — the event that drives this component
 */
import type { MetricsData } from "../hooks/useBridge";

interface MetricsBarProps {
    metrics: MetricsData;
}

/**
 * Format a token count for display.
 * Returns "—" for null values (provider didn't report).
 */
function formatTokens(count: number | null): string {
    if (count === null) return "—";
    return count.toLocaleString();
}

function MetricsBar({ metrics }: MetricsBarProps) {
    return (
        <div className="ymm-metrics-bar">
            {metrics.model && (
                <span className="ymm-metrics-bar__model">{metrics.model}</span>
            )}
            <span className="ymm-metrics-bar__item">
        ↑ {formatTokens(metrics.promptTokens)}
      </span>
            <span className="ymm-metrics-bar__item">
        ↓ {formatTokens(metrics.completionTokens)}
      </span>
            <span className="ymm-metrics-bar__item ymm-metrics-bar__item--total">
        Σ {formatTokens(metrics.totalTokens)}
      </span>
            {metrics.estimatedCost && (
                <span className="ymm-metrics-bar__cost">{metrics.estimatedCost}</span>
            )}
        </div>
    );
}

export default MetricsBar;