package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.security.SessionManager
import kotlinx.coroutines.flow.Flow

/**
 * BOARDLOCK read boundary for register queries that are not part of a
 * specialized projection repository.
 *
 * This collaborator owns no financial state and performs no writes.
 * RegisterRepository keeps its public methods as compatibility delegates.
 */
internal class RegisterQueryRepository(
    database: DmrDatabase
) {
    private val transactionDao = database.transactionDao()
    private val customerDao = database.customerDao()
    private val ledgerDao = database.ledgerDao()

    fun observeRecentTransactions(
        branchId: String,
        limit: Int = 20
    ): Flow<List<TransactionEntity>> =
        transactionDao.observeRecentTransactions(
            branchId = branchId,
            limit = limit
        )

    suspend fun getCustomerCreditOutstanding(
        customerId: String,
        currencyCode: String
    ): Long {
        val session =
            checkNotNull(SessionManager.currentSession.value) {
                "No active session."
            }

        val customer =
            checkNotNull(customerDao.getCustomerById(customerId)) {
                "Customer does not exist."
            }

        require(customer.businessId == session.business.id) {
            "Customer does not belong to the active business."
        }

        require(currencyCode == session.branch.currencyCode) {
            "Credit currency does not match the active branch."
        }

        return ledgerDao.getCreditBalance(
            customerId,
            currencyCode
        )
    }
}
