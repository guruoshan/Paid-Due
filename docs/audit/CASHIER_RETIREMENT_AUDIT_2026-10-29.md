# CASHIER Role Retirement Audit and Implementation Report

**Date:** 2026-10-29  
**Repository:** `guruoshan/Paid-Due`  
**Base audited:** `main` at `18b32c72849644cb4dd188da97bdf220cdb7bb1e`  
**Implementation branch:** `retire-cashier-admin-only-final`  
**Scope:** Android repository, SQL migrations/tests, sync/session/authorization, reporting and legacy data contracts.

## Executive result

The earlier retirement work already removed most staff invitation/management RPCs and cashier-facing screens. This follow-up closes the main remaining active authorization gaps: legacy cashier accounts can no longer be authorized by `PermissionChecker`, restored from trusted local sessions, materialized by the remote business restorer, or queued for financial upload. Unused cashier-roster DAO methods were removed. The final database migration blocks creation/reactivation of cashier memberships while preserving old inactive tombstones and actor IDs.

**Status: ADMIN-only active authorization paths are implemented on this branch, but release completion is not yet certified.** The migration has been committed to the repository only; it has not been applied to Supabase. CI/Android tests and disposable-database SQL behavior validation must pass before merge/deployment. This report does not claim a live Supabase schema inspection or deployment.

## Phase 1 — Discovery and classification

### A. Safe to delete / already retired

| Area | Findings and disposition |
|---|---|
| Staff invitation/activation/login | Existing `20261023050000_retire_cashier_feature.sql` drops staff invite, activation, cashier credential, restoration, session, permission-management and cashier financial-ingest RPCs. |
| Cashier sync session and download RPCs | The retirement migration removes callable cashier session and feed RPCs. Admin financial sync remains on its Admin contracts. |
| Cashier customer-directory RPCs | `20261023070000_remove_cashier_customer_sync_rpcs.sql` removes the old cashier-scoped list/upsert RPCs; Admin sync contracts remain. |
| Cashier notifications | `20261023080000_purge_cashier_notifications.sql` removes the retired notification feature data. |
| Cashier roster DAO | `app/src/main/java/com/dailymoneyregister/core/database/dao/UserDao.kt`: removed unused `getCashiers` and `observeCashiers`; repository search found no call sites outside the DAO. |
| Settings profile label | `app/src/main/java/com/dailymoneyregister/feature/settings/ui/SettingsScreen.kt`: active profile display now presents the Admin role rather than falling through to a cashier label. |
| Startup documentation | `app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt`: corrected obsolete comments that described cashier sessions as valid active authority. |

Historical migration files and staged SQL proposals are intentionally not deleted: they document prior schema evolution and are not current callable behavior.

### B. Converted to ADMIN-only

| File | Change |
|---|---|
| `app/src/main/java/com/dailymoneyregister/core/security/PermissionChecker.kt` | Only `UserRole.ADMIN` is permitted. Legacy cashier membership flags no longer grant any action; session-aware access rejects legacy cashier sessions. |
| `app/src/main/java/com/dailymoneyregister/core/security/TrustedSessionRestorer.kt` | Only active Admin/password sessions restore. The unreachable cashier/PIN restoration branch was removed; rejected sessions are invalidated and cleared. |
| `app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncOperationPolicy.kt` | Only Admin financial operations can enter the upload queue. Legacy role values cannot make an operation uploadable. |
| `app/src/main/java/com/dailymoneyregister/core/cloud/RemoteBusinessRestorer.kt` | Local materialization rejects non-Admin or non-password identities and no longer has a cashier-specific existing-user exception. |
| `app/src/test/java/com/dailymoneyregister/core/security/PermissionCheckerTest.kt` | Assertions now verify that legacy cashier actors and permission flags never authorize access, while Admin actions remain allowed. |
| `app/src/test/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncOperationPolicyTest.kt` | Verifies that Admin financial operations queue and legacy cashier actors do not queue. |
| `app/src/androidTest/java/com/dailymoneyregister/AmountCorrectionFinancialCoreIntegrationTest.kt` | Financial-integrity actors converted to Admin. Assertions were not changed. |
| `app/src/androidTest/java/com/dailymoneyregister/RefundFinancialCoreIntegrationTest.kt` | Financial-integrity actors converted to Admin. Assertions were not changed. |
| `app/src/androidTest/java/com/dailymoneyregister/VoidFinancialCoreIntegrationTest.kt` | Financial-integrity actors converted to Admin. Assertions were not changed. |
| `app/src/test/java/com/dailymoneyregister/feature/register/ui/CouponSaleViewModelTest.kt` | Role-incidental actor converted to Admin/password. |
| `app/src/test/java/com/dailymoneyregister/feature/register/ui/RegisterPresentationViewModelTest.kt` | Role-incidental actor converted to Admin. |
| `app/src/test/java/com/dailymoneyregister/feature/register/ui/RegisterSessionResetTest.kt` | Role-incidental actor converted to Admin. |

