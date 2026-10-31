# CouponCompactDialogR7A Decomposition Plan

## Baseline

- Repository: `guruoshan/Paid-Due`
- Base branch: `main`
- Audited base commit: `a7d7507a91c272e8c3aa5a01eab427117e403930`
- Source: `app/src/main/java/com/dailymoneyregister/feature/register/ui/CouponCompactDialogR7A.kt`
- Baseline size: **1,898 lines**
- Actions contract: `CouponCompactDialogActions`, 11 callbacks.
- The dialog is one Compose `Dialog` with a single modal surface and mutually exclusive success / active-form content.

## Dialog and section inventory

| Section | Approx. source lines | Responsibility |
|---|---:|---|
| Dialog / surface / root layout | 286–318 | Dismiss policy, modal properties, surface shape, spacing and scroll container |
| Header | 319–369 | Mode-dependent title and Cancel action |
| Error message | 371–378 | Displays host-provided error text |
| Success summary | 381–514 | Displays refund, redemption, purchase or top-up result references and amounts |
| Success WhatsApp / receipt share | 515–714 | Builds coupon event message, receipt image fallback, optional receipt-details callback and WhatsApp share |
| Success completion action | 715–735 | Starts a new sale after redemption or dismisses the dialog |
| Customer search | 754–914 | Search mode, phone/name input, validation feedback and search callback |
| Customer results | 915–1055 | Displays matching customers and selects a result; opens wallet details |
| New customer | 1056–1130 | New-customer form and local input handling |
| Selected customer | 1131–1234 | Customer identity, wallet balance and change-customer action |
| Selected wallet WhatsApp | 1235–1293 | Shares the current wallet balance |
| Money controls | 1295–1335 | Amount input and existing amount presentation |
| Authorized users | 1336–1403 | Displays active wallet users and selection state |
| Existing-wallet add-user | 1404–1483 | Adds/deactivates authorized users through the existing callback |
| New-wallet authorized users | 1484–1618 | Local list editing for additional users on a new wallet |
| Redemption balance / top-up switch | 1619–1675 | Shows remaining amount and switches redemption into top-up mode |
| Funding | 1676–approx. 1750 | Existing funding-category selection for the applicable flow |
| QR display | approx. 1751–1797 | Displays configured online QR or unavailable message |
| Main confirmation action | 1798–1883 | Routes the existing confirmation callback to refund, redemption, top-up or sale |
| Date formatting helper | 1893–1898 | Formats event timestamp for coupon WhatsApp text |

Line ranges are approximate section boundaries and may shift during extraction; they describe the audited base, not a proposed behavior change.

## State inventory

### Locally remembered mutable state (14)

- `actionIsRedemption` — current redemption versus purchase/top-up mode.
- `searchByPhone`, `searchName`, `searchPhone`, `searched` — customer-search controls and feedback.
- `customer`, `createCustomer`, `customerName`, `customerPhone` — selected/new customer form state.
- `amount`, `funding` — amount text and selected funding category.
- `selectedUserId` — selected authorized wallet user.
- `newUserName`, `additionalUsersText` — local authorized-user entry/list state.

### Derived state and host-provided state

- Derived mode/lock/result flags: `refundMode`, `locked`, `pending`, `parsed`, `finished`, `intentValid`, `onlineReady`, `confirmEnabled`.
- Derived phone/search/customer/wallet values: normalized search/customer phone, selected customer result, selected wallet, selected balance, existing-wallet flag, wallet details, active users, parsed additional users, redemption remainder.
- Read-only `RegisterUiState` inputs referenced by this dialog: `couponCustomers`, `couponRedemptionResult`, `couponSaleResult`, `couponSearching`, `couponTopUpResult`, `couponWalletDetails`, `couponWalletLoading`, `draftState`, `errorMessage`, `inFlightCouponRedemptionCommand`, `inFlightCouponSaleCommand`, `inFlightCouponTopUpCommand`, `isExecuting`, `onlineQr`, `refundToCouponDraft`, `refundToCouponResult`.

No state ownership or state lifetime should move to extracted components in Pass 1.

## Callback inventory

`CouponCompactDialogActions` exposes exactly 11 callbacks:

