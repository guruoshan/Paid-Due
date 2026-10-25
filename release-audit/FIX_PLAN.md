# Money fixes — phased plan

Source of findings: `release-audit/MONEY_RULES.md` (commit `317a9b47` on `audit/release-readiness-2026-10-25`). Section references below (§) point to that document.

## 0. Ground rules (apply to every phase)

- Work on a branch per phase, created from `audit/release-readiness-2026-10-25`. Never commit to `main`.
- Write the failing test first, then the fix. Each fix is a separate commit whose message cites the § it addresses.
- No live database writes. Schema changes go through a reviewed migration, and only after Phase D's baseline exists.
- Do not change behaviour that is not listed here. Anything new found while working goes into `MONEY_RULES.md` §5.2, not into the current branch.
- Security is out of scope for this plan. See Phase E.
- Each phase ends with the owner's approval of the phase's result before the next phase begins.

## 1. Decisions recorded

| # | Topic | Decision | Source |
|---|---|---|---|
| 1 | Opening float in expected cash | Keep excluded. Day Closing keeps showing OVER on float shifts. | Owner, 1a |
| 2 | Reports / PDF Credit Outstanding | Period activity, keeping the current label, with a note that it is not the balance. Customers keeps the all-time balance. | Owner, 2b |
| 3 | Void message for credit/coupon sales | "Void isn't available for credit or coupon sales. Use Refund instead." | Owner, 3a |
| 4 | Void after Cash/Online switch | Allowed. Void reverses the sale's full amount from the channel the money is in now. Switch history is not rewritten. Net effect on Cash and Online is zero. | Owner, 4b; method chosen by assistant, owner to confirm |
| 5 | Coupon bonus | Build the issuance flow, after a design is approved. Design method in Phase C. | Owner, 5b; method chosen by assistant, owner to confirm |
| 6 | Coupon-plus-credit sales | Keep allowed. | Owner, 6a |
| 7 | Wrong amount on credit/coupon sales | In-place correction allowed. Rules in Phase B, item B3. | Owner, 7b; rules chosen by assistant, owner to confirm |
| 8 | Report day for later adjustments | Adjustment appears on the day it happened. Original sale day does not change. | Owner, 8a |
| 9 | Tax rounding mode | Keep current setting: HALF_UP (`SetupViewModel.kt:52`, `DraftState.kt:33`). | Owner, 9 |

## 2. Phase A — no policy decision needed

Start here. Each item can be merged independently.

### A1. Characterization tests for current behaviour

Pin current behaviour so later changes show up as deliberate test changes.

- Opening float excluded from expected cash. Use the example opening 20.000, cash sale 5.000, counted 25.000 → expected 5.000, difference +20.000, OVER. File: `app/src/test/.../register/data/RegisterShiftDayClosingAccountingTest.kt` (new test `openingCountIsExcludedFromExpectedCash`).
- Reports zero floor. Day 1 credit sale 3.000, Day 2 repayment 1.000, Day 2-only aggregation returns 0.000 with `creditOutstanding` null. File: `app/src/test/.../reporting/data/ReportingAggregatorTest.kt` (new test `periodFallbackFloorsNegativeToZero`).
- Void rejected for credit and coupon sales, with the exception type. File: `app/src/test/.../register/data/` (new `VoidFinancialIntegrityTest.kt`).

Acceptance: tests pass on the current code before any Phase A fix. A1 changes no production code.

### A2. Explain why Void is disabled (fix for §2.3)

- Add a visible reason next to the disabled Void button when `canVoid` is false because the sale is credit- or coupon-funded.
- Text: "Void isn't available for credit or coupon sales. Use Refund instead." (owner-approved, item 3a). Add to `app/src/main/res/values/strings.xml`, plus the same key in every other locale file the project uses. Check for translations before merging.
- Where: `feature/register/ui/TransactionDetailsScreen.kt` around lines 245–252 and 507–517.
- Only show the reason for the credit/coupon case. Other disabled cases keep their current behaviour.

Acceptance: a unit test for the UI state mapping; manual check on a credit sale and a coupon sale that the reason appears and Void stays disabled.

### A3. Specific void failure messages (fix for §2.3)

- Map `VoidUnsupportedCreditException` and `VoidUnsupportedCouponException` to the same message as A2 when a void is submitted anyway (stale screen).
- Map `VoidAmbiguousSettlementException` to a distinct message, so the user knows the sale can't be voided in its current state. Text to be approved by the owner before merge.
- Leave `tx_void_failed` as the fallback for unknown failures.

Acceptance: test that each exception maps to the expected string key.

