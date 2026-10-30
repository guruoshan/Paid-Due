# CustomerDetailScreen Decomposition Plan

## Scope and baseline

- Repository: `guruoshan/Paid-Due`
- Base branch: `main`
- Base commit: `00c599d2458b391a68b98c37ca94a043aae380c2`
- Working branch: `refactor/customer-detail-screen-decomposition`
- Target: `app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomerDetailScreen.kt`
- Baseline size: **1,661 lines** (including imports, comments, blank lines)
- Baseline file blob: `a8e1994d725bdf941d49f0a892a84fb685722b7a`

This document records analysis and a proposed extraction sequence only. No Kotlin source, navigation, ViewModel, repository, database, or sync code is changed in this phase.

## Screen structure map

| Area | Current lines | Responsibility |
|---|---:|---|
| File-local display model and action contract | 61–83 | `CustomerPaymentDisplayRow`; public `CustomerDetailScreenActions` callback contract |
| Screen setup and presentation state | 85–132 | `CustomerDetailScreen`; customer/context lookup; selected tab; WhatsApp choice/preview; message-party helper; tab labels |
| Scaffold and customer header | 134–168 | Top app bar, customer name/phone, back callback |
| Loading/error/not-found states | 177–194 | Progress indicator, retry action, not-found message |
| Tab navigation | 206–226 | Five tabs; selected index is local Compose state |
| Transactions tab | 241–400 | Read-only transaction list, financial-kind/payment-category titles, lifecycle labels, timestamps and formatted net amounts |
| Coupons tab | 401–601 | Coupon wallet/balance, recent ledger activity, activity labels and authorized-user display; WhatsApp action entry |
| Credit tab | 602–871 | Outstanding credit, repayment entry action, repayment status, credit history and receipt/WhatsApp actions |
| Documents/invoices tab | 872–942 | Invoice count, empty state, keyed invoice list and invoice selection |
| Payments tab | 943–1067 | Display projection combining expenses and credit repayments, newest-first ordering, Money In/Out display |
| Receive-payment dialog host | 1068–1085 | Conditional presentation of `CreditReceivePaymentDialog` and forwarding of repayment callbacks |
| WhatsApp and invoice dialogs | 1086–1424 | Credit message options, coupon message options, exact-message preview/send, invoice share options |
| Existing leaf composables | 1425–1661 | `InvoiceHistoryRow`; `CreditReceivePaymentDialog` |

### Largest UI sections

1. **Credit tab — lines 602–871 (270 lines):** largest tab; contains the outstanding balance display, receive-payment entry point, repayment status, credit history, and contextual actions.
2. **Coupons tab — lines 401–601 (201 lines):** wallet state, balance, ledger history, activity-kind labels, authorized-user presentation, and WhatsApp entry point.
3. **Transactions tab — lines 241–400 (160 lines):** read-only financial transaction presentation with lifecycle labels and amount formatting.
4. **Payments tab — lines 943–1067 (125 lines):** combines two existing read projections and renders Money In/Out rows. Its row-building logic is presentation mapping, but amount sign and ordering must remain byte-for-byte equivalent in behavior.
5. **CreditReceivePaymentDialog — lines 1472–1661 (190 lines):** already a separate private composable in the same file; a natural move candidate, but it is payment-sensitive and must retain all existing enablement and dismissal guards.

The line ranges are baseline anchors, not promises about post-extraction line numbers.

## Dialog inventory

There are **five AlertDialog instances** in the current screen:

| Dialog | Current lines | Open/close behavior and important constraints |
|---|---:|---|
| Credit receive-payment dialog | 1472–1661 (rendered at 1068–1085) | Visibility comes from `state.isReceivePaymentOpen`; dismiss and controls are guarded while submitting; callbacks are forwarded to the existing ViewModel |
| Credit WhatsApp options | 1086–1174 | Local `whatsappChoice == "CREDIT"`; composes credit-balance/payment-reminder previews using existing state and formatting |
| Coupon WhatsApp options | 1175–1308 | Local `whatsappChoice == "COUPON"`; uses current wallet and latest purchase data to compose preview text |
| WhatsApp message preview | 1309–1374 | Displays the exact prepared message and sends that same text to the selected customer's canonical phone |
| Invoice share options | 1375–1424 | Driven by `state.selectedInvoice`; forwards PDF/WhatsApp share callbacks and preserves preparation/error/close guards |

The first dialog is implemented by the existing `CreditReceivePaymentDialog` helper; the other four are inline in `CustomerDetailScreen`.

## Bottom-sheet inventory

- **No bottom sheets found** in this file.
- No `ModalBottomSheet` or `BottomSheetScaffold` usage is present.
- Do not introduce a sheet or replace any dialog as part of this refactor.

## Section inventory

- Customer identity header: TopAppBar title and optional canonical phone.
- Screen-state handling: loading, error/retry, and not-found.
- Five tabs: Transactions, Coupons, Credit, Documents, Payments.
- Transaction history: lifecycle-resolved financial transaction rows.
- Coupon wallet: balance, recent ledger activity, authorized-user presentation.
- Credit account: outstanding balance, credit history, receive-payment entry.
- Invoice history: keyed invoice rows; selecting a row opens share options.
- Payment relationship history: expenses (Money Out) and credit repayments (Money In).
- Dialog/communication layer: receive payment, WhatsApp choice/preview, invoice sharing.

