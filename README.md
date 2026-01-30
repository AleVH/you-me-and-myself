YouMeAndMyself Assistant — IntelliJ Platform Plugin

Welcome! This repository contains a multi‑provider AI assistant for JetBrains IDEs, featuring a chat tool window, provider abstraction, and a context orchestrator to enrich prompts from your project.

Where to find the detailed overview
- See docs/DETAILED_OVERVIEW.md for a comprehensive architecture and code tour, including:
  - UI components and actions
  - Provider architecture and registry
  - Settings persistence and configurables
  - Context Orchestrator (detectors, resolvers, merge policy, metrics)
  - Networking via Ktor
  - Extension points, data flows, and next steps

Quick start (developer)
- Open in IntelliJ IDEA
- Run Gradle task: runIde
- In the sandbox IDE, open Settings → Tools → YMM Assistant to configure keys and select a provider
- Use Tools → AI → Test AI Providers or Open YMM Chat

Build
- Kotlin 2.2, Java 17 toolchain, IntelliJ Platform Gradle plugin 2.9
- Since‑build: 243 (IDE 2024.3+)