Tests that explicitly exercise actor authorization, cashier-denial, historical attribution, or shift isolation are retained as compatibility/security tests rather than mechanically rewritten.

### C. Historical compatibility — retained intentionally

| File/area | Reason |
|---|---|
| `app/src/main/java/com/dailymoneyregister/core/model/enums/ModelEnums.kt` — `UserRole.CASHIER` | Retained to decode legacy Room/database records and serialized data. Active authorization, restoration, and sync policy reject it. |
| `app/src/main/java/com/dailymoneyregister/core/model/enums/ModelEnums.kt` — cashier management `OperationType` values | Legacy operation envelopes and stored operation rows may contain these enum names. Retain decoding compatibility; do not reintroduce execution paths. |
| `ConfirmationType.CASHIER_CONFIRMED` | Existing settlements, serialized operations, and historical report evidence use this value. Do not remove or reinterpret stored evidence without a versioned compatibility migration. It remains a legacy naming concern for a separate carefully tested writer/read-contract change. |
| `cashierUserId` fields in `RegisterShiftModels.kt`, `ReportingAccess.kt`, `ReportingReadRepository.kt`, `ReportingDashboardViewModel.kt`, `ReportingModels.kt`, `ReportingViewModel.kt`, `RegisterViewModel.kt`, and corresponding reporting tests | These fields preserve historical shift/actor attribution and reporting filters. They must not be used as an authorization role or imply that cashier login is supported. A future schema/API rename to generic actor attribution should be additive and preserve old serialized payloads. |
| `supabase/migrations/20261023100000_restore_historical_actor_user_ids.sql` | Restores immutable actor IDs from canonical financial envelopes onto inactive cashier tombstones. It does not re-enable login; preserve this mapping for audit/report attribution. |
| Existing financial envelopes, operations, settlements, refund/void/correction evidence, encrypted Room rows and sync receipts | Historical accounting evidence is immutable and must remain readable/replayable. No financial tables or evidence were dropped by this branch. |

### D. Explicit security / authorization tests retained

The remaining test references in `OnlineSaleFinancialIntegrationTest.kt`, `CouponSaleIntegrationTest.kt`, `PaymentMethodChangeWriterTest.kt`, `RegisterShiftAuthorizationTest.kt`, `RegisterShiftFoundationTest.kt`, `CustomerRepositoryTest.kt`, `InvoiceLinkBackfillTest.kt`, `DmrDatabaseIntegrationTest.kt`, `ReportingRoomIntegrationTest.kt`, `SecurityAuthTest.kt`, `FinancialSyncEngineTest.kt`, `RegisterViewModelSessionTest.kt`, `RegisterFinancialResultGuardTest.kt`, `RegisterSessionContextSafetyTest.kt`, `RegisterFinancialSessionContextTest.kt`, `RegisterRecentSessionFlowTest.kt`, `ReportingScopeValidatorTest.kt`, `ReportingRowMappersTest.kt`, and `ReportingAdminOnlyAccessPolicyTest.kt` require case-by-case treatment. They cover denied actors, historical row migration/materialization, shift ownership, reporting actor filters, and stale-session rejection. They are not evidence that a cashier product screen is still supported.

## Phase 4 — Authorization review

| Component | Result on branch |
|---|---|
| `PermissionChecker` | Admin-only; legacy role/permission flags cannot authorize any action. |
| `TrustedSessionRestorer` | Admin/password only; stale cashier session is invalidated. |
| `ReportingAccess` | Reports remain Admin-only. Historical actor/cashier filter remains attribution-only, not an access grant. |
| `FinancialSyncOperationPolicy` | Admin-only upload queue. |
| `FinancialSyncEngine` | Its upload decision continues to use the policy; the policy no longer queues cashier operations. Historical envelope decoding/replay remains separate. |
| `RemoteBusinessRestorer` | Rejects non-Admin/PIN restoration/materialization. |
| Register/customer/invoice financial authorizers | Central permission checker now denies legacy cashier users; specific financial tests retain unauthorized-actor assertions. |

## Phase 5 — Database impact

### Existing state in repository history

`supabase/migrations/20261023050000_retire_cashier_feature.sql` already disables and tombstones cashier memberships, removes staff identities/permission-request/notification/session data and drops callable cashier/staff RPCs. The follow-up `20261023100000_restore_historical_actor_user_ids.sql` restores only historical actor IDs needed for financial attribution.

### New migration added

`supabase/migrations/20261029010000_enforce_admin_only_role.sql`