There is no standalone customer-summary card/component in the current source. The coupon balance and credit outstanding summary are embedded in their respective tabs. A future `CustomerSummary.kt` should therefore be limited to extracting those existing read-only displays if a clean boundary is confirmed; it must not invent a new overview or aggregate financial values.

## Navigation entry points

Navigation is outside the target file and is currently wired in:

- `app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt`, around lines 509–535.
- Route: `Routes.CUSTOMER_DETAIL`.
- Route argument: `customerId`, typed as `NavType.StringType`.
- The route obtains `CustomerDetailViewModel` through `hiltViewModel()`, collects `uiState` with `collectAsStateWithLifecycle()`, and passes state plus `CustomerDetailScreenActions` to the screen.
- Back navigation is `navController.popBackStack()`.

**Constraint:** do not change the route, route argument, route-scoped ViewModel acquisition, back-stack behavior, or the screen's public function signature.

## ViewModel interaction inventory

`CustomerDetailScreen` does not obtain or call a ViewModel directly. It receives immutable UI state and an action bundle. The 11 existing callbacks are wired in `DmrApp.kt`:

| Screen action | Current ViewModel target |
|---|---|
| `onNavigateBack` | `navController.popBackStack()` (not a ViewModel call) |
| `onLoad` | `CustomerDetailViewModel.load` |
| `onOpenReceivePayment` | `openReceivePayment` |
| `onOpenInvoice` | `openInvoice` |
| `onRepaymentAmountChange` | `onRepaymentAmountChange` |
| `onSelectRepaymentMethod` | `selectRepaymentMethod` |
| `onConfirmReceivePayment` | `confirmReceivePayment` |
| `onCloseReceivePayment` | `closeReceivePayment` |
| `onCloseInvoice` | `closeInvoice` |
| `onSharePdf` | `sharePdf` |
| `onShareWhatsApp` | `shareWhatsApp` |

Important boundaries observed in the current ViewModel:

- Repayment input validation, amount parsing, submission state, and command creation live in `CustomerDetailViewModel`.
- The financial mutation path is `RegisterRepository.executeCreditRepayment(command)`, called by `confirmReceivePayment`.
- Invoice selection and PDF/WhatsApp sharing are owned by the ViewModel; share rendering uses the selected invoice's frozen snapshot.
- The screen must only forward the existing callbacks. No ViewModel methods, repository calls, payment commands, or state contracts should move into presentation components.

## Presentation-only local state and sensitive logic boundaries

The screen currently owns three pieces of local Compose state:

- `selectedTab`: selected tab index, initialized to zero.
- `whatsappChoice`: current local credit/coupon message-options selection.
- `whatsappPreview`: exact prepared WhatsApp message shown before sending.

It also defines `messageParty()`, which validates customer/business/phone availability and creates the message-composer party. Message composition, timestamps, exact preview text, recipient selection, and `WhatsAppTransport.share` are behavior-bearing orchestration and should remain in the host during the first extraction pass.

Other logic to preserve exactly:

- Transaction title and lifecycle-label mapping.
- Coupon ledger activity-kind mapping and the selected recent-activity window.
- Credit-history labels and repayment receipt-party handling.
- Payments tab's expense/repayment projection, absolute amount display, Money In/Out sign, and newest-first ordering.
- Invoice keys, row-click callback, share-preparation state, and error/dismissal guards.
- Existing string resources, colors, typography, spacing, and empty states.

## Proposed extraction plan

Use the requested `customerdetail/components/` package under the existing customer UI package. Extract leaf presentation first; keep state ownership and orchestration in `CustomerDetailScreen.kt`.

| Candidate file | Proposed responsibility | Boundary / risk |
|---|---|---|
| `CustomerHeader.kt` | Existing TopAppBar customer name/phone/back presentation; optionally a stateless tab-strip composable if this reduces host noise | Low–medium. Pass the existing customer display values and back callback. Keep selected-tab state and tab selection in the host. No navigation calls inside the component. |
| `CustomerSummary.kt` | Existing read-only coupon-balance and credit-outstanding display fragments only, if a stable shared presentation boundary exists | Medium. There is no current standalone summary section. Do not introduce a new aggregate, recompute totals, change currency/decimal handling, or alter balance coloring. Defer if extraction would duplicate logic or create a new visual grouping. |
| `CustomerInvoiceHistory.kt` | Documents tab heading/count/empty state/LazyColumn and the existing invoice row presentation | Low–medium. Preserve `documentId` keys, ordering, row click forwarding, and exact invoice model values. Keep invoice selection and share dialog state in the host/ViewModel. |
| `CustomerPaymentSection.kt` | Render already-prepared payment display rows | Medium–high. First pass should receive prepared rows and formatting inputs; keep the current expense/repayment row construction, `MoneyMinorUnits(...).abs()`, sign selection, and sorting in the host until separately proven safe. Do not introduce financial calculations. |
| `CustomerActionButtons.kt` | Stateless rendering of existing coupon/credit action buttons with explicit enabled flags and callbacks | Medium–high. Buttons are interleaved with credit/coupon sections and local WhatsApp state. Keep all state transitions, message composition, permission/availability conditions, and ViewModel calls in the host; pass exact existing booleans and callbacks. |

