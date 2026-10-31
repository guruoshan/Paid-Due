# RegisterRepository Financial Characterization Report

## Scope and baseline

- Repository: `guruoshan/Paid-Due`
- Base: `main`
- Audited base SHA: `e5f4dd915652624c909a26641a9a1a4f5ddf0fb8`
- Target: `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterRepository.kt`
- Target size at baseline: 8,757 lines
- Change policy: test-only; no production logic, schema, migration, API, or Supabase changes.

This report maps the existing test inventory and the new cash-sale characterization cases added by this PR. It is a functional coverage inventory, not a JaCoCo/Kover line-coverage report. The current app Gradle configuration does not declare a coverage plugin, and no numerical line/branch coverage percentage is claimed.

## Executive result

The repository's responsibilities are broader than its current class boundary. The file is a compatibility façade and still contains substantial command execution, admission, recovery, reconciliation, and read responsibilities. Some responsibilities have already been extracted to collaborators:

- Coupon top-up and selected coupon workflows: `CouponFinancialRepository`
- Expense writes and lifecycle: `ExpenseFinancialRepository`
- Expense reads: `ExpenseReadRepository`
- Transaction details reads: `TransactionDetailsReadRepository`
- Shift attribution and financial authorization: `FinancialCashShiftResolver` and `FinancialMutationAuthorizer`
- Customer reads: `RegisterCustomerReadRepository`

The existing test inventory is stronger than a filename-only scan initially suggests. In particular, refunds, voids, repayments, payment-method changes, and expense workflows have extensive real-Repository integration tests using isolated in-memory Room databases.

This PR adds three real-Repository cash-sale tests:
1. Identical replay is idempotent and a changed request under the same operation ID conflicts.
2. Currency mismatch is rejected before financial rows are written.
3. An unauthenticated cash-sale request is denied without persistence.

No production code is changed.

## Responsibility map

| Responsibility | Current owner / entry points | Existing evidence |
|---|---|---|
| Cash sales | `RegisterRepository.executeCashSale`, `recoverPendingCashSales` | `CashSaleFinancialCharacterizationTest`, `CashSaleCommandTest` |
| Credit sales | `executeCreditSale`, `recoverPendingCreditSales` | `CreditSaleFinancialIntegrationTest` |
| Refunds | `executeRefund`, `recoverPendingRefunds` | `RefundFinancialCoreIntegrationTest` |
| Voids | `executeVoid`, `recoverPendingVoids` | `VoidFinancialCoreIntegrationTest`, `VoidFinancialIntegrityTest` |
| Amount corrections | `executeAmountCorrection`, `recoverPendingAmountCorrections` | `AmountCorrectionFinancialCoreIntegrationTest`, `AmountCorrectionCommandTest`, `AmountCorrectionCouponSplitTest` |
| Payment-method changes | `executePaymentMethodChange`, `recoverPendingPaymentMethodChanges` | `PaymentMethodChangeWriterTest`, `PaymentMethodChangeCommandTest` |
| Expenses | façade delegates to `ExpenseFinancialRepository`; lifecycle, correction, void | `ExpenseIntegrationTest`, `ExpenseCode2ProjectionIntegrationTest`, `ExpenseSupportingDocumentIntegrationTest`, `ExpenseCommandFactoryTest`, `ExpenseReadProjectionTest` |
| Coupon sales | `CouponFinancialRepository` and RegisterRepository coupon command paths | `CouponSaleIntegrationTest`, `CouponSaleCommandTest`, `CouponSaleViewModelTest` |
| Coupon top-ups | `CouponFinancialRepository.executeCouponTopUp` | `CouponTopUpCharacterizationTest`, `CouponSaleCommandTest` |
| Coupon redemption | `RegisterRepository.executeCouponRedemption` | Partial redemption, coupon refund and read-model assertions in `CouponSaleIntegrationTest` |
| Coupon repayments | `executeCouponCreditRepayment` and credit repayment paths | `CreditRepaymentIntegrationTest` |
| Cash-shift operations | `openRegisterShift`, `countRegisterShift`, `closeRegisterShift` | `RegisterShiftFoundationTest`, `RegisterShiftAuthorizationTest`, `RegisterShiftLifecycleRecoveryCharacterizationTest` |
| Recovery workflows | Per-operation `recoverPending…` methods | Cash sale, credit sale, refund, void, correction, payment change, expense, coupon top-up, credit repayment, and shift recovery tests |
| Reconciliation workflows | Pending-intent validation and `REQUIRES_RECONCILIATION` transitions | Void, refund, amount-correction, payment-method-change, and shift recovery scenarios |
| Sync-related workflows | Repository durable operations/outbox integration; sync engine and inbound materializer are adjacent modules | `FinancialSyncEngineTest`, `RemoteEnvelopeMaterializerTest`, `FinancialSyncOutboxV13Test` |

