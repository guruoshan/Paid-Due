# Money Rules — financial audit (consolidated, Step 7)

## 0. Scope, baseline and method

- **Code baseline:** branch `audit/release-readiness-2026-10-25`, commit `9c2deb779b872acd7e01b3e49a3c73481a150d2e`. Paths below are repo-relative under `app/src/main/java/com/dailymoneyregister/` unless shown otherwise.
- **Live database:** Supabase project `ttvdpgxqvvtjnxxfkoui`, queried with SELECT only on 2026-10-26. No live writes.
- **Method:** source read directly from a clone. Line numbers are as read in that clone. Anything not re-read in this pass is marked **NOT RE-VERIFIED** and cites the earlier audit pass.
- **Excluded by design:** security findings (privileges, SECURITY DEFINER use, RLS, access checks). Reported separately in chat; not in this document.
- **Retracted in this revision:** every earlier statement that contradicts the findings below, specifically: "no OPEN/COUNT/CLOSE tests" (tests exist), "opening float NOT FOUND" (resolved: excluded), "server function NOT FOUND" (resolved: live only), "13 migrations missing from repo" (corrected: 2 missing, 11 renamed or re-timestamped), "amount correction is the fix for a wrong credit or coupon amount" (wrong: rejected).

## 1. Final rules table

Amounts in minor units. "Period" = selected report range. Cash/Online = actual tender parts.

| Operation | Gross Sales | Net Sales activity | Cash / Online | Credit Outstanding (ledger) | Coupon balance | Expected cash (Day Closing) |
|---|---|---|---|---|---|---|
| Sale, `RECORD_SALE` | + original amount | + original amount | + Cash/Online funding parts only | + credit-funded part | − coupon-funded part | + Cash funding part |
| Credit repayment, `RECORD_CREDIT_REPAYMENT` | 0 | 0 | + actual Cash/Online repayment | − allocated to debt | − if coupon pays | + Cash repayment part |
| Coupon purchase/top-up, `RECORD_COUPON_PURCHASE` | not in merchandise inflow | reported separately | + actual Cash/Online funding only | 0 | + purchased value | + Cash funding only |
| Coupon redemption (ledger event, part of a sale) | in the underlying sale | no extra effect | Cash/Online remainder is part of the sale | per the sale's credit part | − redeemed value | + Cash remainder only |
| Coupon promotion bonus (ledger type) | 0 | 0 | 0 | 0 | + bonus value **(no code path issues it, see §5)** | 0 |
| Refund, `RECORD_REFUND` | unchanged | − refund amount, refund's period | − actual Cash/Online payout parts | unpaid-credit return reduces debt with no payout | + only refund-to-coupon | − Cash payout only |
| Void, `RECORD_VOID` | unchanged | − reversed amount, void's period | − supported Cash/Online reversal | **rejected for credit sales** | **rejected for coupon sales** | − Cash reversal only |
| Amount correction, `RECORD_AMOUNT_CORRECTION` | unchanged (original) | ± signed delta, correction's period | ± delta on the sale's Cash/Online identity | not supported | not supported | ± Cash delta |
| Payment-method change, `RECORD_PAYMENT_METHOD_CHANGE` | 0 | 0 | Cash ↔ Online reclassification, sum unchanged | 0 | not supported | net change = Cash side only |
| Expense, `RECORD_EXPENSE` | 0 | 0 | − actual Cash/Online | 0 | 0 | − Cash expense only |
| Expense correction / void | 0 | 0 | signed lifecycle delta in event's period | 0 | 0 | Cash lifecycle delta only |
| `OPEN_CASH_SHIFT` | 0 | 0 | stored as shift evidence, not a receipt | 0 | 0 | **not included** |
| `COUNT_CASH_SHIFT` | 0 | 0 | counted cash, not a receipt | 0 | 0 | none; compared with expected |
| `CLOSE_CASH_SHIFT` | 0 | 0 | shift-close snapshot | 0 | 0 | none |

