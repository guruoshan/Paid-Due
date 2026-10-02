package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.*
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.ids.*
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import org.junit.Test
import org.junit.Assert.*
import java.lang.reflect.Proxy

class CouponSaleCommandTest {
    @Test fun couponTopUpDurableIntentRoundTripsAndBindsIdentity() {
        val c = CouponTopUpCommand(
            OperationId("topup-op"), "biz", "branch", "actor", TransactionId("topup-tx"),
            "wallet", "customer", "Customer", "+96891234567", 2500, "OMR", 3,
            PaymentCategory.ONLINE, "device", 1000
        )
        c.validate()
        val op = OperationEntity(
            id = c.operationId.value, businessId = c.businessId, branchId = c.branchId,
            operationType = c.operationType, subjectType = c.subjectType, subjectId = c.subjectId,
            transactionId = c.transactionId, actorUserId = c.actorUserId, deviceId = c.deviceId,
            occurredAt = c.occurredAt, requestFingerprint = c.requestFingerprint,
            commandSchemaVersion = c.commandSchemaVersion, intentPayload = c.intentPayload,
            status = OperationStatus.PENDING
        )
        assertEquals(c, CouponTopUpPayloadCodec.decode(op))
        assertNotEquals(c.requestFingerprint, c.copy(amountMinor = 2600).requestFingerprint)
        assertTrue(runCatching { CouponTopUpPayloadCodec.decode(op.copy(actorUserId = "other")) }.isFailure)
    }

    private fun command() = CouponSaleCommand(OperationId("op"), "biz", "branch", "actor", TransactionId("tx"),
        "customer", "Customer | : العربية", "+96891234567", true, 5000, "OMR", 3,
        PaymentCategory.CASH, listOf("A;B\nC"), "device", 1000)
    private fun operation(c: CouponSaleCommand) = OperationEntity(id = c.operationId.value, businessId = c.businessId,
        branchId = c.branchId, operationType = c.operationType, subjectType = c.subjectType, subjectId = c.subjectId,
        transactionId = c.transactionId, actorUserId = c.actorUserId, deviceId = c.deviceId, occurredAt = c.occurredAt,
        requestFingerprint = c.requestFingerprint, commandSchemaVersion = 1, intentPayload = c.intentPayload, status = OperationStatus.PENDING)
    private val writer = SaleFinancialWriter(Proxy.newProxyInstance(TransactionDao::class.java.classLoader,
        arrayOf(TransactionDao::class.java)) { _, _, _ -> error("Pure evidence calculation must not access Room") } as TransactionDao)

    @Test fun durableIntentPreservesNamesAndAllIdentities() {
        val c = command()
        assertEquals(c, CouponSalePayloadCodec.decode(operation(c)))
        assertNotEquals(c.requestFingerprint, c.copy(amountMinor = 6000).requestFingerprint)
        assertNotEquals(c.requestFingerprint, c.copy(customerName = "Another").requestFingerprint)
        assertTrue(runCatching { CouponSalePayloadCodec.decode(operation(c).copy(actorUserId = "other")) }.isFailure)
        assertTrue(runCatching { CouponSalePayloadCodec.decode(operation(c).copy(intentPayload = "bad")) }.isFailure)
    }
    @Test fun purchaseProjectionCountsFundingOnceAndPreservesCurrencyAndCustomer() {
        for (category in listOf(PaymentCategory.CASH, PaymentCategory.ONLINE)) {
            val c = command().copy(fundingCategory = category)
            val evidence = writer.expected(c.writerIntent())
            assertEquals(TransactionType.COUPON_PURCHASE, evidence.transaction.type)
            assertEquals(c.customerId, evidence.transaction.customerId)
            assertEquals(EntryPurpose.COUPON_PURCHASE_FUNDING, evidence.settlement.entryPurpose)
            val record = TransactionFinancialRecord(evidence.transaction, settlements = listOf(evidence.settlement))
            val view = FinancialReadModelResolver.resolve(record, 0, 2000)
            assertEquals(5000L, view.periodFinancialEffect.amount)
            assertEquals(category, view.originalPaymentCategory)
            assertEquals(0L, FinancialReadModelResolver.resolve(record, 2000, 3000).periodFinancialEffect.amount)
        }
    }
    @Test fun ordinarySaleStillUsesSaleFundingAndOriginalReference() {
        val intent = command().writerIntent().copy(transactionType = TransactionType.SALE, customerId = null)
        val evidence = writer.expected(intent)
        assertEquals(EntryPurpose.SALE_FUNDING, evidence.settlement.entryPurpose)
        assertTrue(evidence.transaction.displayRef.startsWith("SAL-"))
        assertNull(evidence.transaction.customerId)
        assertEquals(5000L, FinancialReadModelResolver.resolve(TransactionFinancialRecord(evidence.transaction,
            settlements = listOf(evidence.settlement))).periodFinancialEffect.amount)
    }
    @Test fun couponPurchaseRejectsTaxChargesMissingCustomerAndUnsupportedFunding() {
        val intent = command().writerIntent()
        assertTrue(runCatching { writer.expected(intent.copy(customerId = null)) }.isFailure)
        assertTrue(runCatching { writer.expected(intent.copy(draft = intent.draft.copy(isTaxEnabled = true, taxRateBps = 500))) }.isFailure)
        assertTrue(runCatching { writer.expected(intent.copy(draft = intent.draft.copy(txShipping = MoneyMinorUnits(1)))) }.isFailure)
        assertTrue(runCatching { command().copy(fundingCategory = PaymentCategory.CREDIT).validate() }.isFailure)
        assertTrue(runCatching { command().copy(amountMinor = 0).validate() }.isFailure)
    }
}
