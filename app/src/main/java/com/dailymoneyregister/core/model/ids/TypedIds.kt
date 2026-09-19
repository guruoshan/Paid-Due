package com.dailymoneyregister.core.model.ids

import java.security.SecureRandom
import java.util.UUID

/**
 * Universal ID generation and typed wrappers.
 * All IDs are globally unique, offline-safe, time-sortable strings based on UUID v7 or secure time-ordered UUIDs.
 */
object IdGenerator {
    private val secureRandom = SecureRandom()

    /**
     * Generates a time-ordered UUID v7 string.
     * Unix timestamp (48 bits) + version (4 bits) + random (12 bits) + variant (2 bits) + random (62 bits).
     */
    fun newId(): String {
        val timestamp = System.currentTimeMillis()
        val randomBytes = ByteArray(10)
        secureRandom.nextBytes(randomBytes)

        val mostSigBits = (timestamp shl 16) or
                (0x7000L) or // Version 7
                ((randomBytes[0].toLong() and 0x0F) shl 8) or
                (randomBytes[1].toLong() and 0xFF)

        var leastSigBits = 0L
        for (i in 2..9) {
            leastSigBits = (leastSigBits shl 8) or (randomBytes[i].toLong() and 0xFF)
        }
        // Variant 10xx (RFC 4122)
        leastSigBits = (leastSigBits and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE

        return UUID(mostSigBits, leastSigBits).toString()
    }
}

@JvmInline
value class BusinessId(val value: String) {
    companion object { fun generate() = BusinessId(IdGenerator.newId()) }
}

@JvmInline
value class BranchId(val value: String) {
    companion object { fun generate() = BranchId(IdGenerator.newId()) }
}

@JvmInline
value class UserId(val value: String) {
    companion object { fun generate() = UserId(IdGenerator.newId()) }
}

@JvmInline
value class TransactionId(val value: String) {
    companion object { fun generate() = TransactionId(IdGenerator.newId()) }
}

@JvmInline
value class OperationId(val value: String) {
    companion object { fun generate() = OperationId(IdGenerator.newId()) }
}

@JvmInline
value class SettlementId(val value: String) {
    companion object { fun generate() = SettlementId(IdGenerator.newId()) }
}

@JvmInline
value class CustomerId(val value: String) {
    companion object { fun generate() = CustomerId(IdGenerator.newId()) }
}

@JvmInline
value class ItemId(val value: String) {
    companion object { fun generate() = ItemId(IdGenerator.newId()) }
}

@JvmInline
value class ProductId(val value: String) {
    companion object { fun generate() = ProductId(IdGenerator.newId()) }
}

@JvmInline
value class CouponWalletId(val value: String) {
    companion object { fun generate() = CouponWalletId(IdGenerator.newId()) }
}

@JvmInline
value class CouponAuthorizedUserId(val value: String) {
    companion object { fun generate() = CouponAuthorizedUserId(IdGenerator.newId()) }
}

@JvmInline
value class CreditRepaymentId(val value: String) {
    companion object { fun generate() = CreditRepaymentId(IdGenerator.newId()) }
}

@JvmInline
value class CashShiftId(val value: String) {
    companion object { fun generate() = CashShiftId(IdGenerator.newId()) }
}

@JvmInline
value class MediaId(val value: String) {
    companion object { fun generate() = MediaId(IdGenerator.newId()) }
}

@JvmInline
value class DocumentId(val value: String) {
    companion object { fun generate() = DocumentId(IdGenerator.newId()) }
}

@JvmInline
value class QrProfileId(val value: String) {
    companion object { fun generate() = QrProfileId(IdGenerator.newId()) }
}
