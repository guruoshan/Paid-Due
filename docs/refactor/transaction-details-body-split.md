# Transaction Details screen body split — implementation plan

## Scope and source snapshot

- Repository: `guruoshan/Paid-Due`
- Branch: `refactor/transaction-details-split`
- PR: #103
- Audited branch head before this document: `1b0775e24db5b1dcda5afe1d46b7c7b62ff6660c`
- Source: `app/src/main/java/com/dailymoneyregister/feature/register/ui/TransactionDetailsScreen.kt`
- Source blob: `2aa9e03ddfa421b8a10eb3c9b6434e7239bd42e9`
- Source length: 1,061 lines; `TransactionDetailsScreen` is lines 64–1060.
- Earlier proposal: `docs/refactor/transaction-details-split.md`

This plan is based on the current branch source, not the older 1,849-line `main` snapshot recorded in the earlier proposal. The body currently includes the timezone fallback, parent-owned saveable state, derived eligibility, the scroll container, summary/audit content, action controls and four dialogs, items, and timeline assembly/rendering. The earlier proposal's approximate ranges must not be reused as current line ranges.

**Scope boundary:** document only. The split must remain presentation-only. Keep the public `TransactionDetailsScreen` signature, callback contract, authoritative `details` read model, permission gates, action eligibility, financial calculations, timeline ordering, labels/resources, and timezone behavior unchanged. Do not edit Kotlin, tests, resources, Gradle, database, or workflow files as part of this planning task.

---

## 1. Current body sections in display/execution order

Line ranges below are inclusive and refer to the source blob above.

| Order | Lines | Section | What the user sees |
|---:|---:|---|---|
| 1 | 83–108 | Timezone validation and unavailable fallback | If the supplied branch timezone cannot be validated, the screen shows a Back button and an error explaining that transaction-details timezone information is unavailable, then returns without showing transaction content. |
| 2 | 110–219 | Screen state and derived presentation gates | No independent content is displayed here; this prepares the transaction, remembers disclosure/action form state, derives the credit-return route and action eligibility, and creates the scroll state used by the visible screen. |
| 3 | 221–254 | Scroll container and page title/back row | The user sees a vertically scrollable details page with a Back button and centered Transaction Details title. |
| 4 | 256–430 | Transaction summary and audit disclosure | The user sees lifecycle/status wording, current amount, current payment label, original timestamp, returned/net amounts, and an expandable audit card with transaction ID, original/current amounts, returned/net amounts, lifecycle status, and original/current payment classifications. |
| 5 | 430–921 | Action controls, eligibility feedback, and dialogs | The user sees Correct Amount, Refund, Void, and Change Payment controls, any void-block explanation, action error/info messages, and the corresponding correction, refund, void, and payment-change dialogs when opened. |
| 6 | 923–941 | Transaction items and empty state | The user sees the Items heading followed by each item card, or a no-items message when the transaction has no items. |
| 7 | 943–1058 | Activity heading and transaction lifetime timeline | The user sees the activity explanation and chronologically sorted original-sale, repayment-allocation, amount-correction, payment-method-change, refund, void, and supported settlement timeline cards. |

### Section boundaries and observations

- Section 2 is orchestration, not a candidate for a new UI child. It should remain in `TransactionDetailsScreen` so saveable-state ownership and derived gates do not move.
- Section 3 owns the single scroll container. Keep one scroll owner; do not give extracted children independent scroll containers.
- Section 4 ends immediately before the spacer/action row at line 430. The action controls currently share the summary Card's inner Column, so extracting the summary and actions must preserve their existing visual grouping, padding, spacing, and order.
- Section 5 is one large logical section but contains four independently stateful dialogs. They should be extracted incrementally, not moved as one unreviewed block.
- Section 7 constructs the timeline in the screen body. The existing `Settlement` rendering branch is present, but the visible builder shown in this body adds the original transaction, repayment allocations, corrections, payment-method changes, refunds, and voids; do not invent or add settlement data during this UI split.

