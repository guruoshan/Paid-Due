# Money Rules — read-only source audit

Scope: current `main` source only. This document records behavior evidenced in code; it is not a recommendation or a fix. If a calculation or test could not be located in the inspected source, it is marked **NOT FOUND** rather than inferred.

## 1. Operation and event names

### Application operation types

The canonical Kotlin `OperationType` enum defines these 26 values: `CREATE_BUSINESS`, `CREATE_BRANCH`, `CREATE_ADMIN`, `CREATE_CASHIER`, `UPDATE_USER_LANGUAGE`, `UPDATE_BUSINESS_PROFILE`, `UPDATE_CASHIER`, `UPDATE_CASHIER_PERMISSIONS`, `DISABLE_CASHIER`, `ENABLE_CASHIER`, `ADMIN_RESET_PIN`, `RECORD_SALE`, `RECORD_CREDIT_REPAYMENT`, `RECORD_COUPON_PURCHASE`, `RECORD_AMOUNT_CORRECTION`, `RECORD_PAYMENT_METHOD_CHANGE`, `RECORD_VOID`, `RECORD_REFUND`, `RECORD_EXPENSE`, `RECORD_EXPENSE_CORRECTION`, `RECORD_EXPENSE_VOID`, `OPEN_CASH_SHIFT`, `COUNT_CASH_SHIFT`, `CLOSE_CASH_SHIFT`, `GENERATE_DOCUMENT`, `SHARE_DOCUMENT`. Source: `app/src/main/java/com/dailymoneyregister/core/model/enums/ModelEnums.kt`, `OperationType` enum (lines 10–35).

The 13 financial operation types explicitly listed as admin-uploadable are `RECORD_SALE`, `RECORD_CREDIT_REPAYMENT`, `RECORD_COUPON_PURCHASE`, `RECORD_AMOUNT_CORRECTION`, `RECORD_PAYMENT_METHOD_CHANGE`, `RECORD_VOID`, `RECORD_REFUND`, `RECORD_EXPENSE`, `RECORD_EXPENSE_CORRECTION`, `RECORD_EXPENSE_VOID`, `OPEN_CASH_SHIFT`, `COUNT_CASH_SHIFT`, `CLOSE_CASH_SHIFT`. Source: `app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncOperationPolicy.kt`, `adminUploadable` (lines 7–21). The database staged envelope contract has the same 13-name allowlist: `BOARDLOCK/staged_sql/financial_operation_envelopes.sql`, `operation_type` check (search result excerpt; exact line NOT FOUND in this audit).

### Financial and lifecycle event enums

- `TransactionType`: `SALE`, `COUPON_PURCHASE`. `CreditLedgerType`: `OBLIGATION`, `REPAYMENT`, `ADJUSTMENT`. `CouponLedgerType`: `PURCHASE`, `PROMOTION_BONUS`, `REDEMPTION`, `REFUND_CREDIT`. `CashShiftEventType`: `OPENED`, `COUNTED`, `CLOSED`, `ADJUSTED`. `EntryPurpose`: `SALE_FUNDING`, `CREDIT_REPAYMENT_COLLECTION`, `COUPON_PURCHASE_FUNDING`, `REFUND_OUTFLOW`, `EXPENSE_OUTFLOW`, `EXPENSE_CORRECTION`, `FUNDING_CORRECTION`, `PAYMENT_METHOD_RECLASSIFICATION`, `VOID_REVERSAL`. Source: `ModelEnums.kt`, these enum declarations: `EntryPurpose` line 75, `TransactionType` line 87, `CreditLedgerType` line 92, `CouponLedgerType` line 98, `CashShiftEventType` line 111.
- Recent-activity presentation types: `MERCHANDISE_SALE`, `COUPON_SELL`, `COUPON_TOP_UP`, `CREDIT_REPAYMENT`, `REFUND`, `REFUND_TO_COUPON`, `VOID`, `AMOUNT_CORRECTION`, `PAYMENT_METHOD_CHANGE`, `EXPENSE`. Source: `app/src/main/java/com/dailymoneyregister/core/model/financial/RecentFinancialActivity.kt`, `RecentActivityType` (lines 15–26).
- Report row types: `SALE`, `CREDIT_SALE`, `CREDIT_REPAYMENT`, `COUPON_PURCHASE`, `COUPON_TOP_UP`, `COUPON_REDEMPTION`, `COUPON_REFUND`, `COUPON_BONUS`, `COUPON_CREDIT_REPAYMENT`, `REFUND`, `EXPENSE`, `EXPENSE_VOID`, `VOID`, `AMOUNT_CORRECTION`, `PAYMENT_METHOD_CHANGE`. Source: `app/src/main/java/com/dailymoneyregister/feature/reporting/domain/ReportingModels.kt`, `ReportActivityType` (lines 32–49).
- Coupon redemption is represented as a `CouponLedgerType.REDEMPTION` and report/recent activity; a distinct `OperationType` named coupon redemption is **NOT FOUND** in the enum. Coupon promotion bonus is a ledger event, not a distinct operation type. Source: `ModelEnums.kt` and `ReportingModels.kt` enum declarations above.
- No distinct operation type named `RECORD_COUPON_SALE`, `RECORD_COUPON_REDEMPTION`, or generic `RECORD_PAYMENT` was found in `OperationType`. Coupon purchase/top-up and redemption semantics therefore must not be assumed to be separate operation types. Source: `ModelEnums.kt`, `OperationType` and `CouponLedgerType`.

## 2. Effects on financial totals

Legend: **+** increases; **−** decreases; **0** no direct effect; **conditional** depends on tender or the original event; **NOT FOUND** means the audited source did not establish a reliable rule. “Expected cash” means physical cash expected in the shift/day-close calculation, not all collections. A single universal effect table is not present in the inspected code; rows below distinguish documented rules from gaps.

