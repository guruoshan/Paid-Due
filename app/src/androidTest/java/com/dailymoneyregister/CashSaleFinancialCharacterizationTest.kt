package com.dailymoneyregister

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
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
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.CashSalePayloadCodec
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CashSaleFinancialCharacterizationTest {

    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = DmrDatabase.createInMemory(context)
        repository = RegisterRepository(database, CommandExecutor(database))
        runBlocking { seedAuthoritativeFixture() }
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun captureNormalV3() {
        runBlocking {
            val operationId = "op-normal-v3"
            val transactionId = "tx-normal-v3"
            val command = v3Command(operationId, transactionId)

            val result = repository.executeCashSale(
                command,
                fixtureUser,
                fixtureMembership
            )

            assertTrue(
                "Current V3 Cash Sale execution failed: $result",
                result is CommandResult.Success
            )

            assertGolden(
                operationId = operationId,
                transactionId = transactionId,
                schemaVersion = 4,
                description = null,
                itemName = "Characterization Item",
                itemDescription = "Observed item description",
                itemBarcode = "barcode-tx-normal-v3",
                displayRef = "SAL-0-L-V3",
                requestFingerprint =
                    "v1|biz:biz-characterization|br:branch-characterization|" +
                        "act:user-characterization|tx:tx-normal-v3|cur:OMR:3|" +
                        "sub:1500|tax:false:0:0|shp:0|pck:0|itemShp:0|itemPck:0|" +
                        "gnd:1500|desc:|items:[item-tx-normal-v3:Characterization Item:1:1500:0:0]|" +
                        "time:1700000000000",
                payload = expectedV4Payload(operationId, transactionId)
            )

            Log.i(
                "PaidnDueCharacterization",
                "CHARACTERIZATION_NORMAL_V3\n${snapshot(operationId, transactionId)}"
            )
        }
    }

    @Test
    fun captureV1Recovery() {
        runBlocking {
            val operationId = "op-v1"
            val transactionId = "tx-v1"
            val payload = historicalPayload(1, operationId, transactionId)

            insertPending(payload)
            repository.recoverPendingCashSales()

            assertGolden(
                operationId = operationId,
                transactionId = transactionId,
                schemaVersion = 1,
                description = "historical description",
                itemName = "Historical Item",
                itemDescription = "Historical description",
                itemBarcode = "barcode-tx-v1",
                displayRef = "SAL-0-X-V1",
                requestFingerprint =
                    "v1|biz:biz-characterization|br:branch-characterization|" +
                        "act:user-characterization|tx:tx-v1|cur:OMR:3|" +
                        "sub:1500|tax:false:0:0|shp:0|pck:0|itemShp:0|itemPck:0|" +
                        "gnd:1500|desc:historical description|" +
                        "items:[item-tx-v1:Historical Item:1:1500:0:0]|time:1700000000000",
                payload = payload
            )

            Log.i(
                "PaidnDueCharacterization",
                "CHARACTERIZATION_V1_RECOVERY\n${snapshot(operationId, transactionId)}"
            )
        }
    }

    @Test
    fun captureV2Recovery() {
        runBlocking {
            val operationId = "op-v2"
            val transactionId = "tx-v2"
            val payload = historicalPayload(2, operationId, transactionId)

            insertPending(payload)
            repository.recoverPendingCashSales()

            assertGolden(
                operationId = operationId,
                transactionId = transactionId,
                schemaVersion = 2,
                description = "historical description",
                itemName = "Historical Item",
                itemDescription = "Historical description",
                itemBarcode = "barcode-tx-v2",
                displayRef = "SAL-0-X-V2",
                requestFingerprint =
                    "v1|biz:biz-characterization|br:branch-characterization|" +
                        "act:user-characterization|tx:tx-v2|cur:OMR:3|" +
                        "sub:1500|tax:false:0:0|shp:0|pck:0|itemShp:0|itemPck:0|" +
                        "gnd:1500|desc:historical description|" +
                        "items:[item-tx-v2:Historical Item:1:1500:0:0]|time:1700000000000",
                payload = payload
            )

            Log.i(
                "PaidnDueCharacterization",
                "CHARACTERIZATION_V2_RECOVERY\n${snapshot(operationId, transactionId)}"
            )
        }
    }

    @Test
    fun captureV3Recovery() {
        runBlocking {
            val operationId = "op-v3-recovery"
            val transactionId = "tx-v3-recovery"
            val payload = expectedV3Payload(operationId, transactionId)

            insertPending(payload)
            repository.recoverPendingCashSales()

            assertGolden(
                operationId = operationId,
                transactionId = transactionId,
                schemaVersion = 3,
                description = null,
                itemName = "Characterization Item",
                itemDescription = "Observed item description",
                itemBarcode = "barcode-tx-v3-recovery",
                displayRef = "SAL-0-VERY",
                requestFingerprint =
                    "v1|biz:biz-characterization|br:branch-characterization|" +
                        "act:user-characterization|tx:tx-v3-recovery|cur:OMR:3|" +
                        "sub:1500|tax:false:0:0|shp:0|pck:0|itemShp:0|itemPck:0|" +
                        "gnd:1500|desc:|" +
                        "items:[item-tx-v3-recovery:Characterization Item:1:1500:0:0]|" +
                        "time:1700000000000",
                payload = payload
            )

            Log.i(
                "PaidnDueCharacterization",
                "CHARACTERIZATION_V3_RECOVERY\n${snapshot(operationId, transactionId)}"
            )
        }
    }

    @Test
    fun pendingCashSaleSurvivesReopenAndQueuesExactlyOnce() = runBlocking {
        val databaseName = "boardlock_g3_cash_sale_recovery.db"
        val testContext = context
        val operationId = "g3-pending-operation"
        val transactionId = "g3-pending-transaction"

        // Only this dedicated test database can be deleted.
        // Never use the production database name.
        database.close()
        testContext.deleteDatabase(databaseName)

        fun openTestDatabase(): DmrDatabase =
            androidx.room.Room.databaseBuilder(
                testContext,
                DmrDatabase::class.java,
                databaseName
            )
                .addMigrations(DmrDatabase.MIGRATION_12_13)
                .addCallback(
                    com.dailymoneyregister.core.database.security
                        .FinancialImmutabilityCallback
                )
                .allowMainThreadQueries()
                .build()

        fun countRowsForOperation(table: String): Int {
            require(table in setOf(
                "operations",
                "financial_sync_outbox",
                "settlement_entries"
            ))
            return database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM $table WHERE operation_id = ?".let {
                    if (table == "operations") {
                        "SELECT COUNT(*) FROM operations WHERE id = ?"
                    } else {
                        it
                    }
                },
                arrayOf(operationId)
            ).use {
                check(it.moveToFirst())
                it.getInt(0)
            }
        }

        try {
            // 1. Persist a genuine PENDING cash-sale command
            // using the existing characterization fixture.
            database = openTestDatabase()
            repository = RegisterRepository(
                database,
                CommandExecutor(database)
            )
            seedAuthoritativeFixture()

            val command = v3Command(operationId, transactionId)
            insertPending(command.intentPayload)

            assertEquals(
                com.dailymoneyregister.core.model.enums.OperationStatus.PENDING,
                database.operationDao().getOperationById(operationId)?.status
            )
            assertEquals(1, countRowsForOperation("operations"))
            assertEquals(0, countRowsForOperation("financial_sync_outbox"))
            assertEquals(0, countRowsForOperation("settlement_entries"))

            // 2. Close and reopen the same persisted test database.
            // This simulates a durable interrupted command.
            database.close()

            database = openTestDatabase()
            repository = RegisterRepository(
                database,
                CommandExecutor(database)
            )

            // 3. Invoke the real production recovery method.
            repository.recoverPendingCashSales()

            val recoveredOperation =
                database.operationDao().getOperationById(operationId)

            assertEquals(
                com.dailymoneyregister.core.model.enums.OperationStatus.SUCCEEDED,
                recoveredOperation?.status
            )

            val recoveredTransaction =
                database.transactionDao().getTransactionById(transactionId)

            assertNotNull(recoveredTransaction)
            assertEquals(1500L, recoveredTransaction!!.grandTotalMinor)

            assertEquals(1, countRowsForOperation("operations"))
            assertEquals(1, countRowsForOperation("settlement_entries"))
            assertEquals(1, countRowsForOperation("financial_sync_outbox"))

            val outbox = database.openHelper.readableDatabase.query(
                """
                SELECT operation_id, transaction_id, status,
                       request_fingerprint
                FROM financial_sync_outbox
                WHERE operation_id = ?
                """.trimIndent(),
                arrayOf(operationId)
            ).use {
                assertTrue(it.moveToFirst())
                listOf(
                    it.getString(0),
                    it.getString(1),
                    it.getString(2),
                    it.getString(3)
                )
            }

            assertEquals(operationId, outbox[0])
            assertEquals(transactionId, outbox[1])
            assertEquals("QUEUED", outbox[2])
            assertEquals(command.requestFingerprint, outbox[3])

            // 4. Recovery retried on the same database.
            repository.recoverPendingCashSales()

            assertEquals(1, countRowsForOperation("operations"))
            assertEquals(1, countRowsForOperation("settlement_entries"))
            assertEquals(1, countRowsForOperation("financial_sync_outbox"))

            // 5. Recovery retried after another database reopen.
            database.close()
            database = openTestDatabase()
            repository = RegisterRepository(
                database,
                CommandExecutor(database)
            )

            repository.recoverPendingCashSales()

            assertEquals(1, countRowsForOperation("operations"))
            assertEquals(1, countRowsForOperation("settlement_entries"))
            assertEquals(1, countRowsForOperation("financial_sync_outbox"))

            val settlements =
                database.transactionDao()
                    .getSettlementsForTransaction(transactionId)

            assertEquals(1, settlements.size)
            assertEquals(1500L, settlements.single().amountSignedMinor)
        } finally {
            database.close()
            testContext.deleteDatabase(databaseName)

            // Keep the JUnit @After database-close path valid.
            database = DmrDatabase.createInMemory(testContext)
            repository = RegisterRepository(
                database,
                CommandExecutor(database)
            )
        }
    }
    private suspend fun insertPending(payload: String) {
        val command = CashSalePayloadCodec.decode(payload)
        val payloadVersion = payload.lineSequence()
            .first { it.startsWith("v=") }
            .removePrefix("v=")
            .toInt()

        database.operationDao().insertOperation(
            OperationEntity(
                id = command.operationId.value,
                businessId = command.businessId,
                branchId = command.branchId,
                operationType = OperationType.RECORD_SALE,
                subjectType = SubjectType.TRANSACTION,
                subjectId = command.transactionIdVal.value,
                transactionId = command.transactionIdVal.value,
                actorUserId = command.actorUserId,
                deviceId = command.deviceId,
                occurredAt = command.occurredAt,
                requestFingerprint = command.requestFingerprint,
                commandSchemaVersion = payloadVersion,
                intentPayload = payload,
                status = OperationStatus.PENDING
            )
        )
    }

    private suspend fun snapshot(operationId: String, transactionId: String): String {
        val transaction = database.transactionDao().getTransactionById(transactionId)
        val items = database.transactionDao().getItemsForTransaction(transactionId)
        val settlements = database.transactionDao().getSettlementsForTransaction(transactionId)
        val operation = database.operationDao().getOperationById(operationId)
        val operationsForTransaction =
            database.operationDao().getOperationsForTransaction(transactionId)

        return buildString {
            appendLine("counts.transaction=${if (transaction == null) 0 else 1}")
            appendLine("counts.items=${items.size}")
            appendLine("counts.settlements=${settlements.size}")
            appendLine("counts.operations=${operationsForTransaction.size}")
            appendLine("counts.allTransactions=${countRows("transactions")}")
            appendLine("counts.allItems=${countRows("transaction_items")}")
            appendLine("counts.allSettlements=${countRows("settlement_entries")}")
            appendLine("counts.allOperations=${countRows("operations")}")
            appendLine("transaction=$transaction")
            appendLine("items=$items")
            appendLine("settlements=$settlements")
            appendLine("operation=$operation")
        }
    }

    private suspend fun assertGolden(
        operationId: String,
        transactionId: String,
        schemaVersion: Int,
        description: String?,
        itemName: String,
        itemDescription: String,
        itemBarcode: String,
        displayRef: String,
        requestFingerprint: String,
        payload: String
    ) {
        val transaction = database.transactionDao().getTransactionById(transactionId)
        val items = database.transactionDao().getItemsForTransaction(transactionId)
        val settlements = database.transactionDao().getSettlementsForTransaction(transactionId)
        val operation = database.operationDao().getOperationById(operationId)
        val operationsForTransaction =
            database.operationDao().getOperationsForTransaction(transactionId)

        assertEquals(1, countRows("transactions"))
        assertEquals(1, countRows("transaction_items"))
        assertEquals(1, countRows("settlement_entries"))
        val opTypes = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT operation_type FROM operations ORDER BY rowid")
            .use { c -> while (c.moveToNext()) opTypes.add(c.getString(0)) }
        if (operation?.cashShiftId == null) {
            assertEquals(listOf("RECORD_SALE"), opTypes)
        } else {
            assertEquals(
                listOf("OPEN_CASH_SHIFT", "RECORD_SALE"),
                opTypes.sorted()
            )
        }

        assertNotNull(transaction)
        assertEquals(1, items.size)
        assertEquals(1, settlements.size)
        assertEquals(1, operationsForTransaction.size)

        val tx = transaction!!
        assertEquals(transactionId, tx.id)
        assertEquals("branch-characterization", tx.branchId)
        assertEquals(TransactionType.SALE, tx.type)
        assertNull(tx.customerId)
        assertEquals(1500L, tx.subtotalMinor)
        assertEquals(0, tx.taxRateBps)
        assertEquals(1500L, tx.taxableBaseMinor)
        assertEquals(0L, tx.taxAmountMinor)
        assertEquals(0L, tx.txShippingMinor)
        assertEquals(0L, tx.txPackingMinor)
        assertEquals(1500L, tx.grandTotalMinor)
        assertEquals("OMR", tx.currencyCode)
        assertEquals(3, tx.currencyDecimalPlaces)
        assertEquals("user-characterization", tx.createdByUserId)
        assertEquals("device-characterization", tx.deviceId)
        assertEquals(1700000000000L, tx.createdAt)
        assertEquals(description, tx.description)
        assertEquals(displayRef, tx.displayRef)

        val item = items.single()
        assertEquals("item-$transactionId", item.id)
        assertEquals(transactionId, item.transactionId)
        assertNull(item.productId)
        assertEquals(itemName, item.productNameSnapshot)
        assertEquals(itemDescription, item.descriptionSnapshot)
        assertEquals(1, item.quantity)
        assertEquals(1500L, item.unitPriceMinor)
        assertEquals(1500L, item.lineTotalMinor)
        assertEquals(itemBarcode, item.barcodeSnapshot)
        assertNull(item.mediaId)
        assertEquals(0L, item.itemPackingChargeMinor)
        assertEquals(0L, item.itemShippingChargeMinor)

        val settlement = settlements.single()
        assertTrue(settlement.id.isNotBlank())
        assertEquals(operationId, settlement.operationId)
        assertEquals(SubjectType.TRANSACTION, settlement.subjectType)
        assertEquals(transactionId, settlement.subjectId)
        assertEquals(transactionId, settlement.transactionId)
        assertEquals(EntryPurpose.SALE_FUNDING, settlement.entryPurpose)
        assertEquals(PaymentCategory.CASH, settlement.category)
        assertEquals(PaymentChannel.CASH_TENDER, settlement.channel)
        assertEquals(1500L, settlement.amountSignedMinor)
        assertEquals("OMR", settlement.currencyCode)
        assertEquals(ConfirmationType.ADMIN_CONFIRMED, settlement.confirmationType)
        assertNull(settlement.qrProfileIdSnapshot)
        assertEquals(1700000000000L, settlement.occurredAt)
        assertEquals("user-characterization", settlement.actorUserId)
        assertNull(settlement.providerRef)

        assertNotNull(operation)
        val op = operation!!
        assertEquals(operationId, op.id)
        assertEquals("biz-characterization", op.businessId)
        assertEquals("branch-characterization", op.branchId)
        assertEquals(OperationType.RECORD_SALE, op.operationType)
        assertEquals(SubjectType.TRANSACTION, op.subjectType)
        assertEquals(transactionId, op.subjectId)
        assertEquals(transactionId, op.transactionId)
        assertEquals("user-characterization", op.actorUserId)
        assertEquals("device-characterization", op.deviceId)
        assertEquals(1700000000000L, op.occurredAt)
        assertEquals(requestFingerprint, op.requestFingerprint)
        assertEquals(schemaVersion, op.commandSchemaVersion)
        assertEquals(payload, op.intentPayload)
        assertEquals(OperationStatus.SUCCEEDED, op.status)
        assertEquals("TRANSACTION", op.resultReferenceType)
        assertEquals(transactionId, op.resultReferenceId)
        assertNull(op.reason)
    }

    private fun v3Command(operationId: String, transactionId: String) =
        CashSaleCommand(
            operationId = OperationId(operationId),
            businessId = "biz-characterization",
            branchId = "branch-characterization",
            actorUserId = fixtureUser.id,
            transactionIdVal = TransactionId(transactionId),
            draftState = DraftState(
                items = listOf(
                    DraftItem(
                        id = "item-$transactionId",
                        name = "Characterization Item",
                        description = "Observed item description",
                        unitPrice = MoneyMinorUnits(1500L),
                        quantity = 1,
                        barcode = "barcode-$transactionId"
                    )
                ),
                taxRateBps = 0,
                isTaxEnabled = false,
                taxRoundingMode = TaxRoundingMode.HALF_UP
            ),
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "device-characterization",
            occurredAt = 1700000000000L
        )

    private fun expectedV3Payload(operationId: String, transactionId: String): String =
        buildString {
            appendLine("v=3")
            appendLine("commandType=CASH_SALE")
            appendLine("operationId=$operationId")
            appendLine("businessId=biz-characterization")
            appendLine("branchId=branch-characterization")
            appendLine("actorUserId=user-characterization")
            appendLine("transactionId=$transactionId")
            appendLine("currencyCode=OMR")
            appendLine("currencyDecimalPlaces=3")
            appendLine("deviceId=device-characterization")
            appendLine("occurredAt=1700000000000")
            appendLine("manualAmount=0")
            appendLine("manualDescription=")
            appendLine("taxRateBps=0")
            appendLine("isTaxEnabled=false")
            appendLine("taxRoundingMode=HALF_UP")
            appendLine("txShipping=0")
            appendLine("txPacking=0")
            appendLine("itemCount=1")
            appendLine("item[0].id=item-$transactionId")
            appendLine("item[0].name=Characterization Item")
            appendLine("item[0].desc=Observed item description")
            appendLine("item[0].unitPrice=1500")
            appendLine("item[0].qty=1")
            appendLine("item[0].barcode=barcode-$transactionId")
            appendLine("item[0].itemPacking=0")
            appendLine("item[0].itemShipping=0")
        }

    private fun expectedV4Payload(operationId: String, transactionId: String): String =
        buildString {
            appendLine("v=4")
            appendLine("commandType=CASH_SALE")
            appendLine("operationId=$operationId")
            appendLine("businessId=biz-characterization")
            appendLine("branchId=branch-characterization")
            appendLine("actorUserId=user-characterization")
            appendLine("transactionId=$transactionId")
            appendLine("currencyCode=OMR")
            appendLine("currencyDecimalPlaces=3")
            appendLine("deviceId=device-characterization")
            appendLine("occurredAt=1700000000000")
            appendLine("customerName=")
            appendLine("customerPhone=")
            appendLine("manualAmount=0")
            appendLine("manualDescription=")
            appendLine("taxRateBps=0")
            appendLine("isTaxEnabled=false")
            appendLine("taxRoundingMode=HALF_UP")
            appendLine("txShipping=0")
            appendLine("txPacking=0")
            appendLine("itemCount=1")
            appendLine("item[0].id=item-$transactionId")
            appendLine("item[0].name=Characterization Item")
            appendLine("item[0].desc=Observed item description")
            appendLine("item[0].unitPrice=1500")
            appendLine("item[0].qty=1")
            appendLine("item[0].barcode=barcode-$transactionId")
            appendLine("item[0].itemPacking=0")
            appendLine("item[0].itemShipping=0")
        }

    private fun historicalPayload(
        version: Int,
        operationId: String,
        transactionId: String
    ): String =
        buildString {
            require(version == 1 || version == 2)

            appendLine("v=$version")
            if (version == 2) appendLine("commandType=CASH_SALE")
            appendLine("operationId=$operationId")
            appendLine("businessId=biz-characterization")
            appendLine("branchId=branch-characterization")
            appendLine("actorUserId=${fixtureUser.id}")
            appendLine("transactionId=$transactionId")
            appendLine("currencyCode=OMR")
            appendLine("currencyDecimalPlaces=3")
            appendLine("deviceId=device-characterization")
            appendLine("occurredAt=1700000000000")
            appendLine("manualAmount=1500")
            appendLine("manualDescription=historical description")
            appendLine("taxRateBps=0")
            appendLine("isTaxEnabled=false")
            appendLine("taxRoundingMode=HALF_UP")
            appendLine("txShipping=0")
            appendLine("txPacking=0")
            appendLine("itemCount=1")
            appendLine("item[0].id=item-$transactionId")
            appendLine("item[0].name=Historical Item")
            appendLine("item[0].desc=Historical description")
            appendLine("item[0].unitPrice=1500")
            appendLine("item[0].qty=1")
            appendLine("item[0].barcode=barcode-$transactionId")
            appendLine("item[0].itemPacking=0")
            appendLine("item[0].itemShipping=0")
        }

    private fun countRows(tableName: String): Int {
        val allowedTables = setOf(
            "transactions",
            "transaction_items",
            "settlement_entries",
            "operations"
        )

        require(tableName in allowedTables) {
            "Unsupported characterization table: $tableName"
        }

        val cursor = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $tableName"
        )

        return cursor.use {
            check(it.moveToFirst()) {
                "Could not count rows in $tableName"
            }
            it.getInt(0)
        }
    }

    private suspend fun seedAuthoritativeFixture() {
        val business = BusinessEntity(
            id = "biz-characterization",
            name = "Characterization Business",
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        val branch = BranchEntity(
            id = "branch-characterization",
            businessId = "biz-characterization",
            name = "Characterization Branch",
            code = "CHAR",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        database.businessBranchDao().insertBusiness(business)
        database.businessBranchDao().insertBranch(branch)
        database.userDao().insertUser(fixtureUser)
        database.userDao().insertMembership(fixtureMembership)
        SessionManager.setSession(
            ActiveSession(business, branch, fixtureUser, fixtureMembership)
        )
    }

    private val fixtureUser = UserAccountEntity(
        id = "user-characterization",
        businessId = "biz-characterization",
        displayName = "Characterization Admin",
        username = "characterization_admin",
        role = UserRole.ADMIN,
        authType = AuthType.PIN,
        credentialHash = "hash",
        credentialSalt = "salt",
        createdAt = 1700000000000L,
        updatedAt = 1700000000000L
    )

    private val fixtureMembership = UserBranchMembershipEntity(
        id = "membership-characterization",
        userId = "user-characterization",
        branchId = "branch-characterization",
        role = UserRole.ADMIN,
        canCreateSale = true,
        canAcceptCash = true,
        canAcceptOnline = true,
        createdAt = 1700000000000L
    )
}
