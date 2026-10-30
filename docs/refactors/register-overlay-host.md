# RegisterOverlayHost decomposition

## Scope and baseline

- Repository: `guruoshan/Paid-Due`
- Base: `main` at `f4c78e6c052720e270b88e42ce940335e7715069`
- Branch: `refactor/register-overlay-host-decomposition`
- Source: `app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterOverlayHost.kt`
- Before: 1,092 lines
- After: 799 lines
- Net host reduction: 293 lines (approximately 26.8%)
- Extracted source added in new files: 405 lines

This is a presentation-only decomposition. Overlay gating, state ownership, orchestration, callback execution, calculations, and the outer dialog lifecycle remain in the host.

## Phase 1 — overlay and dialog inventory

### Overlay inventory

The host coordinates these register overlays:

1. Day closing
2. Expense
3. Credit sale
4. Coupon sale / coupon wallet flow
5. Coupon wallet
6. Itemized bill
7. Add item
8. Tax
9. Shipping / packing
10. Cash customer
11. Online payment

`InvoiceFlow` is also rendered by the host independently of the overlay checks.

### Dialog inventory

- Day closing: inline Material 3 `AlertDialog`
- Expense: `ExpenseDialog` in the existing expense presentation file
- Credit sale: `CreditSaleDialog` in the existing customer overlay dialogs file
- Coupon sale: `CouponCompactDialogR7A`
- Coupon wallet: `CouponWalletDialog`
- Itemized bill: `ItemizedBillDialog`
- Add item: `AddItemDialog`
- Tax: `TaxDialog`
- Shipping / packing: `ShippingPackingDialog`
- Cash customer: inline Material 3 `AlertDialog`, now delegated to `CashCustomerDialog`
- Online payment: Material `Dialog` containing a `Card`, QR presentation, and customer-entry section

### Bottom sheet inventory

No bottom-sheet composable is created or invoked directly by `RegisterOverlayHost.kt`. The file's modal surfaces are dialogs.

### State sources and ownership

- Main state is supplied as `RegisterUiState`, produced by `RegisterViewModel`.
- Overlay identity is `RegisterOverlay` from `RegisterPresentationState.kt`.
- Invoice state and invoice actions are supplied by `InvoiceViewModel`.
- The host receives currency configuration, business/user records, merchant country code, and the settings-navigation callback as parameters.
- Host-owned remembered state remains in the host: item-name query/suggestions, cash-customer name/phone, and online-payment customer name/phone.
- The item suggestions remain driven by the same `LaunchedEffect(itemNameQuery)` and `viewModel.searchProducts` call.
- The online-payment `BackHandler` and outer `Dialog` remain in the host.

### Dependencies

The host coordinates existing register UI components, `PhoneNumberInput`, `MerchantQrImage`, `InvoiceFlow`, `ReceiptRenderer`, and `ReceiptSharing`. It calls existing `RegisterViewModel` methods through the same callbacks and method references. No repository, ViewModel, navigation, sync, or data-layer source was changed.

### Composable hierarchy

```text
RegisterMainContent
└── RegisterOverlayHost
    ├── Day-closing AlertDialog
    │   ├── DayClosingCashTotals
    │   ├── DayClosingReconciliationSummary
    │   ├── DayClosingResultDetails
    │   ├── DayClosingConfirmActions
    │   └── DayClosingDismissAction
    ├── ExpenseDialog
    ├── CreditSaleDialog
    ├── CouponCompactDialogR7A
    ├── CouponWalletDialog
    ├── ItemizedBillDialog
    ├── InvoiceFlow
    ├── AddItemDialog
    ├── TaxDialog
    ├── ShippingPackingDialog
    ├── CashCustomerDialog
    │   └── AlertDialog
    └── Online-payment Dialog
        └── Card / Column
            ├── QR presentation (remains in host)
            └── OnlinePaymentCustomerSection
```

## Phase 2 — decomposition plan

Natural boundaries selected:

- Day-closing summary rows, reconciliation display, result details, and action-button layout. Money formatting, parsing, reconciliation calculation, receipt construction, and share invocation remain in the host.
- Cash-customer dialog presentation. Its remembered text/phone state and every ViewModel callback remain in the host and are passed as values/callbacks.
- Online-payment customer-entry presentation. Its remembered state, normalized-phone derivation, enablement expression, and ViewModel callback bodies remain in the host. The enclosing `Dialog`, `BackHandler`, QR rendering, and navigation callback remain in the host.

The existing expense/customer/coupon/item/tax/shipping composables were already separate and were not moved or reorganized.

## Phase 3 — extracted composables

| New file | Extracted composables | Responsibility |
|---|---|---|
| `ui/dialogs/DayClosingSections.kt` | `DayClosingCashTotals`, `DayClosingReconciliationSummary`, `DayClosingResultDetails`, `DayClosingConfirmActions`, `DayClosingDismissAction` | Presentation-only day-close rows and action layout |
| `ui/dialogs/CashCustomerDialog.kt` | `CashCustomerDialog` | Controlled cash-customer dialog presentation |
| `ui/dialogs/OnlinePaymentCustomerSection.kt` | `OnlinePaymentCustomerSection` | Controlled online-payment customer-entry presentation |

No state holder was moved into the extracted composables. They receive current values and callbacks from the host.

## Phase 4 — validation

### Source-level checks completed

- [x] The 11 overlay flows and their destination/overlay visibility gates remain in the host.
- [x] The `InvoiceFlow` call remains in the host.
- [x] The six host `remember` declarations remain in the host; count is unchanged.
- [x] The complete `viewModel` method-call/method-reference inventory matches the base file.
- [x] Day-close money-formatting call count remains 11 in the host.
- [x] `parseDayClosingCountedMinor` and `dayClosingReconciliation` remain in the host.
- [x] Receipt construction and `ReceiptSharing.shareDaySummary` remain in the host.
- [x] Compose control counts for `AlertDialog`, `BackHandler`, `Button`, `OutlinedButton`, `TextButton`, `OutlinedTextField`, and `PhoneNumberInput` match the original host plus extracted files.
- [x] No new string resources were introduced.
- [x] No preview existed in the original host; no preview was moved or removed.
- [x] No repository, ViewModel, navigation, sync, financial, database, or migration file was changed.

### Not yet verified

- [ ] Android/Kotlin compilation
- [ ] Unit and instrumentation test suites
- [ ] Runtime smoke test of each dialog path
- [ ] Device-level visual comparison

Source-level parity is not a substitute for compilation or runtime verification. These checks must be completed by CI and/or a local Android build before merge.

## Risks

**Risk: low to moderate, pending compilation and runtime verification.**

The highest-risk areas are conditional dialog composition, callback routing for cash/online customer actions, and day-close share/done controls. The extraction keeps their visibility predicates and callback implementations in the host. Local remembered state remains at its original host call sites. The new files contain presentation only; they do not call repositories, ViewModels, navigation, or financial calculation helpers.

## Behavior verification checklist

- [x] Same overlay visibility conditions
- [x] Same destination checks
- [x] Same host-owned remembered state and effects
- [x] Same ViewModel call inventory
- [x] Same day-close calculation and receipt-building locations
- [x] Same outer online-payment dialog and back handling
- [ ] Compile and tests green
- [ ] Manual smoke test: day close, cash customer, online payment, and all existing delegated dialogs

## Files changed

1. `app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterOverlayHost.kt`
2. `app/src/main/java/com/dailymoneyregister/feature/register/ui/dialogs/DayClosingSections.kt`
3. `app/src/main/java/com/dailymoneyregister/feature/register/ui/dialogs/CashCustomerDialog.kt`
4. `app/src/main/java/com/dailymoneyregister/feature/register/ui/dialogs/OnlinePaymentCustomerSection.kt`
5. `docs/refactors/register-overlay-host.md`

No application behavior or business logic change is intended. Do not merge until CI and runtime checks are complete.
