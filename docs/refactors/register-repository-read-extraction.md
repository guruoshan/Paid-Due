# RegisterRepository Read-Query Extraction

## Scope and baseline

- Repository: `guruoshan/Paid-Due`
- Base branch: `main`
- Base SHA: `c999501609ed1da2e90b73bf55ad2d9bac2028c0` (merged PR #110)
- Target: `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterRepository.kt`
- Baseline size: 8757 lines
- Resulting size: 8733 lines
- Net RegisterRepository reduction: **24 lines**

This is a behavior-preserving extraction. Existing public `RegisterRepository` methods remain in place and delegate to the new read-focused collaborator.

## Newly extracted methods

| Existing public method | New owner | Preserved behavior |
|---|---|---|
| `getCustomerCreditOutstanding(customerId, currencyCode)` | `RegisterQueryRepository` | Active-session requirement, customer existence check, active-business ownership check, active-branch currency check, and the same ledger balance aggregation |
| `observeRecentTransactions(branchId, limit)` | `RegisterQueryRepository` | Same DAO Flow, branch filter, ordering, and limit |

The query implementations were moved without changing their validation order, exception types/messages, DAO calls, or query arguments.

## Existing read responsibilities already extracted

The source audit found these responsibilities already delegated before this PR. They were deliberately not duplicated or moved again:

| Responsibility | Existing collaborator / owner |
|---|---|
| Transaction detail lookup, transaction items, transaction details projection | `TransactionDetailsReadRepository` |
| Expense period query and authoritative expense details | `ExpenseReadRepository` |
| Customer phone lookup and persisted sale-customer identity checks | `RegisterCustomerReadRepository` |
| Coupon sale customer search | `CouponFinancialRepository` |
| Coupon wallet details and activity projection | `CouponWalletReadRepository` |
| Credit invoice projection | `CreditInvoiceReadRepository` |
| Current register shift report | `RegisterShiftReportReadRepository` |
| Today's recent financial activity | `RecentFinancialActivityReadRepository` |

The new collaborator is intentionally a small home for the two remaining direct, general-purpose register queries. It does not replace the specialized projection repositories.

## Responsibilities intentionally retained

- Financial command admission, execution, replay, recovery, and reconciliation.
- Query helpers that are part of a financial writer's precondition, evidence verification, or recovery transaction.
- Read/aggregation logic whose relocation would change a financial command boundary or introduce a second source of truth.

In particular, no sale/refund/void/amount-correction/repayment/shift calculation was rewritten. No financial mutation path was redirected through the new collaborator.

## Compatibility and dependency impact

- `RegisterRepository` retains both public method signatures and forwards to `RegisterQueryRepository`.
- Existing callers do not need to change.
- The new collaborator depends only on `DmrDatabase`, its existing transaction/customer/ledger DAOs, `SessionManager`, and Kotlin Flow.
- No Hilt binding is required; it is instantiated alongside the existing read collaborators by the compatibility façade.
- No DAO query, Room entity, database schema, migration, command API, sync path, or Supabase resource changed.

## Characterization added

1. `recentTransactionQueryReturnsPersistedBranchRows` exercises the existing public façade method after a real cash sale and verifies the observed branch query result.
2. `customerCreditOutstandingQueryMatchesLedgerAndKeepsCurrencyGuard` compares the façade result with the persisted ledger aggregate and verifies that a currency outside the active branch remains rejected.

Existing report, transaction-detail, expense, coupon, and shift-report tests remain unchanged and continue to exercise their specialized read collaborators.

## Risk assessment

**Low, subject to CI verification.** This change moves two read-only method bodies verbatim behind a collaborator and leaves the public façade signatures intact. The only behavior-sensitive elements—the active-session check, business ownership guard, branch currency guard, exact ledger aggregation call, and transaction Flow query—are retained.

## Verification status

Local Gradle execution is not available through the GitHub repository editing interface. The PR's Android unit/instrumentation, lint, release-build, and repository contract workflows must complete successfully before this extraction is considered verified. No numeric coverage percentage is claimed.
