# PaidnDue — Project Governance

**File:** `PROJECT_GOVERNANCE.md`
**Status:** AUTHORITATIVE PROJECT RULEBOOK
**Version:** 1.4
**Updated:** 2026-09-20

This file governs all PaidnDue development, bug fixes, refactors, database changes, UI work, tests, AI/Codex tasks, cloud work, and Play Store releases.

---

## 1. Authority

Every developer or AI agent MUST read this file before changing production code.

When rules conflict, use this order:

1. Applicable law and regulatory requirements.
2. Mandatory current Android / Google Play release requirements.
3. This `PROJECT_GOVERNANCE.md`.
4. Committed financial/database invariants and approved architecture.
5. Automated tests that encode approved behavior.
6. Approved feature specifications.
7. Individual implementation prompts.

If an external requirement appears to conflict with PaidnDue financial integrity or security, STOP and redesign safely. Never silently weaken either side.

Changes to this file require a dedicated review, diff, approval, and separate commit.

Committing this file does NOT mean the current code is automatically compliant. Existing gaps must be audited and classified before related production work continues.

---

# 2. GOLDEN FINANCIAL LAW

## SAVED MONEY NEVER GETS REWRITTEN. CHANGES BECOME HISTORY.

Once a financial fact is committed:

- never silently edit, replace, or delete it;
- later changes become new append-only lifecycle/audit records;
- changes reference the original transaction/subject;
- history must remain reconstructable.

This applies to sales, settlements, corrections, payment-method changes, voids, refunds, credit, repayments, coupons, cash shifts, and future money-bearing features.

Where applicable, preserve:

- original transaction/subject ID;
- operation ID;
- business, branch, actor/cashier, and device identity;
- original and change timestamps;
- before/after values;
- amount, payment method, reason;
- supporting document/media references.

An unsaved register draft is not a financial record and may be edited or cleared freely.

---

# 3. ONE FINANCIAL SOURCE OF TRUTH

PaidnDue MUST have one authoritative financial truth.

Recent, History, Details, receipts, invoices, reports, exports, dashboards, credit balances, coupon balances, refunds, corrections, void status, and future cloud views must derive from the same authoritative lifecycle/read model wherever practical.

Never create a second independent copy of financial state for UI convenience.

Derived caches are allowed only when they can be rebuilt and cannot become financial truth.

## 3A. TRANSACTION LIFETIME AND PERIOD FINANCIAL TRUTH

### One transaction identity for its entire lifetime

A financial transaction is created once and keeps the same `transaction_id` for its entire lifetime.

All later actions affecting that transaction MUST remain linked to the original `transaction_id`.

This includes, where applicable:

- partial refund;
- full refund;
- void;
- amount correction;
- payment-method change;
- settlement correction;
- return;
- dispute/reversal;
- any future financial lifecycle action.

The original committed transaction MUST NOT be replaced, rewritten, duplicated, or deleted in order to represent a later change.

Later actions MUST be represented as append-only lifecycle/audit records and, where money actually moves or financial value changes, by the corresponding authoritative signed financial entries.

An individual lifecycle action may have its own operation/event ID for idempotency, audit, retry, actor attribution, and crash recovery, but it remains part of the lifetime of the original transaction.

### Original transaction remains immutable

Example:

A customer purchases OMR 10.000 on 19/09/2026.

PaidnDue creates:

`TX-1001 = original sale OMR 10.000`

If the customer receives a partial refund of OMR 3.000 on 20/09/2026, PaidnDue MUST NOT create another independent sale transaction and MUST NOT rewrite the original OMR 10.000 sale.

The transaction lifetime becomes:

- 19/09/2026 — Original sale: `+10.000`
- 20/09/2026 — Partial refund: `-3.000`
- Current transaction net: `7.000`
- Current status: `PARTIALLY_REFUNDED`

If another OMR 2.000 is refunded later:

- Original amount remains: `10.000`
- Total refunded: `5.000`
- Current transaction net: `5.000`

If all remaining refundable value is later refunded:

- Original amount remains: `10.000`
- Total refunded: `10.000`
- Current transaction net: `0.000`
- Current status: `FULLY_REFUNDED`

The transaction ID remains `TX-1001` throughout its lifetime.

### Period financial truth uses the date of the financial activity

Daily, weekly, monthly, custom-period, report, dashboard, and export totals MUST reflect financial activity that actually occurred inside that period, regardless of when the original transaction was created.