## Existing test inventory and test matrix

Counts below are the number of `@Test` methods in the named source file(s) inspected at the audited base. They are not line-coverage percentages.

| Area | Existing tests inspected | Coverage demonstrated |
|---|---:|---|
| Cash sale | 5 characterization + 5 command/codec tests | Normal V3 golden snapshot, V1/V2/V3 durable-intent recovery, database reopen recovery, outbox queued exactly once, codec/fingerprint compatibility |
| Credit sale | 7 integration tests | Customer creation/reuse, durable replay, changed-intent conflict, payload round trip, pending recovery |
| Refund | 38 integration tests | Partial/full refunds, over-refund rejection, funding identity, credit and coupon restrictions, concurrent operations, rollback, replay, recovery, B2B return math |
| Void | 28 integration tests + 4 integrity tests | Exact reversal, partial/full refund interactions, corrupt history rejection, rollback, replay, concurrency, pending recovery, reconciliation, database immutability |
| Amount correction | 8 integration + 3 command + 2 coupon split tests | Increase/decrease, append-only chain, replay/conflict, below-refunded rejection, credit adjustment, recovery |
| Payment-method change | 11 writer + 10 command tests | Balanced reclassification pair, original funding preservation, replay/conflict, chronological append, permission/session, recovery, stale-state reconciliation |
| Expenses | 17 integration tests plus projection/factory/supporting-document suites | Cash/online classification, replay/conflict, authorization, customer identity, rollback, correction/void lifecycle, projection |
| Coupon sale | 12 integration tests + 5 command/codec tests | Cash/online funding, concurrency, identity, currency/phone failure rollback, authorization, recovery, partial redemption and coupon refund |
| Coupon top-up | 4 characterization tests | Wallet/ledger balance, replay/conflict, rollback, durable recovery |
| Credit and coupon repayment | 21 integration tests | Cash/online/coupon funding, FIFO, exact payoff, overpayment rejection, replay, wallet ownership, rollback, recovery |
| Cash shifts | 4 foundation + 1 authorization + 2 lifecycle recovery tests; 5 accounting projection tests | Open/count/close, independent shifts, authorization, replay, recovery, expected-cash classification |
| Sync / inbound materialization | Dedicated engine and materializer suites; outbox migration test | Outbox policy, acknowledgement, inbound identity/validation and operation materialization |

### New tests in this PR

File: `app/src/androidTest/java/com/dailymoneyregister/CashSaleFinancialCharacterizationTest.kt`

| New test | Invariant documented |
|---|---|
| `identicalCashSaleReplayIsIdempotentAndChangedIntentConflicts` | Same operation ID + same intent produces one sale; same ID + changed fingerprint is rejected; transaction, item and settlement evidence is not duplicated |
| `invalidCashSaleCurrencyFailsBeforeAnyFinancialWrite` | Branch currency mismatch returns an error before transaction, item, settlement, or operation persistence |
| `cashSaleWithoutAuthenticatedSessionPersistsNothing` | Caller-supplied actor/membership data cannot replace an authenticated session; no financial rows are persisted |

## Financial invariant matrix

