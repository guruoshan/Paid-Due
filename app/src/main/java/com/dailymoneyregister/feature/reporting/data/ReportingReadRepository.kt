package com.dailymoneyregister.feature.reporting.data

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.reporting.domain.ReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportingReadModel
import com.dailymoneyregister.feature.reporting.domain.ReportingScope
import kotlinx.coroutines.CancellationException

/**
 * BOARDLOCK Authoritative Reporting read boundary.
 *
 * Reads persisted financial evidence and projects it into the Reporting
 * domain. It never writes, never recalculates a second financial truth and
 * never guesses missing evidence.
 *
 * Phase 2 returns detailed rows plus ACTIVITY COUNTS only. Financial totals
 * (ReportingSummary amounts) are the Phase 3 aggregator's job; they stay zero
 * here on purpose.
 */
interface ReportingReadRepository {

    /**
     * @throws ReportingAccessDeniedException scope/permission failure.
     * @throws ReportingIntegrityException evidence is inconsistent/unsupported.
     */
    suspend fun read(scope: ReportingScope): ReportingReadModel
}

class RoomReportingReadRepository(
    private val database: DmrDatabase,
    private val sessionProvider: () -> ActiveSession? =
        { SessionManager.currentSession.value }
) : ReportingReadRepository {

    override suspend fun read(scope: ReportingScope): ReportingReadModel {

        val session = sessionProvider()

        val businessBranches =
            if (session != null) {
                database.businessBranchDao()
                    .getBranchesByBusinessId(session.business.id)
            } else {
                emptyList()
            }

        val cashierKnown: Boolean? =
            if (session != null && scope.cashierUserId != null) {
                database.userDao()
                    .getUserByIdInBusiness(
                        userId = scope.cashierUserId,
                        businessId = session.business.id
                    ) != null
            } else {
                null
            }

        // Throws ReportingAccessDeniedException before ANY financial read.
        val access = ReportingAccessValidator.validate(
            session = session,
            scope = scope,
            businessBranches = businessBranches,
            requestedCashierKnownInBusiness = cashierKnown
        )

        return try {
            database.withTransaction {
                readInside(scope, access)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReportingIntegrityException) {
            throw e
        } catch (e: IllegalStateException) {
            throw ReportingIntegrityException(
                "Reporting evidence is inconsistent: ${e.message}",
                e
            )
        } catch (e: IllegalArgumentException) {
            throw ReportingIntegrityException(
                "Reporting evidence is invalid: ${e.message}",
                e
            )
        } catch (e: ArithmeticException) {
            throw ReportingIntegrityException(
                "Reporting amount arithmetic overflowed: ${e.message}",
                e
            )
        }
    }

    private suspend fun readInside(
        scope: ReportingScope,
        access: ReportingAccess
    ): ReportingReadModel {

        val reportingDao = database.reportingReadDao()
        val transactionDao = database.transactionDao()
        val ledgerDao = database.ledgerDao()

        val from = scope.period.fromInclusive
        val to = scope.period.toExclusive
        val branchIds = access.branchIds
        val businessId = access.businessId

        // ---- 1. Original sales -------------------------------------------

        val saleIds =
            reportingDao.getSaleTransactionIdsCreatedInPeriod(branchIds, from, to)

        val saleRecords = loadChunked(saleIds) {
            transactionDao.getFinancialRecordsByIds(it)
        }

        val saleItems = loadChunked(saleIds) {
            reportingDao.getItemsForTransactions(it)
        }.groupBy { it.transactionId }

        val saleCreditEvents = loadChunked(saleIds) {
            reportingDao.getCreditEventsForTransactions(it)
        }.groupBy { requireNotNull(it.transactionId) }

        // ---- 2. Lifecycle activity (each by its own timestamp) -----------

        val refunds = reportingDao.getRefundEventsInPeriod(branchIds, from, to)
        val voids = reportingDao.getVoidEventsInPeriod(branchIds, from, to)
        val corrections = reportingDao.getAmountCorrectionsInPeriod(branchIds, from, to)
        val paymentChanges = reportingDao.getPaymentMethodChangesInPeriod(branchIds, from, to)

        // ---- 3. Repayments, coupon ledger, expenses -----------------------

        val repayments =
            reportingDao.getCreditRepaymentsInPeriod(businessId, branchIds, from, to)

        val couponEvents =
            reportingDao.getCouponEventsInPeriod(businessId, branchIds, from, to)

        val expenses =
            reportingDao.getExpensesInPeriod(businessId, branchIds, from, to)

        check(
            reportingDao.countExpenseEventsInPeriod(businessId, branchIds, from, to) == 0
        ) {
            "Expense correction/void activity exists in this period and is not supported by Reporting yet."
        }

        // ---- 4. Parent transactions for lifecycle + coupon rows ----------

        val transactionsById = mutableMapOf<String, TransactionEntity>()

        saleRecords.forEach { transactionsById[it.transaction.id] = it.transaction }

        val eventTransactionIds =
            (
                refunds.map { it.transactionId } +
                    voids.map { it.transactionId } +
                    corrections.map { it.transactionId } +
                    paymentChanges.map { it.transactionId } +
                    couponEvents.mapNotNull { it.transactionId }
                ).toSet() - transactionsById.keys

        loadChunked(eventTransactionIds) {
            transactionDao.getTransactionsByIds(it)
        }.forEach { transactionsById[it.id] = it }

        val voidRecords: Map<String, TransactionFinancialRecord> =
            loadChunked(voids.map { it.transactionId }) {
                transactionDao.getFinancialRecordsByIds(it)
            }.associateBy { it.transaction.id }

        // ---- 5. Operations + settlements + coupon evidence ----------------

        val operationsById = loadChunked(
            repayments.map { it.operationId } + couponEvents.map { it.operationId }
        ) {
            reportingDao.getOperationsByIds(it)
        }.associateBy { it.id }

        val settlementOperationIds =
            refunds.map { it.operationId } +
                voids.map { it.operationId } +
                repayments.map { it.operationId } +
                couponEvents.map { it.operationId } +
                expenses.map { it.operationId }

        val settlementsByOperation = loadChunked(settlementOperationIds) {
            transactionDao.getSettlementsForOperations(it)
        }.groupBy { it.operationId }

        val refundCouponEvents = loadChunked(refunds.map { it.operationId }) {
            ledgerDao.getCouponEventsForOperations(it)
        }.groupBy { it.operationId }

        val allocationsByRepayment = loadChunked(repayments.map { it.id }) {
            reportingDao.getAllocationsForRepayments(it)
        }.groupBy { it.repaymentId }

        // ---- 6. Display lookups ------------------------------------------

        val walletsById = loadChunked(couponEvents.map { it.walletId }) {
            ledgerDao.getCouponWalletsByIds(it)
        }.associateBy { it.id }

        val customerIds =
            transactionsById.values.mapNotNull { it.customerId } +
                repayments.map { it.customerId } +
                walletsById.values.map { it.customerId }

        val customersById = loadChunked(customerIds) {
            database.customerDao().getCustomersByIds(it)
        }.filter { it.businessId == businessId }
            .associateBy { it.id }

        val userIds =
            transactionsById.values.map { it.createdByUserId } +
                refunds.map { it.actorUserId } +
                voids.map { it.actorUserId } +
                corrections.map { it.actorUserId } +
                paymentChanges.map { it.actorUserId } +
                repayments.map { it.actorUserId } +
                expenses.map { it.actorUserId } +
                operationsById.values.map { it.actorUserId }

        val usersById = loadChunkedWithBusiness(userIds, businessId) { ids ->
            reportingDao.getUsersByIdsInBusiness(businessId, ids)
        }.associateBy { it.id }

        val lookups = ReportingLookups(
            branchesById = access.branchesById,
            usersById = usersById,
            customersById = customersById
        )

        // ---- 7. Ordered ledgers for balance evidence ----------------------

        val creditLedgerByCustomer =
            repayments.map { it.customerId }.distinct().associateWith {
                ledgerDao.getCreditLedgerForCustomer(it)
            }

        val couponLedgerByWallet =
            couponEvents.map { it.walletId }.distinct().associateWith {
                ledgerDao.getCouponLedgerForWallet(it)
            }

        // ---- 8. Map every stream ------------------------------------------

        val rows = mutableListOf<ReportRow>()

        for (record in saleRecords) {
            val tx = record.transaction
            rows += guarded("sale ${tx.id}") {
                ReportingRowMappers.mapSale(
                    record = record,
                    items = saleItems[tx.id].orEmpty(),
                    creditEvents = saleCreditEvents[tx.id].orEmpty(),
                    lookups = lookups
                )
            }
        }

        for (event in refunds) {
            rows += guarded("refund ${event.id}") {
                ReportingRowMappers.mapRefund(
                    event = event,
                    tx = requireTransaction(transactionsById, event.transactionId, access),
                    settlements = settlementsByOperation[event.operationId].orEmpty(),
                    couponEvents = refundCouponEvents[event.operationId].orEmpty(),
                    lookups = lookups
                )
            }
        }

        for (event in voids) {
            rows += guarded("void ${event.id}") {
                val record = checkNotNull(voidRecords[event.transactionId]) {
                    "Void ${event.id} transaction evidence is missing."
                }
                val view = FinancialReadModelResolver.resolve(record)
                ReportingRowMappers.mapVoid(
                    event = event,
                    tx = requireTransaction(transactionsById, event.transactionId, access),
                    settlements = settlementsByOperation[event.operationId].orEmpty(),
                    fallbackAffected = view.currentAmount - view.refundedAmount,
                    lookups = lookups
                )
            }
        }

        for (event in corrections) {
            rows += guarded("correction ${event.id}") {
                ReportingRowMappers.mapCorrection(
                    event = event,
                    tx = requireTransaction(transactionsById, event.transactionId, access),
                    lookups = lookups
                )
            }
        }

        for (event in paymentChanges) {
            rows += guarded("payment change ${event.id}") {
                ReportingRowMappers.mapPaymentMethodChange(
                    event = event,
                    tx = requireTransaction(transactionsById, event.transactionId, access),
                    lookups = lookups
                )
            }
        }

        for (repayment in repayments) {
            rows += guarded("repayment ${repayment.id}") {
                val operation = requireOperation(operationsById, repayment.operationId, access)
                ReportingRowMappers.mapCreditRepayment(
                    repayment = repayment,
                    operation = operation,
                    settlements = settlementsByOperation[repayment.operationId].orEmpty(),
                    allocations = allocationsByRepayment[repayment.id].orEmpty(),
                    balances = ReportingBalanceEvidence.creditBalancesAroundRepayment(
                        ledger = creditLedgerByCustomer[repayment.customerId].orEmpty(),
                        repaymentId = repayment.id,
                        currencyCode = repayment.currencyCode
                    ),
                    lookups = lookups
                )
            }
        }

        for (event in couponEvents) {
            rows += guarded("coupon ${event.id}") {
                val operation = requireOperation(operationsById, event.operationId, access)
                val wallet = checkNotNull(walletsById[event.walletId]) {
                    "Coupon wallet ${event.walletId} was not found."
                }
                ReportingRowMappers.mapCouponEvent(
                    event = event,
                    operation = operation,
                    wallet = wallet,
                    tx = event.transactionId?.let { transactionsById[it] },
                    settlements = settlementsByOperation[event.operationId].orEmpty(),
                    resultingBalance = ReportingBalanceEvidence.couponBalanceAfterEvent(
                        ledger = couponLedgerByWallet[event.walletId].orEmpty(),
                        eventId = event.id
                    ),
                    lookups = lookups
                )
            }
        }

        for (expense in expenses) {
            rows += guarded("expense ${expense.id}") {
                val settlements = settlementsByOperation[expense.operationId].orEmpty()
                check(settlements.size == 1) {
                    "Expense ${expense.id} must have exactly one authoritative settlement."
                }
                check(expense.businessId == businessId && expense.branchId in access.branchIds) {
                    "Expense ${expense.id} escaped the validated scope."
                }
                ReportingRowMappers.mapExpense(
                    expense = expense,
                    settlement = settlements.single(),
                    lookups = lookups
                )
            }
        }

        // ---- 9. Currency guard, cashier filter, ordering, counts ---------

        rows.forEach {
            check(it.currencyCode == access.currencyCode) {
                "Row ${it.stableId} currency ${it.currencyCode} differs from the report currency ${access.currencyCode}."
            }
        }

        val filtered =
            if (scope.cashierUserId != null) {
                rows.filter { it.cashier.userId == scope.cashierUserId }
            } else {
                rows
            }

        val ordered = filtered.sortedWith(
            compareByDescending<ReportRow> { it.occurredAt }
                .thenByDescending { it.stableId }
        )

        return ReportingReadModel(
            scope = scope,
            summary = ReportingAggregator.build(ordered),
            rows = ordered
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun <T> loadChunked(
        ids: Collection<String>,
        loader: suspend (List<String>) -> List<T>
    ): List<T> {

        val distinct = ids.distinct()

        if (distinct.isEmpty()) return emptyList()

        val result = ArrayList<T>()

        for (batch in distinct.chunked(CHUNK_SIZE)) {
            result.addAll(loader(batch))
        }

        return result
    }

    private suspend fun <T> loadChunkedWithBusiness(
        ids: Collection<String>,
        @Suppress("UNUSED_PARAMETER") businessId: String,
        loader: suspend (List<String>) -> List<T>
    ): List<T> = loadChunked(ids, loader)

    private fun requireTransaction(
        transactionsById: Map<String, TransactionEntity>,
        transactionId: String,
        access: ReportingAccess
    ): TransactionEntity {

        val tx = checkNotNull(transactionsById[transactionId]) {
            "Transaction $transactionId evidence is missing."
        }

        check(tx.branchId in access.branchIds) {
            "Transaction $transactionId escaped the validated branch scope."
        }

        return tx
    }

    private fun requireOperation(
        operationsById: Map<String, com.dailymoneyregister.core.database.entity.OperationEntity>,
        operationId: String,
        access: ReportingAccess
    ): com.dailymoneyregister.core.database.entity.OperationEntity {

        val operation = checkNotNull(operationsById[operationId]) {
            "Operation $operationId evidence is missing."
        }

        check(operation.businessId == access.businessId) {
            "Operation $operationId escaped the validated business."
        }

        check(operation.branchId != null && operation.branchId in access.branchIds) {
            "Operation $operationId escaped the validated branch scope."
        }

        return operation
    }

    private inline fun <T> guarded(label: String, block: () -> T): T =
        try {
            block()
        } catch (e: ReportingIntegrityException) {
            throw e
        } catch (e: IllegalStateException) {
            throw ReportingIntegrityException("Reporting could not map $label: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ReportingIntegrityException("Reporting could not map $label: ${e.message}", e)
        } catch (e: ArithmeticException) {
            throw ReportingIntegrityException("Reporting could not map $label: ${e.message}", e)
        }

    private companion object {
        const val CHUNK_SIZE = 500
    }
}