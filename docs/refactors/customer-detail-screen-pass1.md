# CustomerDetailScreen UI Extraction — Pass 1

## Scope

- Repository: `guruoshan/Paid-Due`
- Base: `main` at `00c599d2458b391a68b98c37ca94a043aae380c2`
- Branch: `refactor/customer-detail-screen-decomposition`
- Target: `app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomerDetailScreen.kt`
- Scope limited to the customer header and invoice-history presentation.
- No payment, WhatsApp, invoice-sharing workflow, ViewModel, repository, navigation, database, sync, or business-rule code was intentionally changed.

## Files changed

1. `app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomerDetailScreen.kt`
2. `app/src/main/java/com/dailymoneyregister/feature/customers/ui/customerdetail/components/CustomerHeader.kt` (new)
3. `app/src/main/java/com/dailymoneyregister/feature/customers/ui/customerdetail/components/CustomerInvoiceHistory.kt` (new)
4. `docs/refactors/customer-detail-screen-pass1.md` (this report)

The Phase 2 planning document remains unchanged.

## Line-count results

| Measure | Lines |
|---|---:|
| Before: `CustomerDetailScreen.kt` at base | 1,661 |
| After: `CustomerDetailScreen.kt` after Pass 1 | 1,526 |
| Net host reduction | **135 (8.1%)** |
| Original header presentation moved | 36 |
| Original invoice-tab presentation moved | 71 |
| Existing invoice-row composable moved | 39 |
| Total original presentation lines relocated | **146** |

The net reduction differs from the relocated line count because the host retains small parameterized component call sites and required imports. The new component files include their own package declarations and imports.

## Extractions

### `CustomerHeader.kt`

- Extracts the existing `TopAppBar`, customer title/phone display, back icon, content description, and app-bar colors.
- Receives `title`, optional `phone`, and `onNavigateBack` as parameters.
- The host continues to resolve the exact existing fallback title and nonblank-phone condition.
- The callback remains `actions.onNavigateBack`; the component performs no navigation itself.

### `CustomerInvoiceHistory.kt`

- Extracts the existing Documents tab heading/count, empty state, invoice `LazyColumn`, and `InvoiceHistoryRow`.
- Receives the existing `state.invoices` list and `actions.onOpenInvoice` callback as parameters.
- Retains the `documentId` lazy-list key, source order, singular/plural invoice label, empty-state resource, date and money formatting helpers, unavailable-amount fallback, card shape, and row click behavior.
- Is declared as a `ColumnScope` composable so the original `LazyColumn.weight(1f)` remains scoped to the same outer screen column. No new state is introduced.

## Validation results

### Source-level checks

| Check | Result |
|---|---|
| Public `CustomerDetailScreen(state, actions)` signature | Unchanged |
| `CustomerDetailScreenActions` callback inventory | Same 11 callbacks, same names and types |
| Navigation calls in the screen | None before or after; back remains a supplied callback |
| `DmrApp.kt` route and ViewModel wiring | Not modified |
| CustomerDetailViewModel | Not modified |
| Repository, database, sync, payment processing | Not modified |
| WhatsApp state, composition, preview and transport | Not extracted or modified |
| Invoice selection/sharing state and callbacks | Not extracted or modified |
| Customer tabs and selected-tab state | Remain in the host |
| Invoice list key and click callback | Preserved |
| Invoice count, empty state and row display fields | Preserved |
| String resources | No resource changes |

The existing route still supplies the same callbacks from `CustomerDetailViewModel`: `load`, `openReceivePayment`, `openInvoice`, `onRepaymentAmountChange`, `selectRepaymentMethod`, `confirmReceivePayment`, `closeReceivePayment`, `closeInvoice`, `sharePdf`, and `shareWhatsApp`. Back navigation remains the route's `navController.popBackStack()` callback. The route file was not changed.

### Build

- Required command: `./gradlew :app:assembleDebug` (Windows equivalent: `gradlew.bat :app:assembleDebug`).
- Status: **Pending execution/CI confirmation at report creation.**
- A successful source-level review is not a substitute for the Android build. Update this section with the exact run/commit result before treating the pass as build-verified.

## Risk assessment

**Current refactor risk: low to moderate.**

- Header extraction is low risk: it is stateless and receives the same display values and back callback.
- Invoice-history extraction is low-to-moderate risk because it contains a keyed lazy list and a `weight(1f)` modifier. Keeping the function receiver as `ColumnScope` preserves the original parent scope rather than introducing an additional layout container.
- No payment calculations, repayment command handling, WhatsApp workflow, invoice share preparation, or navigation ownership moved.
- Runtime visual equivalence is not established by static inspection alone. The Documents tab and customer header should still be included in a device/emulator smoke check.

## Conclusion

Pass 1 extracts only the customer header and invoice-history UI. The host is reduced by 135 lines (8.1%), while the callback contract, route wiring, ViewModel interactions, and sensitive workflows remain in their original locations. Do not merge until the Android build and the relevant visual smoke check have been completed.