**Expected cash formula** (`feature/register/data/RegisterShiftReportReadRepository.kt`, function `registerShiftExpectedCashMinor`, approx. lines 39–62):
Cash sales + Cash credit repayments + Cash coupon funding + Cash lifecycle adjustments − Cash refunds − Cash expenses. **The opening count is not an input.**

**Credit Outstanding has two definitions on two screens:**
- Reports and PDF: `max(0, period credit sales − period repayments)` (`feature/reporting/data/ReportingAggregator.kt` ~255–264). Production passes no `creditOutstanding` argument: the only production call is `feature/reporting/data/ReportingReadRepository.kt:480`.
- Customer Detail: all-time signed ledger sum (`core/database/dao/LedgerDao.kt:118`, `SUM(amount_signed_minor)` over all credit ledger rows for the customer and currency; read through `feature/register/data/RegisterRepository.kt` ~2761–2798, `getCustomerCreditOutstanding`; filled into UI state at `feature/customers/ui/CustomersViewModel.kt` ~334–363 and replaced after repayment at ~703).

## 2. Confirmed behaviours

### 2.1 Credit Outstanding (traced, not sampled)

Callers of `ReportingAggregator.build`: one production caller (`ReportingReadRepository.kt:480`), which omits `creditOutstanding`. Reports and PDF therefore always use the period fallback. PDF reads the already-loaded summary and does not recompute (`feature/reporting/pdf/ReportingPdfActions.kt:105–155`, `creditOutstanding` at line 151).

Scenario results (OMR, one customer, one credit sale of 3.000 on Day 1):

| Scenario | Reports / PDF | Customer Detail |
|---|---|---|
| Day 2 PDF, no activity | 0.000 | 3.000 |
| Day 3 cash repayment 3.000; Day 3 PDF | 0.000 (0 − 3, floored) | 0.000 |
| Range Day 1–3 | 0.000 (3 − 3) | 0.000 |
| Range Day 1–2 | 3.000 | 3.000 |
| Day 1 sale 3.000, Day 2 repayment 1.000; Day 2-only report | **0.000** (0 − 1, floored) | **2.000** |

The last row is a defect: the floor hides an outstanding debt whenever a repayment falls inside a range that does not include the sale.

### 2.2 Expected cash and opening float (traced)

- `OPEN_CASH_SHIFT` writes an `OPENED` event (`RegisterRepository.kt` ~1164–1185). The opening count is stored as evidence and is **not** an input to `registerShiftExpectedCashMinor` (§1).
- Example: opening 20.000, cash sale 5.000, counted 25.000 → expected 5.000, counted 25.000, difference +20.000, **OVER**. The opening count is not in the expected figure.
- Difference is counted minus expected: `feature/register/ui/DayClosingPresentation.kt` ~32–60 (**NOT RE-VERIFIED** in this pass; cited from the earlier audit).
- Cash already in the drawer at open is therefore reported as an unexplained OVER on every shift that starts with a float.

### 2.3 Void (`feature/register/data/VoidFinancialIntegrity.kt`, `validate`, approx. lines 151–206)

Rejected, with the exception thrown:
- a void already exists;
- a credit repayment allocation exists;
- the transaction is a coupon purchase, or any settlement is coupon (`VoidUnsupportedCouponException`);
- **the sale has any payment-method change** (`VoidAmbiguousSettlementException`, line ~172);
- sale funding is missing, mixed, or has more than one channel;
- sale funding is credit (`VoidUnsupportedCreditException`) or coupon.

Eligibility: `feature/register/data/TransactionActionEligibilityResolver.kt` ~95–105 runs the validator and returns false on any exception. `feature/register/ui/TransactionDetailsScreen.kt` ~250–252 and ~517 set the Void button to `enabled = canVoid`.

**What the user sees:** a disabled Void button with no explanation. The only void message is `tx_void_failed` (`app/src/main/res/values/strings.xml:326`, "The transaction could not be voided. Review its current lifecycle state."), shown only after a submit fails. **The message is not clear for either credit or coupon sales. This is confirmed, not an open question.**