The authoritative period is determined using the applicable branch timezone.

Therefore:

**Period financial total = the authoritative signed financial effects whose `occurred_at` falls inside that period.**

Example for 20/09/2026:

- New sales today: `+100.000`
- Refund today from yesterday's sale: `-3.000`
- Refund today from last month's sale: `-5.000`
- Valid void reversal today: `-10.000`

Daily financial total:

`100.000 - 3.000 - 5.000 - 10.000 = OMR 82.000`

The age of the original transaction does not move a refund, correction, reversal, or other financial effect back to the original sale date.

A refund performed today affects today's financial activity even if the original sale occurred yesterday, last month, or an earlier valid period.

A period total may legitimately be negative when financial outflows/reversals in that period exceed inflows.

### Refund rule

A refund MUST:

- reference the original `transaction_id`;
- have its own immutable lifecycle/operation identity;
- record its authoritative `occurred_at`;
- record the actor/cashier;
- record the refund amount;
- preserve the reason where applicable;
- reduce the authoritative refundable/current value of that transaction;
- affect financial-period reporting at the date/time when the refund actually occurred.

Partial refunds are allowed until the authoritative refundable remainder reaches zero.

Cumulative refunds MUST NEVER exceed the authoritative refundable amount.

A full refund does not erase the original sale. It reduces the current transaction net to zero while preserving the complete original and refund history.

### Void rule

A void MUST remain linked to the original transaction.

A void MUST NOT cause money already reversed by a previous refund or other valid reversal to be deducted again.

The financial effect of a void must reverse only the authoritative remaining value that has not already been reversed.

Example:

- Original sale: `10.000`
- Previously refunded: `3.000`
- Remaining value: `7.000`

A later valid void MUST NOT deduct another `10.000`.

The maximum remaining financial reversal is `-7.000`.

PaidnDue MUST prevent double deduction, duplicate reversal, and negative remaining transaction value unless a future explicitly approved financial model requires otherwise.

### Amount-correction rule

Amount corrections MUST preserve the original committed amount and create append-only correction history.

The period financial effect of an amount correction is the authoritative difference between the valid previous amount and corrected amount, not the entire corrected transaction amount as a second sale.

Example:

Original amount: `10.000`
Corrected amount: `12.000`

Financial adjustment:

`+2.000`

If corrected from `10.000` to `8.000`:

Financial adjustment:

`-2.000`

Reports MUST NOT count the complete corrected amount as another independent transaction.

Correction chains must be internally consistent and fail closed if their before/after history cannot be reconciled.

### Payment-method-change rule

A payment-method change does not by itself change the total economic value of the transaction.

Example:

`CASH -> ONLINE`

The transaction remains the same transaction and retains the same financial amount.

The change updates authoritative payment classification and audit history.

It MUST NOT create duplicate revenue.

If changing payment method requires actual movement between settlement channels, those signed financial movements must be represented explicitly through the approved authoritative settlement model rather than being inferred or manufactured by UI state.

### Recent financial activity rule

Recent represents financial activity occurring today in the active branch timezone.

A transaction may therefore appear in today's Recent activity even when its original transaction occurred on an earlier date if a relevant refund, void, correction, payment-method change, settlement change, or other approved lifecycle action occurred today.

The transaction remains one transaction identity.

Recent MUST derive transaction financial meaning from the authoritative lifecycle/read model.

Recent MUST NOT maintain an independent copy of transaction financial state or independently recalculate financial truth.

The financial effect contributing to today's total must reflect today's authoritative signed activity, not the entire lifetime/current balance of an older transaction.

### Transaction Details rule

Transaction Details represents the complete lifetime of one transaction.

Where applicable it must preserve and derive from authoritative records:

- original transaction ID;
- original amount;
- original date/time;
- original payment method;
- original actor/cashier;
- every correction;
- every partial/full refund;
- every void;
- every payment-method change;
- every related settlement;
- actor for each lifecycle action;
- date/time of each lifecycle action;
- before/after amounts or classifications;
- reasons;
- operation IDs;
- current authoritative amount;
- cumulative refunded amount;
- remaining/current net amount;
- current lifecycle status.

The full lifetime must remain reconstructable chronologically.

### Reports, dashboards, and exports rule

Reports, dashboards, exports, daily totals, and future cloud analytics MUST distinguish:

1. transaction lifetime truth; and
2. financial activity occurring inside the requested reporting period.

