package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.data.VoidAlreadyExistsException
import com.dailymoneyregister.feature.register.data.VoidAmbiguousSettlementException
import com.dailymoneyregister.feature.register.data.VoidCommand
import com.dailymoneyregister.feature.register.data.VoidCreditRepaymentExistsException
import com.dailymoneyregister.feature.register.data.VoidNothingReversibleException
import com.dailymoneyregister.feature.register.data.VoidUnsupportedCouponException
import com.dailymoneyregister.feature.register.data.VoidUnsupportedCreditException
import com.dailymoneyregister.feature.register.data.VoidFinancialIntegrity
import com.dailymoneyregister.feature.register.data.VoidFinancialPrecondition
import com.dailymoneyregister.feature.register.data.VoidPayloadCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * BOARDLOCK SCOPE:
 *
 * This class proves the REAL production Void financial writer:
 *
 *     RegisterRepository.executeVoid()
 *
 * Sale, Refund and Amount-Correction records created by helper methods in this
 * class are CONTROLLED AUTHORITATIVE DATABASE FIXTURES used only to establish
 * transaction-history scenarios for Void.
 *
 * Those fixture helpers do NOT prove that Sale, Refund or Correction production
 * writers exist or work end-to-end.
 *
 * A test proves production Void behavior only when the Void itself is created
 * by RegisterRepository.executeVoid() or recoverPendingVoids().
 *
 * The direct DAO uniqueness test is deliberately adversarial and proves only
 * database-level protection. It is not a supported financial workflow.
 */
