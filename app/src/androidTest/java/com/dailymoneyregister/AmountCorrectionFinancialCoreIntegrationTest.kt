package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CreditLedgerType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.startup.StartupRecoveryCoordinator
import com.dailymoneyregister.feature.register.data.AmountCorrectionBelowRefundedAmountException
import com.dailymoneyregister.feature.register.data.AmountCorrectionCommand
import com.dailymoneyregister.feature.register.data.AmountCorrectionFinancialIntegrity
import com.dailymoneyregister.feature.register.data.AmountCorrectionFinancialPrecondition
import com.dailymoneyregister.feature.register.data.RegisterRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BOARDLOCK Amount Correction financial integration tests.
 *
 * The original Sale is a controlled prerequisite fixture inserted into an
 * isolated in-memory Room database.
 *
 * Amount Corrections themselves are always created through the REAL production
 * RegisterRepository.executeAmountCorrection() writer or its recovery path.
 *
 * No production/shared financial database is opened by this test.
 */
@RunWith(AndroidJUnit4::class)
class AmountCorrectionFinancialCoreIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository

    private val businessId =
        "amount-correction-test-business"

    private val branchId =
        "amount-correction-test-branch"

    private val cashierActorId = "amount-correction-test-user"
    private var actorId = cashierActorId
    private val adminActorId = "amount-correction-test-admin"

    private val deviceId =
        "amount-correction-test-device"

    @Before
    fun setUp() = runBlocking {
        actorId = cashierActorId

        context =
            ApplicationProvider.getApplicationContext()

        database =
            DmrDatabase.createInMemory(
                context
            )

        repository =
            RegisterRepository(
                database,
                CommandExecutor(database)
            )

        val now =
            System.currentTimeMillis()

        database.businessBranchDao()
            .insertBusiness(
                BusinessEntity(
                    id =
                        businessId,
                    name =
                        "Amount Correction Test Business",
                    createdAt =
                        now,
                    updatedAt =
                        now
                )
            )

        database.businessBranchDao()
            .insertBranch(
                BranchEntity(
                    id =
                        branchId,
                    businessId =
                        businessId,
                    name =
                        "Amount Correction Test Branch",
                    code =
                        "CORR",
                    countryCode =
                        "OM",
                    currencyCode =
                        "OMR",
                    currencyDecimalPlaces =
                        3,
                    currencySymbol =
                        "OMR",
                    timezoneId =
                        "Asia/Muscat",
                    createdAt =
                        now,
                    updatedAt =
                        now
                )
            )

        database.userDao()
            .insertUser(
                UserAccountEntity(
                    id =
                        actorId,
                    businessId =
                        businessId,
                    displayName =
                        "Amount Correction Cashier",
                    username =
                        "amount_correction_cashier",
                    role =
                        UserRole.CASHIER,
                    authType =
                        AuthType.PIN,
                    credentialHash =
                        "hash",
                    credentialSalt =
                        "salt",
                    createdAt =
                        now,
                    updatedAt =
                        now
                )
            )

        database.userDao()
            .insertMembership(
                UserBranchMembershipEntity(
                    id =
                        "amount-correction-membership",
                    userId =
                        actorId,
                    branchId =
                        branchId,
                    role =
                        UserRole.CASHIER,
                    canCreateSale =
                        true,
                    canAcceptCash =
                        true,
                    canAcceptOnline =
                        true,
                    canCorrectAmount =
                        true,
                    createdAt =
                        now
                )
            )


        database.userDao()
            .insertUser(
                UserAccountEntity(
                    id = adminActorId,
                    businessId = businessId,
                    displayName = "Amount Correction Admin",
                    username = "amount_correction_admin",
                    role = UserRole.ADMIN,
                    authType = AuthType.PIN,
                    credentialHash = "hash",
                    credentialSalt = "salt",
                    createdAt = now,
                    updatedAt = now
                )
            )

        database.userDao()
            .insertMembership(
                UserBranchMembershipEntity(
                    id = "amount-correction-admin-membership",
                    userId = adminActorId,
                    branchId = branchId,
                    role = UserRole.ADMIN,
                    canCreateSale = true,
                    canAcceptCash = true,
                    canAcceptOnline = true,
                    canCorrectAmount = true,
                    createdAt = now
                )
            )

        val business =
            checkNotNull(
                database.businessBranchDao()
                    .getBusinessById(
                        businessId
                    )
            )

        val branch =
            checkNotNull(
                database.businessBranchDao()
                    .getBranchById(
                        branchId
                    )
            )

        val actor =
            checkNotNull(
                database.userDao()
                    .getUserById(
                        actorId
                    )
            )

        val membership =
            checkNotNull(
                database.userDao()
                    .getMembership(
                        actorId,
                        branchId
                    )
            )

        SessionManager.setSession(
            ActiveSession(
                business =
                    business,
                branch =
                    branch,
                user =
                    actor,
                membership =
                    membership
            )
        )
    }

    @After
    fun tearDown() {

        SessionManager.clearSession()

        database.close()
    }

    @Test
    fun productionCreditCorrection_updatesDebtWithSignedAdjustment_andReplaysExactlyOnce() =
        runBlocking {
            useAdminActor()
            val transaction = seedCreditSale(
                transactionId = "credit-correction-ledger",
                amountMinor = 10_000L
            )
            val command = newCommand(
                transactionId = transaction.id,
                correctedAmountMinor = 12_000L,
                occurredAt = transaction.createdAt + 1_000L
            )

            val first = repository.executeAmountCorrection(command)
            assertTrue(first is CommandResult.Success)
            assertEquals(
                12_000L,
                database.ledgerDao().getCreditBalance(
                    checkNotNull(transaction.customerId),
                    transaction.currencyCode
                )
            )

            val adjustment = database.ledgerDao()
                .getCreditEventsForOperations(listOf(command.operationId.value))
                .single()
            assertEquals(CreditLedgerType.ADJUSTMENT, adjustment.type)
            assertEquals(2_000L, adjustment.amountSignedMinor)
            assertEquals(transaction.id, adjustment.transactionId)

            val correctionSettlement = correctionSettlements(transaction.id).single()
            assertEquals(PaymentCategory.CREDIT, correctionSettlement.category)
            assertEquals(PaymentChannel.CREDIT_ACCOUNT, correctionSettlement.channel)
            assertEquals(2_000L, correctionSettlement.amountSignedMinor)

            val replay = repository.executeAmountCorrection(command)
            assertTrue(replay is CommandResult.Success)
            replay as CommandResult.Success
            assertTrue(replay.isIdempotentReplay)
            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditEventsForOperations(listOf(command.operationId.value))
                    .size
            )
            assertEquals(
                1,
                correctionSettlements(transaction.id).size
            )

            val decrease = repository.executeAmountCorrection(
                newCommand(
                    transactionId = transaction.id,
                    correctedAmountMinor = 11_000L,
                    occurredAt = transaction.createdAt + 2_000L
                )
            )
            assertTrue(decrease is CommandResult.Success)
            assertEquals(
                11_000L,
                database.ledgerDao().getCreditBalance(
                    checkNotNull(transaction.customerId),
                    transaction.currencyCode
                )
            )
            assertEquals(
                listOf(2_000L, -1_000L),
                database.ledgerDao()
                    .getCreditLedgerForCustomer(checkNotNull(transaction.customerId))
                    .filter { it.type == CreditLedgerType.ADJUSTMENT }
                    .map { it.amountSignedMinor }
            )
        }

    @Test
    fun productionCorrection_increase_writesOnlyPositiveDelta_andPreservesOriginal() =
        runBlocking {
            useAdminActor()

            val transaction =
                seedCashSale(
                    transactionId =
                        "correction-increase",
                    amountMinor =
                        10_000L
                )

            val result =
                repository.executeAmountCorrection(
                    newCommand(
                        transactionId =
                            transaction.id,
                        correctedAmountMinor =
                            12_000L,
                        occurredAt =
                            transaction.createdAt + 1_000L
                    )
                )

            assertTrue(
                result is CommandResult.Success
            )

            val originalAfter =
                checkNotNull(
                    database.transactionDao()
                        .getTransactionById(
                            transaction.id
                        )
                )

            assertEquals(
                10_000L,
                originalAfter.grandTotalMinor
            )

            assertEquals(
                transaction.id,
                originalAfter.id
            )

            val events =
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )

            assertEquals(
                1,
                events.size
            )

            assertEquals(
                10_000L,
                events.single()
                    .previousAmountMinor
            )

            assertEquals(
                12_000L,
                events.single()
                    .correctedAmountMinor
            )

            val deltas =
                correctionSettlements(
                    transaction.id
                )

            assertEquals(
                1,
                deltas.size
            )

            assertEquals(
                2_000L,
                deltas.single()
                    .amountSignedMinor
            )

            val view =
                FinancialReadModelResolver.resolve(
                    checkNotNull(
                        database.transactionDao()
                            .getFinancialRecordById(
                                transaction.id
                            )
                    )
                )

            assertEquals(
                10_000L,
                view.originalAmount.amount
            )

            assertEquals(
                12_000L,
                view.currentAmount.amount
            )
        }

    @Test
    fun productionCorrection_decrease_writesOnlyNegativeDelta() =
        runBlocking {
            useAdminActor()

            val transaction =
                seedCashSale(
                    transactionId =
                        "correction-decrease",
                    amountMinor =
                        10_000L
                )

            val result =
                repository.executeAmountCorrection(
                    newCommand(
                        transactionId =
                            transaction.id,
                        correctedAmountMinor =
                            8_000L,
                        occurredAt =
                            transaction.createdAt + 1_000L
                    )
                )

            assertTrue(
                result is CommandResult.Success
            )

            assertEquals(
                10_000L,
                database.transactionDao()
                    .getTransactionById(
                        transaction.id
                    )
                    ?.grandTotalMinor
            )

            assertEquals(
                -2_000L,
                correctionSettlements(
                    transaction.id
                )
                    .single()
                    .amountSignedMinor
            )
        }

    @Test
    fun productionCorrection_chain_isAppendOnlyAndInternallyConsistent() =
        runBlocking {
            useAdminActor()

            val transaction =
                seedCashSale(
                    transactionId =
                        "correction-chain",
                    amountMinor =
                        10_000L
                )

            val first =
                repository.executeAmountCorrection(
                    newCommand(
                        transactionId =
                            transaction.id,
                        correctedAmountMinor =
                            12_000L,
                        occurredAt =
                            transaction.createdAt + 1_000L
                    )
                )

            assertTrue(
                first is CommandResult.Success
            )

            val second =
                repository.executeAmountCorrection(
                    newCommand(
                        transactionId =
                            transaction.id,
                        correctedAmountMinor =
                            11_000L,
                        occurredAt =
                            transaction.createdAt + 2_000L
                    )
                )

            assertTrue(
                second is CommandResult.Success
            )

            val events =
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )

            assertEquals(
                2,
                events.size
            )

            assertEquals(
                10_000L,
                events[0].previousAmountMinor
            )

            assertEquals(
                12_000L,
                events[0].correctedAmountMinor
            )

            assertEquals(
                12_000L,
                events[1].previousAmountMinor
            )

            assertEquals(
                11_000L,
                events[1].correctedAmountMinor
            )

            val deltas =
                correctionSettlements(
                    transaction.id
                )

            assertEquals(
                listOf(
                    2_000L,
                    -1_000L
                ),
                deltas
                    .sortedBy {
                        it.occurredAt
                    }
                    .map {
                        it.amountSignedMinor
                    }
            )

            assertEquals(
                10_000L,
                database.transactionDao()
                    .getTransactionById(
                        transaction.id
                    )
                    ?.grandTotalMinor
            )

            val view =
                FinancialReadModelResolver.resolve(
                    checkNotNull(
                        database.transactionDao()
                            .getFinancialRecordById(
                                transaction.id
                            )
                    )
                )

            assertEquals(
                11_000L,
                view.currentAmount.amount
            )
        }

    @Test
    fun productionCorrection_sameOperationRetry_replaysWithoutDuplicateMoney() =
        runBlocking {
            useAdminActor()

            val transaction =
                seedCashSale(
                    transactionId =
                        "correction-replay",
                    amountMinor =
                        10_000L
                )

            val command =
                newCommand(
                    transactionId =
                        transaction.id,
                    correctedAmountMinor =
                        12_000L,
                    occurredAt =
                        transaction.createdAt + 1_000L
                )

            val first =
                repository.executeAmountCorrection(
                    command
                )

            val second =
                repository.executeAmountCorrection(
                    command
                )

            assertTrue(
                first is CommandResult.Success
            )

            assertTrue(
                second is CommandResult.Success
            )

            second as CommandResult.Success

            assertTrue(
                second.isIdempotentReplay
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )
                    .size
            )

            assertEquals(
                1,
                correctionSettlements(
                    transaction.id
                ).size
            )

            assertEquals(
                2_000L,
                correctionSettlements(
                    transaction.id
                )
                    .single()
                    .amountSignedMinor
            )
        }

    @Test
    fun productionCorrection_sameOperationChangedIntent_conflictsWithoutDuplicateMoney() =
        runBlocking {
            useAdminActor()

            val transaction =
                seedCashSale(
                    transactionId =
                        "correction-conflict",
                    amountMinor =
                        10_000L
                )

            val operationId =
                OperationId.generate()

            val firstCommand =
                newCommand(
                    transactionId =
                        transaction.id,
                    correctedAmountMinor =
                        12_000L,
                    occurredAt =
                        transaction.createdAt + 1_000L,
                    operationId =
                        operationId
                )

            val first =
                repository.executeAmountCorrection(
                    firstCommand
                )

            assertTrue(
                first is CommandResult.Success
            )

            val changedIntent =
                firstCommand.copy(
                    correctedAmountMinor =
                        13_000L
                )

            val second =
                repository.executeAmountCorrection(
                    changedIntent
                )

            assertTrue(
                second is CommandResult.Conflict
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )
                    .size
            )

            assertEquals(
                1,
                correctionSettlements(
                    transaction.id
                ).size
            )
        }

    @Test
    fun productionCorrection_belowAlreadyRefundedValue_failsClosed() =
        runBlocking {
            useAdminActor()

            val transaction =
                seedCashSale(
                    transactionId =
                        "correction-below-refund",
                    amountMinor =
                        10_000L
                )

            seedRefund(
                transactionId =
                    transaction.id,
                refundAmountMinor =
                    4_000L,
                occurredAt =
                    transaction.createdAt + 1_000L
            )

            val result =
                repository.executeAmountCorrection(
                    newCommand(
                        transactionId =
                            transaction.id,
                        correctedAmountMinor =
                            3_000L,
                        occurredAt =
                            transaction.createdAt + 2_000L
                    )
                )

            assertTrue(
                result is CommandResult.Error
            )

            result as CommandResult.Error

            assertTrue(
                result.exception
                    is AmountCorrectionBelowRefundedAmountException
            )

            assertTrue(
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )
                    .isEmpty()
            )

            assertTrue(
                correctionSettlements(
                    transaction.id
                ).isEmpty()
            )

            assertEquals(
                10_000L,
                database.transactionDao()
                    .getTransactionById(
                        transaction.id
                    )
                    ?.grandTotalMinor
            )
        }

    @Test
    fun startupRecovery_completesAdmittedPendingCorrection_exactlyOnce() =
        runBlocking {
            useAdminActor()

            val transaction =
                seedCashSale(
                    transactionId =
                        "correction-startup-recovery",
                    amountMinor =
                        10_000L
                )

            val requested =
                newCommand(
                    transactionId =
                        transaction.id,
                    correctedAmountMinor =
                        12_000L,
                    occurredAt =
                        transaction.createdAt + 1_000L
                )

            val admitted =
                insertPendingAdmittedCorrection(
                    requested
                )

            assertEquals(
                OperationStatus.PENDING,
                database.operationDao()
                    .getOperationById(
                        admitted.operationId.value
                    )
                    ?.status
            )

            assertTrue(
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )
                    .isEmpty()
            )

            val coordinator =
                StartupRecoveryCoordinator(
                    repository
                )

            val recovery =
                coordinator
                    .recoverBeforeFinancialUse()

            assertTrue(
                recovery.isSuccess
            )

            assertEquals(
                OperationStatus.SUCCEEDED,
                database.operationDao()
                    .getOperationById(
                        admitted.operationId.value
                    )
                    ?.status
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )
                    .size
            )

            assertEquals(
                1,
                correctionSettlements(
                    transaction.id
                ).size
            )

            assertEquals(
                2_000L,
                correctionSettlements(
                    transaction.id
                )
                    .single()
                    .amountSignedMinor
            )

            /*
             * A fresh startup coordinator simulates another startup pass.
             * Terminal SUCCEEDED state must not create another financial effect.
             */
            val secondCoordinator =
                StartupRecoveryCoordinator(
                    repository
                )

            val secondRecovery =
                secondCoordinator
                    .recoverBeforeFinancialUse()

            assertTrue(
                secondRecovery.isSuccess
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getAmountCorrections(
                        transaction.id
                    )
                    .size
            )

            assertEquals(
                1,
                correctionSettlements(
                    transaction.id
                ).size
            )
        }


    private suspend fun useAdminActor() {
        actorId = adminActorId
        val sessionBusiness = checkNotNull(
            database.businessBranchDao().getBusinessById(businessId)
        )
        val sessionBranch = checkNotNull(
            database.businessBranchDao().getBranchById(branchId)
        )
        val sessionActor = checkNotNull(
            database.userDao().getUserById(adminActorId)
        )
        val sessionMembership = checkNotNull(
            database.userDao().getMembership(adminActorId, branchId)
        )
        SessionManager.setSession(
            ActiveSession(
                business = sessionBusiness,
                branch = sessionBranch,
                user = sessionActor,
                membership = sessionMembership
            )
        )
    }

    private fun newCommand(
        transactionId: String,
        correctedAmountMinor: Long,
        occurredAt: Long,
        operationId: OperationId =
            OperationId.generate()
    ): AmountCorrectionCommand {

        return AmountCorrectionCommand(
            operationId =
                operationId,
            businessId =
                businessId,
            branchId =
                branchId,
            subjectId =
                transactionId,
            actorUserId =
                actorId,
            deviceId =
                deviceId,
            occurredAt =
                occurredAt,
            correctedAmountMinor =
                correctedAmountMinor,
            reason =
                "Amount Correction integration test"
        )
    }

    private suspend fun seedCreditSale(
        transactionId: String,
        amountMinor: Long,
        occurredAt: Long = System.currentTimeMillis() - 10_000L
    ): TransactionEntity {
        val customerId = "credit-customer-$transactionId"
        val customer = CustomerEntity(
            id = customerId,
            businessId = businessId,
            canonicalPhone = "+96891234567",
            name = "Credit Correction Customer",
            createdAt = occurredAt,
            updatedAt = occurredAt
        )
        database.customerDao().insertCustomer(customer)

        val operationId = "credit-sale-operation-$transactionId"
        insertSucceededOperation(
            id = operationId,
            operationType = OperationType.RECORD_SALE,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        val transaction = TransactionEntity(
            id = transactionId,
            branchId = branchId,
            type = TransactionType.SALE,
            customerId = customerId,
            subtotalMinor = amountMinor,
            taxRateBps = 0,
            taxableBaseMinor = amountMinor,
            taxAmountMinor = 0L,
            grandTotalMinor = amountMinor,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            createdByUserId = actorId,
            deviceId = deviceId,
            createdAt = occurredAt,
            displayRef = "CORR-$transactionId"
        )
        database.transactionDao().insertTransaction(transaction)
        database.transactionDao().insertSettlement(
            SettlementEntryEntity(
                id = "credit-sale-settlement-$transactionId",
                operationId = operationId,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transactionId,
                transactionId = transactionId,
                entryPurpose = EntryPurpose.SALE_FUNDING,
                category = PaymentCategory.CREDIT,
                channel = PaymentChannel.CREDIT_ACCOUNT,
                amountSignedMinor = amountMinor,
                currencyCode = "OMR",
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = occurredAt,
                actorUserId = actorId
            )
        )
        database.ledgerDao().insertCreditEvent(
            CreditLedgerEventEntity(
                id = "$operationId:credit",
                operationId = operationId,
                customerId = customerId,
                type = CreditLedgerType.OBLIGATION,
                amountSignedMinor = amountMinor,
                currencyCode = "OMR",
                transactionId = transactionId,
                occurredAt = occurredAt
            )
        )
        return transaction
    }

    private suspend fun seedCashSale(
        transactionId: String,
        amountMinor: Long,
        occurredAt: Long =
            System.currentTimeMillis() - 10_000L
    ): TransactionEntity {

        val operationId =
            "sale-operation-$transactionId"

        insertSucceededOperation(
            id =
                operationId,
            operationType =
                OperationType.RECORD_SALE,
            transactionId =
                transactionId,
            occurredAt =
                occurredAt
        )

        val transaction =
            TransactionEntity(
                id =
                    transactionId,
                branchId =
                    branchId,
                type =
                    TransactionType.SALE,
                subtotalMinor =
                    amountMinor,
                taxRateBps =
                    0,
                taxableBaseMinor =
                    amountMinor,
                taxAmountMinor =
                    0L,
                grandTotalMinor =
                    amountMinor,
                currencyCode =
                    "OMR",
                currencyDecimalPlaces =
                    3,
                createdByUserId =
                    actorId,
                deviceId =
                    deviceId,
                createdAt =
                    occurredAt,
                displayRef =
                    "CORR-$transactionId"
            )

        database.transactionDao()
            .insertTransaction(
                transaction
            )

        database.transactionDao()
            .insertSettlement(
                SettlementEntryEntity(
                    id =
                        "sale-settlement-$transactionId",
                    operationId =
                        operationId,
                    subjectType =
                        SubjectType.TRANSACTION,
                    subjectId =
                        transactionId,
                    transactionId =
                        transactionId,
                    entryPurpose =
                        EntryPurpose.SALE_FUNDING,
                    category =
                        PaymentCategory.CASH,
                    channel =
                        PaymentChannel.CASH_TENDER,
                    amountSignedMinor =
                        amountMinor,
                    currencyCode =
                        "OMR",
                    confirmationType =
                        ConfirmationType.CASHIER_CONFIRMED,
                    occurredAt =
                        occurredAt,
                    actorUserId =
                        actorId
                )
            )

        return transaction
    }

    private suspend fun seedRefund(
        transactionId: String,
        refundAmountMinor: Long,
        occurredAt: Long
    ) {

        val operationId =
            "refund-operation-$transactionId-$occurredAt"

        insertSucceededOperation(
            id =
                operationId,
            operationType =
                OperationType.RECORD_REFUND,
            transactionId =
                transactionId,
            occurredAt =
                occurredAt
        )

        database.transactionDao()
            .insertRefund(
                RefundEventEntity(
                    id =
                        "refund-event-$transactionId-$occurredAt",
                    transactionId =
                        transactionId,
                    operationId =
                        operationId,
                    refundAmountMinor =
                        refundAmountMinor,
                    actorUserId =
                        actorId,
                    occurredAt =
                        occurredAt
                )
            )

        database.transactionDao()
            .insertSettlement(
                SettlementEntryEntity(
                    id =
                        "refund-settlement-$transactionId-$occurredAt",
                    operationId =
                        operationId,
                    subjectType =
                        SubjectType.TRANSACTION,
                    subjectId =
                        transactionId,
                    transactionId =
                        transactionId,
                    entryPurpose =
                        EntryPurpose.REFUND_OUTFLOW,
                    category =
                        PaymentCategory.CASH,
                    channel =
                        PaymentChannel.CASH_TENDER,
                    amountSignedMinor =
                        Math.negateExact(
                            refundAmountMinor
                        ),
                    currencyCode =
                        "OMR",
                    confirmationType =
                        ConfirmationType.CASHIER_CONFIRMED,
                    occurredAt =
                        occurredAt,
                    actorUserId =
                        actorId
                )
            )
    }

    private suspend fun insertSucceededOperation(
        id: String,
        operationType: OperationType,
        transactionId: String,
        occurredAt: Long
    ) {

        database.operationDao()
            .insertOperation(
                OperationEntity(
                    id =
                        id,
                    businessId =
                        businessId,
                    branchId =
                        branchId,
                    operationType =
                        operationType,
                    subjectType =
                        SubjectType.TRANSACTION,
                    subjectId =
                        transactionId,
                    transactionId =
                        transactionId,
                    actorUserId =
                        actorId,
                    deviceId =
                        deviceId,
                    occurredAt =
                        occurredAt,
                    requestFingerprint =
                        "fixture-$id",
                    commandSchemaVersion =
                        1,
                    intentPayload =
                        "fixture-$id",
                    status =
                        OperationStatus.SUCCEEDED
                )
            )
    }

    private suspend fun insertPendingAdmittedCorrection(
        requested: AmountCorrectionCommand
    ): AmountCorrectionCommand {

        val record =
            checkNotNull(
                database.transactionDao()
                    .getFinancialRecordById(
                        requested.subjectId
                    )
            )

        val allocations =
            database.ledgerDao()
                .getCreditRepaymentAllocationsForTransaction(
                    requested.subjectId
                )

        val validated =
            AmountCorrectionFinancialIntegrity
                .validate(
                    record =
                        record,
                    creditAllocations =
                        allocations,
                    correctedAmountMinor =
                        requested.correctedAmountMinor
                )

        val admitted =
            requested.copy(
                financialPrecondition =
                    AmountCorrectionFinancialPrecondition(
                        stateFingerprint =
                            AmountCorrectionFinancialIntegrity
                                .fingerprint(
                                    transactionId =
                                        requested.subjectId,
                                    record =
                                        record,
                                    creditAllocations =
                                        allocations
                                ),
                        expectedPreviousAmountMinor =
                            validated.previousAmountMinor,
                        expectedRefundedAmountMinor =
                            validated.refundedAmountMinor,
                        expectedCurrentCategory =
                            validated.currentCategory,
                        expectedCurrentChannel =
                            validated.currentChannel,
                        expectedDeltaMinor =
                            validated.deltaMinor
                    )
            )

        database.operationDao()
            .insertOperation(
                OperationEntity(
                    id =
                        admitted.operationId.value,
                    businessId =
                        admitted.businessId,
                    branchId =
                        admitted.branchId,
                    operationType =
                        admitted.operationType,
                    subjectType =
                        admitted.subjectType,
                    subjectId =
                        admitted.subjectId,
                    transactionId =
                        admitted.transactionId,
                    actorUserId =
                        admitted.actorUserId,
                    deviceId =
                        admitted.deviceId,
                    occurredAt =
                        admitted.occurredAt,
                    requestFingerprint =
                        admitted.requestFingerprint,
                    commandSchemaVersion =
                        admitted.commandSchemaVersion,
                    intentPayload =
                        admitted.intentPayload,
                    status =
                        OperationStatus.PENDING
                )
            )

        return admitted
    }

    private suspend fun correctionSettlements(
        transactionId: String
    ): List<SettlementEntryEntity> {

        return database.transactionDao()
            .getSettlementsForTransaction(
                transactionId
            )
            .filter {
                it.entryPurpose ==
                    EntryPurpose.FUNDING_CORRECTION
            }
    }
}