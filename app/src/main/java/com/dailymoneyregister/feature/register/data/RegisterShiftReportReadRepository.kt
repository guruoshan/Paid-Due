package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.security.SessionManager

internal data class RegisterShiftExternalMovement(
    val cashMinor: Long = 0L,
    val onlineMinor: Long = 0L
)

internal fun registerShiftExternalMovement(
    category: PaymentCategory,
    signedMinor: Long
): RegisterShiftExternalMovement =
    when (category) {
        PaymentCategory.CASH ->
            RegisterShiftExternalMovement(
                cashMinor = signedMinor
            )

        PaymentCategory.ONLINE ->
            RegisterShiftExternalMovement(
                onlineMinor = signedMinor
            )

        else ->
            RegisterShiftExternalMovement()
    }

internal fun registerShiftIsCouponTopUpIntent(
    intentPayload: String
): Boolean =
    intentPayload.startsWith("TOPUP|")

internal fun registerShiftExpectedCashMinor(
    cashSalesMinor: Long,
    cashCreditRepaymentsMinor: Long,
    cashCouponFundingMinor: Long,
    cashLifecycleAdjustmentMinor: Long,
    cashRefundsMinor: Long,
    cashExpensesMinor: Long
): Long =
    Math.subtractExact(
        Math.subtractExact(
            Math.addExact(
                Math.addExact(
                    Math.addExact(
                        cashSalesMinor,
                        cashCreditRepaymentsMinor
                    ),
                    cashCouponFundingMinor
                ),
                cashLifecycleAdjustmentMinor
            ),
            cashRefundsMinor
        ),
        cashExpensesMinor
    )

/**
 * BOARDLOCK Register Shift / Day Closing read boundary.
 *
 * getCurrentRegisterShiftReport() below is transplanted verbatim from
 * RegisterRepository.
 *
 * The shift identity resolver is not duplicated. The existing
 * RegisterRepository.readRegisterShift() implementation is supplied
 * as a read-only callback.
 *
 * No financial state is written here.
 */