The original sale remains associated with its original occurrence date.

Later refunds, corrections, reversals, and other financial movements affect the period in which those actions actually occurred.

No report may move a later financial event backward into the original transaction date merely to simplify calculation.

No report, dashboard, export, or cloud projection may establish an independent financial source of truth.

### Multi-screen consistency rule

Recent, History, Transaction Details, Reports, receipts, invoices, exports, dashboards, credit/coupon views where relevant, and future cloud views MUST agree on the same authoritative transaction lifetime.

They must not disagree about:

- original transaction identity;
- original amount;
- lifecycle events;
- current amount/net;
- refunded amount;
- void state;
- payment classification;
- settlement effects;
- financial impact by period.

Different screens may present different projections of the same underlying truth, but they MUST NOT maintain different financial truth.

### Implementation prohibitions

Future features MUST NOT:

- create a new independent transaction merely because an existing transaction changed;
- overwrite the original committed transaction amount to represent a later event;
- overwrite or erase original payment history;
- delete valid lifecycle history;
- calculate refunds independently inside UI code;
- calculate period financial totals only from current transaction balances;
- double-count refunded, corrected, or voided money;
- deduct already reversed money again;
- move later financial activity back to the original transaction date;
- silently use device/system timezone when authoritative branch timezone is required;
- introduce a second financial source of truth for convenience.

If a proposed feature cannot comply with this rule, implementation MUST STOP for BOARDLOCK financial-architecture review.

### Required lifecycle tests

Before any refund, void, correction, payment-method-change, return, or similar lifecycle feature is considered complete, tests must prove the relevant behaviors, including at minimum:

- old transaction plus today's partial refund;
- old transaction plus today's full refund;
- multiple partial refunds;
- cumulative refunds cannot exceed the authoritative refundable amount;
- refund affects the period in which the refund occurs, not the original sale period;
- partial refund followed by void does not double-deduct money;
- amount correction contributes only its authoritative delta to period totals;
- payment-method change does not duplicate transaction revenue;
- the same original `transaction_id` remains throughout the lifecycle;
- the original committed transaction remains unchanged;
- lifecycle events remain append-only;
- Recent, History, Details, Reports, and exports derive consistent financial results when those features exist;
- branch timezone determines financial-period boundaries;
- retries/recovery cannot duplicate lifecycle money;
- malformed or contradictory lifecycle history fails closed.

### Permanent transaction-lifetime principle

**ONE TRANSACTION. ONE LIFETIME. ONE FINANCIAL TRUTH.**

The transaction is created once.

Every later financial action becomes immutable history linked to that transaction.

Period totals reflect when financial activity actually occurred, while the original transaction remains permanently preserved.

---

# 4. MONEY AND DATABASE CORRECTNESS

### Money

- Store money as integer minor units (`Long`), e.g. OMR 1.500 = `1500`.
- Never use `Float` or `Double` for financial values or calculations.
- Use exact arithmetic, explicit rounding, overflow checks, and currency/precision validation.
- Never silently truncate financial precision.
- Never mix currencies without an explicitly designed FX feature.

### Database

Every entity must be classified as one of:

- immutable financial fact;
- append-only lifecycle event;
- mutable operational metadata;
- mutable configuration;
- derived/cache data.

Before adding **or using** any money-bearing table, verify whether it requires:

- no UPDATE;
- no DELETE;
- no replace/upsert;
- restricted foreign-key deletion.

Existing financial tables, including repayment/allocation/document-related tables, must be explicitly audited and classified before their feature goes live.

Never use destructive migration for customer financial data.

Every schema change requires:

- migration design;
- compatibility review;
- migration test;
- preservation of IDs and financial history;
- failure/recovery reasoning.

Room schema export remains enabled.

---

# 5. FINANCIAL OPERATION RELIABILITY

Every durable financial command must be designed for:

- atomicity;
- idempotency;
- crash recovery;
- duplicate prevention;
- concurrency;
- retry safety;
- auditability.

A financial operation must either fully succeed or have no committed partial financial effect.

Retries must never create duplicate money.

Durable financial operations should have:

- globally safe operation ID;
- request fingerprint/equivalent;
- durable status;
- subject/transaction identity;
- explicit retry/recovery rules.

If partial state cannot be safely understood, FAIL CLOSED until reconciled.

For multi-device/cloud operation:

- IDs must be globally unique;
- server-side tenant/business authorization is mandatory;
- conflicts must be explicit;
- timestamps alone must not decide financial truth;
- **last-write-wins must never erase a legitimate committed financial event**;
- retries and offline reconciliation must remain idempotent.

---

# 6. ARCHITECTURE AND OFFLINE-FIRST

Preferred flow:

`Compose UI → ViewModel/UI state → Repository/domain logic → Room/network data source`

Rules:

- UI never owns financial truth.
- Composables do not write financial tables or make security decisions.
- ViewModels coordinate screen state/events, not permanent financial state.
- Repositories own normal business/data operations.
- Higher layers should not bypass repositories to write DAOs.
- Prefer unidirectional data flow.
- Use dependency injection for important dependencies.
- Reuse shared foundations; do not duplicate money math, permissions, durable command formats, audit logic, or read models.

PaidnDue remains usable without reliable internet.

Future cloud sync must preserve local usability and must not create a second conflicting financial truth.

Use WorkManager only when persistent deferrable background work actually exists.

---

# 7. SECURITY AND PRIVACY

Security is a product requirement from the beginning.

### Secrets and credentials

Never store or ship:

- plaintext PINs/passwords;
- private/server/service-role keys;
- signing private keys;
- database encryption keys in source;
- sensitive secrets in logs, screenshots, resources, BuildConfig, APK/AAB, or Git.

Passwords/PINs require proper salted slow hashing/KDF and safe migration when parameters change.

Use Android Keystore or equivalent secure platform facilities where appropriate.

### Network and storage

- Production traffic uses HTTPS/TLS.
- Cleartext traffic stays disabled unless a narrowly approved development exception exists.
- Never disable certificate validation.
- Financial/customer data uses app-private storage.
- Database-at-rest encryption must not be removed for convenience.

### Permissions

Request only permissions required by a real feature, and only when needed.

Do not request contacts, SMS, call logs, storage, location, microphone, camera, notifications, or nearby-device permissions “for future use.”

Handle optional permission denial gracefully.

### Privacy

Collect the minimum personal data needed.

Before adding sensitive/personal fields, define:

- purpose;
- storage;
- whether it leaves the device;
- who can access it;
- retention;
- deletion/anonymization policy.

Play Data Safety and privacy policy must match actual app behavior and third-party SDK behavior.

If Play account-deletion rules apply, provide the required deletion/request paths while preserving legally required financial/audit records through proper retention/anonymization design.

---

# 8. TRUSTED SESSION — APPROVED PRODUCT SECURITY DECISION

PaidnDue intentionally does **not** use a traditional idle lock for normal trusted-device operation.

This is a conscious product/security decision for the current register/accountability model, **not** a general Android recommendation.

Approved behavior:

- Admin/Cashier authenticates initially.
- Same trusted installation may remain signed in across app close/restart/process death.
- Normal Cash Sales do not repeatedly request PIN.
- Logout ends the trusted session.
- Future Switch Cashier ends the current identity and requires the next cashier to authenticate.
- New/untrusted device requires authentication.
- Cashier forced-PIN-change rules remain enforced.
- Sensitive future operations may require permission checks or selective reauthentication only when explicitly designed.

Do not introduce idle auto-lock, repeated PIN prompts, or a lock screen without an approved product/security decision.

Wrong-PIN throttling remains separate from idle-lock behavior.

Re-review this decision before introducing:

- direct card/SoftPOS credential handling;
- higher-risk regulated data;
- remote administration with materially greater privileges;
- unattended/shared-device deployments with a different threat model;
- enterprise/customer requirements that mandate session expiry.

---

# 9. KEEP THE APP LIGHT

Small download/install size is a permanent product requirement.

### Dependencies

Before adding a library/SDK, answer:

1. Is it necessary?
2. Can Android/Jetpack/Kotlin already do it?
3. What size does it add?
4. Does it add native `.so` files?
5. What permissions/data collection/background work does it add?
6. Is it maintained and secure?
7. What future removal/migration cost does it create?

Avoid large SDKs for small features.

Do not add analytics, ads, attribution, maps, chat, social, AI, payment, or marketing SDKs casually.

### Assets

- Prefer vector icons where practical.
- Compress raster images.
- Avoid duplicate/oversized assets.
- Avoid unused fonts.
- Do not package `.bak`, databases, patches, logs, screenshots, test dumps, or developer artifacts.