### 2.4 Refund (`feature/register/data/RefundFinancialIntegrity.kt`)

- Dispatch (~117–160): credit, coupon, and ambiguous-void exceptions route to credit or coupon validators depending on the destination.
- Credit sale with no repayments: the validator accepts CREDIT, CASH, ONLINE, or COUPON as destination. The repository emits **no external settlement** for an unpaid credit return (`feature/register/data/RegisterRepository.kt` ~5636–5652). The destination choice has no monetary effect. The UI still offers cash, which is misleading.
- Credit sale with repayments: payout limited to `paidValueRefundMinor` (`RegisterRepository.kt` ~5794–5828).
- Credit sale with a payment-method change: rejected as ambiguous (`RefundFinancialIntegrity.kt` line 213).
- Coupon sale: refund-to-coupon only; outflow must equal the refunded amount (`validateCouponFunded`, ~329–359; line 335 rejects payment-method changes).
- **Cash and online sales with a payment-method change: no check in the refund validator.** Refund may be the only available route for a reclassified cash or online sale. Whether `executeRefund` (`RegisterRepository.kt` ~4725–5200) accepts it has **NOT RE-VERIFIED**.

### 2.5 Correct fix path by case

| Mistake | Correct action | Evidence |
|---|---|---|
| Credit sale returned by customer | Refund (unpaid-credit return, no payout) | §2.4 |
| Credit sale, wrong amount | **None in place.** Refund, then a new sale | §2.6 |
| Coupon-funded sale returned | Refund to coupon | §2.4 |
| Coupon-funded sale, wrong amount | **None in place** (coupon semantics not approved) | §2.6 |
| Cash/online sale, wrong amount | Amount correction | §2.6 |
| Cash sale recorded as Online | Payment-method change (then void is blocked; refund route unverified) | §2.7 |

### 2.6 Amount correction (`feature/register/data/AmountCorrectionFinancialIntegrity.kt`, `validate`, lines 111–217)

- Allowed: SALE only; corrected amount ≥ 0; funding identity Cash (`CASH_TENDER`) or approved Online channel. Identity rule in `AmountCorrectionCommand.kt` ~391–430: **Credit** → "requires separately approved credit-obligation semantics"; **Coupon** → "requires separately approved coupon-wallet semantics".
- Rejected: credit repayment allocations; voided sales; fully refunded sales; corrected amount below the refunded amount; unsupported funding; zero delta.
- Effect: delta = corrected − current, written as a `FUNDING_CORRECTION` settlement (`RegisterRepository.kt` ~3855 and ~4032). Positive delta raises, negative lowers. Gross Sales keeps the original; Net Sales carries the signed delta in the correction's period. Period placement follows the reporting event timestamp (see §4; **NOT RE-VERIFIED** in the reporting code this pass).
- **Gap:** this validator does not reject a sale that has a payment-method change, so a correction can be recorded on a reclassified sale while void is blocked.

### 2.7 Payment-method change (`feature/register/data/PaymentMethodChangeFinancialIntegrity.kt`)

- Allowed: SALE with a single identity. Cash = `CASH_TENDER`. Online = `MANUAL_QR` or `SOFTPOS` (`requireSupportedIdentity`, ~284–310). The UI offers Cash or Online and maps Online → `MANUAL_QR` (`feature/register/ui/RegisterViewModel.kt` ~1491–1493). `SOFTPOS` cannot be chosen from the UI.
- Rejected: coupon transactions (~127), voided sales (~131), credit repayments (~135), credit and coupon funding (~192–196), no reclassifiable amount, ambiguous settlement pairs.
- Effect: two balanced settlements of the same amount (debit on the old identity, credit on the new; ~205–280). Gross and Net unchanged. Cash plus Online unchanged. Expected cash changes by the Cash side only. Cited earlier at `RegisterShiftReportReadRepository.kt` ~735–761 (**NOT RE-VERIFIED**).

