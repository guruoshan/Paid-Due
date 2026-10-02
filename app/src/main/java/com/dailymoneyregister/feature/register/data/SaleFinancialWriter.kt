package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.feature.register.domain.DraftState

class SaleFinancialWriter(private val transactionDao: TransactionDao) {
    data class FundingIdentity(
        val category: PaymentCategory,
        val channel: PaymentChannel,
        val confirmationType: ConfirmationType
    )

    data class Intent(
        val operationId: String,
        val transactionId: String,
        val branchId: String,
        val actorUserId: String,
        val deviceId: String,
        val occurredAt: Long,
        val currencyCode: String,
        val currencyDecimalPlaces: Int,
        val draft: DraftState,
        val funding: FundingIdentity,
        val transactionType: TransactionType = TransactionType.SALE,
        val customerId: String? = null,
        val couponAmountMinor: Long? = null,
        val couponRemainderCategory: PaymentCategory? = null
    )

    data class ExpectedEvidence(
        val transaction: TransactionEntity,
        val items: List<TransactionItemEntity>,
        val settlement: SettlementEntryEntity,
        val additionalSettlements: List<SettlementEntryEntity> = emptyList()
    )

    fun validateFunding(identity: FundingIdentity) {
        validateFundingIdentity(identity)
    }

    companion object {
        fun validateFundingIdentity(identity: FundingIdentity) {
        require(identity.confirmationType == ConfirmationType.CASHIER_CONFIRMED) {
            "Sale funding must be cashier confirmed."
        }
        require(
            (identity.category == PaymentCategory.CASH && identity.channel == PaymentChannel.CASH_TENDER) ||
                (identity.category == PaymentCategory.ONLINE && identity.channel == PaymentChannel.MANUAL_QR) ||
                (identity.category == PaymentCategory.COUPON && identity.channel == PaymentChannel.COUPON_WALLET) ||
                (identity.category == PaymentCategory.CREDIT && identity.channel == PaymentChannel.CREDIT_ACCOUNT)
        ) { "Unsupported sale funding identity." }
        }
    }

    fun expected(intent: Intent): ExpectedEvidence {
        validateFunding(intent.funding)
        val draft = intent.draft
        if (intent.funding.category == PaymentCategory.CREDIT) {
            require(intent.transactionType == TransactionType.SALE && !intent.customerId.isNullOrBlank()) {
                "Credit sale requires an authoritative customer."
            }
        }
        require(draft.grandTotal.isPositive)
        if (intent.funding.category == PaymentCategory.COUPON) {
            require(intent.transactionType == TransactionType.SALE && !intent.customerId.isNullOrBlank())
            require(intent.couponAmountMinor != null && intent.couponAmountMinor > 0 && intent.couponAmountMinor <= draft.grandTotal.amount)
            require(if (intent.couponAmountMinor == draft.grandTotal.amount) intent.couponRemainderCategory == null
                else intent.couponRemainderCategory in listOf(PaymentCategory.CASH, PaymentCategory.ONLINE, PaymentCategory.CREDIT))
        } else require(intent.couponAmountMinor == null && intent.couponRemainderCategory == null)
        if (intent.transactionType == TransactionType.COUPON_PURCHASE) {
            require(!intent.customerId.isNullOrBlank()) { "Coupon purchaser is required" }
            require(draft.manualAmount.isPositive && draft.items.isEmpty() &&
                !draft.isTaxEnabled && draft.taxRateBps == 0 &&
                draft.txShipping.isZero && draft.txPacking.isZero) {
                "Coupon purchase requires a positive amount without items, tax or charges"
            }
        }
        val evidence = ExpectedEvidence(
            transaction = transaction(intent),
            items = draft.items.map { item ->
                TransactionItemEntity(
                    id = item.id,
                    transactionId = intent.transactionId,
                    productId = null,
                    productNameSnapshot = item.name,
                    descriptionSnapshot = item.description,
                    quantity = item.quantity,
                    unitPriceMinor = item.unitPrice.amount,
                    lineTotalMinor = item.lineTotal.amount,
                    barcodeSnapshot = item.barcode,
                    itemPackingChargeMinor = item.itemPackingCharge.amount,
                    itemShippingChargeMinor = item.itemShippingCharge.amount
                )
            },
            settlement = SettlementEntryEntity(
                id = "",
                operationId = intent.operationId,
                subjectType = SubjectType.TRANSACTION,
                subjectId = intent.transactionId,
                transactionId = intent.transactionId,
                entryPurpose = if (intent.transactionType == TransactionType.COUPON_PURCHASE)
                    EntryPurpose.COUPON_PURCHASE_FUNDING else EntryPurpose.SALE_FUNDING,
                category = intent.funding.category,
                channel = intent.funding.channel,
                amountSignedMinor = intent.couponAmountMinor ?: draft.grandTotal.amount,
                currencyCode = intent.currencyCode,
                confirmationType = intent.funding.confirmationType,
                occurredAt = intent.occurredAt,
                actorUserId = intent.actorUserId
            )
        )
        val remainder = intent.couponRemainderCategory ?: return evidence
        return evidence.copy(additionalSettlements = listOf(evidence.settlement.copy(
            category = remainder,
            channel = when (remainder) {
                PaymentCategory.CASH -> PaymentChannel.CASH_TENDER
                PaymentCategory.ONLINE -> PaymentChannel.MANUAL_QR
                PaymentCategory.CREDIT -> PaymentChannel.CREDIT_ACCOUNT
                else -> error("Invalid coupon remainder")
            }, amountSignedMinor = Math.subtractExact(draft.grandTotal.amount, requireNotNull(intent.couponAmountMinor))
        )))
    }