| Operation / event | Gross Sales | Net Sales | Cash in hand | Bank/Online | Credit Outstanding | Coupon prepaid liability | Expenses | Expected Cash at Day Closing |
|---|---|---|---|---|---|---|---|---|
| `RECORD_SALE` / merchandise sale | + full sale amount | + full amount initially | + cash tender only | + online tender only | + credit-funded portion | − coupon balance redeemed, if coupon tender | 0 | + cash tender only |
| `RECORD_CREDIT_REPAYMENT` | 0 | 0 | + cash collection portion | + online collection portion | − amount repaid against debt | conditional: coupon-funded repayment affects coupon liability | 0 | + cash collection portion |
| `RECORD_COUPON_PURCHASE` / coupon sale or top-up | **Owner choice:** recent activity treats coupon sale/top-up as cash/online funding received, not full coupon face value | NOT FOUND for report aggregate | + cash funding only | + online funding only | 0 | + coupon value issued (ledger `PURCHASE`); exact promotion treatment depends on ledger event | 0 | + cash funding only |
| `RECORD_AMOUNT_CORRECTION` | 0 or correction delta; exact report aggregation NOT FOUND | correction delta against original sale | conditional on corrected funding/settlement evidence | conditional on corrected funding/settlement evidence | conditional on credit delta | conditional if coupon-funded amount changes | 0 | conditional on cash lifecycle adjustment |
| `RECORD_PAYMENT_METHOD_CHANGE` | 0 | 0 | reclassification delta only; net cash should not change unless settlement evidence changes | reclassification delta only | 0 expected for a tender-only reclassification | conditional if category changes to/from coupon | 0 | code models cash/online lifecycle adjustments; exact sign rule NOT FOUND |
| `RECORD_VOID` / sale void | − original sale value for reporting lifecycle | − original sale value | − only actual cash settlement reversed | − only actual online settlement reversed | − outstanding debt portion as applicable | conditional on coupon tender/refund evidence | 0 | − cash reversal component only |
| `RECORD_REFUND` | original gross sale remains a recorded sale; report period handling differs by event timestamp | − refund/sales reversal amount | − actual cash payout only | − actual online payout only | − unpaid credit debt for unpaid-credit return; not a cash payout | + coupon balance only for a valid refund-to-coupon credit | 0 | − cash payout only |
| `RECORD_EXPENSE` | 0 | 0 | − cash-paid expense only | − online-paid expense only | 0 | 0 | + expense amount | − cash-paid expense only |
| `RECORD_EXPENSE_CORRECTION` | 0 | 0 | conditional reversal/replacement of prior settlement | conditional reversal/replacement of prior settlement | 0 | 0 | prior expense reversed then corrected amount applied, per lifecycle projection design; exact implementation lines NOT FOUND | conditional on corrected cash settlement |
| `RECORD_EXPENSE_VOID` | 0 | 0 | reverse prior cash settlement if present | reverse prior online settlement if present | 0 | 0 | − prior expense impact | reverse prior cash expense impact |
| `OPEN_CASH_SHIFT` | 0 | 0 | opening float is shift evidence; whether included in expected cash is **NOT FOUND** in this audit | 0 | 0 | 0 | 0 | opening-float inclusion rule NOT FOUND |
| `COUNT_CASH_SHIFT` | 0 | 0 | counted cash is a physical observation, not a sale/receipt | 0 | 0 | 0 | 0 | does not itself change expected cash; compares counted cash with expected cash |
| `CLOSE_CASH_SHIFT` | 0 | 0 | 0 direct effect | 0 | 0 | 0 | 0 | records close/reconciliation snapshot; does not itself create sales/settlement |
| Coupon `REDEMPTION` ledger event | 0 separate from the underlying merchandise sale | 0 separate from the underlying merchandise sale | 0 | 0 | 0 | − redeemed amount | 0 | 0 separate from underlying sale |
| Coupon `PROMOTION_BONUS` | 0 | 0 | 0 | 0 | 0 | + bonus amount | 0 | 0 |
| Coupon `REFUND_CREDIT` | 0 | 0 | 0 | 0 | 0 | + coupon credit amount | 0 | 0 |
| Document/admin operations and nonfinancial operation types | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (no financial effect identified in these operation names) |

Evidence and limits:
- Recent activity explicitly documents that merchandise sales count at full value for all tenders, including coupon and credit; coupon purchase/top-up counts only actual Cash/Online funding; refunds/voids reverse sales; expenses and credit repayments do not alter sales; payment-method changes are classification events; and a credit refund can reverse sales without Cash/Online outflow. Source: `RecentFinancialActivity.kt`, KDoc immediately above `recentTotalEffect` (lines 67–72).
- Shift report fields separately track cash/online sales, coupon used, credit given, repayment by tender, coupon issued/top-up/refund credit, cash/online refunds, cash/online expenses, coupon funding and lifecycle adjustments; `totalCashCollectedMinor` adds cash sales, cash credit repayments, cash coupon funding and cash lifecycle adjustment. Source: `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftModels.kt`, `RegisterShiftReport` and computed totals (lines 21–95).
- Day-closing difference is counted cash minus expected cash; status is SHORT/EXACT/OVER based on the sign. Source: `app/src/main/java/com/dailymoneyregister/feature/register/ui/DayClosingPresentation.kt`, `dayClosingReconciliation` (lines 32–60).
- Reporting repository loads original sale IDs, refund/void/correction/payment-change events, credit repayments, coupon events, expenses and expense lifecycle events separately. Source: `app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingReadRepository.kt`, `readInside` query sections (lines 123–171).

## 3. Reversals, corrections and unsupported behavior

- **Refund (`RECORD_REFUND`)**: supported as a distinct operation and lifecycle event. The code documents that a credit refund can reverse sale value without a Cash/Online outflow; refund-to-coupon only creates coupon value when customer-paid value is refundable (release note). Exact split rules for every original tender combination: **NOT FOUND**. Sources: `ModelEnums.kt`, `OperationType.RECORD_REFUND`; `RecentFinancialActivity.kt` KDoc; `docs/releases/2026-10-19-financial-integrity-hotfix.md`, refund-to-coupon rule.
- **Void (`RECORD_VOID`)**: supported as a distinct operation; recent activity documents voids reverse sales. Exact partial/full and tender-by-tender offset rules: **NOT FOUND** in the inspected excerpts. Sources: `ModelEnums.kt`; `RecentFinancialActivity.kt` KDoc.
- **Amount correction (`RECORD_AMOUNT_CORRECTION`)**: supported. Report has an amount-correction activity type. Exact policy for increasing/decreasing the original amount and the associated settlement/credit/coupon changes: **NOT FOUND** in this pass. Sources: `ModelEnums.kt`; `ReportingModels.kt`, `ReportActivityType`.
- **Payment-method change (`RECORD_PAYMENT_METHOD_CHANGE`)**: supported. Recent activity explicitly describes it as classification-only. Exact permitted transitions and any settlement offset rule: **NOT FOUND**. Sources: `RecentFinancialActivity.kt` KDoc; `ModelEnums.kt`.
- **Expense correction/void**: both are named operations and report activity types include `EXPENSE_VOID`; the inspected reporting repository separately loads expense lifecycle events. Exact event-to-total implementation: **NOT FOUND** in this pass. Sources: `ModelEnums.kt`; `ReportingModels.kt`; `ReportingReadRepository.kt`.
- **Not supported as separate operation types**: coupon sale, coupon redemption, coupon promotion bonus, and generic payment-change subtypes have no separate `OperationType` entry; they appear as transaction/ledger/report events instead. A separate “undo a refund” or “undo a void” operation type is **NOT FOUND** in the enum. Source: `ModelEnums.kt` enum declarations.

