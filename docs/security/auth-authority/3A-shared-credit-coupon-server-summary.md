# TASK 3A — Shared Credit/Coupon State: Dependency Audit and Server-Summary Design

**Status:** design only. No schema, API, sync, or financial behavior changed by this document.

## Decision

Do **not** restrict cashier downloads to operations authored by the current cashier. Current local projections reconstruct shared customer Credit and Coupon state from persisted transaction, ledger, allocation, wallet, settlement, refund, and void evidence. Filtering the download to one cashier would make those projections incomplete and could authorize an incorrect balance or an unsafe financial decision.

The existing server download is scoped by business and branch, not by actor membership. That is broad data access, but it currently preserves the evidence required by the local read models.

## Screen-by-screen dependency inventory

| Screen / flow | Current local read path | Why other cashiers' evidence matters |
|---|---|---|
| Register — customer search/selection | `RegisterCustomerReadRepository`, customer DAO | Identifies the customer in the active business/branch. Search alone does not make a balance authoritative; the selected customer's financial projections depend on shared evidence below. |
| Register — Credit sale / invoice | `CreditInvoiceReadRepository`, transaction DAO, Credit ledger DAO, repayment-allocation DAO | Calculates obligation and outstanding amounts from the customer's full Credit ledger and repayment allocations, not only the current cashier's sales. A different cashier may have issued the invoice or recorded repayments. |
| Register — Coupon sale, top-up, redemption, refund-to-Coupon | `CouponFinancialRepository`, `CouponWalletReadRepository`, Coupon ledger DAO | Wallet identity, balance, authorized users, top-ups, bonuses, redemptions, and refund credits are shared customer state. Other cashiers may have funded or redeemed the same wallet. |
| Customers list and Credit/Coupon filters | `CustomerRepository.listCustomers()`, customer summary DAO, `CustomersViewModel` | “Has Credit” / “Has Coupon” and the customer list must reflect activity from all cashiers in the branch. A cashier-only projection can hide customers with live balances. |
| Customer detail — Credit history and outstanding | `CustomerRepository.getHistory()`, Credit ledger, repayment and allocation DAOs; `CustomerDetailViewModel` | Shows obligations, repayments, adjustments, allocations, and current outstanding across all originating cashiers. FIFO repayment allocation requires complete open-obligation evidence. |
| Customer detail — Coupon wallet and ledger | `CouponWalletReadRepository`, Coupon wallet and ledger DAOs | Balance/history and authorized-user events are wallet-wide. Omitting another cashier's top-up/redemption can display the wrong balance. |
| Customer detail — receive Credit payment / repay with Coupon / refund-to-Coupon | Credit repayment commands and `CouponCreditRepaymentCommand` | Admission and evidence checks depend on the shared outstanding amount and wallet balance. These writes must be disabled offline while the latest shared state cannot be confirmed. |
| Recent Transactions panel | `RecentFinancialActivityReadRepository`, transaction and Credit/Coupon ledger DAOs | Resolves repayments and refund-to-Coupon activities through operation → ledger event → wallet/customer. Missing other-cashier evidence produces incomplete or unattributable recent activity. |
| Full History | `HistoryRepository`, history read model, transaction and financial-record DAOs | Branch history, lifecycle status, and audit context may include operations created by another cashier. Existing permission gates decide which history a cashier may view; actor-only download is not a safe substitute. |
| Transaction details / refund / void / amount correction / payment-method change | `TransactionDetailsReadRepository`, `TransactionActionEligibilityResolver`, financial-integrity validators | Lifecycle eligibility must use the original operation and related compensation evidence. Actor-only data can make a transaction appear eligible when its real state is different. |
| Day Closing / shift report | `RegisterShiftReportReadRepository`, shift, settlement, Credit/Coupon ledger DAOs | The report is shift/cashier-scoped, but its rows and totals still need the complete evidence associated with that shift, including repayment and Coupon funding/redemption movements. It must not substitute for branch-wide reporting. |
| Admin Reports and reporting dashboard | `ReportingReadRepository`, `ReportingAggregator`, reporting DAO | Aggregates branch-wide sales, refunds, voids, Credit repayments, Coupon ledger events, expenses, and lifecycle changes. Admin reads all authorized branch evidence; cashier access to Reports remains separately denied. |
| Invoice / WhatsApp credit reminder or Coupon balance message | customer-detail state plus `CustomerWhatsAppMessageComposer` / `UniversalWhatsAppMessageComposer` | A message may state an outstanding Credit or Coupon balance. It must use a current, evidence-derived balance, not a cashier-only or independently stored balance. |
| Inbound financial sync and Room materialization | `FinancialSyncEngine`, `RemoteFinancialSyncRepository`, `InboundEnvelopeApplier` | Inbound envelopes are the evidence feed from which the above local projections are rebuilt. Omitting operations by other cashiers breaks cross-cashier projections even if the UI code is unchanged. |

This is a source-level dependency inventory for the reviewed paths, not a claim that every UI entry point in the entire app has been exhaustively traced. Before replacing envelope download, run a repository-wide call-site audit and integration tests against every listed consumer.

## Proposed server-side summary design

1. Keep the current business/branch scope and server-side membership, branch-active, business-active, and permission checks. Do not filter shared financial evidence by actor membership.
2. Add authenticated, cashier-session-gated read endpoints for:
   - branch customer lookup with minimal fields and Credit/Coupon indicators;
   - a customer summary containing Credit outstanding and open-obligation count;
   - Coupon wallet summary and authorized-user list;
   - cursor-paginated Credit ledger/repayment history;
   - cursor-paginated Coupon ledger/history.
3. Calculate summary values from authoritative ledger and operation evidence inside Postgres. Do not add a second independently mutable balance column. If performance later requires a materialized projection, update it transactionally from ledger writes and provide reconciliation/rebuild tooling against the ledger.
4. Return `as_of`, a monotonic revision/cursor, and currency metadata with each summary. The client should display stale status when cached data is used.
5. Enforce Credit repayment allocation, Coupon redemption limits, wallet status, and current permissions on the server in the same database transaction that admits the write. A client summary is a read model, never authorization to spend.
6. Until these endpoints and server-side write checks are deployed and verified, retain branch-scoped envelope download. In bounded offline mode, allow only local cash operations; block Credit, Coupon, online payment, refunds, voids, and other state-sensitive actions.

## Trade-offs

| Approach | Benefits | Costs / risks |
|---|---|---|
| Keep branch-scoped envelope download (current) | Existing projections retain full evidence; offline history is richer; fewer new API contracts; ledger-based read models remain local. | Larger sync payloads and local storage; each cashier device receives more branch financial detail than strictly needed; permission enforcement must remain strong. |
| Server-side summaries and targeted history (proposed) | Reduces bandwidth, local storage, and exposure of unrelated transaction details; makes shared balances server-authoritative at read/write time. | More API/RPCs, query and pagination design, server load/latency, offline feature limitations, and more integration tests. Every dependent screen must migrate together; incomplete endpoint coverage would break workflows. |

## Acceptance gates before implementation

- Trace all Credit/Coupon balance and history consumers and their DAOs.
- Prove cross-cashier sale, repayment, top-up, redemption, refund-to-Coupon, void/refund, and duplicate-retry scenarios in integration tests.
- Compare server summaries against a fresh ledger rebuild.
- Verify authorization for every endpoint with same-business/different-branch, inactive cashier, revoked session, and unauthorized customer tests.
- Do not remove or restrict the existing download until every dependent consumer has a replacement and the migration plan is approved.
