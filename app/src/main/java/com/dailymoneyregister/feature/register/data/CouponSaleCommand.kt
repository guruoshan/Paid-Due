package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.*
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.domain.DraftState
import java.io.*
import java.security.MessageDigest
import java.util.Base64

/** Frozen purchase intent. The existing Operation is its durable identity and audit record. */
data class CouponSaleCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,
    val transactionIdVal: TransactionId,
    val customerId: String,
    val customerName: String,
    val canonicalPhone: String,
    val createCustomer: Boolean,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val fundingCategory: PaymentCategory,
    val additionalUserNames: List<String>,
    override val deviceId: String,
    override val occurredAt: Long
) : OperationCommand {
    override val operationType = OperationType.RECORD_COUPON_PURCHASE
    override val subjectType = SubjectType.TRANSACTION
    override val subjectId = transactionIdVal.value
    override val transactionId = transactionIdVal.value
    override val commandSchemaVersion = 1
    override val intentPayload: String get() = CouponSalePayloadCodec.encode(this)
    override val requestFingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(intentPayload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun validate() {
        require(listOf(operationId.value, businessId, branchId, actorUserId, subjectId,
            customerId, deviceId, currencyCode).all { it.isNotBlank() })
        require(amountMinor > 0 && currencyDecimalPlaces in 0..3)
        require(customerName.isNotBlank() && customerName == customerName.trim())
        require(Regex("\\+[1-9][0-9]{6,14}").matches(canonicalPhone)) { "Use an international phone number including +country code" }
        require(fundingCategory == PaymentCategory.CASH || fundingCategory == PaymentCategory.ONLINE)
        require(additionalUserNames.size <= 20 && additionalUserNames.all { it.isNotBlank() && it == it.trim() })
    }

    fun writerIntent() = SaleFinancialWriter.Intent(
        operationId.value, subjectId, branchId, actorUserId, deviceId, occurredAt,
        currencyCode, currencyDecimalPlaces, DraftState(manualAmount = MoneyMinorUnits(amountMinor)),
        SaleFinancialWriter.FundingIdentity(fundingCategory,
            if (fundingCategory == PaymentCategory.CASH) PaymentChannel.CASH_TENDER else PaymentChannel.MANUAL_QR,
            ConfirmationType.CASHIER_CONFIRMED),
        TransactionType.COUPON_PURCHASE, customerId
    )
}

data class CouponSaleResult(val transaction: TransactionEntity, val walletId: String, val purchasedAmountMinor: Long)

/** Coupon funding is an extension of the existing sale intent, with the same draft codec. */
data class CouponRedemptionCommand(
    val sale: OnlineSaleCommand,
    val customerId: String,
    val walletId: String,
    val authorizedUserId: String,
    val authorizedUserName: String,
    val amountMinor: Long,
    val remainderCategory: PaymentCategory?
) : OperationCommand by sale {
    override val branchId: String get() = sale.branchId
    override val intentPayload: String get() = "REDEEM|1|" + listOf(
        sale.intentPayload, customerId, walletId, authorizedUserId, authorizedUserName,
        amountMinor.toString(), remainderCategory?.name.orEmpty()
    ).joinToString("|") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    override val requestFingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(intentPayload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun validate() {
        require(listOf(customerId, walletId, authorizedUserId, authorizedUserName).all { it.isNotBlank() })
        require(amountMinor > 0 && amountMinor <= sale.draftState.grandTotal.amount)
        require(if (amountMinor == sale.draftState.grandTotal.amount) remainderCategory == null else
            remainderCategory in listOf(PaymentCategory.CASH, PaymentCategory.ONLINE, PaymentCategory.CREDIT))
    }

    fun writerIntent() = SaleFinancialWriter.Intent(
        operationId.value, subjectId, branchId, actorUserId, deviceId, occurredAt,
        sale.currencyCode, sale.currencyDecimalPlaces, sale.draftState,
        SaleFinancialWriter.FundingIdentity(PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET,
            ConfirmationType.CASHIER_CONFIRMED), customerId = customerId,
        couponAmountMinor = amountMinor, couponRemainderCategory = remainderCategory
    )

    companion object {
        fun decode(op: OperationEntity): CouponRedemptionCommand {
            val parts = op.intentPayload.split('|')
            require(parts.size == 9 && parts.take(2) == listOf("REDEEM", "1"))
            val v = parts.drop(2).map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            val c = CouponRedemptionCommand(OnlineSalePayloadCodec.decode(v[0]), v[1], v[2], v[3], v[4],
                v[5].toLong(), v[6].takeIf { it.isNotEmpty() }?.let(PaymentCategory::valueOf))
            c.validate()
            require(op.id == c.operationId.value && op.businessId == c.businessId && op.branchId == c.branchId &&
                op.actorUserId == c.actorUserId && op.deviceId == c.deviceId && op.occurredAt == c.occurredAt &&
                op.operationType == c.operationType && op.subjectType == c.subjectType && op.subjectId == c.subjectId &&
                op.transactionId == c.transactionId && op.commandSchemaVersion == 1 && op.intentPayload == c.intentPayload &&
                op.requestFingerprint == c.requestFingerprint)
            return c
        }
    }
}

data class CouponRedemptionResult(val transaction: TransactionEntity, val walletId: String,
    val amountMinor: Long, val authorizedUserName: String, val balanceAfterMinor: Long)

/** Durable intent for adding value to an already funded coupon wallet. */
data class CouponTopUpCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,
    val transactionIdVal: TransactionId,
    val walletId: String,
    val customerId: String,
    val customerName: String,
    val canonicalPhone: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val fundingCategory: PaymentCategory,
    override val deviceId: String,
    override val occurredAt: Long
) : OperationCommand {
    override val operationType = OperationType.RECORD_COUPON_PURCHASE
    override val subjectType = SubjectType.TRANSACTION
    override val subjectId = transactionIdVal.value
    override val transactionId = transactionIdVal.value
    override val commandSchemaVersion = 1
    override val intentPayload: String get() = CouponTopUpPayloadCodec.encode(this)
    override val requestFingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(intentPayload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun validate() {
        require(listOf(operationId.value, businessId, branchId, actorUserId, subjectId,
            walletId, customerId, customerName, canonicalPhone, currencyCode, deviceId).all { it.isNotBlank() })
        require(amountMinor > 0 && currencyDecimalPlaces in 0..3)
        require(customerName == customerName.trim())
        require(Regex("\\+[1-9][0-9]{6,14}").matches(canonicalPhone))
        require(fundingCategory == PaymentCategory.CASH || fundingCategory == PaymentCategory.ONLINE)
    }

    fun writerIntent() = SaleFinancialWriter.Intent(
        operationId = operationId.value,
        transactionId = subjectId,
        branchId = branchId,
        actorUserId = actorUserId,
        deviceId = deviceId,
        occurredAt = occurredAt,
        currencyCode = currencyCode,
        currencyDecimalPlaces = currencyDecimalPlaces,
        draft = DraftState(manualAmount = MoneyMinorUnits(amountMinor), manualDescription = "Coupon Top Up"),
        funding = SaleFinancialWriter.FundingIdentity(
            fundingCategory,
            if (fundingCategory == PaymentCategory.CASH) PaymentChannel.CASH_TENDER else PaymentChannel.MANUAL_QR,
            ConfirmationType.CASHIER_CONFIRMED
        ),
        transactionType = TransactionType.COUPON_PURCHASE,
        customerId = customerId
    )
}

data class CouponTopUpResult(val transaction: TransactionEntity, val walletId: String, val amountMinor: Long)

/** Authorization metadata uses the same durable coupon operation and append-only event history. */
data class CouponAuthorizedUserCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,
    val customerId: String,
    val walletId: String,
    val userId: String,
    val name: String,
    val eventType: AuthorizedUserEventType,
    val currencyCode: String,
    override val deviceId: String,
    override val occurredAt: Long
) : OperationCommand {
    override val operationType = OperationType.RECORD_COUPON_PURCHASE
    override val subjectType = SubjectType.COUPON_AUTHORIZED_USER
    override val subjectId = userId
    override val transactionId: String? = null
    override val commandSchemaVersion = 1
    override val intentPayload: String get() = (listOf("USERS", "1") +
        listOf(operationId.value, businessId, branchId, actorUserId, customerId, walletId,
            userId, name, eventType.name, currencyCode, deviceId, occurredAt.toString()).map {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        }).joinToString("|")
    override val requestFingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(intentPayload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun validate() {
        require(listOf(operationId.value, businessId, branchId, actorUserId, customerId, walletId,
            userId, name, currencyCode, deviceId).all { it.isNotBlank() })
        require(name == name.trim() && name.length <= 200)
    }

    companion object {
        fun decode(op: OperationEntity): CouponAuthorizedUserCommand {
            val parts = op.intentPayload.split('|')
            require(parts.size == 14 && parts.take(2) == listOf("USERS", "1"))
            val v = parts.drop(2).map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            val c = CouponAuthorizedUserCommand(OperationId(v[0]), v[1], v[2], v[3], v[4], v[5],
                v[6], v[7], AuthorizedUserEventType.valueOf(v[8]), v[9], v[10], v[11].toLong())
            c.validate()
            require(op.id == c.operationId.value && op.businessId == c.businessId && op.branchId == c.branchId &&
                op.actorUserId == c.actorUserId && op.deviceId == c.deviceId && op.occurredAt == c.occurredAt &&
                op.operationType == c.operationType && op.subjectType == c.subjectType && op.subjectId == c.subjectId &&
                op.transactionId == null && op.commandSchemaVersion == 1 && op.intentPayload == c.intentPayload &&
                op.requestFingerprint == c.requestFingerprint)
            return c
        }
    }
}

object CouponTopUpPayloadCodec {
    private const val VERSION = 1

    fun encode(c: CouponTopUpCommand): String = buildString {
        append("TOPUP|").append(VERSION)
        listOf(c.operationId.value, c.businessId, c.branchId, c.actorUserId, c.subjectId,
            c.walletId, c.customerId, c.customerName, c.canonicalPhone, c.currencyCode,
            c.deviceId, c.amountMinor.toString(), c.currencyDecimalPlaces.toString(),
            c.fundingCategory.name, c.occurredAt.toString()).forEach {
            append('|').append(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(it.toByteArray(Charsets.UTF_8)))
        }
    }

    fun decode(op: OperationEntity): CouponTopUpCommand {
        val parts = op.intentPayload.split('|')
        require(parts.size == 17 && parts[0] == "TOPUP" && parts[1] == VERSION.toString())
        val values = parts.drop(2).map {
            String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
        }
        val c = CouponTopUpCommand(
            OperationId(values[0]), values[1], values[2], values[3], TransactionId(values[4]),
            values[5], values[6], values[7], values[8], values[11].toLong(), values[9],
            values[12].toInt(), PaymentCategory.valueOf(values[13]), values[10], values[14].toLong()
        )
        c.validate()
        require(op.id == c.operationId.value && op.businessId == c.businessId &&
            op.branchId == c.branchId && op.actorUserId == c.actorUserId &&
            op.deviceId == c.deviceId && op.occurredAt == c.occurredAt &&
            op.requestFingerprint == c.requestFingerprint && op.intentPayload == c.intentPayload &&
            op.operationType == c.operationType && op.subjectType == c.subjectType &&
            op.subjectId == c.subjectId && op.transactionId == c.transactionId)
        return c
    }
}

/** A proven rejection with no committed purchase; the UI may safely edit and submit a new intent. */
class CouponSaleRejectedException(message: String) : IllegalArgumentException(message)

/** Length-prefixed fields avoid delimiter collisions in customer names and durable intent. */
object CouponSalePayloadCodec {
    fun encode(c: CouponSaleCommand): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            listOf(c.operationId.value, c.businessId, c.branchId, c.actorUserId, c.subjectId,
                c.customerId, c.customerName, c.canonicalPhone).forEach(out::writeUTF)
            out.writeBoolean(c.createCustomer)
            out.writeLong(c.amountMinor)
            out.writeUTF(c.currencyCode)
            out.writeInt(c.currencyDecimalPlaces)
            out.writeUTF(c.fundingCategory.name)
            out.writeInt(c.additionalUserNames.size)
            c.additionalUserNames.forEach(out::writeUTF)
            out.writeUTF(c.deviceId)
            out.writeLong(c.occurredAt)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(op: OperationEntity): CouponSaleCommand {
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(op.intentPayload)))
        val c = input.use {
            require(it.readInt() == 1 && op.commandSchemaVersion == 1)
            val ids = List(8) { _ -> input.readUTF() }
            val create = it.readBoolean()
            val amount = it.readLong()
            val currency = it.readUTF()
            val decimals = it.readInt()
            val funding = PaymentCategory.valueOf(it.readUTF())
            val count = it.readInt()
            require(count in 0..20)
            val names = List(count) { _ -> input.readUTF() }
            val command = CouponSaleCommand(OperationId(ids[0]), ids[1], ids[2], ids[3], TransactionId(ids[4]),
                ids[5], ids[6], ids[7], create, amount, currency, decimals, funding, names, it.readUTF(), it.readLong())
            require(it.available() == 0)
            command
        }
        c.validate()
        require(op.id == c.operationId.value && op.businessId == c.businessId && op.branchId == c.branchId &&
            op.actorUserId == c.actorUserId && op.subjectType == c.subjectType && op.subjectId == c.subjectId &&
            op.transactionId == c.transactionId && op.operationType == c.operationType && op.deviceId == c.deviceId &&
            op.occurredAt == c.occurredAt && op.requestFingerprint == c.requestFingerprint && op.intentPayload == c.intentPayload)
        return c
    }
}