## 4. Where numbers are calculated and whether logic is shared

| Surface | Calculation/read path located | Shared calculation? | Evidence / possible divergence |
|---|---|---|---|
| Recent Transactions | `RecentFinancialActivity.recentTotalEffect`; presentation in `feature/register/ui/components/RecentTransactionsPanel.kt`; repository in `RegisterRepository.kt` | Uses a dedicated recent-activity projection and sales-activity total; not the Reports summary aggregator | Explicitly defines coupon purchase/top-up by Cash/Online funding, while merchandise sale uses full amount. Source: `RecentFinancialActivity.kt` KDoc and property. |
| History | `feature/register/data/HistoryRepository.kt`; transaction meaning resolved by `FinancialReadModelResolver`, expenses by `ExpenseEntity`/read projection | Reuses transaction resolver but builds its own activity rows; not demonstrated to share report aggregation | History combines transaction and expense streams and uses broad historical range. Source: `HistoryRepository.kt` class KDoc and `observeHistory`. |
| Reports screen | `feature/reporting/data/ReportingReadRepository.kt` → `ReportingReadModel`; summary aggregation is stated to be Phase 3 but exact aggregator file/function is **NOT FOUND** | Separate reporting read model; exact summary aggregator NOT FOUND | It queries lifecycle events by their own timestamps, rather than shifting later events to the original sale date. Source: `ReportingReadRepository.kt` comments and `readInside`. |
| PDF report | `feature/reporting/pdf/ReportingPdfActions.kt`, `generateSnapshot()` reads `state.readModel` and formats `ReportingSummary` values | Appears to consume the Reports screen read model rather than recalculate totals; export uses its own formatting/presentation | Could differ by stale UI state, selected branch/timezone/decimal formatting or unavailable expected cash; independent arithmetic for totals was NOT FOUND. Source: `ReportingPdfActions.kt`, `generateSnapshot` and summary metric construction. |
| Day Closing | `feature/register/data/RegisterRepository.kt` creates `RegisterShiftReport`; `RegisterShiftModels.kt` defines shift totals; `DayClosingPresentation.kt` calculates counted-minus-expected difference | Separate shift-specific calculation; not shown to reuse Reports summary | Shift totals are per shift and include tender-specific movements; Reports uses selected reporting period and event timestamps. Source: `RegisterShiftModels.kt`; `DayClosingPresentation.kt`. |
| Day Closing notification | `RegisterShiftCloseReportSnapshot` is an immutable display summary attached to confirmed shift-close operation | Snapshot-based, separate from live Reports model; exact notification formatter/path **NOT FOUND** | A close-time snapshot can differ from a later report because later lifecycle events and period boundaries differ. Source: `RegisterShiftModels.kt`, KDoc and `RegisterShiftCloseReportSnapshot`. |
| Server `fetch_financial_reporting_inventory_v1` | **NOT FOUND** in current-main GitHub code search or the inspected guessed path `supabase/functions/fetch_financial_reporting_inventory_v1/index.ts` (404) | NOT FOUND | Cannot compare server aggregation to client calculation without locating the function definition/migration. No behavior inferred. |

Places where numbers could disagree:
1. Recent Transactions vs Reports: recent activity's documented daily sales total treats coupon purchase/top-up as only cash/online funding; Reports uses its own typed report rows and summary aggregation (exact aggregator NOT FOUND).
2. History vs Reports: separate row-building/read paths; Reports scopes by period and each lifecycle event's own timestamp; History observes broad history.
3. Reports screen vs PDF: PDF reads the current `state.readModel` but formats/chooses displayed metrics separately.
4. Reports vs Day Closing: period-based reporting versus shift-based cash reconciliation; cash-only expected amount is not the same metric as net sales or all collections.
5. Day Closing live report vs close notification: notification uses an immutable close snapshot, not a live report recomputation.
6. Any client surface vs server inventory: server function definition was NOT FOUND, so parity is unverified, not proven different.

## 5. Storage, currency precision, tax, split payments and floating point

- Monetary values are represented by `MoneyMinorUnits(val amount: Long)`; the code comment says integer minor units (for example, baisa for OMR and paise for INR). Addition, subtraction, negation and multiplication use exact-overflow-checking `Math.addExact/subtractExact/negateExact/multiplyExact`. Source: `app/src/main/java/com/dailymoneyregister/core/model/money/MoneyMinorUnits.kt`, class KDoc and operators (lines 11–35).
- OMR uses 3 decimal places when configured as OMR; the integration test fixture sets `currencyDecimalPlaces = 3` for OMR. This is currency configuration, not a universal hard-coded assumption. Source: `app/src/androidTest/java/com/dailymoneyregister/DmrDatabaseIntegrationTest.kt`, OMR branch fixture (search excerpt; exact line NOT FOUND).
- Tax rates are basis points (100 bps = 1%). Tax uses integer `BigInteger` multiplication/division by 10,000 and an explicit mode: `HALF_EVEN`, `HALF_UP`, `CEILING`, or `FLOOR`. A tie in HALF_EVEN rounds to an even minor-unit quotient; HALF_UP rounds half upward. Source: `app/src/main/java/com/dailymoneyregister/core/model/tax/TaxRoundingPolicy.kt`, `calculateTax` (lines 14–73).
- Split tender: the financial read model exposes tender-specific funding parts, and the recent-activity logic filters Cash/Online portions for coupon sale/top-up. The exact rounding/allocation algorithm for a split such as coupon + cash is **NOT FOUND** in the inspected code. Source: `RecentFinancialActivity.kt` `recentTotalEffect`; split allocator NOT FOUND.
- Floating point: `MoneyMinorUnits.kt` explicitly prohibits Float/Double for financial representation/calculation and uses integer arithmetic; tax uses BigInteger. However, this audit did not scan every source file exhaustively for all `Float`, `Double`, `toDouble()`, or floating-point operators. Therefore **every place using floating-point money arithmetic: NOT FOUND / not exhaustively enumerated**. No claim that the whole repository contains none is made.
- PDF formatting uses `BigDecimal.valueOf(amount, decimals)` for display conversion only. Source: `feature/reporting/pdf/ReportingPdfActions.kt`, local `money(amount: Long)` in `generateSnapshot`.