| Invariant | Current test evidence | Assessment |
|---|---|---|
| Accounting totals | Sale funding, refund outflows, void reversals, expense outflows, credit ledger and repayment tests | Strong across major paths; add cross-operation end-to-end totals where flows interact |
| Shift balances | Shift day-closing accounting tests; expense and financial operation shift attribution tests | Good targeted coverage; more real-repository tests combining sale/refund/expense/reclassification in one shift would improve confidence |
| Refund math | 38 refund integration tests, including unpaid-credit and paid-value boundary cases | Strong |
| Coupon balances | Coupon sale, top-up, redemption, refund-to-coupon, and coupon repayment tests | Strong for core paths; additional malformed-wallet and concurrent redemption edge cases may be useful |
| Recovery results | Per-operation recovery tests, including durable reopen/retry scenarios | Good but uneven by operation family; document unsupported/legacy recovery states explicitly |
| Idempotency | Replay tests for cash/credit sales, refunds, voids, amount correction, payment change, expenses, coupon operations, repayments, shifts | Strong |
| Collision handling | Changed-intent conflicts and durable-payload mismatch checks in operation and financial integration tests | Strong for tested command families; do not equate every fingerprint conflict with a server-side `OPERATION_COLLISION` audit event |
| Transaction rollback | Refund settlement failure, void settlement failure, expense materialization, coupon sale/top-up and repayment failure injection | Good targeted coverage; cash-sale writer failure after first child insert is a remaining explicit gap |
| Database immutability | Void and payment-method-change database guard tests | Strong for covered tables and operations |

## Coverage gaps and risk assessment

### High priority

1. **Cash-sale atomic rollback under an injected mid-materialization failure.** Current cash-sale characterization covers success, durable recovery, replay, changed-intent conflict, authentication denial, and pre-write currency rejection. It does not explicitly inject a failure after a transaction or item insert and prove the entire sale rolls back.
2. **Cash-sale financial edge matrix.** Add golden assertions for tax-enabled sales, multiple items, shipping/packing, and item-level charges. Existing tests cover the baseline payload and command encoding, but the full calculation matrix is not represented by the current characterization file.
3. **Cross-operation shift totals.** The shift accounting unit tests cover classification rules, but a single real Room scenario combining sales, refunds, expenses, and payment reclassification would provide a stronger end-to-end expected-cash assertion.
4. **Coupon redemption isolation.** Redemption is exercised through a coupon sale integration scenario; a dedicated direct redemption test class would make authorization, insufficient balance, replay, and rollback ownership easier to see.
5. **Repository-wide test traceability.** Some tests exercise collaborators or command codecs rather than the real RegisterRepository entry point. Keep those distinctions explicit in future test names and reports.

### Medium priority

- Explicit tests for cash-sale duplicate item IDs, zero/negative values, disabled-tax inconsistencies, and negative charges at the repository boundary.
- Additional credit-sale rollback injection at each financial child-write boundary.
- Broader shift-close report integration for mixed operation histories.
- Explicit verification that each recovery method filters only its own operation types and leaves unrelated pending operations untouched.
- A single responsibility-to-test index maintained alongside future repository extraction PRs.

### Coverage measurement limitation

The app build configuration inspected for this report does not declare JaCoCo or Kover. Therefore this PR reports test inventory and behavioral scenario coverage, not numerical line, branch, or function coverage. CI pass/fail is required before claiming the new tests execute successfully.

## Recommended RegisterRepository decomposition Pass 1

The source file is already a façade for several extracted collaborators. Avoid re-extracting those responsibilities or creating a parallel architecture.

Recommended first slice:

**Extract the remaining read-only transaction/register query surface into a dedicated collaborator only where the current method bodies are still implemented in RegisterRepository.**

Candidate public reads include recent transactions, today transactions, transaction lookup, transaction items, and any remaining transaction detail projection methods. Keep the public RegisterRepository methods as forwarding façade methods so callers and API behavior do not change.

Why this slice:
- Read-only and lower risk than financial writers.
- Clear separation from command admission and financial mutation.
- No changes to operation IDs, fingerprints, settlement math, recovery, or sync.
- Easier to characterize with query-result parity tests.

Before that extraction, add the remaining cash-sale rollback and edge-case tests listed above. Do not begin with refund/void/amount-correction decomposition; those workflows have the greatest financial coupling and should follow only after a dedicated, tested boundary is selected.

## Change and safety statement

- Production source files changed: **0**
- Schema / migration changes: **0**
- Supabase changes: **0**
- API contract changes: **0**
- New tests: **3**
- Existing tests modified: **0**
- Runtime behavior intentionally changed: **none**

Build and test status must be taken from the PR's actual CI runs. This report does not claim tests passed until the workflow has completed successfully.