### Release optimization

- Use Android App Bundle for Play distribution.
- Keep R8/minification enabled for release once verified.
- Use resource shrinking when proven safe.
- Never use broad keep rules just to silence R8.
- Inspect AAB/APK size before release.
- Investigate unexplained size growth.

Size optimization must NEVER weaken financial correctness, security, privacy, or maintainability.

Do not replace a proven secure library with custom code merely to save a few MB.

---

# 10. PERFORMANCE, ACCESSIBILITY, LOCALIZATION

### Performance

- No blocking DB/network/crypto work on the main UI thread.
- Avoid unnecessary recomposition.
- Bound/paginate large histories.
- Avoid N+1 database patterns.
- Use indices for real query patterns.
- No unnecessary polling, wake locks, foreground services, or aggressive background jobs.
- Measure performance before optimizing.

Review Android Vitals after release for crashes, ANRs, battery, memory, and device-specific regressions.

### Accessibility

Important UI must support:

- TalkBack semantics;
- useful content descriptions;
- logical focus order;
- readable contrast;
- text scaling;
- adequate touch targets;
- no critical information communicated only by color.

### Localization

- User-facing text belongs in string resources.
- Avoid hard-coded English in production UI.
- Keep supported-language keys aligned.
- Use Start/End instead of Left/Right where direction matters.
- Test Arabic/Urdu RTL behavior.
- Translation must never change financial meaning.

---

# 11. PAYMENT / CARD SAFETY

If PaidnDue later accepts cards, SoftPOS, online payment, wallets, or bank/payment-provider integrations:

- use approved/certified provider integrations;
- perform a dedicated security/compliance review;
- do not invent custom card-processing/security protocols;
- never casually store raw card PAN, CVV, or equivalent sensitive authentication data;
- do not log payment credentials/tokens unnecessarily;
- keep payment-provider secrets out of the app;
- preserve the same immutable financial/audit model for resulting settlements.

Play Integrity or similar anti-abuse services may be added only when the threat model justifies them. They are not mandatory merely because they exist.

---

# 12. TESTING AND CHARACTERIZATION SAFETY

Testing is part of implementation.

Before refactoring proven financial behavior:

1. characterize current behavior if not already protected;
2. prove the characterization against untouched production behavior;
3. commit the safety net separately;
4. then refactor.

Maintain appropriate tests for:

- money math;
- repositories/domain;
- integration;
- database migrations;
- financial immutability;
- encryption;
- auth/security;
- idempotency;
- concurrency;
- crash recovery;
- localization/RTL;
- accessibility/UI where relevant;
- minified release behavior.

Never weaken a valid test just to make it pass.

### Characterization input vs expected-output rule

A production encoder/constructor/helper MAY be used to create **test input or fixtures** when that component itself is not what the test is characterizing.

But expected/golden results MUST be independently frozen from approved observed behavior or independently specified requirements.

Never compute an expected result using:

- the production function being characterized;
- a helper that shares the same production logic;
- the same encoder/decoder when the codec itself is under test.

Example:

- Materialization test: production codec may build/seed the input payload if the codec is outside the refactor scope; expected transaction/settlement values must still be independently fixed.
- Codec test/refactor: use frozen/static or independently produced input and expected output; do not use the production codec to prove itself.

Do not fabricate golden values.

---

# 13. DEVICE / AVD TEST SAFETY

Unknown device history does **not** mean the device is safe.

Before any device-affecting command:

- identify the exact physical device/AVD;
- verify its serial and expected identity;
- explicitly target that serial for every ADB command;
- STOP if target identity is uncertain or mismatched.

When multiple devices are connected, never rely on untargeted ADB or broad connected-device test tasks.

Stateful physical phones and stateful emulators are non-destructive targets by default.

Without explicit approval for that exact target, DO NOT:

- `pm clear`;
- uninstall the app;
- delete app databases/shared preferences/files;
- reset setup/business/user state;
- change or reset credentials;
- factory reset;
- modify real financial records;
- run destructive migration/recovery experiments.

Prefer a disposable, identity-verified emulator for destructive tests.

On a stateful device, use only tests proven not to alter real user/business/financial data. In-memory instrumentation tests are preferred when suitable.

`adb install -r` may be used only when signature compatibility is known and preserving app data is intended. If uninstall becomes necessary, STOP for approval.