## 6. Tests by operation type

A repository-wide, test-by-test coverage mapping was **NOT FOUND** in this pass. Search results identified these relevant test files or named cases; these are evidence of some coverage, not proof of complete operation coverage.

| Operation / event | Test evidence found | Coverage status |
|---|---|---|
| `RECORD_SALE` | `app/src/androidTest/java/com/dailymoneyregister/OnlineSaleFinancialIntegrationTest.kt`: `simpleOnlineSaleUsesOneExactManualQrFunding`; `app/src/androidTest/java/com/dailymoneyregister/CouponSaleIntegrationTest.kt` covers coupon redemption/funding breakdown | Partial; cash-sale full coverage NOT FOUND |
| `RECORD_CREDIT_REPAYMENT` | **NOT FOUND** exact test names in this search pass | No test located |
| `RECORD_COUPON_PURCHASE` / top-up | `CouponSaleIntegrationTest.kt` found; exact test names NOT FOUND from excerpt | Partial |
| `RECORD_AMOUNT_CORRECTION` | `AmountCorrectionFinancialIntegrity.kt` is implementation, not a test; specific test name **NOT FOUND** | No test located |
| `RECORD_PAYMENT_METHOD_CHANGE` | Specific test name **NOT FOUND** | No test located |
| `RECORD_VOID` | `docs/customer-export-financial-reconciliation.md` describes fixture-based tests for a voided sale, but exact test class/name NOT FOUND | Test existence described, exact test NOT FOUND |
| `RECORD_REFUND` | `docs/releases/2026-10-19-financial-integrity-hotfix.md` describes unpaid-credit refund regression for 3,000 minor units and same-operation replay assertions; exact test name NOT FOUND in retrieved excerpt | Test existence described, exact test NOT FOUND |
| `RECORD_EXPENSE` | `ExpenseCode2ProjectionIntegrationTest.kt` found for shift expense projection; exact test methods NOT FOUND in excerpt | Partial |
| `RECORD_EXPENSE_CORRECTION` | **NOT FOUND** a specific test name | No test located |
| `RECORD_EXPENSE_VOID` | **NOT FOUND** a specific test name | No test located |
| `OPEN_CASH_SHIFT` | **NOT FOUND** a specific test name in this pass | No test located |
| `COUNT_CASH_SHIFT` | **NOT FOUND** a specific test name in this pass | No test located |
| `CLOSE_CASH_SHIFT` | **NOT FOUND** a specific test name in this pass | No test located |
| Coupon `REDEMPTION`, `PROMOTION_BONUS`, `REFUND_CREDIT` | `CouponSaleIntegrationTest.kt` confirms a coupon redemption with mixed Coupon/Cash funding; bonus/refund-credit tests **NOT FOUND** | Partial |
| Server `fetch_financial_reporting_inventory_v1` | Function and corresponding test suite **NOT FOUND** | No test located |

Accordingly, operation types/reversals with **no specific test name located in this audit**: `RECORD_CREDIT_REPAYMENT`, `RECORD_AMOUNT_CORRECTION`, `RECORD_PAYMENT_METHOD_CHANGE`, `RECORD_EXPENSE_CORRECTION`, `RECORD_EXPENSE_VOID`, `OPEN_CASH_SHIFT`, `COUNT_CASH_SHIFT`, `CLOSE_CASH_SHIFT`; exact named tests for refund/void and coupon purchase/top-up were also not retrieved. This is a search result, not proof that no tests exist.

## 7. Questions for the owner

1. Should coupon sale/top-up count in Gross Sales by its full issued face value, or only by the cash/online funding received? Recent Transactions explicitly uses only Cash/Online funding for coupon purchase/top-up, unlike merchandise sales. Source: `RecentFinancialActivity.kt`, KDoc above `recentTotalEffect`.
2. Should coupon redemption be counted only as tender on the merchandise sale, with no separate sales amount? The enums model redemption as a coupon ledger event and a report activity, not a distinct operation type. Source: `ModelEnums.kt`, `CouponLedgerType`; `ReportingModels.kt`, `ReportActivityType`.
3. For credit sales, should Net Sales increase by the full sale while cash/bank remain zero and Credit Outstanding increases? Recent activity says merchandise sales count at full value regardless of Credit tender. Source: `RecentFinancialActivity.kt`, KDoc above `recentTotalEffect`.
4. For a refund of unpaid credit, should sales/debt decrease without any cash/online payout? The code/release note describes this behavior. Source: `RecentFinancialActivity.kt` KDoc; `docs/releases/2026-10-19-financial-integrity-hotfix.md`.
5. For partially repaid credit refunds, should only the customer-paid/refundable portion be returned as cash/online/coupon, while the unpaid portion only reduces debt? The release note says these amounts are separated; owner confirmation of the policy is still appropriate. Source: `docs/releases/2026-10-19-financial-integrity-hotfix.md`.
6. Should a void reverse the full original sale value in Net Sales even where actual settlement/payout is smaller or zero? Exact tender/credit treatment was NOT FOUND. Source: `RecentFinancialActivity.kt` KDoc; exact implementation NOT FOUND.
7. When amount correction changes a sale, should gross sales remain immutable and only Net Sales reflect the delta, or should the corrected amount be restated in Gross Sales? The exact aggregate policy was NOT FOUND. Source: `ReportingModels.kt`, `AMOUNT_CORRECTION`; aggregate implementation NOT FOUND.
8. Can payment-method change move amounts between Cash, Online, Credit and Coupon after the sale, and which ledger balances may change? Recent activity describes classification-only behavior, but allowed transitions and exact offset rules were NOT FOUND. Source: `RecentFinancialActivity.kt` KDoc.
9. Does coupon purchase increase prepaid liability by the full coupon value even when a promotion bonus is also issued, and how should bonus value be distinguished from customer-funded liability? Coupon ledger has separate `PURCHASE` and `PROMOTION_BONUS` types, but the accounting treatment is NOT FOUND. Source: `ModelEnums.kt`, `CouponLedgerType`.
10. Should coupon refund always restore prepaid liability, or only where refundable customer-paid value exists? The release note describes a guard; the business policy should be confirmed. Source: `docs/releases/2026-10-19-financial-integrity-hotfix.md`.
11. Are expense corrections required to reverse the prior expense and then apply the corrected expense, and does an expense void remove the full expense impact while preserving audit evidence? Exact implementation was NOT FOUND in this pass. Source: `ModelEnums.kt` expense operations; `ReportingReadRepository.kt` loads expense lifecycle events.
12. Should the opening cash float be included in expected cash at closing, and how should cash-shift adjustments affect it? This exact rule was NOT FOUND. Source: `RegisterShiftModels.kt` exposes `expectedCashMinor` and lifecycle adjustments; exact calculation not inspected.
13. Is Expected Cash meant to include cash credit repayments and cash coupon funding as well as cash sales, and to subtract cash refunds and cash expenses? The shift report fields and `totalCashCollectedMinor` include some of these categories, but the exact full expected-cash formula needs owner confirmation against the implementation. Source: `RegisterShiftModels.kt`, `RegisterShiftReport`.
14. Should Reports include lifecycle activity in the period when the correction/refund/void occurred rather than restating the original sale date? Reporting repository comments explicitly use each lifecycle event's own timestamp. Source: `ReportingReadRepository.kt`, lifecycle query section.
15. Should PDF be a snapshot of the currently loaded Reports model, or should it independently refresh/recalculate when exported? It currently reads `state.readModel`. Source: `ReportingPdfActions.kt`, `generateSnapshot`.
16. What is the authoritative server-side reporting calculation and how must it reconcile to app reports? `fetch_financial_reporting_inventory_v1` definition was NOT FOUND, so parity cannot be assessed. Source search for that exact function returned no result; guessed path returned 404.
17. Which tax rounding mode is the intended business/legal default for Oman, and can individual businesses override it? The code supports four modes and stores a mode in branch/business configuration; the intended default must be confirmed from actual setup defaults. Source: `TaxRoundingPolicy.kt`; `CoreEntities.kt` tax rounding field; setup default exact line NOT FOUND.
18. What is the required allocation/rounding order for split tender (including coupon + cash) when a total cannot be evenly divided at currency precision? Exact split allocator was NOT FOUND.
19. Should reports display coupon issuance as sales, a liability movement, cash collected, or separate measures? Current code distinguishes coupon ledger events and Cash/Online funding, but the business reporting definition needs explicit owner sign-off. Source: `ModelEnums.kt`, `CouponLedgerType`; `RecentFinancialActivity.kt` KDoc.
20. What should happen when server reporting inventory is missing or disagrees with client evidence? Server implementation and tests were NOT FOUND; no rule should be inferred.

