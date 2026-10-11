# TASK 3A — Cross-cashier financial dependency audit (design only)

**Status: DESIGN ONLY — no SQL, API, client, or deployment change is made here.** Cashier financial downloads must not be narrowed to the cashier's own operations in this proposal. The current download is branch-scoped and contains raw financial envelopes; changing that contract before replacing shared-state dependencies risks incorrect customer balances and Coupon values.

## Screen-by-screen dependency inventory

| Screen / read path | Evidence and cross-cashier dependency | Consequence if another cashier's envelopes disappear |
|---|---|---|
| Register → Recent Transactions | `RecentTransactionsPanel` → `RecentFinancialActivityReadRepository`; reads today’s financial records, Credit repayment ledger events, Coupon ledger events, wallet evidence, and settlements. Refund-to-Coupon recipient resolution follows operation → Coupon ledger event → wallet → customer. | Rows can be missing or misattributed; refund-to-Coupon history and repayment activity can be incomplete. The query must be checked against intended cashier-vs-branch visibility before narrowing. |
| Full History | `HistoryScreen` / `HistoryViewModel` → `HistoryRepository.observeHistory()`; projects persisted transaction financial records and Expense history/settlements, guarded by `VIEW_BROAD_HISTORY`. | Broad history becomes incomplete. The permission must be enforced at the server download boundary, not only in UI/Room. |
| Transaction Details | `TransactionDetailsScreen` → `TransactionDetailsReadRepository`; reads transaction items, Credit repayment allocations, linked repayments, and action eligibility. | A transaction's outstanding amount/action eligibility may be wrong when repayment evidence came from another cashier. |
| Customers list and Customer Details | `CustomersScreen` / `CustomerDetailScreen` → `CustomerRepository`; reads customer summaries, transactions, Credit ledger, repayment records, invoices and settlements. | Shared customer Credit balance/history and invoice paid status can drift or show false outstanding amounts. |
| Credit invoice/detail | `CreditInvoiceReadRepository`; derives an invoice from the persisted Credit transaction, OBLIGATION, FIFO repayment allocations, and signed customer Credit ledger balance. | Cross-cashier repayments/allocation evidence can be omitted, producing wrong invoice balance or negative/missing allocation checks. |
| Coupon wallet details | `CouponWalletReadRepository`; derives wallet balance/history from Coupon ledger events and resolves purchase/top-up transaction descriptions and authorized-user events. | Wallet balance, top-up vs sale classification, redemption/refund history and authorized-user view can be incomplete. |
| Register shift / day-close report | `RegisterShiftReportReadRepository`; joins shift operations to transactions, settlements, Credit events/repayments and Coupon events to calculate cash, online, Credit, Coupon and cash difference. | Any operation legitimately attached to the shift must remain visible regardless of which cashier produced the linked evidence; otherwise closing totals can be wrong. Server must scope by business, branch, shift and time, not by actor alone. |
| Reporting dashboard | `ReportingReadRepository` and Reporting scope/validator; aggregates branch-period sales and persisted refund, void, correction, Credit, Coupon and Expense evidence. | Branch totals and payment breakdowns become incomplete if cross-cashier evidence is dropped. Permission/branch scope must be enforced server-side. |
| Invoice list / invoice detail | `InvoiceRepository` and customer invoice projections; links invoice documents to transaction evidence and payment status. | Paid/unpaid status may be stale or wrong if repayment/settlement evidence is missing. |

This is the complete dependency list found in the reviewed read repositories and screen paths, not a claim that every DAO/UI consumer in the entire repository has been exhaustively traced. Before implementation, search all consumers of the financial-download feed, every Room table materialized from envelopes, and all balance/report DAO queries; add any uncovered consumers to this list.

## Recommended server-side summary design

1. Keep the existing branch download contract unchanged until parity tests exist; **do not apply an actor-only filter**.
2. Add authenticated, branch-scoped read RPCs/endpoints that derive results from the authoritative financial envelope and append-only Credit/Coupon ledger evidence:
   - Customer Credit summary: signed balance, per-invoice outstanding, repayment/allocation summary and source watermark.
   - Coupon wallet summary: ledger-derived balance, event history required by the screen, purchase/top-up/refund classification, and authorized-user state.
   - Shift-close summary: totals by cash/online/Credit/Coupon, refunds/voids/corrections, expected vs counted cash, difference, and the exact shift/operation watermark.
   - Reporting aggregates: branch-period totals plus permission-aware drill-down, with a server-computed watermark.
3. Include business/branch IDs, as-of timestamp or monotonic server watermark, currency/minor-unit definitions, and source operation IDs. Use deterministic ordering, bounded pagination, idempotent projection rebuilds, and server-side authorization on every call.
4. Cache the last verified summary locally only for offline display and label it stale/as-of time. Queued offline financial commands remain separate; summaries never become a write source of truth. Reconcile queued commands on reconnect and update projections only from server-accepted evidence.
5. Keep broad raw History behind `can_view_broad_history`. A summary RPC is not permission to expose raw envelopes. The current broad download should not be narrowed until these replacement paths and offline behavior are shipped and tested together.

### Trade-off

**Benefits:** smaller downloads, less raw cross-cashier/customer data on each device, less local materialization and simpler server-side permission enforcement; ledger-derived summaries centralize calculations and reduce client drift.

**Costs:** more RPC/API work and server load; summary freshness depends on network/sync lag; offline screens can only show cached as-of values; detailed history still needs an explicitly authorized data path; server/client calculation parity and retry/idempotency tests are required. The safest migration is staged: add summaries, dual-read/compare them against Room, instrument mismatches, then change consumers, and only then consider narrowing raw downloads in a separate approved change.
