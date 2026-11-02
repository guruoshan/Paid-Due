package com.dailymoneyregister.feature.register.data

/** Stable deterministic identifiers used by persisted Refund evidence. */
internal object RefundIdFactory {
    fun creditReturnB2SettlementId(
        operationId: String,
        kind: String,
        index: Int
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "credit-return-b2:$operationId:settlement:$kind:$index"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    fun creditReturnB2CouponEventId(
        operationId: String,
        index: Int
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "credit-return-b2:$operationId:coupon:$index"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    fun creditRefundAdjustmentId(operationId: String): String =
        java.util.UUID.nameUUIDFromBytes(
            "credit-return:$operationId".toByteArray(Charsets.UTF_8)
        ).toString()

    fun couponRefundEvidenceId(operationId: String): String =
        java.util.UUID.nameUUIDFromBytes(
            "refund-to-coupon:$operationId".toByteArray(Charsets.UTF_8)
        ).toString()

    fun couponRefundWalletId(operationId: String): String =
        java.util.UUID.nameUUIDFromBytes(
            "refund-to-coupon-wallet:$operationId".toByteArray(Charsets.UTF_8)
        ).toString()
}
