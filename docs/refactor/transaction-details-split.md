# Transaction Details Screen split — proposal (Step 1 only)

## Scope and audited base

- Repository: `guruoshan/Paid-Due`
- Base branch: `main`
- Audited commit: `bed476678684fa8b14f64138a7503b6c7781ea76`
- Source: `app/src/main/java/com/dailymoneyregister/feature/register/ui/TransactionDetailsScreen.kt`
- Source blob at audited base: `eb1d5b5a40990e9374812810cc78acfacbb9df03`
- Source size: 1,849 lines; 22 top-level declarations.
- Working branch: `refactor/transaction-details-split`, created from the audited `main` commit.

### Required reference check

The requested `docs/audit/code-health.md` is not present on the audited `main` commit (GitHub contents lookup returned not found, and repository code search returned no matching path). I did not substitute a different audit document or infer the missing section 3. The proposal below is based on the current source and the governance documents listed in the task. This discrepancy should be resolved by the reviewer if section 3 contains additional constraints.

## Governance applied

- `AGENTS.md`: inspect relevant source first; make minimal scoped changes; preserve financial evidence, offline data, SQLCipher integrity, audit history, and permissions.
- `PROJECT_GOVERNANCE.md` §1: governance hierarchy and separate review for governed changes.
- `PROJECT_GOVERNANCE.md` §9: keep the app light; do not add dependencies or oversized abstractions for this refactor.
- `PROJECT_GOVERNANCE.md` §14: proposal/review/approval before implementation where required; preserve invariants; no unrelated cleanup; verify affected tests; inspect exact changed files; stage explicit paths only.
- `RELEASE_CHECKLIST.md`: preserve financial integrity, unit/integration coverage where relevant, accessibility/localization, and exact intended file scope.

This is a presentation-only refactor proposal. It must not alter the financial read model, lifecycle behavior, callbacks, permission decisions, audit content, or transaction data.

## Declaration inventory and proposed destinations

Line ranges are inclusive and refer to the audited source at the SHA above. Every current top-level declaration is accounted for.