---

## 2. State read, callbacks called, and cross-section state effects

### 1 — Timezone validation and unavailable fallback (83–108)

- **Reads:** `timezoneId`, `modifier`, and `onBack`; the validated result is derived by `validateTransactionDetailsTimezoneId(timezoneId)`.
- **Caller callbacks:** `onBack` is called by the fallback Back button. No financial action callback is called.
- **State effects:** no remembered state is created or changed. If validation fails, the early return prevents sections 2–7 from composing.

### 2 — Screen state and derived presentation gates (110–219)

- **Reads:** `details.financial`, especially `transaction.transaction.id`, `currentPaymentCategory`, `currentPaymentChannel`, `creditRepaymentAllocations.size`, and `details.actionEligibility`; caller-supplied permission flags `correctAmountEnabled`, `refundEnabled`, `voidEnabled`, `changePaymentEnabled`; and `actionInProgress`.
- **rememberSaveable state created here:**
  - `showAuditDetails`: key arguments `transaction.transaction.id`, `"showAuditDetails"`, initial `false`.
  - `showCorrectAmountDialog`: key argument `transaction.transaction.id` only, initial `false`. This is the exceptional key with no explicit descriptive string.
  - `correctedAmountInput`: keys transaction ID + `"correctedAmount"`, initial empty string.
  - `correctionReasonInput`: keys transaction ID + `"correctedReason"`, initial empty string.
  - `showRefundDialog`: keys transaction ID + `"refundDialog"`, initial `false`.
  - `refundAmountInput`: keys transaction ID + `"refundAmount"`, initial empty string.
  - `refundReasonInput`: keys transaction ID + `"refundReason"`, initial empty string.
  - `refundDestination`: keys transaction ID + `"refundDestination"`, initial current payment category or CASH.
  - `showVoidDialog`: keys transaction ID + `"voidDialog"`, initial `false`.
  - `voidReasonInput`: keys transaction ID + `"voidReason"`, initial empty string.
  - `showChangePaymentDialog`: keys transaction ID + `"changePaymentDialog"`, initial `false`.
  - `changePaymentReasonInput`: keys transaction ID + `"changePaymentReason"`, initial empty string.
  - `changePaymentDestination`: keys transaction ID + `"changePaymentDestination"`; initial ONLINE when current category is CASH, otherwise CASH.
- **Other derived values:** `creditReturnRoute`; `actionEligibility`; `canCorrectAmount`, `canRefund`, `canVoid`, and `canChangePayment`; and `scrollState = rememberScrollState()`.
- **Caller callbacks:** none are called while deriving state. References to the callbacks are passed down to the action UI.
- **State effects:** this section owns the state read or changed by Sections 4 and 5. Keep every `rememberSaveable` call here. Do not recreate these states inside extracted composables.

### 3 — Scroll container and page title/back row (221–254)

- **Reads:** `modifier`, `scrollState`, and the fixed title/back string resources and theme styles.
- **Caller callbacks:** `onBack` is called by the Back button.
- **State effects:** scrolling mutates the remembered `scrollState`; no other section reads it. No transaction/action state is changed.

### 4 — Transaction summary and audit disclosure (256–430)

- **Reads from `details.financial`:** void status; lifecycle states; current payment category; current amount; transaction creation timestamp and ID; refunded amount; net amount; original amount; original payment category/channel; current payment channel. It also reads `currency` for amount formatting and the validated timezone for timestamp formatting.
- **Reads remembered state:** `showAuditDetails.value`.
- **Local UI mutation:** the disclosure TextButton toggles `showAuditDetails.value`.
- **Caller callbacks:** none.
- **State effects:** changes only `showAuditDetails`, which is owned by Section 2. No other section reads that disclosure flag. The displayed financial values are read-only projections; this section must not calculate or mutate financial truth.

### 5 — Action controls, eligibility feedback, and dialogs (430–921)

