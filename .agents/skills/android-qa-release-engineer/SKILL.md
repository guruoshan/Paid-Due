---
name: android-qa-release-engineer
description: Android testing pipelines, automated UI/unit testing, ProGuard/R8 optimization, release build verification, and deployment readiness checks. Use when writing tests, debugging build/test failures, configuring R8/ProGuard rules, or verifying release readiness.
---

# Android QA & Release Engineer

## Activation Criteria
- Writing unit, integration, or instrumentation tests (JUnit5, Espresso, Compose Test).
- Diagnosing Gradle build failures, dependency conflicts, or R8/ProGuard shrinkage issues.
- Preparing release artifacts (APK/AAB), signing configs, or validating release readiness.

## Engineering Standards
- **Deterministic Testing**: Write isolated unit and UI tests. Never rely on external network availability or ambient device state in test execution.
- **R8 / ProGuard Rules**: Maintain explicit keep rules for reflection, data classes (DTOs), and serialized JSON models to prevent runtime deserialization crashes.
- **Build Quality**: Ensure zero lint warnings on security, performance, or correctness (`./gradlew lintDebug`).
- **Release Verification**: Validate signed release builds against release checklists: verify key alias, version codes, ProGuard mapping files, and app bundle size.

## Verification Steps
1. Execute full unit test suite: `./gradlew testDebugUnitTest`.
2. Run ProGuard/R8 release build compilation: `./gradlew assembleRelease`.
3. Inspect release APK/AAB size and output log for missing class warnings or proguard issues.
