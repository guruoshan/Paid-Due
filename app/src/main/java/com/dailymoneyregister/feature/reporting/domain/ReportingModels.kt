package com.dailymoneyregister.feature.reporting.domain

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.money.MoneyMinorUnits

/**
 * Requested reporting scope.
 *
 * This is read-model input only and never financial truth.
 * Repository security must validate this scope against the authenticated
 * business, permitted branches and cashier/report permissions.
 */
data class ReportingScope(
    val businessId: String,
    val branchId: String?,
    val cashierUserId: String?,
    val currencyCode: String,
    val period: ReportingPeriod
) {
    init {
        require(businessId.isNotBlank()) {
            "Reporting business ID is required."
        }

        require(currencyCode.isNotBlank()) {
            "Reporting currency code is required."
        }
    }
}

enum class ReportActivityType {
    SALE,
    CREDIT_SALE,
    CREDIT_REPAYMENT,

    COUPON_PURCHASE,
    COUPON_TOP_UP,
    COUPON_REDEMPTION,
    COUPON_REFUND,
    COUPON_BONUS,
    COUPON_CREDIT_REPAYMENT,

    REFUND,
    EXPENSE,

    VOID,
    AMOUNT_CORRECTION,
    PAYMENT_METHOD_CHANGE
}

enum class ReportLifecycleStatus {
    NORMAL,
    PARTIALLY_REFUNDED,
    FULLY_REFUNDED,
    VOIDED,
    CORRECTED
}

data class ReportPerson(
    val id: String?,
    val name: String?,
    val phone: String?
)

data class ReportBranch(
    val id: String,
    val name: String?
) {
    init {
        require(id.isNotBlank()) {
            "Report branch ID is required."
        }
    }
}

data class ReportCashier(
    val userId: String,
    val displayName: String?
) {
    init {
        require(userId.isNotBlank()) {
            "Report cashier user ID is required."
        }
    }
}

data class ReportPaymentPart(
    val category: PaymentCategory,
    val channel: PaymentChannel,
    val amount: MoneyMinorUnits
) {
    init {
        require(amount.amount >= 0L) {
            "Report payment amount cannot be negative."
        }
    }
}

data class ReportSaleItem(
    val itemId: String,
    val productId: String?,
    val name: String,
    val description: String?,
    val quantity: Int,
    val unitPrice: MoneyMinorUnits,
    val lineTotal: MoneyMinorUnits,
    val packingCharge: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val shippingCharge: MoneyMinorUnits = MoneyMinorUnits.ZERO
) {
    init {
        require(itemId.isNotBlank()) {
            "Report item ID is required."
        }

        require(name.isNotBlank()) {
            "Report item name is required."
        }

        require(quantity > 0) {
            "Report item quantity must be positive."
        }
    }
}

/**
 * Presentation-neutral reporting row.
 *
 * Rows are projections of authoritative persisted evidence.
 * They never become a second financial source of truth.
 */
sealed interface ReportRow {

    val stableId: String
    val activityType: ReportActivityType

    val operationId: String?
    val transactionId: String?

    val reference: String?

    /**
     * Timestamp of THIS financial activity.
     *
     * Period filtering must use this timestamp rather than moving later
     * lifecycle actions back to the original sale date.
     */
    val occurredAt: Long

    val currencyCode: String

    val branch: ReportBranch
    val cashier: ReportCashier

    val person: ReportPerson?
}

/**
 * Original sale activity.
 *
 * originalAmount is the immutable sale amount at creation.
 *
 * lifetimeCurrentAmount / lifetimeRefundedAmount are useful for drill-down
 * status only. They MUST NOT be used to calculate a historical period's
 * financial total.
 *
 * Example:
 * a refund today against last month's sale contributes a RefundReportRow to
 * today's financial activity. It does not rewrite last month's sale row.
 */
