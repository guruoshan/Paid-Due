package com.dailymoneyregister.feature.register.data

import android.util.Base64
import com.dailymoneyregister.core.command.CommandFingerprint
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId

enum class ExpenseCategory {
    RENT,
    UTILITIES,
    SUPPLIES,
    TRANSPORT,
    SALARY_WAGES,
    MAINTENANCE,
    FEES,
    FOOD_REFRESHMENTS,
    OTHER
}

/**
 * Single Expense payment-identity contract.
 *
 * BOARDLOCK:
 * - owns the Cash/Online channel rules used by Expense command creation;
 * - keeps those rules out of RegisterViewModel;
 * - does not write financial state.
 */
internal object ExpensePaymentContract {

    fun defaultChannel(
        paymentCategory: PaymentCategory
    ): PaymentChannel =
        when (paymentCategory) {
            PaymentCategory.CASH ->
                PaymentChannel.CASH_TENDER

            PaymentCategory.ONLINE ->
                PaymentChannel.MANUAL_QR

            PaymentCategory.CREDIT ->
                error("Credit is not supported for Expense.")

            PaymentCategory.COUPON ->
                error("Coupon is not supported for Expense.")
        }

    fun requireSupported(
        paymentCategory: PaymentCategory,
        paymentChannel: PaymentChannel
    ) {
        when (paymentCategory) {
            PaymentCategory.CASH ->
                require(
                    paymentChannel == PaymentChannel.CASH_TENDER
                ) {
                    "Cash Expense requires CASH_TENDER."
                }

            PaymentCategory.ONLINE ->
                require(
                    paymentChannel == PaymentChannel.MANUAL_QR ||
                        paymentChannel == PaymentChannel.SOFTPOS
                ) {
                    "Online Expense requires an Online payment channel."
                }

            PaymentCategory.CREDIT ->
                error("Credit is not supported for Expense.")

            PaymentCategory.COUPON ->
                error("Coupon is not supported for Expense.")
        }
    }
}

/**
 * Creates the durable Expense command from already-parsed register input.
 *
 * IDs, snapshot normalization and payment-channel selection are command-layer
 * concerns. RegisterViewModel remains responsible only for UI input parsing and
 * orchestration.
 */
internal object ExpenseCommandFactory {

    fun createNew(
        businessId: String,
        branchId: String,
        actorUserId: String,
        amountMinor: Long,
        currencyCode: String,
        category: ExpenseCategory,
        paymentCategory: PaymentCategory,
        payeeName: String,
        payeePhone: String?,
        reference: String,
        note: String,
        deviceId: String,
        occurredAt: Long
    ): ExpenseCommand =
        ExpenseCommand(
            operationId = OperationId.generate(),
            businessId = businessId,
            branchId = branchId,
            actorUserId = actorUserId,
            expenseId = IdGenerator.newId(),
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            category = category,
            paymentCategory = paymentCategory,
            paymentChannel =
                ExpensePaymentContract.defaultChannel(
                    paymentCategory
                ),
            payeeId = null,
            payeeNameSnapshot = payeeName.trim(),
            payeePhoneSnapshot =
                payeePhone
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            payeeAccountSnapshot = null,
            reference =
                reference
                    .trim()
                    .takeIf { it.isNotEmpty() },
            note =
                note
                    .trim()
                    .takeIf { it.isNotEmpty() },
            recurrenceId = null,
            deviceId = deviceId,
            occurredAt = occurredAt
        )
}

