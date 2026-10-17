package com.dailymoneyregister

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.financial.RecentActivitySourceType
import com.dailymoneyregister.core.model.financial.RecentActivityType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.ExpenseCategory
import com.dailymoneyregister.feature.register.data.ExpenseCommand
import com.dailymoneyregister.feature.register.data.HistoryActivityRow
import com.dailymoneyregister.feature.register.data.HistoryActivitySourceType
import com.dailymoneyregister.feature.register.data.HistoryRepository
import com.dailymoneyregister.feature.register.data.RegisterRepository
import kotlinx.coroutines.flow.first
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
class ExpenseCode2ProjectionIntegrationTest {

    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository
    private lateinit var deviceIdentityProvider: DeviceIdentityProvider

    private val business =
        BusinessEntity(
            "expense-code2-business",
            "Expense Code 2 Business",
            0L,
            0L
        )

    private val branch =
        BranchEntity(
            id = "expense-code2-branch",
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
            id = "expense-code2-user",
            businessId = business.id,
            displayName = "Expense Code 2 Cashier",
            username = "expense_code2_cashier",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val membership =
        UserBranchMembershipEntity(
            id = "expense-code2-membership",
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
                ApplicationProvider.getApplicationContext<android.content.Context>()
            )

        deviceIdentityProvider = DeviceIdentityProvider(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )

        repository =
            RegisterRepository(
                database,
                CommandExecutor(database),
                deviceIdentityProvider
            )

        database.businessBranchDao()
            .insertBusiness(business)

        database.businessBranchDao()
            .insertBranch(branch)

        database.userDao()
            .insertUser(user)

        database.userDao()
            .insertMembership(membership)

        restoreCashierSession()
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    private fun restoreCashierSession() {

        SessionManager.setSession(
            ActiveSession(
                business,
                branch,
                user,
                membership
            )
        )
    }

    private fun command(
        suffix: String,
        amountMinor: Long = 5_250L,
        paymentCategory: PaymentCategory =
            PaymentCategory.CASH,
        paymentChannel: PaymentChannel =
            if (paymentCategory == PaymentCategory.CASH) {
                PaymentChannel.CASH_TENDER
            } else {
                PaymentChannel.MANUAL_QR
            },
        occurredAt: Long =
            System.currentTimeMillis(),
        deviceId: String = deviceIdentityProvider.deviceId
    ): ExpenseCommand =
        ExpenseCommand(
            operationId =
                OperationId(
                    "expense-code2-op-$suffix"
                ),
            businessId =
                business.id,
            branchId =
                branch.id,
            actorUserId =
                user.id,
            expenseId =
                "expense-code2-$suffix",
            amountMinor =
                amountMinor,
            currencyCode =
                branch.currencyCode,
            category =
                ExpenseCategory.SUPPLIES,
            paymentCategory =
                paymentCategory,
            paymentChannel =
                paymentChannel,
            payeeId =
                null,
            payeeNameSnapshot =
                "Code 2 Supplier",
            payeePhoneSnapshot =
                "+96890000000",
            payeeAccountSnapshot =
                "CODE2-SUPPLIER",
            reference =
                "CODE2-REF-$suffix",
            note =
                "Code 2 note $suffix",
            recurrenceId =
                null,
            deviceId =
                deviceId,
            occurredAt =
                occurredAt
        )

    private suspend fun execute(
        command: ExpenseCommand
    ) {

        val result =
            repository.executeExpense(command)

        assertTrue(
            "Expected Expense command success but was $result",
            result is CommandResult.Success
        )
    }

    @Test
    fun deviceLocalRecentExcludesRemoteDeviceExpenseAndPreservesSharedFinancialEvidence() =
        runBlocking {
            val remoteCommand = command(
                suffix = "other-device",
                amountMinor = 8_250L,
                deviceId = "another-physical-installation"
            )
            execute(remoteCommand)

            val recent = repository.observeTodayTransactions(
                branchId = branch.id,
                timezoneId = branch.timezoneId
            ).first()

            assertFalse(
                "Another device's expense must not appear in this device's Recent Transactions.",
                recent.any { it.sourceReference == remoteCommand.expenseId }
            )
            assertNotNull(
                "The shared financial record must remain persisted for History and Reports.",
                database.expenseDao().getExpenseById(remoteCommand.expenseId)
            )
        }

    @Test
    fun recentExpenseAppearsExactlyOnceWithoutFakeTransactionId() =
        runBlocking {

            val command =
                command(
                    suffix = "recent",
                    amountMinor = 6_750L
                )

            execute(command)

            val activities =
                repository.observeTodayTransactions(
                    branchId = branch.id,
                    timezoneId = branch.timezoneId
                ).first { rows ->
                    rows.any {
                        it.sourceType ==
                            RecentActivitySourceType.EXPENSE &&
                            it.sourceReference ==
                                command.expenseId
                    }
                }

            val expenseRows =
                activities.filter {
                    it.sourceType ==
                        RecentActivitySourceType.EXPENSE &&
                        it.sourceReference ==
                            command.expenseId
                }

            assertEquals(
                "Expense must appear exactly once in Recent.",
                1,
                expenseRows.size
            )

            val row =
                expenseRows.single()

            assertEquals(
                RecentActivityType.EXPENSE,
                row.type
            )

            assertNull(
                "Expense Recent row must not manufacture a Transaction ID.",
                row.transactionId
            )

            assertEquals(
                command.operationId.value,
                row.operationId
            )

            assertEquals(
                -command.amountMinor,
                row.signedEffect.amount
            )

            assertEquals(
                -command.amountMinor,
                row.recentTotalEffect.amount
            )

            assertEquals(
                command.reference,
                row.displayRef
            )

            assertEquals(
                "Code 2 Supplier",
                row.customerName
            )

            assertEquals(
                1,
                row.fundingBreakdown.size
            )

            assertEquals(
                PaymentCategory.CASH,
                row.fundingBreakdown.single().category
            )

            assertEquals(
                PaymentChannel.CASH_TENDER,
                row.fundingBreakdown.single().channel
            )
        }

    @Test
    fun expenseDetailsUsesPersistedExpenseAndExactAuthoritativeSettlementChannel() =
        runBlocking {

            val command =
                command(
                    suffix = "details-softpos",
                    amountMinor = 8_125L,
                    paymentCategory =
                        PaymentCategory.ONLINE,
                    paymentChannel =
                        PaymentChannel.SOFTPOS
                )

            execute(command)

            val beforeExpense =
                database.expenseDao()
                    .getExpenseById(
                        command.expenseId
                    )

            val beforeSettlements =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertNotNull(beforeExpense)
            assertEquals(1, beforeSettlements.size)

            val details =
                repository.getExpenseDetails(
                    command.expenseId
                )

            assertNotNull(details)

            details!!

            assertEquals(
                command.expenseId,
                details.expenseId
            )

            assertEquals(
                command.operationId.value,
                details.operationId
            )

            assertEquals(
                command.amountMinor,
                details.amount.amount
            )

            assertEquals(
                -command.amountMinor,
                details.signedEffect.amount
            )

            assertEquals(
                ExpenseCategory.SUPPLIES.name,
                details.category
            )

            assertEquals(
                PaymentCategory.ONLINE,
                details.paymentCategory
            )

            /*
             * Critical Code 2 assertion:
             * Details must preserve the actual persisted settlement channel.
             * It must not infer every Online Expense as MANUAL_QR.
             */
            assertEquals(
                PaymentChannel.SOFTPOS,
                details.paymentChannel
            )

            assertEquals(
                "Code 2 Supplier",
                details.payeeName
            )

            assertEquals(
                "+96890000000",
                details.payeePhone
            )

            assertEquals(
                command.reference,
                details.reference
            )

            assertEquals(
                command.note,
                details.note
            )

            val afterExpense =
                database.expenseDao()
                    .getExpenseById(
                        command.expenseId
                    )

            val afterSettlements =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                "Expense Details read must not mutate the Expense row.",
                beforeExpense,
                afterExpense
            )

            assertEquals(
                "Expense Details read must not mutate settlement evidence.",
                beforeSettlements,
                afterSettlements
            )
        }

