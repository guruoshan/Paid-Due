package com.dailymoneyregister.feature.customers.data

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshotCodec
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class BackfillResult(
    /** Invoices that now point at a customer. */
    val linked: Int,
    /** Customers that did not exist and were created from an invoice. */
    val customersCreated: Int,
    /** Invoices left alone because their saved phone or name could not be used. */
    val skipped: Int
)

/**
 * One-time helper that links invoices issued BEFORE customer linking existed
 * (their documents.customer_id is empty) to a customer, using the phone and
 * name frozen inside each invoice.
 *
 * Safe by design:
 * - Only fills an empty link. It never changes an existing link.
 * - Never renames or edits an existing customer.
 * - Never touches invoice content, transactions, ledger or any financial table.
 * - All-or-nothing: one database transaction, so a failure changes nothing.
 * - Repeatable: running it again finds nothing left to do.
 * - Invoices with an unreadable snapshot or a phone that is not a valid
 *   international number are skipped and reported, not guessed.
 */
class InvoiceLinkBackfillRepository @Inject constructor(
    private val database: DmrDatabase
) {

    private val customerDao = database.customerDao()

    suspend fun countUnlinkedInvoices(): Int {
        val businessId = requireAccess()
        return customerDao.countUnlinkedInvoices(businessId)
    }

    suspend fun linkOlderInvoices(): BackfillResult {
        val businessId = requireAccess()

        return database.withTransaction {
            // Newest first: when a new customer has to be created, the most
            // recent name used with that phone wins.
            val unlinked = customerDao.getUnlinkedInvoices(businessId)
            val customerIdByPhone = mutableMapOf<String, String>()
            var linked = 0
            var created = 0
            var skipped = 0
            val now = System.currentTimeMillis()

            for (document in unlinked) {
                val snapshot = try {
                    InvoiceSnapshotCodec.decode(document.snapshotJson)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }

                val phone = snapshot?.customer?.phone?.trim().orEmpty()
                val name = snapshot?.customer?.name?.trim().orEmpty()
                if (!E164.matches(phone) || name.isEmpty()) {
                    skipped++
                    continue
                }

                var customerId = customerIdByPhone[phone]
                if (customerId == null) {
                    val existing = customerDao.getCustomerByPhone(businessId, phone)
                    if (existing != null) {
                        customerId = existing.id
                    } else {
                        val customer = CustomerEntity(
                            id = IdGenerator.newId(),
                            businessId = businessId,
                            canonicalPhone = phone,
                            name = name,
                            createdAt = now,
                            updatedAt = now
                        )
                        customerDao.insertCustomer(customer)
                        customerId = customer.id
                        created++
                    }
                    customerIdByPhone[phone] = customerId
                }

                if (customerDao.linkInvoiceToCustomer(document.id, customerId) == 1) {
                    linked++
                }
            }

            BackfillResult(linked = linked, customersCreated = created, skipped = skipped)
        }
    }

    /** Returns the signed-in business id, or throws when access is not allowed. */
    private fun requireAccess(): String {
        val session = SessionManager.currentSession.value
            ?: throw PermissionDeniedException("No active session")
        if (session.requiresPinChange) {
            throw PermissionDeniedException("PIN change required before linking invoices")
        }
        PermissionChecker.assertPermitted(session.user, session.membership, PermissionAction.VIEW_BROAD_HISTORY)
        PermissionChecker.assertPermitted(session.user, session.membership, PermissionAction.ISSUE_INVOICE)
        return session.business.id
    }

    companion object {
        private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")

        /** UI gate only. The repository enforces the real check again. */
        fun canBackfill(): Boolean {
            val session = SessionManager.currentSession.value ?: return false
            if (session.requiresPinChange) return false
            return PermissionChecker.isPermitted(session.user, session.membership, PermissionAction.VIEW_BROAD_HISTORY) &&
                PermissionChecker.isPermitted(session.user, session.membership, PermissionAction.ISSUE_INVOICE)
        }
    }
}
