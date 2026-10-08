---
name: financial-integrity-auditor
description: Financial data integrity, immutable transaction ledger auditing, exact monetary calculations, and SQLCipher encryption validation. Use when auditing monetary transactions, balance reconciliation, double-entry ledgers, or payment state transitions.
---

# Financial Integrity Auditor

## Activation Criteria
- Modifying or auditing monetary balances, payment transactions, expenses, or credit records.
- Verifying database encryption, SQLCipher passphrase handling, or local ledger state.
- Designing or reviewing ledger schema migrations and balance calculation logic.

## Engineering Standards
- **Monetary Precision**: Use exact integer representations (cents/minor units) or `BigDecimal`. Never use binary floating-point (`Float`/`Double`) for financial calculations.
- **Immutable Evidence**: Treat financial transactions as immutable append-only ledger entries. Voiding or adjusting transactions requires posting explicit offsetting records.
- **Atomic Operations**: Wrap all multi-entity financial balance updates in single, atomic database transactions (`@Transaction` in Room).
- **SQLCipher Integrity**: Ensure database encryption remains active and passphrases are retrieved securely via Android Keystore.
- **Reconciliation & Auditing**: Maintain explicit audit trails with timestamp, device ID, user ID, and strict sequence counters for every ledger entry.

## Verification Steps
1. Execute monetary precision tests: verify edge cases (zero values, negative adjustments, rounding).
2. Validate transaction atomicity: simulate failure mid-transaction and verify database rollback.
3. Run `./gradlew testDebugUnitTest --tests "*Financial*" ` to confirm balance reconciliation logic.
