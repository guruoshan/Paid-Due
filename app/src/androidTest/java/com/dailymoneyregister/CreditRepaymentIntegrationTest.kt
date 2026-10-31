package com.dailymoneyregister

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
import com.dailymoneyregister.core.model.enums.CreditLedgerType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
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
import com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
import com.dailymoneyregister.feature.customers.data.CouponCreditRepaymentCommand
import com.dailymoneyregister.feature.customers.data.CouponCreditRepaymentPayloadCodec
import com.dailymoneyregister.feature.register.data.CreditSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreditRepaymentIntegrationTest {

    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository

    private val business =
        BusinessEntity(
            "credit-repay-biz",
            "Credit Repayment Test",
            0L,
            0L
        )

    private val branch =
        BranchEntity(
            id = "credit-repay-branch",
            businessId = business.id,
            name = "Main",
            code = "MAIN",
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
            id = "credit-repay-user",
            businessId = business.id,
            displayName = "Credit Admin",
            username = "credit_repay_admin",
            role = UserRole.ADMIN,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val membership =
        UserBranchMembershipEntity(
            id = "credit-repay-membership",
            userId = user.id,
            branchId = branch.id,
            role = UserRole.ADMIN,
            canCreateSale = true,
            canAcceptCash = true,
            canAcceptOnline = true,
            canCreateCreditSale = true,
            canUseCoupon = true,
            canRefund = true,
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

        database.businessBranchDao()
            .insertBusiness(business)

        database.businessBranchDao()
            .insertBranch(branch)

        database.userDao()
            .insertUser(user)

        database.userDao()
            .insertMembership(membership)

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

    /*
     * Debt must be created through the production Credit Sale command.
     * Tests must not manufacture fake Credit obligations directly.
     */
    /*
     * Credit 1C-C test-only helper.
     *
     * Customer setup may use an explicit deterministic ID because the
     * wallet/customer-binding tests need two distinct known customers.
     *
     * IMPORTANT:
     * Credit debt itself is NEVER inserted directly.
     * The OBLIGATION must still be produced by the real production
     * repository.executeCreditSale() path.
     */
    private suspend fun executeCreditSaleForRepaymentTest(
        operationId: String,
        transactionId: String,
        customerId: String,
        customerPhone: String,
        customerName: String,
        amountMinor: Long,
        occurredAt: Long
    ): String {

        val canonicalPhone =
            com.dailymoneyregister.feature.customers.data
                .CustomerIdentity
                .canonicalPhone(customerPhone)

        val customerDao =
            database.customerDao()

        val existingById =
            customerDao.getCustomerById(
                customerId
            )

        if (existingById == null) {

            val existingByPhone =
                customerDao.getCustomerByPhone(
                    business.id,
                    canonicalPhone
                )

            check(existingByPhone == null) {
                "1C-C test setup phone already belongs to another customer."
            }

            customerDao.insertCustomer(
                com.dailymoneyregister.core.database.entity
                    .CustomerEntity(
                        id = customerId,
                        businessId = business.id,
                        canonicalPhone = canonicalPhone,
                        name = customerName,
                        createdAt = occurredAt,
                        updatedAt = occurredAt
                    )
            )

        } else {

            check(
                existingById.businessId ==
                    business.id &&
                    existingById.canonicalPhone ==
                        canonicalPhone &&
                    existingById.name ==
                        customerName
            ) {
                "1C-C deterministic customer setup mismatch."
            }
        }

        val command =
            CreditSaleCommand(
                operationId =
                    OperationId(operationId),

                businessId =
                    business.id,

                branchId =
                    branch.id,

                actorUserId =
                    user.id,

                transactionIdVal =
                    TransactionId(transactionId),

                customerPhone =
                    canonicalPhone,

                customerName =
                    customerName,

                draftState =
                    DraftState(
                        manualAmount =
                            MoneyMinorUnits(
                                amountMinor
                            )
                    ),

                currencyCode =
                    branch.currencyCode,

                currencyDecimalPlaces =
                    branch.currencyDecimalPlaces,

                deviceId =
                    "credit-1c-c-test-device",

                occurredAt =
                    occurredAt
            )

        val result =
            repository.executeCreditSale(
                command
            )

        assertTrue(
            "Authoritative Credit Sale failed: $result",
            result is CommandResult.Success
        )

        val transaction =
            (result as CommandResult.Success)
                .data

        assertEquals(
            TransactionType.SALE,
            transaction.type
        )

        assertEquals(
            transactionId,
            transaction.id
        )

        assertEquals(
            customerId,
            transaction.customerId
        )

        assertEquals(
            amountMinor,
            transaction.grandTotalMinor
        )

        /*
         * Critical BOARDLOCK assertion:
         * prove that Credit debt came from the production Credit Sale
         * command and not from manufactured test ledger evidence.
         */
        val obligation =
            database.ledgerDao()
                .getCreditLedgerForCustomer(
                    customerId
                )
                .filter {
                    it.operationId ==
                        operationId
                }

        assertEquals(
            1,
            obligation.size
        )

        assertEquals(
            CreditLedgerType.OBLIGATION,
            obligation.single().type
        )

        assertEquals(
            transactionId,
            obligation.single()
                .transactionId
        )

        assertEquals(
            amountMinor,
            obligation.single()
                .amountSignedMinor
        )

        return customerId
    }

    private suspend fun createCreditSale(
        suffix: String,
        amountMinor: Long,
        occurredAt: Long
    ): String {

        val command =
            CreditSaleCommand(
                operationId =
                    OperationId(
                        "credit-sale-op-$suffix"
                    ),
                businessId = business.id,
                branchId = branch.id,
                actorUserId = user.id,
                transactionIdVal =
                    TransactionId(
                        "credit-sale-tx-$suffix"
                    ),
                customerPhone = "+96899112233",
                customerName = "Credit Customer",
                draftState =
                    DraftState(
                        manualAmount =
                            MoneyMinorUnits(
                                amountMinor
                            )
                    ),
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                deviceId = "credit-test-device",
                occurredAt = occurredAt
            )

        val result =
            repository.executeCreditSale(command)

        assertTrue(
            "Credit Sale failed: $result",
            result is CommandResult.Success
        )

        val transaction =
            (result as CommandResult.Success).data

        assertEquals(
            TransactionType.SALE,
            transaction.type
        )

        assertEquals(
            amountMinor,
            transaction.grandTotalMinor
        )

        val customerId =
            transaction.customerId

        assertNotNull(customerId)

        return customerId!!
    }

    private fun repayment(
        suffix: String,
        customerId: String,
        amountMinor: Long,
        category: PaymentCategory =
            PaymentCategory.CASH,
        channel: PaymentChannel =
            PaymentChannel.CASH_TENDER,
        occurredAt: Long =
            1_700_000_100_000L
    ) =
        CreditRepaymentCommand(
            operationId =
                OperationId(
                    "repayment-op-$suffix"
                ),
            businessId = business.id,
            branchId = branch.id,
            actorUserId = user.id,
            repaymentId =
                "repayment-$suffix",
            customerId = customerId,
            amountMinor = amountMinor,
            currencyCode = "OMR",
            paymentCategory = category,
            paymentChannel = channel,
            deviceId = "credit-test-device",
            occurredAt = occurredAt
        )

    private fun pending(
        command: CreditRepaymentCommand
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
            requestFingerprint =
                command.requestFingerprint,
            commandSchemaVersion =
                command.commandSchemaVersion,
            intentPayload =
                command.intentPayload,
            status = OperationStatus.PENDING
        )

    
    /*
     * BOARDLOCK 1C-C FINAL GATE:
     * durable PENDING fixture for the Coupon-funded repayment command.
     * This creates only the parent Operation; no financial child evidence.
     */
    private fun pending(
        command: CouponCreditRepaymentCommand
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
    fun cashRepaymentCreatesExactAuthoritativeFinancialEvidence() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "cash",
                    amountMinor = 5_000L,
                    occurredAt = 1_700_000_000_000L
                )

            assertEquals(
                5_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )

            val command =
                repayment(
                    suffix = "cash",
                    customerId = customerId,
                    amountMinor = 2_000L
                )

            val result =
                repository.executeCreditRepayment(
                    command
                )

            assertTrue(
                result.toString(),
                result is CommandResult.Success
            )

            val success =
                result as CommandResult.Success

            assertFalse(
                success.isIdempotentReplay
            )

            val repayment =
                database.ledgerDao()
                    .getCreditRepaymentById(
                        command.repaymentId
                    )

            assertNotNull(repayment)

            assertEquals(
                command.operationId.value,
                repayment!!.operationId
            )

            assertEquals(
                customerId,
                repayment.customerId
            )

            assertEquals(
                2_000L,
                repayment.amountMinor
            )

            assertEquals(
                PaymentCategory.CASH,
                repayment.paymentCategory
            )

            /*
             * Repayment collection is settlement evidence,
             * not another SALE.
             */
            val settlements =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                1,
                settlements.size
            )

            val collection =
                settlements.single()

            assertEquals(
                SubjectType.REPAYMENT,
                collection.subjectType
            )

            assertEquals(
                command.repaymentId,
                collection.subjectId
            )

            assertNull(
                collection.transactionId
            )

            assertEquals(
                EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
                collection.entryPurpose
            )

            assertEquals(
                PaymentCategory.CASH,
                collection.category
            )

            assertEquals(
                PaymentChannel.CASH_TENDER,
                collection.channel
            )

            assertEquals(
                ConfirmationType.CASHIER_CONFIRMED,
                collection.confirmationType
            )

            assertEquals(
                2_000L,
                collection.amountSignedMinor
            )

            val repaymentEvents =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }

            assertEquals(
                1,
                repaymentEvents.size
            )

            val ledgerEvent =
                repaymentEvents.single()

            assertEquals(
                CreditLedgerType.REPAYMENT,
                ledgerEvent.type
            )

            assertEquals(
                -2_000L,
                ledgerEvent.amountSignedMinor
            )

            assertEquals(
                command.repaymentId,
                ledgerEvent.repaymentId
            )

            assertNull(
                ledgerEvent.transactionId
            )

            val allocations =
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )

            assertEquals(
                1,
                allocations.size
            )

            assertEquals(
                "credit-sale-tx-cash",
                allocations.single()
                    .originalTransactionId
            )

            assertEquals(
                2_000L,
                allocations.single()
                    .allocatedAmountMinor
            )

            assertEquals(
                3_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )

            /*
             * No repayment transaction should masquerade
             * as new sales revenue.
             */
            assertNull(
                database.transactionDao()
                    .getTransactionById(
                        command.repaymentId
                    )
            )
        }

    @Test
    fun onlineRepaymentPreservesOnlineCollectionIdentity() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "online",
                    amountMinor = 4_000L,
                    occurredAt = 1_700_000_010_000L
                )

            val command =
                repayment(
                    suffix = "online",
                    customerId = customerId,
                    amountMinor = 1_500L,
                    category =
                        PaymentCategory.ONLINE,
                    channel =
                        PaymentChannel.MANUAL_QR,
                    occurredAt =
                        1_700_000_110_000L
                )

            val result =
                repository.executeCreditRepayment(
                    command
                )

            assertTrue(
                result.toString(),
                result is CommandResult.Success
            )

            val settlement =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .single()

            assertEquals(
                EntryPurpose.CREDIT_REPAYMENT_COLLECTION,
                settlement.entryPurpose
            )

            assertEquals(
                PaymentCategory.ONLINE,
                settlement.category
            )

            assertEquals(
                PaymentChannel.MANUAL_QR,
                settlement.channel
            )

            assertEquals(
                1_500L,
                settlement.amountSignedMinor
            )

            assertEquals(
                2_500L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )
        }

    @Test
    fun fifoRepaymentConsumesOldestCreditSaleFirst() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "fifo-old",
                    amountMinor = 3_000L,
                    occurredAt = 1_700_000_020_000L
                )

            val sameCustomer =
                createCreditSale(
                    suffix = "fifo-new",
                    amountMinor = 4_000L,
                    occurredAt = 1_700_000_030_000L
                )

            assertEquals(
                customerId,
                sameCustomer
            )

            val command =
                repayment(
                    suffix = "fifo",
                    customerId = customerId,
                    amountMinor = 5_000L,
                    occurredAt =
                        1_700_000_120_000L
                )

            val result =
                repository.executeCreditRepayment(
                    command
                )

            assertTrue(
                result.toString(),
                result is CommandResult.Success
            )

            val allocations =
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )

            assertEquals(
                2,
                allocations.size
            )

            assertEquals(
                "credit-sale-tx-fifo-old",
                allocations[0]
                    .originalTransactionId
            )

            assertEquals(
                3_000L,
                allocations[0]
                    .allocatedAmountMinor
            )

            assertEquals(
                "credit-sale-tx-fifo-new",
                allocations[1]
                    .originalTransactionId
            )

            assertEquals(
                2_000L,
                allocations[1]
                    .allocatedAmountMinor
            )

            assertEquals(
                2_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )
        }

    @Test
    fun exactPayoffProducesZeroDerivedOutstanding() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "payoff",
                    amountMinor = 2_750L,
                    occurredAt = 1_700_000_040_000L
                )

            val command =
                repayment(
                    suffix = "payoff",
                    customerId = customerId,
                    amountMinor = 2_750L,
                    occurredAt =
                        1_700_000_130_000L
                )

            assertTrue(
                repository.executeCreditRepayment(
                    command
                ) is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )

            assertEquals(
                2_750L,
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )
                    .sumOf {
                        it.allocatedAmountMinor
                    }
            )
        }

    @Test
    fun overpaymentFailsWithoutFinancialChildWrites() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "overpay",
                    amountMinor = 1_000L,
                    occurredAt = 1_700_000_050_000L
                )

            val command =
                repayment(
                    suffix = "overpay",
                    customerId = customerId,
                    amountMinor = 1_001L,
                    occurredAt =
                        1_700_000_140_000L
                )

            val result =
                repository.executeCreditRepayment(
                    command
                )

            assertTrue(
                result.toString(),
                result is CommandResult.Error
            )

            assertNull(
                database.ledgerDao()
                    .getCreditRepaymentById(
                        command.repaymentId
                    )
            )

            assertTrue(
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .none {
                        it.operationId ==
                            command.operationId.value
                    }
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )
                    .isEmpty()
            )

            assertEquals(
                1_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )
        }

    @Test
    fun identicalReplayDoesNotDuplicateRepaymentEvidence() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "replay",
                    amountMinor = 6_000L,
                    occurredAt = 1_700_000_060_000L
                )

            val command =
                repayment(
                    suffix = "replay",
                    customerId = customerId,
                    amountMinor = 2_000L,
                    occurredAt =
                        1_700_000_150_000L
                )

            val first =
                repository.executeCreditRepayment(
                    command
                )

            val second =
                repository.executeCreditRepayment(
                    command
                )

            assertTrue(
                first is CommandResult.Success
            )

            assertTrue(
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

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .count {
                        it.operationId ==
                            command.operationId.value
                    }
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )
                    .size
            )

            assertEquals(
                4_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )

            val altered =
                command.copy(
                    amountMinor = 2_001L
                )

            assertTrue(
                repository.executeCreditRepayment(
                    altered
                ) is CommandResult.Conflict
            )

            assertEquals(
                4_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )
        }

    @Test
    fun pendingDurableRepaymentRecoversExactlyOnce() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "recovery",
                    amountMinor = 5_000L,
                    occurredAt = 1_700_000_070_000L
                )

            val command =
                repayment(
                    suffix = "recovery",
                    customerId = customerId,
                    amountMinor = 1_250L,
                    occurredAt =
                        1_700_000_160_000L
                )

            database.operationDao()
                .insertOperation(
                    pending(command)
                )

            repository
                .recoverPendingCreditRepayments()

            val operation =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )

            assertEquals(
                OperationStatus.SUCCEEDED,
                operation?.status
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .count {
                        it.operationId ==
                            command.operationId.value
                    }
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )
                    .size
            )

            assertEquals(
                3_750L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )

            /*
             * A second recovery pass must not create
             * any duplicate financial evidence.
             */
            repository
                .recoverPendingCreditRepayments()

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )
                    .size
            )

            assertEquals(
                3_750L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )
        }

    @Test
    fun cashSaleRecoveryDoesNotConsumePendingCreditRepayment() =
        runBlocking {

            val customerId =
                createCreditSale(
                    suffix = "cash-isolation",
                    amountMinor = 3_000L,
                    occurredAt = 1_700_000_080_000L
                )

            val command =
                repayment(
                    suffix = "cash-isolation",
                    customerId = customerId,
                    amountMinor = 500L,
                    occurredAt =
                        1_700_000_170_000L
                )

            database.operationDao()
                .insertOperation(
                    pending(command)
                )

            repository.recoverPendingCashSales()

            val stillPending =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )

            assertEquals(
                OperationStatus.PENDING,
                stillPending?.status
            )

            assertNull(
                database.ledgerDao()
                    .getCreditRepaymentById(
                        command.repaymentId
                    )
            )

            assertTrue(
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )

            assertEquals(
                3_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        "OMR"
                    )
            )
        }

    // ==========================================================
    // BOARDLOCK CREDIT PHASE 1C-C
    // Coupon-funded Credit repayment database evidence tests.
    //
    // All tests use the existing in-memory database created in
    // setUp(). No production database is referenced.
    // ==========================================================

    private data class CouponCreditRepaymentFixture(
        val walletId: String,
        val authorizedUserId: String,
        val authorizedUserName: String
    )

    /*
     * TEST-ONLY alias registry.
     *
     * Tests keep their readable wallet/user aliases.
     * Financial commands receive only the IDs actually created by
     * the authoritative Coupon Sale command.
     */
    private val couponCreditRepaymentFixtures =
        mutableMapOf<String, CouponCreditRepaymentFixture>()

    private suspend fun createCouponForCreditRepayment(
        customerId: String,
        walletId: String,
        authorizedUserId: String,
        authorizedUserName: String,
        amountMinor: Long,
        occurredAt: Long
    ): CouponCreditRepaymentFixture {
        /*
         * BOARDLOCK TEST FIXTURE:
         *
         * Establish Coupon value only through the authoritative production
         * Coupon Sale command. The wallet, authorized-user audit, purchase
         * ledger event, settlement, transaction and parent operation are
         * therefore created under the same production invariants used by
         * the application.
         *
         * walletId / authorizedUserId remain parameters only to preserve
         * readable deterministic test operation/transaction IDs.
         * They are NOT inserted as financial evidence IDs.
         */

        val customer =
            checkNotNull(
                database.customerDao()
                    .getCustomerById(customerId)
            ) {
                "Credit customer must exist before Coupon setup"
            }

        val command =
            com.dailymoneyregister.feature.register.data.CouponSaleCommand(
                operationId =
                    OperationId(
                        "coupon-fixture-op-$walletId"
                    ),
                businessId = business.id,
                branchId = branch.id,
                actorUserId = user.id,
                transactionIdVal =
                    TransactionId(
                        "coupon-fixture-tx-$walletId"
                    ),
                customerId = customerId,
                customerName = customer.name,
                canonicalPhone = customer.canonicalPhone,
                createCustomer = false,
                amountMinor = amountMinor,
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = 3,
                fundingCategory =
                    com.dailymoneyregister.core.model.enums
                        .PaymentCategory.CASH,
                additionalUserNames = emptyList(),
                deviceId = "credit-coupon-fixture-device",
                occurredAt = occurredAt
            )

        val execution =
            repository.executeCouponSale(command)

        check(execution is CommandResult.Success) {
            "Authoritative Coupon Sale fixture failed: $execution"
        }

        val result = execution.data

        val actualWallet =
            checkNotNull(
                database.ledgerDao()
                    .getCouponWallet(
                        customerId,
                        branch.currencyCode
                    )
            ) {
                "Coupon Sale did not create/reuse a wallet"
            }

        check(actualWallet.id == result.walletId) {
            "Coupon Sale result wallet does not match authoritative wallet"
        }

        check(actualWallet.customerId == customerId) {
            "Coupon fixture wallet/customer mismatch"
        }

        check(
            database.ledgerDao()
                .getCouponBalance(actualWallet.id) ==
                amountMinor
        ) {
            "Coupon fixture balance mismatch"
        }

        val activeUsers =
            database.ledgerDao()
                .getActiveAuthorizedUsers(actualWallet.id)

        val purchaser =
            activeUsers.singleOrNull { it.isPurchaser }
                ?: error(
                    "Coupon Sale must create exactly one purchaser"
                )

        val purchaserEvents =
            database.ledgerDao()
                .getAuthorizedUserEvents(purchaser.id)

        check(
            purchaserEvents.singleOrNull()?.nameValue ==
                customer.name
        ) {
            "Coupon purchaser audit evidence mismatch"
        }

        val fixture =
            CouponCreditRepaymentFixture(
                walletId = actualWallet.id,
                authorizedUserId = purchaser.id,
                authorizedUserName = customer.name
            )

        /*
         * walletId is the caller's readable test alias.
         * Store the authoritative production-created evidence behind it.
         */
        couponCreditRepaymentFixtures[walletId] = fixture

        return fixture
    }

    private fun couponCreditRepaymentCommand(
        operationId: String,
        repaymentId: String,
        customerId: String,
        walletId: String,
        authorizedUserId: String,
        authorizedUserName: String,
        amountMinor: Long,
        occurredAt: Long
    ): CouponCreditRepaymentCommand {

        /*
         * Resolve a readable test alias to the IDs created by the
         * authoritative Coupon Sale fixture.
         *
         * If no fixture exists, preserve the supplied values. This is
         * required for payload-only/mismatch tests which intentionally
         * construct commands without creating financial evidence first.
         */
        val fixture =
            couponCreditRepaymentFixtures[walletId]

        val resolvedWalletId =
            fixture?.walletId ?: walletId

        val resolvedAuthorizedUserId =
            fixture?.authorizedUserId ?: authorizedUserId

        val resolvedAuthorizedUserName =
            authorizedUserName

        return CouponCreditRepaymentCommand(
            operationId =
                OperationId(operationId),

            businessId =
                business.id,

            branchId =
                branch.id,

            actorUserId =
                user.id,

            repaymentId =
                repaymentId,

            customerId =
                customerId,

            amountMinor =
                amountMinor,

            currencyCode =
                branch.currencyCode,

            walletId =
                resolvedWalletId,

            authorizedUserId =
                resolvedAuthorizedUserId,

            authorizedUserName =
                resolvedAuthorizedUserName,

            deviceId =
                "credit-repayment-test-device",

            occurredAt =
                occurredAt
        )
    }

    @Test
    fun couponRepaymentAtomicallyMovesCouponValueIntoCreditRepayment() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-atomic"

            val walletId =
                "credit-coupon-wallet-atomic"

            val authorizedUserId =
                "credit-coupon-user-atomic"

            /*
             * Seed debt through the already-authoritative Credit Sale path.
             * Never insert an OBLIGATION directly in this test.
             */
            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-atomic",
                transactionId =
                    "credit-sale-tx-coupon-atomic",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001001",
                customerName =
                    "Coupon Atomic Customer",
                amountMinor =
                    5000L,
                occurredAt =
                    1000L
            )

            val couponFixture =
                createCouponForCreditRepayment(
                    customerId =
                        customerId,
                    walletId =
                        walletId,
                    authorizedUserId =
                        authorizedUserId,
                    authorizedUserName =
                        "Coupon Atomic Customer",
                    amountMinor =
                        3000L,
                    occurredAt =
                        1100L
                )

            val beforeCredit =
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )

            val beforeCoupon =
                database.ledgerDao()
                    .getCouponBalance(
                        couponFixture.walletId
                    )

            assertEquals(
                5000L,
                beforeCredit
            )

            assertEquals(
                3000L,
                beforeCoupon
            )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-atomic",
                    repaymentId =
                        "credit-repay-id-coupon-atomic",
                    customerId =
                        customerId,
                    walletId =
                        walletId,
                    authorizedUserId =
                        authorizedUserId,
                    authorizedUserName =
                        "Coupon Atomic Customer",
                    amountMinor =
                        2000L,
                    occurredAt =
                        2000L
                )

            val result =
                repository.executeCouponCreditRepayment(
                    command
                )

            assertTrue(
                result is CommandResult.Success
            )

            assertEquals(
                3000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                1000L,
                database.ledgerDao()
                    .getCouponBalance(
                        couponFixture.walletId
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
                com.dailymoneyregister.core.model.enums
                    .CouponLedgerType.REDEMPTION,
                couponEvents.single().type
            )

            assertEquals(
                -2000L,
                couponEvents.single()
                    .amountSignedMinor
            )

            assertEquals(
                couponFixture.authorizedUserId,
                couponEvents.single()
                    .authorizedUserId
            )

            assertEquals(
                "Coupon Atomic Customer",
                couponEvents.single()
                    .authorizedUserNameSnapshot
            )

            val repayments =
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                1,
                repayments.size
            )

            assertEquals(
                PaymentCategory.COUPON,
                repayments.single()
                    .paymentCategory
            )

            val settlements =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                1,
                settlements.size
            )

            assertEquals(
                PaymentCategory.COUPON,
                settlements.single().category
            )

            assertEquals(
                PaymentChannel.COUPON_WALLET,
                settlements.single().channel
            )

            assertEquals(
                2000L,
                settlements.single()
                    .amountSignedMinor
            )

            val creditEvents =
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .filter {
                        it.operationId ==
                            command.operationId.value
                    }

            assertEquals(
                1,
                creditEvents.size
            )

            assertEquals(
                com.dailymoneyregister.core.model.enums
                    .CreditLedgerType.REPAYMENT,
                creditEvents.single().type
            )

            assertEquals(
                -2000L,
                creditEvents.single()
                    .amountSignedMinor
            )

            val allocations =
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )

            assertEquals(
                1,
                allocations.size
            )

            assertEquals(
                "credit-sale-tx-coupon-atomic",
                allocations.single()
                    .originalTransactionId
            )

            assertEquals(
                2000L,
                allocations.single()
                    .allocatedAmountMinor
            )
        }

    @Test
    fun couponRepaymentUsesAuthoritativeFifo() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-fifo"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-fifo-old",
                transactionId =
                    "credit-sale-tx-coupon-fifo-old",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001002",
                customerName =
                    "Coupon FIFO Customer",
                amountMinor =
                    2000L,
                occurredAt =
                    1000L
            )

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-fifo-new",
                transactionId =
                    "credit-sale-tx-coupon-fifo-new",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001002",
                customerName =
                    "Coupon FIFO Customer",
                amountMinor =
                    3000L,
                occurredAt =
                    2000L
            )

            createCouponForCreditRepayment(
                customerId =
                    customerId,
                walletId =
                    "credit-coupon-wallet-fifo",
                authorizedUserId =
                    "credit-coupon-user-fifo",
                authorizedUserName =
                    "Coupon FIFO Customer",
                amountMinor =
                    4000L,
                occurredAt =
                    2100L
            )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-fifo",
                    repaymentId =
                        "credit-repay-id-coupon-fifo",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-fifo",
                    authorizedUserId =
                        "credit-coupon-user-fifo",
                    authorizedUserName =
                        "Coupon FIFO Customer",
                    amountMinor =
                        2500L,
                    occurredAt =
                        3000L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    command
                ) is CommandResult.Success
            )

            val allocations =
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    )

            assertEquals(
                2,
                allocations.size
            )

            assertEquals(
                "credit-sale-tx-coupon-fifo-old",
                allocations[0]
                    .originalTransactionId
            )

            assertEquals(
                2000L,
                allocations[0]
                    .allocatedAmountMinor
            )

            assertEquals(
                "credit-sale-tx-coupon-fifo-new",
                allocations[1]
                    .originalTransactionId
            )

            assertEquals(
                500L,
                allocations[1]
                    .allocatedAmountMinor
            )

            assertEquals(
                2500L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                1500L,
                database.ledgerDao()
                    .getCouponBalance(
                        command.walletId
                    )
            )
        }

    @Test
    fun couponRepaymentExactPayoffProducesZeroOutstanding() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-payoff"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-payoff",
                transactionId =
                    "credit-sale-tx-coupon-payoff",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001003",
                customerName =
                    "Coupon Payoff Customer",
                amountMinor =
                    3000L,
                occurredAt =
                    1000L
            )

            createCouponForCreditRepayment(
                customerId =
                    customerId,
                walletId =
                    "credit-coupon-wallet-payoff",
                authorizedUserId =
                    "credit-coupon-user-payoff",
                authorizedUserName =
                    "Coupon Payoff Customer",
                amountMinor =
                    3000L,
                occurredAt =
                    1100L
            )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-payoff",
                    repaymentId =
                        "credit-repay-id-coupon-payoff",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-payoff",
                    authorizedUserId =
                        "credit-coupon-user-payoff",
                    authorizedUserName =
                        "Coupon Payoff Customer",
                    amountMinor =
                        3000L,
                    occurredAt =
                        2000L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    command
                ) is CommandResult.Success
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                0L,
                database.ledgerDao()
                    .getCouponBalance(
                        command.walletId
                    )
            )
        }

    @Test
    fun insufficientCouponBalanceLeavesNoRepaymentEvidence() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-insufficient"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-insufficient",
                transactionId =
                    "credit-sale-tx-coupon-insufficient",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001004",
                customerName =
                    "Coupon Insufficient Customer",
                amountMinor =
                    4000L,
                occurredAt =
                    1000L
            )

            createCouponForCreditRepayment(
                customerId =
                    customerId,
                walletId =
                    "credit-coupon-wallet-insufficient",
                authorizedUserId =
                    "credit-coupon-user-insufficient",
                authorizedUserName =
                    "Coupon Insufficient Customer",
                amountMinor =
                    1000L,
                occurredAt =
                    1100L
            )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-insufficient",
                    repaymentId =
                        "credit-repay-id-coupon-insufficient",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-insufficient",
                    authorizedUserId =
                        "credit-coupon-user-insufficient",
                    authorizedUserName =
                        "Coupon Insufficient Customer",
                    amountMinor =
                        2000L,
                    occurredAt =
                        2000L
                )

            val result =
                repository.executeCouponCreditRepayment(
                    command
                )

            assertTrue(
                result is CommandResult.Error
            )

            assertEquals(
                4000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                1000L,
                database.ledgerDao()
                    .getCouponBalance(
                        command.walletId
                    )
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )

            assertTrue(
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )

            assertTrue(
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )
        }

    @Test
    fun couponOverpaymentLeavesNoFinancialChildWrites() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-overpay"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-overpay",
                transactionId =
                    "credit-sale-tx-coupon-overpay",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001005",
                customerName =
                    "Coupon Overpay Customer",
                amountMinor =
                    1000L,
                occurredAt =
                    1000L
            )

            createCouponForCreditRepayment(
                customerId =
                    customerId,
                walletId =
                    "credit-coupon-wallet-overpay",
                authorizedUserId =
                    "credit-coupon-user-overpay",
                authorizedUserName =
                    "Coupon Overpay Customer",
                amountMinor =
                    5000L,
                occurredAt =
                    1100L
            )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-overpay",
                    repaymentId =
                        "credit-repay-id-coupon-overpay",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-overpay",
                    authorizedUserId =
                        "credit-coupon-user-overpay",
                    authorizedUserName =
                        "Coupon Overpay Customer",
                    amountMinor =
                        2000L,
                    occurredAt =
                        2000L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    command
                ) is CommandResult.Error
            )

            assertEquals(
                1000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                5000L,
                database.ledgerDao()
                    .getCouponBalance(
                        command.walletId
                    )
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )

            assertTrue(
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )
        }

    @Test
    fun couponWalletMustBelongToRepaymentCustomer() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-wallet-owner"

            val otherCustomerId =
                "credit-coupon-customer-wallet-other"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-wallet-owner",
                transactionId =
                    "credit-sale-tx-wallet-owner",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001006",
                customerName =
                    "Wallet Owner",
                amountMinor =
                    2000L,
                occurredAt =
                    1000L
            )

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-wallet-other",
                transactionId =
                    "credit-sale-tx-wallet-other",
                customerId =
                    otherCustomerId,
                customerPhone =
                    "+96890001007",
                customerName =
                    "Other Wallet Customer",
                amountMinor =
                    1000L,
                occurredAt =
                    1100L
            )

            val otherCouponFixture =
                createCouponForCreditRepayment(
                    customerId =
                        otherCustomerId,
                    walletId =
                        "credit-coupon-wallet-other",
                    authorizedUserId =
                        "credit-coupon-user-other",
                    authorizedUserName =
                        "Other Wallet Customer",
                    amountMinor =
                        3000L,
                    occurredAt =
                        1200L
                )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-wrong-wallet",
                    repaymentId =
                        "credit-repay-id-wrong-wallet",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-other",
                    authorizedUserId =
                        "credit-coupon-user-other",
                    authorizedUserName =
                        "Other Wallet Customer",
                    amountMinor =
                        1000L,
                    occurredAt =
                        2000L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    command
                ) is CommandResult.Error
            )

            assertEquals(
                2000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                3000L,
                database.ledgerDao()
                    .getCouponBalance(
                        otherCouponFixture.walletId
                    )
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .isEmpty()
            )
        }

    @Test
    fun changedAuthorizedUserSnapshotFailsWithoutDebit() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-auth-change"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-auth-change",
                transactionId =
                    "credit-sale-tx-auth-change",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001008",
                customerName =
                    "Authorization Customer",
                amountMinor =
                    2000L,
                occurredAt =
                    1000L
            )

            createCouponForCreditRepayment(
                customerId =
                    customerId,
                walletId =
                    "credit-coupon-wallet-auth-change",
                authorizedUserId =
                    "credit-coupon-user-auth-change",
                authorizedUserName =
                    "Correct Authorized Name",
                amountMinor =
                    2000L,
                occurredAt =
                    1100L
            )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-auth-change",
                    repaymentId =
                        "credit-repay-id-auth-change",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-auth-change",
                    authorizedUserId =
                        "credit-coupon-user-auth-change",
                    authorizedUserName =
                        "Changed Authorized Name",
                    amountMinor =
                        1000L,
                    occurredAt =
                        2000L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    command
                ) is CommandResult.Error
            )

            assertEquals(
                2000L,
                database.ledgerDao()
                    .getCouponBalance(
                        command.walletId
                    )
            )

            assertEquals(
                2000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
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

    @Test
    fun identicalCouponRepaymentReplayDoesNotDoubleDebit() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-replay"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-replay",
                transactionId =
                    "credit-sale-tx-coupon-replay",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001009",
                customerName =
                    "Coupon Replay Customer",
                amountMinor =
                    4000L,
                occurredAt =
                    1000L
            )

            createCouponForCreditRepayment(
                customerId =
                    customerId,
                walletId =
                    "credit-coupon-wallet-replay",
                authorizedUserId =
                    "credit-coupon-user-replay",
                authorizedUserName =
                    "Coupon Replay Customer",
                amountMinor =
                    4000L,
                occurredAt =
                    1100L
            )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-replay",
                    repaymentId =
                        "credit-repay-id-coupon-replay",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-replay",
                    authorizedUserId =
                        "credit-coupon-user-replay",
                    authorizedUserName =
                        "Coupon Replay Customer",
                    amountMinor =
                        1500L,
                    occurredAt =
                        2000L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    command
                ) is CommandResult.Success
            )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    command
                ) is CommandResult.Success
            )

            assertEquals(
                2500L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                2500L,
                database.ledgerDao()
                    .getCouponBalance(
                        command.walletId
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
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    )
                    .size
            )
        }

    @Test
    fun changedCouponRepaymentIntentConflictsWithoutSecondDebit() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-conflict"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-conflict",
                transactionId =
                    "credit-sale-tx-coupon-conflict",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001010",
                customerName =
                    "Coupon Conflict Customer",
                amountMinor =
                    4000L,
                occurredAt =
                    1000L
            )

            createCouponForCreditRepayment(
                customerId =
                    customerId,
                walletId =
                    "credit-coupon-wallet-conflict",
                authorizedUserId =
                    "credit-coupon-user-conflict",
                authorizedUserName =
                    "Coupon Conflict Customer",
                amountMinor =
                    4000L,
                occurredAt =
                    1100L
            )

            val first =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-conflict",
                    repaymentId =
                        "credit-repay-id-coupon-conflict",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-conflict",
                    authorizedUserId =
                        "credit-coupon-user-conflict",
                    authorizedUserName =
                        "Coupon Conflict Customer",
                    amountMinor =
                        1000L,
                    occurredAt =
                        2000L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    first
                ) is CommandResult.Success
            )

            val changed =
                first.copy(
                    amountMinor = 1500L
                )

            assertTrue(
                repository.executeCouponCreditRepayment(
                    changed
                ) is CommandResult.Conflict
            )

            assertEquals(
                3000L,
                database.ledgerDao()
                    .getCouponBalance(
                        first.walletId
                    )
            )

            assertEquals(
                3000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        first.operationId.value
                    )
                    .size
            )
        }

    @Test
    fun couponCreditRepaymentPayloadRoundTripsExactly() =
        runBlocking {

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-codec",
                    repaymentId =
                        "credit-repay-id-coupon-codec",
                    customerId =
                        "credit-coupon-customer-codec",
                    walletId =
                        "credit-coupon-wallet-codec",
                    authorizedUserId =
                        "credit-coupon-user-codec",
                    authorizedUserName =
                        "Codec User",
                    amountMinor =
                        1234L,
                    occurredAt =
                        9999L
                )

            val operation =
                com.dailymoneyregister.core.database.entity.OperationEntity(
                    id =
                        command.operationId.value,
                    businessId =
                        command.businessId,
                    branchId =
                        command.branchId,
                    actorUserId =
                        command.actorUserId,
                    deviceId =
                        command.deviceId,
                    operationType =
                        command.operationType,
                    subjectType =
                        command.subjectType,
                    subjectId =
                        command.subjectId,
                    transactionId =
                        command.transactionId,
                    occurredAt =
                        command.occurredAt,
                    requestFingerprint =
                        command.requestFingerprint,
                    commandSchemaVersion =
                        command.commandSchemaVersion,
                    intentPayload =
                        command.intentPayload,
                    status =
                        com.dailymoneyregister.core.model.enums
                            .OperationStatus.PENDING
                )

            assertEquals(
                command,
                CouponCreditRepaymentPayloadCodec
                    .decode(operation)
            )
        }

    // ==========================================================
    // BOARDLOCK CREDIT 1C-C FINAL RECOVERY / ROLLBACK GATE
    // ==========================================================

    @Test
    fun couponPendingRecoveryCompletesExactlyOnce() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-recovery-final"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-recovery-final",
                transactionId =
                    "credit-sale-tx-coupon-recovery-final",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001901",
                customerName =
                    "Coupon Recovery Customer",
                amountMinor =
                    5_000L,
                occurredAt =
                    10_000L
            )

            val fixture =
                createCouponForCreditRepayment(
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-recovery-final",
                    authorizedUserId =
                        "credit-coupon-user-recovery-final",
                    authorizedUserName =
                        "Coupon Recovery Customer",
                    amountMinor =
                        3_000L,
                    occurredAt =
                        11_000L
                )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-recovery-final",
                    repaymentId =
                        "credit-repay-id-coupon-recovery-final",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-recovery-final",
                    authorizedUserId =
                        "credit-coupon-user-recovery-final",
                    authorizedUserName =
                        "Coupon Recovery Customer",
                    amountMinor =
                        2_000L,
                    occurredAt =
                        12_000L
                )

            /*
             * Simulate process interruption after durable admission but
             * before any financial child materialization.
             */
            database.operationDao()
                .insertOperation(
                    pending(command)
                )

            assertEquals(
                OperationStatus.PENDING,
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )?.status
            )

            assertNull(
                database.ledgerDao()
                    .getCreditRepaymentById(
                        command.repaymentId
                    )
            )

            assertEquals(
                5_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                3_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        fixture.walletId
                    )
            )

            repository.recoverPendingCreditRepayments()

            assertEquals(
                OperationStatus.SUCCEEDED,
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )?.status
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    ).size
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    ).size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    ).size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .count {
                        it.operationId ==
                            command.operationId.value
                    }
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    ).size
            )

            assertEquals(
                3_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                1_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        fixture.walletId
                    )
            )

            /*
             * Second recovery pass must be a no-op.
             */
            repository.recoverPendingCreditRepayments()

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    ).size
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    ).size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    ).size
            )

            assertEquals(
                1,
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    ).size
            )

            assertEquals(
                3_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                1_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        fixture.walletId
                    )
            )
        }


    @Test
    fun cashSaleRecoveryDoesNotConsumePendingCouponCreditRepayment() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-recovery-isolation"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-recovery-isolation",
                transactionId =
                    "credit-sale-tx-coupon-recovery-isolation",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001902",
                customerName =
                    "Coupon Recovery Isolation",
                amountMinor =
                    4_000L,
                occurredAt =
                    20_000L
            )

            val fixture =
                createCouponForCreditRepayment(
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-recovery-isolation",
                    authorizedUserId =
                        "credit-coupon-user-recovery-isolation",
                    authorizedUserName =
                        "Coupon Recovery Isolation",
                    amountMinor =
                        2_000L,
                    occurredAt =
                        21_000L
                )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-recovery-isolation",
                    repaymentId =
                        "credit-repay-id-coupon-recovery-isolation",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-recovery-isolation",
                    authorizedUserId =
                        "credit-coupon-user-recovery-isolation",
                    authorizedUserName =
                        "Coupon Recovery Isolation",
                    amountMinor =
                        1_000L,
                    occurredAt =
                        22_000L
                )

            database.operationDao()
                .insertOperation(
                    pending(command)
                )

            /*
             * Unrelated Cash Sale recovery must not consume this command.
             */
            repository.recoverPendingCashSales()

            assertEquals(
                OperationStatus.PENDING,
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )?.status
            )

            assertNull(
                database.ledgerDao()
                    .getCreditRepaymentById(
                        command.repaymentId
                    )
            )

            assertTrue(
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    ).isEmpty()
            )

            assertEquals(
                4_000L,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                2_000L,
                database.ledgerDao()
                    .getCouponBalance(
                        fixture.walletId
                    )
            )
        }


    @Test
    fun couponRepaymentFailureAtFinalCouponWriteRollsBackAllCreditChildren() =
        runBlocking {

            val customerId =
                "credit-coupon-customer-rollback-final"

            executeCreditSaleForRepaymentTest(
                operationId =
                    "credit-sale-op-coupon-rollback-final",
                transactionId =
                    "credit-sale-tx-coupon-rollback-final",
                customerId =
                    customerId,
                customerPhone =
                    "+96890001903",
                customerName =
                    "Coupon Rollback Customer",
                amountMinor =
                    5_000L,
                occurredAt =
                    30_000L
            )

            val fixture =
                createCouponForCreditRepayment(
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-rollback-final",
                    authorizedUserId =
                        "credit-coupon-user-rollback-final",
                    authorizedUserName =
                        "Coupon Rollback Customer",
                    amountMinor =
                        3_000L,
                    occurredAt =
                        31_000L
                )

            val command =
                couponCreditRepaymentCommand(
                    operationId =
                        "credit-repay-op-coupon-rollback-final",
                    repaymentId =
                        "credit-repay-id-coupon-rollback-final",
                    customerId =
                        customerId,
                    walletId =
                        "credit-coupon-wallet-rollback-final",
                    authorizedUserId =
                        "credit-coupon-user-rollback-final",
                    authorizedUserName =
                        "Coupon Rollback Customer",
                    amountMinor =
                        2_000L,
                    occurredAt =
                        32_000L
                )

            val creditBefore =
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )

            val couponBefore =
                database.ledgerDao()
                    .getCouponBalance(
                        fixture.walletId
                    )

            assertEquals(
                5_000L,
                creditBefore
            )

            assertEquals(
                3_000L,
                couponBefore
            )

            /*
             * TEST-ONLY fault injection.
             *
             * Production writes Coupon REDEMPTION LAST, after:
             *   repayment row
             *   collection settlement
             *   Credit REPAYMENT ledger event
             *   FIFO allocations
             *
             * Aborting this final insert therefore proves that the outer
             * CommandExecutor Room transaction rolls ALL preceding child
             * writes back.
             */
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER boardlock_fail_coupon_credit_repayment
                BEFORE INSERT ON coupon_ledger_events
                WHEN NEW.operation_id =
                    'credit-repay-op-coupon-rollback-final'
                BEGIN
                    SELECT RAISE(
                        ABORT,
                        'BOARDLOCK_TEST_FORCED_COUPON_WRITE_FAILURE'
                    );
                END
                """.trimIndent()
            )

            val result =
                repository.executeCouponCreditRepayment(
                    command
                )

            assertTrue(
                result.toString(),
                result is CommandResult.Error
            )

            /*
             * Parent may be FAILED after a genuine execution exception,
             * but NO financial child is allowed to survive.
             */
            assertEquals(
                OperationStatus.FAILED,
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )?.status
            )

            assertNull(
                database.ledgerDao()
                    .getCreditRepaymentById(
                        command.repaymentId
                    )
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(
                        command.operationId.value
                    ).isEmpty()
            )

            assertTrue(
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    ).isEmpty()
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .none {
                        it.operationId ==
                            command.operationId.value
                    }
            )

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentAllocations(
                        command.repaymentId
                    ).isEmpty()
            )

            assertTrue(
                database.ledgerDao()
                    .getCouponEventsForOperation(
                        command.operationId.value
                    ).isEmpty()
            )

            assertEquals(
                creditBefore,
                database.ledgerDao()
                    .getCreditBalance(
                        customerId,
                        branch.currencyCode
                    )
            )

            assertEquals(
                couponBefore,
                database.ledgerDao()
                    .getCouponBalance(
                        fixture.walletId
                    )
            )

            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER IF EXISTS boardlock_fail_coupon_credit_repayment"
            )
        }



    @Test
    fun partialRepaymentsPreserveFifoAcrossMultipleAllocations() =
        runBlocking {
            val customerId =
                createCreditSale(
                    suffix = "multi-partial-old",
                    amountMinor = 3_000L,
                    occurredAt = 1_700_001_000_000L
                )

            createCreditSale(
                suffix = "multi-partial-new",
                amountMinor = 4_000L,
                occurredAt = 1_700_001_001_000L
            )

            val first =
                repayment(
                    suffix = "multi-partial-first",
                    customerId = customerId,
                    amountMinor = 1_500L,
                    occurredAt = 1_700_001_100_000L
                )
            val firstResult = repository.executeCreditRepayment(first)
            assertTrue(firstResult.toString(), firstResult is CommandResult.Success)

            val second =
                repayment(
                    suffix = "multi-partial-second",
                    customerId = customerId,
                    amountMinor = 3_000L,
                    occurredAt = 1_700_001_200_000L
                )
            val secondResult = repository.executeCreditRepayment(second)
            assertTrue(secondResult.toString(), secondResult is CommandResult.Success)

            val firstAllocations =
                database.ledgerDao()
                    .getCreditRepaymentAllocations(first.repaymentId)
            assertEquals(1, firstAllocations.size)
            assertEquals("credit-sale-tx-multi-partial-old", firstAllocations.single().originalTransactionId)
            assertEquals(1_500L, firstAllocations.single().allocatedAmountMinor)

            val secondAllocations =
                database.ledgerDao()
                    .getCreditRepaymentAllocations(second.repaymentId)
            assertEquals(2, secondAllocations.size)
            assertEquals("credit-sale-tx-multi-partial-old", secondAllocations[0].originalTransactionId)
            assertEquals(1_500L, secondAllocations[0].allocatedAmountMinor)
            assertEquals("credit-sale-tx-multi-partial-new", secondAllocations[1].originalTransactionId)
            assertEquals(1_500L, secondAllocations[1].allocatedAmountMinor)

            assertEquals(
                2_500L,
                database.ledgerDao().getCreditBalance(customerId, branch.currencyCode)
            )
            assertEquals(
                2,
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(first.operationId.value)
                    .size
            )
            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(first.operationId.value)
                    .size
            )
        }

    @Test
    fun creditReturnsAndAmountCorrectionsReconstructOutstandingBeforeRepayment() =
        runBlocking {
            val customerId =
                createCreditSale(
                    suffix = "adjusted-obligation",
                    amountMinor = 5_000L,
                    occurredAt = 1_700_002_000_000L
                )
            val transactionId = "credit-sale-tx-adjusted-obligation"
            val saleOperationId = "credit-sale-op-adjusted-obligation"
            val correctionOperationId = "credit-correction-adjusted-obligation"

            database.operationDao().insertOperation(
                OperationEntity(
                    id = correctionOperationId,
                    businessId = business.id,
                    branchId = branch.id,
                    operationType = com.dailymoneyregister.core.model.enums.OperationType.RECORD_AMOUNT_CORRECTION,
                    subjectType = SubjectType.TRANSACTION,
                    subjectId = transactionId,
                    transactionId = transactionId,
                    actorUserId = user.id,
                    deviceId = "credit-test-device",
                    occurredAt = 1_700_002_003_000L,
                    requestFingerprint = "credit-correction-adjusted-obligation",
                    commandSchemaVersion = 1,
                    intentPayload = "credit-correction-adjusted-obligation",
                    status = OperationStatus.SUCCEEDED
                )
            )

            val writable = database.openHelper.writableDatabase
            writable.execSQL(
                """
                INSERT INTO credit_ledger_events (
                    id, operation_id, customer_id, type, amount_signed_minor,
                    currency_code, transaction_id, repayment_id, occurred_at
                ) VALUES (?, ?, ?, 'ADJUSTMENT', ?, ?, ?, NULL, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "credit-return-adjustment-adjusted-obligation",
                    saleOperationId,
                    customerId,
                    -1_000L,
                    branch.currencyCode,
                    transactionId,
                    1_700_002_001_000L
                )
            )
            writable.execSQL(
                """
                INSERT INTO credit_ledger_events (
                    id, operation_id, customer_id, type, amount_signed_minor,
                    currency_code, transaction_id, repayment_id, occurred_at
                ) VALUES (?, ?, ?, 'ADJUSTMENT', ?, ?, ?, NULL, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "credit-negative-correction-adjusted-obligation",
                    saleOperationId,
                    customerId,
                    -500L,
                    branch.currencyCode,
                    transactionId,
                    1_700_002_002_000L
                )
            )
            writable.execSQL(
                """
                INSERT INTO credit_ledger_events (
                    id, operation_id, customer_id, type, amount_signed_minor,
                    currency_code, transaction_id, repayment_id, occurred_at
                ) VALUES (?, ?, ?, 'ADJUSTMENT', ?, ?, ?, NULL, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "credit-positive-correction-adjusted-obligation",
                    correctionOperationId,
                    customerId,
                    700L,
                    branch.currencyCode,
                    transactionId,
                    1_700_002_003_000L
                )
            )

            assertEquals(
                4_200L,
                database.ledgerDao().getCreditBalance(customerId, branch.currencyCode)
            )

            val command =
                repayment(
                    suffix = "adjusted-obligation",
                    customerId = customerId,
                    amountMinor = 4_200L,
                    occurredAt = 1_700_002_100_000L
                )
            val result = repository.executeCreditRepayment(command)
            assertTrue(result.toString(), result is CommandResult.Success)

            val allocations =
                database.ledgerDao().getCreditRepaymentAllocations(command.repaymentId)
            assertEquals(1, allocations.size)
            assertEquals(transactionId, allocations.single().originalTransactionId)
            assertEquals(4_200L, allocations.single().allocatedAmountMinor)
            assertEquals(
                0L,
                database.ledgerDao().getCreditBalance(customerId, branch.currencyCode)
            )
        }

    @Test
    fun repaymentReconstructionIsolatedByCustomerAndCurrency() =
        runBlocking {
            val customerA = "credit-repay-isolation-customer-a"
            val customerB = "credit-repay-isolation-customer-b"

            executeCreditSaleForRepaymentTest(
                operationId = "credit-sale-op-isolation-a",
                transactionId = "credit-sale-tx-isolation-a",
                customerId = customerA,
                customerPhone = "+96890002001",
                customerName = "Isolation Customer A",
                amountMinor = 5_000L,
                occurredAt = 1_700_003_000_000L
            )
            executeCreditSaleForRepaymentTest(
                operationId = "credit-sale-op-isolation-b",
                transactionId = "credit-sale-tx-isolation-b",
                customerId = customerB,
                customerPhone = "+96890002002",
                customerName = "Isolation Customer B",
                amountMinor = 6_000L,
                occurredAt = 1_700_003_001_000L
            )

            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO credit_ledger_events (
                    id, operation_id, customer_id, type, amount_signed_minor,
                    currency_code, transaction_id, repayment_id, occurred_at
                ) VALUES (?, ?, ?, 'OBLIGATION', ?, ?, NULL, NULL, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "credit-foreign-currency-obligation",
                    "credit-sale-op-isolation-a",
                    customerA,
                    9_000L,
                    "USD",
                    1_700_003_002_000L
                )
            )

            val command =
                repayment(
                    suffix = "isolation-a",
                    customerId = customerA,
                    amountMinor = 2_000L,
                    occurredAt = 1_700_003_100_000L
                )
            val result = repository.executeCreditRepayment(command)
            assertTrue(result.toString(), result is CommandResult.Success)

            assertEquals(
                3_000L,
                database.ledgerDao().getCreditBalance(customerA, branch.currencyCode)
            )
            assertEquals(
                6_000L,
                database.ledgerDao().getCreditBalance(customerB, branch.currencyCode)
            )
            assertEquals(
                listOf("credit-sale-tx-isolation-a"),
                database.ledgerDao()
                    .getCreditRepaymentAllocations(command.repaymentId)
                    .map { it.originalTransactionId }
            )
        }

    @Test
    fun malformedObligationAndNegativeLedgerBalanceAreRejectedBeforeRepaymentWrites() =
        runBlocking {
            val malformedCustomer =
                createCreditSale(
                    suffix = "malformed-obligation",
                    amountMinor = 5_000L,
                    occurredAt = 1_700_004_000_000L
                )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO credit_ledger_events (
                    id, operation_id, customer_id, type, amount_signed_minor,
                    currency_code, transaction_id, repayment_id, occurred_at
                ) VALUES (?, ?, ?, 'OBLIGATION', ?, ?, NULL, NULL, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "credit-malformed-obligation",
                    "credit-sale-op-malformed-obligation",
                    malformedCustomer,
                    1_000L,
                    branch.currencyCode,
                    1_700_004_001_000L
                )
            )

            val malformedCommand =
                repayment(
                    suffix = "malformed-obligation",
                    customerId = malformedCustomer,
                    amountMinor = 1_000L,
                    occurredAt = 1_700_004_100_000L
                )
            val malformedResult =
                repository.executeCreditRepayment(malformedCommand)
            assertTrue(malformedResult.toString(), malformedResult is CommandResult.Error)
            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(malformedCommand.operationId.value)
                    .isEmpty()
            )
            assertTrue(
                database.transactionDao()
                    .getSettlementsForOperation(malformedCommand.operationId.value)
                    .isEmpty()
            )

            val negativeCustomer =
                createCreditSale(
                    suffix = "negative-ledger",
                    amountMinor = 5_000L,
                    occurredAt = 1_700_005_000_000L
                )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO credit_ledger_events (
                    id, operation_id, customer_id, type, amount_signed_minor,
                    currency_code, transaction_id, repayment_id, occurred_at
                ) VALUES (?, ?, ?, 'ADJUSTMENT', ?, ?, NULL, NULL, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "credit-negative-ledger-adjustment",
                    "credit-sale-op-negative-ledger",
                    negativeCustomer,
                    -6_000L,
                    branch.currencyCode,
                    1_700_005_001_000L
                )
            )

            val negativeCommand =
                repayment(
                    suffix = "negative-ledger",
                    customerId = negativeCustomer,
                    amountMinor = 1_000L,
                    occurredAt = 1_700_005_100_000L
                )
            val negativeResult =
                repository.executeCreditRepayment(negativeCommand)
            assertTrue(negativeResult.toString(), negativeResult is CommandResult.Error)
            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(negativeCommand.operationId.value)
                    .isEmpty()
            )
            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentAllocations(negativeCommand.repaymentId)
                    .isEmpty()
            )
        }

    @Test
    fun repaymentAllocationOverflowIsRejectedBeforeNewRepaymentWrites() =
        runBlocking {
            val customerId =
                createCreditSale(
                    suffix = "allocation-overflow",
                    amountMinor = 5_000L,
                    occurredAt = 1_700_006_000_000L
                )

            val first =
                repayment(
                    suffix = "allocation-overflow-first",
                    customerId = customerId,
                    amountMinor = 1L,
                    occurredAt = 1_700_006_100_000L
                )
            val second =
                repayment(
                    suffix = "allocation-overflow-second",
                    customerId = customerId,
                    amountMinor = 1L,
                    occurredAt = 1_700_006_200_000L
                )
            assertTrue(repository.executeCreditRepayment(first) is CommandResult.Success)
            assertTrue(repository.executeCreditRepayment(second) is CommandResult.Success)

            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE credit_repayment_allocations
                SET allocated_amount_minor = ?
                WHERE original_transaction_id = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    Long.MAX_VALUE,
                    "credit-sale-tx-allocation-overflow"
                )
            )

            val third =
                repayment(
                    suffix = "allocation-overflow-third",
                    customerId = customerId,
                    amountMinor = 1L,
                    occurredAt = 1_700_006_300_000L
                )
            val result = repository.executeCreditRepayment(third)
            assertTrue(result.toString(), result is CommandResult.Error)

            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentsForOperation(third.operationId.value)
                    .isEmpty()
            )
            assertTrue(
                database.transactionDao()
                    .getSettlementsForOperation(third.operationId.value)
                    .isEmpty()
            )
            assertTrue(
                database.ledgerDao()
                    .getCreditRepaymentAllocations(third.repaymentId)
                    .isEmpty()
            )
        }

    @Test
    fun repaymentReplayRejectsMissingAndAlteredPersistedEvidence() =
        runBlocking {
            val scenarios = listOf(
                "missing-repayment",
                "altered-repayment",
                "missing-settlement",
                "missing-ledger",
                "missing-allocation",
                "invalid-allocation",
                "allocation-sum-mismatch"
            )

            for (scenario in scenarios) {
                val customerId =
                    createCreditSale(
                        suffix = "evidence-$scenario",
                        amountMinor = 5_000L,
                        occurredAt = 1_700_007_000_000L + scenarios.indexOf(scenario) * 10_000L
                    )
                val command =
                    repayment(
                        suffix = "evidence-$scenario",
                        customerId = customerId,
                        amountMinor = 1_000L,
                        occurredAt = 1_700_008_000_000L + scenarios.indexOf(scenario) * 10_000L
                    )

                val initial = repository.executeCreditRepayment(command)
                assertTrue("$scenario initial execution: $initial", initial is CommandResult.Success)

                val writable = database.openHelper.writableDatabase
                when (scenario) {
                    "missing-repayment" ->
                        writable.execSQL(
                            "DELETE FROM credit_repayments WHERE id = ?",
                            arrayOf(command.repaymentId)
                        )
                    "altered-repayment" ->
                        writable.execSQL(
                            "UPDATE credit_repayments SET amount_minor = ? WHERE id = ?",
                            arrayOf<Any?>(1_001L, command.repaymentId)
                        )
                    "missing-settlement" ->
                        writable.execSQL(
                            "DELETE FROM settlement_entries WHERE operation_id = ?",
                            arrayOf(command.operationId.value)
                        )
                    "missing-ledger" ->
                        writable.execSQL(
                            "DELETE FROM credit_ledger_events WHERE operation_id = ?",
                            arrayOf(command.operationId.value)
                        )
                    "missing-allocation" ->
                        writable.execSQL(
                            "DELETE FROM credit_repayment_allocations WHERE repayment_id = ?",
                            arrayOf(command.repaymentId)
                        )
                    "invalid-allocation" ->
                        writable.execSQL(
                            "UPDATE credit_repayment_allocations SET allocated_amount_minor = 0 WHERE repayment_id = ?",
                            arrayOf(command.repaymentId)
                        )
                    "allocation-sum-mismatch" ->
                        writable.execSQL(
                            "UPDATE credit_repayment_allocations SET allocated_amount_minor = 1001 WHERE repayment_id = ?",
                            arrayOf(command.repaymentId)
                        )
                }

                val replay = repository.executeCreditRepayment(command)
                assertTrue("$scenario replay must fail closed: $replay", replay is CommandResult.Error)

                val repaymentRows =
                    database.ledgerDao()
                        .getCreditRepaymentsForOperation(command.operationId.value)
                assertEquals(
                    if (scenario == "missing-repayment") 0 else 1,
                    repaymentRows.size
                )
                assertEquals(
                    if (scenario == "missing-settlement") 0 else 1,
                    database.transactionDao()
                        .getSettlementsForOperation(command.operationId.value)
                        .size
                )
                assertEquals(
                    if (scenario == "missing-ledger") 0 else 1,
                    database.ledgerDao()
                        .getCreditLedgerForCustomer(customerId)
                        .count { it.operationId == command.operationId.value }
                )
                assertEquals(
                    if (scenario == "missing-repayment" || scenario == "missing-allocation") 0 else 1,
                    database.ledgerDao()
                        .getCreditRepaymentAllocations(command.repaymentId)
                        .size
                )
            }
        }

    @Test
    fun repaymentReplayRejectsUnexpectedAdditionalEvidenceRows() =
        runBlocking {
            val scenarios = listOf(
                "extra-repayment",
                "extra-settlement",
                "extra-ledger-event",
                "extra-allocation"
            )

            for (scenario in scenarios) {
                val customerId =
                    createCreditSale(
                        suffix = scenario,
                        amountMinor = 5_000L,
                        occurredAt = 1_700_009_000_000L + scenarios.indexOf(scenario) * 10_000L
                    )
                val command =
                    repayment(
                        suffix = scenario,
                        customerId = customerId,
                        amountMinor = 1_000L,
                        occurredAt = 1_700_010_000_000L + scenarios.indexOf(scenario) * 10_000L
                    )
                val initial = repository.executeCreditRepayment(command)
                assertTrue("$scenario initial execution: $initial", initial is CommandResult.Success)

                val writable = database.openHelper.writableDatabase
                when (scenario) {
                    "extra-repayment" ->
                        writable.execSQL(
                            """
                            INSERT INTO credit_repayments (
                                id, operation_id, customer_id, amount_minor,
                                currency_code, payment_category, occurred_at, actor_user_id
                            )
                            SELECT 'extra-' || id, operation_id, customer_id, amount_minor,
                                   currency_code, payment_category, occurred_at, actor_user_id
                            FROM credit_repayments WHERE id = ?
                            """.trimIndent(),
                            arrayOf(command.repaymentId)
                        )
                    "extra-settlement" ->
                        writable.execSQL(
                            """
                            INSERT INTO settlement_entries (
                                id, operation_id, subject_type, subject_id, transaction_id,
                                entry_purpose, category, channel, amount_signed_minor,
                                currency_code, confirmation_type, occurred_at, actor_user_id
                            )
                            SELECT 'extra-' || id, operation_id, subject_type, subject_id, transaction_id,
                                   entry_purpose, category, channel, amount_signed_minor,
                                   currency_code, confirmation_type, occurred_at, actor_user_id
                            FROM settlement_entries WHERE operation_id = ?
                            """.trimIndent(),
                            arrayOf(command.operationId.value)
                        )
                    "extra-ledger-event" ->
                        writable.execSQL(
                            """
                            INSERT INTO credit_ledger_events (
                                id, operation_id, customer_id, type, amount_signed_minor,
                                currency_code, transaction_id, repayment_id, occurred_at
                            )
                            SELECT 'extra-' || id, operation_id, customer_id, type, amount_signed_minor,
                                   currency_code, transaction_id, repayment_id, occurred_at
                            FROM credit_ledger_events WHERE operation_id = ?
                            """.trimIndent(),
                            arrayOf(command.operationId.value)
                        )
                    "extra-allocation" ->
                        writable.execSQL(
                            """
                            INSERT INTO credit_repayment_allocations (
                                id, repayment_id, original_transaction_id,
                                allocated_amount_minor, occurred_at
                            )
                            SELECT 'extra-' || id, repayment_id, original_transaction_id,
                                   allocated_amount_minor, occurred_at
                            FROM credit_repayment_allocations WHERE repayment_id = ?
                            """.trimIndent(),
                            arrayOf(command.repaymentId)
                        )
                }

                val replay = repository.executeCreditRepayment(command)
                assertTrue("$scenario replay must fail closed: $replay", replay is CommandResult.Error)
                assertEquals(
                    if (scenario == "extra-repayment") 2 else 1,
                    database.ledgerDao()
                        .getCreditRepaymentsForOperation(command.operationId.value)
                        .size
                )
                assertEquals(
                    if (scenario == "extra-settlement") 2 else 1,
                    database.transactionDao()
                        .getSettlementsForOperation(command.operationId.value)
                        .size
                )
                assertEquals(
                    if (scenario == "extra-ledger-event") 2 else 1,
                    database.ledgerDao()
                        .getCreditLedgerForCustomer(customerId)
                        .count { it.operationId == command.operationId.value }
                )
                assertEquals(
                    if (scenario == "extra-allocation") 2 else 1,
                    database.ledgerDao()
                        .getCreditRepaymentAllocations(command.repaymentId)
                        .size
                )
            }
        }

}
