# Reporting Screen Decomposition — Pass 1 Analysis Plan

## Base and scope

- Repository: `guruoshan/Paid-Due`
- Base branch: `main`
- Audited base commit: `ff75099329c2d30b2c2c36b06d53d402080c308e`
- Working branch: `refactor/reporting-screen-pass1`
- Target: `app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingScreen.kt`
- Source blob: `2c8ed41623e5764eb977e33813a88fa2aeaa9367`
- Source size: 892 physical lines (891 Kotlin lines plus terminal newline)
- Repository instructions: root `AGENTS.md` read. No nested `AGENTS.md` files were found along the target path. The requested `boardlock-engineer` skill is not available in this session; the task's explicit constraints govern this presentation-only pass.

## 1. Component inventory

| Component / section | Current responsibility | Pass 1 decision |
|---|---|---|
| `ReportingScreen` | Collects ViewModel state, applies navigation activity, owns screen layout and screen-level event wiring | Keep in host |
| `ReportingPeriodSelector` | Horizontal preset chips; dispatches selected preset | Keep in host; filter control |
| `periodLabel` | Localized preset label mapping | Keep in host with selector |
| `ReportingScopeSelectors` | Branch dropdown and selected branch display; owns `branchExpanded` local state | Keep in host; stateful filter control |
| `ReportingActivityFilters` | Horizontal activity filter chips | Keep in host; filter control |
| `activityFilterLabel` | Localized activity filter mapping | Keep in host with filter |
| `ReportingPaymentFilters` | Horizontal payment filter chips | Keep in host; filter control |
| `ReportingSummarySection` | Summary heading, horizontal metric cards, credit-outstanding note | Extract to `reporting/components/ReportingSummarySection.kt` |
| `summaryMetricLabel` | Localized summary metric label mapping used only by summary section | Move with summary section unchanged |
| `ReportingRowCard` | Activity row visual card, activity-type label and color mapping, optional customer click target | Extract to `reporting/components/ReportingRowCard.kt`; retain exact rendering and callback |
| `ReportingErrorState` | Error message/detail and retry button | Extract to `reporting/components/ReportingErrorState.kt`; retain exact rendering and callback |
| Inline loading state | Progress indicator and loading text | Keep in host |
| Inline empty state | No-results text | Keep in host |
| Inline custom-date controls | From/to fields and apply button | Keep in host; filter controls |

The three requested extraction targets are already composables. The extraction will move their existing bodies and only the imports required by each new file.

## 2. Filter inventory

1. **Period preset** — `ReportingPeriodSelector`, driven by `state.selectedPreset`; callback `viewModel::selectPreset`. Presets are `TODAY`, `YESTERDAY`, `THIS_WEEK`, `THIS_MONTH`, and `CUSTOM`.
2. **Custom period** — shown only for `CUSTOM`; from/to text fields bind to `state.customFromDate` and `state.customToDate`; edits call `updateCustomFromDate` / `updateCustomToDate`; apply calls `applyCustomPeriod`.
3. **Text query** — field binds to `state.query`; edit calls `updateQuery`.
4. **Branch scope** — `ReportingScopeSelectors` displays `state.branchOptions`, resolves selected branch by matching `selectedBranchId`, and calls `selectBranch(branch.id)`. Its dropdown expansion Boolean remains locally remembered in the existing composable.
5. **Activity type** — chips enumerate `ReportActivityFilter.entries`, reflect `state.activityFilter`, and call `selectActivity`.
6. **Payment type** — chips enumerate `ReportPaymentFilter.entries`, reflect `state.paymentFilter`, and call `selectPayment`.
7. **Navigation-derived activity** — `LaunchedEffect(initialActivityName)` calls `applyNavigationActivity(initialActivityName)`; keep the effect and its key in the host.

No filter computation, option selection, filter projection, or filtering state is to move into presentation components.

## 3. Summary section inventory

Existing `ReportingSummarySection(cards)`:

- Returns immediately when `cards.isEmpty()`.
- Shows the localized period-summary heading.
- Renders a horizontally scrollable row with one Material `Card` per supplied summary model.
- Each card renders `summaryMetricLabel(card.metric)` and the already-formatted `card.amountText`.
- Shows the credit-outstanding note when any supplied card has metric `CREDIT_OUTSTANDING`.
- Reads no ViewModel, repository, navigation, or mutable state.
- Has no callbacks.

