# Largest source files — confirmed subset

**Pinned source:** `f4c78e6c052720e270b88e42ce940335e7715069`  
**Status:** INCOMPLETE — exact line counts below are confirmed for individually fetched files; not a complete ranking of all 382 files.

## Confirmed large production files

| Rank within this measured subset | File | Lines | Main responsibility observed | Risk |
|---:|---|---:|---|---|
| 1 | `feature/register/data/RegisterRepository.kt` | 8,757 | Register financial command orchestration, recovery and evidence paths | Critical |
| 2 | `feature/register/ui/RegisterViewModel.kt` | 3,502 | Register UI state and orchestration | High |
| 3 | `feature/register/ui/CouponCompactDialogR7A.kt` | 1,898 | Coupon purchase/top-up UI | Medium |
| 4 | `feature/customers/ui/CustomerDetailScreen.kt` | 1,661 | Customer detail, invoice and payment UI | Medium |
| 5 | `feature/register/ui/RegisterExpensePresentation.kt` | 1,338 | Expense presentation and dialogs | Medium |
| 6 | `core/cloud/sync/RemoteEnvelopeMaterializer.kt` | 1,243 | Inbound remote envelope materialization | Critical |
| 7 | `core/database/security/DatabaseEncryptionMigrator.kt` | 1,111 | Encryption migration and integrity verification | Critical |
| 8 | `feature/register/ui/RegisterOverlayHost.kt` | 1,092 | Register overlay composition and wiring | Medium |
| 9 | `core/database/DmrDatabase.kt` | 898 | Room database and migration registration | Critical |
| 10 | `feature/register/data/RegisterShiftReportReadRepository.kt` | 898 | Cash-shift/day-close report reads | High |
| 11 | `feature/reporting/ui/ReportingScreen.kt` | 892 | Reporting UI and filters | Medium |
| 12 | `feature/customers/ui/CustomersViewModel.kt` | 846 | Customer workflow coordination | Medium |
| 13 | `feature/reporting/data/ReportingRowMappers.kt` | 830 | Financial/reporting record mapping | High |

## Large test files also observed

| File | Lines | Risk/interpretation |
|---|---:|---|
| `app/src/androidTest/java/com/dailymoneyregister/CreditRepaymentIntegrationTest.kt` | 3,209 | Large characterization/integration test suite; review test organization, not removal |
| `app/src/androidTest/java/com/dailymoneyregister/AmountCorrectionFinancialCoreIntegrationTest.kt` | 1,411 | Financial correctness coverage; preserve assertions and scenarios |

## Interpretation

A high line count is a maintainability signal, not proof of a defect. The highest-risk files are large because they sit on financial, synchronization, encryption or database boundaries. Their size alone does not justify extraction or deletion.

## Not yet established

- Complete top-50 ranking across main, test and androidTest.
- All files exceeding 800 lines (especially tests and non-Kotlin source).
- Syntax-aware class/function counts for each >800-line file.
- Largest-function rankings and responsibility boundaries for every large file.

No refactoring recommendation should be approved from file size alone.
