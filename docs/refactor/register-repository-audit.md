# RegisterRepository god-file refactor — Phase 1 audit and proposal

- Repository: `guruoshan/Paid-Due`
- Branch: `refactor/register-repository-audit`
- Audited base: `main` at `837d1bd5762d1b1d7ca0371fea76fad1db628026`
- Target source blob: `3df48aa404e0acfb8ebaeb04a2709df7a32a5ab8`
- Target: `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterRepository.kt`
- Target size: **8,756 lines**; **112 function declarations** found (including two local recovery helpers).
- Scope: read-only audit and proposal. No production code, tests, resources, Gradle files, schemas, or device state changed. No tests were run.

## Executive findings

1. Governance and release instructions were read in full. `AGENTS.md` directs use of `.agents/skills/boardlock-engineer/SKILL.md`; that skill was also read. No prompt/governance conflict was found.
2. The prior grouping counts sum to 68 functions (17 + 8 + 10 + 14 + 4 + 2 + 13). The current target contains **112** function declarations. **44 declarations are outside those seven reported counts**. The exact member roster of the earlier audit could not be located in the repository or available prior-work context, so an exact name-by-name comparison of its 68 entries cannot be certified. The table below independently classifies all 112 current declarations from their bodies.
3. The source is already partly decomposed: expense writes, coupon top-up/search, transaction details, expense reads, customer reads, credit-invoice reads, shift reports, recent-activity reads, authorization, and cash-shift resolution delegate to dedicated repositories. A refactor should extend/reuse those components, not create duplicate abstractions.
4. Main characterization gaps: no test reference was found for Coupon Top-Up; deterministic ID formats have no identified frozen-output tests; register-shift count/close have only one combined happy-path authorization test and no located recovery/replay tests; coupon redemption's credit-remainder and replay/recovery branches are not locked by the located tests.
5. No probable arithmetic or ledger defect was established from the inspected bodies. The high-risk areas are not cleared for implementation; §14 approval remains mandatory.

## Governance sections used

Only the following sections are cited in the function inventory:

| Section | Heading | Relevance |
|---|---|---|
| §2 | **GOLDEN FINANCIAL LAW** | Append-only facts, immutable identity and audit history. |
| §3 | **ONE FINANCIAL SOURCE OF TRUTH** | Read models and projections must derive from authoritative evidence. |
| §3A | **TRANSACTION LIFETIME AND PERIOD FINANCIAL TRUTH** | Stable transaction identity, lifecycle events, signed period effects, refunds, voids, corrections and consistent projections. |
| §4 | **MONEY AND DATABASE CORRECTNESS** | Minor-unit arithmetic, exactness and durable data rules. |
| §5 | **FINANCIAL OPERATION RELIABILITY** | Atomicity, idempotency, crash recovery, concurrency and retry safety. |
| §6 | **ARCHITECTURE AND OFFLINE-FIRST** | Repository boundaries, authoritative data flow and offline operation. |
| §7 | **SECURITY AND PRIVACY** | Authentication, permissions and secure access to data. |
| §8 | **TRUSTED SESSION — APPROVED PRODUCT SECURITY DECISION** | Authenticated session and PIN-change rules. |
| §10 | **PERFORMANCE, ACCESSIBILITY, LOCALIZATION** | Read-path performance and presentation boundaries where applicable. |
| §12 | **TESTING AND CHARACTERIZATION SAFETY** | Characterization before moving proven financial behavior; independent golden outputs. |
| §14 | **BOARDLOCK CHANGE CONTROL** | High-risk proposal/review/approval stop before implementation. |

§13 was read but is not mapped to these methods: it governs device/AVD test safety, and no device operation or device test is proposed in this phase.

## Other requested files over 1,500 lines

Line counts are from the audited `main` source at the commit above.

| File | Lines | Main responsibilities |
|---|---:|---|
| `feature/register/ui/RegisterViewModel.kt` | 3,501 | Register screen state and event coordination: sale draft/keypad/items, customer lookup, expense details/lifecycle, transaction details actions, refunds, voids, amount/payment corrections, coupons, credit and online sale flows. |
| `feature/register/ui/CouponCompactDialogR7A.kt` | 1,897 | Large Compose coupon dialog: customer/wallet selection, coupon purchase/redemption/top-up presentation, authorized-user actions and related messaging/formatting. |
| `feature/register/ui/TransactionDetailsScreen.kt` | 1,848 | Transaction lifetime UI: original sale, repayment, correction, payment-method change, refund, void, settlement and item sections, plus lifecycle actions. |
| `feature/customers/ui/CustomerDetailScreen.kt` | 1,660 | Customer profile/detail UI, financial and invoice history, credit/payment interactions and customer actions. |