### 2.8 Coupon-plus-cash split (created here, rounding checked)

- Amount typed by the cashier: `feature/register/ui/RegisterViewModel.kt` ~2260–2271 (`confirmCouponRedemption`). Parsed by `core/model/money/MoneyMinorUnits.kt` `parse` (~119–150), which **rejects** extra decimal places rather than rounding.
- Validation: `feature/register/data/CouponSaleCommand.kt` `validate` (~65–80): amount > 0 and ≤ sale total. Equal amount needs no remainder. Smaller amount needs remainder of CASH, ONLINE, or CREDIT. Same rule re-checked in `SaleFinancialWriter.kt` ~76–79.
- Writer: `SaleFinancialWriter.kt` ~118–133. Coupon settlement = typed amount. Remainder = `grandTotal − couponAmount`, channel CASH → `CASH_TENDER`, ONLINE → `MANUAL_QR`, CREDIT → `CREDIT_ACCOUNT`.
- Wallet check: `RegisterRepository.kt` ~306 (`executeCouponRedemption`), ~357 (`executeAdmittedCouponRedemption`), ~374 (`require(balance ≥ amount)`).
- **Rounding:** the split is exact integer subtraction (`Math.subtractExact`). No rounding occurs in the split. Rounding exists only in the sale-total calculation, which is outside this step (**NOT RE-VERIFIED**: `core/model/tax/TaxRoundingPolicy.kt`).
- **Not enforced:** any limit on the coupon share, and whether a coupon-plus-credit sale is intended.

### 2.9 Customer balance (traced)

Signed ledger sum in `LedgerDao.kt:118`. OBLIGATION (+) rows written by credit sales (`RegisterRepository.kt` ~341, ~1567). REPAYMENT (−) by repayment. Coupon wallet is separate (`LedgerDao` coupon queries).

## 3. Test inventory

Method names confirmed by grep in the repo. "Read" means assertions were read; "names" means the method was located but its body was not read in this pass.