- Adds a validated constraint that permits a CASHIER row only as an inactive, removed, permissionless tombstone with no auth or staff identity link.
- Adds a trigger that rejects all new CASHIER membership inserts and blocks converting another role into CASHIER.
- Prevents reactivation or permission grants on historical cashier tombstones.
- Leaves `local_user_id` available on tombstones because it may be the historical actor mapping required by financial envelopes.
- Does not delete financial records, operation envelopes, settlement evidence, sync history, branches, businesses, Admin users, or memberships.
- Does not drop historical enum values or alter accounting calculations.

New contract test: `supabase/tests/20261029010000_admin_only_role_contract.sql`.

**Deployment state:** migration and test are committed to this branch only. They have not been applied to the live Supabase project. Run against a disposable/staging schema first, then apply through the normal migration process.

## Phase 6 — Test cleanup

Converted role-incidental fixtures in the amount-correction, refund, void, coupon ViewModel, register presentation, and session-reset tests. Financial assertions and money invariants were not edited in those conversions. Authorization-denial and shift-isolation cases are retained where cashier role is the behavior under test.

## Phase 8 — Final occurrence inventory

This inventory is a source-code search report, not a claim that every occurrence of the word has been deleted. Remaining hits are expected in these classes:

1. **Legacy enum decoding:** `ModelEnums.kt` keeps `UserRole.CASHIER`, cashier operation type names, and `CASHIER_CONFIRMED` so old persisted data still decodes.
2. **Authorization guard comments and role type:** `PermissionChecker.kt`, `TrustedSessionRestorer.kt`, `FinancialSyncOperationPolicy.kt`, and `RemoteBusinessRestorer.kt` mention CASHIER to explain rejection/compatibility; none grants cashier access.
3. **Historical reporting/attribution:** `RegisterShiftModels.kt`, `ReportingAccess.kt`, `ReportingReadRepository.kt`, `ReportingDashboardViewModel.kt`, `ReportingModels.kt`, `ReportingViewModel.kt`, `RegisterViewModel.kt`, and reporting/shift tests retain legacy actor IDs for past transactions.
4. **Historical migration and staged SQL:** `supabase/migrations/*cashier*.sql`, the 2026-10-23 retirement/actor restoration migrations, rollback scripts, `supabase/tests/*cashier*.sql`, and `BOARDLOCK/staged_sql/*cashier*.sql` preserve migration history, rollback evidence, and staged—not auto-deployed—definitions. They must not be applied out of order or used to recreate the feature.
5. **Security test fixtures:** role-specific tests retain cashier actors only to prove denial, ensure historical rows are readable, or test migration/shift isolation.
6. **Localization and documentation:** legacy role labels and old design/audit notes remain in resource/documentation files; active cashier management/navigation paths are not reintroduced by this branch.

Specific search categories run: `CASHIER`, `UserRole.CASHIER`, `cashierUserId`, `cashier_id`, `CASHIER_CONFIRMED`, `CREATE_CASHIER`, `UPDATE_CASHIER_PERMISSIONS`, `DISABLE_CASHIER`, `ENABLE_CASHIER`, `getCashiers(`, `observeCashiers(`, cashier settings/management/routes, and the key authorization/sync classes. GitHub's code-search endpoint caps results per query, so the inventory combines those result sets with direct reads of the active authorization/restoration files. The repository search is not a substitute for introspecting deployed Supabase catalogs.

## Validation status and risk

| Gate | Status |
|---|---|
| Source changes committed to implementation branch | **Done** |
| Android unit/instrumentation CI | **Pending** |
| SQL contract test against disposable/staging database | **Pending** |
| Live Supabase schema/function/RLS catalog inspection | **Not performed in this task** |
| Supabase migration applied | **No** |
| Production Edge Function deployment | **No** |
| Financial data deleted/rewritten by this branch | **No** |

### Risks / follow-up blockers

- Do not merge/deploy the migration until it passes against a disposable schema containing the current membership constraints and all historical tombstones.
- The historical `CASHIER_CONFIRMED` settlement label and `cashierUserId` attribution names are intentionally retained to avoid corrupting existing financial evidence. If the goal is also to remove those terms from *newly written* financial payloads, implement a versioned additive `ADMIN_CONFIRMED`/generic actor contract with dual-read compatibility; do not bulk-rewrite old settlements.
- Historical migration files and staged SQL contain old cashier function definitions by design. The effective latest schema must be verified from live catalogs before asserting that no callable endpoint exists.
- The final app is ADMIN-only in active local authorization/session restoration/sync queue paths on this branch, but a release-level “fully complete” claim depends on CI, SQL behavior tests, and deployed-catalog verification.

## Final statement

**ADMIN-only active product authorization is implemented on this branch.** CASHIER is no longer accepted by the app's central permission gate, trusted-session restore path, remote materialization boundary, or financial upload queue. CASHIER values remain only where needed to decode/audit historical records, enforce tombstone restrictions, or test rejection/compatibility. This is not yet a production-completion certification because CI, staging SQL execution, and live backend catalog verification remain pending.