Additional safe leaf candidates after the named files are mapped:

- Move `InvoiceHistoryRow` with invoice history, preserving its click surface and money/date formatting.
- Consider a transaction-row composable only if its title/lifecycle mapping remains in the host and the extracted component receives the already-resolved display values.
- Move `CreditReceivePaymentDialog` to a separate component file only after a focused comparison confirms every field, keyboard option, enabled condition, submitting guard, and callback remains unchanged. It is already a standalone composable, so moving it alone reduces file size but not orchestration complexity.

### Recommended extraction order

1. `CustomerHeader.kt` — isolate the TopAppBar while retaining tab state and navigation callback ownership in the host.
2. `CustomerInvoiceHistory.kt` — extract the self-contained invoice list and existing row.
3. `CustomerPaymentSection.kt` — extract rendering only, leaving row projection and money-sign preparation in the host.
4. `CustomerSummary.kt` — proceed only if the existing coupon/credit summary fragments have a clean, non-duplicative boundary.
5. `CustomerActionButtons.kt` — proceed last; pass callbacks and exact enabled flags, with no state or message orchestration moved.
6. Dialog file moves can be a separate, independently reviewable pass.

This order prioritizes presentation-only seams and deliberately postpones the most behavior-sensitive action and payment sections.

## Risks

| Risk | Why it matters | Required protection |
|---|---|---|
| Financial display drift | Payment rows combine expense and repayment evidence and display signed amounts | Keep the current projection, absolute-value conversion, sign, currency, and sort order unchanged; compare representative empty, expense-only, repayment-only, and mixed cases |
| Credit repayment behavior drift | Dialog enablement/dismissal and action wiring gate a financial command | Do not move command validation or mutation; preserve every submitting guard and callback identity |
| Coupon history drift | Activity labels and recent-event slicing are derived from authoritative wallet/ledger projections | Preserve exact activity-kind mapping, reversal/take order, and display values |
| WhatsApp message drift | Previewed text must be the exact text sent to the canonical phone | Keep message-party validation, composers, timestamps, preview state, and transport call in the host |
| Invoice sharing drift | Selection and preparation/error state are ViewModel-owned | Preserve invoice keys and callback forwarding; do not move rendering/sharing calls |
| Compose state/lifecycle drift | Tab and WhatsApp states are local to the screen | Keep state owners and initialization unchanged; do not key/recreate state differently |
| Navigation/contract drift | The route and action bundle are the integration boundary | Keep `CustomerDetailScreen`, `CustomerDetailScreenActions`, and route wiring signatures unchanged |
| Resource/UI drift | Text, colors, layout, accessibility and enablement are observable behavior | No resource edits or visual redesign; compare all labels/modifiers/semantics |

## Validation strategy for the later implementation phase

### Before extraction

- Reconfirm the working branch is based on the stated main SHA, and re-read the target file and relevant tests.
- Locate existing Customer Detail UI/unit/instrumentation tests and identify the test command(s) that cover the route, invoice list, WhatsApp preview, and receive-payment dialog.
- Record baseline line counts and the exact current callback/state contract.

### After each extraction

- Inspect the complete diff; only the target screen, new presentation component files, and this refactor's documentation should change.
- Verify no edits to `DmrApp.kt`, `CustomersViewModel.kt`, `CustomerRepository`, `RegisterRepository`, database, sync, financial command, or navigation files.
- Verify public `CustomerDetailScreen` and `CustomerDetailScreenActions` signatures remain unchanged.
- Compare callback forwarding, conditional branches, list keys/order, enabled flags, dismissal guards, money formatting inputs, and string resources against the baseline.
- Confirm local state remains owned by the same screen and no new state is introduced in extracted components.
- Run focused Kotlin compilation and the relevant customer tests, then the normal Android unit-test/lint/PR CI gates.
- Perform a visual smoke check of all five tabs and all five dialogs where a suitable test/device environment is available. Do not use device-data-risking tests without explicit approval.

### Acceptance criteria

- Meaningful reduction in the host's presentation markup without moving business rules or financial logic.
- No change to navigation, ViewModel/repository contracts, database, sync, payment processing, or financial calculations.
- All five tabs, all five dialogs, empty/loading/error states, and callback behavior remain equivalent.
- Focused tests and applicable CI gates pass.
- Final diff remains isolated and reversible.

## Phase 1 conclusion

The screen has clear presentation seams, especially the header, invoice history, and payment-row rendering. The credit and coupon tabs and the WhatsApp/dialog orchestration are more coupled and should be extracted only after the low-risk leaves. No Kotlin source changes are authorized or made in this analysis/plan phase.