data class SaleReportRow(
    override val stableId: String,
    override val operationId: String?,
    override val transactionId: String,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson?,

    val subtotal: MoneyMinorUnits,
    val taxAmount: MoneyMinorUnits,
    val shippingAmount: MoneyMinorUnits,
    val packingAmount: MoneyMinorUnits,

    val originalAmount: MoneyMinorUnits,

    val lifetimeCurrentAmount: MoneyMinorUnits,
    val lifetimeRefundedAmount: MoneyMinorUnits,

    val paymentParts: List<ReportPaymentPart>,
    val items: List<ReportSaleItem>,

    val lifecycleStatus: ReportLifecycleStatus,
    val isCreditSale: Boolean
) : ReportRow {

    override val activityType: ReportActivityType =
        if (isCreditSale) {
            ReportActivityType.CREDIT_SALE
        } else {
            ReportActivityType.SALE
        }

    init {
        require(stableId.isNotBlank())
        require(transactionId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(originalAmount.amount >= 0L)
        require(lifetimeCurrentAmount.amount >= 0L)
        require(lifetimeRefundedAmount.amount >= 0L)
    }
}

data class CreditRepaymentAllocationReport(
    val allocationId: String,
    val originalTransactionId: String,
    val amount: MoneyMinorUnits
) {
    init {
        require(allocationId.isNotBlank())
        require(originalTransactionId.isNotBlank())
        require(amount.amount > 0L)
    }
}

/**
 * Credit repayment is collection of receivable and NEVER new sales revenue.
 */
data class CreditRepaymentReportRow(
    override val stableId: String,
    override val operationId: String,
    override val transactionId: String?,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson,

    val repaymentId: String,
    val amount: MoneyMinorUnits,

    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel?,

    val allocations: List<CreditRepaymentAllocationReport>,

    /**
     * Nullable until the Reporting repository proves these values from the
     * authoritative ordered Credit ledger.
     */
    val balanceBefore: MoneyMinorUnits?,
    val balanceAfter: MoneyMinorUnits?,
    /**
     * Authoritative collection breakdown. Empty only for legacy rows where
     * settlement evidence is absent; those rows must not qualify as complete.
     */
    val paymentParts: List<ReportPaymentPart> = emptyList()
) : ReportRow {

    override val activityType: ReportActivityType =
        ReportActivityType.CREDIT_REPAYMENT

    init {
        require(stableId.isNotBlank())
        require(operationId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(repaymentId.isNotBlank())
        require(amount.amount > 0L)

        require(
            paymentCategory == PaymentCategory.CASH ||
                paymentCategory == PaymentCategory.ONLINE ||
                paymentCategory == PaymentCategory.COUPON
        ) {
            "Credit repayment report category must be Cash, Online or Coupon."
        }
    }
}

enum class CouponReportEventKind {
    PURCHASE,
    TOP_UP,
    REDEMPTION,
    REFUND_CREDIT,
    PROMOTION_BONUS,
    CREDIT_REPAYMENT
}

/**
 * Detailed Coupon activity.
 *
 * The persisted Coupon ledger currently uses PURCHASE for positive purchased
 * value; Reporting may distinguish Purchase versus Top Up only when existing
 * authoritative operation/command evidence proves which intent occurred.
 *
 * It must never infer TOP_UP merely from UI state.
 *
 * Sign contract (same as the authoritative Coupon ledger):
 *
 *   PURCHASE, TOP_UP, REFUND_CREDIT      -> positive (wallet credit)
 *   REDEMPTION, CREDIT_REPAYMENT         -> negative (wallet consumption)
 */
data class CouponReportRow(
    override val stableId: String,
    override val operationId: String,
    override val transactionId: String?,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson,

    val walletId: String,
    val ledgerEventId: String,
    val eventKind: CouponReportEventKind,

    /**
     * Same sign as authoritative Coupon ledger:
     *
     * positive = wallet credit
     * negative = wallet consumption
     */
    val signedAmount: MoneyMinorUnits,

    val fundingCategory: PaymentCategory?,
    val fundingChannel: PaymentChannel?,

    val authorizedUserId: String?,
    val authorizedUserName: String?,

    /**
     * Populated only from authoritative ledger balance evidence.
     */
    val resultingBalance: MoneyMinorUnits?
) : ReportRow {

    override val activityType: ReportActivityType
        get() =
            when (eventKind) {

                CouponReportEventKind.PURCHASE ->
                    ReportActivityType.COUPON_PURCHASE

                CouponReportEventKind.TOP_UP ->
                    ReportActivityType.COUPON_TOP_UP

                CouponReportEventKind.REDEMPTION ->
                    ReportActivityType.COUPON_REDEMPTION

                CouponReportEventKind.REFUND_CREDIT ->
                    ReportActivityType.COUPON_REFUND

                CouponReportEventKind.PROMOTION_BONUS ->
                    ReportActivityType.COUPON_BONUS

                CouponReportEventKind.CREDIT_REPAYMENT ->
                    ReportActivityType.COUPON_CREDIT_REPAYMENT
            }

    init {
        require(stableId.isNotBlank())
        require(operationId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(walletId.isNotBlank())
        require(ledgerEventId.isNotBlank())

        when (eventKind) {

            CouponReportEventKind.PURCHASE,
            CouponReportEventKind.TOP_UP,
            CouponReportEventKind.REFUND_CREDIT,
            CouponReportEventKind.PROMOTION_BONUS ->
                require(signedAmount.amount > 0L) {
                    "Coupon credit event must have a positive amount."
                }

            CouponReportEventKind.REDEMPTION,
            CouponReportEventKind.CREDIT_REPAYMENT ->
                require(signedAmount.amount < 0L) {
                    "Coupon consumption event must have a negative amount."
                }
        }
    }
}

/**
 * Refund is an append-only financial activity occurring at occurredAt.
 *
 * amount is stored as a positive business amount.
 * The Reporting aggregator later applies its outflow/revenue-reversal meaning.
 */
data class RefundReportRow(
    override val stableId: String,
    override val operationId: String,
    override val transactionId: String,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson?,

    val refundEventId: String,
    val amount: MoneyMinorUnits,
    val reason: String?,

    val destinationParts: List<ReportPaymentPart>
) : ReportRow {

    override val activityType: ReportActivityType =
        ReportActivityType.REFUND

    init {
        require(stableId.isNotBlank())
        require(operationId.isNotBlank())
        require(transactionId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(refundEventId.isNotBlank())
        require(amount.amount > 0L)
    }
}

/**
 * Expense amount remains positive in the row.
 *
 * Expense outflow meaning is applied by the Reporting aggregation layer from
 * authoritative Expense/settlement evidence.
 */
data class ExpenseReportRow(
    override val stableId: String,
    override val operationId: String,
    override val transactionId: String? = null,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson?,

    val expenseId: String,
    val category: String,
    val amount: MoneyMinorUnits,

    val paymentCategory: PaymentCategory,
    val paymentChannel: PaymentChannel,

    val note: String?,
    val supportingDocumentCount: Int?
) : ReportRow {

    override val activityType: ReportActivityType =
        ReportActivityType.EXPENSE

    init {
        require(stableId.isNotBlank())
        require(operationId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(expenseId.isNotBlank())
        require(category.isNotBlank())
        require(amount.amount > 0L)
        require(
            supportingDocumentCount == null ||
                supportingDocumentCount >= 0
        )
    }
}

/**
 * Void event.
 *
 * affectedAmount is the positive amount actually reversed by this void.
 * It must later come from authoritative reversal evidence rather than simply
 * copying the original sale total.
 */
data class VoidReportRow(
    override val stableId: String,
    override val operationId: String,
    override val transactionId: String,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson?,

    val eventId: String,
    val affectedAmount: MoneyMinorUnits,
    val reason: String?
) : ReportRow {

    override val activityType: ReportActivityType =
        ReportActivityType.VOID

    init {
        require(stableId.isNotBlank())
        require(operationId.isNotBlank())
        require(transactionId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(eventId.isNotBlank())
        require(affectedAmount.amount >= 0L)
    }
}

data class AmountCorrectionReportRow(
    override val stableId: String,
    override val operationId: String,
    override val transactionId: String,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson?,

    val eventId: String,

    val previousAmount: MoneyMinorUnits,
    val correctedAmount: MoneyMinorUnits,

    val reason: String?
) : ReportRow {

    override val activityType: ReportActivityType =
        ReportActivityType.AMOUNT_CORRECTION

    init {
        require(stableId.isNotBlank())
        require(operationId.isNotBlank())
        require(transactionId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(eventId.isNotBlank())
        require(previousAmount.amount >= 0L)
        require(correctedAmount.amount >= 0L)
    }

    /**
     * Exact signed period effect:
     *
     * corrected - previous
     */
    val signedDifference: MoneyMinorUnits
        get() = correctedAmount - previousAmount
}

data class PaymentMethodChangeReportRow(
    override val stableId: String,
    override val operationId: String,
    override val transactionId: String,
    override val reference: String?,
    override val occurredAt: Long,
    override val currencyCode: String,

    override val branch: ReportBranch,
    override val cashier: ReportCashier,
    override val person: ReportPerson?,

    val eventId: String,

    val previousCategory: PaymentCategory,
    val previousChannel: PaymentChannel?,

    val newCategory: PaymentCategory,
    val newChannel: PaymentChannel?,

    val reason: String?
) : ReportRow {

    override val activityType: ReportActivityType =
        ReportActivityType.PAYMENT_METHOD_CHANGE

    init {
        require(stableId.isNotBlank())
        require(operationId.isNotBlank())
        require(transactionId.isNotBlank())
        require(currencyCode.isNotBlank())
        require(eventId.isNotBlank())
    }
}

/**
 * Summary explicitly separates financial concepts.
 *
 * periodNetSalesActivity is NOT the current lifetime net of sales originally
 * created during this period.
 *
 * It represents authoritative sale/lifecycle economic effects whose own
 * occurredAt timestamps fall inside this reporting period.
 */
data class ReportingSummary(
    val periodSaleInflow: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val periodRefundEffect: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val periodVoidEffect: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val periodCorrectionEffect: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val periodNetSalesActivity: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val cashSalesCollected: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val onlineSalesCollected: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val couponSalesCollected: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val creditSalesOriginated: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val cashCreditRepayments: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val onlineCreditRepayments: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val couponCreditRepayments: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    /**
     * Current outstanding is a point-in-time receivable balance and therefore
     * semantically different from period activity.
     */
    val creditOutstanding: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val couponPurchased: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val couponToppedUp: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val couponRedeemed: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val couponRefunded: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val cashRefunded: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val onlineRefunded: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val cashExpenses: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val onlineExpenses: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val totalExpenses: MoneyMinorUnits = MoneyMinorUnits.ZERO,

    val openingCash: MoneyMinorUnits? = null,
    val expectedCash: MoneyMinorUnits? = null,

    val counts: ReportingActivityCounts =
        ReportingActivityCounts()
)

data class ReportingActivityCounts(
    val sales: Int = 0,
    val creditRepayments: Int = 0,
    val couponEvents: Int = 0,
    val refunds: Int = 0,
    val expenses: Int = 0,
    val voids: Int = 0,
    val corrections: Int = 0,
    val paymentMethodChanges: Int = 0
) {
    init {
        require(sales >= 0)
        require(creditRepayments >= 0)
        require(couponEvents >= 0)
        require(refunds >= 0)
        require(expenses >= 0)
        require(voids >= 0)
        require(corrections >= 0)
        require(paymentMethodChanges >= 0)
    }
}

data class ReportingReadModel(
    val scope: ReportingScope,
    val summary: ReportingSummary,
    val rows: List<ReportRow>,
    val reconciliation: ReportingReconciliation = ReportingReconciliation.unverified()
)

enum class ReportPaymentFilter {
    ALL,
    CASH,
    ONLINE,
    CREDIT,
    COUPON
}

enum class ReportActivityFilter {
    ALL,
    SALES,
    CREDIT,
    CREDIT_REPAYMENTS,
    COUPON,
    REFUNDS,
    EXPENSES,
    VOIDS,
    CORRECTIONS,
    PAYMENT_METHOD_CHANGES
}

data class ReportingFilter(
    val query: String = "",
    val activity: ReportActivityFilter =
        ReportActivityFilter.ALL,
    val payment: ReportPaymentFilter =
        ReportPaymentFilter.ALL,
    val branchId: String? = null,
    val cashierUserId: String? = null
) {
    val normalizedQuery: String
        get() = query.trim()
}