Move `ReportingSummarySection` and its private `summaryMetricLabel` helper together. Preserve list order, empty handling, note condition, text resources, spacing, typography and styling.

## 4. Row-card inventory

Existing `ReportingRowCard(row, onOpenCustomer)`:

- Uses system dark-theme state to choose existing flow colors.
- Maps all `ReportActivityType` values to the current color rules.
- Maps all activity types to localized display labels.
- Renders type badge and occurrence time.
- Conditionally renders amount, reference, customer name/phone, payment, reason, and branch.
- Customer text is clickable only when `row.customerId != null`; the callback receives that exact ID.
- Keeps the existing formatting, line limits, ellipsis, typography, spacing, color values, badge shape and click target.

The component consumes `ReportingRowUiModel` and the existing `(String) -> Unit` callback. Do not calculate amounts, format row data, filter rows, change stable keys, or change callback arguments. The host's `LazyColumn` and `items(key = { it.stableId })` remain untouched.

## 5. Error-state inventory

Existing `ReportingErrorState(error, detail, onRetry)`:

- Maps `ACCESS_DENIED`, `INTEGRITY`, `INVALID_CUSTOM_PERIOD`, and `LOAD_FAILED` to their existing localized messages.
- Renders optional nonblank detail in body-small/on-surface-variant styling.
- Renders the existing Retry button and invokes `onRetry`.
- Owns no state and performs no loading/retry logic.

Move this composable unchanged. The host retains the `when` branch that decides when an error is shown and continues to pass `viewModel::retry`.

## 6. State inventory

### ViewModel-backed state observed by the screen

- `state` is obtained from `viewModel.uiState.collectAsStateWithLifecycle().value`.
- Filter and display fields read by this screen:
  - `selectedPreset`
  - `customFromDate`, `customToDate`
  - `query`
  - `branchOptions`, `selectedBranchId`
  - `activityFilter`, `paymentFilter`
  - `isLoading`, `error`, `errorDetail`
  - `summaryCards`, `isEmpty`, `rows`

### Local Compose state

- `ReportingScopeSelectors.branchExpanded` — `remember { mutableStateOf(false) }`; remains in the existing selector with the same ownership and lifetime.

No new state is required by the extracted summary, row, or error components. No state is to be hoisted, duplicated, or moved.

## 7. Callback inventory

### Screen parameters

- `onNavigateBack: () -> Unit` — top app bar back action.
- `onOpenCustomer: (String) -> Unit` — passed to each row card and invoked only by a row with a non-null customer ID.
- `onExportCustomers: () -> Unit` — customer export button.

### ViewModel method references used by the host

- `applyNavigationActivity(initialActivityName)` — from `LaunchedEffect(initialActivityName)`.
- `selectPreset(ReportingPeriodPreset)`.
- `updateCustomFromDate(String)`.
- `updateCustomToDate(String)`.
- `applyCustomPeriod()`.
- `updateQuery(String)`.
- `selectBranch(String)`.
- `selectActivity(ReportActivityFilter)`.
- `selectPayment(ReportPaymentFilter)`.
- `retry()`.

### Extracted component callbacks

- Summary section: none.
- Row card: `onOpenCustomer(String)`, same callback and conditional click semantics.
- Error state: `onRetry()`, same callback.

No callback signature, callback order, argument, or host wiring is to change.

## 8. ViewModel interaction inventory

- `ReportingScreen` receives a `ReportingViewModel`; no repository is called from the screen.
- It collects `viewModel.uiState` using `collectAsStateWithLifecycle()`.
- A `LaunchedEffect(initialActivityName)` applies the navigation-provided activity filter.
- Filter changes are dispatched directly through the method references listed above.
- Retry is dispatched only through `viewModel::retry`.
- `ReportingPdfActions(state = state, ...)` receives the same state object and remains in the host.
- No ViewModel method, state contract, repository call, projection, export, PDF/share action, or navigation behavior is part of this refactor.

## 9. Safe extraction plan

### Pass 1

1. Create `feature/reporting/ui/components/ReportingSummarySection.kt`:
   - Move `ReportingSummarySection` and `summaryMetricLabel`.
   - Keep all display strings, metric branching, empty handling and styling identical.
2. Create `feature/reporting/ui/components/ReportingRowCard.kt`:
   - Move `ReportingRowCard`.
   - Keep row model and callback parameter types unchanged.
   - Keep all type-to-color and type-to-label branches identical.
