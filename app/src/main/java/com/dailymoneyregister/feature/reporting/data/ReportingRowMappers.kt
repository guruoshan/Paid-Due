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
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.readmodel.TransactionFinancialRecord
import com.dailymoneyregister.core.model.enums.CouponLedgerType
import com.dailymoneyregister.core.model.enums.CreditLedgerType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.data.ExpenseReadProjectionResolver
import com.dailymoneyregister.feature.reporting.domain.AmountCorrectionReportRow
import com.dailymoneyregister.feature.reporting.domain.CouponReportEventKind
import com.dailymoneyregister.feature.reporting.domain.CouponReportRow
import com.dailymoneyregister.feature.reporting.domain.CreditRepaymentAllocationReport
import com.dailymoneyregister.feature.reporting.domain.CreditRepaymentReportRow
import com.dailymoneyregister.feature.reporting.domain.ExpenseReportRow
import com.dailymoneyregister.feature.reporting.domain.PaymentMethodChangeReportRow
import com.dailymoneyregister.feature.reporting.domain.RefundReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportBranch
import com.dailymoneyregister.feature.reporting.domain.ReportCashier
import com.dailymoneyregister.feature.reporting.domain.ReportLifecycleStatus
import com.dailymoneyregister.feature.reporting.domain.ReportPaymentPart
import com.dailymoneyregister.feature.reporting.domain.ReportPerson
import com.dailymoneyregister.feature.reporting.domain.ReportSaleItem
import com.dailymoneyregister.feature.reporting.domain.SaleReportRow
import com.dailymoneyregister.feature.reporting.domain.VoidReportRow

/**
 * Display lookups only. These never carry financial authority.
 */
internal data class ReportingLookups(
    val branchesById: Map<String, BranchEntity>,
    val usersById: Map<String, UserAccountEntity>,
    val customersById: Map<String, CustomerEntity>,
    val cashierNamesById: Map<String, String> = emptyMap()
) {

    fun branch(branchId: String): ReportBranch =
        ReportBranch(
            id = branchId,
            name = branchesById[branchId]?.name
        )

    fun cashier(userId: String): ReportCashier =
        ReportCashier(
            userId = userId,
            displayName = cashierNamesById[userId] ?: usersById[userId]?.displayName
        )

    /** Optional person: transactions do not enforce a customer FK. */
    fun person(customerId: String?): ReportPerson? {

        if (customerId == null) return null

        val customer = customersById[customerId]
            ?: return ReportPerson(id = customerId, name = null, phone = null)

        return ReportPerson(
            id = customer.id,
            name = customer.name,
            phone = customer.canonicalPhone
        )
    }

    /** Required person: repayment and coupon rows have a hard customer FK. */
    fun requirePerson(customerId: String): ReportPerson {

        val customer = checkNotNull(customersById[customerId]) {
            "Customer $customerId was not found for a required Reporting person."
        }

        return ReportPerson(
            id = customer.id,
            name = customer.name,
            phone = customer.canonicalPhone
        )
    }
}

/**
 * Balance evidence derived ONLY from the authoritative ordered ledgers.
 * Returns null whenever the evidence cannot be proven exactly.
 */
internal object ReportingBalanceEvidence {

    private val creditOrder =
        compareBy<CreditLedgerEventEntity>({ it.occurredAt }, { it.id })

    private val couponOrder =
        compareBy<CouponLedgerEventEntity>({ it.occurredAt }, { it.id })

    /**
     * balanceBefore / balanceAfter around one repayment.
     * Null when the repayment's ledger events are missing or interleaved with
     * unrelated events (non-contiguous), because exact order is then unproven.
     */
    fun creditBalancesAroundRepayment(
        ledger: List<CreditLedgerEventEntity>,
        repaymentId: String,
        currencyCode: String
    ): Pair<MoneyMinorUnits, MoneyMinorUnits>? {

        val ordered = ledger
            .filter { it.currencyCode == currencyCode }
            .sortedWith(creditOrder)

        val matched = ordered.indices.filter {
            ordered[it].repaymentId == repaymentId
        }

        if (matched.isEmpty()) return null

        val first = matched.first()
        val last = matched.last()

        if (last - first + 1 != matched.size) return null

        var before = MoneyMinorUnits.ZERO

        for (index in 0 until first) {
            before += MoneyMinorUnits(ordered[index].amountSignedMinor)
        }

        var after = before

        for (index in first..last) {
            after += MoneyMinorUnits(ordered[index].amountSignedMinor)
        }

        return before to after
    }