### A4. Refund destination picker for unpaid credit returns (fix for §2.4)

- For a credit sale with no repayments, offer only the destination that matches reality: a credit return with no payout. Remove CASH, ONLINE, and COUPON from the picker in this case.
- Repository behaviour does not change. `RegisterRepository.kt` ~5636–5652 already emits no payout.
- Test: the destination options for an unpaid credit sale contain only the credit-return option.

Acceptance: picker test passes; `executeRefund` unchanged (existing `productionRefund_unpaidCredit_reducesDebtWithoutCashOnlineOrCoupon` still passes).

### A-review

- One PR (or one branch) per item, all against the audit branch.
- Run the Android unit tests and the instrumented tests for the refund and void suites locally (see §8 for commands).

## 3. Phase B — decided policy changes

Each item needs its tests written and reviewed before code.

### B1. Void after Cash/Online switch (decision 4)

Current code rejects any sale with a payment-method change (`feature/register/data/VoidFinancialIntegrity.kt`, `validate`, ~line 172).

Change:
- Remove the rejection for payment-method changes.
- The void writes reversal settlements against the sale's current funding identity (after the last switch), for the full current amount.
- The switch's settlement pair stays in the ledger unchanged.
- Net effect on Cash and Online for that sale after void: zero.

Tests to add (in `androidTest`, alongside `VoidFinancialCoreIntegrationTest.kt`):
- `voidAfterCashToOnlineSwitchReversesOnlineSide`: Cash 5.000 sale, switched to Online, voided → Online total 0 for the sale, Cash total 0 for the sale.
- `voidAfterOnlineToCashSwitchReversesCashSide`: mirror case.
- `voidAfterSwitchIsIdempotentOnReplay`.
- `voidAfterSwitchRejectsAlteredRequest`.

Open check before coding: confirm refund on a switched cash or online sale (`RegisterRepository.executeRefund`, ~4725–5200) works the same way, because refund is the alternative route today. This is in the NOT RE-VERIFIED list (§5.3 of the audit).

Acceptance: all new tests pass; existing void and refund tests still pass.

### B2. Reports note for Credit Outstanding (decision 2)

- Keep the current label on Reports and PDF.
- Add a short note under the figure: it shows credit activity for the selected period, not the customer's balance. Exact wording to be approved by the owner before merge.
- Code changes: `feature/reporting/ui/` (summary card) and `feature/reporting/pdf/ReportingPdfActions.kt` ~151. No change to the calculation.
- Do not change the floor behaviour here. It stays until a later decision. Note: the floor can hide a debt in a range, so the note must not say the figure is the amount owed.

Acceptance: UI test checks the note appears on the summary; PDF test checks the note line is present.

### B3. In-place correction for credit and coupon sales (decision 7)

Current code rejects credit and coupon funding in amount correction (`feature/register/data/AmountCorrectionCommand.kt` ~391–430).

Rules (chosen by the assistant, owner to confirm):

- **Coupon sale:** the coupon amount stays fixed. The remainder changes, on its own channel (cash, online, or credit). The wallet is not touched.
  - If the corrected total is below the coupon amount, reject. The user must refund to coupon instead.
  - If the corrected total is below what has already been refunded, reject.
- **Credit sale:** the correction adjusts the customer's credit obligation by the difference, as a ledger adjustment. No cash or online movement.
  - If the corrected amount is below what has already been repaid or refunded, reject. The user must use the refund path.
- Both cases keep the existing rules: no correction on voided sales, and no zero delta.

Tests to add:
- `couponCorrectionChangesRemainderOnly`
- `couponCorrectionBelowCouponAmountIsRejected`
- `couponCorrectionBelowRefundedIsRejected`
- `creditCorrectionAdjustsObligationByDelta`
- `creditCorrectionBelowRepaidIsRejected`
- `creditCorrectionReplayIsIdempotent`

Acceptance: tests pass; no wallet balance changes on coupon correction; Credit Outstanding changes by the delta on credit correction.

Before coding: owner confirms these rules (item 7 method). Also needed: confirm how credit repayment allocation interacts with corrections (the audit says correction on a credit sale with repayment allocations is currently rejected by `AmountCorrectionCreditRepaymentExistsException`). The rule above assumes that stays rejected, so the "repaid" check is only needed if the rejection is later relaxed.

### B4. Period placement (decision 8)

No code change. Recorded as the current behaviour. Add a test that a correction in a later period produces only its signed delta in that period (already exists as `expenseCorrectionInLaterPeriodProducesOnlyItsSignedDelta` for expenses; add the equivalent for amount correction).

## 4. Phase C — coupon bonus (decision 5)

