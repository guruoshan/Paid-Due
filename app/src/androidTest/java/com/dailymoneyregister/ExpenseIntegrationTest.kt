package com.dailymoneyregister

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
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.ExpenseCommandResult
import com.dailymoneyregister.feature.register.data.ExpenseLifecycleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseIntegrationTest {

    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository

    private val business =
        BusinessEntity(
            "expense-test-business",
            "Expense Test Business",
            0L,
            0L
        )

    private val branch =
        BranchEntity(
            id = "expense-test-branch",
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
            id = "expense-test-user",
            businessId = business.id,
            displayName = "Expense Cashier",
            username = "expense_test_cashier",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val membership =
        UserBranchMembershipEntity(
            id = "expense-test-membership",
            userId = user.id,
            branchId = branch.id,
            role = UserRole.CASHIER,
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

    private fun command(
        suffix: String,
        amountMinor: Long = 5_250L,
        category: ExpenseCategory =
            ExpenseCategory.SUPPLIES,
        paymentCategory: PaymentCategory =
            PaymentCategory.CASH,
        paymentChannel: PaymentChannel =
            if (paymentCategory == PaymentCategory.CASH) {
                PaymentChannel.CASH_TENDER
            } else {
                PaymentChannel.MANUAL_QR
            },
        reference: String? = "REF-$suffix",
        note: String? = "Expense note $suffix",
        occurredAt: Long = 1_700_000_000_000L,
        payeeName: String = "Test Supplier",
        payeePhone: String? = "+96890000000"
    ): ExpenseCommand =
        ExpenseCommand(
            operationId =
                OperationId("expense-op-$suffix"),
            businessId =
                business.id,
            branchId =
                branch.id,
            actorUserId =
                user.id,
            expenseId =
                "expense-$suffix",
            amountMinor =
                amountMinor,
            currencyCode =
                branch.currencyCode,
            category =
                category,
            paymentCategory =
                paymentCategory,
            paymentChannel =
                paymentChannel,
            payeeId =
                null,
            payeeNameSnapshot =
                payeeName,
            payeePhoneSnapshot =
                payeePhone,
            payeeAccountSnapshot =
                "SUPPLIER-ACCOUNT",
            reference =
                reference,
            note =
                note,
            recurrenceId =
                null,
            deviceId =
                "expense-test-device",
            occurredAt =
                occurredAt
        )

    private fun success(
        result: CommandResult<ExpenseCommandResult>
    ): CommandResult.Success<ExpenseCommandResult> {

        assertTrue(
            "Expected Expense command success but was $result",
            result is CommandResult.Success
        )

        @Suppress("UNCHECKED_CAST")
        return result as
            CommandResult.Success<ExpenseCommandResult>
    }

    @Test
    fun cashExpensePersistsAuthoritativeExpenseAndNegativeOutflow() =
        runBlocking {

            val command =
                command(
                    suffix = "cash",
                    amountMinor = 7_500L,
                    category = ExpenseCategory.SUPPLIES
                )

            val result =
                success(
                    repository.executeExpense(command)
                )

            assertFalse(result.isIdempotentReplay)

            val expense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        command.operationId.value
                    )

            assertNotNull(expense)

            expense!!

            assertEquals(
                command.expenseId,
                expense.id
            )

            assertEquals(
                command.businessId,
                expense.businessId
            )

            assertEquals(
                command.branchId,
                expense.branchId
            )

            assertEquals(
                command.actorUserId,
                expense.actorUserId
            )

            assertEquals(
                command.amountMinor,
                expense.amountMinor
            )

            assertEquals(
                command.category.name,
                expense.category
            )

            assertEquals(
                PaymentCategory.CASH.name,
                expense.paymentCategory
            )

            assertEquals(
                command.reference,
                expense.reference
            )

            assertEquals(
                command.note,
                expense.note
            )

            assertEquals(
                command.occurredAt,
                expense.occurredAt
            )

            assertEquals(
                command.occurredAt,
                expense.createdAt
            )

            assertNotNull(
                "Expense must be attributed to an authoritative cashier shift.",
                expense.cashShiftId
            )

            val settlements =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertEquals(1, settlements.size)

            val settlement =
                settlements.single()

            assertEquals(
                "${command.operationId.value}:expense-outflow",
                settlement.id
            )

            assertEquals(
                SubjectType.EXPENSE,
                settlement.subjectType
            )

            assertEquals(
                command.expenseId,
                settlement.subjectId
            )

            assertNull(settlement.transactionId)

            assertEquals(
                EntryPurpose.EXPENSE_OUTFLOW,
                settlement.entryPurpose
            )

            assertEquals(
                PaymentCategory.CASH,
                settlement.category
            )

            assertEquals(
                PaymentChannel.CASH_TENDER,
                settlement.channel
            )

            assertEquals(
                -7_500L,
                settlement.amountSignedMinor
            )

            assertEquals(
                command.actorUserId,
                settlement.actorUserId
            )

            assertEquals(
                command.occurredAt,
                settlement.occurredAt
            )

            val operation =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )

            assertNotNull(operation)

            assertEquals(
                OperationType.RECORD_EXPENSE,
                operation!!.operationType
            )

            assertEquals(
                SubjectType.EXPENSE,
                operation.subjectType
            )

            assertEquals(
                OperationStatus.SUCCEEDED,
                operation.status
            )

            assertNull(
                "Expense must not manufacture a sale Transaction ID.",
                operation.transactionId
            )
        }

    @Test
    fun onlineExpensePersistsOnlineOutflowWithoutCashClassification() =
        runBlocking {

            val command =
                command(
                    suffix = "online",
                    amountMinor = 12_345L,
                    category = ExpenseCategory.UTILITIES,
                    paymentCategory =
                        PaymentCategory.ONLINE,
                    paymentChannel =
                        PaymentChannel.MANUAL_QR
                )

            success(
                repository.executeExpense(command)
            )

            val expense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        command.operationId.value
                    )

            assertNotNull(expense)

            assertEquals(
                PaymentCategory.ONLINE.name,
                expense!!.paymentCategory
            )

            val settlement =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .single()

            assertEquals(
                PaymentCategory.ONLINE,
                settlement.category
            )

            assertEquals(
                PaymentChannel.MANUAL_QR,
                settlement.channel
            )

            assertNotEquals(
                PaymentCategory.CASH,
                settlement.category
            )

            assertEquals(
                -12_345L,
                settlement.amountSignedMinor
            )
        }

    @Test
    fun expensePreservesCategoryNoteReferencePayeeAndOccurredAt() =
        runBlocking {

            val occurredAt =
                1_711_111_111_111L

            val command =
                command(
                    suffix = "evidence",
                    amountMinor = 9_999L,
                    category =
                        ExpenseCategory.MAINTENANCE,
                    reference =
                        "MAINT-REF-42",
                    note =
                        "Repair refrigeration unit",
                    occurredAt =
                        occurredAt
                )

            success(
                repository.executeExpense(command)
            )

            val expense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        command.operationId.value
                    )!!

            assertEquals(
                ExpenseCategory.MAINTENANCE.name,
                expense.category
            )

            assertEquals(
                "MAINT-REF-42",
                expense.reference
            )

            assertEquals(
                "Repair refrigeration unit",
                expense.note
            )

            assertEquals(
                "Test Supplier",
                expense.payeeNameSnapshot
            )

            assertEquals(
                "+96890000000",
                expense.payeePhoneSnapshot
            )

            assertEquals(
                "SUPPLIER-ACCOUNT",
                expense.payeeAccountSnapshot
            )

            assertEquals(
                occurredAt,
                expense.occurredAt
            )
        }

    @Test
    fun expenseUsesAuthenticatedCashierAndCurrentShift() =
        runBlocking {

            val command =
                command("shift")

            val result =
                success(
                    repository.executeExpense(command)
                )

            val expense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        command.operationId.value
                    )!!

            assertEquals(
                user.id,
                expense.actorUserId
            )

            assertNotNull(expense.cashShiftId)

            assertEquals(
                expense.cashShiftId,
                result.data.cashShiftId
            )

            val operation =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )!!

            assertEquals(
                expense.cashShiftId,
                operation.cashShiftId
            )
        }

    @Test
    fun sameExpenseCommandReplaysWithoutDuplicateFinancialEvidence() =
        runBlocking {

            val command =
                command("replay")

            val first =
                success(
                    repository.executeExpense(command)
                )

            val second =
                success(
                    repository.executeExpense(command)
                )

            assertFalse(first.isIdempotentReplay)
            assertTrue(second.isIdempotentReplay)

            assertEquals(
                first.data,
                second.data
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .size
            )

            val expenses =
                database.expenseDao()
                    .getExpensesForPeriod(
                        business.id,
                        branch.id,
                        command.occurredAt,
                        command.occurredAt + 1L
                    )

            assertEquals(
                1,
                expenses.count {
                    it.operationId ==
                        command.operationId.value
                }
            )
        }

    @Test
    fun reusedOperationIdWithChangedExpenseRequestConflicts() =
        runBlocking {

            val original =
                command(
                    suffix = "conflict",
                    amountMinor = 1_000L
                )

            val changed =
                original.copy(
                    amountMinor = 2_000L
                )

            success(
                repository.executeExpense(original)
            )

            val second =
                repository.executeExpense(changed)

            assertTrue(
                "Changed durable request must conflict: $second",
                second is CommandResult.Conflict
            )

            val persisted =
                database.expenseDao()
                    .getExpenseByOperationId(
                        original.operationId.value
                    )!!

            assertEquals(
                1_000L,
                persisted.amountMinor
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        original.operationId.value
                    )
                    .size
            )
        }

    @Test
    fun periodAndShiftQueriesDeriveFromAuthoritativeExpenseRows() =
        runBlocking {

            val first =
                command(
                    suffix = "period-a",
                    amountMinor = 1_000L,
                    category = ExpenseCategory.RENT,
                    occurredAt = 2_000L
                )

            val second =
                command(
                    suffix = "period-b",
                    amountMinor = 2_000L,
                    category = ExpenseCategory.TRANSPORT,
                    paymentCategory =
                        PaymentCategory.ONLINE,
                    paymentChannel =
                        PaymentChannel.MANUAL_QR,
                    occurredAt = 3_000L
                )

            val third =
                command(
                    suffix = "period-outside",
                    amountMinor = 4_000L,
                    occurredAt = 9_000L
                )

            val firstResult =
                success(
                    repository.executeExpense(first)
                )

            success(
                repository.executeExpense(second)
            )

            success(
                repository.executeExpense(third)
            )

            val period =
                database.expenseDao()
                    .getExpensesForPeriod(
                        business.id,
                        branch.id,
                        1_500L,
                        4_000L
                    )

            assertEquals(2, period.size)

            assertEquals(
                3_000L,
                period.sumOf {
                    it.amountMinor
                }
            )

            assertEquals(
                setOf(
                    ExpenseCategory.RENT.name,
                    ExpenseCategory.TRANSPORT.name
                ),
                period.map {
                    it.category
                }.toSet()
            )

            val shiftExpenses =
                database.expenseDao()
                    .getExpensesForShift(
                        firstResult.data.cashShiftId
                    )

            assertTrue(
                shiftExpenses.any {
                    it.id == first.expenseId
                }
            )

            assertTrue(
                shiftExpenses.any {
                    it.id == second.expenseId
                }
            )
        }

    @Test
    fun cashAndOnlineTotalsRemainDerivableWithoutDuplicateBalanceState() =
        runBlocking {

            val cash =
                command(
                    suffix = "total-cash",
                    amountMinor = 5_000L,
                    occurredAt = 20_000L
                )

            val online =
                command(
                    suffix = "total-online",
                    amountMinor = 7_000L,
                    paymentCategory =
                        PaymentCategory.ONLINE,
                    paymentChannel =
                        PaymentChannel.MANUAL_QR,
                    occurredAt = 21_000L
                )

            success(repository.executeExpense(cash))
            success(repository.executeExpense(online))

            val rows =
                database.expenseDao()
                    .getExpensesForPeriod(
                        business.id,
                        branch.id,
                        19_000L,
                        22_000L
                    )

            val cashTotal =
                rows
                    .filter {
                        it.paymentCategory ==
                            PaymentCategory.CASH.name
                    }
                    .sumOf {
                        it.amountMinor
                    }

            val onlineTotal =
                rows
                    .filter {
                        it.paymentCategory ==
                            PaymentCategory.ONLINE.name
                    }
                    .sumOf {
                        it.amountMinor
                    }

            assertEquals(5_000L, cashTotal)
            assertEquals(7_000L, onlineTotal)
            assertEquals(12_000L, cashTotal + onlineTotal)
        }

    @Test
    fun expenseIsDistinctFromSaleFinancialSemantics() =
        runBlocking {

            val command =
                command(
                    suffix = "not-sale",
                    amountMinor = 3_333L
                )

            success(
                repository.executeExpense(command)
            )

            val operation =
                database.operationDao()
                    .getOperationById(
                        command.operationId.value
                    )!!

            assertEquals(
                OperationType.RECORD_EXPENSE,
                operation.operationType
            )


            assertEquals(
                SubjectType.EXPENSE,
                operation.subjectType
            )

            assertNull(operation.transactionId)

            val settlement =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .single()

            assertEquals(
                EntryPurpose.EXPENSE_OUTFLOW,
                settlement.entryPurpose
            )

            assertNull(
                "Expense outflow must not point at a sale transaction.",
                settlement.transactionId
            )
        }

    @Test
    fun unauthenticatedActorCannotRecordExpense() =
        runBlocking {

            val forged =
                command("forged")
                    .copy(
                        actorUserId =
                            "different-user"
                    )

            val result =
                repository.executeExpense(forged)

            assertFalse(
                "Forged actor must not succeed: $result",
                result is CommandResult.Success
            )

            assertNull(
                database.expenseDao()
                    .getExpenseByOperationId(
                        forged.operationId.value
                    )
            )

            assertTrue(
                database.transactionDao()
                    .getSettlementsForOperation(
                        forged.operationId.value
                    )
                    .isEmpty()
            )
        }

    // ===============================================================
    // BOARDLOCK — EXPENSE UNIVERSAL CUSTOMER ID INTEGRATION
    // ===============================================================

    @Test
    fun expenseWithNewPhoneCreatesUniversalCustomerAndLinksExpense() =
        runBlocking {

            val command =
                command(
                    suffix = "identity-new",
                    payeeName = "Sara Supplier",
                    payeePhone = "+968 9123 4567"
                )

            success(
                repository.executeExpense(command)
            )

            val customers =
                database.customerDao()
                    .getCustomersForBusiness(business.id)

            assertEquals(
                "A new Expense phone must create exactly one universal customer.",
                1,
                customers.size
            )

            val customer =
                customers.single()

            assertEquals(
                "+96891234567",
                customer.canonicalPhone
            )

            assertEquals(
                "Sara Supplier",
                customer.name
            )

            val expense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        command.operationId.value
                    )!!

            assertEquals(
                "Expense must link to the universal CustomerEntity ID.",
                customer.id,
                expense.payeeId
            )

            assertEquals(
                "Historical Expense name snapshot must remain preserved.",
                "Sara Supplier",
                expense.payeeNameSnapshot
            )

            assertEquals(
                "Expense phone snapshot must be canonicalized.",
                "+96891234567",
                expense.payeePhoneSnapshot
            )
        }

    @Test
    fun twoExpensesWithSamePhoneReuseExactlyOneUniversalCustomerId() =
        runBlocking {

            val first =
                command(
                    suffix = "identity-reuse-a",
                    payeeName = "Sara Supplier",
                    payeePhone = "+96891234567",
                    occurredAt = 1_700_000_001_000L
                )

            val second =
                command(
                    suffix = "identity-reuse-b",
                    payeeName = "Sara Supplier",
                    payeePhone = "+968-9123-4567",
                    occurredAt = 1_700_000_002_000L
                )

            success(repository.executeExpense(first))
            success(repository.executeExpense(second))

            val customers =
                database.customerDao()
                    .getCustomersForBusiness(business.id)

            assertEquals(
                "Formatting variants of one phone must not create duplicate customers.",
                1,
                customers.size
            )

            val firstExpense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        first.operationId.value
                    )!!

            val secondExpense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        second.operationId.value
                    )!!

            assertNotNull(firstExpense.payeeId)

            assertEquals(
                firstExpense.payeeId,
                secondExpense.payeeId
            )

            assertEquals(
                customers.single().id,
                firstExpense.payeeId
            )
        }

    @Test
    fun expenseDoesNotRenameExistingUniversalCustomer() =
        runBlocking {

            val first =
                command(
                    suffix = "identity-name-a",
                    payeeName = "Original Supplier",
                    payeePhone = "+96897654321",
                    occurredAt = 1_700_000_003_000L
                )

            success(
                repository.executeExpense(first)
            )

            val customerBefore =
                database.customerDao()
                    .getCustomerByPhone(
                        business.id,
                        "+96897654321"
                    )!!

            val second =
                command(
                    suffix = "identity-name-b",
                    payeeName = "Different Typed Name",
                    payeePhone = "+968 9765 4321",
                    occurredAt = 1_700_000_004_000L
                )

            success(
                repository.executeExpense(second)
            )

            val customerAfter =
                database.customerDao()
                    .getCustomerByPhone(
                        business.id,
                        "+96897654321"
                    )!!

            assertEquals(
                customerBefore.id,
                customerAfter.id
            )

            assertEquals(
                "Existing universal identity must not be silently renamed by Expense.",
                "Original Supplier",
                customerAfter.name
            )

            assertEquals(
                "Existing customer updatedAt must not be rewritten by an Expense alias.",
                customerBefore.updatedAt,
                customerAfter.updatedAt
            )

            assertEquals(
                1,
                database.customerDao()
                    .getCustomersForBusiness(business.id)
                    .size
            )

            val secondExpense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        second.operationId.value
                    )!!

            assertEquals(
                customerBefore.id,
                secondExpense.payeeId
            )

            /*
             * Expense snapshot is historical evidence of what was entered
             * at transaction time. CustomerEntity remains authoritative for
             * the reusable identity/name.
             */
            assertEquals(
                "Different Typed Name",
                secondExpense.payeeNameSnapshot
            )

            assertEquals(
                "+96897654321",
                secondExpense.payeePhoneSnapshot
            )
        }

    @Test
    fun replayedExpenseDoesNotDuplicateUniversalCustomer() =
        runBlocking {

            val command =
                command(
                    suffix = "identity-replay",
                    payeeName = "Replay Supplier",
                    payeePhone = "+96895550000",
                    occurredAt = 1_700_000_005_000L
                )

            val first =
                success(
                    repository.executeExpense(command)
                )

            val second =
                success(
                    repository.executeExpense(command)
                )

            assertFalse(first.isIdempotentReplay)
            assertTrue(second.isIdempotentReplay)

            val customers =
                database.customerDao()
                    .getCustomersForBusiness(business.id)

            assertEquals(
                "Idempotent Expense replay must not duplicate CustomerEntity.",
                1,
                customers.size
            )

            val expenses =
                database.expenseDao()
                    .getExpensesForPeriod(
                        business.id,
                        branch.id,
                        command.occurredAt,
                        command.occurredAt + 1L
                    )

            assertEquals(
                1,
                expenses.count {
                    it.operationId ==
                        command.operationId.value
                }
            )

            assertEquals(
                customers.single().id,
                expenses.single {
                    it.operationId ==
                        command.operationId.value
                }.payeeId
            )

            assertEquals(
                1,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
                    .size
            )
        }

    @Test
    fun failedExpenseMaterializationRollsBackNewUniversalCustomer() =
        runBlocking {

            /*
             * Seed an Expense using this Expense ID.
             */
            val seed =
                command(
                    suffix = "identity-atomic-seed",
                    payeeName = "Existing Supplier",
                    payeePhone = "+96893330000",
                    occurredAt = 1_700_000_006_000L
                )

            success(
                repository.executeExpense(seed)
            )

            val customerCountBefore =
                database.customerDao()
                    .getCustomersForBusiness(business.id)
                    .size

            /*
             * Different operation + new phone, but deliberately reuse the
             * already-persisted Expense ID.
             *
             * CustomerIdentity.resolveOrCreate() executes before the Expense
             * insert. The Expense insert must fail. Because both occur inside
             * CommandExecutor's Room transaction, the newly-created customer
             * must roll back with the failed financial materialization.
             */
            val failing =
                command(
                    suffix = "identity-atomic-fail",
                    payeeName = "Must Roll Back",
                    payeePhone = "+96894440000",
                    occurredAt = 1_700_000_007_000L
                ).copy(
                    expenseId = seed.expenseId
                )

            val result =
                repository.executeExpense(failing)

            assertFalse(
                "Duplicate Expense ID must not succeed: $result",
                result is CommandResult.Success
            )

            assertNull(
                "Customer created only for failed Expense must roll back.",
                database.customerDao()
                    .getCustomerByPhone(
                        business.id,
                        "+96894440000"
                    )
            )

            assertEquals(
                "Failed Expense must not leave an extra universal customer.",
                customerCountBefore,
                database.customerDao()
                    .getCustomersForBusiness(business.id)
                    .size
            )

            assertEquals(
                "Failed operation must not leave settlement evidence.",
                0,
                database.transactionDao()
                    .getSettlementsForOperation(
                        failing.operationId.value
                    )
                    .size
            )

            val originalExpense =
                database.expenseDao()
                    .getExpenseByOperationId(
                        seed.operationId.value
                    )!!

            assertEquals(
                seed.expenseId,
                originalExpense.id
            )
        }

    @Test
    fun adminExpenseCorrectionAndVoidAppendEventsAndQueueExactlyOnce() = runBlocking {
        val original = command(suffix = "admin-lifecycle", amountMinor = 9_000L)
        assertTrue(repository.executeExpense(original) is CommandResult.Success)

        val admin = user.copy(
            id = "expense-test-admin",
            displayName = "Expense Admin",
            username = "expense_test_admin",
            role = UserRole.ADMIN
        )
        val adminMembership = membership.copy(
            id = "expense-test-admin-membership",
            userId = admin.id,
            role = UserRole.ADMIN
        )
        database.userDao().insertUser(admin)
        database.userDao().insertMembership(adminMembership)
        SessionManager.setSession(ActiveSession(business, branch, admin, adminMembership))

        val correction = ExpenseLifecycleCommand(
            operationId = OperationId("expense-correction-admin-lifecycle"),
            businessId = business.id,
            branchId = branch.id,
            actorUserId = admin.id,
            deviceId = "expense-test-device",
            occurredAt = original.occurredAt + 10L,
            expenseId = original.expenseId,
            action = ExpenseLifecycleCommand.Action.CORRECT,
            correctedAmountMinor = 7_500L,
            reason = "Corrected supplier invoice"
        )
        val correctionResult = repository.executeExpenseLifecycle(correction)
        assertTrue("Correction must succeed: $correctionResult", correctionResult is CommandResult.Success)
        assertEquals(7_500L, database.expenseDao().getExpenseById(original.expenseId)!!.amountMinor)
        assertEquals(1, database.expenseDao().getEventsForExpense(original.expenseId).count { it.operationId == correction.operationId.value })
        assertEquals("QUEUED", database.financialSyncOutboxDao().findByOperationId(correction.operationId.value)!!.status)

        val correctionReplay = repository.executeExpenseLifecycle(correction)
        assertTrue("Correction replay must be idempotent: $correctionReplay", correctionReplay is CommandResult.Success)
        assertEquals(1, database.expenseDao().getEventsForExpense(original.expenseId).count { it.operationId == correction.operationId.value })
        assertEquals(1, database.transactionDao().getSettlementsForOperation(correction.operationId.value).size)

        val void = ExpenseLifecycleCommand(
            operationId = OperationId("expense-void-admin-lifecycle"),
            businessId = business.id,
            branchId = branch.id,
            actorUserId = admin.id,
            deviceId = "expense-test-device",
            occurredAt = original.occurredAt + 20L,
            expenseId = original.expenseId,
            action = ExpenseLifecycleCommand.Action.VOID,
            reason = "Duplicate expense entry"
        )
        val voidResult = repository.executeExpenseLifecycle(void)
        assertTrue("Void must succeed: $voidResult", voidResult is CommandResult.Success)
        assertEquals(1, database.expenseDao().getEventsForExpense(original.expenseId).count { it.operationId == void.operationId.value })
        assertEquals("QUEUED", database.financialSyncOutboxDao().findByOperationId(void.operationId.value)!!.status)

        val voidReplay = repository.executeExpenseLifecycle(void)
        assertTrue("Void replay must be idempotent: $voidReplay", voidReplay is CommandResult.Success)
        assertEquals(1, database.expenseDao().getEventsForExpense(original.expenseId).count { it.operationId == void.operationId.value })
        assertEquals(1, database.transactionDao().getSettlementsForOperation(void.operationId.value).size)
    }

}