    /** Wallet balance immediately after one coupon ledger event. */
    fun couponBalanceAfterEvent(
        ledger: List<CouponLedgerEventEntity>,
        eventId: String
    ): MoneyMinorUnits? {

        val ordered = ledger.sortedWith(couponOrder)

        val index = ordered.indexOfFirst { it.id == eventId }

        if (index < 0) return null

        var balance = MoneyMinorUnits.ZERO

        for (position in 0..index) {
            balance += MoneyMinorUnits(ordered[position].amountSignedMinor)
        }

        return balance
    }
}

/**
 * Pure projection from persisted evidence into Reporting rows.
 *
 * No database access. No financial state is created or changed.
 * Anything unprovable throws (IllegalStateException / IllegalArgumentException)
 * and is surfaced by the repository as ReportingIntegrityException.
 */
internal object ReportingRowMappers {

    // ------------------------------------------------------------------
    // Sale
    // ------------------------------------------------------------------

    fun mapSale(
        record: TransactionFinancialRecord,
        items: List<TransactionItemEntity>,
        creditEvents: List<CreditLedgerEventEntity>,
        lookups: ReportingLookups,
        actorUserId: String? = null
    ): SaleReportRow {

        val tx = record.transaction

        check(tx.type == TransactionType.SALE) {
            "Sale mapper received non-sale transaction ${tx.id}."
        }

        val view = FinancialReadModelResolver.resolve(record)

        val paymentParts = view.fundingBreakdown.map {
            ReportPaymentPart(
                category = it.category,
                channel = it.channel,
                amount = MoneyMinorUnits(it.amountMinor)
            )
        }.ifEmpty {
            // A credit obligation is the unpaid tender component, not a collection.
            // Represent it as CREDIT so the sale total remains fully classified without
            // fabricating a cash/card settlement.
            val obligationMinor = creditEvents
                .filter {
                    it.transactionId == tx.id &&
                        it.type == CreditLedgerType.OBLIGATION &&
                        it.amountSignedMinor > 0L
                }
                .fold(0L) { total, event -> Math.addExact(total, event.amountSignedMinor) }
            if (obligationMinor > 0L) {
                listOf(
                    ReportPaymentPart(
                        category = PaymentCategory.CREDIT,
                        channel = PaymentChannel.CREDIT_ACCOUNT,
                        amount = MoneyMinorUnits(obligationMinor)
                    )
                )
            } else {
                emptyList()
            }
        }

        val obligations = creditEvents.filter {
            it.transactionId == tx.id &&
                it.type == CreditLedgerType.OBLIGATION &&
                it.amountSignedMinor > 0L
        }

        val isCreditSale =
            paymentParts.any { it.category == PaymentCategory.CREDIT } ||
                obligations.isNotEmpty()

        val operationId =
            record.settlements
                .filter { it.entryPurpose == EntryPurpose.SALE_FUNDING }
                .map { it.operationId }
                .distinct()
                .singleOrNull()
                ?: obligations
                    .map { it.operationId }
                    .distinct()
                    .singleOrNull()

        check(!operationId.isNullOrBlank()) {
            "Sale ${tx.id} has no unique authoritative originating operation."
        }
        check(sumExact(paymentParts.map { it.amount.amount }) == view.originalAmount.amount) {
            "Sale ${tx.id} tender total does not equal its immutable original amount."
        }

        val states = view.lifecycleStates

        val lifecycle = when {
            FinancialLifecycleState.VOIDED in states ->
                ReportLifecycleStatus.VOIDED

            FinancialLifecycleState.FULLY_REFUNDED in states ->
                ReportLifecycleStatus.FULLY_REFUNDED

            FinancialLifecycleState.PARTIALLY_REFUNDED in states ->
                ReportLifecycleStatus.PARTIALLY_REFUNDED

            FinancialLifecycleState.CORRECTED in states ->
                ReportLifecycleStatus.CORRECTED

            else ->
                ReportLifecycleStatus.NORMAL
        }

        return SaleReportRow(
            stableId = "sale:${tx.id}",
            operationId = operationId,
            transactionId = tx.id,
            reference = tx.displayRef,
            occurredAt = tx.createdAt,
            currencyCode = tx.currencyCode,
            branch = lookups.branch(tx.branchId),
            cashier = lookups.cashier(actorUserId?.takeIf { it.isNotBlank() } ?: tx.createdByUserId),
            person = lookups.person(tx.customerId),
            subtotal = MoneyMinorUnits(tx.subtotalMinor),
            taxAmount = MoneyMinorUnits(tx.taxAmountMinor),
            shippingAmount = MoneyMinorUnits(tx.txShippingMinor),
            packingAmount = MoneyMinorUnits(tx.txPackingMinor),
            originalAmount = view.originalAmount,
            lifetimeCurrentAmount = view.currentAmount,
            lifetimeRefundedAmount = view.refundedAmount,
            paymentParts = paymentParts,
            items = items.map {
                ReportSaleItem(
                    itemId = it.id,
                    productId = it.productId,
                    name = it.productNameSnapshot,
                    description = it.descriptionSnapshot,
                    quantity = it.quantity,
                    unitPrice = MoneyMinorUnits(it.unitPriceMinor),
                    lineTotal = MoneyMinorUnits(it.lineTotalMinor),
                    packingCharge = MoneyMinorUnits(it.itemPackingChargeMinor),
                    shippingCharge = MoneyMinorUnits(it.itemShippingChargeMinor)
                )
            },
            lifecycleStatus = lifecycle,
            isCreditSale = isCreditSale
        )
    }