---

**Audit limitations:** This is a source-search-based first pass. Several implementation details, exact source line numbers for some search-only hits, exhaustive test names, the full floating-point scan, and the server function were not located. Those gaps are explicitly marked NOT FOUND; no code or financial behavior was changed.


---

# Step 1b — implementation evidence and corrections

This section supplements the initial search pass with direct reads of implementation and test files. Source line numbers below refer to the current `main` snapshot inspected for this audit; code was not modified.

## A. Expected Cash: exact formula and source inputs

`registerShiftExpectedCashMinor` is in `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftReportReadRepository.kt:39-62`. The formula is:

`cashSalesMinor + cashCreditRepaymentsMinor + cashCouponFundingMinor + cashLifecycleAdjustmentMinor - cashRefundsMinor - cashExpensesMinor`.

The implementation nests `Math.addExact` and `Math.subtractExact`; overflow throws rather than silently wrapping. Its six inputs are passed by the caller at `RegisterShiftReportReadRepository.kt:767-775`.

- `cashSalesMinor`: positive Cash settlements for `RECORD_SALE`, lines 287-348.
- `cashCreditRepaymentsMinor`: positive Cash settlements linked to credit-repayment evidence, lines 387-431.
- `cashCouponFundingMinor`: Cash funding settlements for `RECORD_COUPON_PURCHASE`, selected by `COUPON_PURCHASE_FUNDING` / `FUNDING_CORRECTION` evidence, lines 433-507.
- `cashLifecycleAdjustmentMinor`: Cash settlement movements for void, amount correction and payment-method reclassification, lines 668-730 and 735-761.
- `cashRefundsMinor`: Cash-category refund outflow settlements, lines 509-543.
- `cashExpensesMinor`: active expense projections with Cash category and matching `EXPENSE_OUTFLOW` evidence; voided expenses are excluded, lines 573-660.

**Opening float: NO, it is not part of this formula.** The function signature has only the six inputs above (lines 39-45); the formula adds no opening-float input (lines 47-62), and the caller supplies only those six values (lines 767-775). Opening cash is recorded as shift lifecycle evidence in `RegisterRepository.kt:672-716`, but it is not passed to this formula. Day Closing computes `countedCash - expectedCash` at `app/src/main/java/com/dailymoneyregister/feature/register/ui/DayClosingPresentation.kt:32-60`. Therefore if the drawer starts with cash that is not represented as a counted cash sale, credit repayment, coupon funding or lifecycle adjustment, the expected amount omits that starting float and the counted-minus-expected variance will appear overstated by the omitted float. This is an arithmetic consequence of the formula, not an inferred business rule.

Tests: `app/src/test/java/com/dailymoneyregister/feature/register/data/RegisterShiftDayClosingAccountingTest.kt:70-95`, `expectedCashIncludesCouponFundingAndLifecycleDeltasExactlyOnce`, asserts 106,000 minor units from cash sales 9,000 + cash credit repayments 27,000 + coupon funding 100,000 + lifecycle adjustment (as supplied) less refunds/expenses (fixture values in lines 70-95). `externalMovementRoutesOnlyCashAndOnline` at lines 12-48 checks external movement classification. The test suite does not establish opening-float inclusion; source formula shows it is excluded.

## B. Amount correction: supported scope and totals

`AmountCorrectionFinancialIntegrity.validate` is in `app/src/main/java/com/dailymoneyregister/feature/register/data/AmountCorrectionFinancialIntegrity.kt:111-217`. It accepts only `TransactionType.SALE` (lines 121-126), rejects negative corrected amounts (128-130), any credit repayment allocations (132-136), voided sales (143-147), fully refunded sales (149-157), corrected values below already-refunded value (159-166), unsupported funding identities (168-191), and a no-op delta (193-203). It computes `delta = correctedAmount - currentAmount` at lines 193-197.