    @Test
    fun expenseDetailsFailsClosedOutsideActiveBranch() =
        runBlocking {

            val command =
                command(
                    suffix = "scope"
                )

            execute(command)

            val otherBranch =
                branch.copy(
                    id = "expense-code2-other-branch",
                    code = "OTHER",
                    name = "Other"
                )

            val otherMembership =
                membership.copy(
                    id = "expense-code2-other-membership",
                    branchId = otherBranch.id
                )

            SessionManager.setSession(
                ActiveSession(
                    business,
                    otherBranch,
                    user,
                    otherMembership
                )
            )

            val details =
                repository.getExpenseDetails(
                    command.expenseId
                )

            assertNull(
                "Expense Details must fail closed outside the active branch.",
                details
            )
        }

    @Test
    fun historyExpenseAppearsExactlyOnceFromAuthoritativeProjection() =
        runBlocking {

            val command =
                command(
                    suffix = "history",
                    amountMinor = 4_875L,
                    paymentCategory =
                        PaymentCategory.ONLINE,
                    paymentChannel =
                        PaymentChannel.SOFTPOS
                )

            execute(command)

            /*
             * History requires VIEW_BROAD_HISTORY.
             *
             * Use the same authenticated identity and branch, but elevate only
             * the in-memory test session role so this test exercises History's
             * read projection rather than being blocked by cashier History
             * policy. No production membership or database row is changed.
             */
            val historyUser =
                user.copy(
                    role = UserRole.ADMIN
                )

            val historyMembership =
                membership.copy(
                    role = UserRole.ADMIN
                )

            SessionManager.setSession(
                ActiveSession(
                    business,
                    branch,
                    historyUser,
                    historyMembership
                )
            )

            val rows =
                HistoryRepository(database)
                    .observeHistory()
                    .first { history ->
                        history.any {
                            it.sourceType ==
                                HistoryActivitySourceType.EXPENSE &&
                                it.sourceId ==
                                    command.expenseId
                        }
                    }

            val expenseRows =
                rows.filter {
                    it.sourceType ==
                        HistoryActivitySourceType.EXPENSE &&
                        it.sourceId ==
                            command.expenseId
                }

            assertEquals(
                "Expense must appear exactly once in History.",
                1,
                expenseRows.size
            )

            val row =
                expenseRows.single()

            assertTrue(
                row is HistoryActivityRow.Expense
            )

            val expenseRow =
                row as HistoryActivityRow.Expense

            assertEquals(
                command.expenseId,
                expenseRow.expense.expenseId
            )

            assertEquals(
                command.operationId.value,
                expenseRow.expense.operationId
            )

            assertEquals(
                -command.amountMinor,
                expenseRow.expense.signedEffect.amount
            )

            assertEquals(
                PaymentCategory.ONLINE,
                expenseRow.expense.paymentCategory
            )

            assertEquals(
                PaymentChannel.SOFTPOS,
                expenseRow.expense.paymentChannel
            )

            assertEquals(
                command.reference,
                expenseRow.displayRef
            )

            assertEquals(
                "Code 2 Supplier",
                expenseRow.customerName
            )

            assertEquals(
                "+96890000000",
                expenseRow.canonicalPhone
            )
        }

