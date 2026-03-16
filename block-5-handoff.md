# Block 5: Context Injection into Chat — Final Handoff

## Status: COMPLETE

All 10 tasks across 4 phases implemented and build-verified.

---

## What Was Built

Block 5 adds **context bypass control** to the YMM chat pipeline. Users can toggle whether IDE context (open files, project structure, summaries) is gathered and injected into prompts sent to AI providers.

### End-to-End Data Flow

```
ContextDial click
  → setBypassMode("FULL")
  → TabData.bypassMode updated (ephemeral, per-tab)
  → sendMessage reads tab.bypassMode
  → SendMessageCommand { bypassMode: "FULL" }
  → BridgeDispatcher passes to orchestrator.send(bypassMode)
  → ChatOrchestrator passes to assembler.assemble(bypassMode)
  → ContextAssembler: FULL → early return, no context gathered
  → AI provider receives raw user text only
```

---

## File Inventory

### New Files (9)

| File | Purpose |
|------|---------|
| `react-frontend/src/utils/log.ts` | Frontend logging utility — routes logs to idea.log via FRONTEND_LOG bridge command. Replaces dead console.log in JCEF. |
| `react-frontend/src/components/context/ContextDial.tsx` | 24px SVG rotary dial, 3 positions (OFF/FULL/SELECTIVE), click cycles clockwise, tier-gated |
| `react-frontend/src/components/context/ContextDial.css` | Darcula-themed styles for the dial — hover scale, focus ring, mode colors |
| `react-frontend/src/components/context/ContextLever.tsx` | Horizontal slider with drag handle, 3 snap positions, STUB "Coming soon" overlay |
| `react-frontend/src/components/context/ContextLever.css` | Green-amber-red gradient track, draggable handle, snap markers |
| `react-frontend/src/components/context/ContextDialStrip.tsx` | Wrapper: dial + mode label + expand toggle. Compact 28px default, expandable for lever |
| `react-frontend/src/components/context/ContextDialStrip.css` | Strip layout — flex row, surface background, expand toggle, detail area |
| `src/main/kotlin/.../settings/ContextSettingsState.kt` | `@Service(PROJECT)` + `@State` — contextEnabled + defaultBypassMode (STUB fields) |
| `src/main/kotlin/.../settings/ContextConfigurable.kt` | Settings UI: Tools → YMM → Context. Checkbox + combo + tier-gated Selective Bypass section |

### Modified Files (11)

| File | Changes |
|------|---------|
| `react-frontend/src/bridge/types.ts` | Added `FRONTEND_LOG` to CommandType, `FrontendLogCommand` interface, `bypassMode` field on `SendMessageCommand` |
| `react-frontend/src/hooks/useBridge.ts` | Extended `ChatMessage` with contextSummary/contextTimeMs (5 construction sites), extended `TabData` with bypassMode (3 construction sites), added setBypassMode callback, replaced 14 console.log calls with log.ts, wired bypassMode into sendMessage SendMessageCommand, extended BridgeState interface |
| `react-frontend/src/components/ChatApp.tsx` | Imported + mounted ContextDialStrip between MessageList and InputBar, updated layout docstring |
| `react-frontend/src/components/MessageList.tsx` | Replaced 1 console.error with log.error |
| `react-frontend/src/metrics/MetricsBar.tsx` | Replaced 1 console.warn with log.warn |
| `src/.../bridge/BridgeMessage.kt` | Added `FrontendLog` data class, `bypassMode` field on `SendMessage`, both with parseCommand() entries |
| `src/.../bridge/BridgeDispatcher.kt` | Added `handleFrontendLog()` handler, threaded `command.bypassMode` to orchestrator.send() |
| `src/.../orchestrator/ChatOrchestrator.kt` | Added `bypassMode` parameter to `send()`, threaded to `assembler.assemble()` |
| `src/.../context/ContextAssembler.kt` | Added `bypassMode` parameter to `assemble()`, FULL bypass early return, SELECTIVE stub log |
| `src/.../tier/Feature.kt` | Added `CONTEXT_SELECTIVE_BYPASS` under `// ── Context Control ──` section |
| `src/main/resources/META-INF/plugin.xml` | Registered `ContextSettingsState` projectService + `ContextConfigurable` projectConfigurable |