The repository admission/writer path is `RegisterRepository.executeAmountCorrection`, `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterRepository.kt:3348 onward`; the validator is called during admission and admitted execution. The source leaves the original transaction amount immutable and appends a signed settlement delta:
- Increase: positive delta; test `productionCorrection_increase_writesOnlyPositiveDelta_andPreservesOriginal` in `app/src/androidTest/java/com/dailymoneyregister/AmountCorrectionFinancialCoreIntegrationTest.kt:244-349` checks original stays 10,000, corrected amount becomes 12,000 in the read model, and one +2,000 settlement is written.
- Decrease: negative delta; `productionCorrection_decrease_writesOnlyNegativeDelta` at lines 350-396 checks original stays 10,000 and a -2,000 settlement is written.
- Append-only chain: `productionCorrection_chain_isAppendOnlyAndInternallyConsistent` at lines 397-515.
- Idempotency/replay and altered intent: `productionCorrection_sameOperationRetry_replaysWithoutDuplicateMoney` at lines 516-588 and `productionCorrection_sameOperationChangedIntent_conflictsWithoutDuplicateMoney` at lines 589-655.
- Below prior refunded value fails closed: `productionCorrection_belowAlreadyRefundedValue_failsClosed` at lines 656-723.
- Recovery: `startupRecovery_completesAdmittedPendingCorrection_exactlyOnce` at lines 724 onward.

**Effect:** Gross Sales / `periodSaleInflow` remains the original sale amount; correction changes `periodCorrectionEffect` by the signed delta and therefore changes Net Sales activity. Cash or Online is adjusted only through the corresponding signed `FUNDING_CORRECTION` settlement. Credit-funded sales and sales with credit repayment allocations are not eligible; coupon funding is not an eligible amount-correction identity. Source: `AmountCorrectionFinancialIntegrity.kt:121-191` and `ReportingAggregator.kt:190-211, 223-225`. A correction appears on the report day of the correction event, not the original sale day: `ReportingReadRepository.kt:137-155` loads corrections by their own occurred-at period; `ReportingAggregator.kt:21-22` explicitly says lifecycle rows contribute on their own `occurredAt`.

## C. Void and refund: implementation scope

### Void

`VoidFinancialIntegrity.validate` in `app/src/main/java/com/dailymoneyregister/feature/register/data/VoidFinancialIntegrity.kt: (open the file's validate function; implementation spans approximately lines 130-325)` resolves the authoritative current sale state, calculates the remaining reversible amount after corrections/refunds, validates funding settlement identity, and rejects ambiguous or unsupported states. The exact implementation constraints are covered by tests:
- `productionVoid_normalSale_createsExactlyOneExactReversal` — `app/src/androidTest/java/com/dailymoneyregister/VoidFinancialCoreIntegrationTest.kt:244-322`: a normal sale gets one exact reversal.
- `productionVoid_partialRefund_reversesOnlyRemainingValue` — lines 324-367: void reverses only the remaining value after a partial refund.
- `productionVoid_fullRefund_createsNoAdditionalReversal` — lines 407-444: full refund leaves no additional reversible value.
- `productionVoid_correctionAndRefund_useAuthoritativeRemainingValue` — lines 446-533: reversal is based on the corrected/refunded current amount.
- `productionVoid_splitRefundOutflows_failClosed` — lines 369-405.
- `productionVoid_creditFundedTransaction_isBlockedWithoutFinancialMutation` — lines 1246-1272.
- `productionVoid_couponFundedTransaction_isBlockedWithoutFinancialMutation` — lines 1274-1300.
- `productionVoid_paymentMethodChangeExists_isBlockedWithoutFinancialMutation` — lines 1302-1352.
- `productionVoid_creditRepaymentAllocationExists_isBlockedWithoutFinancialMutation` — lines 1354-1394.
- `productionVoid_mixedCashAndOnlineFunding_isBlockedAsAmbiguous` — lines 1396-1441.
- `productionVoid_missingSaleFunding_isBlockedAsAmbiguous` — lines 1443-1471.
- Idempotency and concurrency are covered by `productionVoid_sameOperation_replaysWithoutDuplicate_andAlteredReplayConflicts` (623-669), `productionVoid_differentOperation_cannotReverseSameTransactionTwice` (743-792), and `productionVoid_concurrentDifferentOperations_createOneEconomicReversal` (913-976).

So a normal supported single-funding Cash or Online sale can be voided; credit- and coupon-funded sales, ambiguous mixed Cash+Online funding, and several later lifecycle combinations fail closed. A partially refunded sale void reverses only remaining value; a fully refunded sale gets no extra reversal. Cash/Online settlement reversal follows the original supported funding identity. Credit and coupon voids are not supported in this validator.

### Refund

`RefundFinancialIntegrity.validate` is in `app/src/main/java/com/dailymoneyregister/feature/register/data/RefundFinancialIntegrity.kt:54 onward`; the repository applies it in `RegisterRepository.executeRefund` (search for that method in `RegisterRepository.kt`). The code and tests show:
- Partial and multiple refunds are supported within the refundable balance: `productionRefund_partial_createsExactEventAndOutflow` (Refund test lines 310-374) and `productionRefund_partialPartialFull_reachesZeroWithoutRewritingSale` (376-437).
- Over-refund is blocked: `productionRefund_overRefund_isBlockedWithoutFinancialMutation` (438-482).
- A normal Online-funded sale refunds to the same Online channel: `productionRefund_onlineFunding_refundsBackToSameOnlineChannel` (626-655).
- Refund destination must match authoritative funding: `productionRefund_destinationMustMatchAuthoritativeFunding` (592-624).
- Mixed Cash+Online funding is rejected as ambiguous: `productionRefund_mixedCashAndOnlineFunding_isBlockedAsAmbiguous` (1324-1386).
- Pure Credit sale refunds are accepted only under the explicit credit-return destination rules; the test `productionRefund_unpaidCredit_reducesDebtWithoutCashOnlineOrCoupon` (926-1032) asserts unpaid credit is reduced with no Cash/Online/Coupon outflow. The B2B tests `productionRefund_b2b_returnBelowUnpaidCredit_reducesDebtOnly` (1656-1722), `productionRefund_b2b_returnExactlyUnpaidCredit_extinguishesDebtWithoutPaidValueRefund` (1724-1784), and `productionRefund_b2b_returnCrossesUnpaidBoundary_refundsOnlyPaidExcess` (1786 onward) cover the unpaid-versus-paid boundary.
- Coupon-funded refunds are rejected unless the explicit coupon destination path is selected: `productionRefund_couponFundedTransaction_isBlockedWithoutFinancialMutation` (1176-1206); coupon refund credit behavior is represented in coupon-ledger/report events.
- Refund after void is blocked: `productionRefund_afterVoid_isBlocked` (484-519).
- Credit repayment allocation, payment-method-change, split historical outflows and mismatched historical identities have fail-closed tests at lines 1208-1517.

Reporting treats the refund amount as a negative Net Sales activity regardless of whether it paid out cash; Cash/Online totals decrease only by actual destination parts, while coupon destination creates coupon refund credit and unpaid credit return reduces outstanding debt. Source: `ReportingAggregator.kt:146-158`; `RefundFinancialIntegrity.kt:175-329` and the test assertions above.

