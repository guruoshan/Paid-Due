# Source file inventory — preliminary

**Repository:** `guruoshan/Paid-Due`  
**Pinned source:** `main` at `f4c78e6c052720e270b88e42ce940335e7715069`  
**Branch:** `audit/complete-code-health-analysis`  
**Status:** INCOMPLETE — source-set inventory indexed; per-file measurements incomplete.

## Scope and inventory evidence

The recursive Git tree at the pinned commit was fetched and was not truncated. It contains **382 Kotlin/Java source files** in the requested source sets:

| Source set | Files |
|---|---:|
| `app/src/main` | 242 |
| `app/src/test` | 89 |
| `app/src/androidTest` | 51 |
| **Total** | **382** |

The inventory below records exact physical line counts only for files whose full contents were fetched and counted in this audit or its preceding pinned-SHA review. Class/function counts are included only where counted from the fetched text using declaration-token patterns. These are not Kotlin PSI/compiler counts and should not be treated as a complete semantic declaration index.

## Confirmed measurements

| File | Lines | Package | Class/interface/object declarations | `fun` declarations |
|---|---:|---|---:|---:|
| `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterRepository.kt` | 8,757 | `com.dailymoneyregister.feature.register.data` | 14 | 112 |
| `app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterViewModel.kt` | 3,502 | `com.dailymoneyregister.feature.register.ui` | Not measured | Not measured |
| `app/src/main/java/com/dailymoneyregister/feature/register/ui/CouponCompactDialogR7A.kt` | 1,898 | `com.dailymoneyregister.feature.register.ui` | Not measured | Not measured |
| `app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomerDetailScreen.kt` | 1,661 | `com.dailymoneyregister.feature.customers.ui` | Not measured | Not measured |
| `app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterExpensePresentation.kt` | 1,338 | `com.dailymoneyregister.feature.register.ui` | Not measured | Not measured |
| `app/src/main/java/com/dailymoneyregister/core/cloud/sync/RemoteEnvelopeMaterializer.kt` | 1,243 | `com.dailymoneyregister.core.cloud.sync` | 9 | 5 |
| `app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterOverlayHost.kt` | 1,092 | `com.dailymoneyregister.feature.register.ui` | Not measured | Not measured |
| `app/src/main/java/com/dailymoneyregister/core/database/security/DatabaseEncryptionMigrator.kt` | 1,111 | `com.dailymoneyregister.core.database.security` | 5 | 24 |
| `app/src/main/java/com/dailymoneyregister/core/database/DmrDatabase.kt` | 898 | `com.dailymoneyregister.core.database` | 1 | 20 |
| `app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftReportReadRepository.kt` | 898 | `com.dailymoneyregister.feature.register.data` | 2 | 6 |
| `app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingScreen.kt` | 892 | `com.dailymoneyregister.feature.reporting.ui` | Not measured | Not measured |
| `app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomersViewModel.kt` | 846 | `com.dailymoneyregister.feature.customers.ui` | Not measured | Not measured |
| `app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingRowMappers.kt` | 830 | `com.dailymoneyregister.feature.reporting.data` | 3 | 16 |
| `app/src/androidTest/java/com/dailymoneyregister/CreditRepaymentIntegrationTest.kt` | 3,209 | `com.dailymoneyregister` | 2 | 30 |
| `app/src/androidTest/java/com/dailymoneyregister/AmountCorrectionFinancialCoreIntegrationTest.kt` | 1,411 | `com.dailymoneyregister` | 1 | 18 |

## Outstanding work

- Fetch and count every one of the 382 source files.
- Include all paths, package declarations, class/object/interface declarations and function declarations in a complete row-per-file inventory.
- Rank the top 50 by measured line count across all three source sets.
- Use Kotlin PSI or another Kotlin-aware parser to distinguish declarations from local functions, generated/anonymous constructs and syntax edge cases.
- Include Java declaration parsing separately if Java source files are present.

**Do not use this preliminary table as the top-50 ranking.** It is a confirmed measurement subset only. No source file was changed.
