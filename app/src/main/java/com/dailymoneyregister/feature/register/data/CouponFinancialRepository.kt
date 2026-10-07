package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandRequiresReconciliationException
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.SessionManager

/**
 * BOARDLOCK RegisterRepository decomposition.
 *
 * Phase 1:
 * - Coupon customer/wallet lookup.
 *
 * Phase 2A:
 * - Coupon Top Up financial execution.
 * - Coupon Top Up evidence verification.
 *
 * RegisterRepository remains the compatibility facade.
 *
 * No schema, migration, command payload, operation identity,
 * request fingerprint or financial calculation is redefined here.
 */
internal class CouponFinancialRepository(
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
        suspend (OperationCommand) -> String
) {
    private val transactionDao = database.transactionDao()
    private val ledgerDao = database.ledgerDao()

    private val saleFinancialWriter =
        SaleFinancialWriter(transactionDao)

    suspend fun searchCouponSaleCustomers(
        query: String
    ): List<CouponSaleCustomerWallet> {
        val session =
            SessionManager.currentSession.value
                ?: return emptyList()

        return database.customerDao()
            .searchCustomers(
                session.business.id,
                query.trim()
            )
            .map { customer ->
                val wallet =
                    ledgerDao.getCouponWallet(
                        customer.id,
                        session.branch.currencyCode
                    )

                CouponSaleCustomerWallet(
                    customer = customer,
                    wallet = wallet,
                    balanceMinor =
                        wallet?.let {
                            ledgerDao.getCouponBalance(it.id)
                        } ?: 0L
                )
            }
    }

    suspend fun executeCouponTopUp(
        command: CouponTopUpCommand
    ): CommandResult<CouponTopUpResult> {
        return try {
            command.validate()

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
                        "Coupon Top Up operation already belongs to a different request"
                    )
                }

                return executeAdmittedCouponTopUp(
                    CouponTopUpPayloadCodec.decode(existing)
                )
            }

            for (
                action in listOf(
                    PermissionAction.USE_COUPON,
                    if (
                        command.fundingCategory ==
                            PaymentCategory.CASH
                    ) {
                        PermissionAction.ACCEPT_CASH
                    } else {
                        PermissionAction.ACCEPT_ONLINE
                    }
                )
            ) {
                authorizeFinancialMutation(
                    command.actorUserId,
                    command.businessId,
                    command.branchId,
                    action,
                    "Coupon Top Up"
                )?.let {
                    return it
                }
            }

            executeAdmittedCouponTopUp(command)

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {
            throw e
        } catch (e: Exception) {
            CommandResult.Error(e)
        }
    }

    internal suspend fun executeAdmittedCouponTopUp(
        command: CouponTopUpCommand,
        recovering: Boolean = false
    ): CommandResult<CouponTopUpResult> =
        commandExecutor.execute(
            command = command,
            cashShiftId = resolveFinancialCashShiftId(command),
            replayResolver = {
                loadCouponTopUpEvidence(command)
            }
        ) {
            try {
                val branch =
                    checkNotNull(
                        database.businessBranchDao()
                            .getBranchById(command.branchId)
                    )

                require(
                    branch.businessId == command.businessId &&
                        branch.currencyCode ==
                            command.currencyCode &&
                        branch.currencyDecimalPlaces ==
                            command.currencyDecimalPlaces
                ) {
                    "Coupon Top Up branch/currency mismatch"
                }

                val customer =
                    checkNotNull(
                        database.customerDao()
                            .getCustomerById(command.customerId)
                    ) {
                        "Coupon Top Up customer does not exist"
                    }

                require(
                    customer.businessId == command.businessId &&
                        customer.name == command.customerName &&
                        customer.canonicalPhone ==
                            command.canonicalPhone
                ) {
                    "Customer identity changed"
                }

                val wallet =
                    checkNotNull(
                        ledgerDao.getCouponWallet(
                            command.customerId,
                            command.currencyCode
                        )
                    ) {
                        "Coupon Top Up requires an existing coupon wallet"
                    }

                require(
                    wallet.id == command.walletId &&
                        wallet.isActive
                ) {
                    "Coupon wallet is inactive or mismatched"
                }

                Math.addExact(
                    ledgerDao.getCouponBalance(wallet.id),
                    command.amountMinor
                )

                require(
                    transactionDao.getTransactionById(
                        command.subjectId
                    ) == null
                ) {
                    throw CommandRequiresReconciliationException(
                        "Coupon Top Up transaction already exists"
                    )
                }

                require(
                    transactionDao
                        .getSettlementsForCashSaleRecovery(
                            command.operationId.value,
                            command.subjectId
                        )
                        .isEmpty()
                ) {
                    throw CommandRequiresReconciliationException(
                        "Partial Coupon Top Up financial evidence"
                    )
                }

                saleFinancialWriter.materialize(
                    command.writerIntent()
                )

                ledgerDao.insertCouponEvent(
                    com.dailymoneyregister.core.database.entity
                        .CouponLedgerEventEntity(
                            id =
                                couponTopUpEvidenceId(
                                    command,
                                    "ledger"
                                ),
                            operationId =
                                command.operationId.value,
                            walletId =
                                wallet.id,
                            type =
                                com.dailymoneyregister.core.model.enums
                                    .CouponLedgerType.PURCHASE,
                            amountSignedMinor =
                                command.amountMinor,
                            currencyCode =
                                command.currencyCode,
                            transactionId =
                                command.subjectId,
                            occurredAt =
                                command.occurredAt
                        )
                )

                loadCouponTopUpEvidence(command) to
                    ("TRANSACTION" to command.subjectId)

            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {
                throw e
            } catch (
                e: CommandRequiresReconciliationException
            ) {
                throw e
            } catch (e: Exception) {
                if (recovering) {
                    throw CommandRequiresReconciliationException(
                        "Coupon Top Up recovery evidence mismatch: ${e.message}"
                    )
                }

                throw e
            }
        }

    private fun couponTopUpEvidenceId(
        command: CouponTopUpCommand,
        suffix: String
    ): String =
        java.util.UUID.nameUUIDFromBytes(
            "coupon-topup:${command.operationId.value}:$suffix"
                .toByteArray(Charsets.UTF_8)
        ).toString()

    private suspend fun loadCouponTopUpEvidence(
        command: CouponTopUpCommand
    ): CouponTopUpResult {
        val transaction =
            saleFinancialWriter.loadVerified(
                command.writerIntent()
            )

        val wallet =
            checkNotNull(
                ledgerDao.getCouponWallet(
                    command.customerId,
                    command.currencyCode
                )
            )

        val expected =
            com.dailymoneyregister.core.database.entity
                .CouponLedgerEventEntity(
                    id =
                        couponTopUpEvidenceId(
                            command,
                            "ledger"
                        ),
                    operationId =
                        command.operationId.value,
                    walletId =
                        wallet.id,
                    type =
                        com.dailymoneyregister.core.model.enums
                            .CouponLedgerType.PURCHASE,
                    amountSignedMinor =
                        command.amountMinor,
                    currencyCode =
                        command.currencyCode,
                    transactionId =
                        command.subjectId,
                    occurredAt =
                        command.occurredAt
                )

        check(
            ledgerDao.getCouponLedgerForWallet(wallet.id)
                .filter {
                    it.operationId ==
                        command.operationId.value
                } == listOf(expected)
        ) {
            "Coupon Top Up ledger evidence mismatch"
        }

        return CouponTopUpResult(
            transaction,
            wallet.id,
            command.amountMinor
        )
    }
}