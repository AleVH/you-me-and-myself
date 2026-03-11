/**
 * Metrics Dashboard — Expanded Analytics View (Post-Launch Placeholder)
 *
 * ## What This Will Be
 *
 * A full-page metrics dashboard spawned by the ⤢ expand button on
 * MetricsBar. Opens in its own tab (ephemeral by default). Shows:
 * - Per-provider token breakdown charts
 * - Per-model comparison charts
 * - Per-provider × model cross-reference
 * - Per-conversation breakdown
 * - Response time comparison
 * - Time-series token usage (daily/weekly/monthly)
 * - CSV export button
 *
 * ## Why It Exists Now
 *
 * The placeholder ensures:
 * 1. The file structure is correct from day one (src/metrics/)
 * 2. The barrel export (index.ts) includes it
 * 3. No dead import errors when the expand button is eventually wired
 * 4. A junior dev can find where to build the dashboard
 *
 * ## What It Needs (Post-Launch)
 *
 * - QUERY_METRICS bridge command (React → Kotlin)
 * - MetricsQueryResultEvent (Kotlin → React)
 * - Chart rendering (recharts or similar)
 * - Refresh strategy (manual / on-focus / auto-interval)
 * - Tier gating (Pro only)
 * - See §14 in YMM_Metrics_Module___Design_Document.md
 *
 * @see MetricsBar.tsx — the compact bar that spawns this dashboard
 * @see types.ts — shared type definitions
 */

/**
 * Placeholder dashboard for the expanded metrics view.
 *
 * Renders a simple "coming soon" message. Will be replaced with
 * the full analytics dashboard in a post-launch update.
 */
export default function MetricsDashboard() {
    return (
        <div
            style={{
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
                height: "100%",
                color: "rgba(255, 255, 255, 0.4)",
                fontFamily: "inherit",
                gap: "8px",
                padding: "24px",
            }}
        >
            <span style={{ fontSize: "24px" }}>⤢</span>
            <span style={{ fontSize: "14px", fontWeight: 500 }}>
                Metrics Dashboard
            </span>
            <span style={{ fontSize: "12px", textAlign: "center", maxWidth: "300px" }}>
                Detailed token usage analytics, per-model charts, and export
                capabilities. Coming in a future update (Pro feature).
            </span>
        </div>
    );
}