| Area | File: methods | Status |
|---|---|---|
| Cash sale | `androidTest/.../RegisterIntegrationTest.kt`: testQuickCashSaleExecution, testItemizedBillCashSaleWithTaxShippingPacking, testUnauthorizedCashierSaleRejected, testCashSaleRejectsNegativeTaxBeforeAnyPersistence, testCashSaleRejectsTaxAboveOneHundredPercentBeforeAnyPersistence, testCashSaleAllowsOneHundredPercentTaxWithExactFinancialResult, testCashSaleRejectsDuplicateItemIdsBeforeAnyPersistence, testRapidDoubleTapIdempotencyProtection, testTrueConcurrentSameOperationExecutesExactlyOnce, testPendingCrashRecoveryExecution, testSameOperationIdAlteredPayloadConflict, testNoTaxByDefaultExactMinorUnits, testConcurrentRecoveryCreatesOnlyOneFinancialResult. `CashSaleFinancialCharacterizationTest.kt`: pendingCashSaleSurvivesReopenAndQueuesExactlyOnce. `test/.../CashSaleCommandTest.kt` unit tests. | names |
| Coupon purchase | `androidTest/.../CouponSaleIntegrationTest.kt`: cashPurchaseCommitsCustomerFundingLedgerAndAuditExactlyOnce, onlinePurchaseUsesExistingCustomerAndProjectionCountsOnce, concurrentSubmissionsCreditOnlyOnce, invalidPhoneRollsBackCustomerWalletFundingAndLedger, wrongCurrencyRollsBackWithoutFinancialWrites, pendingPurchaseRecoversFromDurableIntentWithoutSession, malformedPendingIntentBlocksRecoveryRepeatedly, existingPhoneAndExistingFundedWalletCannotCreateDuplicates. `test/.../CouponSaleCommandTest.kt`: couponPurchaseRejectsTaxChargesMissingCustomerAndUnsupportedFunding. | first one read: type COUPON_PURCHASE; wallet = purchase; purpose COUPON_PURCHASE_FUNDING; replay idempotent; changed retry = Conflict. Others names |
| Coupon redemption | `CouponSaleIntegrationTest.kt`: partialRedemptionAndRefundToCouponAreAtomicReplaySafeAndVisibleInReadModel | read: 7000 sale = 3000 coupon + 4000 cash; wallet 5000 → 2000; funding {COUPON, CASH}; replay idempotent; refund-to-coupon 1000 restores to 3000 |
| Coupon bonus | `test/.../reporting/domain/ReportingDomainTest.kt`: couponPromotionBonusIsPositiveAndMapsToBonusActivity; `test/.../reporting/data/ReportingRowMappersTest.kt`: couponPromotionBonusIsItsOwnKind | mapping only. **No issuance code and no issuance test.** |
| Coupon refund | `CouponSaleIntegrationTest` (above); `androidTest/.../RefundFinancialCoreIntegrationTest.kt`: productionRefund_b2_destinationCoupon_refundsOnlyPaidValueToCoupon, productionRefund_b2b_fullReturnToCoupon_creditsOnlyPaidValueAndNeverCreatesDoubleValue, productionRefund_b2_couponFundedRepayment_selectedDestinationsNeverCreateDoubleValue, productionRefund_couponFundedTransaction_isBlockedWithoutFinancialMutation | names |
| Refund (cash/online, credit) | `RefundFinancialCoreIntegrationTest.kt`: productionRefund_partial_createsExactEventAndOutflow (310), productionRefund_partialPartialFull_reachesZeroWithoutRewritingSale (376), productionRefund_overRefund_isBlockedWithoutFinancialMutation (439), productionRefund_onlineFunding_refundsBackToSameOnlineChannel (626), productionRefund_unpaidCredit_reducesDebtWithoutCashOnlineOrCoupon (926) | names |
| OPEN_CASH_SHIFT | `androidTest/.../RegisterShiftFoundationTest.kt`: openingShiftIsDurableAndIdempotent, pendingOpeningRecoversWithoutDuplicateEvent, twoCashiersCanHoldIndependentOpenShiftsInSameBranch, sameCashierCannotOpenSecondShiftWhileFirstIsOpen. `androidTest/.../core/cloud/sync/CloudOnlyCashShiftOwnerTest.kt`: appliesOpenCashShiftForCloudOnlyCashier, replaysCashShiftEnvelopeExactlyOnce | read (first): replay idempotent; one shift event |
| COUNT_CASH_SHIFT | `androidTest/.../RegisterShiftAuthorizationTest.kt`: cashierCanCountAndCloseOwnShift, cashierCannotCloseAnotherUsersShift, adminCanCountAndCloseCashierShiftWithoutChangingOwner. `CloudOnlyCashShiftOwnerTest.kt`: appliesCountCashShiftForCloudOnlyCashier | read (first): count and close succeed; shift closed |
| CLOSE_CASH_SHIFT | same files; `CloudOnlyCashShiftOwnerTest.kt`: appliesCloseCashShiftForCloudOnlyCashier | read (cloud): shift, event and operation persisted; cashier row not created |
| Day Closing arithmetic | `test/.../ui/DayClosingPresentationTest.kt`: shortIsCountedMinusExpected, exactHasZeroDifference, overIsCountedMinusExpected, omrCountParsesUsingThreeDecimals, negativeCountIsRejected. `test/.../data/RegisterShiftDayClosingAccountingTest.kt`: expectedCashIncludesCouponFundingAndLifecycleDeltasExactlyOnce, externalMovementRoutesOnlyCashAndOnline | names |
| Expense correction and void | `androidTest/.../ExpenseIntegrationTest.kt`: adminExpenseCorrectionAndVoidAppendEventsAndQueueExactlyOnce (1273). `test/.../reporting/data/ReportingAggregatorTest.kt`: expenseCreatedCorrectionAndVoidRemainReportableWithoutDoubleCounting (440), expenseCorrectionInLaterPeriodProducesOnlyItsSignedDelta (470), expenseCorrectionAndVoidSupportOnlineFundingAsWell (480) | read (integration): correction one event, one settlement, outbox QUEUED, replay idempotent |
| Expense void alone | no standalone test; covered only inside the combined test above | — |
| Payment-method change | `androidTest/.../PaymentMethodChangeWriterTest.kt`: appendsExactBalancedPairAndPreservesOriginalFunding, retryIsIdempotentAndAlteredRequestConflicts, secondChangeRetainsFirstEventAndBothPairs | names |
| Amount correction | `androidTest/.../AmountCorrectionFinancialCoreIntegrationTest.kt`: productionCorrection_increase_writesOnlyPositiveDelta_andPreservesOriginal, productionCorrection_decrease_writesOnlyNegativeDelta (244–396 region) | names |
| Credit repayment | `androidTest/.../CreditRepaymentIntegrationTest.kt`: cashRepaymentCreatesExactAuthoritativeFinancialEvidence, fifoRepaymentConsumesOldestCreditSaleFirst, exactPayoffProducesZeroDerivedOutstanding, overpaymentFailsWithoutFinancialChildWrites, identicalReplayDoesNotDuplicateRepaymentEvidence | names |

