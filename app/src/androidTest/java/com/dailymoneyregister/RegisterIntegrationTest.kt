package com.dailymoneyregister

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegisterIntegrationTest {

    private lateinit var database: DmrDatabase
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var repository: RegisterRepository
    private lateinit var context: Context

    private val testUser = UserAccountEntity(
        id = "cashier-test-1",
        businessId = "biz-1",
        displayName = "Cashier Test",
        username = "cashier_test",
        role = UserRole.CASHIER,
        authType = AuthType.PIN,
        credentialHash = "hash",
        credentialSalt = "salt",
        createdAt = 0L,
        updatedAt = 0L
    )

    private val testMembershipAuthorized = UserBranchMembershipEntity(
        id = "mem-1",
        userId = "cashier-test-1",
        branchId = "branch-1",
        role = UserRole.CASHIER,
        canCreateSale = true,
        canAcceptCash = true,
        canAcceptOnline = true,
        createdAt = 0L
    )

    private val testMembershipUnauthorized = UserBranchMembershipEntity(
        id = "mem-1",
        userId = "cashier-test-1",
        branchId = "branch-1",
        role = UserRole.CASHIER,
        canCreateSale = false, // DENIED!
        canAcceptCash = false, // DENIED!
        createdAt = 0L
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DmrDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        commandExecutor = CommandExecutor(database)
        repository = RegisterRepository(database, commandExecutor)

        // Seed Business and Branch so Foreign Keys pass
        val now = System.currentTimeMillis()
        database.businessBranchDao().insertBusiness(
            BusinessEntity(
                id = "biz-1",
                name = "Test Business",
                createdAt = now,
                updatedAt = now
            )
        )
        database.businessBranchDao().insertBranch(
            BranchEntity(
                id = "branch-1",
                businessId = "biz-1",
                name = "Test Branch",
                code = "TST",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                createdAt = now,
                updatedAt = now
            )
        )

        // Seed authoritative cashier identity and branch permissions.
        // Cash-sale tests must not rely only on caller-supplied objects.
        database.userDao().insertUser(testUser)
        database.userDao().insertMembership(testMembershipAuthorized)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testQuickCashSaleExecution() = runBlocking {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()

        val draft = DraftState(
            manualAmount = MoneyMinorUnits(1000L), // 1.000 OMR
            taxRateBps = 0,
            isTaxEnabled = false
        )

        val command = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        val result = repository.executeCashSale(command, testUser, testMembershipAuthorized)
        assertTrue("Quick Cash sale should succeed, got: $result", result is CommandResult.Success)

        val transaction = (result as CommandResult.Success).data
        assertEquals(txId.value, transaction.id)
        assertEquals(1000L, transaction.grandTotalMinor)
        assertEquals(TransactionType.SALE, transaction.type)

        // Verify Settlement Entry created
        val settlements = database.transactionDao().getSettlementsForTransaction(txId.value)
        assertEquals(1, settlements.size)
        val settlement = settlements.first()
        assertEquals(opId.value, settlement.operationId)
        assertEquals(EntryPurpose.SALE_FUNDING, settlement.entryPurpose)
        assertEquals(PaymentCategory.CASH, settlement.category)
        assertEquals(1000L, settlement.amountSignedMinor)

        // Verify Operation Entity created
        val op = database.operationDao().getOperationById(opId.value)
        assertNotNull(op)
        assertEquals(OperationStatus.SUCCEEDED, op?.status)
    }

    @Test
    fun testItemizedBillCashSaleWithTaxShippingPacking() = runBlocking {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()

        val items = listOf(
            DraftItem("item-1", "Milk", unitPrice = MoneyMinorUnits(1000L), quantity = 1),
            DraftItem("item-2", "Biscuits", unitPrice = MoneyMinorUnits(2000L), quantity = 1),
            DraftItem("item-3", "Tea", unitPrice = MoneyMinorUnits(3000L), quantity = 1)
        )

        val draft = DraftState(
            items = items,
            taxRateBps = 500, // 5.00%
            isTaxEnabled = true,
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            txPacking = MoneyMinorUnits(2000L),
            txShipping = MoneyMinorUnits(1000L)
        )

        val command = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        val result = repository.executeCashSale(command, testUser, testMembershipAuthorized)
        assertTrue("Itemized sale should succeed, got: $result", result is CommandResult.Success)

        val transaction = (result as CommandResult.Success).data

        // Grand Total = 6.000 + 0.300 + 2.000 + 1.000 = 9.300 OMR (9300 baisa)
        assertEquals(9300L, transaction.grandTotalMinor)
        assertEquals(6000L, transaction.subtotalMinor)
        assertEquals(300L, transaction.taxAmountMinor)
        assertEquals(1000L, transaction.txShippingMinor)
        assertEquals(2000L, transaction.txPackingMinor)

        // Verify Transaction Items inserted
        val storedItems = database.transactionDao().getItemsForTransaction(txId.value)
        assertEquals(3, storedItems.size)
        val milk = storedItems.find { it.productNameSnapshot == "Milk" }
        assertNotNull(milk)
        assertEquals(1000L, milk?.unitPriceMinor)

        // Verify Settlement Entry
        val settlements = database.transactionDao().getSettlementsForTransaction(txId.value)
        assertEquals(1, settlements.size)
        assertEquals(9300L, settlements.first().amountSignedMinor)
    }

    @Test
    fun testRapidDoubleTapIdempotencyProtection() = runBlocking {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()
        val draft = DraftState(manualAmount = MoneyMinorUnits(2500L))

        val command = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        // 1st click
        val res1 = repository.executeCashSale(command, testUser, testMembershipAuthorized)
        assertTrue("1st execution should succeed, got: $res1", res1 is CommandResult.Success)
        assertFalse((res1 as CommandResult.Success).isIdempotentReplay)

        // 2nd rapid click with SAME Operation ID
        val res2 = repository.executeCashSale(command, testUser, testMembershipAuthorized)
        assertTrue("2nd execution should succeed as idempotent replay, got: $res2", res2 is CommandResult.Success)
        assertTrue((res2 as CommandResult.Success).isIdempotentReplay)

        // Verify database contains EXACTLY ONE transaction and ONE settlement
        val settlements = database.transactionDao().getSettlementsForTransaction(txId.value)
        assertEquals(1, settlements.size)
    }


    @Test
    fun testTrueConcurrentSameOperationExecutesExactlyOnce() = runBlocking {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()

        val draft = DraftState(
            manualAmount = MoneyMinorUnits(3750L)
        )

        val command = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "concurrent-test-device",
        )

        /*
         * Both coroutines wait on the same gate so they attempt
         * the same financial command at effectively the same time.
         */
        val startGate = CompletableDeferred<Unit>()

        val firstExecution = async(Dispatchers.IO) {
            startGate.await()

            repository.executeCashSale(
                command,
                testUser,
                testMembershipAuthorized
            )
        }

        val secondExecution = async(Dispatchers.IO) {
            startGate.await()

            repository.executeCashSale(
                command,
                testUser,
                testMembershipAuthorized
            )
        }

        /*
         * Release both executions together.
         */
        startGate.complete(Unit)

        val results = awaitAll(
            firstExecution,
            secondExecution
        )

        assertEquals(2, results.size)

        assertTrue(
            "Both callers must receive successful results. Results: $results",
            results.all { it is CommandResult.Success }
        )

        val successResults =
            results.map { it as CommandResult.Success }

        /*
         * Exactly ONE caller performs the real financial execution.
         */
        assertEquals(
            "Exactly one execution must be the real execution.",
            1,
            successResults.count {
                !it.isIdempotentReplay
            }
        )

        /*
         * The competing caller must receive the already-committed
         * result as an idempotent replay.
         */
        assertEquals(
            "Exactly one execution must be an idempotent replay.",
            1,
            successResults.count {
                it.isIdempotentReplay
            }
        )

        /*
         * There must be exactly one authoritative transaction.
         */
        val transaction =
            database.transactionDao()
                .getTransactionById(txId.value)

        assertNotNull(transaction)
        assertEquals(
            3750L,
            transaction?.grandTotalMinor
        )

        /*
         * Most important financial duplicate check:
         * only ONE settlement entry may exist.
         */
        val settlements =
            database.transactionDao()
                .getSettlementsForTransaction(txId.value)

        assertEquals(
            "Concurrent execution must create exactly one settlement.",
            1,
            settlements.size
        )

        assertEquals(
            3750L,
            settlements.first().amountSignedMinor
        )

        /*
         * Durable operation must remain terminally SUCCEEDED.
         */
        val storedOperation =
            database.operationDao()
                .getOperationById(opId.value)

        assertNotNull(storedOperation)
        assertEquals(
            OperationStatus.SUCCEEDED,
            storedOperation?.status
        )

        assertEquals(
            txId.value,
            storedOperation?.resultReferenceId
        )
    }
    @Test
    fun testUnauthorizedCashierSaleRejected() = runBlocking {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()
        val draft = DraftState(manualAmount = MoneyMinorUnits(1000L))

        val command = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        // Permission checks use the authoritative membership stored in Room.
        database.userDao().updateMembership(testMembershipUnauthorized)

        val result = repository.executeCashSale(
            command,
            testUser,
            testMembershipUnauthorized
        )

        assertTrue(
            "Unauthorized cashier sale should be rejected",
            result is CommandResult.PermissionDenied
        )
    }

    @Test
    fun testNoTaxByDefaultExactMinorUnits() = runBlocking {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()

        // Explicitly test entering 1.000 with tax disabled (default)
        val draft = DraftState(
            manualAmount = MoneyMinorUnits(1000L),
            taxRateBps = 500, // Even if tax rate is set to 5%
            isTaxEnabled = false // Tax is DISABLED by default
        )

        val command = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        val result = repository.executeCashSale(command, testUser, testMembershipAuthorized)
        assertTrue("Cash sale must succeed", result is CommandResult.Success)

        val tx = (result as CommandResult.Success).data
        assertEquals("Subtotal must be exactly 1000 minor units", 1000L, tx.subtotalMinor)
        assertEquals("Tax amount must be exactly 0 minor units", 0L, tx.taxAmountMinor)
        assertEquals("Grand total must be exactly 1000 minor units (1.000)", 1000L, tx.grandTotalMinor)
    }

    @Test
    fun testPendingCrashRecoveryExecution() = runBlocking {
        val opId = OperationId.generate()
        val txId = TransactionId.generate()

        val draft = DraftState(
            items = listOf(
                DraftItem("item-rec-1", "Coffee", unitPrice = MoneyMinorUnits(1200L), quantity = 2)
            ),
            taxRateBps = 0,
            isTaxEnabled = false
        )

        val originalCmd = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId,
            draftState = draft,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
            occurredAt = System.currentTimeMillis()
        )

        // Simulate crash right after PENDING operation was persisted, BEFORE financial rows were written
        val pendingOp = com.dailymoneyregister.core.database.entity.OperationEntity(
            id = opId.value,
            operationType = com.dailymoneyregister.core.model.enums.OperationType.RECORD_SALE,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            subjectType = com.dailymoneyregister.core.model.enums.SubjectType.TRANSACTION,
            subjectId = txId.value,
            transactionId = txId.value,
            deviceId = originalCmd.deviceId,
            occurredAt = originalCmd.occurredAt,
            requestFingerprint = originalCmd.requestFingerprint,
            commandSchemaVersion = originalCmd.commandSchemaVersion,
            intentPayload = originalCmd.intentPayload,
            status = OperationStatus.PENDING
        )
        database.operationDao().insertOperation(pendingOp)

        // Verify transaction does NOT exist yet
        assertNull(database.transactionDao().getTransactionById(txId.value))

        // Recreate repository (simulating new process/app launch)
        val newCommandExecutor = CommandExecutor(database)
        val newRepository = RegisterRepository(database, newCommandExecutor)

        // Run recovery
        val recovered = newRepository.recoverPendingCashSales()
        assertEquals(1, recovered.size)

        val recoveredTx = recovered.first()
        assertEquals(txId.value, recoveredTx.id)
        assertEquals(2400L, recoveredTx.grandTotalMinor)

        // Verify transaction, items, and settlement exist in DB
        val dbTx = database.transactionDao().getTransactionById(txId.value)
        assertNotNull(dbTx)
        val dbItems = database.transactionDao().getItemsForTransaction(txId.value)
        assertEquals(1, dbItems.size)
        assertEquals("Coffee", dbItems.first().productNameSnapshot)

        val settlements = database.transactionDao().getSettlementsForTransaction(txId.value)
        assertEquals(1, settlements.size)
        assertEquals(2400L, settlements.first().amountSignedMinor)

        // Verify Operation entity is now SUCCEEDED
        val updatedOp = database.operationDao().getOperationById(opId.value)
        assertEquals(OperationStatus.SUCCEEDED, updatedOp?.status)
        assertEquals(txId.value, updatedOp?.resultReferenceId)

        // Second recovery call should be idempotent and not duplicate rows
        val secondRecovery = newRepository.recoverPendingCashSales()
        assertEquals(0, secondRecovery.size)
    }

    @Test
    fun testSameOperationIdAlteredPayloadConflict() = runBlocking {
        val opId = OperationId.generate()
        val txId1 = TransactionId.generate()
        val txId2 = TransactionId.generate()

        val draft1 = DraftState(manualAmount = MoneyMinorUnits(1000L))
        val draft2 = DraftState(manualAmount = MoneyMinorUnits(2000L)) // Altered amount

        val cmd1 = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId1,
            draftState = draft1,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        val cmd2Altered = CashSaleCommand(
            operationId = opId,
            businessId = "biz-1",
            branchId = "branch-1",
            actorUserId = testUser.id,
            transactionIdVal = txId2,
            draftState = draft2,
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "test-device",
        )

        // 1st execution succeeds
        val res1 = repository.executeCashSale(cmd1, testUser, testMembershipAuthorized)
        assertTrue(res1 is CommandResult.Success)

        // 2nd execution with same opId but altered inputs must be rejected with Conflict
        val res2 = repository.executeCashSale(cmd2Altered, testUser, testMembershipAuthorized)
        assertTrue("Altered payload must produce Conflict, got: $res2", res2 is CommandResult.Conflict)
    }

    @Test
    fun testInactiveUserAccountRejected() = runBlocking {
        val inactiveUser = testUser.copy(
            id = "inactive-cashier",
            username = "inactive_cashier",
            isActive = false
        )
        database.userDao().insertUser(inactiveUser)

        val throttler = com.dailymoneyregister.core.security.PinThrottler(context = context)
        val langManager = com.dailymoneyregister.core.i18n.LanguageManager(context)
        val authRepo = com.dailymoneyregister.feature.auth.data.AuthRepository(
            database = database,
            pinThrottler = throttler,
            languageManager = langManager,
            trustedSessionStore = TrustedSessionStore(context)
        )

        val authResult = authRepo.loginCashier("inactive_cashier", "123456")
        assertTrue("Inactive account must be rejected", authResult is com.dailymoneyregister.feature.auth.data.AuthResult.InvalidCredentials)
    }

    @Test
    fun testNonAdminCashierCreationRejectedAtDomainBoundary() = runBlocking {
        // testUser is role = UserRole.CASHIER (non-admin)
        // It is already seeded by setUp().

        val cashierRepo = com.dailymoneyregister.feature.admin.data.CashierManagementRepository(database, commandExecutor, com.dailymoneyregister.core.device.DeviceIdentityProvider(context))
        val params = com.dailymoneyregister.feature.admin.data.CreateCashierParams(
            businessId = "biz-1",
            branchId = "branch-1",
            adminUserId = testUser.id, // Cashier attempting to create another cashier
            displayName = "Sub Cashier",
            username = "sub_cashier",
            pin = "654321",
            preferredLanguage = "ar"
        )

        val result = cashierRepo.createCashier(params)
        assertTrue("Non-admin cashier creation must be rejected at domain boundary", result is CommandResult.PermissionDenied)
    }
}