    // ------------------------------------------------------------------
    // Refund
    // ------------------------------------------------------------------

    /**
     * settlements = settlements of the REFUND operation.
     * couponEvents = coupon ledger events of the REFUND operation.
     */
    fun mapRefund(
        event: RefundEventEntity,
        tx: TransactionEntity,
        settlements: List<SettlementEntryEntity>,
        couponEvents: List<CouponLedgerEventEntity>,
        lookups: ReportingLookups
    ): RefundReportRow {

        val outflows = settlements.filter {
            it.entryPurpose == EntryPurpose.REFUND_OUTFLOW &&
                it.amountSignedMinor < 0L
        }

        val externalParts =
            outflows
                .groupBy { it.category to it.channel }
                .map { (identity, entries) ->
                    ReportPaymentPart(
                        category = identity.first,
                        channel = identity.second,
                        amount = MoneyMinorUnits(
                            sumExact(entries.map { Math.negateExact(it.amountSignedMinor) })
                        )
                    )
                }

        val couponCredit = sumExact(
            couponEvents
                .filter {
                    it.type == CouponLedgerType.REFUND_CREDIT &&
                        it.amountSignedMinor > 0L
                }
                .map { it.amountSignedMinor }
        )

        val couponParts =
            if (couponCredit > 0L) {
                listOf(
                    ReportPaymentPart(
                        category = PaymentCategory.COUPON,
                        channel = PaymentChannel.COUPON_WALLET,
                        amount = MoneyMinorUnits(couponCredit)
                    )
                )
            } else {
                emptyList()
            }

        val destinations = externalParts + couponParts
        check(sumExact(destinations.map { it.amount.amount }) == event.refundAmountMinor) {
            "Refund ${event.id} destinations do not equal its authoritative refund amount."
        }

        return RefundReportRow(
            stableId = "refund:${event.id}",
            operationId = event.operationId,
            transactionId = tx.id,
            reference = tx.displayRef,
            occurredAt = event.occurredAt,
            currencyCode = tx.currencyCode,
            branch = lookups.branch(tx.branchId),
            cashier = lookups.cashier(event.actorUserId),
            person = lookups.person(tx.customerId),
            refundEventId = event.id,
            amount = MoneyMinorUnits(event.refundAmountMinor),
            reason = event.reason,
            destinationParts = destinations
        )
    }

