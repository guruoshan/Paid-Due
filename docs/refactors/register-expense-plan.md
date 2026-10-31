# Register Expense Presentation Decomposition Plan

## Base and scope

- Repository: `guruoshan/Paid-Due`
- Base branch: `main`
- Audited base commit: `a7d7507a91c272e8c3aa5a01eab427117e403930`
- Working branch: `refactor/register-expense-presentation-decomposition`
- Target: `app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterExpensePresentation.kt`
- Source size at base: 1,338 lines (blob `c6f3fd2374727b2b89d1554a583eeb0f39aeeca4`)
- Repository instructions: `AGENTS.md` read at base. It calls for the `boardlock-engineer` skill, but that skill is not present in the installed skill catalog for this session; source inspection and the task's explicit safety constraints govern this pass.

## 1. Section inventory

| Lines | Symbol / section | Responsibility |
|---|---|---|
| 1–41 | Imports | Compose UI, activity-result launchers, FileProvider, expense projection, WhatsApp/receipt sharing, date formatting |
| 43–57 | `expenseDetailsDateTime` | Formats occurrence time using supplied timezone and default locale |
| 59–70 | `expenseDetailsLabel` | Converts enum-like labels to display text |
| 75–562 | `ExpenseDetailsScreen` | Expense detail screen, correction/void entry points, voucher sharing, supporting-document attachment/open/share |
| 564–572 | `expenseEvidenceMimeType` | Maps evidence file extension to MIME type |
| 575–601 | `ExpenseDetailLine` | Reusable label/value presentation row |
| 603–618 | `ExpenseDialogActions` | UI-to-host callback contract (six callbacks) |
| 621–1277 | `ExpenseDialog` | Expense-entry dialog, form, payee lookup presentation, evidence staging, today's expense list, save/dismiss actions |
| 1280–1316 | `registerExpenseCategoryLabel20` | Localized expense-category display labels |
| 1319–1338 | `registerExpensePaymentLabel20` | Localized payment-category display labels |

## 2. Dialog inventory

1. **Expense entry dialog** — `ExpenseDialog` (lines 621–1277), one `AlertDialog`:
   - title: Expenses
   - scrollable form body
   - confirm: save expense
   - dismiss: dismiss dialog, gated by the existing lock state
2. **Expense correction dialog** — nested in `ExpenseDetailsScreen` (lines 308–340):
   - corrected amount and lifecycle reason fields
   - confirmation calls `onCorrectExpense(amount, reason)`
3. **Expense void dialog** — nested in `ExpenseDetailsScreen` (lines 342–369):
   - explanation and lifecycle reason field
   - confirmation calls `onVoidExpense(reason)`

No additional standalone dialogs are defined in this file.

## 3. Form inventory

### Expense-entry form (`ExpenseDialog`)

- Amount: free-form text; validity is checked using positive decimal, configured decimal precision, exact minor-unit conversion and overflow-safe conversion.
- Category: dropdown backed by `ExpenseCategory.entries`.
- Payment method: CASH / ONLINE filter chips.
- Payee phone: `PhoneNumberInput`, country code + raw number.
- Payee identity: existing-customer result, new-customer name field, lookup/loading/invalid-phone feedback.
- Note: optional text field.
- Reference: optional single-line text field.
- Supporting documents: camera capture and document picker; staged URI list with remove action.
- Error presentation: `state.errorMessage`.
- Payment information: cash/online explanatory text.
- Submit enabled only when the existing lock, amount, phone and payee validity conditions permit.

### Expense correction form

- Corrected amount input initialized from the expense's current amount and decimal precision.
- Required lifecycle reason.
- Existing confirm enablement requires both values to be nonblank.

### Expense void form

- Required lifecycle reason.
- Existing confirm enablement requires a nonblank reason.

## 4. Expense workflow inventory

### Create expense

1. User edits amount/category/payment/payee/note/reference.
2. Phone normalization is derived from `PhoneNumberInputValue`; invalid input yields no normalized phone.
3. A `LaunchedEffect` calls `onLookupPayeeByPhone(null)` for blank phone or passes a valid normalized phone to the host.
4. Lookup result is associated with the exact normalized phone before it is treated as an existing customer.
5. A second `LaunchedEffect` copies an identified customer's name into the form state.
6. Save callback receives amount text, category enum, payment enum, effective payee name, normalized nullable phone, note and reference.
7. Camera/document results stage URI strings through the existing action callback.

