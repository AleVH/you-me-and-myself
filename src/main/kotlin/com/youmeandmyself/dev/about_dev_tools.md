# YMM Dev Mode Guide

## Overview

Dev Mode provides hidden testing tools for developers to exercise critical plugin functionality without needing real AI providers or specific response conditions. These tools are invisible to end users and only activate when explicitly enabled.

**Primary use case:** Testing the response parsing correction flow (Scenarios 1-3) which is difficult to trigger with real providers since most return known formats.

---

## Enabling Dev Mode

### IntelliJ IDEA / Plugin Development

1. **Open Run Configuration**
    - Locate the green **Play** button in the top toolbar
    - Click the dropdown to its left (shows current configuration name)
    - Select **"Edit Configurations..."** at the bottom

2. **Add VM Option**
    - Select your plugin run configuration (e.g., "Run Plugin" or "runIde")
    - Find the **"VM options"** field
        - If not visible: Click **"Modify options"** → **"Add VM options"**
    - Add the following:
      ```
      -Dymm.devMode=true
      ```
    - If other options exist, add this at the end separated by a space

3. **Apply and Run**
    - Click **Apply** → **OK**
    - Run the plugin normally with the Play button

### Command Line (Alternative)

If running the IDE from command line:

```bash
./idea.sh -Dymm.devMode=true
```

Or for Gradle-based plugin runs:

```bash
./gradlew runIde -Dymm.devMode=true
```

### Verification

Once the test IDE launches:

1. Open the YMM Assistant chat panel
2. Type `/dev-help` and press Enter
3. **Expected:** A list of available dev commands appears
4. **If it sends to AI instead:** VM option not applied — revisit steps above

---

## Available Commands

| Command | Description |
|---------|-------------|
| `/dev-help` | Show all available dev commands |
| `/dev-status` | Show current dev mode status and configuration |
| `/dev-scenario1` | Test known format response (no correction UI) |
| `/dev-scenario2` | Test heuristic response with correction option |
| `/dev-scenario3` | Test low confidence response (immediate dialog) |
| `/dev-error` | Test error response parsing |

---

## Test Scenarios Explained

The response parsing system has three scenarios based on parser confidence. These dev commands let you trigger each one manually.

### Scenario 1: Known Format

**Command:** `/dev-scenario1`

**What it simulates:** A response from a recognized provider (OpenAI, Gemini, Anthropic) where we know exactly how to extract content.

**Expected behavior:**
- Response displays immediately
- No "Type /correct to fix" message
- No correction UI
- Test passes message: "No correction UI (as expected)"

**When this happens in production:** Most requests to major providers.

---

### Scenario 2: Heuristic with Confidence

**Command:** `/dev-scenario2`

**What it simulates:** A response from an unknown provider where we used JSON walk heuristic and found a plausible match with medium/high confidence.

**Expected behavior:**
- Response displays immediately (our best guess)
- System message: "ℹ️ Response auto-detected. Not what you expected? Type /correct to fix."
- Correction context is stored
- Test passes message: "Correction context stored. Try /correct or /raw"

**Follow-up testing:**
- Type `/correct` → Opens CorrectionDialog with alternative candidates
- Type `/raw` → Opens RawResponseDialog showing the JSON

**When this happens in production:** Custom/unknown AI providers with non-standard response formats.

---

### Scenario 3: Low Confidence

**Command:** `/dev-scenario3`

**What it simulates:** A response where we really don't know which content is correct — multiple candidates with similar scores.

**Expected behavior:**
- CorrectionDialog appears **immediately** (before any response shows)
- User must select the correct content from candidates
- After selection, the chosen content displays
- If cancelled, best guess displays with correction option

**When this happens in production:** Very unusual response formats, malformed JSON, or providers with ambiguous structures.

---

### Error Response

**Command:** `/dev-error`

**What it simulates:** A provider error (rate limit, authentication failure, etc.)

**Expected behavior:**
- Error message displays with error styling
- No correction UI (errors don't have alternatives)

**When this happens in production:** API quota exceeded, invalid API key, network failures, etc.

---

## Testing Workflow

### Full Correction Flow Test

```
1. /dev-scenario2          → Verify response shows with correction hint
2. /correct                → Verify dialog opens with candidates  
3. Select alternative      → Verify corrected response displays
4. Check "Remember" box    → Verify format hint is saved
```

### Raw JSON Inspection

```
1. /dev-scenario2          → Trigger a correctable response
2. /raw                    → Verify raw JSON dialog opens
3. Check JSON structure    → Verify it matches expected format
4. Copy to clipboard       → Verify copy button works
```

### Immediate Correction Test

```
1. /dev-scenario3          → Dialog should appear immediately
2. Select any option       → Verify response displays
3. /correct                → Should say "no response available" (context cleared)
```

### Clean Scenario Test

```
1. /dev-scenario1          → Response displays immediately  
2. /correct                → Should say "no response available"
3. /raw                    → Should say "no recent response"
```

---

## Troubleshooting

### Commands not recognized (sent to AI)

**Cause:** Dev mode not enabled

**Fix:**
- Verify `-Dymm.devMode=true` is in VM options
- Restart the test IDE after adding the option
- Check with `/dev-status` (if it responds, dev mode is on)

### Correction dialog doesn't appear

**Cause:** Scenario conditions not met

**Check:**
- For `/dev-scenario3`: Dialog should appear immediately
- For `/dev-scenario2`: Use `/correct` command after response

### /raw shows "no recent response"

**Cause:** No correction context stored

**Fix:** Run `/dev-scenario2` first, then `/raw`

### Test responses look wrong

**Cause:** Test data in `TestResponseFactory.kt` may need updating

**Fix:** Modify the factory methods to generate different test content

---

## Architecture Notes

### Files Involved

| File | Location | Purpose |
|------|----------|---------|
| `DevMode.kt` | `com.youmeandmyself.dev` | Checks system property, lists commands |
| `DevCommandHandler.kt` | `com.youmeandmyself.dev` | Processes `/dev-*` commands |
| `TestResponseFactory.kt` | `com.youmeandmyself.dev` | Generates fake `ParsedResponse` objects |
| `CorrectionFlowHelper.kt` | `com.youmeandmyself.ai.providers.parsing.ui` | Handles correction logic, test JSON override |

### Adding New Test Commands

1. Add command description to `DevMode.availableCommands`
2. Add handler case in `DevCommandHandler.handleIfDevCommand()`
3. If needed, add factory method in `TestResponseFactory`

### Security Considerations

- Dev mode is **not** a security boundary
- Anyone who knows the property name can enable it
- Do **not** put sensitive functionality behind this flag
- It exists only to prevent accidental user access to test features

---

## Quick Reference

```
# Enable dev mode
VM option: -Dymm.devMode=true

# Basic commands
/dev-help        - Show commands
/dev-status      - Check status

# Test scenarios  
/dev-scenario1   - Known format (no correction UI)
/dev-scenario2   - Heuristic (with correction option)
/dev-scenario3   - Low confidence (immediate dialog)
/dev-error       - Error response

# After scenario2
/correct         - Open correction dialog
/raw             - View raw JSON
```

---

*Last updated: January 2025*
*Authors: Development Team*