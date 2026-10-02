package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.feature.register.domain.DraftState

data class OnlineSaleCommand(
    override val operationId: OperationId,
    override val businessId: String,
    override val branchId: String,
    override val actorUserId: String,
    val transactionIdVal: TransactionId,
    val draftState: DraftState,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    override val deviceId: String,
    override val occurredAt: Long = System.currentTimeMillis()
) : OperationCommand {
    override val operationType = OperationType.RECORD_SALE
    override val subjectType = SubjectType.TRANSACTION
    override val subjectId = transactionIdVal.value
    override val transactionId = transactionIdVal.value
    override val commandSchemaVersion = OnlineSalePayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() {
            val itemsDigest = draftState.items.joinToString(";") {
                "${it.id}:${it.name}:${it.quantity}:${it.unitPrice.amount}:${it.itemPackingCharge.amount}:${it.itemShippingCharge.amount}"
            }
            return "online-v1|biz:$businessId|br:$branchId|act:$actorUserId|tx:${transactionIdVal.value}|" +
                "cur:$currencyCode:$currencyDecimalPlaces|sub:${draftState.subtotal.amount}|" +
                "tax:${draftState.isTaxEnabled}:${draftState.taxRateBps}:${draftState.taxAmount.amount}|" +
                "shp:${draftState.txShipping.amount}|pck:${draftState.txPacking.amount}|" +
                "itemShp:${draftState.itemShippingTotal.amount}|itemPck:${draftState.itemPackingTotal.amount}|" +
                "gnd:${draftState.grandTotal.amount}|desc:${draftState.manualDescription ?: ""}|" +
                "items:[$itemsDigest]|time:$occurredAt"
        }

    override val intentPayload: String
        get() = OnlineSalePayloadCodec.encode(this)
}
