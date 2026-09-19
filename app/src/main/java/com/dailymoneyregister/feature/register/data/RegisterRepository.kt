package com.dailymoneyregister.feature.register.data

import kotlinx.coroutines.flow.map
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.currency.CurrencyInvariant
import androidx.room.withTransaction
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class CashSaleCommand(
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
    override val commandSchemaVersion = CashSalePayloadCodec.CURRENT_SCHEMA_VERSION

    override val requestFingerprint: String
        get() {
            val itemsDigest = draftState.items.joinToString(";") {
                "${it.id}:${it.name}:${it.quantity}:${it.unitPrice.amount}:${it.itemPackingCharge.amount}:${it.itemShippingCharge.amount}"
            }
            return "v1|biz:$businessId|br:$branchId|act:$actorUserId|tx:${transactionIdVal.value}|" +
                    "cur:$currencyCode:$currencyDecimalPlaces|sub:${draftState.subtotal.amount}|" +
                    "tax:${draftState.isTaxEnabled}:${draftState.taxRateBps}:${draftState.taxAmount.amount}|" +
                    "shp:${draftState.txShipping.amount}|pck:${draftState.txPacking.amount}|" +
                    "itemShp:${draftState.itemShippingTotal.amount}|itemPck:${draftState.itemPackingTotal.amount}|" +
                    "gnd:${draftState.grandTotal.amount}|desc:${draftState.manualDescription ?: ""}|" +
                    "items:[$itemsDigest]|time:$occurredAt"
        }

    override val intentPayload: String
        get() = CashSalePayloadCodec.encode(this)
}

