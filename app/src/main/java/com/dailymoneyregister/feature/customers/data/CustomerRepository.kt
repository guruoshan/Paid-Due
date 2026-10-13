package com.dailymoneyregister.feature.customers.data

import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.database.dao.CustomerSummary
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.feature.register.data.ExpenseReadProjection
import com.dailymoneyregister.feature.register.data.ExpenseReadProjectionResolver
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshotCodec
import javax.inject.Inject

/** One invoice line in a customer's history. Read-only. */
data class CustomerInvoiceRow(
    val documentId: String,
    val displayNumber: String,
    val createdAt: Long,
    /** Null only when the stored snapshot could not be read. */
    val totalMinor: Long?,
    val currencyCode: String?,
    val decimalPlaces: Int?,
    /** True once the invoice has been linked to a paid sale. */
    val isPaid: Boolean
)

enum class CustomerCreditHistoryKind {
    OBLIGATION,
    REPAYMENT,
    ADJUSTMENT
}

/**
 * Read-only projection of authoritative Credit ledger evidence.
 * No mutable balance or independent financial state is stored here.
 */
data class CustomerCreditHistoryRow(
    val kind: CustomerCreditHistoryKind,
    val amountSignedMinor: Long,
    val currencyCode: String,
    val occurredAt: Long,
    val repaymentMethodName: String? = null
)

data class CustomerHistory(
    val customer: CustomerEntity,
    val transactions: List<FinancialTransactionView>,
    val expenses: List<ExpenseReadProjection>,
    val creditHistory: List<CustomerCreditHistoryRow>,
    val invoices: List<CustomerInvoiceRow>
)

/**
 * Read-only access to customers and the invoices linked to them.
 *
 * Nothing here writes to the database or touches the ledger.
 * Customers are limited to the signed-in business, and the same
 * permission check runs here that the UI uses to show the entry point.
 */