1. `onDismiss`
2. `onStartNewSale`
3. `onSearchCustomers(String)`
4. `onOpenWallet(String, Boolean)`
5. `onCloseWallet`
6. `onSaveUser(String?, String, Boolean)`
7. `onConfirmRefund(CustomerEntity?, String, String, List<String>)`
8. `onConfirmRedemption(CustomerEntity?, String?, String, PaymentCategory?)`
9. `onConfirmTopUp(CustomerEntity, String, String, PaymentCategory)`
10. `onConfirmSale(CustomerEntity?, String, String, String, PaymentCategory, List<String>)`
11. `onLoadReceiptDetails(String, callback)`

All callback declarations and call sites remain in the host during Pass 1. The extracted composables may receive narrowly scoped presentation callbacks, but they must not own orchestration or create new ViewModel-facing contracts.

## ViewModel interaction inventory

- The composable has no direct ViewModel reference and performs no direct repository call.
- Its external interaction boundary is the supplied `CouponCompactDialogActions`.
- The action implementation and ViewModel wiring live outside this file and are out of scope.
- Preserve all 11 action signatures, all call-site arguments, and all call ordering.

## Payment-flow inventory

- Refund-to-coupon: the main action calls `onConfirmRefund` with selected/new customer data and additional users.
- Coupon redemption: the main action calls `onConfirmRedemption` with customer, selected authorized user, amount text and funding category.
- Wallet top-up: the main action calls `onConfirmTopUp` with the selected customer, wallet ID, amount text and effective funding category.
- Coupon purchase: the main action calls `onConfirmSale` with customer data, amount text, effective funding category and additional users.
- Funding chips and online QR are conditional presentation/input controls for the existing flows.
- Success receipt and WhatsApp handling is a separate post-success flow; it is not payment processing and must remain untouched.

## Coupon-flow inventory

- Customer search and result selection, wallet detail open/close, authorized-user selection/management, and customer creation are existing dialog interactions.
- Purchase, top-up and redemption confirmations remain delegated to existing callbacks.
- Refund-to-coupon remains delegated to its existing callback.
- The redemption remainder and top-up mode switch are existing UI/state behavior; do not change calculations or transitions.
- Result summaries, receipt generation, WhatsApp composition/sharing and success completion remain in the host.

## Largest UI sections

1. Success and receipt/WhatsApp handling (approx. lines 381–735): large, but deliberately excluded because it contains receipt generation, message composition, async receipt-detail loading and fallback behavior.
2. Customer search/results/new-customer UI (approx. lines 754–1130): sizeable, but tightly coupled to remembered state and wallet-opening behavior; defer.
3. Selected customer plus wallet WhatsApp (approx. lines 1131–1293): contains customer state reset and WhatsApp composition; only the display card is a possible narrow extraction.
4. Authorized users / funding / QR / main action (approx. lines 1336–1883): coupled to local editing, payment inputs, calculations and confirmation routing; defer.

## Pass 1 extraction decision

### Approved narrow candidates

1. **CouponHeader** — extract the existing title and Cancel row only. The host continues to compute the same title text and passes it plus the existing dismiss callback and enabled state. Error display stays in the host.
2. **CouponSelectedCustomerCard** — extract only the selected customer's identity and conditional wallet-balance card. The host retains the existing change-customer button and every state reset/action that it currently performs. The card receives already-derived display values only.

These are presentation-only boundaries with no calculations, state ownership, ViewModel calls, repository calls, payment handling, or coupon orchestration moved.

### Explicitly deferred

- Success/result summary and all receipt/WhatsApp handling.
- Customer search, results, new-customer form and wallet open/close orchestration.
- Amount input, authorized-user editing/selection, redemption remainder, top-up switching, funding chips, QR and main confirmation button.
- Any proposed component that would require moving validation, calculations, state transitions or callback orchestration.

## Validation plan

- Keep `CouponCompactDialogR7A(state, decimalPlaces, startMode, currencyCode, currencySymbol, merchantCountryCode, businessName, actions)` unchanged.
- Preserve `CouponCompactDialogActions` with exactly 11 callbacks and unchanged signatures.
- Compare all action call sites and arguments before/after.
- Confirm payment, coupon purchase, top-up, redemption, refund, receipt, WhatsApp and ViewModel wiring remain in the host and unchanged.
- Run `:app:assembleDebug` (CI or a documented local build).
- Report exact before/after line counts and extraction counts in `coupon-compact-dialog-pass1.md`.
- No database, sync, repository, ViewModel, navigation, resource, calculation or business-rule changes.