## Function inventory

Coverage notation:
- `D:` a test file directly references the named function.
- `I:` no direct test reference was found for that function; the listed suite exercises it indirectly through its public caller or startup coordinator.
- `—` no direct or indirect test coverage was established by the searches.
- A fixture-only reference is explicitly not counted as behavioral coverage.

Test-key paths:

| Key | Existing test source |
|---|---|
| EXP | `app/src/androidTest/java/com/dailymoneyregister/ExpenseIntegrationTest.kt`; `ExpenseCode2ProjectionIntegrationTest.kt`; `ExpenseSupportingDocumentIntegrationTest.kt` |
| CPN | `app/src/androidTest/java/com/dailymoneyregister/CouponSaleIntegrationTest.kt` |
| SHIFT | `app/src/androidTest/java/com/dailymoneyregister/RegisterShiftFoundationTest.kt`; `RegisterShiftAuthorizationTest.kt` |
| CASH | `app/src/androidTest/java/com/dailymoneyregister/RegisterIntegrationTest.kt`; `CashSaleFinancialCharacterizationTest.kt` |
| CREDIT-SALE | `app/src/androidTest/java/com/dailymoneyregister/CreditSaleFinancialIntegrationTest.kt` |
| REPAY | `app/src/androidTest/java/com/dailymoneyregister/CreditRepaymentIntegrationTest.kt` |
| ONLINE | `app/src/androidTest/java/com/dailymoneyregister/OnlineSaleFinancialIntegrationTest.kt` |
| CORR | `app/src/androidTest/java/com/dailymoneyregister/AmountCorrectionFinancialCoreIntegrationTest.kt`; `app/src/test/java/com/dailymoneyregister/feature/register/data/AmountCorrectionCommandTest.kt` |
| PM | `app/src/androidTest/java/com/dailymoneyregister/PaymentMethodChangeWriterTest.kt`; `PaymentMethodChangeCommandTest.kt` |
| REF | `app/src/androidTest/java/com/dailymoneyregister/RefundFinancialCoreIntegrationTest.kt` |
| VOID | `app/src/androidTest/java/com/dailymoneyregister/VoidFinancialCoreIntegrationTest.kt` |
| TZ | `app/src/androidTest/java/com/dailymoneyregister/TimezoneIntegrationTest.kt` |
| VM-FIX | `app/src/test/java/com/dailymoneyregister/feature/register/ui/RegisterViewModelTestFactory.kt` (fixture reference only; not a behavior test) |

The group names/counts below are the body-based comparison buckets used for this audit. The previous audit's exact member roster was not available; do not interpret the matching bucket counts as proof that its original function membership was identical.

