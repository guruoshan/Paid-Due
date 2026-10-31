# Reporting Screen Decomposition — Pass 1 Report

## Scope

- Repository: `guruoshan/Paid-Due`
- Base: `main` at `ff75099329c2d30b2c2c36b06d53d402080c308e`
- Branch: `refactor/reporting-screen-pass1`
- Target: `app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingScreen.kt`
- Behavior changes intended: none

## Files changed

1. `app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingScreen.kt`
2. `app/src/main/java/com/dailymoneyregister/feature/reporting/ui/components/ReportingSummarySection.kt` (new)
3. `app/src/main/java/com/dailymoneyregister/feature/reporting/ui/components/ReportingRowCard.kt` (new)
4. `app/src/main/java/com/dailymoneyregister/feature/reporting/ui/components/ReportingErrorState.kt` (new)
5. `docs/refactors/reporting-screen-plan.md` (new)
6. `docs/refactors/reporting-screen-pass1.md` (new)

## Before / after size

| File / measure | Before | After |
|---|---:|---:|
| `ReportingScreen.kt` | 892 physical lines | 565 physical lines |
| Host reduction | — | 327 lines |
| Extracted composable bodies | — | 314 lines |
| New summary component file | — | 127 lines |
| New row-card component file | — | 184 lines |
| New error-state component file | — | 71 lines |

The three extracted bodies are 105, 154 and 55 lines respectively. The host reduction also includes removal of imports that became unused and addition of three component imports.

## Extracted composables

- `ReportingSummarySection` and its `summaryMetricLabel` helper.
- `ReportingRowCard`.
- `ReportingErrorState`.

The composable bodies were compared with their originals and match exactly apart from changing the extracted top-level composable visibility from `private` to `internal` so the host in the parent package can call them. No rendering branches or callback behavior were rewritten.

## Callback parity

- `ReportingScreen` parameter signature unchanged.
- `onNavigateBack` remains wired to the top app bar.
- `onExportCustomers` remains wired to the customer export button.
- `onOpenCustomer` remains forwarded from the host's LazyColumn item to the row card. The extracted row still invokes it only when `customerId` is non-null and passes the same ID.
- `viewModel::retry` remains passed to the error component.
- All ten ViewModel method references/effect interactions remain exactly once in the host.
- No callback signatures or argument types changed.

## State and ViewModel parity

- State still comes from `viewModel.uiState.collectAsStateWithLifecycle().value`.
- The navigation `LaunchedEffect(initialActivityName)` remains in the host.
- Both existing local Compose state declarations remain in the branch selector; no state was hoisted, duplicated or moved.
- Period, custom date, query, branch, activity and payment filters remain in the host.
- Loading/empty/error decisions, summary selection, LazyColumn item order and `stableId` keys remain in the host.
- PDF actions and customer export remain in the host.
- No ViewModel, repository, navigation, export/share, sync, database, domain or business-rule files changed.

## Risk assessment

**Low, presentation-only.**

The change moves three existing stateless presentation composables into a dedicated package. Their model inputs, string resources, theme behavior, display branches and callbacks are unchanged. State ownership, filtering decisions, data projection, ViewModel interactions, list keys, navigation, and export/share behavior remain in the original screen.

The main residual risk is ordinary Compose/Kotlin compilation or import/visibility mistakes caused by the package move; the build gate below is intended to catch these.

## Validation results

- Static component-body parity: PASS.
- Screen signature parity: PASS.
- ViewModel callback/method-reference parity: PASS.
- Local state count and ownership: PASS.
- Navigation effect parity: PASS.
- LazyColumn stable-key parity: PASS.
- PDF/export and filter host ownership: PASS.
- `:app:assembleDebug`: PASS on corrected branch head `639b251d33d34a1ca1cc5332a713f4e8610ef655` via Android PR validation run `41162716431` (`debug-build-and-tests`, success). The release-validation run `41162716401` also passed, including unit tests, lint, release APK assembly and AAB build. The repair-branch validation run `41162716432` passed its Android build/unit-test job; its conditional Room schema commit job was skipped.

## Merge recommendation

Build validation is green on the corrected head. Recommendation: the code is ready for review and merge after the PR is marked ready for review and normal repository review requirements are satisfied. No merge has been performed.