data class ExpenseCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,

    val expenseId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val category: ExpenseCategory,

    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel,

    val payeeId: String? = null,
    val payeeNameSnapshot: String = "",
    val payeePhoneSnapshot: String? = null,
    val payeeAccountSnapshot: String? = null,

    val reference: String? = null,
    val note: String? = null,
    val recurrenceId: String? = null,

    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis()
) : OperationCommand {

    init {
        require(expenseId.isNotBlank()) {
            "Expense ID is required."
        }

        require(businessId.isNotBlank()) {
            "Expense business is required."
        }

        require(branchId.isNotBlank()) {
            "Expense branch is required."
        }

        require(actorUserId.isNotBlank()) {
            "Expense cashier is required."
        }

        require(amountMinor > 0L) {
            "Expense amount must be positive."
        }

        require(currencyCode.isNotBlank()) {
            "Expense currency is required."
        }

        ExpensePaymentContract.requireSupported(
            paymentCategory = paymentCategory,
            paymentChannel = paymentChannel
        )
    }

    override val operationType =
        OperationType.RECORD_EXPENSE

    override val subjectType =
        SubjectType.EXPENSE

    override val subjectId =
        expenseId

    override val transactionId: String? =
        null

    override val commandSchemaVersion =
        ExpensePayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() =
            CommandFingerprint.sha256(
                "expense-v1|" +
                    "biz:$businessId|" +
                    "br:$branchId|" +
                    "actor:$actorUserId|" +
                    "expense:$expenseId|" +
                    "amount:$amountMinor|" +
                    "currency:$currencyCode|" +
                    "category:${category.name}|" +
                    "payment:${paymentCategory.name}|" +
                    "channel:${paymentChannel.name}|" +
                    "payee:${payeeId.orEmpty()}|" +
                    "payeeName:$payeeNameSnapshot|" +
                    "payeePhone:${payeePhoneSnapshot.orEmpty()}|" +
                    "payeeAccount:${payeeAccountSnapshot.orEmpty()}|" +
                    "reference:${reference.orEmpty()}|" +
                    "note:${note.orEmpty()}|" +
                    "recurrence:${recurrenceId.orEmpty()}|" +
                    "device:$deviceId|" +
                    "time:$occurredAt"
            )

    override val intentPayload: String
        get() = ExpensePayloadCodec.encode(this)
}

object ExpensePayloadCodec {

    const val CURRENT_SCHEMA_VERSION = 1
    private const val PREFIX = "expense-v1"

    fun encode(command: ExpenseCommand): String =
        listOf(
            PREFIX,
            command.operationId.value,
            command.businessId,
            command.branchId,
            command.actorUserId,
            command.expenseId,
            command.amountMinor.toString(),
            command.currencyCode,
            command.category.name,
            command.paymentCategory.name,
            command.paymentChannel.name,
            command.payeeId.orEmpty(),
            command.payeeNameSnapshot,
            command.payeePhoneSnapshot.orEmpty(),
            command.payeeAccountSnapshot.orEmpty(),
            command.reference.orEmpty(),
            command.note.orEmpty(),
            command.recurrenceId.orEmpty(),
            command.deviceId,
            command.occurredAt.toString()
        ).joinToString("|") {
            Base64.encodeToString(
                it.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        }

    fun decode(payload: String): ExpenseCommand {
        val parts =
            payload.split('|').map {
                String(
                    Base64.decode(it, Base64.NO_WRAP),
                    Charsets.UTF_8
                )
            }

        require(parts.size == 20) {
            "Invalid Expense durable payload."
        }

        require(parts[0] == PREFIX) {
            "Unsupported Expense durable payload version."
        }

        return ExpenseCommand(
            operationId = OperationId(parts[1]),
            businessId = parts[2],
            branchId = parts[3],
            actorUserId = parts[4],
            expenseId = parts[5],
            amountMinor =
                parts[6].toLongOrNull()
                    ?: error("Invalid Expense amount."),
            currencyCode = parts[7],
            category =
                ExpenseCategory.valueOf(parts[8]),
            paymentCategory =
                PaymentCategory.valueOf(parts[9]),
            paymentChannel =
                PaymentChannel.valueOf(parts[10]),
            payeeId = parts[11].ifBlank { null },
            payeeNameSnapshot = parts[12],
            payeePhoneSnapshot =
                parts[13].ifBlank { null },
            payeeAccountSnapshot =
                parts[14].ifBlank { null },
            reference =
                parts[15].ifBlank { null },
            note =
                parts[16].ifBlank { null },
            recurrenceId =
                parts[17].ifBlank { null },
            deviceId = parts[18],
            occurredAt =
                parts[19].toLongOrNull()
                    ?: error("Invalid Expense occurredAt.")
        )
    }
}

data class ExpenseCommandResult(
    val expenseId: String,
    val operationId: String,
    val amountMinor: Long,
    val category: ExpenseCategory,
    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel,
    val actorUserId: String,
    val cashShiftId: String,
    val occurredAt: Long
)
