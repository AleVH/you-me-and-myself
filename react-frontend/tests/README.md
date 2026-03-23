# React Frontend — Test Suite

## What Is This?

This folder contains all tests for the React chat frontend (`src/`). Tests validate that the UI components, logic functions, and bridge protocol work correctly — without affecting the production code in any way.

Tests are organized into **three layers** based on what they test and how critical a failure is. Each layer runs and reports separately so you can instantly see where problems are.

---

## How to Run the Tests

From the `react-frontend/` folder:

```bash
# Run all tests once (use before committing or to check if anything broke)
npm test

# Watch mode (re-runs affected tests every time you save a file)
# This is the one you use during development — leave it running in a terminal
npm run test:watch

# Run with coverage report (shows which lines/branches are tested)
npm run test:coverage
```

### Tests are wired to the build

`npm run build` runs **tests first, then type-check, then build**:

```
vitest run  →  tsc  →  vite build
```

If any test fails, the build stops immediately — no broken code gets shipped to the plugin. You don't need to remember to run tests separately before building; the pipeline enforces it.

---

## What a Good Outcome Looks Like

When everything passes, you see green checkmarks grouped by layer:

```
 ✓ |Layer 1 — Logic| tests/layer-1-logic/metrics/accumulator.test.ts (27 tests) 93ms
 ✓ |Layer 1 — Logic| tests/layer-1-logic/bridge/transport.test.ts (17 tests) 178ms
 ✓ |Layer 1 — Logic| tests/layer-1-logic/rendering/markdownRenderer.test.ts (21 tests) 1056ms
 ✓ |Layer 2 — UI| tests/layer-2-components/context/ContextDial.test.tsx (15 tests) 895ms
 ✓ |Layer 2 — UI| tests/layer-2-components/context/ContextLever.test.tsx (11 tests) 1022ms
 ✓ |Layer 2 — UI| tests/layer-2-components/context/SummaryDial.test.tsx (12 tests) 983ms
 ✓ |Layer 2 — UI| tests/layer-2-components/InputBar.test.tsx (15 tests) 1280ms

 Test Files  7 passed (7)
      Tests  118 passed (118)
```

**How to read each line:**
- **|Layer 1 — Logic|** or **|Layer 2 — UI|** — which layer this test belongs to
- **✓** — all tests in this file passed
- **(27 tests)** — how many individual test cases ran
- **93ms** — how long it took

The exit code is **0** (success) — CI pipelines and scripts can rely on this.

---

## What a Bad Outcome Looks Like

When a test fails, you see a red ✗ with a detailed explanation:

```
 ✗ |Layer 1 — Logic| tests/layer-1-logic/metrics/accumulator.test.ts (26 tests | 1 failed) 45ms

   FAIL  formatTokenCount > returns "0" for zero
   AssertionError: expected "–" to be "0"

   - Expected: "0"
   + Received: "–"

    ❯ tests/layer-1-logic/metrics/accumulator.test.ts:98:42
       96|     it('returns "0" for zero', () => {
       97|         expect(formatTokenCount(0)).toBe("0");
       98|                                         ^
       99|     });

 Test Files  1 failed | 6 passed (7)
      Tests  1 failed | 117 passed (118)
```

This tells you:
1. **Which layer failed** — `|Layer 1 — Logic|` means core logic is broken (critical)
2. **Which test failed** — `formatTokenCount > returns "0" for zero`
3. **What was expected vs what happened** — expected `"0"`, got `"–"`
4. **Exact file and line number** — `accumulator.test.ts:98`
5. **The code around the failure** — so you can see the assertion in context

The exit code is **1** (failure).

**What to do when a test fails:**
- Read the layer prefix — that tells you how critical it is.
- Read the "Expected vs Received" — it usually makes the problem obvious.
- Go to the source file (not the test file) and check what changed.
- If the source change was intentional, update the test to match. If it wasn't, fix the source.

---

## How Critical Is a Failure? (by layer)

| Layer | What broke | Severity | What it means |
|-------|-----------|----------|---------------|
| **Layer 1 — Logic** | Pure calculations, data pipeline, protocol | **Critical** | The math is wrong, tokens are miscounted, events don't route, or markdown renders incorrectly. The user will see wrong data or broken output. Fix immediately. |
| **Layer 2 — UI** | Component rendering, click behavior, visual state | **High** | The user sees a broken button, wrong placeholder, missing label, or a click that does nothing when it should do something. Visible bug — fix soon. |
| **Layer 3 — State** | Tab switching, message routing, metrics accumulation | **High** | The plumbing between logic and UI is broken. Messages go to the wrong tab, metrics don't update, or providers don't switch. The UI looks fine but behaves wrong. |

---

## Test Structure

Tests are physically separated into layer folders:

```
tests/
├── README.md                          ← you are here
├── test-setup.ts                      ← global setup (runs before every test file)
│
├── layer-1-logic/                     ← LAYER 1: pure functions, no DOM
│   ├── metrics/
│   │   └── accumulator.test.ts        ← token math, formatting, fill bar colors
│   ├── bridge/
│   │   └── transport.test.ts          ← event registry, JCEF vs mock, command
│   │                                    dispatch, mock handler responses
│   └── rendering/
│       └── markdownRenderer.test.ts   ← bold/italic/headers, code block wrapping,
│                                        language labels, link security, HTML
│                                        injection prevention, inline rendering
│
├── layer-2-components/                ← LAYER 2: React rendering + interaction
│   ├── InputBar.test.tsx              ← text input, send button, Enter/Shift+Enter,
│   │                                    placeholder states, disabled behavior
│   └── context/
│       ├── ContextDial.test.tsx        ← mode cycling (OFF/ON/CUSTOM), tier gating,
│       │                                disabled state, CSS classes, ARIA labels
│       ├── ContextLever.test.tsx       ← visibility toggle, level labels (Minimal/
│       │                                Partial/Full), slider ARIA, snap markers
│       └── SummaryDial.test.tsx        ← ON/OFF toggle, disabled state, CSS classes
│
└── layer-3-state/                     ← LAYER 3: state management (NOT YET WRITTEN)
    └── (future: useBridge.test.ts)
```

---

## Test Annotation Convention

Every test has comments explaining what aspect of the system it validates, using three tags:

### VISIBLE
What the user sees on screen. A button appears, text changes, a color updates.

### VISIBLE PROCESS
What user action triggers a change. Clicking a button, typing text, pressing Enter.

### BEHIND THE SCENES
Internal logic the user never sees directly. A callback fires with the right value, state updates correctly, null values are handled gracefully, immutability is preserved.

**Example from ContextDial.test.tsx:**
```typescript
it("does NOT fire onModeChange when disabled", async () => {
    // VISIBLE: the dial is greyed out.
    // VISIBLE PROCESS: clicking does nothing.
    // BEHIND THE SCENES: the global kill-switch in Settings overrides
    // any per-tab preference. Silent click rejection is deliberate UX.
```

At least one tag is present on every test. When a test fails, these annotations tell you whether the user is seeing something broken right now, or whether it's an internal calculation that will cascade later.

---

## What's NOT Tested Yet

These source files exist in `src/` but have no corresponding test file:

| Source file | Layer it belongs to | Why not yet |
|-------------|-------------------|-------------|
| `hooks/useBridge.ts` | Layer 3 — State | The big one — Phase 4 in the strategy |
| `components/ChatApp.tsx` | Layer 2 — UI | Top-level orchestrator — depends on useBridge |
| `components/MessageList.tsx` | Layer 2 — UI | Complex rendering + collapse — needs useBridge mocking |
| `components/TabBar.tsx` | Layer 2 — UI | Multi-tab interaction — Phase 3 |
| `components/ProviderSelector.tsx` | Layer 2 — UI | Dropdown — Phase 3 |
| `components/ThinkingIndicator.tsx` | Layer 2 — UI | Trivial — quick win when we get to it |
| `components/SystemMessage.tsx` | Layer 2 — UI | Trivial — quick win |
| `components/context/ContextDialStrip.tsx` | Layer 2 — UI | Wrapper — depends on useBridge |
| `metrics/MetricsBar.tsx` | Layer 2 — UI | Display component — Phase 3 |
| `rendering/mermaidRenderer.ts` | Layer 1 — Logic | Async SVG — harder to test in jsdom |
| `rendering/katexPlugin.ts` | Layer 1 — Logic | Math rendering plugin |
| `rendering/collapsiblePlugin.ts` | Layer 1 — Logic | Custom markdown-it plugin |
| `utils/log.ts` | Layer 1 — Logic | Logging router — small, straightforward |

---

## What test-setup.ts Does

This file runs automatically before every test file. It imports `@testing-library/jest-dom`, which adds extra assertion methods like:

- `toBeInTheDocument()` — element exists in the DOM
- `toBeDisabled()` — button/input is disabled
- `toBeEnabled()` — not disabled
- `toHaveClass("foo")` — CSS class is present
- `toHaveValue("hello")` — input has this value
- `toHaveAttribute("aria-label", "...")` — HTML attribute check
- `toBeVisible()` — not hidden/display:none

You never need to import these manually — they're available in every test file automatically.

---

## Adding a New Test

1. **Decide which layer** it belongs to:
   - Pure function with no React? → `tests/layer-1-logic/`
   - React component that renders UI? → `tests/layer-2-components/`
   - State management hook? → `tests/layer-3-state/`

2. **Create the file** mirroring the source path within that layer:
   - Source: `src/components/TabBar.tsx`
   - Test: `tests/layer-2-components/TabBar.test.tsx`

3. **Add the file header** comment explaining what it tests and which layer.

4. **Import the source** with a relative path back to `src/`:
   ```typescript
   // From tests/layer-2-components/
   import TabBar from "../../src/components/TabBar";

   // From tests/layer-2-components/context/
   import ContextDial from "../../../src/components/context/ContextDial";

   // From tests/layer-1-logic/metrics/
   import { accumulate } from "../../../src/metrics/accumulator";
   ```

5. **Write tests** using `describe` / `it` / `expect` with the annotation convention (VISIBLE / VISIBLE PROCESS / BEHIND THE SCENES).

6. **Run `npm test`** to verify. The new test will automatically appear under the correct layer prefix in the output.