No code until the design is approved.

### C1. Design note (first deliverable)

Write `release-audit/COUPON_BONUS_DESIGN.md` covering:

- Who can issue a bonus: admin role only. Confirm the permission key.
- Unit of issuance: one bonus event per customer per campaign, with a campaign reference and an amount.
- Ledger: append-only `CouponLedgerType.PROMOTION_BONUS` event, with reason, issuing user, campaign reference, and operation ID.
- Idempotency: keyed on the operation ID, so a retry cannot issue twice.
- Cap: a per-customer cap per campaign. The cap amount to be set by the owner.
- Expiry: none in the first version.
- Reporting: bonus appears as its own coupon event, not as sales, which matches §1.
- Offline sync: the bonus must go through the same financial envelope path as other coupon events. Confirm with the existing sync design.

Owner approves the design note before C2.

### C2. Implementation

- Writer for the bonus event (new, nothing exists in the repo today — see audit §3).
- Admin UI entry point, permission check, and the cap check.
- Tests: issuance, cap rejection, idempotent replay, non-admin rejection, reporting shows bonus separately.

Acceptance: tests pass; the bonus appears in reporting as a coupon event and not as merchandise sales.

## 5. Phase D — database baseline and migrations (owner approval required)

This phase changes nothing live until approved. Read-only checks come first.

### D1. Baseline schema capture

- Capture a schema-only dump of the live project (tables, functions, triggers, policies). Read-only. Store it under `supabase/baseline/` as a reference file, not a migration.
- Review the dump against the findings in audit §4, in particular: `collision_audit`, `staff_invites`, `staff_invite_activations`, the six unmatched triggers, and the three unmatched functions.

### D2. Reconcile the two live migrations with no repo file

- `20261018140123 add_financial_reporting_inventory_rpc` and `20261024174802 capture_financial_operation_collisions`.
- Pull the live SQL (read-only) and decide, per migration: add the matching file to the repo, or document why not.
- No migration is re-applied to live without approval.

### D3. Repo-only migrations not applied live

- `20261019190000_supabase_hardening_fk_indexes.sql`: low risk (indexes). Decide whether to apply.
- `20261019190100_optimize_notification_rls_auth_uid.sql`: changes an access policy. Do not apply until the security review (Phase E) has covered it.

### D4. Timestamp mismatches

- Eleven repo files carry different timestamps from their live versions. Decide whether to rename the repo files to the live versions, or to record the mapping. Do not rename without a note in `supabase/migrations/README.md`, which currently makes a false claim (audit §4).

Acceptance: `supabase migration list` matches the repo for every applied version, and the baseline dump is committed.

## 6. Phase E — security review (separate deliverable)

Out of scope for this plan. The audit excluded it on purpose.

- Scope: function privileges and SECURITY DEFINER use, row-level security policies, access checks in the financial RPCs, and the policy in D3.
- Output: a separate document, delivered to the owner directly, not in `MONEY_RULES.md`.
- Gate: Phase D3's second file cannot be applied until this review signs off.

## 7. Order and gates

1. A1 (characterization tests) — first, no production change.
2. A2, A3, A4 — in any order after A1.
3. Owner approves Phase A results.
4. B1 — needs the refund check noted in B1 first.
5. B2 and B3 — B3 needs the owner's confirmation of the rules.
6. B4 — tests only.
7. Owner approves Phase B results.
8. C1 design note — owner approval — then C2.
9. D1 (read-only capture) can run in parallel with A and B.
10. D2–D4 after D1 and after the owner's decision on each item.
11. E runs in parallel with D; D3 waits on E.

## 8. Commands

Run from the repo root in PowerShell. Adjust the Gradle task names to match the project's build if they differ.

```
git checkout audit/release-readiness-2026-10-25
git checkout -b fix/money-phase-a
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Use the same pattern for each phase branch (`fix/money-phase-b`, `fix/money-phase-c`). The instrumented tests need a connected device or an emulator.

Push each branch with `git push origin <branch>`. Do not push to `main`.

## 9. Risks

- Phase B1 changes what a void does to a sale that was already switched. Any tests that assume the old rejection must be updated, and each change needs review.
- Phase B3 changes money on credit and coupon sales. Test with replay and concurrency cases, not only the happy path.
- Phase A2 and A3 text needs translation in every locale the project supports.
- The database phases must not run against live data without a reviewed plan.

## 10. Not in this plan

- Recent Transactions and History screens (not traced in the audit).
- Tax rounding logic beyond confirming HALF_UP (decision 9).
- Any security change.
- Expected cash formula changes (decision 1 is to keep it).