class CustomerRepository @Inject constructor(
    private val customerDao: CustomerDao,
    private val database: DmrDatabase
) {

    suspend fun listCustomers(): List<CustomerSummary> {
        val businessId = requireDirectoryAccess()
        return customerDao.getCustomerSummaries(businessId)
    }

    /** Returns null when the customer does not exist in the signed-in business. */
    suspend fun getHistory(customerId: String): CustomerHistory? {
        val businessId = requireHistoryAccess()

        val customer = customerDao.getCustomerById(customerId) ?: return null
        if (customer.businessId != businessId) return null

        val session = SessionManager.currentSession.value
            ?: throw PermissionDeniedException("No active session")

        val openedBranchId = session.branch.id

        val transactions = database.transactionDao()
            .getCustomerFinancialRecords(
                branchId = openedBranchId,
                customerId = customerId
            )
            .map { record ->
                check(record.transaction.branchId == openedBranchId) {
                    "Customer transaction escaped active branch"
                }

                check(record.transaction.customerId == customerId) {
                    "Customer transaction escaped requested customer"
                }

                val relatedCustomer = record.customer
                    ?: error("Customer transaction is missing its customer relation")

                check(relatedCustomer.id == customerId) {
                    "Customer transaction relation resolved to a different customer"
                }

                check(relatedCustomer.businessId == businessId) {
                    "Customer transaction relation escaped active business"
                }

                FinancialReadModelResolver.resolve(record)
            }
        /*
         * Credit History is derived directly from authoritative
         * append-only Credit ledger events.
         */
        val creditLedger =
            database.ledgerDao()
                .getCreditLedgerForCustomer(customerId)

        val repaymentIds =
            creditLedger
                .mapNotNull { it.repaymentId }
                .distinct()

        val repaymentsById =
            if (repaymentIds.isEmpty()) {
                emptyMap()
            } else {
                database.ledgerDao()
                    .getCreditRepaymentsByIds(repaymentIds)
                    .associateBy { it.id }
            }

        val creditHistory =
            creditLedger
                .map { event ->
                    check(event.customerId == customerId) {
                        "Credit ledger event escaped requested customer"
                    }

                    val kind =
                        when (event.type.name) {
                            "OBLIGATION" ->
                                CustomerCreditHistoryKind.OBLIGATION

                            "REPAYMENT" ->
                                CustomerCreditHistoryKind.REPAYMENT

                            "ADJUSTMENT" ->
                                CustomerCreditHistoryKind.ADJUSTMENT

                            else ->
                                error("Unsupported Credit ledger type: ${event.type}")
                        }

                    val repaymentMethodName =
                        event.repaymentId
                            ?.let(repaymentsById::get)
                            ?.paymentCategory
                            ?.name

                    CustomerCreditHistoryRow(
                        kind = kind,
                        amountSignedMinor = event.amountSignedMinor,
                        currencyCode = event.currencyCode,
                        occurredAt = event.occurredAt,
                        repaymentMethodName = repaymentMethodName
                    )
                }
                .sortedByDescending { it.occurredAt }

        val rows = customerDao.getInvoicesForCustomer(customerId).map { document ->
            val snapshot = runCatching { InvoiceSnapshotCodec.decode(document.snapshotJson) }.getOrNull()
            CustomerInvoiceRow(
                documentId = document.id,
                displayNumber = document.displayNumber,
                createdAt = document.createdAt,
                totalMinor = snapshot?.grandTotalMinor,
                currencyCode = snapshot?.currencyCode,
                decimalPlaces = snapshot?.currencyDecimalPlaces,
                isPaid = document.transactionId != null
            )
        }
        val expenses = database.expenseDao().getExpensesForCustomer(
            businessId = businessId,
            branchId = openedBranchId,
            customerId = customerId
        )

        val expenseSettlementsByOperation =
            expenses
                .map { it.operationId }
                .distinct()
                .chunked(900)
                .flatMap { operationIds ->
                    database.transactionDao().getSettlementsForOperations(operationIds)
                }
                .groupBy { it.operationId }

        val expenseProjections = expenses.map { expense ->
            check(expense.businessId == businessId) { "Customer Expense escaped active business" }
            check(expense.branchId == openedBranchId) { "Customer Expense escaped active branch" }
            check(expense.payeeId == customerId) { "Customer Expense escaped requested customer" }
            val settlements = expenseSettlementsByOperation[expense.operationId].orEmpty()
            check(settlements.size == 1) {
                "Customer Expense settlement count mismatch for ${expense.id}"
            }
            ExpenseReadProjectionResolver.resolve(expense, settlements.single())
        }
        return CustomerHistory(
            customer = customer,
            transactions = transactions,
            expenses = expenseProjections,
            creditHistory = creditHistory,
            invoices = rows
        )
    }

    /** Directory access is business-wide, but requires a valid active user/session. */
    private fun requireDirectoryAccess(): String {
        val session = SessionManager.currentSession.value
            ?: throw PermissionDeniedException("No active session")
        if (session.requiresPinChange || !session.user.isActive ||
            session.user.businessId != session.business.id ||
            session.branch.businessId != session.business.id
        ) throw PermissionDeniedException("Active business session required")

        if (session.user.role.name == "CASHIER") {
            val membership = session.membership
                ?: throw PermissionDeniedException("Active branch membership required")
            if (membership.userId != session.user.id ||
                membership.branchId != session.branch.id ||
                membership.role.name != "CASHIER"
            ) throw PermissionDeniedException("Active branch membership mismatch")
        }
        PermissionChecker.assertPermitted(
            session.user, session.membership, PermissionAction.CUSTOMER_DIRECTORY_ACCESS
        )
        return session.business.id
    }

    /** Financial history retains the stricter existing broad-history permission. */
    private fun requireHistoryAccess(): String {
        val businessId = requireDirectoryAccess()
        val session = SessionManager.currentSession.value
            ?: throw PermissionDeniedException("No active session")
        PermissionChecker.assertPermitted(
            session.user, session.membership, PermissionAction.VIEW_BROAD_HISTORY
        )
        return businessId
    }

    companion object {
        /** UI gate mirrors directory access; history remains separately protected. */
        fun canViewCustomers(): Boolean {
            val session = SessionManager.currentSession.value ?: return false
            if (session.requiresPinChange || !session.user.isActive ||
                session.user.businessId != session.business.id ||
                session.branch.businessId != session.business.id
            ) return false
            if (session.user.role.name == "CASHIER") {
                val membership = session.membership ?: return false
                if (membership.userId != session.user.id ||
                    membership.branchId != session.branch.id ||
                    membership.role.name != "CASHIER"
                ) return false
            }
            return PermissionChecker.isPermitted(
                session.user, session.membership, PermissionAction.CUSTOMER_DIRECTORY_ACCESS
            )
        }
    }
}