### Expense detail and lifecycle

- Screen refreshes supporting documents when `details.expenseId` changes.
- Camera/document attachment passes URI strings to `onAttachSupportingDocument`.
- Correction/void actions are conditionally displayed using `canManageFinancialLifecycle`.
- Correction and void dialogs call their existing host callbacks; lifecycle orchestration remains outside this presentation file.
- Voucher sharing composes the same expense message, attempts image voucher sharing, and retains the text-only WhatsApp fallback.
- Supporting evidence can be opened or shared using FileProvider intents.

### Today's expense list

- Shows loading, empty, or up to ten expense rows from `state.todayExpenses`.
- Rows display formatted amount, category, payment method, optional payee and note.
- Row click invokes `onOpenExpenseDetails(expense.id)`.
- A “latest 10” note appears when the source list exceeds ten entries.

## 5. Callback inventory

### `ExpenseDialogActions` — six callbacks, declaration order preserved

1. `onStageSupportingDocument(String)`
2. `onLookupPayeeByPhone(String?)`
3. `onDismiss()`
4. `onRemoveSupportingDocument(String)`
5. `onOpenExpenseDetails(String)`
6. `onSave(String, ExpenseCategory, PaymentCategory, String, String?, String, String)`

Call sites in this file: staging from both activity-result launchers; phone lookup from the lookup effect; dismiss from dialog dismissal and dismiss button; remove staged URI; open expense details; save with seven existing arguments.

### `ExpenseDetailsScreen` host callbacks

- `onRefreshSupportingDocuments()`
- `onAttachSupportingDocument(String)`
- `onCorrectExpense(String, String)`
- `onVoidExpense(String)`
- `onBack()`

No callback is to be renamed, reordered, retyped, or moved to a ViewModel/repository layer in this decomposition.

## 6. State inventory

### `ExpenseDetailsScreen`

- `pendingCameraUri: Uri?` — transient camera result association.
- `showExpenseCorrection: Boolean` — correction dialog visibility.
- `showExpenseVoid: Boolean` — void dialog visibility.
- `correctedAmountInput: String` — saveable, keyed by expense ID.
- `lifecycleReason: String` — saveable, keyed by expense ID.
- Activity-result launchers for document selection and camera capture.
- Refresh effect keyed by expense ID.

### `ExpenseDialog`

- `pendingExpenseCameraUri: Uri?` — transient camera result association.
- `amount: String`
- `categoryName: String`
- `paymentName: String`
- `note: String`
- `reference: String`
- `payeeName: String`
- `payeePhoneCountryCode: String`
- `payeePhoneRawNumber: String`
- `categoryExpanded: Boolean`
- Activity-result launchers for document selection and camera capture.

Derived values retained in the host: `payeePhone`, `retryPending`, `locked`, category/payment enum conversion, `amountValid`, normalized phone, phone validity, existing-customer match, lookup-complete flag, new-customer flag and payee validity. These contain workflow/validation semantics and are not extraction targets for Pass 1.

## 7. ViewModel interaction inventory

- No ViewModel is directly referenced in this file.
- `ExpenseDialog` receives immutable `RegisterUiState` and an `ExpenseDialogActions` callback bundle.
- The six action callbacks form the boundary to the host/ViewModel orchestration.
- `ExpenseDetailsScreen` similarly receives state/data and explicit callbacks.
- `RegisterUiState` fields read by the entry dialog include `inFlightExpenseCommand`, `isExecuting`, `expensePayeeLookupPhone`, `expensePayeeLookupResult`, `expensePayeeLookupLoading`, `errorMessage`, `stagedExpenseSupportingDocumentUris`, `todayExpenses`, and `expenseLoading`.
- Do not change these state contracts, their ownership, or the host's callback wiring.

## 8. Largest UI blocks