## D. Payment-method change: exact allowed transitions and totals

`PaymentMethodChangeFinancialIntegrity.kt:52-64` defines the narrow supported scope: SALE funded by one Cash or Online identity, with zero or more prior reconciled payment-method changes. It rejects voided sales (130-132), credit repayment allocations (134-136), amount corrections/refunds (138-145), credit and coupon funding (284-313), and ambiguous settlement pairs. Allowed categories are Cash with `CASH_TENDER`, or Online with `MANUAL_QR` or `SOFTPOS` (284-313). A change writes two balanced settlements: negative full sale amount from the current identity and positive full amount to the new identity (205-280); it does not change the economic sale amount. Therefore Gross Sales and Net Sales do not change; Cash and Online classification changes by equal/opposite amounts, leaving combined Cash+Online value unchanged. Credit Outstanding and coupon liability do not change because those identities are unsupported. Day Closing applies signed Cash/Online lifecycle adjustments from the two settlements (`RegisterShiftReportReadRepository.kt:735-761`); net expected cash changes only by the Cash side of the reclassification.

Tests: `PaymentMethodChangeWriterTest.kt:81-113` (`appendsExactBalancedPairAndPreservesOriginalFunding`); 114-125 (`retryIsIdempotentAndAlteredRequestConflicts`); 126-137 (`secondChangeRetainsFirstEventAndBothPairs`); 138-147 (session/permission); 205-230 (backdated/equal timestamp rejected). Report test `saleRefundVoidCorrectionAndPaymentChangePreservePeriodSemantics` in `app/src/test/java/com/dailymoneyregister/feature/reporting/data/ReportingAggregatorTest.kt:67-166` asserts payment-change count increments while revenue totals stay the same.

## E. Expense correction/void projections and Day Closing

The expense repository path is `RegisterRepository.executeExpenseLifecycle` at `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterRepository.kt:268-283`; test `adminExpenseCorrectionAndVoidAppendEventsAndQueueExactlyOnce` in `app/src/androidTest/java/com/dailymoneyregister/ExpenseIntegrationTest.kt:1272 onward` asserts the correction and void append events and queue exactly once.

Reports load original expenses and expense lifecycle events separately in `ReportingReadRepository.kt:137-171`; `ReportingAggregator.kt:161-175` applies signed expense rows by their event date. The reporting tests are:
- `expenseCreatedCorrectionAndVoidRemainReportableWithoutDoubleCounting` — `ReportingAggregatorTest.kt:439-468`.
- `expenseCorrectionInLaterPeriodProducesOnlyItsSignedDelta` — lines 470-478.
- `expenseCorrectionAndVoidSupportOnlineFundingAsWell` — lines 480-491.

Day Closing reads the original Expense entity, matches authoritative `EXPENSE_OUTFLOW` settlement evidence, and excludes voided expense projections. Source: `RegisterShiftReportReadRepository.kt:573-660`. `RECORD_EXPENSE_VOID` explicitly does not add the `VOID_REVERSAL` settlement again because the voided original expense is already excluded (lines 662-666). Thus an expense void removes the expense's impact from Day Closing without double-subtracting its reversal; an expense correction contributes through signed lifecycle settlement adjustments. Day Closing is shift-scoped and reports Cash/Online expense totals separately.

## F. Split payments and rounding

The sale/report model preserves separate `ReportPaymentPart` rows (`ReportingModels.kt:72-88`), and `ReportingAggregator.kt:96-105` sums each part by tender. Coupon funding parts are allocated separately and only Cash/Online parts count as external receipts (`ReportingAggregator.kt:68-88`). Day Closing likewise sums each positive settlement by its recorded category (`RegisterShiftReportReadRepository.kt:318-348`).

No rounding occurs when aggregating an already-persisted split: amounts are integer minor units and summed with `Math.addExact`. The exact user-input allocation algorithm that first creates a mixed coupon+cash split was not yet located. Files and searches tried: `RegisterRepository.kt` search for `split`, `fundingParts`, `paymentParts`, `CouponRedemptionCommand`, `executeCouponRedemption`; `CouponSaleIntegrationTest.kt`; `ReportingModels.kt` `ReportPaymentPart`; `ReportingAggregator.kt` `paymentParts` / `fundingParts`. Those reads establish consumption of stored splits but not the split-creation rounding rule. That narrow algorithm remains NOT FOUND in this pass.

## G. ReportingAggregator, Gross Sales, Net Sales and Credit Outstanding

`ReportingAggregator.build(rows, creditOutstanding)` is `app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingAggregator.kt:26-29`.
- Gross Sales is `periodSaleInflow`, summed from `SaleReportRow.originalAmount` at lines 93-96.
- Net Sales activity is sale inflow + negative refund effect + negative void effect + signed correction effect at lines 223-225.
- Payment-method changes are classification/audit events only; lines 212-220 (see the full file) do not add them to sales revenue.
- Cash in hand and bank/online are separate net collection calculations, including repayments and coupon cash/online receipts, minus refund outflows, expenses and void settlement reversals (lines 228-240).
- Credit Outstanding is passed as an optional `creditOutstanding` argument; if omitted, fallback is `max(0, period credit sales - period repayments)`, lines 255-264. It is not computed from the Gross Sales field.
- `ReportingReadRepository.readInside` loads original sale IDs created in the selected period, then lifecycle events by their own timestamps, plus repayments, coupon events, expenses and expense lifecycle events (`ReportingReadRepository.kt:137-171`); it calls `ReportingAggregator.build(ordered)` when constructing the read model (search result found this call; exact return block is immediately after the event mapping).

PDF uses the same already-loaded `state.readModel` rather than calling the aggregator again: `app/src/main/java/com/dailymoneyregister/feature/reporting/pdf/ReportingPdfActions.kt:105-155`. It reads `summary.periodSaleInflow`, `periodNetSalesActivity`, `creditOutstanding` and `expectedCash` from that model. Therefore Reports and PDF share the same summary values at export time; differences can still arise from stale state, formatting, or PDF's “not available” handling for nullable expected cash, not from a second totals formula in PDF.

## H. Test coverage — known names and true gaps still to check

The prior statement that no credit repayment or amount correction test was located was incorrect. The actual test files and names include:

- `RECORD_CREDIT_REPAYMENT`: `app/src/androidTest/java/com/dailymoneyregister/CreditRepaymentIntegrationTest.kt`: `cashRepaymentCreatesExactAuthoritativeFinancialEvidence` (480-689), `onlineRepaymentPreservesOnlineCollectionIdentity` (690-760), `fifoRepaymentConsumesOldestCreditSaleFirst` (761-847), `exactPayoffProducesZeroDerivedOutstanding` (848-894), `overpaymentFailsWithoutFinancialChildWrites` (895-968), `identicalReplayDoesNotDuplicateRepaymentEvidence` (969-1085), `pendingDurableRepaymentRecoversExactlyOnce` (1086-1216), plus coupon-credit repayment tests at 1508 onward.
- `RECORD_AMOUNT_CORRECTION`: `app/src/androidTest/java/com/dailymoneyregister/AmountCorrectionFinancialCoreIntegrationTest.kt`: the six tests listed in section B, plus startup recovery.
- `RECORD_REFUND`: `app/src/androidTest/java/com/dailymoneyregister/RefundFinancialCoreIntegrationTest.kt`: partial, multi-partial/full, over-refund, after-void, replay/altered replay, destination identity, online funding, unpaid-credit return, mixed funding fail-closed and concurrency/recovery cases listed in section C.
- `RECORD_VOID`: `app/src/androidTest/java/com/dailymoneyregister/VoidFinancialCoreIntegrationTest.kt`: normal sale, partial refund remainder, full refund, correction+refund remaining value, unsupported credit/coupon/mixed funding, replay, duplicate, concurrency and recovery cases listed in section C.
- `RECORD_PAYMENT_METHOD_CHANGE`: `app/src/androidTest/java/com/dailymoneyregister/PaymentMethodChangeWriterTest.kt` methods listed in section D; `PaymentMethodChangeCommandTest.kt` also covers command/precondition/payload validation (exact method list not expanded in this pass).
- `RECORD_EXPENSE`: `app/src/androidTest/java/com/dailymoneyregister/ExpenseIntegrationTest.kt`: `cashExpensePersistsAuthoritativeExpenseAndNegativeOutflow` (215-388), `onlineExpensePersistsOnlineOutflowWithoutCashClassification` (390-448), `periodAndShiftQueriesDeriveFromAuthoritativeExpenseRows` (659-748), `cashAndOnlineTotalsRemainDerivableWithoutDuplicateBalanceState` (750-807), and lifecycle test `adminExpenseCorrectionAndVoidAppendEventsAndQueueExactlyOnce` (1272 onward).
- `RECORD_EXPENSE_CORRECTION` and `RECORD_EXPENSE_VOID`: `ExpenseIntegrationTest.kt` lifecycle test above, plus `ReportingAggregatorTest.kt` three expense lifecycle methods listed in section E.
- `RECORD_SALE`: `app/src/androidTest/java/com/dailymoneyregister/OnlineSaleFinancialIntegrationTest.kt` and `app/src/androidTest/java/com/dailymoneyregister/RegisterIntegrationTest.kt`; method-level names were not extracted in this pass. Coupon sale/top-up coverage exists in `app/src/androidTest/java/com/dailymoneyregister/CouponSaleIntegrationTest.kt`, but its complete method list was not extracted.
- Cash shifts: `app/src/test/java/com/dailymoneyregister/feature/register/data/RegisterShiftDayClosingAccountingTest.kt`: `externalMovementRoutesOnlyCashAndOnline`, `couponTopUpClassificationUsesDurableIntentPayload`, `expectedCashIncludesCouponFundingAndLifecycleDeltasExactlyOnce`, `lifecycleSignedMovementCanRepresentCorrectionReclassificationAndVoid`. These cover calculation helpers, not necessarily each production OPEN/COUNT/CLOSE writer path.

Tests truly absent from the specific files examined cannot yet be stated as repository-wide absences. Exact tests for `OPEN_CASH_SHIFT`, `COUNT_CASH_SHIFT`, and `CLOSE_CASH_SHIFT` production writer paths remain NOT FOUND in the search performed. The complete `RECORD_SALE` and `RECORD_COUPON_PURCHASE` test-method list also remains NOT FOUND in this pass. Searches attempted included GitHub code search for each test class/operation name and direct reads of the test files listed above; the full files for those two sale/coupon classes were not yet line-by-line enumerated.

## I. Live server function and migration drift

A read-only SQL query against live project `ttvdpgxqvvtjnxxfkoui` retrieved `public.fetch_financial_reporting_inventory_v1`. Its return columns are `operation_id`, `business_id`, `branch_id`, `operation_type`, `occurred_at`, `received_at`, `canonical_text`, `canonical_payload`, and `canonical_sha256`. Its query selects these envelope columns from `private.financial_operation_envelopes`, filters by business, requested branches, occurred-at window, optional actor and cursor, orders by received-at/operation ID, and applies a maximum page size. It does **not** aggregate Gross Sales, Net Sales, cash, credit outstanding, or other financial totals; it returns raw canonical envelope records for the client to interpret.

The live migration history contains version `20261018140123` named `add_financial_reporting_inventory_rpc`. However, fetching the guessed repository file `supabase/migrations/20261018140123_add_financial_reporting_inventory_rpc.sql` returned 404; a second guess `supabase/migrations/20261018140123_add_financial_reporting_rpc.sql` also returned 404. GitHub code search for `fetch_financial_reporting_inventory_v1` returned no result. Therefore the live function exists and the database records a migration, but the corresponding migration source file was not located in the repo under those names. No exhaustive inventory comparison of every live table/function against every repository migration was completed in this pass, so additional drift is NOT FOUND rather than ruled out.

The live definition also contains access-control checks. Per the owner's instruction, those security-specific details are intentionally kept out of this non-security document and reported only in chat.

## J. Documentation workflow status

The latest commit returned by GitHub's repository commit search was `2519f6cc4e08c06a8d9e93ed92dbcacb0c3222a4`, the second documentation commit, followed by `d2264176966efebfc0eab1cc32dfefbd44342879`, then `287de0c3205a50b1c490bad00a7d67bdc2647c6c` and `f7cd5e09513ea7d1d540d2f1113d2ec537259103`. This means those documentation commits were in the repository's current default branch history at the time checked, despite the requested audit-only workflow. The available commit-run lookup returned no runs for either SHA, but that connector only returns pull-request-triggered workflow runs; it cannot establish whether push-triggered workflows or release builds ran. Workflow file paths guessed (`.github/workflows/build-apk.yml`, `.github/workflows/android-release.yml`) were not found, so release-build status remains unverified, not “none”.

Going forward this document is copied/maintained on `audit/release-readiness-2026-10-25`; no additional changes are made to `main`.
