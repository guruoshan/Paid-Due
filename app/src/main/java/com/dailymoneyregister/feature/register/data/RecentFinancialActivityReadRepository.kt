package com.dailymoneyregister.feature.register.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.financial.RecentActivityReadModelResolver
import com.dailymoneyregister.core.model.financial.RecentFinancialActivity
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.financial.FinancialFundingPart
import com.dailymoneyregister.core.model.financial.RecentActivityStatus
import com.dailymoneyregister.core.model.financial.RecentActivitySourceType
import com.dailymoneyregister.core.model.financial.RecentActivityType
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandRequiresReconciliationException
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import com.dailymoneyregister.core.model.currency.CurrencyInvariant
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.timezone.TimezoneRegistry
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.Instant
import javax.inject.Inject

/**
 * BOARDLOCK Recent Financial Activity read boundary.
 *
 * Current Recent financial semantics are preserved.
 *
 * This component reads authoritative persisted financial evidence only.
 * It performs no financial mutation and owns no second financial state.
 *
 * observeLocalDayStarts() remains the existing package-level helper.
 */
internal class RecentFinancialActivityReadRepository(
    private val database: DmrDatabase,
    private val localDeviceId: String? = null
) {
    private val transactionDao =
        database.transactionDao()

    private val ledgerDao =
        database.ledgerDao()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeTodayTransactions(
        branchId: String,
        timezoneId: String
    ): Flow<List<RecentFinancialActivity>> {

        val zoneId = TimezoneRegistry.resolve(timezoneId)

        /*
         * BOARDLOCK Expense Recent scope:
         *
         * ExpenseDao is business + branch scoped. Recent must never invent
         * a business ID or fall back to an empty value. The Register itself
         * is active-session scoped, so capture that authoritative scope here
         * and fail closed when the requested branch is not the active branch.
         */
        val session =
            SessionManager.currentSession.value
                ?: return flow {
                    emit(emptyList())
                }

        if (session.branch.id != branchId) {
            return flow {
                emit(emptyList())
            }
        }

        val businessId = session.business.id

        return observeLocalDayStarts(zoneId).flatMapLatest { startOfDayMillis ->
            val nextStartOfDayMillis = Instant.ofEpochMilli(startOfDayMillis)
                .atZone(zoneId)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            val localOperationIdsFlow =
                localDeviceId?.let { deviceId ->
                    database.operationDao().observeSucceededOperationIdsForDevice(
                        branchId = branchId,
                        deviceId = deviceId,
                        fromInclusive = startOfDayMillis,
                        toExclusive = nextStartOfDayMillis
                    )
                } ?: flowOf(emptyList())

            val financialInputs = combine(
                transactionDao.observeTodayFinancialRecords(
                    branchId,
                    startOfDayMillis,
                    nextStartOfDayMillis
                ),
                ledgerDao.observeCreditRepaymentsForPeriod(
                    branchId,
                    startOfDayMillis,
                    nextStartOfDayMillis
                ),
                database.expenseDao().observeExpensesForPeriod(
                    businessId = businessId,
                    branchId = branchId,
                    fromInclusive = startOfDayMillis,
                    toExclusive = nextStartOfDayMillis
                ),
                database.expenseDao().observeExpenseEventsForPeriod(
                    businessId = businessId,
                    branchId = branchId,
                    fromInclusive = startOfDayMillis,
                    toExclusive = nextStartOfDayMillis
                )
            ) { records, repayments, expenses, expenseEvents ->
                Triple(records, repayments, expenses) to expenseEvents
            }

            combine(financialInputs, localOperationIdsFlow) { inputs, operationIds ->
                inputs to operationIds.toSet()
            }.map { (inputs, localOperationIds) ->
                val ((records, repayments, expenses), expenseEvents) = inputs
                val expenseEventsById = expenseEvents.groupBy { it.expenseId }

                val unresolvedTransactionActivities =
                    records.flatMap { record ->
                        RecentActivityReadModelResolver.resolve(
                            record,
                            startOfDayMillis,
                            nextStartOfDayMillis
                        )
                    }

                val couponRefundOperationIds =
                    unresolvedTransactionActivities
                        .asSequence()
                        .filter {
                            it.type ==
                                RecentActivityType.REFUND_TO_COUPON
                        }
                        .mapNotNull { it.operationId }
                        .toList()

                val couponRefundEvents =
                    loadRecentRowsInBatches(
                        couponRefundOperationIds,
                        ledgerDao::getCouponEventsForOperations
                    ).filter {
                        it.type ==
                            com.dailymoneyregister.core.model.enums
                                .CouponLedgerType.REFUND_CREDIT &&
                            it.amountSignedMinor > 0L
                    }

                val couponWalletsById =
                    loadRecentRowsInBatches(
                        couponRefundEvents.map { it.walletId },
                        ledgerDao::getCouponWalletsByIds
                    ).associateBy { it.id }

                val customersById =
                    loadRecentRowsInBatches(
                        repayments.map { it.customerId } +
                            couponWalletsById.values.map {
                                it.customerId
                            },
                        database.customerDao()::getCustomersByIds
                    ).associateBy { it.id }

                val settlementsByOperation =
                    loadRecentRowsInBatches(
                        repayments.map { it.operationId } +
                            expenses.map { it.operationId },
                        transactionDao::getSettlementsForOperations
                    ).groupBy { it.operationId }

                val transactionActivities =
                    resolveRecentCouponRefundRecipients(
                        activities = unresolvedTransactionActivities,
                        couponRefundEventsByOperation =
                            couponRefundEvents.groupBy {
                                it.operationId
                            },
                        couponWalletsById = couponWalletsById,
                        customersById = customersById
                    )

                val repaymentActivities =
                    repayments.map { repayment ->
                        RecentActivityReadModelResolver.resolveCreditRepayment(
                            repayment = repayment,
                            customerName =
                                customersById[repayment.customerId]
                                    ?.name,
                            settlements =
                                settlementsByOperation[
                                    repayment.operationId
                                ].orEmpty()
                        )
                    }

                val expenseActivities =
                    expenses.map { expense ->

                        val settlements =
                            settlementsByOperation[
                                expense.operationId
                            ].orEmpty()

                        check(settlements.size == 1) {
                            "Expense Recent settlement count mismatch for ${expense.id}"
                        }

                        val projection =
                            ExpenseReadProjectionResolver.resolve(
                                expense = expense,
                                settlement = settlements.single(),
                                events = expenseEventsById[expense.id].orEmpty()
                            )

                        RecentFinancialActivity(
                            activityId =
                                "expense:${projection.expenseId}",
                            transactionId = null,
                            operationId =
                                projection.operationId,
                            type =
                                RecentActivityType.EXPENSE,
                            signedEffect =
                                projection.signedEffect,
                            fundingBreakdown =
                                projection.fundingBreakdown,
                            occurredAt =
                                projection.occurredAt,
                            customerId = null,
                            customerName =
                                projection.payeeName,
                            status =
                                if (projection.isVoided) RecentActivityStatus.VOIDED
                                else RecentActivityStatus.RECORDED,
                            originalTransactionId = null,
                            sourceType =
                                RecentActivitySourceType.EXPENSE,
                            sourceReference =
                                projection.expenseId,
                            displayRef =
                                projection.displayReference,
                            reason = projection.voidReason
                        )
                    }
                val allActivities =
                    transactionActivities +
                        repaymentActivities +
                        expenseActivities

                // Recent Activity is a shared read view: show the same
                // authoritative Room evidence on every logged-in device.
                allActivities.sortedWith(
                        compareByDescending<RecentFinancialActivity> {
                            it.occurredAt
                        }.thenByDescending {
                            it.activityId
                        }
                    )
            }
            }
    }

    /**
     * Resolves the actual recipient of Refund-to-Coupon activity from
     * authoritative persisted evidence.
     *
     * operationId -> Coupon ledger event -> Coupon wallet -> customer
     *
     * This is read-only presentation enrichment. It does not mutate
     * transactions, refunds, Credit, Coupon value, settlements or balances.
     */
    private suspend fun resolveRecentCouponRefundRecipients(
        activities: List<RecentFinancialActivity>,
        couponRefundEventsByOperation:
            Map<String, List<CouponLedgerEventEntity>>,
        couponWalletsById: Map<String, CouponWalletEntity>,
        customersById: Map<String, CustomerEntity>
    ): List<RecentFinancialActivity> =
        activities.map { activity ->
            if (
                activity.type !=
                    com.dailymoneyregister.core.model.financial.RecentActivityType
                        .REFUND_TO_COUPON
            ) {
                return@map activity
            }

            val operationId =
                activity.operationId
                    ?: return@map activity

            val refundEvents =
                couponRefundEventsByOperation[operationId]
                    .orEmpty()

            /*
             * Do not guess when persisted evidence is missing or ambiguous.
             * Legacy rows keep their existing presentation.
             */
            val refundEvent =
                refundEvents.singleOrNull()
                    ?: return@map activity

            val wallet =
                couponWalletsById[refundEvent.walletId]
                    ?: return@map activity

            val recipient =
                customersById[wallet.customerId]
                    ?: return@map activity

            activity.copy(
                customerId = recipient.id,
                customerName = recipient.name
            )
        }
}

private const val RECENT_READ_BATCH_SIZE = 900

/**
 * Keeps Recent's read amplification bounded without relying on SQLite's
 * maximum bind-parameter count. Empty inputs deliberately execute no query.
 */
internal suspend fun <T> loadRecentRowsInBatches(
    ids: Collection<String>,
    query: suspend (List<String>) -> List<T>
): List<T> =
    ids.asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
        .chunked(RECENT_READ_BATCH_SIZE)
        .flatMap { batch -> query(batch) }