| # | Declaration | Current lines | Current visibility | Proposed destination |
|---:|---|---:|---|---|
| 1 | `TransactionTimelineItem` (sealed interface and nested variants) | 48–102 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimelineModel.kt` |
| 2 | `TransactionDetailsScreen` | 104–1102 | public | Keep the public entry point in `feature/register/ui/TransactionDetailsScreen.kt`; reduce it to screen orchestration and delegate extracted sections |
| 3 | `OriginalTransactionTimelineCard` | 1103–1141 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 4 | `CreditRepaymentTimelineCard` | 1142–1172 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 5 | `AmountCorrectionTimelineCard` | 1173–1216 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 6 | `PaymentMethodChangeTimelineCard` | 1217–1261 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 7 | `RefundTimelineCard` | 1262–1294 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 8 | `VoidTimelineCard` | 1295–1316 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 9 | `SettlementTimelineCard` | 1317–1401 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 10 | `TimelineCard` | 1402–1426 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 11 | `TimelineReason` | 1427–1440 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 12 | `TimelineActorOperation` | 1441–1460 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 13 | `TimelineTimestamp` | 1461–1474 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 14 | `simplePaymentLabel` | 1475–1494 | private | `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` |
| 15 | `TransactionDetailRow` | 1495–1520 | private | `feature/register/ui/transactiondetails/TransactionDetailsComponents.kt` |
| 16 | `TransactionActionChoice` | 1521–1540 | private | `feature/register/ui/transactiondetails/TransactionDetailsComponents.kt` |
| 17 | `TransactionItemCard` | 1541–1621 | private | `feature/register/ui/transactiondetails/TransactionDetailsItems.kt` |
| 18 | `lifecycleLabel` | 1622–1682 | private | `feature/register/ui/transactiondetails/TransactionDetailsFormatting.kt` |
| 19 | `paymentMethodLabel` | 1683–1765 | private | `feature/register/ui/transactiondetails/TransactionDetailsFormatting.kt` |
| 20 | `settlementPurposeLabel` | 1766–1813 | private | `feature/register/ui/transactiondetails/TransactionDetailsFormatting.kt` |
| 21 | `confirmationLabel` | 1814–1829 | private | `feature/register/ui/transactiondetails/TransactionDetailsFormatting.kt` |
| 22 | `formatTransactionTimestamp` | 1830–1849 | private | `feature/register/ui/transactiondetails/TransactionDetailsFormatting.kt` |

## Proposed extracted screen sections

The large body of `TransactionDetailsScreen` (lines 104–1102) should become a small coordinator without changing its public signature or package-qualified name. Extract the existing UI sections, preserving their current order and inputs:

| Proposed file | Responsibility / source section |
|---|---|
| `feature/register/ui/transactiondetails/TransactionDetailsHeader.kt` | Transaction summary, visible transaction metadata, and audit-details disclosure currently assembled in the screen body (approximately lines 215–476). |
| `feature/register/ui/transactiondetails/TransactionDetailsActions.kt` | Existing correction, refund/refund-to-coupon, void, and payment-method action controls and dialogs (approximately lines 477–969). Keep state ownership and callback wiring explicit; do not move command execution into UI. |
| `feature/register/ui/transactiondetails/TransactionDetailsItems.kt` | Transaction item list/empty state (approximately lines 970–1048) plus the existing `TransactionItemCard` declaration (1541–1621). |
| `feature/register/ui/transactiondetails/TransactionDetailsTimeline.kt` | Timeline rendering currently in the screen body (approximately lines 1049–1102) plus timeline cards and their local presentation helpers (1103–1494). |
| `feature/register/ui/transactiondetails/TransactionDetailsComponents.kt` | Reusable detail row and action-choice components (1495–1540). |
| `feature/register/ui/transactiondetails/TransactionDetailsFormatting.kt` | Lifecycle/payment/settlement labels and timestamp formatting (1622–1849). |
| `feature/register/ui/transactiondetails/TransactionDetailsTimelineModel.kt` | Private timeline item model and variants (48–102). |

All new files should use the `com.dailymoneyregister.feature.register.ui.transactiondetails` package. The existing public entry point remains in `com.dailymoneyregister.feature.register.ui`, with its exact name, parameter types, parameter order, defaults, and call contract unchanged. The implementation may import internal declarations from the new subpackage; no new public API is proposed.

## Public declarations and call sites in app/src

Only one declaration in this file is public: `TransactionDetailsScreen`.

- `app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterDestinationHost.kt:73` calls `TransactionDetailsScreen(`.
- The call supplies `details`, `currency`, branch `timezoneId`, ViewModel method references for back/correction/refund/refund-to-coupon/void/payment change, and permission/progress/error/info state (lines 74–90).
- Repository-wide `app/src` code search for the exact declaration name returned this call site and the declaration itself; no other external call site was found.
- All other 21 declarations in the inventory are top-level `private` declarations and therefore have no supported external call sites. Search hits for similarly named private functions in `HistoryScreen.kt`, `CustomerDetailScreen.kt`, or the customer exporter are separate declarations, not calls to these private functions.

**External-use marker:** `TransactionDetailsScreen` — used outside this file by `RegisterDestinationHost.kt:73`.

## Declarations that should not move

- **`TransactionDetailsScreen` public entry point:** keep the declaration in its existing file and package. Its public fully qualified name and default-argument call contract are used by `RegisterDestinationHost.kt`. The body should be shortened through extraction, not by moving/renaming the public API.
- **Screen-owned presentation state and orchestration:** keep `rememberSaveable` state, transaction-keyed save keys, timezone validation/fail-closed branch, and callback coordination in the screen coordinator unless an extraction can preserve the exact ownership and keys. They are not independent business state.
- **ViewModel and command execution:** do not move ViewModel access or financial command execution into extracted composables. The current screen receives callbacks and state from its caller; preserve that unidirectional boundary.

## Risks and controls

### Visibility and package boundaries

- Kotlin top-level `private` is file-private. A declaration moved to another file cannot remain `private` if the screen coordinator or another extracted file calls it.
- Prefer `internal` only for the small set of cross-file composables/helpers/types that need it; keep all other declarations `private`. Do not make helpers public merely to make the split compile.
- The sealed `TransactionTimelineItem` is currently private but is constructed by the screen and consumed by timeline rendering. If those responsibilities are separated, it will need module-internal visibility (or both responsibilities must remain together).
- `TransactionDetailRow`, `TransactionActionChoice`, `TransactionItemCard`, `lifecycleLabel`, `paymentMethodLabel`, and `formatTransactionTimestamp` may be referenced across the proposed file boundaries. Confirm actual usages before selecting the minimal visibility changes.
- Keep the public screen in its current package. Do not change its name, parameter list/order, defaults, or call-site import/FQN.

### Compose behavior

- Preserve composable call order, conditional branches, modifier chains, layout parameters, semantics, and all existing string/resource references.
- Preserve `rememberSaveable` keys and transaction scoping. Moving state to a different composable can change save/restore lifetime and dialog behavior.
- Keep dialogs, progress/error/info rendering, and action enablement behavior identical.
- Do not duplicate or subtly change timeline ordering, stable IDs, or timestamp/timezone handling.

### Financial-value and ViewModel boundaries

- The composable does **not** read a `RegisterViewModel` directly. `RegisterDestinationHost` owns the ViewModel and passes method references and state into the screen.
- The composable does read financial data directly from `TransactionDetailsReadModel`, notably `details.financial` and its transaction/payment/lifecycle fields, and renders `details.items` and timeline event values. This is acceptable presentation of the supplied read model, but it is a key refactor risk: do not recalculate financial truth, mutate values, derive a second financial model, or bypass the read repository.
- Keep action execution behind the existing callbacks. Do not move repository/DAO access, permission checks, refund/correction/void calculations, or ViewModel decisions into UI components.
- Preserve the existing distinction between transaction-lifetime details and period financial activity; this task must not change either model.

### Text and API compatibility

- No visible text changes: preserve literal labels, string resource IDs, formatting arguments, ordering, and punctuation.
- No public names used by other files change. Private helper names may be relocated, but avoid renaming unless necessary and approved.
- No dependencies, resources, database, migrations, ViewModel, repository, or financial logic changes are in scope.

## Exact verification plan (for Step 2 after approval)

No verification commands are run in this proposal-only step. After implementation approval:

1. **Compile**
   - Windows: `.gradlew.bat :app:compileDebugKotlin`
   - Unix/macOS: `./gradlew :app:compileDebugKotlin`

2. **Unit tests**
   - Windows: `.gradlew.bat :app:testDebugUnitTest`
   - Unix/macOS: `./gradlew :app:testDebugUnitTest`
   - Confirm the existing focused coverage remains green, especially:
     - `app/src/test/java/com/dailymoneyregister/TransactionDetailsUiLogicTest.kt` (timezone validation and deterministic timeline ordering).
     - `app/src/test/java/com/dailymoneyregister/feature/register/ui/TransactionLifecycleActionsViewModelTest.kt` (details action callback/ViewModel lifecycle behavior).
   - Run the complete `:app:testDebugUnitTest` task, not only the focused classes. No dedicated `TransactionDetailsScreen` Compose UI test was found in the source search.

3. **Manual device checks**
   - Open Register → History → select a transaction → Transaction Details; verify the public entry route and Back behavior.
   - Verify transaction summary, original payment classification, metadata, audit-details disclosure, and all visible text.
   - Verify a transaction with multiple items and a transaction with no items.
   - Verify timeline rendering/order for original sale, credit repayment, amount correction, payment-method change, refund, void, and settlement entries, including equal/near timestamps where reproducible.
   - Exercise amount-correction, refund (including unpaid-credit/refund-to-coupon routing), void, and payment-method dialogs; check enabled/disabled states, cancel/back behavior, in-progress state, and error/info messages.
   - Verify valid branch timezone formatting and the unavailable/invalid-timezone fallback.
   - Repeat key details and dialog checks in Arabic and Urdu RTL, including text scaling/accessibility focus where practical.

4. **Diff/scope checks**
   - Confirm visible text/resource references and public signature are unchanged.
   - Confirm no financial/data/ViewModel/repository behavior or dependencies changed.
   - Run `git diff --check`; inspect the exact changed paths; stage explicit paths only.
   - Confirm only the intended Kotlin split files changed in Step 2, with no unrelated cleanup.

## Step 1 boundary

This commit is the proposal only. No Kotlin source, tests, resources, Gradle files, database files, or workflows are to be changed in Step 1. Do not begin Step 2 until explicit human approval is given.