    // ------------------------------------------------------------------
    // Void
    // ------------------------------------------------------------------

    /**
     * The amount comes only from this void operation's own immutable
     * VOID_REVERSAL settlements. No transaction-current-balance fallback is
     * allowed because it can reuse an amount across multiple void events.
     */
    fun mapVoid(
        event: VoidEventEntity,
        tx: TransactionEntity,
        settlements: List<SettlementEntryEntity>,
        lookups: ReportingLookups
    ): VoidReportRow {

        val reversals = settlements.filter {
            it.entryPurpose == EntryPurpose.VOID_REVERSAL &&
                it.amountSignedMinor < 0L
        }

        check(reversals.isNotEmpty()) {
            "Void ${event.id} has no event-specific authoritative reversal settlements."
        }
        val affected = MoneyMinorUnits(
            sumExact(reversals.map { Math.negateExact(it.amountSignedMinor) })
        )
        check(affected.amount > 0L) {
            "Void ${event.id} reverses no positive amount."
        }

        return VoidReportRow(
            stableId = "void:${event.id}",
            operationId = event.operationId,
            transactionId = tx.id,
            reference = tx.displayRef,
            occurredAt = event.occurredAt,
            currencyCode = tx.currencyCode,
            branch = lookups.branch(tx.branchId),
            cashier = lookups.cashier(event.actorUserId),
            person = lookups.person(tx.customerId),
            eventId = event.id,
            affectedAmount = affected,
            reason = event.reason
        )
    }

    // ------------------------------------------------------------------
    // Amount correction / payment-method change
    // ------------------------------------------------------------------

    fun mapCorrection(
        event: AmountCorrectionEventEntity,
        tx: TransactionEntity,
        lookups: ReportingLookups
    ): AmountCorrectionReportRow =
        AmountCorrectionReportRow(
            stableId = "correction:${event.id}",
            operationId = event.operationId,
            transactionId = tx.id,
            reference = tx.displayRef,
            occurredAt = event.occurredAt,
            currencyCode = tx.currencyCode,
            branch = lookups.branch(tx.branchId),
            cashier = lookups.cashier(event.actorUserId),
            person = lookups.person(tx.customerId),
            eventId = event.id,
            previousAmount = MoneyMinorUnits(event.previousAmountMinor),
            correctedAmount = MoneyMinorUnits(event.correctedAmountMinor),
            reason = event.reason
        )

    fun mapPaymentMethodChange(
        event: PaymentMethodChangeEventEntity,
        tx: TransactionEntity,
        lookups: ReportingLookups
    ): PaymentMethodChangeReportRow =
        PaymentMethodChangeReportRow(
            stableId = "paymentchange:${event.id}",
            operationId = event.operationId,
            transactionId = tx.id,
            reference = tx.displayRef,
            occurredAt = event.occurredAt,
            currencyCode = tx.currencyCode,
            branch = lookups.branch(tx.branchId),
            cashier = lookups.cashier(event.actorUserId),
            person = lookups.person(tx.customerId),
            eventId = event.id,
            previousCategory = event.previousMethod,
            previousChannel = event.previousChannel,
            newCategory = event.newMethod,
            newChannel = event.newChannel,
            reason = event.reason
        )

    // ------------------------------------------------------------------
    // Credit repayment
    // ------------------------------------------------------------------