- **Reads from `details`:** `details.financial` current amount/net amount/current payment category/current payment channel; `details.actionEligibility` including `voidBlockReason); and the previously derived `creditReturnRoute`.
- **Reads caller state/flags:** `currency`, `actionInProgress`, `actionError`, `actionInfo`, and the four derived enablement booleans `canCorrectAmount`, `canRefund`, `canVoid`, `canChangePayment`.
- **Reads and changes remembered state owned by Section 2:**
  - Correction: `showCorrectAmountDialog`, `correctedAmountInput`, `correctionReasonInput`.
  - Refund: `showRefundDialog`, `refundAmountInput`, `refundReasonInput`, `refundDestination`.
  - Void: `showVoidDialog`, `voidReasonInput`.
  - Payment change: `showChangePaymentDialog`, `changePaymentReasonInput`, `changePaymentDestination`.
- **Caller callbacks invoked:**
  - `onCorrectAmount(correctedAmountInput.value, correctionReasonInput.value)`.
  - `onRefundToCoupon(refundAmountInput.value, refundReasonInput.value)` when the selected destination is COUPON.
  - Otherwise `onRefund(refundAmountInput.value, refundDestination.value, refundReasonInput.value)`.
  - `onVoid(voidReasonInput.value)`.
  - `onChangePaymentMethod(changePaymentDestination.value, changePaymentReasonInput.value)`.
- **Local state mutations:** opening an action resets the relevant input/reason (and destination where currently done) before setting its dialog flag; fields and destination choices update their remembered values; dismiss/confirm paths close their dialog. Preserve the existing distinction that correction/void/payment-change dismissal is blocked while an action is in progress, while the refund dialog's current dismiss behavior is not guarded in the same way.
- **State effects:** this is the only visible section that changes the action form/dialog states. Dialog state and form values are not read by Sections 4, 6, or 7. The parent still owns them so their saveable identity does not change.

### 6 — Transaction items and empty state (923–941)

- **Reads:** `details.items` and `currency`; each item is passed to `TransactionItemCard`.
- **Caller callbacks:** none.
- **State effects:** none. This is read-only rendering.

### 7 — Activity heading and transaction lifetime timeline (943–1058)

- **Reads:** `details.creditRepayments`, `details.creditRepaymentAllocations`, `details.amountCorrections`, `details.paymentMethodChanges`, `details.refundEvents`, `details.voidEvents`, `details` itself for the original transaction card, `transaction.transaction.createdAt`, `transaction.transaction.id`, `currency`, and the validated timezone.
- **Derived local data:** repayment lookup by ID; timeline item list; stable original item ID; and `orderedTimeline` using `sortTransactionDetailsTimeline(occurredAt, stableId)`.
- **Caller callbacks:** none.
- **State effects:** none. Do not change lookup semantics, event inclusion, ordering, stable IDs, card selection, or timestamp/timezone inputs as part of extraction.

---

## 3. Proposed composables, files, parameters, and value sources

Keep the public entry point and all saveable state declarations in `TransactionDetailsScreen.kt`. New composables should receive values and callbacks; they should not create a second source of state, read a ViewModel, call repositories/DAOs, or make permission/financial decisions.

| Current section | Proposed composable | File | Parameters and source |
|---|---|---|---|
| Timezone fallback (83–108) | `TransactionDetailsTimezoneUnavailable` (optional extraction; safest to leave inline initially) | Keep inline in `TransactionDetailsScreen.kt` for first pass, or `transactiondetails/TransactionDetailsFallback.kt` later | `modifier` from screen parameter; `onBack` from caller. The validated null condition remains in the parent. |
| State/derived orchestration (110–219) | **No child composable** | Keep in `TransactionDetailsScreen.kt` | Keep all `rememberSaveable`, `creditReturnRoute`, eligibility booleans, timezone validation, and scroll-state creation here. |
| Page title/back row (231–254) | `TransactionDetailsTopBar(onBack: () -> Unit)` | `transactiondetails/TransactionDetailsHeader.kt` | `onBack` from screen caller. Title/resource/theme remain inside the composable. |
| Summary/audit (256–430) | `TransactionDetailsSummary(details, currency, timezoneId, showAuditDetails, onToggleAuditDetails)` | `transactiondetails/TransactionDetailsSummary.kt` (or the existing `TransactionDetailsHeader.kt` if keeping the file count small) | `details`, `currency`, and validated timezone from parent; disclosure value and toggle callback from the parent-owned `showAuditDetails`. Do not pass a ViewModel. |
| Action controls/dialogs (430–921) | `TransactionDetailsActions(...)`, with private child composables for each dialog introduced one at a time | Existing `transactiondetails/TransactionDetailsActions.kt` | `details`, `currency`, `creditReturnRoute`, action eligibility/void block reason, `canCorrectAmount`, `canRefund`, `canVoid`, `canChangePayment`, `actionInProgress`, `actionError`, `actionInfo`; each dialog flag/input/destination as a value plus an `on…Change` callback; all five caller action callbacks with their exact existing signatures. Every value originates from Section 2 or the original screen parameters. |
| Items (923–941) | `TransactionDetailsItems(items, currency)` | Existing `transactiondetails/TransactionDetailsItems.kt` | `items = details.items`; `currency` from caller. Reuse existing `TransactionItemCard`; do not duplicate its implementation. |
| Timeline (943–1058) | `TransactionDetailsTimelineSection(details, currency, timezoneId)` | Existing `transactiondetails/TransactionDetailsTimeline.kt` | `details`, `currency`, and validated timezone from parent. Keep timeline construction and sorting in this composable initially so the model and rendering move together without altering their behavior. |

### Parameter and state-passing conventions

1. **Parent owns state.** Do not move any `rememberSaveable` call into `TransactionDetailsSummary`, `TransactionDetailsActions`, or dialog children.
2. Prefer primitive/value parameters plus explicit callbacks for extracted children. Example: `showRefundDialog: Boolean` and `onShowRefundDialogChange: (Boolean) -> Unit`, rather than allowing a child to own the saveable state. For text fields, pass the current string and an `on…Change: (String) -> Unit`.
3. For destination fields, use `PaymentCategory` and `(PaymentCategory) -> Unit`; retain the current fallback/initialization logic in the parent.
4. Keep callback signatures byte-for-byte equivalent in meaning and argument order. The extracted UI must not parse/validate amounts, calculate refunds, decide coupon/credit financial effects, or call a data layer.
5. Pass the already-derived `can…` booleans and `creditReturnRoute` down. Do not recompute permission or financial eligibility inside the child.
6. Keep `TransactionDetailsScreen` in package `com.dailymoneyregister.feature.register.ui`, with its current public name, parameter order, types, defaults, and single caller contract unchanged.

---

## 4. Risks and controls

### Shared state and dependencies

- **Summary disclosure:** Section 4 reads/writes `showAuditDetails`; it is created in Section 2. Keep its owner in the parent and pass a controlled value/callback.
- **Action state cluster:** Section 5 reads/writes all dialog flags and form inputs created in Section 2. Keep all 13 saveable states in the parent and pass controlled values/callbacks to the action composable.
- **Action enablement dependency:** `canCorrectAmount`, `canRefund`, `canVoid`, and `canChangePayment` depend on caller permission flags, `details.actionEligibility`, and `actionInProgress`. Derive them only once in the parent.
- **Refund route dependency:** `creditReturnRoute` depends on current payment category/channel and repayment-allocation count. Do not rederive it differently in the refund dialog.
- **Financial read-model dependency:** summary, actions, items, and timeline all read from the same supplied `details` model. Do not create local financial state, recalculate net/refunded values, or replace the read model with ad hoc parameters that can disagree.
- **Timeline join dependency:** repayment allocations resolve a repayment through `repaymentById.getValue`. Do not change missing-reference behavior or sorting as part of a presentation-only split.
- **Scroll ownership:** the parent owns one `rememberScrollState`; extracted sections must not introduce nested scrolling or alter the existing vertical order.

### rememberSaveable identity/key risks

The following keys are part of the current behavior and must remain exact:

| State | Current key arguments | Risk if moved |
|---|---|---|
| `showAuditDetails` | `transaction.transaction.id`, `"showAuditDetails"` | Moving its saveable call changes the saveable registry call site/identity and may lose or restore disclosure state unexpectedly. |
| `showCorrectAmountDialog` | `transaction.transaction.id` only | Highest key-review priority: it has no explicit descriptive string key; preserve the one-argument key and parent call site. |
| `correctedAmountInput` | transaction ID, `"correctedAmount"` | Moving/rekeying can discard or cross-restore a partially entered amount. |
| `correctionReasonInput` | transaction ID, `"correctedReason"` | Same risk for a partially entered reason. |
| `showRefundDialog` | transaction ID, `"refundDialog"` | Could reopen/close incorrectly after navigation or process recreation. |
| `refundAmountInput` | transaction ID, `"refundAmount"` | Could lose or cross-restore the entered refund amount. |
| `refundReasonInput` | transaction ID, `"refundReason"` | Could lose or cross-restore the entered reason. |
| `refundDestination` | transaction ID, `"refundDestination"` | Could restore the wrong destination; its initializer uses current category only when state is first created. |
| `showVoidDialog` | transaction ID, `"voidDialog"` | Could reopen/close incorrectly. |
| `voidReasonInput` | transaction ID, `"voidReason"` | Could lose or cross-restore the entered reason. |
| `showChangePaymentDialog` | transaction ID, `"changePaymentDialog"` | Could reopen/close incorrectly. |
| `changePaymentReasonInput` | transaction ID, `"changePaymentReason"` | Could lose or cross-restore the entered reason. |
| `changePaymentDestination` | transaction ID, `"changePaymentDestination"` | Could restore a stale/wrong destination; initializer derives the opposite of the current category only on first creation. |

Even if the same explicit keys are copied into a child, moving the `rememberSaveable` calls changes their composition location and can change restoration behavior. The safest design is to leave every call in the existing parent in the same order and pass controlled values down.

### Dialog lifecycle risks

- All four dialog visibility flags are parent-owned. If a child is conditionally omitted, moved outside the existing Card/Column, or keyed differently, an open dialog may disappear or reappear unexpectedly.
- Preserve the existing `if (show…Dialog.value)` conditions and their close behavior. Do not add a second visibility state inside a dialog child.
- Preserve the current in-progress dismissal differences: correction, void, and payment-change dismiss requests are guarded by `!actionInProgress`; refund dismissal currently closes without that guard. Do not “standardize” them during extraction.
- Preserve confirm ordering: invoke the caller callback with the current form values, then close the dialog, exactly as the current code does.
- Preserve enabled-state rules on confirm/dismiss buttons and fields. In particular, the refund amount nonblank check, payment destination inequality check, and action-in-progress checks must not drift.
- A parent state flag may still be true while the child is not composed due to a changed parent conditional. Keep the action section present under the same conditions as today (after the timezone guard).

### UI/localization and layout risks

- Keep all existing string resource IDs, hard-coded display strings, formatting arguments, punctuation, and ordering unchanged in this refactor.
- Preserve RTL behavior by keeping Compose layout primitives and directional behavior intact; do not introduce absolute left/right padding or alignment.
- Preserve Card nesting, padding, spacing, button weights, field order, scroll behavior, text colors, and semantics.
- Do not move a child across a composition boundary that changes focus, text-field state, dialog window ownership, or accessibility traversal without explicit testing.

### Financial/security boundary risks

- Do not move `details.actionEligibility` resolution or permission flags into the child.
- Do not move ViewModel/repository/DAO access into UI.
- Do not alter action callbacks, their argument values/order, or the distinction between refund-to-coupon and ordinary refund.
- Do not change timeline data construction, event ordering, stable IDs, timestamp formatting, or timezone validation.
- No database, migration, financial model, sync, authentication, permission, or audit-evidence change is allowed.

---

## 5. Verification plan (after implementation, in this order)

No Gradle, device, or emulator commands are part of this documentation task. The following checks are for the later implementation step.

### A. Compile first

Run:

```bash
./gradlew :app:compileDebugKotlin
```

On Windows, use `.\gradlew.bat :app:compileDebugKotlin`. Do not proceed to tests or device checks until compilation passes. Fix only compile errors introduced by the current extraction step, then recompile.

### B. Unit tests second

Run:

```bash
./gradlew :app:testDebugUnitTest
```

On Windows, use `.\gradlew.bat :app:testDebugUnitTest`. Run the full unit-test task, not only selected classes. At minimum confirm existing relevant coverage remains green:

- `app/src/test/java/com/dailymoneyregister/TransactionDetailsUiLogicTest.kt` — timezone validation and deterministic timeline ordering.
- `app/src/test/java/com/dailymoneyregister/feature/register/ui/TransactionLifecycleActionsViewModelTest.kt` — lifecycle action/ViewModel behavior.

No dedicated `TransactionDetailsScreen` Compose UI test was identified in the earlier proposal's search; confirm the current test inventory before relying on that statement. Do not weaken or rewrite financial assertions to accommodate a UI extraction.

### C. Phone checks third

Use a real phone with the debug build installed from the exact tested commit. Navigate **Register → History → select a transaction → Transaction Details**.

Check these screens and scenarios:

1. **Entry, scrolling, and fallback**
   - Back button returns to History.
   - Page title, scroll behavior, spacing, and action placement match the pre-split screen.
   - Exercise a valid branch timezone and the unavailable/invalid-timezone fallback; fallback shows Back and the timezone-unavailable message only.
2. **Summary and audit disclosure**
   - Cash sale, online sale, credit sale, and a transaction with lifecycle events.
   - Expand/collapse audit details; verify transaction ID, original/current amounts, returned/net amounts, status, original/current payment labels, and timestamp.
   - Navigate away/back and recreate the process if practical; confirm disclosure state restoration is unchanged.
3. **Items**
   - Transaction with multiple items, long item names, and quantity/amount values.
   - Transaction with no items; verify the empty-state message and spacing.
4. **Timeline**
   - Original sale only.
   - Credit repayment allocations (including more than one allocation if available).
   - Amount correction, payment-method change, partial/full refund, void, and settlement history where available.
   - Confirm chronological ordering, stable display, actor/reason/timestamp details, and timezone formatting. Include equal or near-equal timestamps if a suitable fixture exists.
5. **Correction dialog**
   - Open, type amount/reason, cancel, reopen, confirm.
   - Verify blank amount remains disabled; verify in-progress field/confirm/dismiss behavior.
6. **Refund dialog**
   - Ordinary cash/online sale refund.
   - Unpaid credit return (debt reduction/no payout path).
   - Repaid credit return and destination selection, including refund-to-coupon.
   - Verify amount, reason, destination, cancel, confirm, and current dismissal behavior while an action is in progress.
7. **Void dialog**
   - Eligible transaction and a transaction with a void-block reason.
   - Verify reason, confirm/cancel, and in-progress dismissal behavior.
8. **Payment-method dialog**
   - Cash-to-online and online-to-cash choices.
   - Verify current destination cannot be selected as a change, reason entry, confirm enablement, cancel, and in-progress behavior.
9. **External action feedback**
   - Verify caller-supplied `actionError` and `actionInfo` render in the same location and colors.
   - Verify action buttons remain disabled/enabled according to the supplied permission flags, financial eligibility, and in-progress state.

### D. Arabic and Urdu RTL checks

Repeat the key screen, summary/audit, items, timeline, and all four dialog checks with the app language set to **Arabic** and then **Urdu**.

- Confirm RTL ordering/alignment, title and Back affordance, Card layout, button order/weights, text fields, dialog actions, and scroll behavior.
- Check long translated labels and reason text for clipping/overlap.
- Confirm amount/currency formatting and timestamps remain consistent with existing locale behavior.
- Check keyboard/focus traversal, accessibility labels/reading order, and text scaling.
- Do not introduce language-specific hard-coded layout direction or change resource strings in this refactor.

### E. Diff/scope check last

- Review the exact changed-file list; only the intended transaction-details UI Kotlin files should change in the implementation step.
- Confirm `TransactionDetailsScreen` public signature and call site remain unchanged.
- Confirm all 13 `rememberSaveable` declarations, key arguments, order, and parent ownership remain unchanged.
- Compare visible text/resource references, formatting arguments, conditional branches, callback arguments, and dialog enable/dismiss behavior against the pre-split source.
- Confirm no financial/data/ViewModel/repository/security behavior or dependency changes.
- Run `git diff --check`; inspect the complete diff; stage explicit file paths only.

---

## 6. Recommended implementation order — least to most risky

Every implementation step below is a separate small change. After each step, run `:app:compileDebugKotlin` and do not start the next step until it passes. Keep the parent state declarations untouched throughout.

| Order | Extraction step | Risk | Why this order; compile-preserving boundary |
|---:|---|---|---|
| 1 | Extract the title/back row to `TransactionDetailsTopBar` in `TransactionDetailsHeader.kt`. | Low | It has no transaction state and only receives `onBack`. Replace the inline row with one call; compile before continuing. |
| 2 | Extract the items heading, empty state, and item-card loop to `TransactionDetailsItems` in the existing `TransactionDetailsItems.kt`. | Low | It is read-only and consumes only `details.items` and `currency`. Reuse the existing `TransactionItemCard`; do not move or rewrite its implementation in the same step. Compile. |
| 3 | Extract activity heading, timeline assembly, sort, and card dispatch to `TransactionDetailsTimelineSection` in the existing `TransactionDetailsTimeline.kt`. | Low–medium | It is read-only but has a repayment join and ordering/stable-ID behavior. Move the complete section as-is; keep the current timeline model/cards and timezone argument. Compile. |
| 4 | Extract summary and audit disclosure to `TransactionDetailsSummary` in `TransactionDetailsSummary.kt` (or the header file if deliberately consolidating). | Medium | It reads financial projection fields and toggles one saveable disclosure flag. Keep the flag in the parent; pass controlled value/change callback. Preserve the exact summary Card and audit layout. Compile. |
| 5 | Extract action buttons, void-block explanation, and action error/info messages to an `TransactionDetailsActionControls` child in `TransactionDetailsActions.kt`, leaving all four dialogs inline temporarily. | Medium | This separates stateless controls from dialog lifecycle first. Pass precomputed `can…` flags and state-change callbacks; preserve reset-before-open behavior. Compile. |
| 6 | Extract the correction dialog, then compile; extract the refund dialog, then compile; extract the void dialog, then compile; extract the payment-change dialog, then compile. Keep each dialog's parent-owned flag and input values. | High | Dialogs have the densest state and callback coupling. Move one dialog at a time, preserving visibility condition, callback argument order, close timing, enabled rules, and distinct dismiss behavior. Compile after each dialog, not only after all four. |
| 7 | Once the children are stable, reduce `TransactionDetailsScreen` to timezone guard, saveable-state/derived-state orchestration, and the single scroll container that calls the extracted children in the same order. | Highest | This is the final integration step. It changes the parent body structure and composition boundaries, so verify every saveable call remains in the same parent, in the same order, with identical keys. Compile and then complete the full verification plan in Section 5. |

**Do not combine steps to save commits.** Each intermediate state must compile. If an extraction cannot compile without moving a saveable state, changing a callback, or altering a financial/timeline rule, stop and redesign that step rather than weakening the invariant.

---

## Completion boundary

This document is a plan only. No Kotlin implementation, tests, resources, Gradle files, database files, or workflows are changed by this document. The next implementation step requires separate explicit approval.