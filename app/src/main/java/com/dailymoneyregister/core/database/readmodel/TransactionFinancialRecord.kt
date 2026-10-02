package com.dailymoneyregister.core.database.readmodel

import androidx.room.Embedded
import androidx.room.Relation
import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity

/**
 * Raw authoritative financial history for one transaction.
 *
 * The original transaction is immutable. All later financial changes
 * are loaded as append-only related records and interpreted by the
 * financial read-model resolver.
 */
data class TransactionFinancialRecord(
    @Embedded
    val transaction: TransactionEntity,

    @Relation(
        parentColumn = "customer_id",
        entityColumn = "id"
    )
    val customer: CustomerEntity? = null,

    @Relation(
        parentColumn = "id",
        entityColumn = "transaction_id"
    )
    val amountCorrections: List<AmountCorrectionEventEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "transaction_id"
    )
    val paymentMethodChanges: List<PaymentMethodChangeEventEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "transaction_id"
    )
    val voidEvents: List<VoidEventEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "transaction_id"
    )
    val refundEvents: List<RefundEventEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "transaction_id"
    )
    val settlements: List<SettlementEntryEntity> = emptyList()
)
