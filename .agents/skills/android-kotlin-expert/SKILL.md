---
name: android-kotlin-expert
description: Advanced Kotlin, Jetpack Compose, Coroutines, Flow, ViewModel, and Room architecture patterns for Android engineering. Use when implementing Kotlin code, refactoring Jetpack UI, managing async coroutines/flows, or optimizing Android code quality.
---

# Android Kotlin Expert

## Activation Criteria
- Writing or refactoring Kotlin code, Jetpack Compose UIs, or ViewModels.
- Designing asynchronous pipelines with Coroutines and StateFlow/SharedFlow.
- Structuring Android Architecture Components (ViewModel, Repository, Room).

## Engineering Standards
- **Coroutines & Threading**: Always pass `CoroutineDispatcher` via dependency injection. Use `Dispatchers.IO` for disk/network I/O and `Dispatchers.Default` for CPU-bound tasks. Avoid blocking calls on `Dispatchers.Main`.
- **State Management**: Prefer `StateFlow` for UI state representation (`SharingStarted.WhileSubscribed(5_000)`). Expose immutable `StateFlow` to UI while keeping mutable state private.
- **Compose Best Practices**: Ensure UI composables are stateless and receive state via parameters. Use stable data classes and `key()` in dynamic lists to minimize recompositions.
- **Memory Safety**: Avoid storing `Context` or View references inside ViewModels or long-lived coroutine scopes. Utilize `viewModelScope` for ViewModel-bound jobs.
- **Clean Architecture**: Maintain clear boundaries between Data (Repositories/DTOs), Domain (UseCases/Entities), and UI (ViewModels/Composables).

## Verification Steps
1. Run `./gradlew compileDebugKotlin` to verify type safety and compilation.
2. Run unit tests `./gradlew testDebugUnitTest` to ensure zero coroutine leaks or regressions.
3. Validate memory hygiene using Android Studio Profiler or LeakCanary if memory issues arise.