    @Test
    fun recentAndDetailsReadsDoNotCreateDuplicateFinancialEvidence() =
        runBlocking {

            val command =
                command(
                    suffix = "read-only",
                    amountMinor = 3_625L
                )

            execute(command)

            val beforeExpenses =
                database.expenseDao()
                    .getExpensesForPeriod(
                        business.id,
                        branch.id,
                        command.occurredAt,
                        command.occurredAt + 1L
                    )

            val beforeSettlements =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertEquals(1, beforeExpenses.size)
            assertEquals(1, beforeSettlements.size)

            repository.getExpenseDetails(
                command.expenseId
            )

            repository.observeTodayTransactions(
                branchId = branch.id,
                timezoneId = branch.timezoneId
            ).first { rows ->
                rows.any {
                    it.sourceType ==
                        RecentActivitySourceType.EXPENSE &&
                        it.sourceReference ==
                            command.expenseId
                }
            }

            val afterExpenses =
                database.expenseDao()
                    .getExpensesForPeriod(
                        business.id,
                        branch.id,
                        command.occurredAt,
                        command.occurredAt + 1L
                    )

            val afterSettlements =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                "Read projections must not duplicate Expense evidence.",
                beforeExpenses,
                afterExpenses
            )

            assertEquals(
                "Read projections must not duplicate settlement evidence.",
                beforeSettlements,
                afterSettlements
            )
        }
}