3. Create `feature/reporting/ui/components/ReportingErrorState.kt`:
   - Move `ReportingErrorState`.
   - Keep error mapping, optional detail condition and retry callback identical.
4. Update only the imports and three call sites in `ReportingScreen.kt`.

### Explicitly keep in host

- ViewModel state collection and navigation effect.
- Scaffold, top bar, loading and empty states.
- All period, date, query, branch, activity and payment filters.
- `ReportingPdfActions` and customer export action.
- The `when` decision tree and summary/row list selection.
- Lazy list, item order and `stableId` keys.
- All navigation and callback wiring.

## 10. Validation and parity requirements

- Compare `ReportingScreen` parameter signature before/after.
- Compare every ViewModel method reference and the navigation effect key.
- Compare all local state declarations and ownership.
- Compare `ReportingSummarySection`, `summaryMetricLabel`, `ReportingRowCard`, and `ReportingErrorState` bodies to their originals; only visibility/package placement/imports may differ as needed for cross-file access.
- Confirm the host's filter controls, `when` decision tree, PDF/export controls, LazyColumn and stable keys remain unchanged.
- Confirm callback declarations and argument forwarding are unchanged.
- Run `:app:assembleDebug` (the requested `assembleDebug` task) and report the exact result. Do not change tests or unrelated files to make validation pass.

## 11. Pass 1 implementation record

Pending. No Kotlin files were modified during Phase 1 analysis.


## 11. Pass 1 implementation record

Implemented on `refactor/reporting-screen-pass1`:

- Added `feature/reporting/ui/components/ReportingSummarySection.kt`.
  - Moved `ReportingSummarySection` and its existing private `summaryMetricLabel` helper.
- Added `feature/reporting/ui/components/ReportingRowCard.kt`.
  - Moved `ReportingRowCard` with its row type/color mapping, labels, display fields and conditional customer click behavior.
- Added `feature/reporting/ui/components/ReportingErrorState.kt`.
  - Moved `ReportingErrorState` with its error mapping, optional detail and retry button.
- Updated `ReportingScreen.kt` only to import and call the extracted components and remove imports that became unused.
- No new functionality was introduced. Extracted composable bodies compare identical to the original bodies after the required top-level visibility change from file-private to module-internal.

### Static parity checks

- `ReportingScreen` parameter signature: unchanged.
- ViewModel method references: all 10 preserved exactly once (`applyNavigationActivity`, `selectPreset`, both custom date updates, `applyCustomPeriod`, `updateQuery`, `selectBranch`, `selectActivity`, `selectPayment`, `retry`).
- `LaunchedEffect(initialActivityName)`: one before and after.
- Local Compose state: the existing `remember` / `mutableStateOf` declarations remain in the host selector (2 declarations before and after); no state was added to the extracted components.
- Summary, row-card and error-state composable bodies: exact parity confirmed, excluding visibility required for cross-package use.
- Customer callback: screen parameter and LazyColumn forwarding unchanged; the row's conditional invocation and customer ID argument are unchanged in the extracted file.
- Retry callback: host still passes `viewModel::retry` unchanged.
- LazyColumn and `stableId` key: unchanged.
- `ReportingPdfActions`, customer export action, loading/empty branches, filters and navigation effect remain in the host.
- No ViewModel, repository, navigation, export/share, sync, database, domain, or business-rule files changed.

### Size

- Host before: 892 physical lines.
- Host after: 565 physical lines.
- Host reduction: 327 lines.
- Extracted composable bodies: 314 lines total (summary 105, row card 154, error state 55).
- New component files including package/import declarations: summary 127 lines, row card 184 lines, error state 71 lines.
- The remaining net host reduction is import cleanup minus the three new component imports.

### Validation

Static parity checks passed. The required `:app:assembleDebug` build is pending CI on the PR head and will be recorded in `reporting-screen-pass1.md`.


### Build diagnosis and minimal correction

The first PR release-validation build reached `:app:compileDebugKotlin` and failed at the existing `branchExpanded by remember { mutableStateOf(false) }` declaration because import cleanup had removed the implicitly used Compose `getValue` and `setValue` operators. Restored those two imports only in commit `4b06dc453306a14f952919277569ceed7e3a6a68`. No state declaration, state owner, UI logic, or behavior was changed. Revalidation is pending on the corrected head.
