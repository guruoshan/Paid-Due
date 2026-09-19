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
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.startup.StartupRecoveryCoordinator
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

    // Independent materialization fixtures: expected rows are not generated by recovery.
    private fun recoveryCommand() = CashSaleCommand(
        operationId = OperationId("recovery-op"), businessId = "biz-1",
        branchId = "branch-1", actorUserId = testUser.id,
        transactionIdVal = TransactionId("recovery-tx"),
        draftState = DraftState(
            items = listOf(DraftItem("recovery-item", "Coffee", "Roasted",
                MoneyMinorUnits(1200), 2, "123", MoneyMinorUnits(10), MoneyMinorUnits(20))),
            manualDescription = "Sale", taxRateBps = 500, isTaxEnabled = true,
            txShipping = MoneyMinorUnits(100), txPacking = MoneyMinorUnits(50)
        ), currencyCode = "OMR", currencyDecimalPlaces = 3,
        deviceId = "recovery-device", occurredAt = 1700000000000L
    )

    private fun recoveryTransaction() = TransactionEntity(
        id = "recovery-tx", branchId = "branch-1", type = TransactionType.SALE,
        customerId = null, subtotalMinor = 2400, taxRateBps = 500,
        taxableBaseMinor = 2400, taxAmountMinor = 120, txShippingMinor = 100,
        txPackingMinor = 50, grandTotalMinor = 2730, currencyCode = "OMR",
        currencyDecimalPlaces = 3, createdByUserId = testUser.id,
        deviceId = "recovery-device", createdAt = 1700000000000L,
        description = "Sale", displayRef = "SAL-0-Y-TX"
    )

    private fun recoveryItem() = TransactionItemEntity(
        id = "recovery-item", transactionId = "recovery-tx", productId = null,
        productNameSnapshot = "Coffee", descriptionSnapshot = "Roasted",
        quantity = 2, unitPriceMinor = 1200, lineTotalMinor = 2400,
        barcodeSnapshot = "123", itemPackingChargeMinor = 10, itemShippingChargeMinor = 20
    )

    private fun recoverySettlement() = SettlementEntryEntity(
        id = "arbitrary-settlement-id", operationId = "recovery-op",
        subjectType = SubjectType.TRANSACTION, subjectId = "recovery-tx",
        transactionId = "recovery-tx", entryPurpose = EntryPurpose.SALE_FUNDING,
        category = PaymentCategory.CASH, channel = PaymentChannel.CASH_TENDER,
        amountSignedMinor = 2730, currencyCode = "OMR",
        confirmationType = ConfirmationType.CASHIER_CONFIRMED,
        occurredAt = 1700000000000L, actorUserId = testUser.id
    )

    private suspend fun seedRecovery(
        transaction: TransactionEntity? = recoveryTransaction(),
        items: List<TransactionItemEntity> = listOf(recoveryItem()),
        settlements: List<SettlementEntryEntity> = listOf(recoverySettlement()),
        version: Int = 3,
        command: CashSaleCommand = recoveryCommand(),
        operationTransform: (OperationEntity) -> OperationEntity = { it }
    ): OperationEntity {
        // Safe ASCII fixture has identical V1/V2/V3 decoding semantics.
        val payload = command.intentPayload.replace("v=3\n", "v=$version\n")
            .let { if (version == 1) it.replace("commandType=CASH_SALE\n", "") else it }
        val operation = operationTransform(OperationEntity(
            id = command.operationId.value, businessId = command.businessId,
            branchId = command.branchId, operationType = OperationType.RECORD_SALE,
            subjectType = SubjectType.TRANSACTION, subjectId = command.transactionIdVal.value,
            transactionId = command.transactionIdVal.value, actorUserId = command.actorUserId,
            deviceId = command.deviceId, occurredAt = command.occurredAt,
            requestFingerprint = command.requestFingerprint, commandSchemaVersion = version,
            intentPayload = payload, status = OperationStatus.PENDING
        ))
        database.operationDao().insertOperation(operation)
        if (transaction != null) database.transactionDao().insertTransaction(transaction)
        database.transactionDao().insertTransactionItems(items)
        database.transactionDao().insertSettlements(settlements)
        return operation
    }

    private fun financialRows(): List<List<String?>> =
        listOf("transactions", "transaction_items", "settlement_entries").flatMap { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(listOf(table) + (0 until cursor.columnCount).map { cursor.getString(it) })
                    }
                }
            }
        }

    private suspend fun assertRecoveryBlocked(original: OperationEntity) {
        val before = financialRows()
        val coordinator = StartupRecoveryCoordinator(repository)
        assertTrue("Incomplete recovery must block startup", coordinator.recoverBeforeFinancialUse().isFailure)
        val stored = database.operationDao().getOperationById(original.id)!!
        assertEquals(OperationStatus.REQUIRES_RECONCILIATION, stored.status)
        assertFalse(stored.reason.isNullOrBlank())
        assertEquals(original, stored.copy(status = original.status, reason = original.reason))
        assertEquals(before, financialRows())
        assertTrue("Retry must stay blocked", coordinator.recoverBeforeFinancialUse().isFailure)
        val restarted = StartupRecoveryCoordinator(RegisterRepository(database, CommandExecutor(database)))
        assertTrue("Fresh coordinator must stay blocked", restarted.recoverBeforeFinancialUse().isFailure)
        assertEquals(stored, database.operationDao().getOperationById(original.id))
        assertEquals(before, financialRows())
    }

    @Test
    fun testCompleteExistingRecoveryPreservesRowsForV1V2V3() = runBlocking {
        for (version in 1..3) {
            database.close()
            setUp()
            seedRecovery(version = version)
            val before = financialRows()
            assertEquals(listOf(recoveryTransaction()), repository.recoverPendingCashSales())
            assertEquals(OperationStatus.SUCCEEDED, database.operationDao().getOperationById("recovery-op")!!.status)
            assertEquals(before, financialRows())
            assertTrue(repository.recoverPendingCashSales().isEmpty())
            assertEquals(before, financialRows())
        }
    }

    @Test
    fun testExistingRecoveryMissingSettlementBlocksPersistently() = runBlocking {
        assertRecoveryBlocked(seedRecovery(settlements = emptyList()))
    }

    @Test
    fun testExistingRecoveryMissingItemsBlocksPersistently() = runBlocking {
        assertRecoveryBlocked(seedRecovery(items = emptyList()))
    }

    @Test
    fun testExistingRecoveryRejectsTransactionMismatches() = runBlocking {
        val expected = recoveryTransaction()
        val variants = listOf(
            expected.copy(type = TransactionType.COUPON_PURCHASE),
            expected.copy(branchId = "other-branch"),
            expected.copy(customerId = "unexpected"), expected.copy(subtotalMinor = 2401),
            expected.copy(taxRateBps = 501), expected.copy(taxableBaseMinor = 2401),
            expected.copy(taxAmountMinor = 121), expected.copy(txShippingMinor = 101),
            expected.copy(txPackingMinor = 51), expected.copy(grandTotalMinor = 2731),
            expected.copy(currencyCode = "USD"), expected.copy(currencyDecimalPlaces = 2),
            expected.copy(createdByUserId = "other"), expected.copy(deviceId = "other"),
            expected.copy(createdAt = 1700000000001L), expected.copy(description = "other"),
            expected.copy(displayRef = "other")
        )
        for (variant in variants) {
            database.close()
            setUp()
            database.businessBranchDao().insertBranch(
                database.businessBranchDao().getBranchById("branch-1")!!.copy(id = "other-branch")
            )
            assertRecoveryBlocked(seedRecovery(transaction = variant))
        }
    }

    @Test
    fun testExistingRecoveryRejectsSettlementMismatches() = runBlocking {
        val expected = recoverySettlement()
        val variants = listOf(
            expected.copy(amountSignedMinor = 2731), expected.copy(currencyCode = "USD"),
            expected.copy(category = PaymentCategory.values().first { it != PaymentCategory.CASH }),
            expected.copy(channel = PaymentChannel.values().first { it != PaymentChannel.CASH_TENDER }),
            expected.copy(operationId = "other-op"), expected.copy(transactionId = "other-tx"),
            expected.copy(subjectId = "other-tx"),
            expected.copy(subjectType = SubjectType.values().first { it != SubjectType.TRANSACTION }),
            expected.copy(entryPurpose = EntryPurpose.values().first { it != EntryPurpose.SALE_FUNDING }),
            expected.copy(confirmationType = ConfirmationType.values().first { it != ConfirmationType.CASHIER_CONFIRMED }),
            expected.copy(actorUserId = "other"), expected.copy(occurredAt = 1700000000001L)
        )
        for (variant in variants) {
            database.close()
            setUp()
            // A real operation parent keeps FK enforcement enabled for the wrong-link fixture.
            val original = seedRecovery(settlements = emptyList())
            database.operationDao().insertOperation(original.copy(id = "other-op", status = OperationStatus.SUCCEEDED))
            database.transactionDao().insertSettlement(variant)
            assertRecoveryBlocked(original)
        }
    }

    @Test
    fun testExistingRecoveryRejectsItemMismatchesAndExtras() = runBlocking {
        val expected = recoveryItem()
        val variants = listOf(
            listOf(expected.copy(id = "other-item")), listOf(expected.copy(productId = "other")),
            listOf(expected.copy(productNameSnapshot = "other")), listOf(expected.copy(descriptionSnapshot = "other")),
            listOf(expected.copy(quantity = 3)), listOf(expected.copy(unitPriceMinor = 1201)),
            listOf(expected.copy(lineTotalMinor = 2401)), listOf(expected.copy(barcodeSnapshot = "other")),
            listOf(expected.copy(itemPackingChargeMinor = 11)), listOf(expected.copy(itemShippingChargeMinor = 21)),
            listOf(expected.copy(mediaId = "unexpected")), listOf(expected, expected.copy(id = "extra-item"))
        )
        for (variant in variants) {
            database.close()
            setUp()
            assertRecoveryBlocked(seedRecovery(items = variant))
        }
    }

    @Test
    fun testExistingRecoveryRejectsExtraSettlement() = runBlocking {
        assertRecoveryBlocked(seedRecovery(settlements = listOf(
            recoverySettlement(), recoverySettlement().copy(id = "extra-settlement")
        )))
    }

    @Test
    fun testAbsentTransactionWithOrphanSettlementDoesNotInsertMoney() = runBlocking {
        assertRecoveryBlocked(seedRecovery(transaction = null, items = emptyList()))
    }

    @Test
    fun testAbsentRecoveryPreservesV1V2V3WithoutCurrentAuthorization() = runBlocking {
        for (version in 1..3) {
            database.close()
            setUp()
            seedRecovery(transaction = null, items = emptyList(), settlements = emptyList(), version = version)
            database.userDao().updateMembership(testMembershipUnauthorized)
            repository.recoverPendingCashSales()
            assertEquals(recoveryTransaction(), database.transactionDao().getTransactionById("recovery-tx"))
            assertEquals(listOf(recoveryItem()), database.transactionDao().getItemsForTransaction("recovery-tx"))
            val settlement = database.transactionDao().getSettlementsForTransaction("recovery-tx").single()
            assertTrue(settlement.id.isNotBlank())
            assertEquals(recoverySettlement().copy(id = settlement.id), settlement)
            assertEquals(OperationStatus.SUCCEEDED, database.operationDao().getOperationById("recovery-op")!!.status)
            val before = financialRows()
            assertTrue(repository.recoverPendingCashSales().isEmpty())
            assertEquals(before, financialRows())
        }
    }

    @Test
    fun testRecoveryIgnoresItemRowOrder() = runBlocking {
        val command = recoveryCommand().let { original ->
            original.copy(draftState = original.draftState.copy(items = listOf(
                original.draftState.items.single().copy(id = "item-b", quantity = 1),
                original.draftState.items.single().copy(id = "item-a", quantity = 1)
            )))
        }
        seedRecovery(command = command, items = listOf(
            recoveryItem().copy(id = "item-a", quantity = 1, lineTotalMinor = 1200),
            recoveryItem().copy(id = "item-b", quantity = 1, lineTotalMinor = 1200)
        ))
        val before = financialRows()
        assertEquals(listOf(recoveryTransaction()), repository.recoverPendingCashSales())
        assertEquals(before, financialRows())
    }

    @Test
    fun testNonItemizedRecoveryRejectsUnexpectedItems() = runBlocking {
        val command = recoveryCommand().let { original ->
            original.copy(draftState = original.draftState.copy(
                items = emptyList(), manualAmount = MoneyMinorUnits(2400)
            ))
        }
        assertRecoveryBlocked(seedRecovery(
            command = command,
            transaction = recoveryTransaction().copy(grandTotalMinor = 2670),
            settlements = listOf(recoverySettlement().copy(amountSignedMinor = 2670))
        ))
    }

    @Test
    fun testRecoveryRejectsInvalidDurableIdentityAndPayload() = runBlocking {
        val variants: List<(OperationEntity) -> OperationEntity> = listOf(
            { it.copy(subjectId = "other") },
            { it.copy(subjectType = SubjectType.BUSINESS) },
            { it.copy(transactionId = "other") },
            { it.copy(resultReferenceId = "other") },
            { it.copy(resultReferenceType = "BUSINESS") },
            { it.copy(intentPayload = "invalid") },
            { it.copy(commandSchemaVersion = 99) },
            { it.copy(requestFingerprint = "different") }
        )
        for (variant in variants) {
            database.close()
            setUp()
            assertRecoveryBlocked(seedRecovery(operationTransform = variant))
        }
    }

    @Test
    fun testRecoveryDetectsSettlementLinkedOnlyBySubject() = runBlocking {
        val original = seedRecovery(settlements = emptyList())
        database.operationDao().insertOperation(original.copy(id = "other-op", status = OperationStatus.SUCCEEDED))
        database.transactionDao().insertSettlement(recoverySettlement().copy(
            operationId = "other-op", transactionId = "other-tx"
        ))
        assertRecoveryBlocked(original)
    }

    @Test
    fun testConcurrentRecoveryCreatesOnlyOneFinancialResult() = runBlocking {
        seedRecovery(transaction = null, items = emptyList(), settlements = emptyList())
        val gate = CompletableDeferred<Unit>()
        val attempts = (1..2).map {
            async(Dispatchers.IO) {
                gate.await()
                RegisterRepository(database, CommandExecutor(database)).recoverPendingCashSales()
            }
        }
        gate.complete(Unit)
        attempts.awaitAll()
        assertEquals(recoveryTransaction(), database.transactionDao().getTransactionById("recovery-tx"))
        assertEquals(listOf(recoveryItem()), database.transactionDao().getItemsForTransaction("recovery-tx"))
        assertEquals(1, database.transactionDao().getSettlementsForTransaction("recovery-tx").size)
        assertEquals(OperationStatus.SUCCEEDED, database.operationDao().getOperationById("recovery-op")!!.status)
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



