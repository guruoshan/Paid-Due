package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.RecentFinancialActivity

/**
 * Read-only presentation eligibility derived from the authoritative
 * financial-integrity validators for the same persisted transaction lifetime.
 *
 * These booleans are not permission checks and are not a financial source of
 * truth. Command execution must still run the authoritative validators.
 */
data class TransactionActionEligibility(
    val canCorrectAmount: Boolean = false,
    val canRefund: Boolean = false,
    val canVoid: Boolean = false,
    val canChangePaymentMethod: Boolean = false
)

/**
 * Read-only aggregate for Transaction Details.
 *
 * BOARDLOCK:
 * - This is not an independent financial source of truth.
 * - Financial meaning remains owned by FinancialTransactionView and the
 *   authoritative persisted lifecycle/settlement records.
 * - Raw lifecycle records are passed through from TransactionFinancialRecord;
 *   they are not reconstructed or recalculated here.
 * - No balances are stored or derived independently.
 * - No financial writes originate from this model.
 */
data class TransactionDetailsReadModel(
    val financial: FinancialTransactionView,

    /**
     * Existing summarized activity projection.
     *
     * Retained temporarily for the current UI while Transaction Details moves
     * to the complete persisted lifetime timeline below.
     */
    val activities: List<RecentFinancialActivity>,

    /**
     * Immutable item snapshots belonging to the original transaction.
     */
    val items: List<TransactionItemEntity>,

    /**
     * Credit repayment evidence linked to this transaction.
     */
    val creditRepaymentAllocations: List<CreditRepaymentAllocationEntity>,
    val creditRepayments: List<CreditRepaymentEntity>,

    /**
     * Authoritative persisted lifetime evidence from TransactionFinancialRecord.
     *
     * These collections are not new financial state. They are the same Room
     * relation records already used by the authoritative financial resolver.
     */
    val amountCorrections: List<AmountCorrectionEventEntity>,
    val paymentMethodChanges: List<PaymentMethodChangeEventEntity>,
    val voidEvents: List<VoidEventEntity>,
    val refundEvents: List<RefundEventEntity>,
    val settlements: List<SettlementEntryEntity>,
    val actionEligibility: TransactionActionEligibility =
        TransactionActionEligibility()
)