@RunWith(AndroidJUnit4::class)
class VoidFinancialCoreIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository

    private val businessId = "void-test-business"
    private val branchId = "void-test-branch"
    private val cashierActorId = "void-authorized-user"
    private var actorId = cashierActorId
    private val adminActorId = "void-authorized-admin"
    private val unauthorizedActorId = "void-unauthorized-user"
    private val deviceId = "void-test-device"

    @Before
    fun setUp() = runBlocking {
        actorId = cashierActorId
        context = ApplicationProvider.getApplicationContext()
        database = DmrDatabase.createInMemory(context)

        repository = RegisterRepository(
            database,
            CommandExecutor(database)
        )

        val now = System.currentTimeMillis()

        database.businessBranchDao().insertBusiness(
            BusinessEntity(
                id = businessId,
                name = "Void Test Business",
                createdAt = now,
                updatedAt = now
            )
        )

        database.businessBranchDao().insertBranch(
            BranchEntity(
                id = branchId,
                businessId = businessId,
                name = "Void Test Branch",
                code = "VOID",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                createdAt = now,
                updatedAt = now
            )
        )

        database.userDao().insertUser(
            UserAccountEntity(
                id = actorId,
                businessId = businessId,
                displayName = "Void Authorized Cashier",
                username = "void_authorized",
                role = UserRole.ADMIN,
                authType = AuthType.PIN,
                credentialHash = "hash",
                credentialSalt = "salt",
                createdAt = now,
                updatedAt = now
            )
        )

        database.userDao().insertMembership(
            UserBranchMembershipEntity(
                id = "void-authorized-membership",
                userId = actorId,
                branchId = branchId,
                role = UserRole.ADMIN,
                canCreateSale = true,
                canAcceptCash = true,
                canVoid = true,
                createdAt = now
            )
        )


        database.userDao().insertUser(
            UserAccountEntity(
                id = adminActorId,
                businessId = businessId,
                displayName = "Void Admin",
                username = "void_admin",
                role = UserRole.ADMIN,
                authType = AuthType.PIN,
                credentialHash = "hash",
                credentialSalt = "salt",
                createdAt = now,
                updatedAt = now
            )
        )

        database.userDao().insertMembership(
            UserBranchMembershipEntity(
                id = "void-admin-membership",
                userId = adminActorId,
                branchId = branchId,
                role = UserRole.ADMIN,
                canCreateSale = true,
                canAcceptCash = true,
                canVoid = true,
                createdAt = now
            )
        )

        database.userDao().insertUser(
            UserAccountEntity(
                id = unauthorizedActorId,
                businessId = businessId,
                displayName = "Void Unauthorized Cashier",
                username = "void_unauthorized",
                role = UserRole.ADMIN,
                authType = AuthType.PIN,
                credentialHash = "hash",
                credentialSalt = "salt",
                createdAt = now,
                updatedAt = now
            )
        )

        database.userDao().insertMembership(
            UserBranchMembershipEntity(
                id = "void-unauthorized-membership",
                userId = unauthorizedActorId,
                branchId = branchId,
                role = UserRole.ADMIN,
                canCreateSale = true,
                canAcceptCash = true,
                canVoid = false,
                createdAt = now
            )
        )

        val sessionBusiness =
            checkNotNull(
                database.businessBranchDao()
                    .getBusinessById(businessId)
            )
        val sessionBranch =
            checkNotNull(
                database.businessBranchDao()
                    .getBranchById(branchId)
            )
        val sessionActor =
            checkNotNull(
                database.userDao()
                    .getUserById(actorId)
            )
        val sessionMembership =
            checkNotNull(
                database.userDao()
                    .getMembership(actorId, branchId)
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

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun productionVoid_newOperationWithoutAuthenticatedSession_persistsNothing() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-no-session",
                amountMinor = 4_000L
            )
            val command = newVoidCommand(tx.id)

            SessionManager.clearSession()

            val result = repository.executeVoid(command)

            assertTrue(result is CommandResult.PermissionDenied)
            assertNull(
                database.operationDao()
                    .getOperationById(command.operationId.value)
            )
            assertNoVoidFinancialEffect(tx.id)
        }

    @Test
    fun productionVoid_normalSale_createsExactlyOneExactReversal() = runBlocking {
            useAdminActor()
        val original = seedAuthoritativeSaleFixture(
            transactionId = "void-normal",
            amountMinor = 10_000L
        )

        val command = newVoidCommand(original.id)

        val result = repository.executeVoid(command)

        assertTrue(result is CommandResult.Success)

        val success = result as CommandResult.Success
        assertFalse(success.isIdempotentReplay)
        assertEquals(10_000L, success.data.reversalAmountMinor)

        val event = database.transactionDao().getVoidEvent(original.id)

        assertNotNull(event)
        assertEquals(command.operationId.value, event!!.operationId)
        assertEquals(actorId, event.actorUserId)
        assertEquals(command.occurredAt, event.occurredAt)

        val reversals = getVoidReversals(original.id)

        assertEquals(1, reversals.size)
        assertEquals(-10_000L, reversals.single().amountSignedMinor)
        assertEquals(PaymentCategory.CASH, reversals.single().category)
        assertEquals(PaymentChannel.CASH_TENDER, reversals.single().channel)

        /*
         * The original sale row must remain exactly unchanged.
         */
        assertEquals(
            original,
            database.transactionDao().getTransactionById(original.id)
        )

        val record =
            database.transactionDao()
                .getFinancialRecordById(original.id)!!

        val view =
            FinancialReadModelResolver.resolve(record)

        assertTrue(view.isVoided)
        assertEquals(0L, view.netAmount.amount)

        val operation =
            database.operationDao()
                .getOperationById(command.operationId.value)

        assertEquals(OperationStatus.SUCCEEDED, operation?.status)
        assertEquals("VOID_EVENT", operation?.resultReferenceType)
        assertEquals(event.id, operation?.resultReferenceId)
        assertEquals(
            VoidPayloadCodec.CURRENT_SCHEMA_VERSION,
            operation?.commandSchemaVersion
        )

        val durablePayload =
            VoidPayloadCodec.decode(operation!!.intentPayload)

        assertNotNull(durablePayload.financialPrecondition)
        assertEquals(
            10_000L,
            durablePayload.financialPrecondition
                ?.expectedReversalAmountMinor
        )
        assertEquals(
            PaymentCategory.CASH,
            durablePayload.financialPrecondition?.fundingCategory
        )
        assertEquals(
            PaymentChannel.CASH_TENDER,
            durablePayload.financialPrecondition?.fundingChannel
        )
    }

    @Test
    fun productionVoid_partialRefund_reversesOnlyRemainingValue() = runBlocking {
            useAdminActor()
        val tx = seedAuthoritativeSaleFixture(
            transactionId = "void-partial-refund",
            amountMinor = 10_000L
        )

        seedAuthoritativeRefundFixture(
            transactionId = tx.id,
            refundAmountMinor = 3_000L,
            occurredAt = tx.createdAt + 1_000L
        )

        val result =
            repository.executeVoid(
                newVoidCommand(
                    transactionId = tx.id,
                    occurredAt = tx.createdAt + 2_000L
                )
            )

        assertTrue(result is CommandResult.Success)

        assertEquals(
            7_000L,
            (result as CommandResult.Success).data.reversalAmountMinor
        )

        val reversals = getVoidReversals(tx.id)

        assertEquals(1, reversals.size)
        assertEquals(-7_000L, reversals.single().amountSignedMinor)

        val record =
            database.transactionDao()
                .getFinancialRecordById(tx.id)!!

        val view =
            FinancialReadModelResolver.resolve(record)

        assertEquals(3_000L, view.refundedAmount.amount)
        assertTrue(view.isVoided)
        assertEquals(0L, view.netAmount.amount)
    }

    @Test
    fun productionVoid_splitRefundOutflows_failClosed() = runBlocking {
            useAdminActor()
        val tx = seedAuthoritativeSaleFixture(
            transactionId = "void-split-refund-history",
            amountMinor = 10_000L
        )

        val refundAt = tx.createdAt + 1_000L

        seedSplitRefundFixture(
            transactionId = tx.id,
            refundAmountMinor = 3_000L,
            occurredAt = refundAt
        )

        val command =
            newVoidCommand(
                transactionId = tx.id,
                occurredAt = refundAt + 1_000L
            )

        val result =
            repository.executeVoid(command)

        assertTrue(result is CommandResult.Error)
        assertTrue(
            (result as CommandResult.Error).exception
                is VoidAmbiguousSettlementException
        )
        assertNoVoidFinancialEffect(tx.id)
        assertEquals(
            OperationStatus.FAILED,
            database.operationDao()
                .getOperationById(command.operationId.value)
                ?.status
        )
    }

    @Test
    fun productionVoid_fullRefund_createsNoAdditionalReversal() = runBlocking {
            useAdminActor()
        val tx = seedAuthoritativeSaleFixture(
            transactionId = "void-full-refund",
            amountMinor = 10_000L
        )

        seedAuthoritativeRefundFixture(
            transactionId = tx.id,
            refundAmountMinor = 10_000L,
            occurredAt = tx.createdAt + 1_000L
        )

        val command =
            newVoidCommand(
                transactionId = tx.id,
                occurredAt = tx.createdAt + 2_000L
            )

        val result =
            repository.executeVoid(command)

        assertTrue(result is CommandResult.Error)

        assertTrue(
            (result as CommandResult.Error).exception
                is VoidNothingReversibleException
        )

        assertNoVoidFinancialEffect(tx.id)

        assertEquals(
            OperationStatus.FAILED,
            database.operationDao()
                .getOperationById(command.operationId.value)
                ?.status
        )
    }

    @Test
    fun productionVoid_correctionAndRefund_useAuthoritativeRemainingValue() =
        runBlocking {
            useAdminActor()

            val upward = seedAuthoritativeSaleFixture(
                transactionId = "void-correct-up",
                amountMinor = 10_000L
            )

            seedAuthoritativeCorrectionFixture(
                transactionId = upward.id,
                previousAmountMinor = 10_000L,
                correctedAmountMinor = 12_000L,
                occurredAt = upward.createdAt + 1_000L
            )

            seedAuthoritativeRefundFixture(
                transactionId = upward.id,
                refundAmountMinor = 2_000L,
                occurredAt = upward.createdAt + 2_000L
            )

            val upwardResult =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = upward.id,
                        occurredAt = upward.createdAt + 3_000L
                    )
                )

            assertTrue(upwardResult is CommandResult.Success)

            assertEquals(
                10_000L,
                (upwardResult as CommandResult.Success)
                    .data
                    .reversalAmountMinor
            )

            assertEquals(
                -10_000L,
                getVoidReversals(upward.id)
                    .single()
                    .amountSignedMinor
            )

            val downward = seedAuthoritativeSaleFixture(
                transactionId = "void-correct-down",
                amountMinor = 10_000L
            )

            seedAuthoritativeCorrectionFixture(
                transactionId = downward.id,
                previousAmountMinor = 10_000L,
                correctedAmountMinor = 7_000L,
                occurredAt = downward.createdAt + 1_000L
            )

            seedAuthoritativeRefundFixture(
                transactionId = downward.id,
                refundAmountMinor = 2_000L,
                occurredAt = downward.createdAt + 2_000L
            )

            val downwardResult =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = downward.id,
                        occurredAt = downward.createdAt + 3_000L
                    )
                )

            assertTrue(downwardResult is CommandResult.Success)

            assertEquals(
                5_000L,
                (downwardResult as CommandResult.Success)
                    .data
                    .reversalAmountMinor
            )

            assertEquals(
                -5_000L,
                getVoidReversals(downward.id)
                    .single()
                    .amountSignedMinor
            )
        }

    @Test
    fun productionVoid_brokenCorrectionHistory_failsClosed() = runBlocking {
            useAdminActor()
        val tx = seedAuthoritativeSaleFixture(
            transactionId = "void-broken-correction",
            amountMinor = 10_000L
        )

        /*
         * Intentionally corrupt lifecycle fixture:
         * previousAmount does not match the authoritative prior value.
         *
         * This is not a supported Correction workflow.
         */
        seedAuthoritativeCorrectionFixture(
            transactionId = tx.id,
            previousAmountMinor = 9_000L,
            correctedAmountMinor = 12_000L,
            occurredAt = tx.createdAt + 1_000L,
            includeSettlement = false
        )

        val result =
            repository.executeVoid(
                newVoidCommand(
                    transactionId = tx.id,
                    occurredAt = tx.createdAt + 2_000L
                )
            )

        assertTrue(result is CommandResult.Error)
        assertNoVoidFinancialEffect(tx.id)
    }

    @Test
    fun productionVoid_underfundedSettlement_failsClosed() = runBlocking {
            useAdminActor()
        val tx = seedAuthoritativeSaleFixture(
            transactionId = "void-underfunded",
            amountMinor = 10_000L,
            fundingAmountMinor = 5_000L
        )

        val result =
            repository.executeVoid(
                newVoidCommand(
                    transactionId = tx.id,
                    occurredAt = tx.createdAt + 1_000L
                )
            )

        assertTrue(result is CommandResult.Error)

        assertTrue(
            (result as CommandResult.Error).exception
                is VoidAmbiguousSettlementException
        )

        assertNoVoidFinancialEffect(tx.id)
    }

    @Test
    fun productionVoid_unauthorizedActor_persistsNothing() = runBlocking {
        val tx = seedAuthoritativeSaleFixture(
            transactionId = "void-denied",
            amountMinor = 4_000L
        )

        // Keep the CASHIER session from setUp while the command actor is ADMIN.
        // This preserves the production actor/session mismatch denial, not a role-permission denial.
        val command =
            newVoidCommand(
                transactionId = tx.id,
                actor = adminActorId
            )

        val result =
            repository.executeVoid(command)

        assertTrue(result is CommandResult.PermissionDenied)

        /*
         * Authorization happens before admitting the Operation.
         */
        assertNull(
            database.operationDao()
                .getOperationById(command.operationId.value)
        )

        assertNoVoidFinancialEffect(tx.id)
    }

    @Test
    fun productionVoid_sameOperation_replaysWithoutDuplicate_andAlteredReplayConflicts() =
        runBlocking {
            useAdminActor()

            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-replay",
                amountMinor = 8_000L
            )

            val command =
                newVoidCommand(
                    transactionId = tx.id,
                    reason = "Damaged item"
                )

            val first =
                repository.executeVoid(command)

            assertTrue(first is CommandResult.Success)

            val firstSuccess =
                first as CommandResult.Success

            assertFalse(firstSuccess.isIdempotentReplay)

            val second =
                repository.executeVoid(command)

            assertTrue(second is CommandResult.Success)

            val secondSuccess =
                second as CommandResult.Success

            assertTrue(secondSuccess.isIdempotentReplay)
            assertEquals(firstSuccess.data, secondSuccess.data)
            assertEquals(1, getVoidReversals(tx.id).size)

            val altered =
                repository.executeVoid(
                    command.copy(
                        reason = "Different reason"
                    )
                )

            assertTrue(altered is CommandResult.Conflict)
            assertEquals(1, getVoidReversals(tx.id).size)
        }

    @Test
    fun productionVoid_legacyV1SucceededOperation_remainsReplayable() = runBlocking {
        val tx = seedAuthoritativeSaleFixture(
            transactionId = "void-legacy-success-replay",
            amountMinor = 3_250L
        )

        val legacyCommand = newVoidCommand(
            transactionId = tx.id,
            reason = "Legacy replay"
        )
        val voidEventId = "legacy-void-event-${tx.id}"

        database.operationDao().insertOperation(
            OperationEntity(
                id = legacyCommand.operationId.value,
                businessId = legacyCommand.businessId,
                branchId = legacyCommand.branchId,
                operationType = legacyCommand.operationType,
                subjectType = legacyCommand.subjectType,
                subjectId = legacyCommand.subjectId,
                transactionId = legacyCommand.transactionId,
                actorUserId = legacyCommand.actorUserId,
                deviceId = legacyCommand.deviceId,
                occurredAt = legacyCommand.occurredAt,
                requestFingerprint = legacyCommand.requestFingerprint,
                commandSchemaVersion = VoidPayloadCodec.LEGACY_SCHEMA_VERSION,
                intentPayload = legacyCommand.intentPayload,
                status = OperationStatus.SUCCEEDED,
                resultReferenceType = "VOID_EVENT",
                resultReferenceId = voidEventId
            )
        )

        database.transactionDao().insertVoid(
            VoidEventEntity(
                id = voidEventId,
                transactionId = tx.id,
                operationId = legacyCommand.operationId.value,
                reason = legacyCommand.reason,
                actorUserId = actorId,
                occurredAt = legacyCommand.occurredAt
            )
        )

        database.transactionDao().insertSettlement(
            SettlementEntryEntity(
                id = "legacy-void-settlement-${tx.id}",
                operationId = legacyCommand.operationId.value,
                subjectType = SubjectType.TRANSACTION,
                subjectId = tx.id,
                transactionId = tx.id,
                entryPurpose = EntryPurpose.VOID_REVERSAL,
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountSignedMinor = -3_250L,
                currencyCode = "OMR",
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = legacyCommand.occurredAt,
                actorUserId = actorId
            )
        )

        val replay = repository.executeVoid(legacyCommand)

        assertTrue(replay is CommandResult.Success)
        val success = replay as CommandResult.Success
        assertTrue(success.isIdempotentReplay)
        assertEquals(3_250L, success.data.reversalAmountMinor)
        assertEquals(1, getVoidReversals(tx.id).size)
    }

    @Test
    fun productionVoid_differentOperation_cannotReverseSameTransactionTwice() =
        runBlocking {
            useAdminActor()

            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-second-operation",
                amountMinor = 6_000L
            )

            val firstCommand =
                newVoidCommand(tx.id)

            val first =
                repository.executeVoid(firstCommand)

            assertTrue(first is CommandResult.Success)

            val secondCommand =
                newVoidCommand(
                    transactionId = tx.id,
                    operationId = OperationId.generate(),
                    occurredAt = firstCommand.occurredAt + 1L
                )

            val second =
                repository.executeVoid(secondCommand)

            assertTrue(second is CommandResult.Error)

            assertTrue(
                (second as CommandResult.Error).exception
                    is VoidAlreadyExistsException
            )

            assertEquals(1, getVoidReversals(tx.id).size)

            assertEquals(
                firstCommand.operationId.value,
                database.transactionDao()
                    .getVoidEvent(tx.id)
                    ?.operationId
            )
        }

    /**
     * DATABASE-ONLY ADVERSARIAL TEST.
     *
     * Direct lifecycle DAO insertion here is intentional.
     * This test proves the v5 UNIQUE constraints themselves and does NOT
     * represent a supported production Void workflow.
     */
    @Test
    fun databaseGuard_directDuplicateVoidRows_areRejected() = runBlocking {
            useAdminActor()
        val txA = seedAuthoritativeSaleFixture(
            transactionId = "void-db-a",
            amountMinor = 1_000L
        )

        val txB = seedAuthoritativeSaleFixture(
            transactionId = "void-db-b",
            amountMinor = 1_000L
        )

        val time = System.currentTimeMillis()

        insertAuthoritativeOperationFixture(
            id = "direct-void-operation-a",
            type = OperationType.RECORD_VOID,
            transactionId = txA.id,
            occurredAt = time
        )

        database.transactionDao().insertVoid(
            VoidEventEntity(
                id = "direct-void-event-a",
                transactionId = txA.id,
                operationId = "direct-void-operation-a",
                actorUserId = actorId,
                occurredAt = time
            )
        )

        insertAuthoritativeOperationFixture(
            id = "direct-void-operation-b",
            type = OperationType.RECORD_VOID,
            transactionId = txA.id,
            occurredAt = time + 1L
        )

        assertDirectVoidInsertRejected(
            VoidEventEntity(
                id = "direct-void-event-same-transaction",
                transactionId = txA.id,
                operationId = "direct-void-operation-b",
                actorUserId = actorId,
                occurredAt = time + 1L
            )
        )

        assertEquals(
            "direct-void-event-a",
            database.transactionDao()
                .getVoidEvent(txA.id)
                ?.id
        )

        assertDirectVoidInsertRejected(
            VoidEventEntity(
                id = "direct-void-event-same-operation",
                transactionId = txB.id,
                operationId = "direct-void-operation-a",
                actorUserId = actorId,
                occurredAt = time + 2L
            )
        )

        assertNull(
            database.transactionDao()
                .getVoidEvent(txB.id)
        )
    }

    @Test
    fun productionVoid_settlementInsertFailure_rollsBackVoidEvent() =
        runBlocking {
            useAdminActor()

            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-atomic-rollback",
                amountMinor = 9_000L
            )

            val command =
                newVoidCommand(tx.id)

            /*
             * Test-only failure injection.
             * Forces the second financial write to fail after the VoidEvent
             * insert has been attempted.
             */
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER test_abort_void_reversal
                BEFORE INSERT ON settlement_entries
                WHEN NEW.entry_purpose = 'VOID_REVERSAL'
                BEGIN
                    SELECT RAISE(ABORT, 'forced Void reversal failure');
                END
                """.trimIndent()
            )

            val result =
                try {
                    repository.executeVoid(command)
                } finally {
                    database.openHelper.writableDatabase.execSQL(
                        "DROP TRIGGER IF EXISTS test_abort_void_reversal"
                    )
                }

            assertTrue(result is CommandResult.Error)

            assertNoVoidFinancialEffect(tx.id)

            assertEquals(
                9_000L,
                database.transactionDao()
                    .getTransactionById(tx.id)
                    ?.grandTotalMinor
            )
        }

    @Test
    fun productionVoid_concurrentDifferentOperations_createOneEconomicReversal() =
        runBlocking {
            useAdminActor()

            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-concurrent",
                amountMinor = 11_000L
            )

            val commandA =
                newVoidCommand(
                    transactionId = tx.id,
                    operationId = OperationId.generate()
                )

            val commandB =
                newVoidCommand(
                    transactionId = tx.id,
                    operationId = OperationId.generate(),
                    occurredAt = commandA.occurredAt + 1L
                )

            val startGate =
                CompletableDeferred<Unit>()

            val first =
                async(Dispatchers.IO) {
                    startGate.await()
                    repository.executeVoid(commandA)
                }

            val second =
                async(Dispatchers.IO) {
                    startGate.await()
                    repository.executeVoid(commandB)
                }

            startGate.complete(Unit)

            val results =
                awaitAll(first, second)

            assertEquals(
                1,
                results.count {
                    it is CommandResult.Success
                }
            )

            val reversals =
                getVoidReversals(tx.id)

            assertEquals(1, reversals.size)
            assertEquals(
                -11_000L,
                reversals.single().amountSignedMinor
            )

            assertNotNull(
                database.transactionDao()
                    .getVoidEvent(tx.id)
            )
        }

    @Test
    fun productionVoid_pendingRecovery_completesOnceWithoutDuplicate() =
        runBlocking {

            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-recovery",
                amountMinor = 7_500L
            )

            val originalCommand =
                newVoidCommand(tx.id)

            val admittedCommand =
                insertAdmittedPendingVoidFixture(originalCommand)

            /*
             * Permission is mutable session state. Once the exact v2 intent is
             * durably PENDING, later permission revocation must not redefine it.
             */
            val membership = checkNotNull(
                database.userDao().getMembership(actorId, branchId)
            )
            database.userDao().updateMembership(
                membership.copy(canVoid = false)
            )

            val firstRecovery =
                repository.recoverPendingVoids()

            assertEquals(1, firstRecovery.size)
            assertEquals(tx.id, firstRecovery.single().transactionId)
            assertEquals(
                7_500L,
                firstRecovery.single().reversalAmountMinor
            )

            val operation =
                database.operationDao()
                    .getOperationById(admittedCommand.operationId.value)

            assertEquals(
                OperationStatus.SUCCEEDED,
                operation?.status
            )
            assertEquals(
                VoidPayloadCodec.CURRENT_SCHEMA_VERSION,
                operation?.commandSchemaVersion
            )
            assertEquals(1, getVoidReversals(tx.id).size)

            val secondRecovery =
                repository.recoverPendingVoids()

            assertTrue(secondRecovery.isEmpty())
            assertEquals(1, getVoidReversals(tx.id).size)
        }

    @Test
    fun productionVoid_existingPendingRetry_usesDurableIntentAfterPermissionRevocation() =
        runBlocking {
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-pending-retry-permission",
                amountMinor = 5_500L
            )

            val originalCommand = newVoidCommand(tx.id)
            insertAdmittedPendingVoidFixture(originalCommand)

            val membership = checkNotNull(
                database.userDao().getMembership(actorId, branchId)
            )
            database.userDao().updateMembership(
                membership.copy(canVoid = false)
            )

            val result = repository.executeVoid(originalCommand)

            assertTrue(result is CommandResult.Success)
            assertEquals(
                5_500L,
                (result as CommandResult.Success).data.reversalAmountMinor
            )
            assertEquals(1, getVoidReversals(tx.id).size)
        }

    @Test
    fun productionVoid_pendingRecovery_stateChanged_movesToReconciliationWithoutReversal() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-recovery-state-changed",
                amountMinor = 10_000L
            )

            val command = newVoidCommand(tx.id)
            val admittedCommand =
                insertAdmittedPendingVoidFixture(command)

            /*
             * This financial activity occurs AFTER durable Void admission.
             * Recovery must not silently recalculate the old intent.
             */
            seedAuthoritativeRefundFixture(
                transactionId = tx.id,
                refundAmountMinor = 1_000L,
                occurredAt = command.occurredAt + 1_000L
            )

            var failure: Throwable? = null
            try {
                repository.recoverPendingVoids()
            } catch (error: Throwable) {
                failure = error
            }

            assertNotNull(failure)
            assertTrue(
                failure!!.message.orEmpty()
                    .contains("requires reconciliation")
            )

            assertEquals(
                OperationStatus.REQUIRES_RECONCILIATION,
                database.operationDao()
                    .getOperationById(admittedCommand.operationId.value)
                    ?.status
            )
            assertNoVoidFinancialEffect(tx.id)
            assertEquals(1, database.transactionDao().getRefundEvents(tx.id).size)
        }

    @Test
    fun productionVoid_legacyV1Pending_movesToReconciliationWithoutGuessing() =
        runBlocking {
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-legacy-pending",
                amountMinor = 4_500L
            )

            val legacyCommand = newVoidCommand(tx.id)

            database.operationDao().insertOperation(
                OperationEntity(
                    id = legacyCommand.operationId.value,
                    businessId = legacyCommand.businessId,
                    branchId = legacyCommand.branchId,
                    operationType = legacyCommand.operationType,
                    subjectType = legacyCommand.subjectType,
                    subjectId = legacyCommand.subjectId,
                    transactionId = legacyCommand.transactionId,
                    actorUserId = legacyCommand.actorUserId,
                    deviceId = legacyCommand.deviceId,
                    occurredAt = legacyCommand.occurredAt,
                    requestFingerprint = legacyCommand.requestFingerprint,
                    commandSchemaVersion = legacyCommand.commandSchemaVersion,
                    intentPayload = legacyCommand.intentPayload,
                    status = OperationStatus.PENDING
                )
            )

            assertEquals(
                VoidPayloadCodec.LEGACY_SCHEMA_VERSION,
                legacyCommand.commandSchemaVersion
            )

            var failure: Throwable? = null
            try {
                repository.recoverPendingVoids()
            } catch (error: Throwable) {
                failure = error
            }

            assertNotNull(failure)
            assertEquals(
                OperationStatus.REQUIRES_RECONCILIATION,
                database.operationDao()
                    .getOperationById(legacyCommand.operationId.value)
                    ?.status
            )
            assertNoVoidFinancialEffect(tx.id)
        }

    @Test
    fun productionVoid_balancingCorruptLifecycleSettlements_failClosed() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-balanced-corruption",
                amountMinor = 10_000L
            )

            /*
             * True lifecycle effect:
             *   correction +2,000, refund -1,000 => remaining 11,000.
             *
             * Corrupt settlements deliberately still total +1,000:
             *   correction +3,000, refund -2,000.
             *
             * Aggregate-only validation would see 11,000 and could pass.
             * Operation-by-operation reconciliation MUST reject it.
             */
            seedAuthoritativeCorrectionFixture(
                transactionId = tx.id,
                previousAmountMinor = 10_000L,
                correctedAmountMinor = 12_000L,
                occurredAt = tx.createdAt + 1_000L,
                settlementAmountMinor = 3_000L
            )

            seedAuthoritativeRefundFixture(
                transactionId = tx.id,
                refundAmountMinor = 1_000L,
                occurredAt = tx.createdAt + 2_000L,
                settlementAmountMinor = -2_000L
            )

            val result = repository.executeVoid(
                newVoidCommand(
                    transactionId = tx.id,
                    occurredAt = tx.createdAt + 3_000L
                )
            )

            assertTrue(result is CommandResult.Error)
            assertTrue(
                (result as CommandResult.Error).exception
                    is VoidAmbiguousSettlementException
            )
            assertNoVoidFinancialEffect(tx.id)
        }

    @Test
    fun commandExecutor_sameFingerprintDifferentDurablePayload_isRejected() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-durable-payload-binding",
                amountMinor = 2_000L
            )

            val original = newVoidCommand(tx.id)
            val admitted = insertAdmittedPendingVoidFixture(original)
            val differentDurableIntent = admitted.copy(
                financialPrecondition =
                    admitted.financialPrecondition!!.copy(
                        stateFingerprint = "0".repeat(64)
                    )
            )

            var executionCount = 0
            val result = CommandExecutor(database).execute(
                command = differentDurableIntent,
                replayResolver = {
                    error("Replay must not occur for mismatched durable intent")
                }
            ) {
                executionCount++
                error("Execution must not run for mismatched durable intent")
            }

            assertTrue(result is CommandResult.Conflict)
            assertEquals(0, executionCount)
            assertEquals(
                OperationStatus.PENDING,
                database.operationDao()
                    .getOperationById(admitted.operationId.value)
                    ?.status
            )
            assertNoVoidFinancialEffect(tx.id)
        }

    @Test
    fun productionVoid_creditFundedTransaction_isBlockedWithoutFinancialMutation() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-boundary-credit-funded",
                amountMinor = 10_000L,
                fundingCategory = PaymentCategory.CREDIT
            )
            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = tx.id,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertVoidRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException = VoidUnsupportedCreditException::class.java
            )
        }

    @Test
    fun productionVoid_couponFundedTransaction_isBlockedWithoutFinancialMutation() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-boundary-coupon-funded",
                amountMinor = 10_000L,
                fundingCategory = PaymentCategory.COUPON
            )
            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = tx.id,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertVoidRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException = VoidUnsupportedCouponException::class.java
            )
        }

    @Test
    fun productionVoid_paymentMethodChangeExists_isBlockedWithoutFinancialMutation() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-boundary-payment-change",
                amountMinor = 10_000L
            )
            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val changeTime = tx.createdAt + 1_000L
            val changeOperationId = "payment-change-operation-${tx.id}"

            insertAuthoritativeOperationFixture(
                id = changeOperationId,
                type = OperationType.RECORD_PAYMENT_METHOD_CHANGE,
                transactionId = tx.id,
                occurredAt = changeTime
            )

            database.transactionDao().insertPaymentMethodChange(
                PaymentMethodChangeEventEntity(
                    id = "payment-change-event-${tx.id}",
                    transactionId = tx.id,
                    operationId = changeOperationId,
                    previousMethod = PaymentCategory.CASH,
                    previousChannel = PaymentChannel.CASH_TENDER,
                    newMethod = PaymentCategory.ONLINE,
                    newChannel = PaymentChannel.MANUAL_QR,
                    reason = "Boundary test",
                    actorUserId = actorId,
                    occurredAt = changeTime
                )
            )

            val result =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = tx.id,
                        occurredAt = changeTime + 1_000L
                    )
                )

            assertVoidRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException = VoidAmbiguousSettlementException::class.java
            )
        }

    @Test
    fun productionVoid_creditRepaymentAllocationExists_isBlockedWithoutFinancialMutation() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-boundary-credit-allocation",
                amountMinor = 10_000L
            )
            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            /*
             * Adversarial prerequisite fixture only.
             *
             * Step 8B is proving that Void refuses a transaction once an
             * authoritative repayment allocation targets it. The complete
             * Credit repayment writer is audited separately, so this isolated
             * in-memory DB inserts only the allocation evidence required by
             * the production Void query.
             */
            seedAdversarialCreditRepaymentAllocationFixture(
                transactionId = tx.id,
                occurredAt = tx.createdAt + 1_000L
            )

            val result =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = tx.id,
                        occurredAt = tx.createdAt + 2_000L
                    )
                )

            assertVoidRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException =
                    VoidCreditRepaymentExistsException::class.java
            )
        }

    @Test
    fun productionVoid_mixedCashAndOnlineFunding_isBlockedAsAmbiguous() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-boundary-mixed-funding",
                amountMinor = 10_000L,
                fundingAmountMinor = 5_000L
            )

            database.transactionDao().insertSettlement(
                SettlementEntryEntity(
                    id = "sale-settlement-online-${tx.id}",
                    operationId = "sale-operation-${tx.id}",
                    subjectType = SubjectType.TRANSACTION,
                    subjectId = tx.id,
                    transactionId = tx.id,
                    entryPurpose = EntryPurpose.SALE_FUNDING,
                    category = PaymentCategory.ONLINE,
                    channel = PaymentChannel.MANUAL_QR,
                    amountSignedMinor = 5_000L,
                    currencyCode = "OMR",
                    confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                    occurredAt = tx.createdAt,
                    actorUserId = actorId
                )
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = tx.id,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertVoidRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException = VoidAmbiguousSettlementException::class.java
            )
        }

    @Test
    fun productionVoid_missingSaleFunding_isBlockedAsAmbiguous() =
        runBlocking {
            useAdminActor()
            val tx = seedAuthoritativeSaleFixture(
                transactionId = "void-boundary-missing-funding",
                amountMinor = 10_000L,
                includeFundingSettlement = false
            )
            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            assertTrue(settlementsBefore.isEmpty())

            val result =
                repository.executeVoid(
                    newVoidCommand(
                        transactionId = tx.id,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertVoidRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException = VoidAmbiguousSettlementException::class.java
            )
        }

    @Test
    fun productionVoid_realWriter_makesOlderSaleAppearInTodayRecent() =
        runBlocking {
            useAdminActor()

            val zone =
                ZoneId.of("Asia/Muscat")

            val todayStart =
                LocalDate.now(zone)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()

            /*
             * Original sale belongs to the previous day.
             */
            val oldSaleTime =
                todayStart - 60_000L

            /*
             * Void activity belongs to today.
             */
            val voidTime =
                todayStart + 1_000L

            val tx =
                seedAuthoritativeSaleFixture(
                    transactionId = "void-real-recent",
                    amountMinor = 10_000L,
                    occurredAt = oldSaleTime
                )

            val command =
                newVoidCommand(
                    transactionId = tx.id,
                    occurredAt = voidTime
                )

            val result =
                repository.executeVoid(command)

            assertTrue(result is CommandResult.Success)

            /*
             * This is the important distinction from the historical test:
             * no direct VoidEvent fixture is inserted above.
             *
             * Recent receives a Void created by the REAL production writer.
             */
            val recent =
                repository.observeTodayTransactions(
                    branchId,
                    "Asia/Muscat"
                ).first()

            val projected =
                recent.single {
                    it.transactionId == tx.id &&
                        it.type == com.dailymoneyregister.core.model.financial.RecentActivityType.VOID
                }

            assertEquals(
                tx.id,
                projected.transactionId
            )

            assertEquals(
                voidTime,
                projected.occurredAt
            )

            assertEquals(
                -10_000L,
                projected.signedEffect.amount
            )

            assertEquals(
                tx.id,
                projected.originalTransactionId
            )

            assertEquals(1, getVoidReversals(tx.id).size)

            assertEquals(
                -10_000L,
                getVoidReversals(tx.id)
                    .single()
                    .amountSignedMinor
            )
        }

    /*
     * ---------------------------------------------------------
     * CONTROLLED FIXTURES
     * ---------------------------------------------------------
     *
     * These helpers create authoritative prerequisite history directly in the
     * isolated test DB. They are NOT production writer tests.
     */


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

    private suspend fun seedAuthoritativeSaleFixture(
        transactionId: String,
        amountMinor: Long,
        fundingAmountMinor: Long = amountMinor,
        fundingCategory: PaymentCategory = PaymentCategory.CASH,
        fundingChannel: PaymentChannel = PaymentChannel.CASH_TENDER,
        includeFundingSettlement: Boolean = true,
        occurredAt: Long =
            System.currentTimeMillis() - 10_000L
    ): TransactionEntity {

        val operationId =
            "sale-operation-$transactionId"

        insertAuthoritativeOperationFixture(
            id = operationId,
            type = OperationType.RECORD_SALE,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        val transaction =
            TransactionEntity(
                id = transactionId,
                branchId = branchId,
                type = TransactionType.SALE,
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
                displayRef = "VOID-$transactionId"
            )

        database.transactionDao()
            .insertTransaction(transaction)

        if (includeFundingSettlement) {
            database.transactionDao()
                .insertSettlement(
                    SettlementEntryEntity(
                        id = "sale-settlement-$transactionId",
                        operationId = operationId,
                        subjectType = SubjectType.TRANSACTION,
                        subjectId = transactionId,
                        transactionId = transactionId,
                        entryPurpose = EntryPurpose.SALE_FUNDING,
                        category = fundingCategory,
                        channel = fundingChannel,
                        amountSignedMinor = fundingAmountMinor,
                        currencyCode = "OMR",
                        confirmationType =
                            ConfirmationType.CASHIER_CONFIRMED,
                        occurredAt = occurredAt,
                        actorUserId = actorId
                    )
                )
        }

        return transaction
    }

    private suspend fun seedSplitRefundFixture(
        transactionId: String,
        refundAmountMinor: Long,
        occurredAt: Long
    ) {
        val operationId =
            "split-refund-operation-$transactionId-$occurredAt"

        insertAuthoritativeOperationFixture(
            id = operationId,
            type = OperationType.RECORD_REFUND,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        database.transactionDao()
            .insertRefund(
                RefundEventEntity(
                    id =
                        "split-refund-event-$transactionId-$occurredAt",
                    transactionId = transactionId,
                    operationId = operationId,
                    refundAmountMinor = refundAmountMinor,
                    actorUserId = actorId,
                    occurredAt = occurredAt
                )
            )

        listOf(-1_000L, -2_000L).forEachIndexed { index, amount ->
            database.transactionDao()
                .insertSettlement(
                    SettlementEntryEntity(
                        id =
                            "split-refund-settlement-$transactionId-$occurredAt-$index",
                        operationId = operationId,
                        subjectType = SubjectType.TRANSACTION,
                        subjectId = transactionId,
                        transactionId = transactionId,
                        entryPurpose = EntryPurpose.REFUND_OUTFLOW,
                        category = PaymentCategory.CASH,
                        channel = PaymentChannel.CASH_TENDER,
                        amountSignedMinor = amount,
                        currencyCode = "OMR",
                        confirmationType =
                            ConfirmationType.CASHIER_CONFIRMED,
                        occurredAt = occurredAt,
                        actorUserId = actorId
                    )
                )
        }
    }

    private suspend fun seedAuthoritativeRefundFixture(
        transactionId: String,
        refundAmountMinor: Long,
        occurredAt: Long,
        settlementAmountMinor: Long? = null
    ) {
        val operationId =
            "refund-operation-$transactionId-$occurredAt"

        insertAuthoritativeOperationFixture(
            id = operationId,
            type = OperationType.RECORD_REFUND,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        database.transactionDao()
            .insertRefund(
                RefundEventEntity(
                    id =
                        "refund-event-$transactionId-$occurredAt",
                    transactionId = transactionId,
                    operationId = operationId,
                    refundAmountMinor = refundAmountMinor,
                    actorUserId = actorId,
                    occurredAt = occurredAt
                )
            )

        database.transactionDao()
            .insertSettlement(
                SettlementEntryEntity(
                    id =
                        "refund-settlement-$transactionId-$occurredAt",
                    operationId = operationId,
                    subjectType = SubjectType.TRANSACTION,
                    subjectId = transactionId,
                    transactionId = transactionId,
                    entryPurpose = EntryPurpose.REFUND_OUTFLOW,
                    category = PaymentCategory.CASH,
                    channel = PaymentChannel.CASH_TENDER,
                    amountSignedMinor =
                        settlementAmountMinor
                            ?: Math.negateExact(refundAmountMinor),
                    currencyCode = "OMR",
                    confirmationType =
                        ConfirmationType.CASHIER_CONFIRMED,
                    occurredAt = occurredAt,
                    actorUserId = actorId
                )
            )
    }

    private suspend fun seedAuthoritativeCorrectionFixture(
        transactionId: String,
        previousAmountMinor: Long,
        correctedAmountMinor: Long,
        occurredAt: Long,
        includeSettlement: Boolean = true,
        settlementAmountMinor: Long? = null
    ) {
        val operationId =
            "correction-operation-$transactionId-$occurredAt"

        insertAuthoritativeOperationFixture(
            id = operationId,
            type = OperationType.RECORD_AMOUNT_CORRECTION,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        database.transactionDao()
            .insertAmountCorrection(
                AmountCorrectionEventEntity(
                    id =
                        "correction-event-$transactionId-$occurredAt",
                    transactionId = transactionId,
                    operationId = operationId,
                    previousAmountMinor = previousAmountMinor,
                    correctedAmountMinor = correctedAmountMinor,
                    actorUserId = actorId,
                    occurredAt = occurredAt
                )
            )

        if (includeSettlement) {
            val delta =
                Math.subtractExact(
                    correctedAmountMinor,
                    previousAmountMinor
                )

            database.transactionDao()
                .insertSettlement(
                    SettlementEntryEntity(
                        id =
                            "correction-settlement-$transactionId-$occurredAt",
                        operationId = operationId,
                        subjectType = SubjectType.TRANSACTION,
                        subjectId = transactionId,
                        transactionId = transactionId,
                        entryPurpose =
                            EntryPurpose.FUNDING_CORRECTION,
                        category = PaymentCategory.CASH,
                        channel = PaymentChannel.CASH_TENDER,
                        amountSignedMinor =
                            settlementAmountMinor ?: delta,
                        currencyCode = "OMR",
                        confirmationType =
                            ConfirmationType.CASHIER_CONFIRMED,
                        occurredAt = occurredAt,
                        actorUserId = actorId
                    )
                )
        }
    }

    private suspend fun insertAdmittedPendingVoidFixture(
        command: VoidCommand
    ): VoidCommand {
        val record = checkNotNull(
            database.transactionDao()
                .getFinancialRecordById(command.subjectId)
        )
        val allocations =
            database.ledgerDao()
                .getCreditRepaymentAllocationsForTransaction(
                    command.subjectId
                )
        val validated =
            VoidFinancialIntegrity.validate(
                record = record,
                creditAllocations = allocations
            )

        val admitted = command.copy(
            financialPrecondition = VoidFinancialPrecondition(
                stateFingerprint =
                    VoidFinancialIntegrity.fingerprint(
                        transactionId = command.subjectId,
                        record = record,
                        creditAllocations = allocations
                    ),
                expectedReversalAmountMinor =
                    validated.reversibleAmountMinor,
                fundingCategory = validated.fundingCategory,
                fundingChannel = validated.fundingChannel
            )
        )

        database.operationDao().insertOperation(
            OperationEntity(
                id = admitted.operationId.value,
                businessId = admitted.businessId,
                branchId = admitted.branchId,
                operationType = admitted.operationType,
                subjectType = admitted.subjectType,
                subjectId = admitted.subjectId,
                transactionId = admitted.transactionId,
                actorUserId = admitted.actorUserId,
                deviceId = admitted.deviceId,
                occurredAt = admitted.occurredAt,
                requestFingerprint = admitted.requestFingerprint,
                commandSchemaVersion = admitted.commandSchemaVersion,
                intentPayload = admitted.intentPayload,
                status = OperationStatus.PENDING
            )
        )

        return admitted
    }

    private suspend fun insertAuthoritativeOperationFixture(
        id: String,
        type: OperationType,
        transactionId: String,
        occurredAt: Long,
        status: OperationStatus =
            OperationStatus.SUCCEEDED
    ) {
        database.operationDao()
            .insertOperation(
                OperationEntity(
                    id = id,
                    businessId = businessId,
                    branchId = branchId,
                    operationType = type,
                    subjectType = SubjectType.TRANSACTION,
                    subjectId = transactionId,
                    transactionId = transactionId,
                    actorUserId = actorId,
                    deviceId = deviceId,
                    occurredAt = occurredAt,
                    requestFingerprint = "fixture-$id",
                    commandSchemaVersion = 1,
                    intentPayload = "fixture-$id",
                    status = status
                )
            )
    }

    private suspend fun seedAdversarialCreditRepaymentAllocationFixture(
        transactionId: String,
        occurredAt: Long
    ) {
        val writable = database.openHelper.writableDatabase

        writable.execSQL("PRAGMA foreign_keys = OFF")
        try {
            writable.execSQL(
                """
                INSERT INTO credit_repayment_allocations (
                    id,
                    repayment_id,
                    original_transaction_id,
                    allocated_amount_minor,
                    occurred_at
                )
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "void-credit-allocation-$transactionId",
                    "void-missing-repayment-$transactionId",
                    transactionId,
                    1L,
                    occurredAt
                )
            )
        } finally {
            writable.execSQL("PRAGMA foreign_keys = ON")
        }

        assertEquals(
            1,
            database.ledgerDao()
                .getCreditRepaymentAllocationsForTransaction(transactionId)
                .size
        )
    }

    private suspend fun assertVoidRejectedPreservingOriginal(
        original: TransactionEntity,
        settlementsBefore: List<SettlementEntryEntity>,
        result: CommandResult<*>,
        expectedException: Class<out Throwable>
    ) {
        assertTrue(
            "Expected Void failure, got: $result",
            result is CommandResult.Error
        )

        val error = (result as CommandResult.Error).exception
        assertTrue(
            "Expected ${expectedException.simpleName}, got ${error::class.java.simpleName}",
            expectedException.isInstance(error)
        )

        assertNoVoidFinancialEffect(original.id)

        assertEquals(
            original,
            database.transactionDao()
                .getTransactionById(original.id)
        )

        assertEquals(
            settlementsBefore,
            database.transactionDao()
                .getSettlementsForTransaction(original.id)
        )
    }

    private fun newVoidCommand(
        transactionId: String,
        operationId: OperationId =
            OperationId.generate(),
        actor: String = actorId,
        occurredAt: Long =
            System.currentTimeMillis(),
        reason: String? =
            "Void integration test"
    ): VoidCommand {
        return VoidCommand(
            operationId = operationId,
            businessId = businessId,
            branchId = branchId,
            subjectId = transactionId,
            actorUserId = actor,
            deviceId = deviceId,
            occurredAt = occurredAt,
            reason = reason
        )
    }

    private suspend fun getVoidReversals(
        transactionId: String
    ): List<SettlementEntryEntity> {
        return database.transactionDao()
            .getSettlementsForTransaction(transactionId)
            .filter {
                it.entryPurpose ==
                    EntryPurpose.VOID_REVERSAL
            }
    }

    private suspend fun assertNoVoidFinancialEffect(
        transactionId: String
    ) {
        assertNull(
            database.transactionDao()
                .getVoidEvent(transactionId)
        )

        assertTrue(
            getVoidReversals(transactionId)
                .isEmpty()
        )
    }

    private suspend fun assertDirectVoidInsertRejected(
        event: VoidEventEntity
    ) {
        var failure: Throwable? = null

        try {
            database.transactionDao()
                .insertVoid(event)
        } catch (error: Throwable) {
            failure = error
        }

        assertNotNull(
            "Expected database uniqueness to reject duplicate Void",
            failure
        )
    }
}
