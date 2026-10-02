package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Read-only broad History.
 *
 * No financial state is written here.
 * TransactionFinancialRecord and FinancialReadModelResolver remain authoritative.
 */
class HistoryRepository @Inject constructor(
    private val database: DmrDatabase
) {

    fun observeHistory():
        Flow<List<HistoryTransactionRow>> =
        flow {

            val session =
                SessionManager.currentSession.value
                    ?: throw SecurityException(
                        "History requires an authenticated session"
                    )

            val membership =
                SessionManager.activeMembership
                    ?: throw SecurityException(
                        "History requires active branch membership"
                    )

            if (
                membership.branchId !=
                session.branch.id
            ) {
                throw SecurityException(
                    "History membership does not match active branch"
                )
            }

            PermissionChecker.assertPermitted(
                session.user,
                membership,
                PermissionAction.VIEW_BROAD_HISTORY
            )

            val openedBusinessId =
                session.business.id

            val openedBranchId =
                session.branch.id

            emitAll(
                database
                    .transactionDao()
                    .observeHistoryFinancialRecords(
                        openedBranchId
                    )
                    .map { records ->

                        val liveSession =
                            SessionManager.currentSession.value
                                ?: throw SecurityException(
                                    "History session ended"
                                )

                        val liveMembership =
                            SessionManager.activeMembership
                                ?: throw SecurityException(
                                    "History membership ended"
                                )

                        if (
                            liveSession.business.id !=
                            openedBusinessId ||
                            liveSession.branch.id !=
                            openedBranchId ||
                            liveMembership.branchId !=
                            openedBranchId
                        ) {
                            throw SecurityException(
                                "History session changed"
                            )
                        }

                        PermissionChecker.assertPermitted(
                            liveSession.user,
                            liveMembership,
                            PermissionAction.VIEW_BROAD_HISTORY
                        )

                        records.map { record ->

                            if (
                                record.transaction.branchId !=
                                openedBranchId
                            ) {
                                throw IllegalStateException(
                                    "History record escaped active branch"
                                )
                            }

                            val customer =
                                record.customer
                                    ?.takeIf {
                                        it.id ==
                                            record.transaction.customerId &&
                                        it.businessId ==
                                            openedBusinessId
                                    }

                            HistoryTransactionRow(
                                financial =
                                    FinancialReadModelResolver.resolve(
                                        record
                                    ),
                                customerName =
                                    customer?.name,
                                canonicalPhone =
                                    customer?.canonicalPhone
                            )
                        }
                    }
            )
        }
}