internal class RegisterShiftReportReadRepository(
    private val database: DmrDatabase,
    private val readRegisterShiftResolver:
        suspend (String, String, String) -> RegisterShiftResult
) {
    private val transactionDao =
        database.transactionDao()

    private val ledgerDao =
        database.ledgerDao()

    private val businessBranchDao =
        database.businessBranchDao()

    private val userDao =
        database.userDao()

    private val cashShiftDao =
        database.cashShiftDao()

    private suspend fun readRegisterShift(
        shiftId: String,
        expectedBranchId: String,
        expectedBusinessId: String
    ): RegisterShiftResult =
        readRegisterShiftResolver(
            shiftId,
            expectedBranchId,
            expectedBusinessId
        )

    suspend fun getCurrentRegisterShiftReport(): RegisterShiftReport? {
        val session =
            SessionManager.currentSession.value
                ?: return null

        val latest =
            cashShiftDao.getLatestShiftForCashier(
                branchId = session.branch.id,
                userId = session.user.id
            ) ?: return null

        val shift =
            readRegisterShift(
                shiftId = latest.id,
                expectedBranchId = session.branch.id,
                expectedBusinessId = session.business.id
            )

        if (!shift.isOpen) {
            return null
        }

        val operations =
            database.operationDao()
                .getSucceededOperationsForCashShift(
                    shift.shiftId.value
                )

        
        /*
         * BOARDLOCK Stage 3.
         *
         * Ephemeral read indexes only.
         * No financial authority is introduced.
         */
        suspend fun <T> loadDayClosingBatches(
            ids: List<String>,
            loader: suspend (List<String>) -> List<T>
        ): List<T> {
            if (ids.isEmpty()) {
                return emptyList()
            }

            val result = mutableListOf<T>()

            for (batch in ids.chunked(500)) {
                result.addAll(
                    loader(batch)
                )
            }

            return result
        }

        val operationIds =
            operations
                .map { it.id }
                .distinct()

        val settlementsByOperationId =
            loadDayClosingBatches(
                operationIds
            ) { batch ->
                transactionDao.getSettlementsForOperations(
                    batch
                )
            }.groupBy {
                it.operationId
            }

        val couponEventsByOperationId =
            loadDayClosingBatches(
                operationIds
            ) { batch ->
                ledgerDao.getCouponEventsForOperations(
                    batch
                )
            }.groupBy {
                it.operationId
            }

        val creditEventsByOperationId =
            loadDayClosingBatches(
                operationIds
            ) { batch ->
                ledgerDao.getCreditEventsForOperations(
                    batch
                )
            }.groupBy {
                it.operationId
            }

        val creditRepaymentsByOperationId =
            loadDayClosingBatches(
                operationIds
            ) { batch ->
                ledgerDao.getCreditRepaymentsForOperations(
                    batch
                )
            }.groupBy {
                it.operationId
            }

        val expensesByOperationId =
            loadDayClosingBatches(
                operationIds
            ) { batch ->
                database.expenseDao()
                    .getExpensesByOperationIds(
                        batch
                    )
            }.associateBy {
                it.operationId
            }

        val saleTransactionIds =
            operations
                .asSequence()
                .filter {
                    it.operationType ==
                        OperationType.RECORD_SALE
                }
                .map {
                    it.transactionId
                        ?: it.resultReferenceId
                        ?: it.subjectId
                }
                .distinct()
                .toList()

        val financialRecordsByTransactionId =
            loadDayClosingBatches(
                saleTransactionIds
            ) { batch ->
                transactionDao.getFinancialRecordsByIds(
                    batch
                )
            }.associateBy {
                it.transaction.id
            }
var salesCount = 0
        var grossSalesMinor = 0L

        var cashSalesMinor = 0L
        var onlineSalesMinor = 0L
        var couponUsedMinor = 0L
        var creditGivenMinor = 0L

        var cashCreditRepaymentsMinor = 0L
        var onlineCreditRepaymentsMinor = 0L
        var couponCreditRepaymentsMinor = 0L

        var couponIssuedMinor = 0L
        var couponTopUpMinor = 0L
        var couponRefundCreditsMinor = 0L

        var cashCouponFundingMinor = 0L
        var onlineCouponFundingMinor = 0L

        var cashLifecycleAdjustmentMinor = 0L
        var onlineLifecycleAdjustmentMinor = 0L

        var cashRefundsMinor = 0L
        var onlineRefundsMinor = 0L

        /*
         * ExpenseEntity stores a positive amount.
         * The authoritative EXPENSE_OUTFLOW settlement proves
         * whether the outflow was Cash or Online.
         */
        var cashExpensesMinor = 0L
        var onlineExpensesMinor = 0L

        var refundCount = 0
        var voidCount = 0
        var correctionCount = 0
        var paymentMethodChangeCount = 0

        for (operation in operations) {

            when (operation.operationType) {

                OperationType.RECORD_SALE -> {
                    val transactionId =
                        operation.transactionId
                            ?: operation.resultReferenceId
                            ?: operation.subjectId

                    val record =
                        checkNotNull(financialRecordsByTransactionId[transactionId]) {
                            "Day Closing sale evidence is missing for transaction $transactionId."
                        }

                    val financial =
                        FinancialReadModelResolver.resolve(record)

                    salesCount += 1
                    grossSalesMinor =
                        Math.addExact(
                            grossSalesMinor,
                            financial.originalAmount.amount
                        )

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    for (settlement in settlements) {
                        if (settlement.amountSignedMinor <= 0L) {
                            continue
                        }

                        when (settlement.category) {
                            PaymentCategory.CASH ->
                                cashSalesMinor =
                                    Math.addExact(
                                        cashSalesMinor,
                                        settlement.amountSignedMinor
                                    )

                            PaymentCategory.ONLINE ->
                                onlineSalesMinor =
                                    Math.addExact(
                                        onlineSalesMinor,
                                        settlement.amountSignedMinor
                                    )

                            PaymentCategory.COUPON ->
                                couponUsedMinor =
                                    Math.addExact(
                                        couponUsedMinor,
                                        settlement.amountSignedMinor
                                    )

                            PaymentCategory.CREDIT ->
                                creditGivenMinor =
                                    Math.addExact(
                                        creditGivenMinor,
                                        settlement.amountSignedMinor
                                    )

                            else -> Unit
                        }
                    }

                    /*
                     * Credit Sale can intentionally have no collected settlement.
                     * Its authoritative credit obligation is therefore obtained
                     * from the Credit ledger rather than inventing cash inflow.
                     */
                    val customerId = record.transaction.customerId

                    if (
                        customerId != null &&
                        settlements.none {
                            it.category == PaymentCategory.CREDIT
                        }
                    ) {
                        val creditForOperation =
                            creditEventsByOperationId[operation.id]
                                .orEmpty()
                                .filter {
                                    it.customerId == customerId &&
                                        it.operationId == operation.id &&
                                        it.amountSignedMinor > 0L
                                }
                                .fold(0L) { total, event ->
                                    Math.addExact(
                                        total,
                                        event.amountSignedMinor
                                    )
                                }

                        creditGivenMinor =
                            Math.addExact(
                                creditGivenMinor,
                                creditForOperation
                            )
                    }
                }

                OperationType.RECORD_CREDIT_REPAYMENT -> {
                    val repayments =
                        creditRepaymentsByOperationId[operation.id].orEmpty()

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    /*
                     * Settlement evidence carries the actual collection channel.
                     * Repayment evidence proves this is collection of Credit,
                     * not new sales revenue.
                     */
                    if (repayments.isNotEmpty()) {
                        for (settlement in settlements) {
                            if (settlement.amountSignedMinor <= 0L) {
                                continue
                            }

                            when (settlement.category) {
                                PaymentCategory.CASH ->
                                    cashCreditRepaymentsMinor =
                                        Math.addExact(
                                            cashCreditRepaymentsMinor,
                                            settlement.amountSignedMinor
                                        )

                                PaymentCategory.ONLINE ->
                                    onlineCreditRepaymentsMinor =
                                        Math.addExact(
                                            onlineCreditRepaymentsMinor,
                                            settlement.amountSignedMinor
                                        )

                                PaymentCategory.COUPON ->
                                    couponCreditRepaymentsMinor =
                                        Math.addExact(
                                            couponCreditRepaymentsMinor,
                                            settlement.amountSignedMinor
                                        )

                                else -> Unit
                            }
                        }
                    }
                }

                OperationType.RECORD_COUPON_PURCHASE -> {
                    val couponEvents =
                        couponEventsByOperationId[operation.id].orEmpty()

                    val positiveCouponValue =
                        couponEvents
                            .filter {
                                it.amountSignedMinor > 0L
                            }
                            .fold(0L) { total, event ->
                                Math.addExact(
                                    total,
                                    event.amountSignedMinor
                                )
                            }

                    /*
                     * Sell and Top Up intentionally share RECORD_COUPON_PURCHASE.
                     * The durable operation payload is authoritative command-kind
                     * evidence: Top Up payloads begin with "TOPUP|".
                     */
                    if (
                        registerShiftIsCouponTopUpIntent(
                            operation.intentPayload
                        )
                    ) {
                        couponTopUpMinor =
                            Math.addExact(
                                couponTopUpMinor,
                                positiveCouponValue
                            )
                    } else {
                        couponIssuedMinor =
                            Math.addExact(
                                couponIssuedMinor,
                                positiveCouponValue
                            )
                    }

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    /*
                     * Coupon funding is external Cash/Online collection,
                     * but it is NOT merchandise sales revenue.
                     */
                    for (settlement in settlements) {
                        if (
                            settlement.entryPurpose !=
                                EntryPurpose.COUPON_PURCHASE_FUNDING ||
                            settlement.amountSignedMinor <= 0L
                        ) {
                            continue
                        }

                        val movement =
                            registerShiftExternalMovement(
                                category = settlement.category,
                                signedMinor =
                                    settlement.amountSignedMinor
                            )

                        cashCouponFundingMinor =
                            Math.addExact(
                                cashCouponFundingMinor,
                                movement.cashMinor
                            )

                        onlineCouponFundingMinor =
                            Math.addExact(
                                onlineCouponFundingMinor,
                                movement.onlineMinor
                            )
                    }
                }

                OperationType.RECORD_REFUND -> {
                    refundCount += 1

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    for (settlement in settlements) {
                        if (settlement.amountSignedMinor >= 0L) {
                            continue
                        }

                        val amount =
                            Math.negateExact(
                                settlement.amountSignedMinor
                            )

                        when (settlement.category) {
                            PaymentCategory.CASH ->
                                cashRefundsMinor =
                                    Math.addExact(
                                        cashRefundsMinor,
                                        amount
                                    )

                            PaymentCategory.ONLINE ->
                                onlineRefundsMinor =
                                    Math.addExact(
                                        onlineRefundsMinor,
                                        amount
                                    )

                            else -> Unit
                        }
                    }

                    val couponEvents =
                        couponEventsByOperationId[operation.id].orEmpty()

                    val refundCouponValue =
                        couponEvents
                            .filter {
                                it.amountSignedMinor > 0L
                            }
                            .fold(0L) { total, event ->
                                Math.addExact(
                                    total,
                                    event.amountSignedMinor
                                )
                            }

                    couponRefundCreditsMinor =
                        Math.addExact(
                            couponRefundCreditsMinor,
                            refundCouponValue
                        )
                }

                OperationType.RECORD_EXPENSE -> {

                    /*
                     * BOARDLOCK:
                     * Day Closing must consume the same authoritative Expense
                     * evidence used by Recent and Expense Details.
                     *
                     * Do not trust ExpenseEntity.paymentCategory alone.
                     * Payment identity comes from the persisted settlement
                     * through ExpenseReadProjectionResolver.
                     */
                    val expense =
                        checkNotNull(
                            expensesByOperationId[operation.id]
                        ) {
                            "Day Closing Expense evidence is missing for operation ${operation.id}."
                        }

                    check(
                        expense.businessId ==
                            session.business.id
                    ) {
                        "Day Closing Expense escaped active business."
                    }

                    check(
                        expense.branchId ==
                            session.branch.id
                    ) {
                        "Day Closing Expense escaped active branch."
                    }

                    check(
                        expense.cashShiftId ==
                            shift.shiftId.value
                    ) {
                        "Day Closing Expense does not belong to this cashier shift."
                    }

                    check(
                        expense.actorUserId ==
                            session.user.id
                    ) {
                        "Day Closing Expense escaped authenticated cashier."
                    }

                    check(
                        expense.currencyCode ==
                            session.branch.currencyCode
                    ) {
                        "Day Closing Expense currency mismatch."
                    }

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    check(settlements.size == 1) {
                        "Day Closing Expense must have exactly one authoritative outflow settlement."
                    }

                    val expenseProjection =
                        ExpenseReadProjectionResolver.resolve(
                            expense = expense,
                            settlement = settlements.single(),
                            events = database.expenseDao().getEventsForExpense(expense.id)
                        )

                    when (
                        expenseProjection.paymentCategory
                    ) {
                        PaymentCategory.CASH ->
                            cashExpensesMinor =
                                Math.addExact(
                                    cashExpensesMinor,
                                    expenseProjection
                                        .amount
                                        .amount
                                )

                        PaymentCategory.ONLINE ->
                            onlineExpensesMinor =
                                Math.addExact(
                                    onlineExpensesMinor,
                                    expenseProjection
                                        .amount
                                        .amount
                                )

                        PaymentCategory.CREDIT,
                        PaymentCategory.COUPON ->
                            error(
                                "Unsupported Day Closing Expense payment category."
                            )
                    }
                }

                OperationType.RECORD_EXPENSE_VOID -> {
                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    for (settlement in settlements) {
                        if (settlement.entryPurpose != EntryPurpose.VOID_REVERSAL) {
                            continue
                        }

                        val movement =
                            registerShiftExternalMovement(
                                category = settlement.category,
                                signedMinor = settlement.amountSignedMinor
                            )

                        cashLifecycleAdjustmentMinor =
                            Math.addExact(cashLifecycleAdjustmentMinor, movement.cashMinor)
                        onlineLifecycleAdjustmentMinor =
                            Math.addExact(onlineLifecycleAdjustmentMinor, movement.onlineMinor)
                    }
                }

                OperationType.RECORD_VOID -> {
                    voidCount += 1

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    for (settlement in settlements) {
                        if (
                            settlement.entryPurpose !=
                                EntryPurpose.VOID_REVERSAL
                        ) {
                            continue
                        }

                        val movement =
                            registerShiftExternalMovement(
                                category = settlement.category,
                                signedMinor =
                                    settlement.amountSignedMinor
                            )

                        cashLifecycleAdjustmentMinor =
                            Math.addExact(
                                cashLifecycleAdjustmentMinor,
                                movement.cashMinor
                            )

                        onlineLifecycleAdjustmentMinor =
                            Math.addExact(
                                onlineLifecycleAdjustmentMinor,
                                movement.onlineMinor
                            )
                    }
                }

                OperationType.RECORD_AMOUNT_CORRECTION -> {
                    correctionCount += 1

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    for (settlement in settlements) {
                        if (
                            settlement.entryPurpose !=
                                EntryPurpose.FUNDING_CORRECTION
                        ) {
                            continue
                        }

                        val movement =
                            registerShiftExternalMovement(
                                category = settlement.category,
                                signedMinor =
                                    settlement.amountSignedMinor
                            )

                        cashLifecycleAdjustmentMinor =
                            Math.addExact(
                                cashLifecycleAdjustmentMinor,
                                movement.cashMinor
                            )

                        onlineLifecycleAdjustmentMinor =
                            Math.addExact(
                                onlineLifecycleAdjustmentMinor,
                                movement.onlineMinor
                            )
                    }
                }

                OperationType.RECORD_PAYMENT_METHOD_CHANGE -> {
                    paymentMethodChangeCount += 1

                    val settlements =
                        settlementsByOperationId[operation.id].orEmpty()

                    for (settlement in settlements) {
                        if (
                            settlement.entryPurpose !=
                                EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION
                        ) {
                            continue
                        }

                        val movement =
                            registerShiftExternalMovement(
                                category = settlement.category,
                                signedMinor =
                                    settlement.amountSignedMinor
                            )

                        cashLifecycleAdjustmentMinor =
                            Math.addExact(
                                cashLifecycleAdjustmentMinor,
                                movement.cashMinor
                            )

                        onlineLifecycleAdjustmentMinor =
                            Math.addExact(
                                onlineLifecycleAdjustmentMinor,
                                movement.onlineMinor
                            )
                    }
                }

                else -> Unit
            }
        }

        val expectedCashMinor =
            registerShiftExpectedCashMinor(
                cashSalesMinor = cashSalesMinor,
                cashCreditRepaymentsMinor =
                    cashCreditRepaymentsMinor,
                cashCouponFundingMinor =
                    cashCouponFundingMinor,
                cashLifecycleAdjustmentMinor =
                    cashLifecycleAdjustmentMinor,
                cashRefundsMinor = cashRefundsMinor,
                cashExpensesMinor = cashExpensesMinor
            )

        return RegisterShiftReport(
            shift = shift,
            operationCount = operations.size,

            salesCount = salesCount,
            grossSalesMinor = grossSalesMinor,

            cashSalesMinor = cashSalesMinor,
            onlineSalesMinor = onlineSalesMinor,
            couponUsedMinor = couponUsedMinor,
            creditGivenMinor = creditGivenMinor,

            cashCreditRepaymentsMinor =
                cashCreditRepaymentsMinor,
            onlineCreditRepaymentsMinor =
                onlineCreditRepaymentsMinor,
            couponCreditRepaymentsMinor =
                couponCreditRepaymentsMinor,

            couponIssuedMinor = couponIssuedMinor,
            couponTopUpMinor = couponTopUpMinor,
            couponRefundCreditsMinor =
                couponRefundCreditsMinor,

            cashRefundsMinor = cashRefundsMinor,
            onlineRefundsMinor = onlineRefundsMinor,

            cashExpensesMinor = cashExpensesMinor,
            onlineExpensesMinor = onlineExpensesMinor,

            cashCouponFundingMinor =
                cashCouponFundingMinor,
            onlineCouponFundingMinor =
                onlineCouponFundingMinor,
            cashLifecycleAdjustmentMinor =
                cashLifecycleAdjustmentMinor,
            onlineLifecycleAdjustmentMinor =
                onlineLifecycleAdjustmentMinor,

            refundCount = refundCount,
            voidCount = voidCount,
            correctionCount = correctionCount,
            paymentMethodChangeCount =
                paymentMethodChangeCount,

            expectedCashMinor = expectedCashMinor
        )
    }
}
