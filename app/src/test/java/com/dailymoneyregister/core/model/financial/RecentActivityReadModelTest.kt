package com.dailymoneyregister.core.model.financial

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentActivityReadModelTest {
    @Test
    fun cashSaleProducesExactlyOneSaleActivity() {
        val record = saleRecord("cash", PaymentCategory.CASH, PaymentChannel.CASH_TENDER)

        val activity = resolve(record).single()

        assertEquals(RecentActivityType.MERCHANDISE_SALE, activity.type)
        assertEquals(10_000L, activity.signedEffect.amount)
        assertEquals(PaymentCategory.CASH, activity.fundingBreakdown.single().category)
        assertTrue(activity.isRevenue)
    }

    @Test
    fun onlineSaleProducesExactlyOneSaleActivity() {
        val activity = resolve(
            saleRecord("online", PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR)
        ).single()

        assertEquals(RecentActivityType.MERCHANDISE_SALE, activity.type)
        assertEquals(PaymentCategory.ONLINE, activity.fundingBreakdown.single().category)
    }

    @Test
    fun cashRefundIsSeparateSignedActivityWithOriginalRelationship() {
        val record = saleWithRefund("cash-refund", PaymentCategory.CASH, PaymentChannel.CASH_TENDER)

        val activities = resolve(record)
        val refund = activities.single { it.type == RecentActivityType.REFUND }

        assertEquals(2, activities.size)
        assertEquals(-2_000L, refund.signedEffect.amount)
        assertEquals(PaymentCategory.CASH, refund.fundingBreakdown.single().category)
        assertEquals(record.transaction.id, refund.originalTransactionId)
    }

    @Test
    fun onlineRefundUsesOnlineMethod() {
        val refund = resolve(
            saleWithRefund("online-refund", PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR)
        ).single { it.type == RecentActivityType.REFUND }

        assertEquals(-2_000L, refund.signedEffect.amount)
        assertEquals(PaymentCategory.ONLINE, refund.fundingBreakdown.single().category)
        assertEquals(PaymentChannel.MANUAL_QR, refund.fundingBreakdown.single().channel)
    }

    @Test
    fun twoRefundsRemainDistinctActivities() {
        val base = saleRecord("two-refunds", PaymentCategory.CASH, PaymentChannel.CASH_TENDER)
        val first = refund("refund-a", base.transaction.id, "refund-op-a", 2_000L, 2_000L)
        val second = refund("refund-b", base.transaction.id, "refund-op-b", 1_000L, 3_000L)
        val record = base.copy(
            refundEvents = listOf(first, second),
            settlements = base.settlements + listOf(
                refundSettlement(base.transaction.id, first, PaymentCategory.CASH, PaymentChannel.CASH_TENDER),
                refundSettlement(base.transaction.id, second, PaymentCategory.CASH, PaymentChannel.CASH_TENDER)
            )
        )

        val refunds = resolve(record).filter { it.type == RecentActivityType.REFUND }

        assertEquals(2, refunds.size)
        assertEquals(setOf(-2_000L, -1_000L), refunds.map { it.signedEffect.amount }.toSet())
        assertNotEquals(refunds[0].activityId, refunds[1].activityId)
    }

    @Test
    fun refundToCouponRemainsRefundAndIsNotRevenueOrTopUp() {
        val record = saleWithRefund("coupon-refund", PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET)

        val activity = resolve(record).single { it.type == RecentActivityType.REFUND_TO_COUPON }

        assertEquals(-2_000L, activity.signedEffect.amount)
        assertEquals(PaymentCategory.COUPON, activity.fundingBreakdown.single().category)
        assertFalse(activity.isRevenue)
        assertTrue(resolve(record).none { it.type == RecentActivityType.COUPON_TOP_UP })
        assertTrue(resolve(record).none { it.type == RecentActivityType.COUPON_SELL })
    }

    @Test
    fun voidProducesSeparateActualReversalInsteadOfZero() {
        val base = saleRecord("void", PaymentCategory.CASH, PaymentChannel.CASH_TENDER)
        val event = VoidEventEntity("void-event", base.transaction.id, "void-op", actorUserId = "user", occurredAt = 2_000L)
        val reversal = settlement(
            base.transaction.id,
            "void-op",
            EntryPurpose.VOID_REVERSAL,
            PaymentCategory.CASH,
            PaymentChannel.CASH_TENDER,
            -10_000L,
            2_000L
        )

        val activities = resolve(base.copy(voidEvents = listOf(event), settlements = base.settlements + reversal))
        val void = activities.single { it.type == RecentActivityType.VOID }

        assertEquals(2, activities.size)
        assertEquals(-10_000L, void.signedEffect.amount)
        assertNotEquals(0L, void.signedEffect.amount)
    }

    @Test
    fun couponSellIsFundingActivityNotMerchandiseRevenue() {
        val activity = resolve(couponRecord("sell", description = "Coupon purchase")).single()

        assertEquals(RecentActivityType.COUPON_SELL, activity.type)
        assertFalse(activity.isRevenue)
    }

    @Test
    fun couponTopUpUsesCompatiblePersistedDescriptionClassification() {
        val activity = resolve(couponRecord("topup", description = "Coupon Top Up")).single()

        assertEquals(RecentActivityType.COUPON_TOP_UP, activity.type)
        assertFalse(activity.isRevenue)
    }

    @Test
    fun couponOnlyMerchandiseSaleProducesOneRevenueActivity() {
        val activities = resolve(
            saleRecord("coupon-only", PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET)
        )

        assertEquals(1, activities.size)
        assertEquals(RecentActivityType.MERCHANDISE_SALE, activities.single().type)
        assertEquals(1, activities.count { it.isRevenue })
    }

    @Test
    fun couponCashSplitProducesOneMerchandiseRevenueActivity() {
        assertOneSplitSale(PaymentCategory.CASH, PaymentChannel.CASH_TENDER)
    }

    @Test
    fun couponOnlineSplitProducesOneMerchandiseRevenueActivity() {
        assertOneSplitSale(PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR)
    }

    @Test
    fun couponCreditSplitProducesOneMerchandiseRevenueActivity() {
        assertOneSplitSale(PaymentCategory.CREDIT, PaymentChannel.CREDIT_ACCOUNT)
    }

    @Test
    fun topUpDuringSaleProducesSeparateTopUpAndExactlyOneMerchandiseSale() {
        val activities = RecentActivityReadModelResolver.resolve(
            listOf(
                couponRecord("during-sale-topup", "Coupon Top Up", occurredAt = 1_000L),
                saleRecord("during-sale-merchandise", PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET, occurredAt = 2_000L)
            ),
            0L,
            10_000L
        )

        assertEquals(2, activities.size)
        assertEquals(1, activities.count { it.type == RecentActivityType.COUPON_TOP_UP })
        assertEquals(1, activities.count { it.type == RecentActivityType.MERCHANDISE_SALE })
        assertEquals(1, activities.count { it.isRevenue })
    }

    @Test
    fun orderingUsesOccurredAtThenStableActivityId() {
        val older = saleRecord("older", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, occurredAt = 1_000L)
        val sameTimeA = saleRecord("a", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, occurredAt = 2_000L)
        val sameTimeB = saleRecord("b", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, occurredAt = 2_000L)

        val activities = RecentActivityReadModelResolver.resolve(listOf(older, sameTimeA, sameTimeB), 0L, 10_000L)

        assertEquals(listOf("transaction:b", "transaction:a", "transaction:older"), activities.map { it.activityId })
    }

    @Test
    fun reconstructionProducesSameIdsWithoutDuplicates() {
        val record = saleWithRefund("stable", PaymentCategory.CASH, PaymentChannel.CASH_TENDER)

        val first = resolve(record)
        val second = resolve(record)

        assertEquals(first, second)
        assertEquals(first.size, first.map { it.activityId }.distinct().size)
    }

    @Test
    fun existingCustomerReferenceIsReadWithoutCreatingState() {
        val customer = CustomerEntity("customer", "business", "+96891234567", "Existing Customer", 1L, 1L)
        val record = saleRecord("customer-sale", PaymentCategory.CASH, PaymentChannel.CASH_TENDER)
            .copy(transaction = transaction("customer-sale", customerId = customer.id), customer = customer)

        val activity = resolve(record).single()

        assertEquals(customer.id, activity.customerId)
        assertEquals(customer.name, activity.customerName)
    }

    @Test
    fun periodFilteringPreservesDayBoundaryBehavior() {
        val before = saleRecord("before", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, occurredAt = 999L)
        val atStart = saleRecord("start", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, occurredAt = 1_000L)
        val atEnd = saleRecord("end", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, occurredAt = 2_000L)

        val activities = RecentActivityReadModelResolver.resolve(listOf(before, atStart, atEnd), 1_000L, 2_000L)

        assertEquals(listOf("transaction:start"), activities.map { it.activityId })
    }

    @Test
    fun correctionRetainsExistingRecentLifecycleVisibility() {
        val base = saleRecord("correction", PaymentCategory.CASH, PaymentChannel.CASH_TENDER, occurredAt = -500L)
        val correction = AmountCorrectionEventEntity(
            id = "correction-event",
            transactionId = base.transaction.id,
            operationId = "correction-op",
            previousAmountMinor = 10_000L,
            correctedAmountMinor = 12_000L,
            actorUserId = "user",
            occurredAt = 2_000L
        )

        val activity = resolve(base.copy(amountCorrections = listOf(correction))).single()

        assertEquals(RecentActivityType.AMOUNT_CORRECTION, activity.type)
        assertEquals(2_000L, activity.signedEffect.amount)
    }

    private fun assertOneSplitSale(remainder: PaymentCategory, channel: PaymentChannel) {
        val transaction = transaction("split-${remainder.name.lowercase()}")
        val settlements = listOf(
            settlement(transaction.id, "sale-op", EntryPurpose.SALE_FUNDING,
                PaymentCategory.COUPON, PaymentChannel.COUPON_WALLET, 4_000L, 1_000L),
            settlement(transaction.id, "sale-op", EntryPurpose.SALE_FUNDING,
                remainder, channel, 6_000L, 1_000L)
        )

        val activities = resolve(TransactionFinancialRecord(transaction, settlements = settlements))

        assertEquals(1, activities.size)
        assertEquals(1, activities.count { it.isRevenue })
        assertEquals(setOf(PaymentCategory.COUPON, remainder),
            activities.single().fundingBreakdown.map { it.category }.toSet())
    }

    private fun saleWithRefund(
        id: String,
        refundCategory: PaymentCategory,
        refundChannel: PaymentChannel
    ): TransactionFinancialRecord {
        val base = saleRecord(id, PaymentCategory.CASH, PaymentChannel.CASH_TENDER)
        val refund = refund("$id-refund", id, "$id-refund-op", 2_000L, 2_000L)
        return base.copy(
            refundEvents = listOf(refund),
            settlements = base.settlements + refundSettlement(id, refund, refundCategory, refundChannel)
        )
    }

    private fun saleRecord(
        id: String,
        category: PaymentCategory,
        channel: PaymentChannel,
        occurredAt: Long = 1_000L
    ): TransactionFinancialRecord {
        val transaction = transaction(id, occurredAt = occurredAt)
        return TransactionFinancialRecord(
            transaction = transaction,
            settlements = listOf(
                settlement(transaction.id, "$id-sale-op", EntryPurpose.SALE_FUNDING,
                    category, channel, transaction.grandTotalMinor, occurredAt)
            )
        )
    }

    private fun couponRecord(
        id: String,
        description: String,
        occurredAt: Long = 1_000L
    ): TransactionFinancialRecord {
        val transaction = transaction(id, occurredAt = occurredAt).copy(
            type = TransactionType.COUPON_PURCHASE,
            description = description
        )
        return TransactionFinancialRecord(
            transaction = transaction,
            settlements = listOf(
                settlement(transaction.id, "$id-coupon-op", EntryPurpose.COUPON_PURCHASE_FUNDING,
                    PaymentCategory.CASH, PaymentChannel.CASH_TENDER, transaction.grandTotalMinor, occurredAt)
            )
        )
    }

    private fun refund(
        id: String,
        transactionId: String,
        operationId: String,
        amount: Long,
        occurredAt: Long
    ) = RefundEventEntity(id, transactionId, operationId, amount, actorUserId = "user", occurredAt = occurredAt)

    private fun refundSettlement(
        transactionId: String,
        refund: RefundEventEntity,
        category: PaymentCategory,
        channel: PaymentChannel
    ) = settlement(
        transactionId,
        refund.operationId,
        EntryPurpose.REFUND_OUTFLOW,
        category,
        channel,
        -refund.refundAmountMinor,
        refund.occurredAt
    )

    private fun settlement(
        transactionId: String,
        operationId: String,
        purpose: EntryPurpose,
        category: PaymentCategory,
        channel: PaymentChannel,
        amount: Long,
        occurredAt: Long
    ) = SettlementEntryEntity(
        id = "$operationId-${purpose.name}-${category.name}",
        operationId = operationId,
        subjectType = SubjectType.TRANSACTION,
        subjectId = transactionId,
        transactionId = transactionId,
        entryPurpose = purpose,
        category = category,
        channel = channel,
        amountSignedMinor = amount,
        currencyCode = "OMR",
        confirmationType = ConfirmationType.CASHIER_CONFIRMED,
        occurredAt = occurredAt,
        actorUserId = "user"
    )

    private fun transaction(
        id: String,
        customerId: String? = null,
        occurredAt: Long = 1_000L
    ) = TransactionEntity(
        id = id,
        branchId = "branch",
        type = TransactionType.SALE,
        customerId = customerId,
        subtotalMinor = 10_000L,
        taxRateBps = 0,
        taxableBaseMinor = 10_000L,
        taxAmountMinor = 0L,
        grandTotalMinor = 10_000L,
        currencyCode = "OMR",
        currencyDecimalPlaces = 3,
        createdByUserId = "user",
        deviceId = "device",
        createdAt = occurredAt,
        displayRef = "REF-$id"
    )

    private fun resolve(record: TransactionFinancialRecord): List<RecentFinancialActivity> =
        RecentActivityReadModelResolver.resolve(record, 0L, 10_000L)
}
