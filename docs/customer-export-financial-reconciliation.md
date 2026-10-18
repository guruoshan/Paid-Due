# Customer Excel Export — Financial Reconciliation Contract

## Identity and scope
- Export only records owned by the active business.
- Customer identity is `customers.id`. Phone numbers are displayed contact attributes and are never used to merge or reassign a financial row.
- Financial summaries are keyed by customer ID and currency code. No FX conversion or cross-currency addition is performed.

## Source-of-truth rules
- Original transaction amounts come from `transactions`; the latest `amount_correction_events.corrected_amount_minor` is used where present.
- A transaction with a `void_events` record remains visible in history with a void status, but is excluded from valid sales totals.
- Refund events are shown as negative amounts in the history and deducted from net sales in the summary.
- Repayments are shown as cash/online collections against credit. They are not sales revenue and are not added to net sales.
- Issued documents are audit/history rows only. Invoice values are not counted a second time as sales.
- Outstanding credit is the signed sum of `credit_ledger_events.amount_signed_minor`, grouped by customer and currency. It is not inferred from sales less repayments because adjustments and historical events may exist.
- Credit obligations are compared with original sale credit funding, including voided sale originals. The variance is surfaced for investigation rather than silently forced to zero.
- Currency precision uses the transaction snapshot when available; ledger-only currencies use the known branch precision for that currency or the app currency registry. Conflicting precision for the same currency stops the export instead of combining unlike minor units.

## Workbook controls
- **Customer Directory:** identity/contact data and activity counts only; it does not aggregate unlike currencies.
- **Customer Financial Summary:** financial values separated by customer and currency, with valid sales, credit funding, repayments, refunds, coupon purchases, net sales after refunds, authoritative outstanding credit, and the credit-obligation variance.
- **Full Transaction History:** transaction, repayment, refund, and document events. Refund amounts are negative; document rows have a blank amount and are explicitly excluded from financial totals.
- **Credit Reconciliation:** sale credit funding vs credit-ledger obligations, ledger repayments, adjustments, outstanding balance, and a visible PASS/INVESTIGATE control.

## Required release checks
1. Android build, unit tests, and instrumentation tests pass on the exact head SHA.
2. Validate the generated XLSX with an independent workbook reader (e.g. Apache POI or Python openpyxl) and confirm all four worksheets open.
3. Fixture-based tests cover OMR (3 decimals) and INR (2 decimals), same-phone customer IDs without cross-merging, a corrected sale, a voided sale, partial/full refunds, credit sale and repayment, credit adjustment, invoice+receipt documents, and a coupon purchase.
4. Reconcile each fixture's sale, refund, credit-ledger balance, and variance to the expected minor-unit arithmetic. No totals may be compared across currencies.
5. A real database export should be compared to authoritative ledger queries before release; a green build alone is not accounting sign-off.

## Known boundary
This workbook is an operational customer statement/reconciliation tool. It is not a tax return or statutory financial statement. Where credit-obligation variance is non-zero, the workbook flags it for review; it does not alter the ledger or invent a balancing entry.