class RegisterRepository @Inject constructor(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor
) {
    private val transactionDao = database.transactionDao()
    private val businessBranchDao = database.businessBranchDao()
    private val userDao = database.userDao()

    suspend fun executeCashSale(
        command: CashSaleCommand,
        actorUser: UserAccountEntity,
        actorMembership: UserBranchMembershipEntity?
    ): CommandResult<TransactionEntity> {
        // 1. BOARDLOCK: authoritative identity, branch, permission, currency and money validation.
        // Never trust only the UI/caller for financial truth.

        if (actorUser.id != command.actorUserId) {
            return CommandResult.PermissionDenied(
                "Cash sale actor does not match the authenticated user"
            )
        }

        if (SessionManager.currentSession.value?.user?.id == actorUser.id &&
            SessionManager.currentSession.value?.requiresPinChange == true
        ) {
            return CommandResult.PermissionDenied("Cashier must create a new PIN before using the register")
        }

        val authoritativeActor = userDao.getUserById(command.actorUserId)
            ?: return CommandResult.PermissionDenied(
                "Cash sale actor does not exist"
            )

        if (authoritativeActor.mustChangePin) {
            return CommandResult.PermissionDenied(
                "Cashier must create a new PIN before using the register"
            )
        }

        if (!authoritativeActor.isActive) {
            return CommandResult.PermissionDenied(
                "Inactive user cannot create a Cash sale"
            )
        }

        if (authoritativeActor.businessId != command.businessId ||
            actorUser.businessId != command.businessId
        ) {
            return CommandResult.PermissionDenied(
                "Cash sale business does not match the authenticated user"
            )
        }

        val authoritativeBranch = businessBranchDao.getBranchById(command.branchId)
            ?: return CommandResult.Error(
                IllegalArgumentException("Cash sale branch does not exist")
            )

        if (authoritativeBranch.businessId != command.businessId) {
            return CommandResult.PermissionDenied(
                "Cash sale branch does not belong to the command business"
            )
        }

        val authoritativeMembership =
            userDao.getMembership(authoritativeActor.id, authoritativeBranch.id)
                ?: return CommandResult.PermissionDenied(
                    "User is not a member of the Cash sale branch"
                )

        if (actorMembership != null &&
            (actorMembership.userId != authoritativeActor.id ||
             actorMembership.branchId != authoritativeBranch.id)
        ) {
            return CommandResult.PermissionDenied(
                "Cash sale membership does not match the authenticated user/branch"
            )
        }

        try {
            PermissionChecker.assertPermitted(
                authoritativeActor,
                authoritativeMembership,
                PermissionAction.CREATE_SALE
            )
            PermissionChecker.assertPermitted(
                authoritativeActor,
                authoritativeMembership,
                PermissionAction.ACCEPT_CASH
            )
        } catch (e: PermissionDeniedException) {
            return CommandResult.PermissionDenied(
                e.message ?: "Permission denied for Cash sale"
            )
        }

        try {
            CurrencyInvariant.assertSameCurrency(
                command.currencyCode,
                authoritativeBranch.currencyCode,
                "Cash sale"
            )
        } catch (e: IllegalStateException) {
            return CommandResult.Error(e)
        }

        if (command.currencyDecimalPlaces != authoritativeBranch.currencyDecimalPlaces) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Cash sale currency precision does not match branch configuration"
                )
            )
        }

        val validatedDraft = command.draftState

        if (validatedDraft.subtotal.amount <= 0L) {
            return CommandResult.Error(
                IllegalArgumentException("Cash sale subtotal must be greater than zero")
            )
        }

        if (validatedDraft.grandTotal.amount <= 0L) {
            return CommandResult.Error(
                IllegalArgumentException("Cash sale grand total must be greater than zero")
            )
        }

        if (validatedDraft.txShipping.amount < 0L ||
            validatedDraft.txPacking.amount < 0L ||
            validatedDraft.itemShippingTotal.amount < 0L ||
            validatedDraft.itemPackingTotal.amount < 0L
        ) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Shipping and packing charges cannot be negative"
                )
            )
        }

        if (validatedDraft.taxRateBps < 0 ||
            validatedDraft.taxAmount.amount < 0L
        ) {
            return CommandResult.Error(
                IllegalArgumentException("Tax values cannot be negative")
            )
        }

        if (validatedDraft.isTaxEnabled && validatedDraft.taxRateBps <= 0) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Enabled tax must have a positive tax rate"
                )
            )
        }

        if (!validatedDraft.isTaxEnabled &&
            validatedDraft.taxAmount.amount != 0L
        ) {
            return CommandResult.Error(
                IllegalArgumentException(
                    "Disabled tax must not create a tax amount"
                )
            )
        }

        for (item in validatedDraft.items) {
            if (item.quantity <= 0) {
                return CommandResult.Error(
                    IllegalArgumentException(
                        "Item quantity must be greater than zero"
                    )
                )
            }

            if (item.unitPrice.amount < 0L ||
                item.itemPackingCharge.amount < 0L ||
                item.itemShippingCharge.amount < 0L ||
                item.lineTotal.amount < 0L
            ) {
                return CommandResult.Error(
                    IllegalArgumentException(
                        "Item prices and charges cannot be negative"
                    )
                )
            }
        }

        // 2. Execute via universal CommandExecutor with type-safe replay resolver
        return commandExecutor.execute(
            command = command,
            replayResolver = { op ->
                val txId = op.resultReferenceId ?: command.transactionIdVal.value
                transactionDao.getTransactionById(txId)
                    ?: throw IllegalStateException("Replay failed: Transaction entity for '$txId' not found in database")
            }
        ) {
            val txId = command.transactionIdVal.value
            val draft = command.draftState
            val now = command.occurredAt

            // Generate display reference (e.g. SAL-1710500000000-123)
            val displayRef = "SAL-${now % 1_000_000_000}-${txId.takeLast(4).uppercase()}"

            val transactionEntity = TransactionEntity(
                id = txId,
                branchId = command.branchId,
                type = TransactionType.SALE,
                customerId = null,
                subtotalMinor = draft.subtotal.amount,
                taxRateBps = if (draft.isTaxEnabled) draft.taxRateBps else 0,
                taxableBaseMinor = draft.taxableBase.amount,
                taxAmountMinor = draft.taxAmount.amount,
                txShippingMinor = draft.txShipping.amount,
                txPackingMinor = draft.txPacking.amount,
                grandTotalMinor = draft.grandTotal.amount,
                currencyCode = command.currencyCode,
                currencyDecimalPlaces = command.currencyDecimalPlaces,
                createdByUserId = command.actorUserId,
                deviceId = command.deviceId,
                createdAt = now,
                description = draft.manualDescription,
                displayRef = displayRef
            )

            // Insert Transaction
            transactionDao.insertTransaction(transactionEntity)

            // Insert Items if itemized
            if (draft.isItemized) {
                val itemEntities = draft.items.map { item ->
                    TransactionItemEntity(
                        id = item.id,
                        transactionId = txId,
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
                }
                transactionDao.insertTransactionItems(itemEntities)
            }

            // Insert signed Settlement Entry (SALE_FUNDING, CASH, +grandTotal)
            val settlementEntity = SettlementEntryEntity(
                id = IdGenerator.newId(),
                operationId = command.operationId.value,
                subjectType = SubjectType.TRANSACTION,
                subjectId = txId,
                transactionId = txId,
                entryPurpose = EntryPurpose.SALE_FUNDING,
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountSignedMinor = draft.grandTotal.amount, // SIGNED positive for cash inflow
                currencyCode = command.currencyCode,
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = now,
                actorUserId = command.actorUserId
            )
            transactionDao.insertSettlement(settlementEntity)

            Pair(transactionEntity, Pair("TRANSACTION", txId))
        }
    }

    /**
     * Recovers any PENDING Cash sales interrupted by process death or crash.
     * Decodes the authoritative intent payload and commits remaining steps atomically.
     */
    suspend fun recoverPendingCashSales(): List<TransactionEntity> {
        val pendingOps = commandExecutor.getPendingOperations()
            .filter { it.operationType == OperationType.RECORD_SALE }

        val recoveredList = mutableListOf<TransactionEntity>()

        for (op in pendingOps) {
            val targetTxId = op.transactionId ?: op.subjectId
            val existingTx = transactionDao.getTransactionById(targetTxId)

            if (existingTx != null) {
                // Transaction already exists in DB, ensure operation marked SUCCEEDED
                val changedRows = database.operationDao().markSucceededIfPending(
                    operationId = op.id,
                    requestFingerprint = op.requestFingerprint,
                    resultReferenceType = "TRANSACTION",
                    resultReferenceId = existingTx.id
                )

                if (changedRows == 0) {
                    val latest = database.operationDao().getOperationById(op.id)

                    check(
                        latest?.status == OperationStatus.SUCCEEDED &&
                            latest.resultReferenceId == existingTx.id
                    ) {
                        "Pending operation '${op.id}' could not be safely reconciled with transaction '${existingTx.id}'"
                    }
                }
                recoveredList.add(existingTx)
            } else {
                // Decode command from persisted durable payload
                val recoveredCmd = CashSalePayloadCodec.decode(op)
                val txId = recoveredCmd.transactionIdVal.value
                val draft = recoveredCmd.draftState
                val now = recoveredCmd.occurredAt
                val displayRef = "SAL-${now % 1_000_000_000}-${txId.takeLast(4).uppercase()}"

                val transactionEntity = TransactionEntity(
                    id = txId,
                    branchId = recoveredCmd.branchId,
                    type = TransactionType.SALE,
                    customerId = null,
                    subtotalMinor = draft.subtotal.amount,
                    taxRateBps = if (draft.isTaxEnabled) draft.taxRateBps else 0,
                    taxableBaseMinor = draft.taxableBase.amount,
                    taxAmountMinor = draft.taxAmount.amount,
                    txShippingMinor = draft.txShipping.amount,
                    txPackingMinor = draft.txPacking.amount,
                    grandTotalMinor = draft.grandTotal.amount,
                    currencyCode = recoveredCmd.currencyCode,
                    currencyDecimalPlaces = recoveredCmd.currencyDecimalPlaces,
                    createdByUserId = recoveredCmd.actorUserId,
                    deviceId = recoveredCmd.deviceId,
                    createdAt = now,
                    description = draft.manualDescription,
                    displayRef = displayRef
                )

                database.withTransaction {
                    transactionDao.insertTransaction(transactionEntity)

                    if (draft.isItemized) {
                        val itemEntities = draft.items.map { item ->
                            TransactionItemEntity(
                                id = item.id,
                                transactionId = txId,
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
                        }
                        transactionDao.insertTransactionItems(itemEntities)
                    }

                    val settlementEntity = SettlementEntryEntity(
                        id = IdGenerator.newId(),
                        operationId = op.id,
                        subjectType = SubjectType.TRANSACTION,
                        subjectId = txId,
                        transactionId = txId,
                        entryPurpose = EntryPurpose.SALE_FUNDING,
                        category = PaymentCategory.CASH,
                        channel = PaymentChannel.CASH_TENDER,
                        amountSignedMinor = draft.grandTotal.amount,
                        currencyCode = recoveredCmd.currencyCode,
                        confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                        occurredAt = now,
                        actorUserId = recoveredCmd.actorUserId
                    )
                    transactionDao.insertSettlement(settlementEntity)

                    val changedRows = database.operationDao().markSucceededIfPending(
                        operationId = op.id,
                        requestFingerprint = op.requestFingerprint,
                        resultReferenceType = "TRANSACTION",
                        resultReferenceId = txId
                    )

                    check(changedRows == 1) {
                        "Pending operation '${op.id}' lost its PENDING state during crash recovery"
                    }
                }

                recoveredList.add(transactionEntity)
            }
        }

        return recoveredList
    }

    fun observeRecentTransactions(branchId: String, limit: Int = 20): Flow<List<TransactionEntity>> {
        return transactionDao.observeRecentTransactions(branchId, limit)
    }

    fun observeTodayTransactions(
        branchId: String,
        timezoneId: String
    ): Flow<List<FinancialTransactionView>> {

        val zoneId = try {
            ZoneId.of(timezoneId)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }

        val startOfDayMillis =
            LocalDate.now(zoneId)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()

        return transactionDao
            .observeTodayFinancialRecords(
                branchId,
                startOfDayMillis
            )
            .map { records ->
                records.map(
                    FinancialReadModelResolver::resolve
                )
            }
    }

    suspend fun getFinancialTransactionById(
        transactionId: String
    ): FinancialTransactionView? {
        return transactionDao
            .getFinancialRecordById(transactionId)
            ?.let(FinancialReadModelResolver::resolve)
    }
    suspend fun getTransactionItems(transactionId: String): List<TransactionItemEntity> {
        return transactionDao.getItemsForTransaction(transactionId)
    }
}