| Line | Function | Group | Governance sections | Existing test coverage |
|---:|---|---|---|---|
| 268 | `executeExpense` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: EXP |
| 273 | `executeExpenseLifecycle` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: EXP |
| 284 | `getExpensesForPeriod` | Reads | §3, §3A, §4, §6, §10, §12, §14 | D: EXP |
| 296 | `resolveFinancialCashShiftId` | Additional execution internals | §5, §6, §12, §14 | I: financial integration suites |
| 301 | `searchCouponSaleCustomers` | Reads | §3, §3A, §4, §6, §10, §12, §14 | D: CPN |
| 306 | `executeCouponRedemption` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: CPN |
| 332 | `redemptionEvent` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CPN |
| 338 | `couponCreditRemainder` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CPN (cash-remainder path only) |
| 345 | `redemptionEvidence` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CPN |
| 357 | `executeAdmittedCouponRedemption` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CPN |
| 389 | `executeCouponSale` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: CPN |
| 405 | `executeCouponTopUp` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | — |
| 417 | `executeAdmittedCouponTopUp` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | — |
| 425 | `executeCouponSaleRequest` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CPN |
| 445 | `executeAdmittedCouponSale` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CPN |
| 519 | `couponEvidenceId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: CPN |
| 522 | `loadCouponSaleEvidence` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CPN |
| 573 | `getCouponWalletDetails` | Reads | §3, §3A, §4, §6, §10, §12, §14 | D: CPN |
| 575 | `executeCouponAuthorizedUser` | Additional execution internals | §2, §5, §7, §8, §12, §14 | D: CPN |
| 593 | `authorizationEvent` | Additional execution internals | §2, §5, §7, §8, §12, §14 | I: CPN |
| 598 | `authorizationEvidence` | Additional execution internals | §2, §5, §7, §8, §12, §14 | I: CPN |
| 607 | `executeAdmittedCouponAuthorizedUser` | Additional execution internals | §2, §5, §7, §8, §12, §14 | I: CPN |
| 634 | `recoverPendingCouponSales` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: CPN |
| 672 | `openRegisterShift` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: SHIFT |
| 717 | `getCurrentRegisterShiftReport` | Reads | §3, §3A, §4, §6, §10, §12, §14 | D: EXP |
| 718 | `countRegisterShift` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: SHIFT |
| 783 | `closeRegisterShift` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: SHIFT |
| 859 | `authorizeNewRegisterShiftLifecycle` | Authorization | §5, §7, §8, §14 | I: SHIFT |
| 945 | `materializeRegisterShiftCount` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: SHIFT |
| 1004 | `materializeRegisterShiftClose` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: SHIFT |
| 1073 | `recoverPendingRegisterShifts` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: SHIFT |
| 1132 | `materializeRegisterShift` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: SHIFT |
| 1195 | `readRegisterShift` | Additional execution internals | §3, §4, §6, §12, §14 | I: SHIFT, EXP |
| 1236 | `authorizeNewFinancialMutation` | Authorization | §5, §7, §8, §14 | I: EXP, CPN, CASH, CREDIT-SALE, REPAY, ONLINE, CORR, PM, REF, VOID |
| 1261 | `findSaleCustomerByPhone` | Reads | §3, §3A, §4, §6, §10, §12, §14 | Fixture-only reference; no behavior test |
| 1270 | `resolveOptionalSaleCustomerId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: CASH, EXP |
| 1297 | `existingOptionalSaleCustomerId` | Additional execution internals | §3, §6, §7, §8, §12, §14 | I: CASH |
| 1306 | `executeCashSale` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: CASH |
| 1564 | `creditSaleIntent` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: CREDIT-SALE |
| 1585 | `creditSaleObligation` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: CREDIT-SALE |
| 1638 | `getCreditInvoiceDetails` | Reads | §3, §3A, §4, §6, §10, §12, §14 | — |
| 1645 | `loadCreditSaleEvidence` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CREDIT-SALE |
| 1690 | `executeCreditSale` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: CREDIT-SALE |
| 1746 | `executeAdmittedCreditSale` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CREDIT-SALE |
| 1859 | `couponCreditRepaymentEvent` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 1897 | `loadCouponCreditRepaymentEvidence` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 1957 | `executeCouponCreditRepayment` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: REPAY |
| 2029 | `executeAdmittedCouponCreditRepayment` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 2367 | `creditRepaymentCollection` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 2392 | `creditRepaymentRecord` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 2407 | `creditRepaymentLedgerEvent` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 2426 | `creditRepaymentAllocationId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 2441 | `creditRepaymentOutstandingObligations` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 2656 | `loadCreditRepaymentEvidence` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 2810 | `getCustomerCreditOutstanding` | Reads | §3, §3A, §4, §6, §10, §12, §14 | — |
| 2849 | `executeCreditRepayment` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: REPAY |
| 2964 | `executeAdmittedCreditRepayment` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REPAY |
| 3168 | `recoverPendingCreditRepayments` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: REPAY |
| 3247 | `executeOnlineSale` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: ONLINE |
| 3298 | `executeAdmittedOnlineSale` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: ONLINE |
| 3359 | `onlineFunding` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: ONLINE, REF, VOID |
| 3365 | `validateOnlineSale` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: ONLINE |
| 3397 | `executeAmountCorrection` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: CORR |
| 3500 | `prepareAmountCorrectionAdmission` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CORR |
| 3577 | `decodePersistedAmountCorrectionCommand` | Codecs | §3A, §5, §6, §12, §14 | I: CORR, AC-CMD |
| 3670 | `reconcilePendingAmountCorrection` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CORR |
| 3759 | `executeAdmittedAmountCorrection` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CORR |
| 3972 | `resolveAmountCorrectionReplay` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: CORR |
| 4200 | `recoverPendingAmountCorrections` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | I: CORR |
| 4335 | `executePaymentMethodChange` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: PM |
| 4391 | `preparePaymentMethodChangeAdmission` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: PM |
| 4437 | `decodePersistedPaymentMethodChangeCommand` | Codecs | §3A, §5, §6, §12, §14 | I: PM |
| 4488 | `reconcilePendingPaymentMethodChange` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: PM |
| 4535 | `executeAdmittedPaymentMethodChange` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: PM |
| 4665 | `resolvePaymentMethodChangeReplay` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: PM |
| 4775 | `recoverPendingPaymentMethodChanges` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: PM |
| 4835 | `executeRefund` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: REF, CPN |
| 4926 | `prepareRefundAdmission` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 4989 | `decodePersistedRefundCommand` | Codecs | §3A, §5, §6, §12, §14 | I: REF |
| 5084 | `reconcilePendingRefund` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 5171 | `executeAdmittedRefund` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 6195 | `resolveRefundReplay` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7132 | `buildCreditReturnB2Plan` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7405 | `creditReturnB2SettlementId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7415 | `creditReturnB2CouponEventId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7423 | `validateUnpaidCreditReturnLedger` | Financial helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7533 | `creditRefundAdjustmentId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7540 | `couponRefundEvidenceId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF, CPN |
| 7543 | `couponRefundWalletId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7546 | `couponRefundAuthorizedUserId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7555 | `couponRefundAuthorizedUserEventId` | Identity helpers | §2, §3A, §4, §5, §6, §12, §14 | I: REF |
| 7570 | `recoverPendingRefunds` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: REF |
| 7688 | `executeVoid` | Financial writes | §2, §3A, §4, §5, §6, §12, §14 | D: VOID |
| 7754 | `prepareVoidAdmission` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: VOID |
| 7806 | `decodePersistedVoidCommand` | Codecs | §3A, §5, §6, §12, §14 | I: VOID |
| 7856 | `reconcilePendingVoid` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: VOID |
| 7926 | `executeAdmittedVoid` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: VOID |
| 8074 | `resolveVoidReplay` | Additional execution internals | §2, §3A, §4, §5, §6, §12, §14 | I: VOID |
| 8215 | `recoverPendingVoids` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: VOID |
| 8312 | `recoverPendingCreditSales` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: CREDIT-SALE |
| 8369 | `recoverPendingCashSales` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: CASH |
| 8371 | `requireNoReconciliation` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | I: CASH |
| 8406 | `reconcile` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | I: CASH |
| 8527 | `recoverPendingOnlineSales` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | D: ONLINE |
| 8649 | `expectedRecoveryResult` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | I: CASH |
| 8679 | `recoveryMismatch` | Recovery | §2, §3A, §4, §5, §6, §12, §14 | I: CASH |
| 8693 | `observeRecentTransactions` | Reads | §3, §3A, §4, §6, §10, §12, §14 | — |
| 8697 | `observeTodayTransactions` | Reads | §3, §3A, §4, §6, §10, §12, §14 | D: TZ, EXP, REF, VOID |
| 8706 | `getFinancialTransactionById` | Reads | §3, §3A, §4, §6, §10, §12, §14 | D: CPN, ONLINE |
| 8713 | `getTransactionItems` | Reads | §3, §3A, §4, §6, §10, §12, §14 | — |
| 8734 | `getExpenseDetails` | Reads | §3, §3A, §4, §6, §10, §12, §14 | D: EXP |
| 8741 | `getTransactionDetails` | Reads | §3, §3A, §4, §6, §10, §12, §14 | — |

## Disagreements and body-based classification corrections

### Count/scope disagreement

- The seven reported prior groups total **68**, but the current file contains **112** declared functions. The **44 additional execution/admission/evidence internals** are listed as such in the inventory rather than silently omitted.
- Because the prior audit's member list was not recoverable, the 44 are a verified count discrepancy, not a claim that every one was necessarily absent from an earlier working spreadsheet. Exact member-level disagreement remains unverified until that roster is supplied.

### Important body findings

1. **Expense facade, not in-file writer** — lines 268 and 273: `executeExpense` and `executeExpenseLifecycle` delegate to `ExpenseFinancialRepository`. The actual expense writer and its evidence loader are outside this target file.
2. **Coupon Top-Up facade, not in-file writer** — lines 405 and 417: `executeCouponTopUp` and `executeAdmittedCouponTopUp` delegate to `CouponFinancialRepository`. This is still a financial write path, but moving its implementation from this file would be a false-positive extraction.
3. **Coupon search is a read facade** — line 301: `searchCouponSaleCustomers` delegates to `CouponFinancialRepository`; its body does not perform a financial write.
4. **Authorized-user lifecycle is a separate append-only identity write** — lines 575–633: `executeCouponAuthorizedUser`, `authorizationEvent`, `authorizationEvidence`, and `executeAdmittedCouponAuthorizedUser` create/verify authorized-user records and lifecycle events. They do not move money and should not be merged into a money-writing phase.
5. **Coupon recovery scope is broader than its name** — lines 634–671: `recoverPendingCouponSales` also selects pending coupon-redemption operations (the `REDEEM|` intent prefix), not only purchase operations.
6. **Shift materializers are writes; shift reader is not** — lines 945, 1004 and 1132 materialize count, close and open shift evidence. Line 1195 `readRegisterShift` reconstructs a shift result from persisted rows and is read-only.
7. **Customer identity resolution has two different effects** — line 1270 `resolveOptionalSaleCustomerId` trims inputs, rejects a name without a phone, returns null when both are absent, otherwise calls `CustomerIdentity.resolveOrCreate` and may create the customer within the caller's transaction. Line 1297 `existingOptionalSaleCustomerId` only looks up an existing identity; it does not create one.
8. **The four persisted-command decoders are boundary validators, not just codec calls** — lines 3577, 4437, 4989 and 7806. Each checks operation type, transaction subject identity, branch presence, schema version, reconstructed request fingerprint, and null result references for PENDING operations. The payload codecs are separate classes.
9. **Many helpers outside the reported eight-helper count participate in financial execution** — e.g. lines 332–388 (coupon event construction/evidence and admitted write), 1645–1858 (credit sale evidence and admitted write), 1897–2028 (coupon repayment evidence and admitted write), 3298–3396 (online admitted write/funding/validation), and 3500–7131 (lifecycle admission, reconciliation, write and replay). They must be assigned with their owning flow during implementation, not treated as unrelated utilities.

## Characterization coverage and gaps

| Financial write / recovery path | Current lock-down | Required characterization before extraction |
|---|---|---|
| Expense create/correct/void | Good integration coverage in EXP for exact expense rows, authorization, idempotency, conflict, rollback, and append-only lifecycle events. Implementation is already in `ExpenseFinancialRepository`. | Keep the existing tests unchanged; add no duplicate writer. Confirm report/period projection tests remain green when that repository is later touched. |
| Coupon purchase | Good but bounded CPN coverage: exact wallet/funding, replay, conflict, concurrency, invalid input rollback, permissions, pending recovery and malformed pending intent. | Preserve existing evidence and exact customer/wallet/user audit assertions. |
| Coupon Top-Up | **Gap**: no test reference to `executeCouponTopUp` or its facade was found in `app/src/test` or `app/src/androidTest`. | Add characterization for initial/top-up balance, ledger event identity, funding classification, retry/altered payload, concurrent duplicate, failure rollback, pending recovery and malformed durable intent before moving it. |
| Coupon redemption | Partial CPN coverage: one partial redemption success path plus coupon refund/read-model behavior. | Add tests for replay, altered same-operation intent, concurrent redemption, insufficient balance, CREDIT remainder (including exact credit obligation), failure rollback, and pending recovery. Existing test uses the CASH remainder path only. |
| Coupon authorized users | CPN proves add/edit/deactivate events are append-only and reflected in wallet details. | Add retry/idempotency, changed-intent conflict, authorization rejection, and partial-write rollback/recovery characterization before moving the identity lifecycle. |
| Register shift open/count/close | SHIFT covers durable/idempotent opening and pending opening recovery. Count and close are exercised together in one admin test. | Add separate count/close replay and altered-intent tests, exact event/count evidence, ownership/authorization matrix, duplicate/concurrent submission, failure rollback, and pending count/close recovery. |
| Cash sale | Strong CASH characterization: exact minor-unit results, input rejection, idempotency/concurrency, durable payload conflict, existing evidence validation, crash recovery and fail-closed reconciliation. | Preserve the complete matrix and independently frozen expected financial rows. |
| Credit sale | Good CREDIT-SALE coverage: exact obligation/customer identity, replay/conflict, durable payload round-trip and pending recovery. | Preserve original transaction identity and exact credit ledger/settlement evidence; add malformed pending-payload coverage if not covered by the current fixture set. |
| Credit repayment / coupon credit repayment | Strong REPAY coverage: exact collections, FIFO allocation, payoff, overpayment rejection, replay, recovery, coupon debit, authorization snapshot and atomic rollback. | Preserve exact FIFO allocation and the equality between ledger outstanding and allocatable outstanding. |
| Online sale | Good ONLINE coverage: exact manual-QR funding, items/tax/shipping, idempotency/concurrency, permission/business/branch rejection, valid recovery and malformed-payload fail-closed. | Preserve exact funding category/channel/confirmation and all recovery outcomes. |
| Amount correction | Good CORR coverage: positive/negative deltas, append-only chains, replay/conflict, invalid below-refund state and startup recovery exactly once. | Preserve the exact delta and financial precondition snapshot; keep expected values independent of the production encoder/decoder. |
| Payment-method change | Good PM coverage: balanced reclassification pair, original funding preservation, repeated change history, replay/conflict, authorization, admitted pending recovery, changed-state/missing-precondition/malformed-intent reconciliation and DB immutability guards. | Preserve exact old/new category/channel, unchanged transaction economic value, exact balanced pair, and pending-vs-new authorization distinction. |
| Refund | Strong REF coverage: partial/full, cumulative limit, funding destination, unpaid credit, coupon and partially paid credit return, rollback, concurrency, idempotency and pending recovery after permission revocation. | Preserve all B1/B2 paths and the invariant that unpaid credit reduction creates no cash/online/coupon outflow. |
| Void | Strong VOID coverage: remaining-value reversal after refunds/corrections, no double reversal, fail-closed malformed history, rollback, concurrency, replay, legacy pending/succeeded handling and recovery. | Preserve all reversal amount/funding/identity checks and fail-closed behavior. |

## Codec and identity contracts

### Four persisted-command decode adapters

The following behavior must remain byte-for-byte/semantically compatible with already-persisted operations:

| Function | Payload fields reconstructed | Existing tests relevant to compatibility |
|---|---|---|
| `decodePersistedAmountCorrectionCommand` (3577) | `correctedAmountMinor`, `reason`, `financialPrecondition`; operation metadata supplies operation/business/branch/subject/actor/device/time. | CORR and AC-CMD. AC-CMD tests semantic round-trip using the production encoder; it is not an independent frozen payload string. |
| `decodePersistedPaymentMethodChangeCommand` (4437) | `newCategory`, `newChannel`, `reason`, `financialPrecondition`; operation metadata supplies the durable identity fields. | PM. Tests semantic round-trip and malformed/unknown payload rejection; no independent golden string was identified. |
| `decodePersistedRefundCommand` (4989) | `refundAmountMinor`, destination category/channel, reason, financial precondition and coupon destination. | REF exercises durable replay/recovery decode paths; no independent golden payload output was identified. |
| `decodePersistedVoidCommand` (7806) | `reason` and financial precondition; operation metadata supplies the durable identity fields. | VOID exercises durable replay/recovery decode paths; no independent golden payload output was identified. |

All four adapters also enforce: correct `OperationType`; `SubjectType.TRANSACTION`; `transactionId == subjectId`; non-null branch; codec schema version equal to operation schema version; reconstructed request fingerprint equal to the persisted fingerprint; and no result references on PENDING operations. On malformed PENDING intents, the corresponding flow moves to reconciliation rather than guessing from current mutable state. These checks and the rule that existing admitted work is not re-authorized must remain unchanged.

### Identity helpers

The 10-function identity bucket used for this comparison is:

- `resolveOptionalSaleCustomerId` (1270): trims phone/name; no phone + no name returns null; name without phone rejects; otherwise delegates to `CustomerIdentity.resolveOrCreate` using business, raw normalized inputs and `occurredAt`, then returns the universal customer ID.
- `couponEvidenceId` (519): UUID name from UTF-8 bytes of `coupon:<operationId>:<suffix>`; suffixes used here include `purchase`, `user:<index>`, and `user-event:<index>`.
- `creditRepaymentAllocationId` (2426): UUID name from `credit-repayment:<operationId>:<transactionId>`.
- `creditReturnB2SettlementId` (7405): UUID name from `credit-return-b2:<operationId>:settlement:<kind>:<index>`.
- `creditReturnB2CouponEventId` (7415): UUID name from `credit-return-b2:<operationId>:coupon:<index>`.
- `creditRefundAdjustmentId` (7533): UUID name from `credit-return:<operationId>`.
- `couponRefundEvidenceId` (7540): UUID name from `refund-to-coupon:<operationId>`.
- `couponRefundWalletId` (7543): UUID name from `refund-to-coupon-wallet:<operationId>`.
- `couponRefundAuthorizedUserId` (7546): UUID name from `refund-to-coupon-user:<operationId>:<index>`.
- `couponRefundAuthorizedUserEventId` (7555): UUID name from `refund-to-coupon-user-event:<operationId>:<index>`.

For all UUID helpers, preserve the exact namespace text, punctuation, operation ID, suffix/index, UTF-8 byte encoding, `UUID.nameUUIDFromBytes` algorithm and canonical `UUID.toString()` output. Do not replace with random IDs, a different namespace, a different hash, normalized input, or a new ID scheme. The helper `existingOptionalSaleCustomerId` (1297) is an additional read-only identity lookup outside this 10-function bucket.

No test reference was found that asserts the exact output of these deterministic ID helpers. Existing financial tests assert evidence count/linkage and replay behavior, but that is not a frozen ID-format contract. Before moving these functions, add tests with independently frozen literal expected IDs for representative and indexed cases; do not compute expected values by calling the production helpers.

The current codec tests mostly verify semantic round-trips and invalid payload rejection. Before moving codecs, add independent golden payload strings and expected decoded fields for all four schemas. Do not generate expected payloads with the production encoder under test, per §12.

## Proposed target structure and phase plan

The repository already contains these useful boundaries; retain and extend them rather than duplicating them:
- `ExpenseFinancialRepository.kt` — expense create and lifecycle writes.
- `CouponFinancialRepository.kt` — coupon customer search and coupon top-up implementation.
- `FinancialMutationAuthorizer.kt` and `FinancialCashShiftResolver.kt` — new-operation authorization and cash-shift attribution.
- `TransactionDetailsReadRepository.kt`, `ExpenseReadRepository.kt`, `CouponWalletReadRepository.kt`, `CreditInvoiceReadRepository.kt`, `RegisterCustomerReadRepository.kt`, `RecentFinancialActivityReadRepository.kt`, and `RegisterShiftReportReadRepository.kt` — existing read boundaries.

| Phase | Target file(s) | Functions / responsibility | Risk |
|---|---|---|---|
| 1 — Reads first | Extend existing `CreditInvoiceReadRepository.kt`, `RecentFinancialActivityReadRepository.kt`, and `RegisterShiftReportReadRepository.kt`. Keep public wrappers in `RegisterRepository.kt`. | Move `getCustomerCreditOutstanding` to Credit Invoice reads; move `observeRecentTransactions` only after confirming its consumers and whether it is a raw legacy feed; move `readRegisterShift` into the shift-report reader. The other read wrappers already delegate to dedicated read repositories and should remain API-compatible. | **Low–medium** |
| 2 — Identity support | New `RegisterFinancialIdentityIds.kt`; optionally `SaleCustomerIdentityResolver.kt`. | Move the 9 deterministic UUID helpers and `resolveOptionalSaleCustomerId`; keep `existingOptionalSaleCustomerId` with `RegisterCustomerReadRepository`. Freeze every output first. | **High** |
| 3 — Authorization | Reuse `FinancialMutationAuthorizer.kt`; new `RegisterShiftLifecycleAuthorizer.kt` only if a distinct class is justified. | Keep `authorizeNewFinancialMutation` delegation; move `authorizeNewRegisterShiftLifecycle` without changing session, business/branch, active-user, role, membership, shift-owner or PIN-change rules. | **High** |
| 4 — Durable command decoding | New `PersistedFinancialCommandDecoders.kt`. | Move the four `decodePersisted*Command` adapters and preserve every identity/schema/fingerprint/PENDING-reference check. | **High** |
| 5 — Recovery | New `RegisterRecoveryRepository.kt`, with domain-specific recovery collaborators if needed. | Move the 10 recovery entrypoints plus `requireNoReconciliation`, `reconcile`, `expectedRecoveryResult`, `recoveryMismatch`; then move each lifecycle replay resolver with its owning operation. | **Critical** |
| 6 — Financial helpers | New domain-specific helper files, not one generic utility file: `CreditRepaymentFinancialSupport.kt`, `CreditReturnPlanning.kt`, and coupon/sale evidence support as justified. | Move the eight pure/event/planning helpers in the inventory plus currently additional event builders, evidence verifiers, admission-precondition builders and validators with their owning writer. | **Critical** |
| 7a — Shift writes | New `RegisterShiftFinancialRepository.kt`. | `openRegisterShift`, `countRegisterShift`, `closeRegisterShift`, `authorizeNewRegisterShiftLifecycle`, `materializeRegisterShift`, `materializeRegisterShiftCount`, `materializeRegisterShiftClose`; recovery stays in Phase 5. | **Critical** |
| 7b — Coupon writes | Extend existing `CouponFinancialRepository.kt` or create `CouponSaleFinancialRepository.kt` after dependency review. | `executeCouponSale`, `executeCouponRedemption`, `executeCouponAuthorizedUser` and their admitted/evidence/event helpers. Top-Up already delegates to the existing coupon repository. | **Critical** |
| 7c — Sales | New `CashSaleFinancialRepository.kt`, `CreditSaleFinancialRepository.kt`, and `OnlineSaleFinancialRepository.kt`. | Cash, credit and online sale entrypoints plus their admitted writers, validators, evidence and recovery adapters (recovery implementation in Phase 5). | **Critical** |
| 7d — Repayments | New `CreditRepaymentFinancialRepository.kt`. | Cash/online credit repayment and coupon credit repayment, their collection/record/ledger/allocation helpers and evidence reconstruction. | **Critical** |
| 7e — Amount correction | New `AmountCorrectionFinancialRepository.kt`. | Amount correction admission, admitted writer and replay resolver; decoder/recovery move in Phases 4–5. | **Critical** |
| 7f — Payment-method change | New `PaymentMethodChangeFinancialRepository.kt`. | Payment-method-change admission, admitted writer and replay resolver; decoder/recovery move in Phases 4–5. | **Critical** |
| 7g — Refund | New `RefundFinancialRepository.kt`. | Refund admission, admitted writer, replay resolver, Credit Return B2 plan/validation and refund-specific IDs; recovery in Phase 5. | **Critical** |
| 7h — Void | New `VoidFinancialRepository.kt`. | Void admission, admitted writer and replay resolver; recovery in Phase 5. | **Critical** |
| Existing expense path | Keep `ExpenseFinancialRepository.kt`; do not create another expense writer. | Existing `executeExpense` and `executeExpenseLifecycle` facade methods remain in `RegisterRepository.kt` unless a later approved API-facade cleanup says otherwise. | **High** if changed |

Financial writes are deliberately last and separated by domain. Every high/critical phase requires its own proposal, human approval under §14, characterization safety net, bounded extraction, focused tests, and a separate review. No implementation is authorized by this audit.

## Open questions for human review

1. Can the earlier 68-function audit roster be supplied, or should the 112-function inventory here become the authoritative baseline?
2. Is `observeRecentTransactions` still a required raw-transaction feed, or can it be consolidated with the authoritative recent-activity read model after consumer review?
3. Should `executeCouponAuthorizedUser` be governed as its own append-only identity/authorization lifecycle, separate from financial writes?
4. Is Coupon Top-Up currently in active product scope? If yes, who owns the missing create/retry/recovery characterization before any move?
5. Should count/close shift recovery and replay be required before moving the shift writer?
6. Are there approved golden payload strings and deterministic ID outputs outside the current test tree? None were found in the inspected tests.
7. Confirm that public `RegisterRepository` method signatures remain stable as a compatibility facade throughout the extraction.
8. Confirm whether domain-specific recovery files are preferred over one recovery repository; the current recovery functions have materially different transaction and reconciliation rules.

## Stop condition

Phase 1 audit and proposal only. No application code or tests were changed, no test/device/emulator command was run, and no PR was opened. Stop here for human review. Do not begin Phase 2 or any extraction until explicit approval under PROJECT_GOVERNANCE.md §14.
