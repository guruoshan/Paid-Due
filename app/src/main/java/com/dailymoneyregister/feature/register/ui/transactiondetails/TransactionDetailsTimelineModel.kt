package com.dailymoneyregister.feature.register.ui.transactiondetails

import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity


internal sealed interface TransactionTimelineItem {
    val occurredAt: Long
    val stableId: String

    data class Original(
        override val occurredAt: Long,
        override val stableId: String
    ) : TransactionTimelineItem

    data class CreditRepayment(
        val allocation: CreditRepaymentAllocationEntity,
        val repayment: CreditRepaymentEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long
            get() = repayment.occurredAt

        override val stableId: String
            get() = "credit:${repayment.id}:${allocation.id}"
    }

    data class AmountCorrection(
        val event: AmountCorrectionEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "correction:${event.id}"
    }

    data class PaymentMethodChange(
        val event: PaymentMethodChangeEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "payment:${event.id}"
    }

    data class Refund(
        val event: RefundEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "refund:${event.id}"
    }

    data class Void(
        val event: VoidEventEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "void:${event.id}"
    }

    data class Settlement(
        val event: SettlementEntryEntity
    ) : TransactionTimelineItem {
        override val occurredAt: Long get() = event.occurredAt
        override val stableId: String get() = "settlement:${event.id}"
    }
}
