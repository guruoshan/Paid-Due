package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CouponWalletEntity
import com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CouponLedgerType
import com.dailymoneyregister.core.model.enums.CreditLedgerType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.CouponReportEventKind
import com.dailymoneyregister.feature.reporting.domain.ReportActivityType
import com.dailymoneyregister.feature.reporting.domain.ReportLifecycleStatus
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReportingRowMappersTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private val branch =
        BranchEntity(
            id = "branch-1",
            businessId = "business",
            name = "Main",
            code = "MAIN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val user =
        UserAccountEntity(
            id = "user-1",
            businessId = "business",
            displayName = "Cashier One",
            username = "c1",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "h",
            credentialSalt = "s",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val customer =
        CustomerEntity(
            id = "cust-1",
            businessId = "business",
            canonicalPhone = "+96890000000",
            name = "Customer One",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val lookups =
        ReportingLookups(
            branchesById = mapOf(branch.id to branch),
            usersById = mapOf(user.id to user),
            customersById = mapOf(customer.id to customer)
        )

    private fun tx(
        id: String = "tx-1",
        total: Long = 1000L,
        customerId: String? = null
    ) = TransactionEntity(
        id = id,
        branchId = "branch-1",
        type = TransactionType.SALE,
        customerId = customerId,
        subtotalMinor = total,
        taxRateBps = 0,
        taxableBaseMinor = total,
        taxAmountMinor = 0L,
        grandTotalMinor = total,
        currencyCode = "OMR",
        currencyDecimalPlaces = 3,
        createdByUserId = "user-1",
        deviceId = "device-1",
        createdAt = 100L,
        displayRef = "SALE-1"
    )

    private fun settlement(
        id: String,
        operationId: String,
        purpose: EntryPurpose,
        category: PaymentCategory,
        channel: PaymentChannel,
        amount: Long,
        transactionId: String? = "tx-1",
        subjectType: SubjectType = SubjectType.TRANSACTION,
        subjectId: String = "tx-1"
    ) = SettlementEntryEntity(
        id = id,
        operationId = operationId,
        subjectType = subjectType,
        subjectId = subjectId,
        transactionId = transactionId,
        entryPurpose = purpose,
        category = category,
        channel = channel,
        amountSignedMinor = amount,
        currencyCode = "OMR",
        confirmationType = ConfirmationType.CASHIER_CONFIRMED,
        occurredAt = 100L,
        actorUserId = "user-1"
    )

    private fun operation(
        id: String,
        type: OperationType,
        intentPayload: String = "payload"
    ) = OperationEntity(
        id = id,
        businessId = "business",
        branchId = "branch-1",
        operationType = type,
        subjectType = SubjectType.TRANSACTION,
        subjectId = "subject",
        actorUserId = "user-1",
        deviceId = "device-1",
        occurredAt = 100L,
        requestFingerprint = "fp",
        intentPayload = intentPayload,
        status = OperationStatus.SUCCEEDED
    )

    private fun couponEvent(
        id: String,
        operationId: String,
        type: CouponLedgerType,
        amount: Long,
        occurredAt: Long = 100L,
        transactionId: String? = null
    ) = CouponLedgerEventEntity(
        id = id,
        operationId = operationId,
        walletId = "wallet-1",
        type = type,
        amountSignedMinor = amount,
        currencyCode = "OMR",
        transactionId = transactionId,
        occurredAt = occurredAt
    )

    private val wallet =
        CouponWalletEntity(
            id = "wallet-1",
            customerId = "cust-1",
            currencyCode = "OMR",
            createdAt = 0L
        )

    private fun creditEvent(
        id: String,
        type: CreditLedgerType,
        amount: Long,
        occurredAt: Long,
        repaymentId: String? = null,
        transactionId: String? = null,
        operationId: String = "op-x"
    ) = CreditLedgerEventEntity(
        id = id,
        operationId = operationId,
        customerId = "cust-1",
        type = type,
        amountSignedMinor = amount,
        currencyCode = "OMR",
        transactionId = transactionId,
        repaymentId = repaymentId,
        occurredAt = occurredAt
    )

    private fun repayment(amount: Long = 1000L, category: PaymentCategory = PaymentCategory.CASH) =
        CreditRepaymentEntity(
            id = "rep-1",
            operationId = "op-rep",
            customerId = "cust-1",
            amountMinor = amount,
            currencyCode = "OMR",
            paymentCategory = category,
            occurredAt = 100L,
            actorUserId = "user-1"
        )

    // ------------------------------------------------------------------
    // Sale
    // ------------------------------------------------------------------

    @Test
    fun cashSaleMapsFundingAndStaysSale() {
        val record = TransactionFinancialRecord(
            transaction = tx(),
            settlements = listOf(
                settlement(
                    "s1", "op-1", EntryPurpose.SALE_FUNDING,
                    PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 1000L
                )
            )
        )

        val row = ReportingRowMappers.mapSale(record, emptyList(), emptyList(), lookups)

        assertEquals(ReportActivityType.SALE, row.activityType)
        assertEquals(MoneyMinorUnits(1000L), row.originalAmount)
        assertEquals("op-1", row.operationId)
        assertEquals("Main", row.branch.name)
        assertEquals("Cashier One", row.cashier.displayName)
        assertEquals(ReportLifecycleStatus.NORMAL, row.lifecycleStatus)
        assertEquals(
            listOf(
                ReportPaymentPart(
                    PaymentCategory.CASH,
                    PaymentChannel.CASH_TENDER,
                    MoneyMinorUnits(1000L)
                )
            ),
            row.paymentParts
        )
    }

    @Test
    fun creditObligationMakesCreditSaleWithoutInventingCollection() {
        val record = TransactionFinancialRecord(
            transaction = tx(customerId = "cust-1"),
            settlements = emptyList()
        )

        val obligation = creditEvent(
            id = "c1",
            type = CreditLedgerType.OBLIGATION,
            amount = 1000L,
            occurredAt = 100L,
            transactionId = "tx-1",
            operationId = "op-2"
        )

        val row = ReportingRowMappers.mapSale(record, emptyList(), listOf(obligation), lookups)

        assertEquals(ReportActivityType.CREDIT_SALE, row.activityType)
        assertEquals(emptyList<ReportPaymentPart>(), row.paymentParts)
        assertEquals("op-2", row.operationId)
        assertEquals("Customer One", row.person?.name)
    }

    @Test
    fun voidedSaleHasVoidedLifecycleStatus() {
        val record = TransactionFinancialRecord(
            transaction = tx(),
            voidEvents = listOf(
                VoidEventEntity(
                    id = "v1",
                    transactionId = "tx-1",
                    operationId = "op-v",
                    actorUserId = "user-1",
                    occurredAt = 200L
                )
            )
        )

        val row = ReportingRowMappers.mapSale(record, emptyList(), emptyList(), lookups)

        assertEquals(ReportLifecycleStatus.VOIDED, row.lifecycleStatus)
    }

    // ------------------------------------------------------------------
    // Refund / void
    // ------------------------------------------------------------------

    @Test
    fun refundDestinationCombinesCashOutflowAndCouponCredit() {
        val event = RefundEventEntity(
            id = "r1",
            transactionId = "tx-1",
            operationId = "op-r",
            refundAmountMinor = 700L,
            reason = "Damaged",
            actorUserId = "user-1",
            occurredAt = 300L
        )

        val row = ReportingRowMappers.mapRefund(
            event = event,
            tx = tx(),
            settlements = listOf(
                settlement(
                    "s-r", "op-r", EntryPurpose.REFUND_OUTFLOW,
                    PaymentCategory.CASH, PaymentChannel.CASH_TENDER, -500L
                )
            ),
            couponEvents = listOf(
                couponEvent("ce1", "op-r", CouponLedgerType.REFUND_CREDIT, 200L)
            ),
            lookups = lookups
        )

        assertEquals(ReportActivityType.REFUND, row.activityType)
        assertEquals(MoneyMinorUnits(700L), row.amount)
        assertEquals(
            setOf(
                ReportPaymentPart(
                    PaymentCategory.CASH,
                    PaymentChannel.CASH_TENDER,
                    MoneyMinorUnits(500L)
                ),
                ReportPaymentPart(
                    PaymentCategory.COUPON,
                    PaymentChannel.COUPON_WALLET,
                    MoneyMinorUnits(200L)
                )
            ),
            row.destinationParts.toSet()
        )
    }

    private val voidEvent =
        VoidEventEntity(
            id = "v1",
            transactionId = "tx-1",
            operationId = "op-v",
            actorUserId = "user-1",
            occurredAt = 400L
        )

    @Test
    fun voidUsesReversalEvidenceWhenPresent() {
        val row = ReportingRowMappers.mapVoid(
            event = voidEvent,
            tx = tx(),
            settlements = listOf(
                settlement(
                    "s-v", "op-v", EntryPurpose.VOID_REVERSAL,
                    PaymentCategory.CASH, PaymentChannel.CASH_TENDER, -1000L
                )
            ),
            fallbackAffected = MoneyMinorUnits(999L),
            lookups = lookups
        )

        assertEquals(MoneyMinorUnits(1000L), row.affectedAmount)
    }

    @Test
    fun voidFallsBackWhenNoReversalEvidenceExists() {
        val row = ReportingRowMappers.mapVoid(
            event = voidEvent,
            tx = tx(),
            settlements = emptyList(),
            fallbackAffected = MoneyMinorUnits(750L),
            lookups = lookups
        )

        assertEquals(MoneyMinorUnits(750L), row.affectedAmount)
    }

    // ------------------------------------------------------------------
    // Credit repayment
    // ------------------------------------------------------------------

    @Test
    fun creditRepaymentTakesIdentityFromCollectionSettlement() {
        val row = ReportingRowMappers.mapCreditRepayment(
            repayment = repayment(),
            operation = operation("op-rep", OperationType.RECORD_CREDIT_REPAYMENT),
            settlements = listOf(
                settlement(
                    "s-rep", "op-rep", EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
                    PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 1000L,
                    transactionId = null, subjectType = SubjectType.REPAYMENT, subjectId = "rep-1"
                )
            ),
            allocations = listOf(
                CreditRepaymentAllocationEntity("a1", "rep-1", "tx-1", 600L, 100L),
                CreditRepaymentAllocationEntity("a2", "rep-1", "tx-2", 400L, 100L)
            ),
            balances = MoneyMinorUnits(5000L) to MoneyMinorUnits(4000L),
            lookups = lookups
        )

        assertEquals(ReportActivityType.CREDIT_REPAYMENT, row.activityType)
        assertEquals(PaymentCategory.CASH, row.paymentCategory)
        assertEquals(PaymentChannel.CASH_TENDER, row.paymentChannel)
        assertEquals(2, row.allocations.size)
        assertEquals(MoneyMinorUnits(5000L), row.balanceBefore)
        assertEquals(MoneyMinorUnits(4000L), row.balanceAfter)
        assertEquals("Customer One", row.person.name)
    }

    @Test(expected = IllegalStateException::class)
    fun creditRepaymentWithMixedCollectionIdentitiesFailsClosed() {
        ReportingRowMappers.mapCreditRepayment(
            repayment = repayment(amount = 1000L),
            operation = operation("op-rep", OperationType.RECORD_CREDIT_REPAYMENT),
            settlements = listOf(
                settlement(
                    "s1", "op-rep", EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
                    PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 600L,
                    transactionId = null, subjectType = SubjectType.REPAYMENT, subjectId = "rep-1"
                ),
                settlement(
                    "s2", "op-rep", EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
                    PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR, 400L,
                    transactionId = null, subjectType = SubjectType.REPAYMENT, subjectId = "rep-1"
                )
            ),
            allocations = emptyList(),
            balances = null,
            lookups = lookups
        )
    }

    // ------------------------------------------------------------------
    // Coupon
    // ------------------------------------------------------------------

    @Test
    fun couponTopUpIsProvedByDurableIntentPayload() {
        val row = ReportingRowMappers.mapCouponEvent(
            event = couponEvent("ce-top", "op-top", CouponLedgerType.PURCHASE, 500L),
            operation = operation("op-top", OperationType.RECORD_COUPON_PURCHASE, "TOPUP|wallet-1|500"),
            wallet = wallet,
            tx = null,
            settlements = listOf(
                settlement(
                    "s-top", "op-top", EntryPurpose.COUPON_PURCHASE_FUNDING,
                    PaymentCategory.CASH, PaymentChannel.CASH_TENDER, 500L
                )
            ),
            resultingBalance = MoneyMinorUnits(500L),
            lookups = lookups
        )

        assertEquals(CouponReportEventKind.TOP_UP, row.eventKind)
        assertEquals(ReportActivityType.COUPON_TOP_UP, row.activityType)
        assertEquals(PaymentCategory.CASH, row.fundingCategory)
        assertEquals(PaymentChannel.CASH_TENDER, row.fundingChannel)
    }

    @Test
    fun couponPurchaseWithoutTopUpPayloadStaysPurchase() {
        val row = ReportingRowMappers.mapCouponEvent(
            event = couponEvent("ce-buy", "op-buy", CouponLedgerType.PURCHASE, 800L),
            operation = operation("op-buy", OperationType.RECORD_COUPON_PURCHASE, "SELL|wallet-1|800"),
            wallet = wallet,
            tx = null,
            settlements = emptyList(),
            resultingBalance = null,
            lookups = lookups
        )

        assertEquals(CouponReportEventKind.PURCHASE, row.eventKind)
        assertNull(row.fundingCategory)
    }

    @Test
    fun couponRedemptionInsideCreditRepaymentIsReportedAsCreditRepayment() {
        val row = ReportingRowMappers.mapCouponEvent(
            event = couponEvent("ce-cr", "op-rep", CouponLedgerType.REDEMPTION, -200L),
            operation = operation("op-rep", OperationType.RECORD_CREDIT_REPAYMENT),
            wallet = wallet,
            tx = null,
            settlements = emptyList(),
            resultingBalance = MoneyMinorUnits(300L),
            lookups = lookups
        )

        assertEquals(CouponReportEventKind.CREDIT_REPAYMENT, row.eventKind)
        assertEquals(ReportActivityType.COUPON_CREDIT_REPAYMENT, row.activityType)
        assertEquals(MoneyMinorUnits(-200L), row.signedAmount)
        assertEquals(PaymentCategory.COUPON, row.fundingCategory)
    }

    @Test
    fun couponPromotionBonusIsItsOwnKind() {
        val row = ReportingRowMappers.mapCouponEvent(
            event = couponEvent("ce-bonus", "op-bonus", CouponLedgerType.PROMOTION_BONUS, 100L),
            operation = operation("op-bonus", OperationType.RECORD_COUPON_PURCHASE),
            wallet = wallet,
            tx = null,
            settlements = emptyList(),
            resultingBalance = null,
            lookups = lookups
        )

        assertEquals(CouponReportEventKind.PROMOTION_BONUS, row.eventKind)
        assertEquals(ReportActivityType.COUPON_BONUS, row.activityType)
    }

    // ------------------------------------------------------------------
    // Balance evidence
    // ------------------------------------------------------------------

    @Test
    fun couponBalanceIsCumulativeInLedgerOrder() {
        val ledger = listOf(
            couponEvent("a", "o1", CouponLedgerType.PURCHASE, 1000L, occurredAt = 1L),
            couponEvent("b", "o2", CouponLedgerType.REDEMPTION, -200L, occurredAt = 2L),
            couponEvent("c", "o3", CouponLedgerType.PROMOTION_BONUS, 50L, occurredAt = 3L)
        )

        assertEquals(MoneyMinorUnits(800L), ReportingBalanceEvidence.couponBalanceAfterEvent(ledger, "b"))
        assertEquals(MoneyMinorUnits(850L), ReportingBalanceEvidence.couponBalanceAfterEvent(ledger, "c"))
        assertNull(ReportingBalanceEvidence.couponBalanceAfterEvent(ledger, "missing"))
    }

    @Test
    fun creditBalanceBeforeAndAfterRepaymentIsProvedFromLedger() {
        val ledger = listOf(
            creditEvent("e1", CreditLedgerType.OBLIGATION, 5000L, occurredAt = 1L, transactionId = "tx-1"),
            creditEvent("e2", CreditLedgerType.REPAYMENT, -1000L, occurredAt = 2L, repaymentId = "rep-1")
        )

        val balances = ReportingBalanceEvidence.creditBalancesAroundRepayment(ledger, "rep-1", "OMR")

        assertNotNull(balances)
        assertEquals(MoneyMinorUnits(5000L), balances!!.first)
        assertEquals(MoneyMinorUnits(4000L), balances.second)
    }

    @Test
    fun creditBalanceEvidenceIsNullWhenRepaymentEventsAreInterleaved() {
        val ledger = listOf(
            creditEvent("e1", CreditLedgerType.REPAYMENT, -300L, occurredAt = 1L, repaymentId = "rep-1"),
            creditEvent("e2", CreditLedgerType.OBLIGATION, 900L, occurredAt = 2L, transactionId = "tx-9"),
            creditEvent("e3", CreditLedgerType.REPAYMENT, -700L, occurredAt = 3L, repaymentId = "rep-1")
        )

        assertNull(ReportingBalanceEvidence.creditBalancesAroundRepayment(ledger, "rep-1", "OMR"))
    }

    // ------------------------------------------------------------------
    // Expense / correction / payment change
    // ------------------------------------------------------------------

    @Test
    fun expenseMapsThroughTheAuthoritativeSettlement() {
        val expense = ExpenseEntity(
            id = "exp-1",
            operationId = "op-exp",
            businessId = "business",
            branchId = "branch-1",
            cashShiftId = null,
            actorUserId = "user-1",
            payeeId = null,
            payeeNameSnapshot = "Supplier",
            payeePhoneSnapshot = "+96891111111",
            payeeAccountSnapshot = null,
            category = "Supplies",
            amountMinor = 2500L,
            paymentCategory = "CASH",
            currencyCode = "OMR",
            reference = "EXP-1",
            note = null,
            recurrenceId = null,
            occurredAt = 100L,
            createdAt = 100L
        )

        val outflow = settlement(
            "s-exp", "op-exp", EntryPurpose.EXPENSE_OUTFLOW,
            PaymentCategory.CASH, PaymentChannel.CASH_TENDER, -2500L,
            transactionId = null, subjectType = SubjectType.EXPENSE, subjectId = "exp-1"
        )

        val row = ReportingRowMappers.mapExpense(expense, outflow, lookups)

        assertEquals(ReportActivityType.EXPENSE, row.activityType)
        assertEquals(MoneyMinorUnits(2500L), row.amount)
        assertEquals(PaymentCategory.CASH, row.paymentCategory)
        assertEquals("Supplier", row.person?.name)
        assertNull(row.supportingDocumentCount)
    }

    @Test
    fun correctionAndPaymentMethodChangeMapDirectly() {
        val correction = ReportingRowMappers.mapCorrection(
            AmountCorrectionEventEntity(
                id = "c1",
                transactionId = "tx-1",
                operationId = "op-c",
                previousAmountMinor = 5000L,
                correctedAmountMinor = 4500L,
                actorUserId = "user-1",
                occurredAt = 500L
            ),
            tx(),
            lookups
        )

        assertEquals(MoneyMinorUnits(-500L), correction.signedDifference)
        assertEquals("SALE-1", correction.reference)

        val change = ReportingRowMappers.mapPaymentMethodChange(
            PaymentMethodChangeEventEntity(
                id = "p1",
                transactionId = "tx-1",
                operationId = "op-p",
                previousMethod = PaymentCategory.CASH,
                previousChannel = PaymentChannel.CASH_TENDER,
                newMethod = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.MANUAL_QR,
                actorUserId = "user-1",
                occurredAt = 600L
            ),
            tx(),
            lookups
        )

        assertEquals(PaymentCategory.ONLINE, change.newCategory)
        assertEquals(ReportActivityType.PAYMENT_METHOD_CHANGE, change.activityType)
    }
}