**Gaps in tests:**
- The opening-float rule is **not pinned by any test**: no test asserts that the opening count is excluded from expected cash.
- Coupon bonus issuance: no code, no test.
- Expense void: no standalone test.
- Day Closing screen result (difference and status end to end): not verified beyond arithmetic helpers.
- Void/refund for a reclassified cash or online sale: no test located.

## 4. Database (live vs repo)

Live migration ledger: 44 rows (`supabase_migrations.schema_migrations`, SELECT). Repo: 42 SQL files in `supabase/migrations/`.

| Category | Items |
|---|---|
| Live, no repo file | `20261018140123 add_financial_reporting_inventory_rpc`; `20261024174802 capture_financial_operation_collisions` |
| Live, one repo file for three versions | `reactivate_removed_cashier_identity` × 3 live versions (20261015185018, 20261015185332, 20261015185405) → repo `20261015203000_reactivate_removed_cashier_identity.sql`. Whether all three applied the same SQL is **unknown**. |
| Repo, not applied live | `20261019190000_supabase_hardening_fk_indexes.sql` (adds indexes); `20261019190100_optimize_notification_rls_auth_uid.sql` (**security-relevant change: see chat**) |
| Live, file exists under different timestamp | 11 versions, e.g. live `20261023044205 retire_cashier_feature` → repo `20261023050000_retire_cashier_feature.sql`. The `supabase/migrations/README.md` statement that timestamps match is **contradicted**. |

**Live functions with no active repo definition:**
- `fetch_financial_reporting_inventory_v1`: live only. Not called by the app (no references in `app/src/main`). Returns raw envelopes, computes no totals.
- `fetch_financial_envelopes_v2`: no SQL in the repo; mentioned only in `BOARDLOCK/*.md`. Not called by the app.
- `restore_admin_by_auth_id`: defined only in `BOARDLOCK/staged_sql/restore_admin_by_auth_id.sql`, outside the active migration path. Called by `app/src/main/.../RemoteBusinessRestorer.kt` and `core/cloud/sync/RemoteFinancialSyncRepository.kt`.
- `enforce_membership_branch_business`, `prevent_envelope_mutation`, `touch_updated_at`: no definition anywhere in the repo.

**Live tables with no active repo `CREATE`:**
- `collision_audit`: no reference anywhere in the repo. Probably created by the unmatched `capture_financial_operation_collisions` migration (inference).
- `staff_invites`, `staff_invite_activations`: only ALTER and index statements in the repo (e.g. `20261010094606_staff_email_auth_flow.sql`, line 5).
- The repo has no baseline schema. `supabase/migrations/README.md` says so explicitly ("does not contain a complete clean-project baseline schema dump").

