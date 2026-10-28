package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.security.PermissionAction

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandRequiresReconciliationException
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.feature.customers.data.CustomerIdentity
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.enums.UserRole

internal class ExpenseFinancialRepository(
    private val database: DmrDatabase,
    private val commandExecutor: CommandExecutor,
    private val authorizeFinancialMutation:
        suspend (
            actorUserId: String,
            businessId: String,
            branchId: String,
            action: PermissionAction,
            actionLabel: String
        ) -> CommandResult<Nothing>?,
    private val resolveFinancialCashShiftId:
        suspend (ExpenseCommand) -> String
) {
    private val expenseDao =
        database.expenseDao()

    private val transactionDao =
        database.transactionDao()

    suspend fun executeExpenseLifecycle(
        command: ExpenseLifecycleCommand
    ): CommandResult<com.dailymoneyregister.core.database.entity.ExpenseEntity> {
        return try {
            val session = SessionManager.currentSession.value
                ?: return CommandResult.PermissionDenied("An authenticated Admin session is required.")
            if (session.user.id != command.actorUserId ||
                session.business.id != command.businessId ||
                session.branch.id != command.branchId) {
                return CommandResult.PermissionDenied("Expense lifecycle identity does not match the signed-in session.")
            }
            val actor = database.userDao().getUserByIdInBusiness(command.actorUserId, command.businessId)
                ?: return CommandResult.PermissionDenied("Expense lifecycle actor is not a member of this business.")
            if (!actor.isActive || actor.role != UserRole.ADMIN) {
                return CommandResult.PermissionDenied("Only an active Admin can correct or void an expense.")
            }
            authorizeFinancialMutation(
                command.actorUserId,
                command.businessId,
                command.branchId,
                PermissionAction.ACCEPT_CASH,
                "Expense correction/void"
            )?.let { return it }
            val expense = database.expenseDao().getExpenseById(command.expenseId)
                ?: return CommandResult.Error(IllegalArgumentException("Expense does not exist."))
            if (expense.businessId != command.businessId || expense.branchId != command.branchId) {
                return CommandResult.PermissionDenied("Expense belongs to another business or branch.")
            }
            commandExecutor.execute(
                command = command,
                cashShiftId = expense.cashShiftId,
                replayResolver = {
                    database.expenseDao().getExpenseById(command.expenseId)
                        ?: error("Expense lifecycle replay references a missing expense.")
                }
            ) {
                val current = database.expenseDao().getExpenseById(command.expenseId)
                    ?: error("Expense disappeared before lifecycle event commit.")
                require(current.businessId == command.businessId && current.branchId == command.branchId) {
                    "Expense tenant identity changed before lifecycle event commit."
                }
                val events = database.expenseDao().getEventsForExpense(current.id)
                require(events.none { it.eventType.uppercase().contains("VOID") }) {
                    "A voided expense cannot be corrected or voided again."
                }
                val effectiveAmount = events
                    .asSequence()
                    .filter { !it.eventType.uppercase().contains("VOID") }
                    .sortedWith(compareBy<com.dailymoneyregister.core.database.entity.ExpenseEventEntity>({ it.occurredAt }, { it.id }))
                    .mapNotNull { it.newAmountMinor }
                    .lastOrNull() ?: current.amountMinor
                if (command.action == ExpenseLifecycleCommand.Action.CORRECT) {
                    require(effectiveAmount != command.correctedAmountMinor) {
                        "Corrected amount must differ from the current effective expense amount."
                    }
                }
                val previousAmount = effectiveAmount
                val nextAmount = command.correctedAmountMinor
                val delta = if (command.action == ExpenseLifecycleCommand.Action.CORRECT) {
                    Math.subtractExact(previousAmount, requireNotNull(nextAmount))
                } else previousAmount
                val event = com.dailymoneyregister.core.database.entity.ExpenseEventEntity(
                    id = "${command.operationId.value}:expense-event",
                    expenseId = current.id,
                    operationId = command.operationId.value,
                    eventType = if (command.action == ExpenseLifecycleCommand.Action.CORRECT) "CORRECTED" else "VOIDED",
                    actorUserId = command.actorUserId,
                    previousAmountMinor = previousAmount,
                    newAmountMinor = nextAmount,
                    previousPaymentCategory = current.paymentCategory,
                    newPaymentCategory = if (command.action == ExpenseLifecycleCommand.Action.CORRECT) current.paymentCategory else null,
                    previousCategory = current.category,
                    newCategory = if (command.action == ExpenseLifecycleCommand.Action.CORRECT) current.category else null,
                    previousPayeeId = current.payeeId,
                    newPayeeId = current.payeeId,
                    reason = command.reason,
                    occurredAt = command.occurredAt
                )
                database.expenseDao().insertExpenseEvent(event)
                val purpose = if (command.action == ExpenseLifecycleCommand.Action.CORRECT) {
                    EntryPurpose.EXPENSE_CORRECTION
                } else EntryPurpose.VOID_REVERSAL
                transactionDao.insertSettlement(
                    SettlementEntryEntity(
                        id = "${command.operationId.value}:expense-lifecycle-settlement",
                        operationId = command.operationId.value,
                        subjectType = SubjectType.EXPENSE,
                        subjectId = current.id,
                        transactionId = null,
                        entryPurpose = purpose,
                        category = com.dailymoneyregister.core.model.enums.PaymentCategory.valueOf(current.paymentCategory),
                        channel = com.dailymoneyregister.core.model.enums.PaymentChannel.valueOf(
                            if (current.paymentCategory == "CASH") "CASH_TENDER" else
                                transactionDao.getSettlementsForOperation(current.operationId).first().channel.name
                        ),
                        amountSignedMinor = delta,
                        currencyCode = current.currencyCode,
                        confirmationType = ConfirmationType.ADMIN_CONFIRMED,
                        qrProfileIdSnapshot = null,
                        occurredAt = command.occurredAt,
                        actorUserId = command.actorUserId,
                        providerRef = current.reference
                    )
                )
                val updated = database.expenseDao().getExpenseById(current.id)
                    ?: error("Expense lifecycle result could not be loaded.")
                updated to ("EXPENSE" to current.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.Error(e)
        }
    }

    suspend fun executeExpense(
        command: ExpenseCommand
    ): CommandResult<ExpenseCommandResult> {
        return try {
            val existing =
                database.operationDao()
                    .getOperationById(command.operationId.value)

            if (existing != null) {
                if (
                    existing.requestFingerprint !=
                        command.requestFingerprint ||
                    existing.intentPayload !=
                        command.intentPayload
                ) {
                    return CommandResult.Conflict(
                        "Expense operation already belongs to a different request."
                    )
                }

                return executeAdmittedExpense(
                    ExpensePayloadCodec.decode(
                        existing.intentPayload
                    )
                )
            }

            val permission =
                when (command.paymentCategory) {
                    PaymentCategory.CASH ->
                        PermissionAction.ACCEPT_CASH

                    PaymentCategory.ONLINE ->
                        PermissionAction.ACCEPT_ONLINE

                    PaymentCategory.CREDIT,
                    PaymentCategory.COUPON ->
                        return CommandResult.Error(
                            IllegalArgumentException(
                                "Expense supports Cash or Online only."
                            )
                        )
                }

            authorizeFinancialMutation(
                command.actorUserId,
                command.businessId,
                command.branchId,
                permission,
                "Expense"
            )?.let {
                return it
            }

            executeAdmittedExpense(command)

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {
            throw e
        } catch (e: Exception) {
            CommandResult.Error(e)
        }
    }

    internal suspend fun executeAdmittedExpense(
        command: ExpenseCommand
    ): CommandResult<ExpenseCommandResult> {

        val cashShiftId =
            resolveFinancialCashShiftId(command)

        return commandExecutor.execute(
            command = command,
            cashShiftId = cashShiftId,
            replayResolver = {
                loadExpenseEvidence(command)
            }
        ) {
            val branch =
                checkNotNull(
                    database.businessBranchDao()
                        .getBranchById(command.branchId)
                ) {
                    "Expense branch does not exist."
                }

            require(
                branch.businessId == command.businessId
            ) {
                "Expense branch/business mismatch."
            }

            require(
                branch.currencyCode == command.currencyCode
            ) {
                "Expense currency does not match branch currency."
            }

            val existingExpense =
                expenseDao.getExpenseByOperationId(
                    command.operationId.value
                )

            val existingSettlements =
                transactionDao.getSettlementsForOperation(
                    command.operationId.value
                )

            if (
                existingExpense != null ||
                existingSettlements.isNotEmpty()
            ) {
                throw CommandRequiresReconciliationException(
                    "Expense materialization already exists inside a pending execution. " +
                        "Recovery must resolve the persisted authoritative evidence."
                )
            }

            /*
             * BOARDLOCK — UNIVERSAL PARTY IDENTITY
             *
             * CustomerEntity is the existing authoritative identity source.
             * Expense must not create a second Expense-specific identity.
             *
             * When an Expense has a phone number:
             * - normalize it through CustomerIdentity
             * - reuse the existing CustomerEntity for this business, or
             * - create it once when the phone is new
             *
             * This runs inside the existing CommandExecutor transaction, so
             * identity creation and Expense financial materialization commit
             * together or roll back together.
             *
             * Expense snapshots remain historical evidence and are not replaced
             * by mutable Customer data.
             */
            val resolvedPayee =
                command.payeePhoneSnapshot
                    ?.takeIf { it.isNotBlank() }
                    ?.let { rawPhone ->
                        CustomerIdentity.resolveOrCreate(
                            customerDao = database.customerDao(),
                            businessId = command.businessId,
                            rawPhone = rawPhone,
                            rawName = command.payeeNameSnapshot,
                            now = command.occurredAt
                        )
                    }

            val authoritativePayeeId =
                resolvedPayee?.customer?.id
                    ?: command.payeeId

            val authoritativePayeePhoneSnapshot =
                resolvedPayee?.customer?.canonicalPhone
                    ?: command.payeePhoneSnapshot

            val expense =
                ExpenseEntity(
                    id = command.expenseId,
                    operationId =
                        command.operationId.value,
                    businessId =
                        command.businessId,
                    branchId =
                        command.branchId,
                    cashShiftId =
                        cashShiftId,
                    actorUserId =
                        command.actorUserId,
                    payeeId =
                        authoritativePayeeId,
                    payeeNameSnapshot =
                        command.payeeNameSnapshot,
                    payeePhoneSnapshot =
                        authoritativePayeePhoneSnapshot,
                    payeeAccountSnapshot =
                        command.payeeAccountSnapshot,
                    category =
                        command.category.name,
                    amountMinor =
                        command.amountMinor,
                    paymentCategory =
                        command.paymentCategory.name,
                    currencyCode =
                        command.currencyCode,
                    reference =
                        command.reference,
                    note =
                        command.note,
                    recurrenceId =
                        command.recurrenceId,
                    occurredAt =
                        command.occurredAt,
                    createdAt =
                        command.occurredAt
                )

            expenseDao.insertExpense(expense)

            val settlement =
                SettlementEntryEntity(
                    id = "${command.operationId.value}:expense-outflow",
                    operationId =
                        command.operationId.value,
                    subjectType =
                        SubjectType.EXPENSE,
                    subjectId =
                        command.expenseId,
                    transactionId =
                        null,
                    entryPurpose =
                        EntryPurpose.EXPENSE_OUTFLOW,
                    category =
                        command.paymentCategory,
                    channel =
                        command.paymentChannel,
                    amountSignedMinor =
                        Math.negateExact(
                            command.amountMinor
                        ),
                    currencyCode =
                        command.currencyCode,
                    confirmationType =
                        ConfirmationType.ADMIN_CONFIRMED,
                    qrProfileIdSnapshot =
                        null,
                    occurredAt =
                        command.occurredAt,
                    actorUserId =
                        command.actorUserId,
                    providerRef =
                        command.reference
                )

            transactionDao.insertSettlement(settlement)

            val result =
                loadExpenseEvidence(command)

            result to
                ("EXPENSE" to command.expenseId)
        }
    }

    private suspend fun loadExpenseEvidence(
        command: ExpenseCommand
    ): ExpenseCommandResult {

        val expense =
            expenseDao.getExpenseByOperationId(
                command.operationId.value
            )
                ?: throw IllegalStateException(
                    "Expense evidence is missing."
                )

        require(
            expense.id == command.expenseId &&
                expense.operationId ==
                    command.operationId.value &&
                expense.businessId ==
                    command.businessId &&
                expense.branchId ==
                    command.branchId &&
                expense.actorUserId ==
                    command.actorUserId &&
                expense.amountMinor ==
                    command.amountMinor &&
                expense.currencyCode ==
                    command.currencyCode &&
                expense.category ==
                    command.category.name &&
                expense.paymentCategory ==
                    command.paymentCategory.name &&
                expense.occurredAt ==
                    command.occurredAt
        ) {
            "Expense evidence does not match durable intent."
        }

        val shiftId =
            requireNotNull(expense.cashShiftId) {
                "Expense has no cashier shift attribution."
            }

        val settlements =
            transactionDao.getSettlementsForOperation(
                command.operationId.value
            )

        require(settlements.size == 1) {
            "Expense must have exactly one financial outflow settlement."
        }

        val settlement =
            settlements.single()

        require(
            settlement.operationId ==
                command.operationId.value &&
                settlement.subjectType ==
                    SubjectType.EXPENSE &&
                settlement.subjectId ==
                    command.expenseId &&
                settlement.transactionId == null &&
                settlement.entryPurpose ==
                    EntryPurpose.EXPENSE_OUTFLOW &&
                settlement.category ==
                    command.paymentCategory &&
                settlement.channel ==
                    command.paymentChannel &&
                settlement.amountSignedMinor ==
                    Math.negateExact(
                        command.amountMinor
                    ) &&
                settlement.currencyCode ==
                    command.currencyCode &&
                settlement.actorUserId ==
                    command.actorUserId &&
                settlement.occurredAt ==
                    command.occurredAt
        ) {
            "Expense settlement does not match durable intent."
        }

        return ExpenseCommandResult(
            expenseId =
                expense.id,
            operationId =
                expense.operationId,
            amountMinor =
                expense.amountMinor,
            category =
                ExpenseCategory.valueOf(
                    expense.category
                ),
            paymentCategory =
                command.paymentCategory,
            paymentChannel =
                command.paymentChannel,
            actorUserId =
                expense.actorUserId,
            cashShiftId =
                shiftId,
            occurredAt =
                expense.occurredAt
        )
    }
}



