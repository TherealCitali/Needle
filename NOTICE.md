# Needle — third-party notices

This repository contains the original Python/Termux assistant (MIT, see below if
present) plus an Android application under `android/` that bundles or adapts work
from two other projects. Both are used with their licences intact.

## Needle (the model and its engine) — Apache License 2.0

- Copyright (c) Cactus Compute, Inc.
- https://github.com/cactus-compute/needle
- https://huggingface.co/Cactus-Compute/needle3
- Vendored: `android/app/src/main/cpp/needle.h` (the public C header).
- Downloaded at build time: `libneedle.a` (the engine, per ABI) from
  `Cactus-Compute/needle3`, checksum-verified against the hashes in
  `android/app/src/main/cpp/CMakeLists.txt`.
- Downloaded on the device, once: `needle3.cact` (the 2-bit quantised weights),
  verified against the SHA-256 pinned in `android/app/build.gradle.kts`.

Licensed under the Apache License, Version 2.0; a copy is in
`licenses/Apache-2.0.txt`.

## TaskPilot — MIT License

The screen-automation core in `android/app/src/main/java/dev/citali/needle/pilot/`
is adapted from TaskPilot.

- Copyright (c) 2026 TherealCitali
- https://github.com/TherealCitali/TaskPilot

Files vendored and modified (package renamed to `dev.citali.needle.pilot`, the
decision step rewired to the on-device model, UI replaced):

- `agent/Action.kt`, `agent/AgentEngine.kt`, `agent/DeterministicExecutor.kt`,
  `agent/LlmClient.kt`, `agent/Plan.kt`, `agent/SafetyPolicy.kt`
- `accessibility/NeedleAccessibilityService.kt` (from `TaskPilotAccessibilityService.kt`),
  `accessibility/UiTree.kt`
- `data/AppInventory.kt`, `data/HistoryStore.kt`, `data/SecureStore.kt`, `data/SettingsStore.kt`
- `overlay/PilotOverlay.kt`, `overlay/PilotQuestionOverlay.kt`

The full MIT text is in `licenses/MIT-TaskPilot.txt`.

## Everything else

Jetpack Compose, AndroidX, Material 3, Kotlin and the Gradle wrapper are used
under their respective Apache-2.0 / MIT licences.