| Lines | Approx. size | Block | Assessment |
|---|---:|---|---|
| 621–1277 | 657 lines | `ExpenseDialog` | Largest block; combines local form state, derived validity/lookup state, form UI, evidence controls, today's list and dialog actions |
| 75–562 | 488 lines | `ExpenseDetailsScreen` | Detail summary, lifecycle dialogs, voucher sharing, evidence attachment and evidence actions |
| 971–1041 | 71 lines | Payee entry/lookup presentation | Candidate visual subsection, but tightly coupled to lookup-derived state |
| 1101–1123 | 23 lines | Staged evidence rows and removal | Presentation with callback; safe only if state/URI ownership stays in host |
| 1126–1230 | 105 lines | Today's expense list | Candidate presentation component, but amount formatting and navigation callback must be passed through unchanged |
| 283–369 | 87 lines | Lifecycle controls and dialogs | Do not move workflow state or lifecycle callback orchestration in Pass 1 |

## 9. Safe extraction assessment

### Pass 1 candidates

1. **Expense detail header** — purely visual back/title row. Keep `onBack` callback and all host navigation ownership unchanged.
2. **Expense detail summary card** — display-only amount and detail fields. Precompute all formatted strings in the host and pass them in; do not move amount/date/label calculations into a component.
3. **Expense detail label/value row** — existing `ExpenseDetailLine`; can move unchanged to a dedicated component file if it reduces file-local noise.
4. **Today's expense list** — possible later extraction if all source state, amount formatting, category/payment labels and row callback are passed explicitly. Keep it out of Pass 1 to avoid coupling display extraction with calculations or click behavior.

### Explicitly defer

- Expense form state and derived validity calculations.
- Phone normalization, lookup effects and existing/new customer decisions.
- Save callback argument construction and enablement rules.
- Camera/document launchers, URI lifecycle and FileProvider handling.
- Expense correction/void state and lifecycle callbacks.
- Receipt/voucher composition, image sharing and WhatsApp fallback.
- Supporting-document open/share actions.
- Today's expense amount calculations and navigation callback wiring.
- All ViewModel, repository, database, sync and navigation contracts.

## 10. Pass 1 guardrails and validation

- Keep `ExpenseDetailsScreen`, `ExpenseDialog`, and `ExpenseDialogActions` public/internal signatures unchanged.
- Preserve all callback declarations and call sites, argument order and enabled conditions.
- Preserve all `remember` / `rememberSaveable` ownership and keys.
- Preserve every calculation, effect key, launcher contract, intent, URI and file-provider authority.
- Do not touch expense calculations, payment processing, repository/ViewModel calls, navigation, sync, database, business rules, receipt generation, expense lifecycle or validation.
- Compare source before/after for parameter signatures, callback occurrences/order, state declarations, effect keys, and key workflow expressions.
- Run the repository's prescribed relevant lint/build/unit-test checks after implementation; report any skipped gates explicitly.


## 11. Pass 1 implementation record

Implemented on `refactor/register-expense-presentation-decomposition`:

- Added `ExpenseDetailsPresentation.kt`.
- Extracted `ExpenseDetailsHeader` (back/title row) without changing the `onBack` callback.
- Extracted `ExpenseDetailsSummaryCard` (amount and detail rows). All display values are still calculated/formatted by `ExpenseDetailsScreen` and passed as strings; no calculations moved.
- Moved the existing `ExpenseDetailLine` composable unchanged into the new presentation file.
- Replaced the original header and summary markup with calls to the extracted composables.
- Removed only the now-unused `TextAlign` and `sp` imports from the host.

### Pass 1 scope measurements

- Host source: 1,338 lines at base; 1,195 lines after extraction (143-line reduction).
- New presentation component: 131 lines.
- Files changed so far: this plan, the host Kotlin file, and the new presentation Kotlin file.
- No form state, validation, calculations, effects, callback contracts, launcher ownership, receipt/WhatsApp behavior, supporting-document behavior, or lifecycle behavior was moved.

### Parity checks performed against base

- `ExpenseDetailsScreen` signature unchanged.
- `ExpenseDialog` signature unchanged.
- `ExpenseDialogActions` declaration unchanged.
- All nine `actions.*` call-site occurrences remain in the same order.
- All 15 `remember` / `rememberSaveable` state declarations remain in the host.
- No changes to ViewModel, repository, navigation, sync, database, or business-rule files.

### Validation status

Static source parity checks passed. Build, lint, unit tests and instrumentation tests have not yet been run for this branch; CI validation is pending.