Do not treat an ADB/PowerShell exit code alone as proof of instrumentation success; inspect actual test output (`OK`, failures, individual statuses).

Manual/emulator testing is not a substitute for automated tests and must be targeted to a real unanswered risk.

---

# 14. BOARDLOCK CHANGE CONTROL

### Before changing code

- inspect/read first;
- do not guess paths, package names, versions, APIs, or test names;
- understand final-product impact;
- identify source of truth;
- identify financial/security/privacy impact;
- identify tests that protect current behavior;
- preserve a known-good state before risky changes.

### Human review checkpoint for high-risk work

High-risk changes require a **proposal → human review/approval → implementation** stop.

An AI/developer MUST NOT design, approve, implement, and declare success on the same high-risk architecture decision without the required human checkpoint.

High-risk includes:

- financial schema/invariant changes;
- immutability/audit-model changes;
- database encryption/key handling;
- authentication/session security model changes;
- cloud sync/conflict strategy;
- multi-device financial behavior;
- payment/card/SoftPOS integration;
- destructive migrations/device operations;
- release signing/keystore process;
- major storage/security-sensitive dependency upgrades;
- governance changes that weaken protections.

The proposal should state:

- problem/evidence;
- intended change;
- affected invariants;
- alternatives considered;
- migration/rollback risk;
- exact validation plan.

Implementation begins only after explicit approval.

### During changes

- one hypothesis/change at a time;
- no unrelated cleanup;
- no speculative rewrite;
- no dependency/Gradle/SDK/database change without direct evidence and need;
- no production safeguard weakening to pass a test;
- no device/API workaround before proving the device/API problem;
- if a new failure appears immediately after our change, suspect/revert our change first;
- after two failed fixes for the same issue, STOP and reassess;
- never call an issue “environment” without evidence;
- never close a phase with a known relevant gap.

### After approved implementation

Preferred flow:

1. bounded implementation;
2. one senior audit;
3. automated tests;
4. targeted runtime proof when required;
5. `git diff --check`;
6. inspect exact changed files;
7. stage explicit approved paths only;
8. commit;
9. push;
10. verify remote commit when important.

Never use `git add .` for controlled PaidnDue work.

Do not mix unrelated financial, auth, UI, dependency, schema, and documentation changes in one commit without an approved reason.

---

# 15. AI / CODEX RULE

Every coding-agent prompt must instruct the agent to:

1. read `PROJECT_GOVERNANCE.md`;
2. state which rules apply;
3. inspect existing relevant code first;
4. preserve financial/security invariants;
5. stop for human approval when §14 classifies the work as high-risk;
6. avoid unrelated modifications;
7. run required tests;
8. report exact changed files;
9. not commit/push unless explicitly authorized.

AI output is not trusted merely because it compiles.

AI may audit its implementation, but that audit does not replace the required human checkpoint for high-risk design decisions.

---

# 16. PLAY STORE / RELEASE GATE

Before every production release, re-check current official Android and Google Play requirements. Do not rely on old hard-coded policy numbers.

Use `RELEASE_CHECKLIST.md` as the mechanical pre-release checklist.

`RELEASE_CHECKLIST.md` is a governed project-control artifact. Changes to it follow the same review, diff, approval, and separate-commit discipline defined in §1 for this governance file.

At minimum, no release may proceed with:

- a destructive financial overwrite path;
- failing required tests;
- known data-loss or duplicate-money defects;
- known authentication bypass;
- exposed secrets;
- known critical/high security blocker;
- known normal-flow crash/ANR blocker.

PaidnDue currently uses native SQLCipher, so native compatibility must be checked before each Play release rather than assumed.

---

# 17. FINAL PRODUCT RULE

Design from the FINAL PRODUCT backward.

Before implementing any feature, consider:

- complete user journey;
- authoritative data model;
- financial/accounting effect;
- lifecycle/history;
- permissions;
- security/privacy;
- offline behavior;
- synchronization/concurrency;
- reports/audit;
- accessibility/localization;
- app size/performance;
- release/store impact;
- future extensibility.

Do not solve only today’s screen if it creates tomorrow’s architectural conflict.

---

# FINAL PRINCIPLE

PaidnDue must remain:

**financially correct, auditable, secure, offline-capable, lightweight, testable, scalable, privacy-conscious, and Play-ready.**

Speed of development never justifies weakening those properties.

## SAVED MONEY NEVER GETS REWRITTEN. CHANGES BECOME HISTORY.