**Live triggers with no repo definition:**
- `trg_membership_branch_business`, `trg_memberships_touch_updated_at`, `trg_businesses_touch_updated_at`, `trg_profiles_touch_updated_at`, `trg_staff_identities_touch_updated_at`, `trg_branches_touch_updated_at`.
- `financial_envelope_immutable` on `private.financial_operation_envelopes`: referenced only in the archived production reset SQL (`supabase/archive/production-only-migrations/`), never in an active migration.
- Defined: `trg_notify_admins_of_confirmed_day_close` (`20261011230317_business_notifications_and_permission_requests.sql`, line 181).

**Archive:** `supabase/archive/production-only-migrations/20261012104437_authorized_production_data_reset_20261012.sql` is a destructive historical reset. The active replacement in `supabase/migrations/` is a `SELECT 1` no-op, as documented. Not to be executed.

**Not done:** a complete comparison of live function bodies against repo definitions for the functions that do exist in the repo (the `capture_live_function_definitions` migration suggests drift is already known for `ingest_financial_envelope`).

## 5. Known gaps and open questions

### 5.1 Decisions needed from the owner (code cannot answer these)

1. Should opening float count toward expected cash? Current behaviour: no, producing OVER on every float shift (§2.2).
2. Should Reports show period activity or an as-of balance for Credit Outstanding? Current: both, on different screens (§1, §2.1).
3. Should coupon purchases/top-ups count as sales revenue or remain a liability? Current: liability, shown separately.
4. Should a coupon-plus-credit split be allowed? Current: allowed by the writer (§2.8).
5. Should a sale with a payment-method change be voidable? Current: no (§2.3).
6. Should wrong-amount corrections be possible for credit and coupon sales? Current: no (§2.6).
7. Should coupon issuance of a bonus be implemented at all? Current: not implemented (§5.2).
8. What should the user-facing void message say? Current: disabled button and a generic message (§2.3).
9. Should report period boundaries follow event timestamps (current) or restate the original sale?
10. Which tax rounding mode is the business default, and may it be overridden? (`TaxRoundingPolicy.kt`, not re-verified)

### 5.2 Confirmed defects and gaps (code evidence in §2–§4)

- Reports zero-floor hides outstanding debt on repayment-in-range reports (§2.1).
- Opening float produces OVER on every shift that starts with cash (§2.2).
- Disabled Void button with no reason; generic message for a failed void (§2.3).
- Refund destination picker has no monetary effect for unpaid credit returns (§2.4).
- Payment-method-changed sales cannot be voided; amount correction is allowed on them (§2.6, §2.7).
- Coupon bonus: mapping and export readers exist, no issuance code (§3).
- Repo-only migration not applied live (§4).
- Live server function not in repo, not used by the app (§4).
- `restore_admin_by_auth_id` only in a staged file, outside the migration path (§4).
- `supabase/migrations/README.md` claims contradicted by the repo (§4).

### 5.3 Not verified (stated explicitly)

- Refund path for a reclassified cash or online sale (`RegisterRepository.executeRefund`, ~4725–5200).
- Period placement of amount corrections and expense corrections in `ReportingReadRepository.kt` and `ReportingAggregator.kt`.
- `RegisterShiftReportReadRepository.kt` ~735–761 (payment-change expected-cash adjustment) and `DayClosingPresentation.kt` ~32–60.
- `TaxRoundingPolicy.kt` and the sale-total calculation.
- Recent Transactions and History screens: their totals and whether they match Reports for the same event. Not traced in this pass.
- Full function-body comparison of live versus repo for functions that exist in both.
- Test bodies for most methods listed in §3.
- Whether any live function is unsafe in ways that affect financial correctness. Security is out of scope here and is reported in chat.

## 6. Change record

- Consolidated from earlier drafts (`MONEY_RULES.md`, `MONEY_RULES__1_` through `__5_`) into this single document. Earlier drafts are superseded.
- Corrections to earlier drafts are listed in §0.
- This document is documentation only. No application code or database objects were changed.