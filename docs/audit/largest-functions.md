# Largest functions — analysis status

**Pinned source:** `f4c78e6c052720e270b88e42ce940335e7715069`  
**Status:** INCOMPLETE — function declarations were enumerated in selected files, but reliable body-size ranking was not completed.

## Confirmed function inventory examples

The following declarations were read from the pinned source. Line numbers are source line numbers.

### RegisterRepository.kt

Selected financial lifecycle and recovery declarations:

| Line | Function |
|---:|---|
| 1306 | `executeCashSale` |
| 1690 | `executeCreditSale` |
| 1957 | `executeCouponCreditRepayment` |
| 2849 | `executeCreditRepayment` |
| 3168 | `recoverPendingCreditRepayments` |
| 3397 | `executeAmountCorrection` |
| 4200 | `recoverPendingAmountCorrections` |
| 4335 | `executePaymentMethodChange` |
| 4835 | `executeRefund` |
| 6195 | `resolveRefundReplay` |
| 7132 | `buildCreditReturnB2Plan` |
| 7570 | `recoverPendingRefunds` |
| 7688 | `executeVoid` |
| 8215 | `recoverPendingVoids` |
| 8312 | `recoverPendingCreditSales` |

The file contains 112 `fun` declaration-token matches in the fetched source. The list above is selected to illustrate major financial responsibilities, not a top-20 body-size ranking.

### RemoteEnvelopeMaterializer.kt

| Line | Function |
|---:|---|
| 37 | `materializeInboundEnvelope` |
| 678 | `hasActorIdentity` |
| 714 | `normalizeJson` |
| 726 | `materializeNonTransactionOperation` |
| 1026 | `materializeEnvelope` |

### RegisterShiftReportReadRepository.kt

| Line | Function |
|---:|---|
| 15 | `registerShiftExternalMovement` |
| 34 | `registerShiftIsCouponTopUpIntent` |
| 39 | `registerShiftExpectedCashMinor` |
| 96 | `readRegisterShift` |
| 107 | `getCurrentRegisterShiftReport` |
| 142 | `loadDayClosingBatches` |

### ReportingRowMappers.kt

| Line | Function |
|---:|---|
| 61 | `branch` |
| 67 | `cashier` |
| 74 | `person` |
| 120 | `creditBalancesAroundRepayment` |
| 157 | `couponBalanceAfterEvent` |
| 191 | `mapSale` |
| 299 | `mapRefund` |
| 374 | `mapVoid` |
| 428 | `mapCorrection` |
| 464 | `mapPaymentMethodChange` |
| 496 | `mapCreditRepayment` |
| 574 | `mapCouponEvent` |
| 696 | `mapExpense` |
| 751 | `mapExpenseLifecycle` |
| 828 | `sumExact` |

## Why a top-20 ranking is not claimed

A reliable function-size ranking must parse Kotlin syntax, match each function body (including nested lambdas, local declarations, strings and comments), and distinguish block-bodied functions from expression-bodied functions. A naive brace count was previously shown to produce incorrect ranges. The current GitHub source inspection did not provide a Kotlin PSI/compiler index, and this pass does not claim those rankings.

## Required completion method

Use a pinned local checkout and Kotlin PSI/compiler-aware parsing. For each file over 800 lines, report the top 20 functions by inclusive source span, declaration line, end line, line count, visibility, suspend/extension status and containing class/object. Manually validate the largest results before using them as refactoring targets.

No function is marked as a defect or deletion candidate based on size.
