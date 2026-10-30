package com.dailymoneyregister

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CouponLedgerEventEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.CouponLedgerType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.CouponSaleCommand
import com.dailymoneyregister.feature.register.data.CouponTopUpCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CouponTopUpCharacterizationTest {
    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository

    private val business = BusinessEntity("topup-biz", "Top Up Test", 0, 0)
    private val branch = BranchEntity(
        "topup-branch", business.id, "Main", "MAIN", "OM", "OMR", 3,
        "OMR", "Asia/Muscat", createdAt = 0, updatedAt = 0
    )
    private val admin = UserAccountEntity(
        id = "topup-admin",
        businessId = business.id,
        displayName = "Owner",
        username = "topup-owner",
        role = UserRole.ADMIN,
        authType = AuthType.PIN,
        credentialHash = "test-hash",
        credentialSalt = "test-salt",
        createdAt = 0,
        updatedAt = 0
    )
    private val membership = UserBranchMembershipEntity(
        "topup-membership", admin.id, branch.id, UserRole.ADMIN,
        canCreateSale = true,
        canAcceptCash = true,
        canAcceptOnline = true,
        canCreateCreditSale = true,
        canUseCoupon = true,
        canRefund = true,
        createdAt = 0
    )

    @Before
    fun setUp() = runBlocking {
        database = DmrDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        repository = RegisterRepository(database, CommandExecutor(database))
        database.businessBranchDao().insertBusiness(business)
        database.businessBranchDao().insertBranch(branch)
        database.userDao().insertUser(admin)
        database.userDao().insertMembership(membership)
        SessionManager.setSession(ActiveSession(business, branch, admin, membership))
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun initialFundingAndTopUpAppendExpectedBalanceAndLedgerIdentity() = runBlocking {
        val walletId = createInitiallyFundedWallet()
        assertEquals(5_000L, database.ledgerDao().getCouponBalance(walletId))
        val command = topUpCommand(walletId)

        val result = repository.executeCouponTopUp(command)
        assertTrue(result.toString(), result is CommandResult.Success)

        val success = result as CommandResult.Success
        assertEquals(TransactionType.COUPON_PURCHASE, success.data.transaction.type)
        assertEquals(walletId, success.data.walletId)
        assertEquals(2_500L, success.data.amountMinor)
        assertEquals(7_500L, database.ledgerDao().getCouponBalance(walletId))

        val event = database.ledgerDao().getCouponLedgerForWallet(walletId)
            .single { it.operationId == "topup-op" }
        // Frozen UUID.nameUUIDFromBytes output for "coupon-topup:topup-op:ledger".
        assertEquals("812396ac-d26d-3298-88e0-3497ead86d59", event.id)
        assertEquals(CouponLedgerType.PURCHASE, event.type)
        assertEquals(2_500L, event.amountSignedMinor)
        assertEquals(command.subjectId, event.transactionId)
        assertEquals(command.operationId.value, event.operationId)

        val settlement = database.transactionDao()
            .getSettlementsForTransaction(command.subjectId)
            .single()
        assertEquals(EntryPurpose.COUPON_PURCHASE_FUNDING, settlement.entryPurpose)
        assertEquals(command.operationId.value, settlement.operationId)
        assertEquals(PaymentCategory.CASH, settlement.category)
    }

    @Test
    fun sameOperationReplaysOnceAndAlteredPayloadConflicts() = runBlocking {
        val walletId = createInitiallyFundedWallet()
        val command = topUpCommand(walletId)

        val first = repository.executeCouponTopUp(command)
        assertTrue(first.toString(), first is CommandResult.Success)
        val replay = repository.executeCouponTopUp(command)
        assertTrue(replay.toString(), replay is CommandResult.Success)
        assertTrue((replay as CommandResult.Success).isIdempotentReplay)

        val altered = repository.executeCouponTopUp(command.copy(amountMinor = 2_600L))
        assertTrue(altered.toString(), altered is CommandResult.Conflict)

        assertEquals(7_500L, database.ledgerDao().getCouponBalance(walletId))
        assertEquals(
            1,
            database.ledgerDao().getCouponLedgerForWallet(walletId)
                .count { it.operationId == command.operationId.value }
        )
        assertEquals(
            1,
            database.transactionDao()
                .getSettlementsForTransaction(command.subjectId).size
        )
    }

    @Test
    fun duplicateLedgerEvidenceRollsBackTopUpTransactionAndSettlement() = runBlocking {
        val walletId = createInitiallyFundedWallet()
        val command = topUpCommand(walletId)

        // The ledger event's operation_id foreign key requires its parent operation.
        val preexistingCommand = topUpCommand(walletId, operationId = "preexisting-operation")
        database.operationDao().insertOperation(
            OperationEntity(
                id = preexistingCommand.operationId.value,
                businessId = preexistingCommand.businessId,
                branchId = preexistingCommand.branchId,
                operationType = preexistingCommand.operationType,
                subjectType = preexistingCommand.subjectType,
                subjectId = preexistingCommand.subjectId,
                transactionId = preexistingCommand.transactionId,
                actorUserId = preexistingCommand.actorUserId,
                deviceId = preexistingCommand.deviceId,
                occurredAt = preexistingCommand.occurredAt,
                requestFingerprint = preexistingCommand.requestFingerprint,
                commandSchemaVersion = preexistingCommand.commandSchemaVersion,
                intentPayload = preexistingCommand.intentPayload,
                status = OperationStatus.SUCCEEDED
            )
        )

        // Force the ledger insert to fail after the transaction writer has run.
        database.ledgerDao().insertCouponEvent(
            CouponLedgerEventEntity(
                id = "812396ac-d26d-3298-88e0-3497ead86d59",
                operationId = "preexisting-operation",
                walletId = walletId,
                type = CouponLedgerType.PURCHASE,
                amountSignedMinor = 1L,
                currencyCode = "OMR",
                transactionId = "preexisting-transaction",
                occurredAt = 1_700_000_000_500L
            )
        )

        val result = repository.executeCouponTopUp(command)
        assertTrue(result.toString(), result is CommandResult.Error)

        assertNull(database.transactionDao().getTransactionById(command.subjectId))
        assertTrue(database.transactionDao()
            .getSettlementsForOperation(command.operationId.value).isEmpty())
        assertTrue(database.ledgerDao().getCouponEventsForOperation(command.operationId.value).isEmpty())
        assertEquals(5_001L, database.ledgerDao().getCouponBalance(walletId))
        assertEquals(
            1,
            database.ledgerDao().getCouponEventsForOperation("preexisting-operation").size
        )
    }

    @Test
    fun pendingTopUpRecoversFromDurableIntentExactlyOnce() = runBlocking {
        val walletId = createInitiallyFundedWallet()
        val command = topUpCommand(walletId, operationId = "topup-recovery-op")
        val shift = requireNotNull(
            database.cashShiftDao().getLatestShiftForCashier(branch.id, admin.id)
        )

        database.operationDao().insertOperation(
            OperationEntity(
                id = command.operationId.value,
                businessId = command.businessId,
                branchId = command.branchId,
                operationType = command.operationType,
                subjectType = command.subjectType,
                subjectId = command.subjectId,
                transactionId = command.transactionId,
                cashShiftId = shift.id,
                actorUserId = command.actorUserId,
                deviceId = command.deviceId,
                occurredAt = command.occurredAt,
                requestFingerprint = command.requestFingerprint,
                commandSchemaVersion = command.commandSchemaVersion,
                intentPayload = command.intentPayload,
                status = OperationStatus.PENDING
            )
        )

        SessionManager.clearSession()
        repository.recoverPendingCouponSales()
        repository.recoverPendingCouponSales()

        assertEquals(
            OperationStatus.SUCCEEDED,
            database.operationDao().getOperationById(command.operationId.value)?.status
        )
        assertEquals(7_500L, database.ledgerDao().getCouponBalance(walletId))
        val event = database.ledgerDao().getCouponLedgerForWallet(walletId)
            .single { it.operationId == command.operationId.value }
        assertEquals("262455c5-a0bd-30aa-985b-951e3eb37197", event.id)
        assertEquals(2_500L, event.amountSignedMinor)
        assertEquals(
            1,
            database.transactionDao().getSettlementsForTransaction(command.subjectId).size
        )
    }

    private suspend fun createInitiallyFundedWallet(): String {
        val command = CouponSaleCommand(
            operationId = OperationId("topup-initial-sale-op"),
            businessId = business.id,
            branchId = branch.id,
            actorUserId = admin.id,
            transactionIdVal = TransactionId("topup-initial-sale-tx"),
            customerId = "topup-customer",
            customerName = "Coupon Customer",
            canonicalPhone = "+96891234567",
            createCustomer = true,
            amountMinor = 5_000L,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            fundingCategory = PaymentCategory.CASH,
            additionalUserNames = listOf("Family Member"),
            deviceId = "topup-test-device",
            occurredAt = 1_700_000_000_000L
        )
        val result = repository.executeCouponSale(command)
        assertTrue(result.toString(), result is CommandResult.Success)
        return (result as CommandResult.Success).data.walletId
    }

    private fun topUpCommand(
        walletId: String,
        operationId: String = "topup-op"
    ) = CouponTopUpCommand(
        operationId = OperationId(operationId),
        businessId = business.id,
        branchId = branch.id,
        actorUserId = admin.id,
        transactionIdVal = TransactionId("$operationId-tx"),
        walletId = walletId,
        customerId = "topup-customer",
        customerName = "Coupon Customer",
        canonicalPhone = "+96891234567",
        amountMinor = 2_500L,
        currencyCode = "OMR",
        currencyDecimalPlaces = 3,
        fundingCategory = PaymentCategory.CASH,
        deviceId = "topup-test-device",
        occurredAt = 1_700_000_001_000L
    )
}
