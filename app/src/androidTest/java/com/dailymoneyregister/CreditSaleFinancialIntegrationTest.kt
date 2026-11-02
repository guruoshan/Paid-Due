package com.dailymoneyregister

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
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
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.customers.data.CustomerIdentity
import com.dailymoneyregister.feature.register.data.CreditSaleCommand
import com.dailymoneyregister.feature.register.data.CreditSalePayloadCodec
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreditSaleFinancialIntegrationTest {

    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository

    private val business =
        BusinessEntity(
            "biz-credit-1b",
            "Credit Phase 1B Test",
            0L,
            0L
        )

    private val branch =
        BranchEntity(
            id = "branch-credit-1b",
            businessId = business.id,
            name = "Credit Test Branch",
            code = "CR1B",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val user =
        UserAccountEntity(
            id = "credit-cashier",
            businessId = business.id,
            displayName = "Credit Admin",
            username = "credit_admin",
            role = UserRole.ADMIN,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val membership =
        UserBranchMembershipEntity(
            id = "credit-membership",
            userId = user.id,
            branchId = branch.id,
            role = UserRole.ADMIN,
            canCreateSale = true,
            canAcceptCash = true,
            canAcceptOnline = true,
            canCreateCreditSale = true,
            createdAt = 0L
        )

    @Before
    fun setUp() = runBlocking {
        database =
            DmrDatabase.createInMemory(
                ApplicationProvider.getApplicationContext()
            )

        repository =
            RegisterRepository(
                database,
                CommandExecutor(database)
            )

        database.businessBranchDao().insertBusiness(business)
        database.businessBranchDao().insertBranch(branch)
        database.userDao().insertUser(user)
        database.userDao().insertMembership(membership)

        SessionManager.setSession(
            ActiveSession(
                business,
                branch,
                user,
                membership
            )
        )
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    private fun command(
        operationId: String = "credit-op-1",
        transactionId: String = "credit-tx-1",
        phone: String = "+96891234567",
        name: String = "Credit Customer",
        amountMinor: Long = 5000L,
        occurredAt: Long = 1700000000000L
    ) =
        CreditSaleCommand(
            operationId = OperationId(operationId),
            businessId = business.id,
            branchId = branch.id,
            actorUserId = user.id,
            transactionIdVal = TransactionId(transactionId),
            customerPhone = phone,
            customerName = name,
            draftState =
                DraftState(
                    manualAmount =
                        MoneyMinorUnits(amountMinor)
                ),
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            deviceId = "credit-test-device",
            occurredAt = occurredAt
        )

    private fun pending(
        command: CreditSaleCommand
    ) =
        OperationEntity(
            id = command.operationId.value,
            businessId = command.businessId,
            branchId = command.branchId,
            operationType = command.operationType,
            subjectType = command.subjectType,
            subjectId = command.subjectId,
            transactionId = command.transactionId,
            actorUserId = command.actorUserId,
            deviceId = command.deviceId,
            occurredAt = command.occurredAt,
            requestFingerprint = command.requestFingerprint,
            commandSchemaVersion = command.commandSchemaVersion,
            intentPayload = command.intentPayload,
            status = OperationStatus.PENDING
        )

    @Test
    fun customerCreditOutstandingQueryMatchesLedgerAndKeepsCurrencyGuard() =
        runBlocking {
            val result = repository.executeCreditSale(command())
            assertTrue(result.toString(), result is CommandResult.Success)

            val transaction = (result as CommandResult.Success).data
            val customerId = checkNotNull(transaction.customerId)
            val persistedBalance = database.ledgerDao()
                .getCreditBalance(customerId, branch.currencyCode)

            assertEquals(
                persistedBalance,
                repository.getCustomerCreditOutstanding(
                    customerId,
                    branch.currencyCode
                )
            )

            val wrongCurrencyError = runCatching {
                repository.getCustomerCreditOutstanding(customerId, "USD")
            }.exceptionOrNull()
            assertTrue(
                "A currency outside the active branch must remain rejected",
                wrongCurrencyError is IllegalArgumentException
            )
        }

    @Test
    fun newCustomerCreditSaleCreatesOneAuthoritativeSaleAndObligation() =
        runBlocking {

            val command = command()

            val result =
                repository.executeCreditSale(command)

            assertTrue(
                result.toString(),
                result is CommandResult.Success
            )

            val transaction =
                (result as CommandResult.Success).data

            assertFalse(result.isIdempotentReplay)

            assertEquals(
                TransactionType.SALE,
                transaction.type
            )

            assertEquals(
                command.transactionIdVal.value,
                transaction.id
            )

            assertEquals(
                command.draftState.grandTotal.amount,
                transaction.grandTotalMinor
            )

            assertEquals(
                "OMR",
                transaction.currencyCode
            )

            val customerId =
                assertNotNull(transaction.customerId)
                    .let { transaction.customerId!! }

            val customer =
                database.customerDao()
                    .getCustomerById(customerId)

            assertNotNull(customer)

            assertEquals(
                CustomerIdentity.canonicalPhone(
                    command.customerPhone
                ),
                customer!!.canonicalPhone
            )

            assertEquals(
                command.customerName,
                customer.name
            )

            val settlements =
                database.transactionDao()
                    .getSettlementsForTransaction(
                        transaction.id
                    )

            assertEquals(1, settlements.size)

            val settlement = settlements.single()

            assertEquals(
                command.operationId.value,
                settlement.operationId
            )

            assertEquals(
                EntryPurpose.SALE_FUNDING,
                settlement.entryPurpose
            )

            assertEquals(
                PaymentCategory.CREDIT,
                settlement.category
            )

            assertEquals(
                PaymentChannel.CREDIT_ACCOUNT,
                settlement.channel
            )

            assertEquals(
                ConfirmationType.CASHIER_CONFIRMED,
                settlement.confirmationType
            )

            assertEquals(
                command.draftState.grandTotal.amount,
                settlement.amountSignedMinor
            )

            val creditEvents =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )

            assertEquals(1, creditEvents.size)

            val obligation =
                creditEvents.single()

            assertEquals(
                CreditLedgerType.OBLIGATION,
                obligation.type
            )

            assertEquals(
                command.operationId.value,
                obligation.operationId
            )

            assertEquals(
                transaction.id,
                obligation.transactionId
            )

            assertEquals(
                customerId,
                obligation.customerId
            )

            assertEquals(
                command.draftState.grandTotal.amount,
                obligation.amountSignedMinor
            )

            assertEquals(
                command.draftState.grandTotal.amount,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        command.currencyCode
                    )
            )
        }

    @Test
    fun existingCanonicalCustomerIsReusedWithoutDuplicateCustomer() =
        runBlocking {

            val canonicalPhone = "+96891234567"

            val existing =
                CustomerEntity(
                    id = "existing-credit-customer",
                    businessId = business.id,
                    canonicalPhone = canonicalPhone,
                    name = "Existing Customer",
                    createdAt = 100L,
                    updatedAt = 100L
                )

            database.customerDao()
                .insertCustomer(existing)

            val command =
                command(
                    phone = "+968 9123-4567",
                    name = "Existing Customer"
                )

            val result =
                repository.executeCreditSale(command)

            assertTrue(
                result.toString(),
                result is CommandResult.Success
            )

            val transaction =
                (result as CommandResult.Success).data

            assertEquals(
                existing.id,
                transaction.customerId
            )

            val resolved =
                database.customerDao()
                    .getCustomerByPhone(
                        business.id,
                        canonicalPhone
                    )

            assertNotNull(resolved)

            assertEquals(
                existing.id,
                resolved!!.id
            )

            assertEquals(
                command.draftState.grandTotal.amount,
                database.ledgerDao()
                    .getCreditBalance(
                        existing.id,
                        command.currencyCode
                    )
            )
        }

    @Test
    fun identicalReplayIsIdempotentAndDoesNotDuplicateDebt() =
        runBlocking {

            val command = command()

            val first =
                repository.executeCreditSale(command)

            val second =
                repository.executeCreditSale(command)

            assertTrue(
                first.toString(),
                first is CommandResult.Success
            )

            assertTrue(
                second.toString(),
                second is CommandResult.Success
            )

            assertFalse(
                (first as CommandResult.Success)
                    .isIdempotentReplay
            )

            assertTrue(
                (second as CommandResult.Success)
                    .isIdempotentReplay
            )

            val transaction =
                second.data

            val customerId =
                transaction.customerId!!

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForTransaction(
                        transaction.id
                    ).size
            )

            val events =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }

            assertEquals(1, events.size)

            assertEquals(
                command.draftState.grandTotal.amount,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        command.currencyCode
                    )
            )
        }

    @Test
    fun changedIntentUsingSameOperationIdIsRejected() =
        runBlocking {

            val original =
                command(
                    amountMinor = 5000L
                )

            val changed =
                command(
                    amountMinor = 6000L
                )

            assertTrue(
                repository.executeCreditSale(original)
                    is CommandResult.Success
            )

            val result =
                repository.executeCreditSale(changed)

            assertTrue(
                result.toString(),
                result is CommandResult.Conflict
            )

            val transaction =
                database.transactionDao()
                    .getTransactionById(
                        original.transactionIdVal.value
                    )

            assertNotNull(transaction)

            assertEquals(
                5000L,
                transaction!!.grandTotalMinor
            )

            val customerId =
                transaction.customerId!!

            assertEquals(
                5000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        original.currencyCode
                    )
            )
        }

    @Test
    fun durablePayloadRoundTripPreservesCustomerIdentity() {

        val original =
            command(
                phone = "+968 9123-4567",
                name = "Durable Credit Customer"
            )

        val decoded =
            CreditSalePayloadCodec.decode(
                original.intentPayload
            )

        assertEquals(
            original.operationId,
            decoded.operationId
        )

        assertEquals(
            original.transactionIdVal,
            decoded.transactionIdVal
        )

        assertEquals(
            CustomerIdentity.canonicalPhone(
                original.customerPhone
            ),
            decoded.customerPhone
        )

        assertEquals(
            original.customerName,
            decoded.customerName
        )

        assertEquals(
            original.draftState.grandTotal.amount,
            decoded.draftState.grandTotal.amount
        )

        assertEquals(
            original.requestFingerprint,
            decoded.requestFingerprint
        )
    }

    @Test
    fun creditObligationFailureRollsBackSaleTransactionItemsAndSettlement() = runBlocking {
        val command = command(
            operationId = "credit-obligation-rollback-op",
            transactionId = "credit-obligation-rollback-tx"
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER test_abort_credit_sale_obligation
            BEFORE INSERT ON credit_ledger_events
            BEGIN
                SELECT RAISE(ABORT, 'forced Credit sale obligation failure');
            END
            """.trimIndent()
        )

        val result = try {
            repository.executeCreditSale(command)
        } finally {
            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER IF EXISTS test_abort_credit_sale_obligation"
            )
        }

        assertTrue("Injected obligation failure must fail the Credit sale: $result", result is CommandResult.Error)
        assertEquals(null, database.transactionDao().getTransactionById(command.transactionIdVal.value))
        assertTrue(database.transactionDao().getItemsForTransaction(command.transactionIdVal.value).isEmpty())
        assertTrue(database.transactionDao().getSettlementsForTransaction(command.transactionIdVal.value).isEmpty())
        val creditRows = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM credit_ledger_events WHERE operation_id = ?",
            arrayOf(command.operationId.value)
        ).use {
            assertTrue(it.moveToFirst())
            it.getInt(0)
        }
        assertEquals(0, creditRows)
    }

    @Test
    fun malformedPendingCreditSaleFailsClosedWithoutChangingItsDurableStatus() = runBlocking {
        val operation = OperationEntity(
            id = "malformed-credit-recovery-op",
            businessId = business.id,
            branchId = branch.id,
            operationType = OperationType.RECORD_SALE,
            subjectType = SubjectType.TRANSACTION,
            subjectId = "malformed-credit-recovery-tx",
            transactionId = "malformed-credit-recovery-tx",
            actorUserId = user.id,
            deviceId = "device-1",
            occurredAt = 1700000000000L,
            requestFingerprint = "malformed-credit-fingerprint",
            commandSchemaVersion = 1,
            intentPayload = "v=1\ncommandType=CREDIT_SALE\n",
            status = OperationStatus.PENDING
        )
        database.operationDao().insertOperation(operation)

        val failure = runCatching { repository.recoverPendingCreditSales() }.exceptionOrNull()

        assertTrue("Invalid admitted Credit intent must fail closed", failure is IllegalStateException)
        assertEquals(
            OperationStatus.PENDING,
            database.operationDao().getOperationById(operation.id)?.status
        )
        assertEquals(null, database.transactionDao().getTransactionById(operation.transactionId!!))
    }

    @Test
    fun pendingDurableCreditSaleRecoversExactlyOnce() =
        runBlocking {

            val command =
                command(
                    operationId = "credit-recovery-op",
                    transactionId = "credit-recovery-tx"
                )

            database.operationDao()
                .insertOperation(
                    pending(command)
                )

            val recovered =
                repository.recoverPendingCreditSales()

            assertEquals(1, recovered.size)

            val transaction =
                recovered.single()

            assertEquals(
                command.transactionIdVal.value,
                transaction.id
            )

            assertEquals(
                TransactionType.SALE,
                transaction.type
            )

            val customerId =
                transaction.customerId!!

            val events =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }

            assertEquals(1, events.size)

            assertEquals(
                CreditLedgerType.OBLIGATION,
                events.single().type
            )

            assertEquals(
                command.draftState.grandTotal.amount,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        command.currencyCode
                    )
            )

            val secondRecovery =
                repository.recoverPendingCreditSales()

            assertTrue(secondRecovery.isEmpty())

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }
                    .size
            )
        }

    @Test
    fun cashRecoveryDoesNotConsumePendingCreditSale() =
        runBlocking {

            val command =
                command(
                    operationId = "credit-cash-isolation-op",
                    transactionId = "credit-cash-isolation-tx"
                )

            database.operationDao()
                .insertOperation(
                    pending(command)
                )

            val cashRecovered =
                repository.recoverPendingCashSales()

            assertTrue(cashRecovered.isEmpty())

            assertEquals(
                null,
                database.transactionDao()
                    .getTransactionById(
                        command.transactionIdVal.value
                    )
            )

            val stillPending =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )

            assertNotNull(stillPending)

            assertEquals(
                OperationStatus.PENDING,
                stillPending!!.status
            )

            val creditRecovered =
                repository.recoverPendingCreditSales()

            assertEquals(1, creditRecovered.size)
        }
}