    suspend fun materialize(intent: Intent): Pair<TransactionEntity, Pair<String, String>> {
        val expected = expected(intent)
        transactionDao.insertTransaction(expected.transaction)
        if (expected.items.isNotEmpty()) transactionDao.insertTransactionItems(expected.items)
        transactionDao.insertSettlement(expected.settlement.copy(id = IdGenerator.newId()))
        expected.additionalSettlements.forEach { transactionDao.insertSettlement(it.copy(id = IdGenerator.newId())) }
        return expected.transaction to ("TRANSACTION" to intent.transactionId)
    }

    fun mismatch(
        expected: ExpectedEvidence,
        transaction: TransactionEntity?,
        items: List<TransactionItemEntity>,
        settlements: List<SettlementEntryEntity>
    ): String? = when {
        transaction != expected.transaction -> "Sale transaction mismatch"
        items.sortedBy { it.id } != expected.items.sortedBy { it.id } -> "Sale items mismatch"
        settlements.size != 1 + expected.additionalSettlements.size -> "Sale settlement count mismatch"
        settlements.any { it.id.isBlank() } || settlements.map { it.copy(id = "") }.toSet() !=
            (listOf(expected.settlement) + expected.additionalSettlements).toSet() -> "Sale settlement mismatch"
        else -> null
    }

    suspend fun loadVerified(intent: Intent): TransactionEntity {
        val expected = expected(intent)
        val transaction = transactionDao.getTransactionById(intent.transactionId)
        val items = transactionDao.getItemsForTransaction(intent.transactionId)
        val settlements = transactionDao.getSettlementsForCashSaleRecovery(intent.operationId, intent.transactionId)
        check(mismatch(expected, transaction, items, settlements) == null) {
            "Sale persisted financial evidence mismatch"
        }
        return checkNotNull(transaction)
    }

    private fun transaction(intent: Intent): TransactionEntity {
        val draft = intent.draft
        if (intent.funding.category == PaymentCategory.CREDIT) {
            require(intent.transactionType == TransactionType.SALE && !intent.customerId.isNullOrBlank()) {
                "Credit sale requires an authoritative customer."
            }
        }
        return TransactionEntity(
            id = intent.transactionId,
            branchId = intent.branchId,
            type = intent.transactionType,
            customerId = intent.customerId,
            subtotalMinor = draft.subtotal.amount,
            taxRateBps = if (draft.isTaxEnabled) draft.taxRateBps else 0,
            taxableBaseMinor = draft.taxableBase.amount,
            taxAmountMinor = draft.taxAmount.amount,
            txShippingMinor = draft.txShipping.amount,
            txPackingMinor = draft.txPacking.amount,
            grandTotalMinor = draft.grandTotal.amount,
            currencyCode = intent.currencyCode,
            currencyDecimalPlaces = intent.currencyDecimalPlaces,
            createdByUserId = intent.actorUserId,
            deviceId = intent.deviceId,
            createdAt = intent.occurredAt,
            description = draft.manualDescription,
            displayRef = "${if (intent.transactionType == TransactionType.COUPON_PURCHASE) "CPN" else "SAL"}-${intent.occurredAt % 1_000_000_000}-${intent.transactionId.takeLast(4).uppercase()}"
        )
    }
}