    /**
     * Payment identity comes from the repayment operation's collection
     * settlements (they carry the real Cash / Online / Coupon channel).
     * Mixed collection identities cannot be represented and fail closed.
     */
    fun mapCreditRepayment(
        repayment: CreditRepaymentEntity,
        operation: OperationEntity,
        settlements: List<SettlementEntryEntity>,
        allocations: List<CreditRepaymentAllocationEntity>,
        balances: Pair<MoneyMinorUnits, MoneyMinorUnits>?,
        lookups: ReportingLookups
    ): CreditRepaymentReportRow {

        check(operation.id == repayment.operationId) {
            "Repayment ${repayment.id} operation mismatch."
        }

        val branchId = checkNotNull(operation.branchId) {
            "Repayment ${repayment.id} operation has no branch."
        }

        val collections = settlements.filter { it.amountSignedMinor > 0L }
        check(collections.isNotEmpty()) {
            "Repayment ${repayment.id} has no authoritative collection settlements."
        }
        check(collections.all {
            it.category == PaymentCategory.CASH ||
                it.category == PaymentCategory.ONLINE ||
                it.category == PaymentCategory.COUPON
        }) {
            "Repayment ${repayment.id} contains an unsupported collection category."
        }
        check(sumExact(collections.map { it.amountSignedMinor }) == repayment.amountMinor) {
            "Repayment ${repayment.id} settlement total disagrees with the repayment."
        }

        val paymentParts = collections
            .groupBy { it.category to it.channel }
            .map { (identity, entries) ->
                ReportPaymentPart(
                    category = identity.first,
                    channel = identity.second,
                    amount = MoneyMinorUnits(sumExact(entries.map { it.amountSignedMinor }))
                )
            }
        val identities = paymentParts.map { it.category to it.channel }.distinct()
        val identity = identities.singleOrNull()
        val category = identity?.first ?: repayment.paymentCategory

        if (identities.size == 1) {
            check(category == repayment.paymentCategory) {
                "Repayment ${repayment.id} settlement category disagrees with the repayment."
            }
        }
        check(sumExact(allocations.map { it.allocatedAmountMinor }) == repayment.amountMinor) {
            "Repayment ${repayment.id} allocations do not equal the repayment amount."
        }

        return CreditRepaymentReportRow(
            stableId = "repayment:${repayment.id}",
            operationId = repayment.operationId,
            transactionId = null,
            reference = null,
            occurredAt = repayment.occurredAt,
            currencyCode = repayment.currencyCode,
            branch = lookups.branch(branchId),
            cashier = lookups.cashier(repayment.actorUserId),
            person = lookups.requirePerson(repayment.customerId),
            repaymentId = repayment.id,
            amount = MoneyMinorUnits(repayment.amountMinor),
            paymentCategory = category,
            paymentChannel = identity?.second,
            allocations = allocations.map {
                CreditRepaymentAllocationReport(
                    allocationId = it.id,
                    originalTransactionId = it.originalTransactionId,
                    amount = MoneyMinorUnits(it.allocatedAmountMinor)
                )
            },
            balanceBefore = balances?.first,
            balanceAfter = balances?.second,
            paymentParts = paymentParts
        )
    }

    // ------------------------------------------------------------------
    // Coupon
    // ------------------------------------------------------------------

