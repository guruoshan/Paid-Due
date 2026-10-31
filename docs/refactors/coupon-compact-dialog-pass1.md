# CouponCompactDialogR7A Decomposition — Pass 1 Report

## Scope and baseline

- Repository: `guruoshan/Paid-Due`
- Base: `main` at `a7d7507a91c272e8c3aa5a01eab427117e403930`
- Branch: `refactor/coupon-compact-dialog-decomposition`
- Current implementation commit: `6de163888d58a465c50d915660f93ea9b7c77a19`
- Source file: `app/src/main/java/com/dailymoneyregister/feature/register/ui/CouponCompactDialogR7A.kt`

## Line counts

| Measure | Lines |
|---|---:|
| Host before extraction | 1,898 |
| Host after extraction | 1,850 |
| Host net reduction | **48** |
| Lines relocated from host into components | **78** |
| New component source lines | 78 (40 + 38 in Git diff accounting) |

The host is shorter by 48 lines because the two extracted sections (78 lines) are replaced by 30 lines of component calls and arguments.

## Files changed

1. `app/src/main/java/com/dailymoneyregister/feature/register/ui/CouponCompactDialogR7A.kt` — imports and calls the two extracted presentation composables; all state and orchestration remain in the host.
2. `app/src/main/java/com/dailymoneyregister/feature/register/ui/coupon/components/CouponHeader.kt` — extracted title and Cancel row.
3. `app/src/main/java/com/dailymoneyregister/feature/register/ui/coupon/components/CouponSelectedCustomerDetails.kt` — extracted selected customer name, phone and optional wallet-balance text.
4. `docs/refactors/coupon-compact-dialog-plan.md` — Phase 1 inventory and extraction plan.
5. `docs/refactors/coupon-compact-dialog-pass1.md` — this report.

## Extracted composables

- **CouponHeader** receives the already-resolved title, existing dismiss-enabled flag and existing dismiss callback. The host retains title-mode selection and lock-state calculation.
- **CouponSelectedCustomerDetails** receives the selected customer's display strings and existing weighted modifier. The host retains the selected-customer container, change-customer action, all state resets and wallet-close callback.

No state, calculations, validation, ViewModel interactions, repository calls, payment handling, coupon orchestration, receipt generation or WhatsApp handling moved.

## Contract and callback parity

- `CouponCompactDialogR7A` parameter signature: unchanged.
- `CouponCompactDialogActions`: exactly 11 callback declarations; names, types and order unchanged.
- Callback invocation occurrences in the host: 13 before and 13 after, in the same order.
- Customer-change state reset and `onCloseWallet` call remain in the host.
- Main confirmation routing remains in the host and continues to call the existing refund, redemption, top-up and sale callbacks.
- Receipt-details loading and all receipt/WhatsApp flows remain in the host.

## Excluded areas

No changes to coupon or top-up calculations, payment processing, validation, repository calls, ViewModel contracts, navigation, sync, database code, business rules, receipt generation or WhatsApp workflows.

## Risk assessment

**Low, presentation-only risk.** Both extractions are leaf composables receiving values and an existing callback/modifier. They do not own remembered state or business orchestration. The changes are easily reversible by restoring the original header row and customer details column in the host and deleting the two component files.

## Validation results

### Static review — passed

- Reviewed the branch diff against the stated base.
- Only the host, two new presentation components and the two requested documentation files are changed.
- Host screen parameters and the 11-callback actions contract are byte-for-byte identical to the base.
- All 13 callback invocation occurrences remain in the host in the same order.
- No calculation, validation, payment, top-up, refund, sale, redemption, receipt, WhatsApp or ViewModel orchestration was moved.

### Build and automated tests — pending at report creation

- Android PR Validation run #870: [workflow](https://github.com/guruoshan/Paid-Due/actions/runs/41152478110) — `Run unit tests, instrumentation tests, and assemble debug APK` was still in progress.
- Paid-Due repair branch validation run #1446: [workflow](https://github.com/guruoshan/Paid-Due/actions/runs/41152478129) — debug build and unit tests were still in progress.
- Release validation run #588: [workflow](https://github.com/guruoshan/Paid-Due/actions/runs/41152478023) — unit tests passed; lint and release build steps were still running.
- No local Gradle build was run in this session.

Update this section with final conclusions before treating the PR as merge-ready.
