package com.dailymoneyregister.feature.invoice.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.DocumentEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.model.enums.DocumentType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshotCodec
import com.dailymoneyregister.feature.invoice.domain.validationError
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** An issued invoice: the stored row plus its decoded frozen content. */
data class IssuedInvoice(
    val document: DocumentEntity,
    val snapshot: InvoiceSnapshot
) {
    val displayNumber: String get() = document.displayNumber

    /** True once the invoice has been linked to a paid sale. */
    val isPaid: Boolean get() = document.transactionId != null
}

/**
 * Issues and reads customer invoices.
 *
 * Invoices are NON-FINANCIAL documents stored in the existing
 * `documents` table through the normal command spine:
 *   operations row (GENERATE_DOCUMENT) + documents row, one transaction.
 *
 * Issuing also creates or updates the customer (matched by business +
 * phone) and links the document to it through documents.customer_id, in
 * the same database transaction. No ledger effect.
 */
class InvoiceRepository @Inject constructor(
    private val database: DmrDatabase
) {
    private val commandExecutor = CommandExecutor(database)
    private val documentDao = database.documentMediaDao()
    private val customerDao = database.customerDao()
    private val branchDao = database.businessBranchDao()
    private val userDao = database.userDao()
    private val operationDao = database.operationDao()

    // ------------------------------------------------------------------
    // Issue
    // ------------------------------------------------------------------

    suspend fun executeGenerateInvoice(
        command: GenerateInvoiceCommand
    ): CommandResult<DocumentEntity> {

        // A known operation id is a replay / crash-recovery: the executor
        // verifies fingerprint + durable intent itself.
        val known = operationDao.getOperationById(command.operationId.value)

        if (known == null) {
            authorize(command)?.let { return it }
            validate(command)?.let { return it }
        }

        return try {
            commandExecutor.execute(
                command = command,
                replayResolver = { operation -> loadIssued(operation) }
            ) {
                materialize(command)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            CommandResult.Error(error)
        }
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    suspend fun getInvoice(documentId: String): IssuedInvoice? {
        val doc = documentDao.getDocumentById(documentId) ?: return null
        if (doc.documentType != DocumentType.INVOICE) return null
        return IssuedInvoice(doc, InvoiceSnapshotCodec.decode(doc.snapshotJson))
    }

    suspend fun listInvoices(branchId: String, limit: Int = 50): List<IssuedInvoice> =
        documentDao.getInvoicesForBranch(branchId, limit).map {
            IssuedInvoice(it, InvoiceSnapshotCodec.decode(it.snapshotJson))
        }

    suspend fun findCustomerByPhone(
        businessId: String,
        canonicalPhone: String
    ): CustomerEntity? = customerDao.getCustomerByPhone(businessId, canonicalPhone)

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun loadIssued(operation: OperationEntity): DocumentEntity {
        val doc = documentDao.getDocumentByOperationId(operation.id)
            ?: error("Invoice for succeeded operation '${operation.id}' is missing")
        check(doc.documentType == DocumentType.INVOICE) {
            "Operation '${operation.id}' does not reference an invoice"
        }
        return doc
    }

    /**
     * Runs INSIDE the executor's database transaction, so numbering and
     * insert are serialized and atomic with the operation state change.
     */
    private suspend fun materialize(
        command: GenerateInvoiceCommand
    ): Pair<DocumentEntity, Pair<String, String>> {

        val branch = branchDao.getBranchById(command.branchId)
            ?: error("Invoice branch does not exist")

        val zone = ZoneId.of(branch.timezoneId)
        val year = Instant.ofEpochMilli(command.occurredAt).atZone(zone).year
        val yearStart = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yearEnd = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()

        val sequence = documentDao.countInvoicesInRange(branch.id, yearStart, yearEnd) + 1
        val displayNumber =
            "INV-${branch.code}-$year-${sequence.toString().padStart(6, '0')}"

        val customerId = upsertCustomer(command)

        val document = DocumentEntity(
            id = command.snapshot.documentId,
            documentType = DocumentType.INVOICE,
            subjectType = SubjectType.DOCUMENT,
            subjectId = command.snapshot.documentId,
            transactionId = null,
            operationId = command.operationId.value,
            snapshotJson = InvoiceSnapshotCodec.encode(command.snapshot),
            displayNumber = displayNumber,
            createdAt = command.occurredAt,
            createdByUserId = command.actorUserId,
            customerId = customerId
        )

        documentDao.insertDocument(document)

        return document to (REFERENCE_TYPE to document.id)
    }

    /**
     * Finds the customer by (business, phone). Creates it when new; when it
     * exists and the name on this invoice differs, updates the stored name.
     * Runs inside the same transaction as the document insert, so a
     * document can never point at a customer that was not saved.
     * Returns the customer id to store on the document.
     */
    private suspend fun upsertCustomer(command: GenerateInvoiceCommand): String {
        val phone = command.snapshot.customer.phone.trim()
        val name = command.snapshot.customer.name.trim()
        val now = command.occurredAt

        val existing = customerDao.getCustomerByPhone(command.businessId, phone)
        if (existing == null) {
            val created = CustomerEntity(
                id = IdGenerator.newId(),
                businessId = command.businessId,
                canonicalPhone = phone,
                name = name,
                createdAt = now,
                updatedAt = now
            )
            customerDao.insertCustomer(created)
            return created.id
        }

        if (existing.name != name) {
            customerDao.updateCustomer(existing.copy(name = name, updatedAt = now))
        }
        return existing.id
    }

    private suspend fun validate(command: GenerateInvoiceCommand): CommandResult<Nothing>? {
        val branch = branchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(IllegalArgumentException("Invoice branch does not exist"))

        val s = command.snapshot

        if (s.currencyCode != branch.currencyCode) {
            return CommandResult.Error(
                IllegalArgumentException("Invoice currency does not match branch currency")
            )
        }
        if (s.currencyDecimalPlaces != branch.currencyDecimalPlaces) {
            return CommandResult.Error(
                IllegalArgumentException("Invoice currency precision does not match branch configuration")
            )
        }
        s.validationError()?.let {
            return CommandResult.Error(IllegalArgumentException(it))
        }
        return null
    }

    /** Same admission rules as RegisterRepository.authorizeNewFinancialMutation. */
    private suspend fun authorize(command: GenerateInvoiceCommand): CommandResult<Nothing>? {
        val label = "Invoice"

        val session = SessionManager.currentSession.value
            ?: return CommandResult.PermissionDenied("$label requires an authenticated session")

        if (session.user.id != command.actorUserId) {
            return CommandResult.PermissionDenied("$label actor does not match the authenticated user")
        }
        if (session.business.id != command.businessId || session.branch.id != command.branchId) {
            return CommandResult.PermissionDenied("$label business/branch does not match the authenticated session")
        }
        if (session.requiresPinChange) {
            return CommandResult.PermissionDenied("Cashier must create a new PIN before using the register")
        }

        val actor = userDao.getUserById(command.actorUserId)
            ?: return CommandResult.PermissionDenied("$label actor does not exist")

        if (actor.mustChangePin) {
            return CommandResult.PermissionDenied("Cashier must create a new PIN before using the register")
        }
        if (!actor.isActive) {
            return CommandResult.PermissionDenied("Inactive user cannot issue an invoice")
        }
        if (actor.businessId != command.businessId) {
            return CommandResult.PermissionDenied("$label business does not match the authenticated user")
        }

        val branch = branchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(IllegalArgumentException("$label branch does not exist"))

        if (branch.businessId != command.businessId) {
            return CommandResult.PermissionDenied("$label branch does not belong to the command business")
        }

        val membership = userDao.getMembership(actor.id, branch.id)
            ?: return CommandResult.PermissionDenied("User is not a member of the $label branch")

        return try {
            PermissionChecker.assertPermitted(actor, membership, PermissionAction.ISSUE_INVOICE)
            null
        } catch (error: PermissionDeniedException) {
            CommandResult.PermissionDenied(error.message ?: "Permission denied for $label")
        }
    }

    private companion object {
        const val REFERENCE_TYPE = "DOCUMENT"
    }
}
