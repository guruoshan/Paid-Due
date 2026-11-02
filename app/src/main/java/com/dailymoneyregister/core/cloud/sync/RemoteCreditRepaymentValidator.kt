package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import org.json.JSONObject

internal object RemoteCreditRepaymentValidator {
    suspend fun validate(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        branchId: String,
        sessionBusinessId: String,
        actorUserId: String,
        repaymentJson: JSONObject?,
        evidence: JSONObject
    ): InboundProcessResult? {
        val payload = envelope.payloadJson
        
        if (repaymentJson == null) {
            return InboundProcessResult.Rejected("Missing credit repayment evidence")
        }
        if (
            repaymentJson.optString("operation_id") != envelope.operationId ||
            repaymentJson.optString("actor_user_id") != actorUserId ||
            repaymentJson.optString("customer_id").isBlank() ||
            repaymentJson.optLong("amount_minor", 0L) <= 0L ||
            repaymentJson.optLong("occurred_at", Long.MIN_VALUE) != payload.optLong("occurred_at", Long.MAX_VALUE)
        ) {
            return InboundProcessResult.Rejected("Credit repayment identity or amount is invalid")
        }
        val customerId = repaymentJson.optString("customer_id")
        val customer = database.customerDao().getCustomerById(customerId)
            ?: return InboundProcessResult.Rejected(
                "Credit repayment customer is missing from the synchronized business"
            )
        if (customer.id != customerId || customer.businessId != sessionBusinessId) {
            return InboundProcessResult.Rejected("Credit repayment customer identity belongs to another business")
        }
        val branch = database.businessBranchDao().getBranchById(branchId)
            ?: return InboundProcessResult.Rejected("Referenced branch is missing locally")
        if (branch.businessId != sessionBusinessId ||
            repaymentJson.optString("currency_code") != branch.currencyCode
        ) {
            return InboundProcessResult.Rejected("Credit repayment business, branch, or currency mismatch")
        }
        if (customer != null && customer.businessId != sessionBusinessId) {
            return InboundProcessResult.Rejected("Credit repayment customer belongs to another business")
        }
        val repaymentId = repaymentJson.optString("id")
        val repaymentAmount = repaymentJson.optLong("amount_minor", 0L)
        if (
            repaymentId.isBlank() ||
            payload.optString("subject_type") != SubjectType.REPAYMENT.name ||
            payload.optString("subject_id") != repaymentId
        ) {
            return InboundProcessResult.Rejected("Credit repayment subject does not match its repayment evidence")
        }
        val paymentCategory = runCatching {
            PaymentCategory.valueOf(repaymentJson.getString("payment_category"))
        }.getOrElse {
            return InboundProcessResult.Rejected("Credit repayment payment category is invalid")
        }
        val settlements = payload.optJSONArray("settlements") ?: org.json.JSONArray()
        if (settlements.length() != 1) {
            return InboundProcessResult.Rejected("Credit repayment requires exactly one funding settlement")
        }
        val settlement = settlements.getJSONObject(0)
        val paymentChannel = runCatching {
            PaymentChannel.valueOf(settlement.getString("channel"))
        }.getOrElse {
            return InboundProcessResult.Rejected("Credit repayment settlement channel is invalid")
        }
        val validPaymentPair = when (paymentCategory) {
            PaymentCategory.CASH -> paymentChannel == PaymentChannel.CASH_TENDER
            PaymentCategory.ONLINE -> paymentChannel == PaymentChannel.MANUAL_QR ||
                paymentChannel == PaymentChannel.SOFTPOS
            PaymentCategory.COUPON -> paymentChannel == PaymentChannel.COUPON_WALLET
            else -> false
        }
        if (
            !validPaymentPair ||
            settlement.optString("operation_id") != envelope.operationId ||
            settlement.optString("subject_type") != SubjectType.REPAYMENT.name ||
            settlement.optString("subject_id") != repaymentId ||
            settlement.optString("transaction_id").isNotBlank() ||
            settlement.optString("currency_code") != repaymentJson.optString("currency_code") ||
            settlement.optString("entry_purpose") != EntryPurpose.CREDIT_REPAYMENT_COLLECTION.name ||
            settlement.optString("category") != paymentCategory.name ||
            settlement.optString("actor_user_id") != actorUserId ||
            settlement.optString("confirmation_type") != ConfirmationType.CASHIER_CONFIRMED.name ||
            settlement.optLong("amount_signed_minor", 0L) != repaymentAmount ||
            settlement.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
        ) {
            return InboundProcessResult.Rejected("Credit repayment settlement does not match the repayment record")
        }
        val ledgerEvents = evidence.optJSONArray("credit_ledger_events") ?: org.json.JSONArray()
        if (ledgerEvents.length() != 1) {
            return InboundProcessResult.Rejected("Credit repayment requires exactly one matching Credit ledger event")
        }
        val ledgerEvent = ledgerEvents.getJSONObject(0)
        val expectedNegativeAmount = try {
            Math.negateExact(repaymentAmount)
        } catch (_: ArithmeticException) {
            return InboundProcessResult.Rejected("Credit repayment amount overflows the supported money range")
        }
        if (
            ledgerEvent.optString("operation_id") != envelope.operationId ||
            ledgerEvent.optString("type") != "REPAYMENT" ||
            ledgerEvent.optString("repayment_id") != repaymentId ||
            ledgerEvent.optString("transaction_id").isNotBlank() ||
            ledgerEvent.optString("customer_id") != customerId ||
            ledgerEvent.optString("currency_code") != repaymentJson.optString("currency_code") ||
            ledgerEvent.optLong("amount_signed_minor", 0L) != expectedNegativeAmount ||
            ledgerEvent.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
        ) {
            return InboundProcessResult.Rejected("Credit repayment is missing its exact negative ledger event")
        }
        val allocations = evidence.optJSONArray("credit_repayment_allocations") ?: org.json.JSONArray()
        if (allocations.length() == 0) {
            return InboundProcessResult.Rejected("Credit repayment is missing FIFO allocation evidence")
        }
        var allocatedTotal = 0L
        val allocationIds = mutableSetOf<String>()
        val allocatedTransactions = mutableSetOf<String>()
        for (i in 0 until allocations.length()) {
            val allocation = allocations.getJSONObject(i)
            val allocationId = allocation.optString("id")
            val originalTransactionId = allocation.optString("original_transaction_id")
            val allocatedAmount = allocation.optLong("allocated_amount_minor", 0L)
            if (
                allocation.optString("repayment_id") != repaymentId ||
                allocationId.isBlank() ||
                originalTransactionId.isBlank() ||
                allocatedAmount <= 0L ||
                allocation.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE) ||
                !allocationIds.add(allocationId) ||
                !allocatedTransactions.add(originalTransactionId)
            ) {
                return InboundProcessResult.Rejected("Credit repayment FIFO allocation identity or amount is invalid")
            }
            allocatedTotal = Math.addExact(allocatedTotal, allocatedAmount)
        }
        if (allocatedTotal != repaymentAmount) {
            return InboundProcessResult.Rejected("Credit repayment FIFO allocations do not equal the repayment amount")
        }
        val couponEvents = evidence.optJSONArray("coupon_ledger_events") ?: org.json.JSONArray()
        if (paymentCategory == PaymentCategory.COUPON) {
            val wallets = evidence.optJSONArray("coupon_wallets") ?: org.json.JSONArray()
            if (wallets.length() != 1 || couponEvents.length() != 1) {
                return InboundProcessResult.Rejected(
                    "Coupon-funded Credit repayment requires one wallet and one redemption event"
                )
            }
            val wallet = wallets.getJSONObject(0)
            val couponEvent = couponEvents.getJSONObject(0)
            if (
                wallet.optString("id").isBlank() ||
                wallet.optString("customer_id") != customerId ||
                wallet.optString("currency_code") != repaymentJson.optString("currency_code") ||
                !wallet.has("is_active") || wallet.isNull("is_active") || !wallet.optBoolean("is_active", false) ||
                couponEvent.optString("id").isBlank() ||
                couponEvent.optString("operation_id") != envelope.operationId ||
                couponEvent.optString("wallet_id") != wallet.optString("id") ||
                couponEvent.optString("type") != "REDEMPTION" ||
                couponEvent.optLong("amount_signed_minor", 0L) != expectedNegativeAmount ||
                couponEvent.optString("currency_code") != repaymentJson.optString("currency_code") ||
                couponEvent.optString("transaction_id").isNotBlank() ||
                couponEvent.optString("authorized_user_id").isBlank() ||
                couponEvent.optString("authorized_user_name_snapshot").isBlank() ||
                couponEvent.optLong("occurred_at", Long.MIN_VALUE) != repaymentJson.optLong("occurred_at", Long.MAX_VALUE)
            ) {
                return InboundProcessResult.Rejected(
                    "Coupon-funded Credit repayment is missing its exact wallet redemption evidence"
                )
            }
        } else if (couponEvents.length() != 0) {
            return InboundProcessResult.Rejected(
                "Cash/Online Credit repayment cannot carry Coupon redemption evidence"
            )
        }
        return null
    }
}