    /**
     * Sell vs Top Up share RECORD_COUPON_PURCHASE; the durable operation
     * intent payload (prefix "TOPUP|") is the authoritative discriminator.
     * A coupon REDEMPTION made by a credit-repayment operation is reported as
     * CREDIT_REPAYMENT.
     */
    fun mapCouponEvent(
        event: CouponLedgerEventEntity,
        operation: OperationEntity,
        wallet: CouponWalletEntity,
        tx: TransactionEntity?,
        settlements: List<SettlementEntryEntity>,
        resultingBalance: MoneyMinorUnits?,
        lookups: ReportingLookups
    ): CouponReportRow {

        check(event.operationId == operation.id) {
            "Coupon event ${event.id} operation mismatch."
        }

        check(event.walletId == wallet.id) {
            "Coupon event ${event.id} wallet mismatch."
        }

        check(event.currencyCode == wallet.currencyCode) {
            "Coupon event ${event.id} currency disagrees with its wallet."
        }

        val branchId = checkNotNull(operation.branchId) {
            "Coupon event ${event.id} operation has no branch."
        }

        val isTopUp = operation.intentPayload.startsWith("TOPUP|")

        val kind = when (event.type) {

            CouponLedgerType.PURCHASE ->
                if (isTopUp) CouponReportEventKind.TOP_UP
                else CouponReportEventKind.PURCHASE

            CouponLedgerType.PROMOTION_BONUS ->
                CouponReportEventKind.PROMOTION_BONUS

            CouponLedgerType.REDEMPTION ->
                if (operation.operationType == OperationType.RECORD_CREDIT_REPAYMENT) {
                    CouponReportEventKind.CREDIT_REPAYMENT
                } else {
                    CouponReportEventKind.REDEMPTION
                }

            CouponLedgerType.REFUND_CREDIT ->
                CouponReportEventKind.REFUND_CREDIT
        }

        var fundingCategory: PaymentCategory? = null
        var fundingChannel: PaymentChannel? = null

        when (kind) {

            CouponReportEventKind.PURCHASE,
            CouponReportEventKind.TOP_UP -> {

                val identities = settlements
                    .filter {
                        it.entryPurpose == EntryPurpose.COUPON_PURCHASE_FUNDING &&
                            it.amountSignedMinor > 0L
                    }
                    .map { it.category to it.channel }
                    .distinct()

                check(identities.size == 1) {
                    "Coupon funding event ${event.id} must have one authoritative tender identity."
                }
                val fundingTotal = sumExact(
                    settlements.filter {
                        it.entryPurpose == EntryPurpose.COUPON_PURCHASE_FUNDING &&
                            it.amountSignedMinor > 0L
                    }.map { it.amountSignedMinor }
                )
                check(fundingTotal == event.amountSignedMinor) {
                    "Coupon funding event ${event.id} amount does not match its funding settlements."
                }
                val identity = identities.single()

                fundingCategory = identity.first
                fundingChannel = identity.second
            }

            CouponReportEventKind.REDEMPTION,
            CouponReportEventKind.CREDIT_REPAYMENT -> {
                fundingCategory = PaymentCategory.COUPON
                fundingChannel = PaymentChannel.COUPON_WALLET
            }

            CouponReportEventKind.REFUND_CREDIT,
            CouponReportEventKind.PROMOTION_BONUS -> Unit
        }

        return CouponReportRow(
            stableId = "coupon:${event.id}",
            operationId = event.operationId,
            transactionId = event.transactionId,
            reference = tx?.displayRef,
            occurredAt = event.occurredAt,
            currencyCode = event.currencyCode,
            branch = lookups.branch(branchId),
            cashier = lookups.cashier(operation.actorUserId),
            person = lookups.requirePerson(wallet.customerId),
            walletId = wallet.id,
            ledgerEventId = event.id,
            eventKind = kind,
            signedAmount = MoneyMinorUnits(event.amountSignedMinor),
            fundingCategory = fundingCategory,
            fundingChannel = fundingChannel,
            authorizedUserId = event.authorizedUserId,
            authorizedUserName = event.authorizedUserNameSnapshot,
            resultingBalance = resultingBalance
        )
    }

    // ------------------------------------------------------------------
    // Expense
    // ------------------------------------------------------------------

    fun mapExpense(
        expense: ExpenseEntity,
        settlement: SettlementEntryEntity,
        lookups: ReportingLookups
    ): ExpenseReportRow {

        // Single authoritative mapper shared with Recent / Day Closing.
        val projection = ExpenseReadProjectionResolver.resolve(
            expense = expense,
            settlement = settlement
        )

        val hasPayee =
            expense.payeeId != null ||
                projection.payeeName != null ||
                projection.payeePhone != null

        return ExpenseReportRow(
            stableId = "expense:${expense.id}",
            operationId = expense.operationId,
            transactionId = null,
            reference = projection.reference,
            occurredAt = projection.occurredAt,
            currencyCode = expense.currencyCode,
            branch = lookups.branch(expense.branchId),
            cashier = lookups.cashier(expense.actorUserId),
            person =
                if (hasPayee) {
                    ReportPerson(
                        id = expense.payeeId,
                        name = projection.payeeName,
                        phone = projection.payeePhone
                    )
                } else {
                    null
                },
            expenseId = expense.id,
            category = projection.category,
            amount = projection.amount,
            paymentCategory = projection.paymentCategory,
            paymentChannel = projection.paymentChannel,
            note = projection.note,
            // Supporting-document count is not read in Phase 2.
            supportingDocumentCount = null
        )
    }

    private fun sumExact(values: List<Long>): Long =
        values.fold(0L) { total, value -> Math.addExact(total, value) }
}