---

## Task Completion Matrix

| # | Task | Phase | Status |
|---|------|-------|--------|
| 1 | Wire contextSummary + contextTimeMs into ChatMessage | A | Done |
| 2 | Replace console.log with log.ts + FRONTEND_LOG bridge | A | Done |
| 3 | Thread bypassMode through send pipeline (6 touch points) | B | Done |
| 4 | FULL bypass early return in ContextAssembler.assemble() | B | Done |
| 5 | Feature.CONTEXT_SELECTIVE_BYPASS in Feature.kt | B | Done |
| 6 | ContextDial component (SVG rotary, 3 positions) | C | Done |
| 7 | ContextLever component (drag slider, STUB) | C | Done |
| 8 | ContextDialStrip wrapper + TabData.bypassMode extension | C | Done |
| 9 | ChatApp layout integration + sendMessage wiring | D | Done |
| 10 | Placeholder settings page (Tools → YMM → Context) | D | Done |

---

## What Is Functional vs STUB

### Functional Now
- **OFF mode**: Full context gathering (existing behavior, unchanged)
- **FULL mode**: Skips all context gathering — ContextAssembler.assemble() returns immediately with raw user text. System prompt + conversation history still flow.
- **Frontend logging**: All React-side logs route to idea.log via FRONTEND_LOG bridge
- **Per-tab bypassMode**: Each tab remembers its own mode (ephemeral)
- **ContextDial**: Cycles OFF ↔ FULL for Basic tier users
- **Settings page**: Visible at Tools → YMM → Context, values persist to XML

### STUB (Deferred)
- **SELECTIVE mode**: Backend logs and treats as OFF. ContextLever renders with "Coming soon" overlay. Pro-tier gate exists but no per-component bypass logic.
- **ContextSettingsState fields**: `contextEnabled` and `defaultBypassMode` are persisted but NOT read by ContextAssembler or the frontend. Per-message bypassMode from SendMessage is the only active control.
- **ContextBadgeTray**: Not built in this block. The contextSummary field exists on ChatMessage but no UI renders it yet.

---

## Build Verification

| Check | Result |
|-------|--------|
| `npx tsc --noEmit` | Clean (0 errors) |
| `./gradlew compileKotlin` | BUILD SUCCESSFUL |

---

## Architecture Notes for Next Developer

### Bridge Sync Pattern
Both sides of the bridge must stay in sync. When adding a new command/event:
1. `BridgeMessage.kt` — Kotlin sealed class + parseCommand() entry
2. `types.ts` — TypeScript interface + CommandType/EventType entry + union member
3. `BridgeDispatcher.kt` — dispatch case + handler method
4. React component — event subscription or command sender

### TabData Ephemeral vs Persisted Fields
- **Persisted** (survives IDE restart): fields included in `TabStateDto` and `SaveTabStateCommand` (id, title, conversationId, tabOrder, scrollPosition, providerId)
- **Ephemeral** (reset on restart): `messages`, `isThinking`, `metricsState`, `historyLoaded`, `collapsedIds`, `bypassMode`

### Tier Gating Pattern
```kotlin
val canUse = CompositeTierProvider.getInstance().canUse(Feature.CONTEXT_SELECTIVE_BYPASS)
```
Currently UNKNOWN tier → INDIVIDUAL_BASIC, which does NOT have CONTEXT_SELECTIVE_BYPASS. The ContextDial skips SELECTIVE in its cycle when `canUseSelective={false}`.

### Context Assembly Pipeline
`ContextAssembler.assemble()` steps:
0. Bypass check (NEW — FULL returns immediately)
1. shouldGatherContext() — heuristic: is context useful?
2. DumbService check — IDE indexing blocks context
3. gatherIdeContext() — 4 detectors, 1400ms budget
4. enrichWithSummaries() — read path, 50% benefit ratio
5-7. Format and assemble final prompt
