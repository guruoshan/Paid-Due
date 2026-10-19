package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CreditLedgerEventEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
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
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.feature.register.data.RefundAfterVoidException
import com.dailymoneyregister.feature.register.data.RefundAmbiguousSettlementException
import com.dailymoneyregister.feature.register.data.RefundCreditRepaymentExistsException
import com.dailymoneyregister.feature.register.data.RefundAmountExceedsRemainingException
import com.dailymoneyregister.feature.register.data.RefundCommand
import com.dailymoneyregister.feature.register.data.RefundDestinationMismatchException
import com.dailymoneyregister.feature.register.data.RefundFinancialIntegrity
import com.dailymoneyregister.feature.register.data.RefundFinancialPrecondition
import com.dailymoneyregister.feature.register.data.RefundCouponDestination
import com.dailymoneyregister.feature.register.data.RefundPayloadCodec
import com.dailymoneyregister.feature.register.data.RefundUnsupportedCouponException
import com.dailymoneyregister.feature.register.data.RefundUnsupportedCreditException
import com.dailymoneyregister.feature.register.data.RegisterRepository
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

@RunWith(AndroidJUnit4::class)
class RefundFinancialCoreIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository

    private val businessId = "refund-test-business"
    private val branchId = "refund-test-branch"
    private val actorId = "refund-authorized-user"
    private val unauthorizedActorId = "refund-unauthorized-user"
    private val deviceId = "refund-test-device"

    @Before
    fun setUp() = runBlocking {
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
                name = "Refund Test Business",
                createdAt = now,
                updatedAt = now
            )
        )

        database.businessBranchDao().insertBranch(
            BranchEntity(
                id = branchId,
                businessId = businessId,
                name = "Refund Test Branch",
                code = "REFUND",
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
                displayName = "Refund Authorized Cashier",
                username = "refund_authorized",
                role = UserRole.CASHIER,
                authType = AuthType.PIN,
                credentialHash = "hash",
                credentialSalt = "salt",
                createdAt = now,
                updatedAt = now
            )
        )

        database.userDao().insertMembership(
            UserBranchMembershipEntity(
                id = "refund-authorized-membership",
                userId = actorId,
                branchId = branchId,
                role = UserRole.CASHIER,
                canCreateSale = true,
                canAcceptCash = true,
                canAcceptOnline = true,
                canRefund = true,
                canVoid = true,
                createdAt = now
            )
        )

        database.userDao().insertUser(
            UserAccountEntity(
                id = unauthorizedActorId,
                businessId = businessId,
                displayName = "Refund Unauthorized Cashier",
                username = "refund_unauthorized",
                role = UserRole.CASHIER,
                authType = AuthType.PIN,
                credentialHash = "hash",
                credentialSalt = "salt",
                createdAt = now,
                updatedAt = now
            )
        )

        database.userDao().insertMembership(
            UserBranchMembershipEntity(
                id = "refund-unauthorized-membership",
                userId = unauthorizedActorId,
                branchId = branchId,
                role = UserRole.CASHIER,
                canCreateSale = true,
                canAcceptCash = true,
                canRefund = false,
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
    fun productionRefund_newOperationWithoutAuthenticatedSession_persistsNothing() =
        runBlocking {
            val tx = seedSale(
                transactionId = "refund-no-session",
                amountMinor = 4_000L
            )
            val command = newRefundCommand(
                transactionId = tx.id,
                amountMinor = 1_000L
            )

            SessionManager.clearSession()

            val result = repository.executeRefund(command)

            assertTrue(result is CommandResult.PermissionDenied)
            assertNull(
                database.operationDao()
                    .getOperationById(command.operationId.value)
            )
            assertTrue(
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .isEmpty()
            )
            assertTrue(refundOutflows(tx.id).isEmpty())
        }

    @Test
    fun productionRefund_inactiveActor_persistsNothing() =
        runBlocking {
            val tx = seedSale(
                transactionId = "refund-inactive-actor",
                amountMinor = 4_000L
            )
            val actor = checkNotNull(
                database.userDao().getUserById(actorId)
            )
            database.userDao().updateUser(
                actor.copy(
                    isActive = false,
                    updatedAt = actor.updatedAt + 1L
                )
            )
            val command = newRefundCommand(
                transactionId = tx.id,
                amountMinor = 1_000L
            )

            val result = repository.executeRefund(command)

            assertTrue(result is CommandResult.PermissionDenied)
            assertNull(
                database.operationDao()
                    .getOperationById(command.operationId.value)
            )
            assertTrue(
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .isEmpty()
            )
            assertTrue(refundOutflows(tx.id).isEmpty())
        }

    @Test
    fun productionRefund_mustChangePinActor_persistsNothing() =
        runBlocking {
            val tx = seedSale(
                transactionId = "refund-pin-change-actor",
                amountMinor = 4_000L
            )
            val actor = checkNotNull(
                database.userDao().getUserById(actorId)
            )
            database.userDao().updateUser(
                actor.copy(
                    mustChangePin = true,
                    updatedAt = actor.updatedAt + 1L
                )
            )
            val command = newRefundCommand(
                transactionId = tx.id,
                amountMinor = 1_000L
            )

            val result = repository.executeRefund(command)

            assertTrue(result is CommandResult.PermissionDenied)
            assertNull(
                database.operationDao()
                    .getOperationById(command.operationId.value)
            )
            assertTrue(
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .isEmpty()
            )
            assertTrue(refundOutflows(tx.id).isEmpty())
        }

    @Test
    fun productionRefund_partial_createsExactEventAndOutflow() = runBlocking {
        val tx = seedSale(
            transactionId = "refund-partial",
            amountMinor = 10_000L
        )

        val command = newRefundCommand(
            transactionId = tx.id,
            amountMinor = 3_000L
        )

        val result = repository.executeRefund(command)

        assertTrue(result is CommandResult.Success)

        val success = result as CommandResult.Success

        assertFalse(success.isIdempotentReplay)
        assertEquals(3_000L, success.data.refundAmountMinor)

        val events =
            database.transactionDao().getRefundEvents(tx.id)

        assertEquals(1, events.size)
        assertEquals(3_000L, events.single().refundAmountMinor)
        assertEquals(command.operationId.value, events.single().operationId)

        val outflows = refundOutflows(tx.id)

        assertEquals(1, outflows.size)
        assertEquals(-3_000L, outflows.single().amountSignedMinor)
        assertEquals(PaymentCategory.CASH, outflows.single().category)
        assertEquals(PaymentChannel.CASH_TENDER, outflows.single().channel)

        assertEquals(
            tx,
            database.transactionDao().getTransactionById(tx.id)
        )

        val view = FinancialReadModelResolver.resolve(
            database.transactionDao().getFinancialRecordById(tx.id)!!
        )

        assertEquals(3_000L, view.refundedAmount.amount)
        assertEquals(7_000L, view.netAmount.amount)

        val operation =
            database.operationDao()
                .getOperationById(command.operationId.value)

        assertEquals(OperationStatus.SUCCEEDED, operation?.status)
        assertEquals("REFUND_EVENT", operation?.resultReferenceType)
        assertEquals(events.single().id, operation?.resultReferenceId)

        val payload =
            RefundPayloadCodec.decode(operation!!.intentPayload)

        assertNotNull(payload.financialPrecondition)
        assertEquals(
            10_000L,
            payload.financialPrecondition
                ?.expectedRemainingRefundableMinor
        )
    }

    @Test
    fun productionRefund_partialPartialFull_reachesZeroWithoutRewritingSale() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-three-stage",
                amountMinor = 10_000L
            )

            assertTrue(
                repository.executeRefund(
                    newRefundCommand(
                        tx.id,
                        3_000L,
                        occurredAt = tx.createdAt + 1_000L
                    )
                ) is CommandResult.Success
            )

            assertTrue(
                repository.executeRefund(
                    newRefundCommand(
                        tx.id,
                        2_000L,
                        occurredAt = tx.createdAt + 2_000L
                    )
                ) is CommandResult.Success
            )

            assertTrue(
                repository.executeRefund(
                    newRefundCommand(
                        tx.id,
                        5_000L,
                        occurredAt = tx.createdAt + 3_000L
                    )
                ) is CommandResult.Success
            )

            val record =
                database.transactionDao()
                    .getFinancialRecordById(tx.id)!!

            val view =
                FinancialReadModelResolver.resolve(record)

            assertEquals(3, record.refundEvents.size)
            assertEquals(10_000L, view.refundedAmount.amount)
            assertEquals(0L, view.netAmount.amount)

            assertEquals(
                tx,
                database.transactionDao()
                    .getTransactionById(tx.id)
            )

            assertEquals(
                -10_000L,
                refundOutflows(tx.id)
                    .sumOf { it.amountSignedMinor }
            )
        }

    @Test
    fun productionRefund_overRefund_isBlockedWithoutFinancialMutation() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-over",
                amountMinor = 10_000L
            )

            val command =
                newRefundCommand(
                    tx.id,
                    10_001L
                )

            val result =
                repository.executeRefund(command)

            assertTrue(result is CommandResult.Error)

            assertTrue(
                (result as CommandResult.Error).exception
                    is RefundAmountExceedsRemainingException
            )

            assertTrue(
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .isEmpty()
            )

            assertTrue(
                refundOutflows(tx.id).isEmpty()
            )

            assertEquals(
                OperationStatus.FAILED,
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )
                    ?.status
            )
        }

    @Test
    fun productionRefund_afterVoid_isBlocked() = runBlocking {
        val tx = seedSale(
            transactionId = "refund-after-void",
            amountMinor = 8_000L
        )

        seedAuthoritativeVoid(
            transactionId = tx.id,
            amountMinor = 8_000L,
            occurredAt = tx.createdAt + 1_000L
        )

        val command =
            newRefundCommand(
                tx.id,
                1_000L,
                occurredAt = tx.createdAt + 2_000L
            )

        val result =
            repository.executeRefund(command)

        assertTrue(result is CommandResult.Error)

        assertTrue(
            (result as CommandResult.Error).exception
                is RefundAfterVoidException
        )

        assertTrue(
            database.transactionDao()
                .getRefundEvents(tx.id)
                .isEmpty()
        )
    }

    @Test
    fun productionRefund_sameOperation_replaysAndAlteredReplayConflicts() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-replay",
                amountMinor = 8_000L
            )

            val command =
                newRefundCommand(
                    transactionId = tx.id,
                    amountMinor = 2_000L,
                    reason = "Customer return"
                )

            val first =
                repository.executeRefund(command)

            assertTrue(first is CommandResult.Success)
            assertFalse(
                (first as CommandResult.Success)
                    .isIdempotentReplay
            )

            val second =
                repository.executeRefund(command)

            assertTrue(second is CommandResult.Success)

            assertTrue(
                (second as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                first.data,
                second.data
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .size
            )

            assertEquals(
                1,
                refundOutflows(tx.id).size
            )

            val altered =
                repository.executeRefund(
                    command.copy(
                        reason = "Different reason"
                    )
                )

            assertTrue(
                altered is CommandResult.Conflict
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .size
            )
        }

    @Test
    fun productionRefund_destinationMustMatchAuthoritativeFunding() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-destination",
                amountMinor = 6_000L
            )

            val command =
                newRefundCommand(
                    transactionId = tx.id,
                    amountMinor = 1_000L,
                    category = PaymentCategory.ONLINE,
                    channel = PaymentChannel.MANUAL_QR
                )

            val result =
                repository.executeRefund(command)

            assertTrue(result is CommandResult.Error)

            assertTrue(
                (result as CommandResult.Error).exception
                    is RefundDestinationMismatchException
            )

            assertTrue(
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .isEmpty()
            )
        }

    @Test
    fun productionRefund_onlineFunding_refundsBackToSameOnlineChannel() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-online",
                amountMinor = 7_000L,
                category = PaymentCategory.ONLINE,
                channel = PaymentChannel.MANUAL_QR
            )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 2_500L,
                        category = PaymentCategory.ONLINE,
                        channel = PaymentChannel.MANUAL_QR
                    )
                )

            assertTrue(result is CommandResult.Success)

            val outflow =
                refundOutflows(tx.id).single()

            assertEquals(-2_500L, outflow.amountSignedMinor)
            assertEquals(PaymentCategory.ONLINE, outflow.category)
            assertEquals(PaymentChannel.MANUAL_QR, outflow.channel)
        }

    @Test
    fun productionRefund_unauthorizedActor_persistsNothing() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-denied",
                amountMinor = 4_000L
            )

            val command =
                newRefundCommand(
                    transactionId = tx.id,
                    amountMinor = 1_000L,
                    actor = unauthorizedActorId
                )

            val result =
                repository.executeRefund(command)

            assertTrue(
                result is CommandResult.PermissionDenied
            )

            assertNull(
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )
            )

            assertTrue(
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .isEmpty()
            )

            assertTrue(refundOutflows(tx.id).isEmpty())
        }

    @Test
    fun productionRefund_settlementFailure_rollsBackRefundEvent() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-rollback",
                amountMinor = 9_000L
            )

            val command =
                newRefundCommand(
                    tx.id,
                    2_000L
                )

            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER test_abort_refund_outflow
                BEFORE INSERT ON settlement_entries
                WHEN NEW.entry_purpose = 'REFUND_OUTFLOW'
                BEGIN
                    SELECT RAISE(ABORT, 'forced Refund outflow failure');
                END
                """.trimIndent()
            )

            val result =
                try {
                    repository.executeRefund(command)
                } finally {
                    database.openHelper.writableDatabase.execSQL(
                        "DROP TRIGGER IF EXISTS test_abort_refund_outflow"
                    )
                }

            assertTrue(result is CommandResult.Error)

            assertTrue(
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .isEmpty()
            )

            assertTrue(
                refundOutflows(tx.id).isEmpty()
            )
        }

    @Test
    fun productionRefund_concurrentOperations_cannotOverRefund() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-concurrent",
                amountMinor = 10_000L
            )

            val commandA =
                newRefundCommand(
                    transactionId = tx.id,
                    amountMinor = 7_000L,
                    operationId = OperationId.generate()
                )

            val commandB =
                newRefundCommand(
                    transactionId = tx.id,
                    amountMinor = 7_000L,
                    operationId = OperationId.generate(),
                    occurredAt = commandA.occurredAt + 1L
                )

            val startGate =
                CompletableDeferred<Unit>()

            val first =
                async(Dispatchers.IO) {
                    startGate.await()
                    repository.executeRefund(commandA)
                }

            val second =
                async(Dispatchers.IO) {
                    startGate.await()
                    repository.executeRefund(commandB)
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

            assertEquals(
                1,
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .size
            )

            assertEquals(
                -7_000L,
                refundOutflows(tx.id)
                    .single()
                    .amountSignedMinor
            )

            val statuses =
                listOf(commandA, commandB)
                    .mapNotNull {
                        database.operationDao()
                            .getOperationById(
                                it.operationId.value
                            )
                            ?.status
                    }

            assertTrue(
                OperationStatus.SUCCEEDED in statuses
            )

            assertTrue(
                OperationStatus.REQUIRES_RECONCILIATION in statuses
            )
        }

    @Test
    fun productionRefund_pendingRecovery_completesAfterPermissionRevocation() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-recovery",
                amountMinor = 7_500L
            )

            val original =
                newRefundCommand(
                    transactionId = tx.id,
                    amountMinor = 2_000L
                )

            val admitted =
                insertAdmittedPendingRefund(original)

            val membership =
                checkNotNull(
                    database.userDao()
                        .getMembership(
                            actorId,
                            branchId
                        )
                )

            database.userDao()
                .updateMembership(
                    membership.copy(
                        canRefund = false
                    )
                )

            val recovered =
                repository.recoverPendingRefunds()

            assertEquals(1, recovered.size)
            assertEquals(
                2_000L,
                recovered.single()
                    .refundAmountMinor
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
                    .getRefundEvents(tx.id)
                    .size
            )

            assertTrue(
                repository.recoverPendingRefunds()
                    .isEmpty()
            )
        }

    @Test
    fun productionRefund_creditFundedTransaction_isBlockedWithoutFinancialMutation() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-boundary-credit-funded",
                amountMinor = 10_000L,
                category = PaymentCategory.CREDIT
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertRefundRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException =
                    RefundUnsupportedCreditException::class.java
            )
        }

    @Test
    fun productionRefund_unpaidCredit_reducesDebtWithoutCashOnlineOrCoupon() =
        runBlocking {

            val now =
                System.currentTimeMillis() -
                    20_000L

            val customer =
                CustomerEntity(
                    id =
                        "refund-credit-customer",
                    businessId =
                        businessId,
                    canonicalPhone =
                        "+96890000001",
                    name =
                        "Credit Return Customer",
                    createdAt =
                        now,
                    updatedAt =
                        now
                )

            database.customerDao()
                .insertCustomer(customer)

            val tx =
                seedSale(
                    transactionId =
                        "refund-unpaid-credit",
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.CREDIT,
                    channel =
                        PaymentChannel.CREDIT_ACCOUNT,
                    occurredAt =
                        now,
                    customerId =
                        customer.id
                )

            database.ledgerDao()
                .insertCreditEvent(
                    CreditLedgerEventEntity(
                        id =
                            "refund-unpaid-credit-obligation",
                        operationId =
                            "sale-operation-${tx.id}",
                        customerId =
                            customer.id,
                        type =
                            CreditLedgerType.OBLIGATION,
                        amountSignedMinor =
                            10_000L,
                        currencyCode =
                            "OMR",
                        transactionId =
                            tx.id,
                        repaymentId =
                            null,
                        occurredAt =
                            now
                    )
                )

            assertEquals(
                10_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customer.id,
                        "OMR"
                    )
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentAllocationsForTransaction(
                        tx.id
                    )
                    .isEmpty()
            )

            val command =
                newRefundCommand(
                    transactionId =
                        tx.id,
                    amountMinor =
                        3_000L,
                    category =
                        PaymentCategory.COUPON,
                    channel =
                        PaymentChannel.COUPON_WALLET,
                    couponDestination =
                        RefundCouponDestination(
                            customerId = customer.id,
                            customerName = customer.name,
                            canonicalPhone = customer.canonicalPhone,
                            createCustomer = false
                        ),
                    occurredAt =
                        now + 1_000L,
                    reason =
                        "Customer return before Credit payment"
                )

            val first =
                repository.executeRefund(
                    command
                )

            assertTrue(
                first is CommandResult.Success
            )

            val success =
                first as CommandResult.Success

            assertFalse(
                success.isIdempotentReplay
            )

            assertEquals(
                3_000L,
                success.data.refundAmountMinor
            )

            assertEquals(
                7_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customer.id,
                        "OMR"
                    )
            )

            // A Coupon destination must not manufacture store value from
            // the unpaid 3,000 minor units. No wallet or Coupon event is due.
            assertNull(
                database.ledgerDao().getCouponWallet(customer.id, "OMR")
            )
            assertTrue(
                database.ledgerDao().getCouponEventsForCustomer(customer.id).isEmpty()
            )

            val refundSettlements =
                refundOutflows(tx.id)

            /*
             * An unpaid Credit return has no externally collected value.
             * Production records the RefundEvent plus a CreditLedger
             * adjustment, but must not invent a REFUND_OUTFLOW settlement.
             */
            assertEquals(
                0,
                refundSettlements.size
            )

            val adjustments =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customer.id
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }

            assertEquals(
                1,
                adjustments.size
            )

            val adjustment =
                adjustments.single()

            assertEquals(
                CreditLedgerType.ADJUSTMENT,
                adjustment.type
            )

            assertEquals(
                -3_000L,
                adjustment.amountSignedMinor
            )

            assertEquals(
                tx.id,
                adjustment.transactionId
            )

            assertNull(
                adjustment.repaymentId
            )

            /*
             * Same operation must replay without creating duplicate
             * Refund or Credit evidence.
             */
            val replay =
                repository.executeRefund(
                    command
                )

            assertTrue(
                replay is CommandResult.Success
            )

            assertTrue(
                (replay as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                success.data,
                replay.data
            )

            assertEquals(
                7_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customer.id,
                        "OMR"
                    )
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getRefundEvents(
                        tx.id
                    )
                    .size
            )

            assertEquals(
                0,
                refundOutflows(tx.id).size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customer.id
                    )
                    .count {
                        it.operationId ==
                            command.operationId.value
                    }
            )
        }
    @Test
    fun productionRefund_couponFundedTransaction_isBlockedWithoutFinancialMutation() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-boundary-coupon-funded",
                amountMinor = 10_000L,
                category = PaymentCategory.COUPON
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertRefundRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException =
                    RefundUnsupportedCouponException::class.java
            )
        }

    @Test
    fun productionRefund_paymentMethodChangeExists_isBlockedWithoutFinancialMutation() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-boundary-payment-change",
                amountMinor = 10_000L
            )

            val changeTime =
                tx.createdAt + 1_000L

            val operationId =
                "payment-change-operation-${tx.id}"

            insertOperation(
                id = operationId,
                type =
                    OperationType.RECORD_PAYMENT_METHOD_CHANGE,
                transactionId = tx.id,
                occurredAt = changeTime
            )

            database.transactionDao()
                .insertPaymentMethodChange(
                    PaymentMethodChangeEventEntity(
                        id =
                            "payment-change-event-${tx.id}",
                        transactionId =
                            tx.id,
                        operationId =
                            operationId,
                        previousMethod =
                            PaymentCategory.CASH,
                        previousChannel =
                            PaymentChannel.CASH_TENDER,
                        newMethod =
                            PaymentCategory.ONLINE,
                        newChannel =
                            PaymentChannel.MANUAL_QR,
                        reason =
                            "Refund boundary test",
                        actorUserId =
                            actorId,
                        occurredAt =
                            changeTime
                    )
                )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = changeTime + 1_000L
                    )
                )

            assertRefundRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException =
                    RefundAmbiguousSettlementException::class.java
            )
        }

    @Test
    fun productionRefund_creditRepaymentAllocationExists_isBlockedWithoutFinancialMutation() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-boundary-credit-allocation",
                amountMinor = 10_000L
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            seedAdversarialCreditRepaymentAllocation(
                transactionId = tx.id,
                occurredAt = tx.createdAt + 1_000L
            )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = tx.createdAt + 2_000L
                    )
                )

            /*
             * This is deliberately malformed evidence:
             * a Cash-funded sale has an adversarial Credit repayment
             * allocation attached to it.
             *
             * Current hardened validation fails closed as unsupported
             * Credit lifecycle. The important contract is zero mutation,
             * not the historical exception subtype.
             */
            assertRefundRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException =
                    RefundUnsupportedCreditException::class.java
            )
        }

    @Test
    fun productionRefund_mixedCashAndOnlineFunding_isBlockedAsAmbiguous() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-boundary-mixed-funding",
                amountMinor = 10_000L,
                fundingAmountMinor = 5_000L
            )

            database.transactionDao()
                .insertSettlement(
                    SettlementEntryEntity(
                        id =
                            "sale-settlement-online-${tx.id}",
                        operationId =
                            "sale-operation-${tx.id}",
                        subjectType =
                            SubjectType.TRANSACTION,
                        subjectId =
                            tx.id,
                        transactionId =
                            tx.id,
                        entryPurpose =
                            EntryPurpose.SALE_FUNDING,
                        category =
                            PaymentCategory.ONLINE,
                        channel =
                            PaymentChannel.MANUAL_QR,
                        amountSignedMinor =
                            5_000L,
                        currencyCode =
                            "OMR",
                        confirmationType =
                            ConfirmationType.CASHIER_CONFIRMED,
                        occurredAt =
                            tx.createdAt,
                        actorUserId =
                            actorId
                    )
                )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertRefundRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException =
                    RefundAmbiguousSettlementException::class.java
            )
        }

    @Test
    fun productionRefund_missingSaleFunding_isBlockedAsAmbiguous() =
        runBlocking {

            val tx = seedSale(
                transactionId = "refund-boundary-missing-funding",
                amountMinor = 10_000L,
                includeFundingSettlement = false
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            assertTrue(settlementsBefore.isEmpty())

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = tx.createdAt + 1_000L
                    )
                )

            assertRefundRejectedPreservingOriginal(
                original = tx,
                settlementsBefore = settlementsBefore,
                result = result,
                expectedException =
                    RefundAmbiguousSettlementException::class.java
            )
        }
    @Test
    fun productionRefund_splitHistoricalRefundOutflows_failClosed() =
        runBlocking {
            val tx = seedSale(
                transactionId = "refund-split-history",
                amountMinor = 10_000L
            )

            val refundAt = tx.createdAt + 1_000L

            seedHistoricalRefundEvidence(
                transactionId = tx.id,
                refundAmountMinor = 3_000L,
                occurredAt = refundAt,
                settlementParts = listOf(-1_000L, -2_000L)
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = refundAt + 1_000L
                    )
                )

            assertTrue(result is CommandResult.Error)
            assertTrue(
                (result as CommandResult.Error).exception
                    is RefundAmbiguousSettlementException
            )
            assertEquals(
                1,
                database.transactionDao()
                    .getRefundEvents(tx.id)
                    .size
            )
            assertEquals(2, refundOutflows(tx.id).size)
            assertEquals(
                settlementsBefore,
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)
            )
            assertEquals(
                tx,
                database.transactionDao()
                    .getTransactionById(tx.id)
            )
        }

    @Test
    fun productionRefund_historicalRefundWrongFundingIdentity_failsClosed() =
        runBlocking {
            val tx = seedSale(
                transactionId = "refund-wrong-funding-history",
                amountMinor = 10_000L
            )

            val refundAt = tx.createdAt + 1_000L

            seedHistoricalRefundEvidence(
                transactionId = tx.id,
                refundAmountMinor = 3_000L,
                occurredAt = refundAt,
                settlementCategory = PaymentCategory.ONLINE,
                settlementChannel = PaymentChannel.MANUAL_QR
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 1_000L,
                        occurredAt = refundAt + 1_000L
                    )
                )

            assertTrue(result is CommandResult.Error)
            assertTrue(
                (result as CommandResult.Error).exception
                    is RefundAmbiguousSettlementException
            )
            assertEquals(1, refundOutflows(tx.id).size)
            assertEquals(
                settlementsBefore,
                database.transactionDao()
                    .getSettlementsForTransaction(tx.id)
            )
        }

    @Test
    fun productionRefund_historicalRefundActorOrTimestampMismatch_failsClosed() =
        runBlocking {
            suspend fun verifyRejected(
                transactionId: String,
                settlementActor: String,
                settlementTimeDelta: Long
            ) {
                val tx = seedSale(
                    transactionId = transactionId,
                    amountMinor = 10_000L
                )
                val refundAt = tx.createdAt + 1_000L

                seedHistoricalRefundEvidence(
                    transactionId = tx.id,
                    refundAmountMinor = 3_000L,
                    occurredAt = refundAt,
                    settlementActorUserId = settlementActor,
                    settlementOccurredAt =
                        refundAt + settlementTimeDelta
                )

                val settlementsBefore =
                    database.transactionDao()
                        .getSettlementsForTransaction(tx.id)

                val result =
                    repository.executeRefund(
                        newRefundCommand(
                            transactionId = tx.id,
                            amountMinor = 1_000L,
                            occurredAt = refundAt + 2_000L
                        )
                    )

                assertTrue(result is CommandResult.Error)
                assertTrue(
                    (result as CommandResult.Error).exception
                        is RefundAmbiguousSettlementException
                )
                assertEquals(1, refundOutflows(tx.id).size)
                assertEquals(
                    settlementsBefore,
                    database.transactionDao()
                        .getSettlementsForTransaction(tx.id)
                )
            }

            verifyRejected(
                transactionId = "refund-wrong-actor-history",
                settlementActor = unauthorizedActorId,
                settlementTimeDelta = 0L
            )

            verifyRejected(
                transactionId = "refund-wrong-time-history",
                settlementActor = actorId,
                settlementTimeDelta = 1L
            )
        }

    @Test
    fun productionRefund_realWriter_makesOlderSaleAppearInTodayRecent() =
        runBlocking {

            val zone =
                ZoneId.of("Asia/Muscat")

            val todayStart =
                LocalDate.now(zone)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()

            val oldSaleTime =
                todayStart - 60_000L

            val refundTime =
                todayStart + 1_000L

            val tx =
                seedSale(
                    transactionId =
                        "refund-real-recent",
                    amountMinor =
                        10_000L,
                    occurredAt =
                        oldSaleTime
                )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = tx.id,
                        amountMinor = 3_000L,
                        occurredAt = refundTime
                    )
                )

            assertTrue(
                result is CommandResult.Success
            )

            val recent =
                repository.observeTodayTransactions(
                    branchId,
                    "Asia/Muscat"
                ).first()

            val projected =
                recent.single {
                    it.transactionId == tx.id &&
                        it.type == com.dailymoneyregister.core.model.financial.RecentActivityType.REFUND
                }

            assertEquals(
                refundTime,
                projected.occurredAt
            )

            assertEquals(
                -3_000L,
                projected.signedEffect.amount
            )

            assertEquals(
                tx.id,
                projected.originalTransactionId
            )
        }


    // ============================================================
    // BOARDLOCK_CREDIT_RETURN_B2_B_INTEGRATION_TESTS
    // ============================================================

    @Test
    fun productionRefund_b2b_returnBelowUnpaidCredit_reducesDebtOnly() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "debt-only",
                    cashRepaymentMinor = 4_000L
                )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId = scenario.transaction.id,
                        amountMinor = 3_000L,
                        category = PaymentCategory.CREDIT,
                        channel = PaymentChannel.CREDIT_ACCOUNT,
                        occurredAt = scenario.returnAt
                    )
                )

            assertTrue(result is CommandResult.Success)

            assertEquals(
                3_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(1, outflows.size)

            assertEquals(
                PaymentCategory.CREDIT,
                outflows.single().category
            )

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                outflows.single().channel
            )

            assertEquals(
                -3_000L,
                outflows.single().amountSignedMinor
            )

            assertTrue(
                outflows.none {
                    it.category ==
                        PaymentCategory.CASH ||
                    it.category ==
                        PaymentCategory.ONLINE ||
                    it.category ==
                        PaymentCategory.COUPON
                }
            )


        }

    @Test
    fun productionRefund_b2b_returnExactlyUnpaidCredit_extinguishesDebtWithoutPaidValueRefund() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "exact-unpaid",
                    cashRepaymentMinor = 4_000L
                )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId =
                            scenario.transaction.id,
                        amountMinor =
                            6_000L,
                        category =
                            PaymentCategory.CREDIT,
                        channel =
                            PaymentChannel.CREDIT_ACCOUNT,
                        occurredAt =
                            scenario.returnAt
                    )
                )

            assertTrue(
                result is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(1, outflows.size)

            assertEquals(
                PaymentCategory.CREDIT,
                outflows.single().category
            )

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                outflows.single().channel
            )

            assertEquals(
                -6_000L,
                outflows.single().amountSignedMinor
            )
        }

    @Test
    fun productionRefund_b2b_returnCrossesUnpaidBoundary_refundsOnlyPaidExcess() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "cross-boundary",
                    cashRepaymentMinor = 4_000L
                )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId =
                            scenario.transaction.id,
                        amountMinor =
                            8_000L,
                        category =
                            PaymentCategory.CREDIT,
                        channel =
                            PaymentChannel.CREDIT_ACCOUNT,
                        occurredAt =
                            scenario.returnAt
                    )
                )

            assertTrue(
                result is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(2, outflows.size)

            val credit =
                outflows.single {
                    it.category ==
                        PaymentCategory.CREDIT
                }

            val cash =
                outflows.single {
                    it.category ==
                        PaymentCategory.CASH
                }

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                credit.channel
            )

            assertEquals(
                -6_000L,
                credit.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CASH_TENDER,
                cash.channel
            )

            assertEquals(
                -2_000L,
                cash.amountSignedMinor
            )

            assertEquals(
                -8_000L,
                outflows.sumOf {
                    it.amountSignedMinor
                }
            )

            assertTrue(
                outflows.none {
                    it.category ==
                        PaymentCategory.CASH &&
                    it.amountSignedMinor ==
                        -8_000L
                }
            )
        }

    @Test
    fun productionRefund_b2b_fullReturn_refundsOnlyActuallyPaidValue() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "full-return",
                    cashRepaymentMinor = 4_000L
                )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId =
                            scenario.transaction.id,
                        amountMinor =
                            10_000L,
                        category =
                            PaymentCategory.CREDIT,
                        channel =
                            PaymentChannel.CREDIT_ACCOUNT,
                        occurredAt =
                            scenario.returnAt
                    )
                )

            assertTrue(
                result is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(2, outflows.size)

            val credit =
                outflows.single {
                    it.category ==
                        PaymentCategory.CREDIT
                }

            val cash =
                outflows.single {
                    it.category ==
                        PaymentCategory.CASH
                }

            assertEquals(
                -6_000L,
                credit.amountSignedMinor
            )

            assertEquals(
                -4_000L,
                cash.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CASH_TENDER,
                cash.channel
            )

            assertTrue(
                outflows.none {
                    it.category ==
                        PaymentCategory.CASH &&
                    it.amountSignedMinor ==
                        -10_000L
                }
            )

            assertEquals(
                10_000L,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .single()
                    .refundAmountMinor
            )
        }

    @Test
    fun productionRefund_b2b_mixedRepaymentFunding_preservesCashAndOnlineIdentity() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "mixed-funding",
                    cashRepaymentMinor = 2_000L,
                    onlineRepaymentMinor = 2_000L
                )

            val result =
                repository.executeRefund(
                    newRefundCommand(
                        transactionId =
                            scenario.transaction.id,
                        amountMinor =
                            10_000L,
                        category =
                            PaymentCategory.CREDIT,
                        channel =
                            PaymentChannel.CREDIT_ACCOUNT,
                        occurredAt =
                            scenario.returnAt
                    )
                )

            assertTrue(
                result is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(3, outflows.size)

            val credit =
                outflows.single {
                    it.category ==
                        PaymentCategory.CREDIT
                }

            val cash =
                outflows.single {
                    it.category ==
                        PaymentCategory.CASH
                }

            val online =
                outflows.single {
                    it.category ==
                        PaymentCategory.ONLINE
                }

            assertEquals(
                -6_000L,
                credit.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                credit.channel
            )

            assertEquals(
                -2_000L,
                cash.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CASH_TENDER,
                cash.channel
            )

            assertEquals(
                -2_000L,
                online.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.MANUAL_QR,
                online.channel
            )

            assertTrue(
                outflows.none {
                    it.amountSignedMinor ==
                        -4_000L &&
                    (
                        it.category ==
                            PaymentCategory.CASH ||
                        it.category ==
                            PaymentCategory.ONLINE
                    )
                }
            )

            assertEquals(
                -10_000L,
                outflows.sumOf {
                    it.amountSignedMinor
                }
            )
        }

    @Test
    fun productionRefund_b2b_sameOperation_replaysWithoutDuplicateFinancialEvidence() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "replay",
                    cashRepaymentMinor = 4_000L
                )

            val command =
                newRefundCommand(
                    transactionId =
                        scenario.transaction.id,
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.CREDIT,
                    channel =
                        PaymentChannel.CREDIT_ACCOUNT,
                    occurredAt =
                        scenario.returnAt
                )

            val first =
                repository.executeRefund(
                    command
                )

            assertTrue(
                first is CommandResult.Success
            )

            assertFalse(
                (first as CommandResult.Success)
                    .isIdempotentReplay
            )

            val refundCountBefore =
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size

            val outflowsBefore =
                refundOutflows(
                    scenario.transaction.id
                )

            val creditEventsBefore =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        scenario.customer.id
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }

            val second =
                repository.executeRefund(
                    command
                )

            assertTrue(
                second is CommandResult.Success
            )

            assertTrue(
                (second as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                first.data,
                second.data
            )

            assertEquals(
                refundCountBefore,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size
            )

            assertEquals(
                outflowsBefore,
                refundOutflows(
                    scenario.transaction.id
                )
            )

            assertEquals(
                creditEventsBefore,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        scenario.customer.id
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size
            )

            assertEquals(
                2,
                refundOutflows(
                    scenario.transaction.id
                ).size
            )

            assertEquals(
                1,
                creditEventsBefore.size
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )
        }

    /*
     * BOARDLOCK B2 destination-selection regression tests.
     *
     * Original Credit sale = 10.000 OMR
     * Repaid value         =  5.000 OMR
     * Full return          = 10.000 OMR
     *
     * Authoritative economic result:
     *
     * unpaid Credit reduction = 5.000 OMR
     * paid-value refund       = 5.000 OMR
     *
     * The cashier-selected destination applies only to the paid-value
     * refund. Total economic reversal must remain exactly 10.000 OMR.
     */

    @Test
    fun productionRefund_b2_destinationCash_refundsOnlyPaidValueToCash() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "destination-cash",
                    cashRepaymentMinor = 5_000L
                )

            val command =
                newRefundCommand(
                    transactionId =
                        scenario.transaction.id,
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.CASH,
                    channel =
                        PaymentChannel.CASH_TENDER,
                    occurredAt =
                        scenario.returnAt,
                    reason =
                        "B2 destination Cash"
                )

            val result =
                repository.executeRefund(command)

            assertTrue(
                "Expected successful B2 Cash destination, got: $result",
                result is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(2, outflows.size)

            val credit =
                outflows.single {
                    it.category ==
                        PaymentCategory.CREDIT
                }

            val cash =
                outflows.single {
                    it.category ==
                        PaymentCategory.CASH
                }

            assertEquals(
                -5_000L,
                credit.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                credit.channel
            )

            assertEquals(
                -5_000L,
                cash.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CASH_TENDER,
                cash.channel
            )

            assertEquals(
                -10_000L,
                outflows.sumOf {
                    it.amountSignedMinor
                }
            )

            assertEquals(
                10_000L,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .single()
                    .refundAmountMinor
            )

            assertNull(
                database.ledgerDao()
                    .getCouponWallet(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            /*
             * BOARDLOCK replay gate.
             *
             * Re-executing the same durable Refund operation must resolve
             * the existing evidence. It must not create another RefundEvent,
             * Credit adjustment or settlement outflow.
             */
            val refundCountBeforeReplay =
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size

            val outflowsBeforeReplay =
                refundOutflows(
                    scenario.transaction.id
                )

            val second =
                repository.executeRefund(command)

            assertTrue(
                "Expected successful B2 Cash replay, got: $second",
                second is CommandResult.Success
            )

            assertTrue(
                (second as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                (result as CommandResult.Success).data,
                second.data
            )

            assertEquals(
                refundCountBeforeReplay,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size
            )

            assertEquals(
                outflowsBeforeReplay,
                refundOutflows(
                    scenario.transaction.id
                )
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            assertNull(
                database.ledgerDao()
                    .getCouponWallet(
                        scenario.customer.id,
                        "OMR"
                    )
            )
        }

    @Test
    fun productionRefund_b2_destinationOnline_refundsOnlyPaidValueToOnline() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "destination-online",
                    cashRepaymentMinor = 5_000L
                )

            val command =
                newRefundCommand(
                    transactionId =
                        scenario.transaction.id,
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.ONLINE,
                    channel =
                        PaymentChannel.MANUAL_QR,
                    occurredAt =
                        scenario.returnAt,
                    reason =
                        "B2 destination Online"
                )

            val result =
                repository.executeRefund(command)

            assertTrue(
                /*
                 * This test is expected to expose the current B2 restriction
                 * until production supports cashier-selected Online refunds.
                 */
                "Expected successful B2 Online destination, got: $result",
                result is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(2, outflows.size)

            val credit =
                outflows.single {
                    it.category ==
                        PaymentCategory.CREDIT
                }

            val online =
                outflows.single {
                    it.category ==
                        PaymentCategory.ONLINE
                }

            assertEquals(
                -5_000L,
                credit.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                credit.channel
            )

            assertEquals(
                -5_000L,
                online.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.MANUAL_QR,
                online.channel
            )

            assertEquals(
                -10_000L,
                outflows.sumOf {
                    it.amountSignedMinor
                }
            )

            assertEquals(
                10_000L,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .single()
                    .refundAmountMinor
            )

            assertNull(
                database.ledgerDao()
                    .getCouponWallet(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            /*
             * BOARDLOCK replay gate.
             *
             * Re-executing the same durable Refund operation must preserve
             * the selected Online destination exactly and create no duplicate
             * financial evidence.
             */
            val refundCountBeforeReplay =
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size

            val outflowsBeforeReplay =
                refundOutflows(
                    scenario.transaction.id
                )

            val second =
                repository.executeRefund(command)

            assertTrue(
                "Expected successful B2 Online replay, got: $second",
                second is CommandResult.Success
            )

            assertTrue(
                (second as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                (result as CommandResult.Success).data,
                second.data
            )

            assertEquals(
                refundCountBeforeReplay,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size
            )

            assertEquals(
                outflowsBeforeReplay,
                refundOutflows(
                    scenario.transaction.id
                )
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            assertNull(
                database.ledgerDao()
                    .getCouponWallet(
                        scenario.customer.id,
                        "OMR"
                    )
            )
        }

    @Test
    fun productionRefund_b2_destinationCoupon_refundsOnlyPaidValueToCoupon() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "destination-coupon",
                    cashRepaymentMinor = 5_000L
                )

            val command =
                newRefundCommand(
                    transactionId =
                        scenario.transaction.id,
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.COUPON,
                    channel =
                        PaymentChannel.COUPON_WALLET,
                    couponDestination =
                        RefundCouponDestination(
                            customerId = scenario.customer.id,
                            customerName = scenario.customer.name,
                            canonicalPhone = scenario.customer.canonicalPhone,
                            createCustomer = false
                        ),
                    occurredAt =
                        scenario.returnAt,
                    reason =
                        "B2 destination Coupon"
                )

            val first =
                repository.executeRefund(command)

            assertTrue(
                "Expected successful B2 Coupon destination, got: $first",
                first is CommandResult.Success
            )

            assertFalse(
                (first as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val wallet =
                checkNotNull(
                    database.ledgerDao()
                        .getCouponWallet(
                            scenario.customer.id,
                            "OMR"
                        )
                )

            assertEquals(
                5_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        wallet.id
                    )
            )

            val couponEventsBefore =
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                1,
                couponEventsBefore.size
            )

            assertEquals(
                5_000L,
                couponEventsBefore.single()
                    .amountSignedMinor
            )

            val outflowsBefore =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(
                2,
                outflowsBefore.size
            )

            assertEquals(
                -5_000L,
                outflowsBefore.single {
                    it.category ==
                        PaymentCategory.CREDIT
                }.amountSignedMinor
            )

            /*
             * BOARDLOCK B2 destination semantics:
             *
             * Historical repayment funding proves how much paid value is
             * eligible for refund. It does not dictate where the cashier
             * sends that refund now.
             *
             * Coupon was selected, so the paid-value REFUND_OUTFLOW must be
             * Coupon, while the Coupon ledger event proves delivery of that
             * same 5.000 OMR to the wallet.
             */
            val couponOutflow =
                outflowsBefore.single {
                    it.category ==
                        PaymentCategory.COUPON
                }

            assertEquals(
                -5_000L,
                couponOutflow.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.COUPON_WALLET,
                couponOutflow.channel
            )

            assertTrue(
                outflowsBefore.none {
                    it.category ==
                        PaymentCategory.CASH
                }
            )

            assertEquals(
                -10_000L,
                outflowsBefore.sumOf {
                    it.amountSignedMinor
                }
            )

            assertEquals(
                10_000L,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .single()
                    .refundAmountMinor
            )

            val refundCountBefore =
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size

            val second =
                repository.executeRefund(command)

            assertTrue(
                second is CommandResult.Success
            )

            assertTrue(
                (second as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                first.data,
                second.data
            )

            assertEquals(
                refundCountBefore,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size
            )

            assertEquals(
                outflowsBefore,
                refundOutflows(
                    scenario.transaction.id
                )
            )

            assertEquals(
                couponEventsBefore,
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )
            )

            assertEquals(
                5_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        wallet.id
                    )
            )
        }
    @Test
    fun productionRefund_b2b_fullReturnToCoupon_creditsOnlyPaidValueAndNeverCreatesDoubleValue() =
        runBlocking {

            /*
             * BOARDLOCK regression:
             *
             * 10.000 OMR original Credit sale
             * 5.000 OMR already repaid
             * 10.000 OMR full return
             * destination = Coupon
             *
             * Economic result MUST be:
             *
             * 5.000 OMR remaining Credit extinguished
             * 5.000 OMR paid value credited to Coupon
             *
             * It must NEVER credit 10.000 OMR to Coupon while also
             * extinguishing the remaining 5.000 OMR Credit.
             */
            val scenario =
                seedB2CreditScenario(
                    suffix = "full-return-to-coupon",
                    cashRepaymentMinor = 5_000L
                )

            assertEquals(
                5_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            assertNull(
                database.ledgerDao()
                    .getCouponWallet(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            val command =
                newRefundCommand(
                    transactionId =
                        scenario.transaction.id,
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.COUPON,
                    channel =
                        PaymentChannel.COUPON_WALLET,
                    couponDestination =
                        RefundCouponDestination(
                            customerId = scenario.customer.id,
                            customerName = scenario.customer.name,
                            canonicalPhone = scenario.customer.canonicalPhone,
                            createCustomer = false
                        ),
                    occurredAt =
                        scenario.returnAt,
                    reason =
                        "Full Credit return to Coupon"
                )

            val first =
                repository.executeRefund(
                    command
                )

            assertTrue(
                "Expected successful Credit return to Coupon, got: $first",
                first is CommandResult.Success
            )

            val success =
                first as CommandResult.Success

            assertFalse(
                success.isIdempotentReplay
            )

            /*
             * Remaining unpaid Credit is extinguished.
             */
            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            /*
             * Only the 5.000 OMR actually paid by the customer may become
             * Coupon value.
             */
            val wallet =
                checkNotNull(
                    database.ledgerDao()
                        .getCouponWallet(
                            scenario.customer.id,
                            "OMR"
                        )
                )

            assertEquals(
                5_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        wallet.id
                    )
            )

            val couponEvents =
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                1,
                couponEvents.size
            )

            assertEquals(
                5_000L,
                couponEvents.single()
                    .amountSignedMinor
            )

            assertEquals(
                scenario.transaction.id,
                couponEvents.single()
                    .transactionId
            )

            /*
             * The Refund remains the full economic reversal of the original
             * 10.000 OMR sale.
             */
            assertEquals(
                10_000L,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .single()
                    .refundAmountMinor
            )

            /*
             * Settlement evidence must describe exactly:
             *
             * 5.000 Credit reduction
             * 5.000 Coupon-destination paid-value reversal
             *
             * The Coupon ledger event separately proves that the refundable
             * paid value was credited to the Coupon destination.
             *
             * Total settlement reversal = 10.000, never 15.000.
             */
            val outflows =
                refundOutflows(
                    scenario.transaction.id
                )

            assertEquals(
                2,
                outflows.size
            )

            val creditOutflow =
                outflows.single {
                    it.operationId ==
                        command.operationId.value &&
                    it.category ==
                        PaymentCategory.CREDIT
                }

            val paidValueOutflow =
                outflows.single {
                    it.operationId ==
                        command.operationId.value &&
                    it.category ==
                        PaymentCategory.COUPON &&
                    it.channel ==
                        PaymentChannel.COUPON_WALLET
                }

            assertEquals(
                -5_000L,
                creditOutflow.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                creditOutflow.channel
            )

            assertEquals(
                -5_000L,
                paidValueOutflow.amountSignedMinor
            )

            assertEquals(
                PaymentChannel.COUPON_WALLET,
                paidValueOutflow.channel
            )

            assertEquals(
                -10_000L,
                outflows
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }
                    .sumOf {
                        it.amountSignedMinor
                    }
            )

            val creditAdjustments =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        scenario.customer.id
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }

            assertEquals(
                1,
                creditAdjustments.size
            )

            assertEquals(
                CreditLedgerType.ADJUSTMENT,
                creditAdjustments.single().type
            )

            assertEquals(
                -5_000L,
                creditAdjustments.single()
                    .amountSignedMinor
            )

            /*
             * Replay must not create another Coupon credit, Credit
             * adjustment, RefundEvent or settlement.
             */
            val replay =
                repository.executeRefund(
                    command
                )

            assertTrue(
                "Expected idempotent replay, got: $replay",
                replay is CommandResult.Success
            )

            assertTrue(
                (replay as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertEquals(
                success.data,
                replay.data
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            assertEquals(
                5_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        wallet.id
                    )
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
                    .size
            )

            assertEquals(
                2,
                refundOutflows(
                    scenario.transaction.id
                )
                    .count {
                        it.operationId ==
                            command.operationId.value
                    }
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        scenario.customer.id
                    )
                    .count {
                        it.operationId ==
                            command.operationId.value
                    }
            )
        }
    @Test
    fun productionRefund_b2b_missingRepaymentEvidence_failsClosedWithoutFinancialMutation() =
        runBlocking {

            val now =
                System.currentTimeMillis() -
                    30_000L

            val customer =
                CustomerEntity(
                    id =
                        "b2b-malformed-customer",
                    businessId =
                        businessId,
                    canonicalPhone =
                        "+96892000007",
                    name =
                        "B2B Malformed Customer",
                    createdAt =
                        now,
                    updatedAt =
                        now
                )

            database.customerDao()
                .insertCustomer(customer)

            val tx =
                seedSale(
                    transactionId =
                        "b2b-malformed-sale",
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.CREDIT,
                    channel =
                        PaymentChannel.CREDIT_ACCOUNT,
                    occurredAt =
                        now,
                    customerId =
                        customer.id
                )

            database.ledgerDao()
                .insertCreditEvent(
                    CreditLedgerEventEntity(
                        id =
                            "b2b-malformed-obligation",
                        operationId =
                            "sale-operation-${tx.id}",
                        customerId =
                            customer.id,
                        type =
                            CreditLedgerType.OBLIGATION,
                        amountSignedMinor =
                            10_000L,
                        currencyCode =
                            "OMR",
                        transactionId =
                            tx.id,
                        repaymentId =
                            null,
                        occurredAt =
                            now
                    )
                )

            /*
             * Deliberately orphan the allocation from its repayment record.
             * Foreign keys are temporarily disabled only inside this
             * in-memory test database to construct malformed historical
             * evidence. No real application database is involved.
             */
            val writable =
                database.openHelper
                    .writableDatabase

            writable.execSQL(
                "PRAGMA foreign_keys = OFF"
            )

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
                        "b2b-malformed-allocation",
                        "b2b-missing-repayment",
                        tx.id,
                        4_000L,
                        now + 1_000L
                    )
                )
            } finally {
                writable.execSQL(
                    "PRAGMA foreign_keys = ON"
                )
            }

            val originalBefore =
                database.transactionDao()
                    .getTransactionById(tx.id)

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForTransaction(
                        tx.id
                    )

            val ledgerBefore =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customer.id
                    )

            val allocationsBefore =
                database.ledgerDao()
                    .getCreditRepaymentAllocationsForTransaction(
                        tx.id
                    )

            val command =
                newRefundCommand(
                    transactionId =
                        tx.id,
                    amountMinor =
                        8_000L,
                    category =
                        PaymentCategory.CREDIT,
                    channel =
                        PaymentChannel.CREDIT_ACCOUNT,
                    occurredAt =
                        now + 2_000L
                )

            val result =
                repository.executeRefund(
                    command
                )

            /*
             * BOARDLOCK:
             * malformed authoritative repayment evidence is not an ordinary
             * command failure after durable admission.
             *
             * CommandExecutor must preserve the durable operation as
             * REQUIRES_RECONCILIATION and surface CommandResult.Conflict.
             *
             * The assertions below still prove that no Refund financial
             * evidence was materialized.
             */
            assertTrue(
                result is CommandResult.Conflict
            )

            assertTrue(
                (result as CommandResult.Conflict)
                    .message
                    .contains(
                        "requires reconciliation",
                        ignoreCase = true
                    )
            )

            assertTrue(
                database.transactionDao()
                    .getRefundEvents(
                        tx.id
                    )
                    .isEmpty()
            )

            assertEquals(
                settlementsBefore,
                database.transactionDao()
                    .getSettlementsForTransaction(
                        tx.id
                    )
            )

            assertEquals(
                ledgerBefore,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customer.id
                    )
            )

            assertEquals(
                allocationsBefore,
                database.ledgerDao()
                    .getCreditRepaymentAllocationsForTransaction(
                        tx.id
                    )
            )

            assertEquals(
                originalBefore,
                database.transactionDao()
                    .getTransactionById(
                        tx.id
                    )
            )

            assertTrue(
                refundOutflows(
                    tx.id
                ).isEmpty()
            )
        }

    @Test
    fun productionRefund_b2_couponFundedRepayment_selectedDestinationsNeverCreateDoubleValue() =
        runBlocking {

            data class Destination(
                val suffix: String,
                val category: PaymentCategory,
                val channel: PaymentChannel
            )

            val destinations =
                listOf(
                    Destination(
                        suffix = "coupon-funded-to-cash",
                        category = PaymentCategory.CASH,
                        channel = PaymentChannel.CASH_TENDER
                    ),
                    Destination(
                        suffix = "coupon-funded-to-online",
                        category = PaymentCategory.ONLINE,
                        channel = PaymentChannel.MANUAL_QR
                    ),
                    Destination(
                        suffix = "coupon-funded-to-coupon",
                        category = PaymentCategory.COUPON,
                        channel = PaymentChannel.COUPON_WALLET
                    )
                )

            for (destination in destinations) {

                val scenario =
                    seedB2CreditScenario(
                        suffix = destination.suffix,
                        couponRepaymentMinor = 5_000L
                    )

                assertEquals(
                    5_000L,
                    database.ledgerDao()
                        .getCreditBalance(
                            scenario.customer.id,
                            "OMR"
                        )
                )

                val command =
                    newRefundCommand(
                        transactionId =
                            scenario.transaction.id,
                        amountMinor =
                            10_000L,
                        category =
                            destination.category,
                        channel =
                            destination.channel,
                        couponDestination =
                            if (destination.category == PaymentCategory.COUPON) {
                                RefundCouponDestination(
                                    customerId = scenario.customer.id,
                                    customerName = scenario.customer.name,
                                    canonicalPhone = scenario.customer.canonicalPhone,
                                    createCustomer = false
                                )
                            } else {
                                null
                            },
                        occurredAt =
                            scenario.returnAt,
                        reason =
                            "B2 Coupon-funded selected destination"
                    )

                val first =
                    repository.executeRefund(command)

                assertTrue(
                    "Expected successful Coupon-funded B2 return to ${destination.category}, got: $first",
                    first is CommandResult.Success
                )

                assertFalse(
                    (first as CommandResult.Success)
                        .isIdempotentReplay
                )

                /*
                 * Full 10.000 return after 5.000 repayment:
                 *
                 * 5.000 unpaid Credit reduction
                 * 5.000 paid-value refund to selected destination
                 *
                 * Never 15.000 of economic reversal.
                 */
                assertEquals(
                    0L,
                    database.ledgerDao()
                        .getCreditBalance(
                            scenario.customer.id,
                            "OMR"
                        )
                )

                val outflowsBefore =
                    refundOutflows(
                        scenario.transaction.id
                    )

                assertEquals(
                    2,
                    outflowsBefore.size
                )

                val creditOutflow =
                    outflowsBefore.single {
                        it.category ==
                            PaymentCategory.CREDIT
                    }

                assertEquals(
                    -5_000L,
                    creditOutflow.amountSignedMinor
                )

                assertEquals(
                    PaymentChannel.CREDIT_ACCOUNT,
                    creditOutflow.channel
                )

                val paidValueOutflow =
                    outflowsBefore.single {
                        it.category ==
                            destination.category &&
                        it.channel ==
                            destination.channel
                    }

                assertEquals(
                    -5_000L,
                    paidValueOutflow.amountSignedMinor
                )

                assertEquals(
                    -10_000L,
                    outflowsBefore.sumOf {
                        it.amountSignedMinor
                    }
                )

                assertEquals(
                    10_000L,
                    database.transactionDao()
                        .getRefundEvents(
                            scenario.transaction.id
                        )
                        .single()
                        .refundAmountMinor
                )

                val walletBeforeReplay =
                    database.ledgerDao()
                        .getCouponWallet(
                            scenario.customer.id,
                            "OMR"
                        )

                if (
                    destination.category ==
                        PaymentCategory.COUPON
                ) {
                    val wallet =
                        checkNotNull(
                            walletBeforeReplay
                        )

                    /*
                     * Only the refundable paid value may be credited.
                     * Historical Coupon repayment must NOT be restored again.
                     */
                    assertEquals(
                        5_000L,
                        database.ledgerDao()
                            .getCouponBalance(
                                wallet.id
                            )
                    )

                    val events =
                        database.ledgerDao()
                            .getCouponEventsForOperation(
                                command.operationId.value
                            )

                    assertEquals(
                        1,
                        events.size
                    )

                    assertEquals(
                        5_000L,
                        events.single()
                            .amountSignedMinor
                    )
                } else {
                    /*
                     * Cash/Online was selected. Historical Coupon repayment
                     * must not automatically create Coupon compensation.
                     */
                    assertNull(
                        walletBeforeReplay
                    )

                    assertTrue(
                        database.ledgerDao()
                            .getCouponEventsForOperation(
                                command.operationId.value
                            )
                            .isEmpty()
                    )
                }

                val refundCountBeforeReplay =
                    database.transactionDao()
                        .getRefundEvents(
                            scenario.transaction.id
                        )
                        .size

                val couponEventsBeforeReplay =
                    database.ledgerDao()
                        .getCouponEventsForOperation(
                            command.operationId.value
                        )

                val second =
                    repository.executeRefund(command)

                assertTrue(
                    "Expected successful replay for ${destination.category}, got: $second",
                    second is CommandResult.Success
                )

                assertTrue(
                    (second as CommandResult.Success)
                        .isIdempotentReplay
                )

                assertEquals(
                    first.data,
                    second.data
                )

                assertEquals(
                    refundCountBeforeReplay,
                    database.transactionDao()
                        .getRefundEvents(
                            scenario.transaction.id
                        )
                        .size
                )

                assertEquals(
                    outflowsBefore,
                    refundOutflows(
                        scenario.transaction.id
                    )
                )

                assertEquals(
                    couponEventsBeforeReplay,
                    database.ledgerDao()
                        .getCouponEventsForOperation(
                            command.operationId.value
                        )
                )

                assertEquals(
                    0L,
                    database.ledgerDao()
                        .getCreditBalance(
                            scenario.customer.id,
                            "OMR"
                        )
                )

                if (
                    destination.category ==
                        PaymentCategory.COUPON
                ) {
                    val wallet =
                        checkNotNull(
                            database.ledgerDao()
                                .getCouponWallet(
                                    scenario.customer.id,
                                    "OMR"
                                )
                        )

                    assertEquals(
                        5_000L,
                        database.ledgerDao()
                            .getCouponBalance(
                                wallet.id
                            )
                    )
                } else {
                    assertNull(
                        database.ledgerDao()
                            .getCouponWallet(
                                scenario.customer.id,
                                "OMR"
                            )
                    )
                }
            }
        }

    @Test
    fun productionRefund_b2_selectedDestinationWriteFailure_rollsBackEntireEconomicReversal() =
        runBlocking {

            val scenario =
                seedB2CreditScenario(
                    suffix = "selected-destination-rollback",
                    cashRepaymentMinor = 5_000L
                )

            val command =
                newRefundCommand(
                    transactionId =
                        scenario.transaction.id,
                    amountMinor =
                        10_000L,
                    category =
                        PaymentCategory.COUPON,
                    channel =
                        PaymentChannel.COUPON_WALLET,
                    occurredAt =
                        scenario.returnAt,
                    reason =
                        "B2 forced atomic rollback"
                )

            val creditBefore =
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )

            val refundsBefore =
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )

            val outflowsBefore =
                refundOutflows(
                    scenario.transaction.id
                )

            val creditLedgerBefore =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        scenario.customer.id
                    )

            assertEquals(
                5_000L,
                creditBefore
            )

            assertNull(
                database.ledgerDao()
                    .getCouponWallet(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            database.openHelper
                .writableDatabase
                .execSQL(
                    """
                    CREATE TRIGGER test_abort_b2_selected_destination
                    BEFORE INSERT ON settlement_entries
                    WHEN NEW.entry_purpose = 'REFUND_OUTFLOW'
                    BEGIN
                        SELECT RAISE(
                            ABORT,
                            'forced B2 selected destination failure'
                        );
                    END
                    """.trimIndent()
                )

            val result =
                try {
                    repository.executeRefund(
                        command
                    )
                } finally {
                    database.openHelper
                        .writableDatabase
                        .execSQL(
                            "DROP TRIGGER IF EXISTS test_abort_b2_selected_destination"
                        )
                }

            assertTrue(
                result is CommandResult.Error
            )

            /*
             * No partial financial mutation may survive.
             */
            assertEquals(
                refundsBefore,
                database.transactionDao()
                    .getRefundEvents(
                        scenario.transaction.id
                    )
            )

            assertEquals(
                outflowsBefore,
                refundOutflows(
                    scenario.transaction.id
                )
            )

            assertEquals(
                creditBefore,
                database.ledgerDao()
                    .getCreditBalance(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            assertEquals(
                creditLedgerBefore,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        scenario.customer.id
                    )
            )

            assertNull(
                database.ledgerDao()
                    .getCouponWallet(
                        scenario.customer.id,
                        "OMR"
                    )
            )

            assertTrue(
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )
        }
    private data class B2CreditScenario(
        val customer: CustomerEntity,
        val transaction: TransactionEntity,
        val returnAt: Long
    )

    private suspend fun seedB2CreditScenario(
        suffix: String,
        cashRepaymentMinor: Long = 0L,
        onlineRepaymentMinor: Long = 0L,
        couponRepaymentMinor: Long = 0L
    ): B2CreditScenario {

        val now =
            System.currentTimeMillis() -
                40_000L

        val customer =
            CustomerEntity(
                id =
                    "b2b-customer-$suffix",
                businessId =
                    businessId,
                canonicalPhone =
                    "+96893" +
                        suffix.hashCode()
                            .toUInt()
                            .toString()
                            .takeLast(6)
                            .padStart(6, '0'),
                name =
                    "B2B Customer $suffix",
                createdAt =
                    now,
                updatedAt =
                    now
            )

        database.customerDao()
            .insertCustomer(customer)

        val tx =
            seedSale(
                transactionId =
                    "b2b-sale-$suffix",
                amountMinor =
                    10_000L,
                category =
                    PaymentCategory.CREDIT,
                channel =
                    PaymentChannel.CREDIT_ACCOUNT,
                occurredAt =
                    now,
                customerId =
                    customer.id
            )

        database.ledgerDao()
            .insertCreditEvent(
                CreditLedgerEventEntity(
                    id =
                        "b2b-obligation-$suffix",
                    operationId =
                        "sale-operation-${tx.id}",
                    customerId =
                        customer.id,
                    type =
                        CreditLedgerType.OBLIGATION,
                    amountSignedMinor =
                        10_000L,
                    currencyCode =
                        "OMR",
                    transactionId =
                        tx.id,
                    repaymentId =
                        null,
                    occurredAt =
                        now
                )
            )

        var repaymentIndex = 0

        suspend fun seedRepayment(
            amountMinor: Long,
            category: PaymentCategory,
            channel: PaymentChannel
        ) {

            if (amountMinor <= 0L) {
                return
            }

            repaymentIndex += 1

            val repaymentId =
                "b2b-repayment-$suffix-$repaymentIndex"

            val operationId =
                "b2b-repayment-operation-$suffix-$repaymentIndex"

            val occurredAt =
                now +
                    (repaymentIndex * 1_000L)

            insertOperation(
                id =
                    operationId,
                type =
                    OperationType.RECORD_CREDIT_REPAYMENT,
                transactionId =
                    tx.id,
                occurredAt =
                    occurredAt
            )

            database.ledgerDao()
                .insertCreditRepayment(
                    com.dailymoneyregister.core.database.entity
                        .CreditRepaymentEntity(
                            id =
                                repaymentId,
                            operationId =
                                operationId,
                            customerId =
                                customer.id,
                            amountMinor =
                                amountMinor,
                            currencyCode =
                                "OMR",
                            paymentCategory =
                                category,
                            occurredAt =
                                occurredAt,
                            actorUserId =
                                actorId
                        )
                )

            database.transactionDao()
                .insertSettlement(
                    SettlementEntryEntity(
                        id =
                            "$operationId:collection",
                        operationId =
                            operationId,
                        subjectType =
                            SubjectType.REPAYMENT,
                        subjectId =
                            repaymentId,
                        transactionId =
                            null,
                        entryPurpose =
                            EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
                        category =
                            category,
                        channel =
                            channel,
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

            database.ledgerDao()
                .insertCreditEvent(
                    CreditLedgerEventEntity(
                        id =
                            "$operationId:credit-repayment",
                        operationId =
                            operationId,
                        customerId =
                            customer.id,
                        type =
                            CreditLedgerType.REPAYMENT,
                        amountSignedMinor =
                            Math.negateExact(
                                amountMinor
                            ),
                        currencyCode =
                            "OMR",
                        transactionId =
                            null,
                        repaymentId =
                            repaymentId,
                        occurredAt =
                            occurredAt
                    )
                )

            database.ledgerDao()
                .insertCreditRepaymentAllocations(
                    listOf(
                        com.dailymoneyregister.core.database.entity
                            .CreditRepaymentAllocationEntity(
                                id =
                                    "b2b-allocation-$suffix-$repaymentIndex",
                                repaymentId =
                                    repaymentId,
                                originalTransactionId =
                                    tx.id,
                                allocatedAmountMinor =
                                    amountMinor,
                                occurredAt =
                                    occurredAt
                            )
                    )
                )
        }

        seedRepayment(
            amountMinor =
                cashRepaymentMinor,
            category =
                PaymentCategory.CASH,
            channel =
                PaymentChannel.CASH_TENDER
        )

        seedRepayment(
            amountMinor =
                onlineRepaymentMinor,
            category =
                PaymentCategory.ONLINE,
            channel =
                PaymentChannel.MANUAL_QR
        )

        seedRepayment(
            amountMinor =
                couponRepaymentMinor,
            category =
                PaymentCategory.COUPON,
            channel =
                PaymentChannel.COUPON_WALLET
        )

        val totalRepaid =
            Math.addExact(
                Math.addExact(
                    cashRepaymentMinor,
                    onlineRepaymentMinor
                ),
                couponRepaymentMinor
            )

        assertEquals(
            Math.subtractExact(
                10_000L,
                totalRepaid
            ),
            database.ledgerDao()
                .getCreditBalance(
                    customer.id,
                    "OMR"
                )
        )

        assertEquals(
            totalRepaid,
            database.ledgerDao()
                .getCreditRepaymentAllocationsForTransaction(
                    tx.id
                )
                .sumOf {
                    it.allocatedAmountMinor
                }
        )

        return B2CreditScenario(
            customer =
                customer,
            transaction =
                tx,
            returnAt =
                now + 10_000L
        )
    }

    private suspend fun seedSale(
        transactionId: String,
        amountMinor: Long,
        fundingAmountMinor: Long = amountMinor,
        category: PaymentCategory =
            PaymentCategory.CASH,
        channel: PaymentChannel =
            PaymentChannel.CASH_TENDER,
        includeFundingSettlement: Boolean = true,
        occurredAt: Long =
            System.currentTimeMillis() - 10_000L,
        customerId: String? = null
    ): TransactionEntity {

        val operationId =
            "sale-operation-$transactionId"

        insertOperation(
            id = operationId,
            type = OperationType.RECORD_SALE,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        val tx =
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
                displayRef = "REFUND-$transactionId",
                customerId = customerId
            )

        database.transactionDao()
            .insertTransaction(tx)

        if (includeFundingSettlement) {
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
                            category,
                        channel =
                            channel,
                        amountSignedMinor =
                            fundingAmountMinor,
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

        return tx
    }

    private suspend fun seedAuthoritativeVoid(
        transactionId: String,
        amountMinor: Long,
        occurredAt: Long
    ) {
        val operationId =
            "void-operation-$transactionId"

        insertOperation(
            id = operationId,
            type = OperationType.RECORD_VOID,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        database.transactionDao()
            .insertVoid(
                VoidEventEntity(
                    id = "void-event-$transactionId",
                    transactionId = transactionId,
                    operationId = operationId,
                    actorUserId = actorId,
                    occurredAt = occurredAt
                )
            )

        database.transactionDao()
            .insertSettlement(
                SettlementEntryEntity(
                    id =
                        "void-settlement-$transactionId",
                    operationId =
                        operationId,
                    subjectType =
                        SubjectType.TRANSACTION,
                    subjectId =
                        transactionId,
                    transactionId =
                        transactionId,
                    entryPurpose =
                        EntryPurpose.VOID_REVERSAL,
                    category =
                        PaymentCategory.CASH,
                    channel =
                        PaymentChannel.CASH_TENDER,
                    amountSignedMinor =
                        Math.negateExact(amountMinor),
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

    private suspend fun insertOperation(
        id: String,
        type: OperationType,
        transactionId: String,
        occurredAt: Long
    ) {
        database.operationDao()
            .insertOperation(
                OperationEntity(
                    id = id,
                    businessId = businessId,
                    branchId = branchId,
                    operationType = type,
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

    private suspend fun insertAdmittedPendingRefund(
        command: RefundCommand
    ): RefundCommand {

        val record =
            checkNotNull(
                database.transactionDao()
                    .getFinancialRecordById(
                        command.subjectId
                    )
            )

        val allocations =
            database.ledgerDao()
                .getCreditRepaymentAllocationsForTransaction(
                    command.subjectId
                )

        val validated =
            RefundFinancialIntegrity.validate(
                record = record,
                creditAllocations = allocations
            )

        val admitted =
            command.copy(
                financialPrecondition =
                    RefundFinancialPrecondition(
                        stateFingerprint =
                            RefundFinancialIntegrity
                                .fingerprint(
                                    transactionId =
                                        command.subjectId,
                                    record =
                                        record,
                                    creditAllocations =
                                        allocations
                                ),
                        expectedRemainingRefundableMinor =
                            validated
                                .remainingRefundableMinor,
                        fundingCategory =
                            validated
                                .fundingCategory,
                        fundingChannel =
                            validated
                                .fundingChannel
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

    private suspend fun seedHistoricalRefundEvidence(
        transactionId: String,
        refundAmountMinor: Long,
        occurredAt: Long,
        settlementParts: List<Long> =
            listOf(Math.negateExact(refundAmountMinor)),
        settlementCategory: PaymentCategory =
            PaymentCategory.CASH,
        settlementChannel: PaymentChannel =
            PaymentChannel.CASH_TENDER,
        settlementActorUserId: String = actorId,
        settlementOccurredAt: Long = occurredAt
    ) {
        val operationId =
            "historical-refund-operation-$transactionId"

        insertOperation(
            id = operationId,
            type = OperationType.RECORD_REFUND,
            transactionId = transactionId,
            occurredAt = occurredAt
        )

        database.transactionDao()
            .insertRefund(
                RefundEventEntity(
                    id =
                        "historical-refund-event-$transactionId",
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

        settlementParts.forEachIndexed { index, amount ->
            database.transactionDao()
                .insertSettlement(
                    SettlementEntryEntity(
                        id =
                            "historical-refund-settlement-$transactionId-$index",
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
                            settlementCategory,
                        channel =
                            settlementChannel,
                        amountSignedMinor =
                            amount,
                        currencyCode =
                            "OMR",
                        confirmationType =
                            ConfirmationType.CASHIER_CONFIRMED,
                        occurredAt =
                            settlementOccurredAt,
                        actorUserId =
                            settlementActorUserId
                    )
                )
        }
    }

    private suspend fun seedAdversarialCreditRepaymentAllocation(
        transactionId: String,
        occurredAt: Long
    ) {
        val writable =
            database.openHelper.writableDatabase

        writable.execSQL(
            "PRAGMA foreign_keys = OFF"
        )

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
                    "refund-credit-allocation-$transactionId",
                    "refund-missing-repayment-$transactionId",
                    transactionId,
                    1L,
                    occurredAt
                )
            )
        } finally {
            writable.execSQL(
                "PRAGMA foreign_keys = ON"
            )
        }

        assertEquals(
            1,
            database.ledgerDao()
                .getCreditRepaymentAllocationsForTransaction(
                    transactionId
                )
                .size
        )
    }

    private suspend fun assertRefundRejectedPreservingOriginal(
        original: TransactionEntity,
        settlementsBefore: List<SettlementEntryEntity>,
        result: CommandResult<*>,
        expectedException: Class<out Throwable>
    ) {
        assertTrue(
            "Expected Refund failure, got: $result",
            result is CommandResult.Error
        )

        val error =
            (result as CommandResult.Error).exception

        assertTrue(
            "Expected ${expectedException.simpleName}, got " +
                error::class.java.simpleName,
            expectedException.isInstance(error)
        )

        assertTrue(
            database.transactionDao()
                .getRefundEvents(original.id)
                .isEmpty()
        )

        assertTrue(
            refundOutflows(original.id)
                .isEmpty()
        )

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
    private fun newRefundCommand(
        transactionId: String,
        amountMinor: Long,
        operationId: OperationId =
            OperationId.generate(),
        actor: String = actorId,
        category: PaymentCategory =
            PaymentCategory.CASH,
        channel: PaymentChannel =
            PaymentChannel.CASH_TENDER,
        couponDestination: RefundCouponDestination? = null,
        occurredAt: Long =
            System.currentTimeMillis(),
        reason: String? = null
    ): RefundCommand =
        RefundCommand(
            operationId =
                operationId,
            businessId =
                businessId,
            branchId =
                branchId,
            subjectId =
                transactionId,
            actorUserId =
                actor,
            deviceId =
                deviceId,
            occurredAt =
                occurredAt,
            refundAmountMinor =
                amountMinor,
            destinationCategory =
                category,
            destinationChannel =
                channel,
            couponDestination =
                couponDestination,
            reason =
                reason
        )

    private suspend fun refundOutflows(
        transactionId: String
    ): List<SettlementEntryEntity> =
        database.transactionDao()
            .getSettlementsForTransaction(
                transactionId
            )
            .filter {
                it.entryPurpose ==
                    EntryPurpose.REFUND_OUTFLOW
            }
}




