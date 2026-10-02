package com.dailymoneyregister.feature.invoice.data

import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshotCodec
import java.security.MessageDigest

/**
 * Durable intent to issue ONE customer invoice.
 *
 * Non-financial: no transaction is created (transactionId = null).
 * The operation id permanently belongs to this exact snapshot.
 */
data class GenerateInvoiceCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,
    val snapshot: InvoiceSnapshot,
    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis()
) : OperationCommand {

    override val operationType = OperationType.GENERATE_DOCUMENT
    override val subjectType = SubjectType.DOCUMENT
    override val subjectId: String = snapshot.documentId
    override val transactionId: String? = null
    override val commandSchemaVersion = InvoiceSnapshotCodec.CURRENT_SCHEMA_VERSION

    override val intentPayload: String
        get() = InvoiceSnapshotCodec.encode(snapshot)

    override val requestFingerprint: String
        get() = "invoice-v1|biz:$businessId|br:$branchId|act:$actorUserId|" +
            "doc:${snapshot.documentId}|time:$occurredAt|" +
            "payload:${sha256Hex(intentPayload)}"

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
