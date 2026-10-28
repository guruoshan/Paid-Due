package com.dailymoneyregister

import android.content.Context
import android.net.Uri
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
import com.dailymoneyregister.core.model.enums.DocumentType
import com.dailymoneyregister.core.model.enums.MediaType
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
import com.dailymoneyregister.feature.register.data.ExpenseSupportingDocumentRepository
import com.dailymoneyregister.feature.register.data.RegisterRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExpenseSupportingDocumentIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var registerRepository: RegisterRepository
    private lateinit var supportingRepository:
        ExpenseSupportingDocumentRepository

    private val business =
        BusinessEntity(
            "expense-document-test-business",
            "Expense Document Test Business",
            0L,
            0L
        )

    private val branch =
        BranchEntity(
            id = "expense-document-test-branch",
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
            id = "expense-document-test-user",
            businessId = business.id,
            displayName = "Expense Document Admin",
            username = "expense_document_admin",
            role = UserRole.ADMIN,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 0L,
            updatedAt = 0L
        )

    private val membership =
        UserBranchMembershipEntity(
            id = "expense-document-test-membership",
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

        context =
            ApplicationProvider.getApplicationContext()

        database =
            DmrDatabase.createInMemory(context)

        registerRepository =
            RegisterRepository(
                database,
                CommandExecutor(database)
            )

        supportingRepository =
            ExpenseSupportingDocumentRepository(
                database,
                context
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
        suffix: String
    ): ExpenseCommand =
        ExpenseCommand(
            operationId =
                OperationId("expense-document-op-$suffix"),
            businessId =
                business.id,
            branchId =
                branch.id,
            actorUserId =
                user.id,
            expenseId =
                "expense-document-$suffix",
            amountMinor =
                5_250L,
            currencyCode =
                branch.currencyCode,
            category =
                ExpenseCategory.SUPPLIES,
            paymentCategory =
                PaymentCategory.CASH,
            paymentChannel =
                PaymentChannel.CASH_TENDER,
            payeeId =
                null,
            payeeNameSnapshot =
                "Document Supplier",
            payeePhoneSnapshot =
                "+96890000000",
            payeeAccountSnapshot =
                "SUPPLIER-ACCOUNT",
            reference =
                "DOC-$suffix",
            note =
                "Supporting document test",
            recurrenceId =
                null,
            deviceId =
                "expense-document-test-device",
            occurredAt =
                1_700_000_100_000L
        )

    private fun executeExpense(
        command: ExpenseCommand
    ): CommandResult.Success<ExpenseCommandResult> {

        val result =
            runBlocking {
                registerRepository.executeExpense(command)
            }

        assertTrue(
            "Expected Expense success but was $result",
            result is CommandResult.Success
        )

        @Suppress("UNCHECKED_CAST")
        return result as
            CommandResult.Success<ExpenseCommandResult>
    }

    private fun sourceUri(
        kind: String = "evidence"
    ): String =
        Uri.Builder()
            .scheme("content")
            .authority(
                "com.dailymoneyregister.expense.supporting.document.test"
            )
            .appendPath(kind)
            .build()
            .toString()

    @Test
    fun attachmentPersistsNormalizedEvidenceForAuthoritativeExpense() =
        runBlocking {

            val command =
                command("single")

            executeExpense(command)

            val expenseBefore =
                database.expenseDao()
                    .getExpenseById(command.expenseId)!!

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            val result =
                supportingRepository.attach(
                    command.expenseId,
                    sourceUri()
                )

            assertEquals(
                command.expenseId,
                result.expenseId
            )

            val documents =
                database.documentMediaDao()
                    .getDocumentsForSubject(
                        SubjectType.EXPENSE.name,
                        command.expenseId
                    )

            assertEquals(1, documents.size)

            val document = documents.single()

            assertEquals(
                DocumentType.SUPPORTING_DOCUMENT,
                document.documentType
            )

            assertEquals(
                SubjectType.EXPENSE,
                document.subjectType
            )

            assertEquals(
                command.expenseId,
                document.subjectId
            )

            assertEquals(
                command.operationId.value,
                document.operationId
            )

            assertEquals(
                null,
                document.transactionId
            )

            val media =
                database.documentMediaDao()
                    .getMediaForDocument(document.id)

            assertEquals(1, media.size)

            val mediaItem = media.single()

            assertEquals(
                MediaType.DOCUMENT_ATTACHMENT,
                mediaItem.mediaType
            )

            assertEquals(
                business.id,
                mediaItem.businessId
            )

            assertEquals(
                branch.id,
                mediaItem.branchId
            )

            assertTrue(
                File(mediaItem.localReference).isFile
            )

            assertTrue(
                File(mediaItem.localReference).length() > 0L
            )

            assertNotEquals(
                sourceUri(),
                mediaItem.localReference
            )

            val expenseAfter =
                database.expenseDao()
                    .getExpenseById(command.expenseId)!!

            assertEquals(
                expenseBefore,
                expenseAfter
            )

            val settlementsAfter =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            assertEquals(
                settlementsBefore,
                settlementsAfter
            )
        }

    @Test
    fun multipleAttachmentsRemainIndependentAndDoNotDuplicateExpense() =
        runBlocking {

            val command =
                command("multiple")

            executeExpense(command)

            val first =
                supportingRepository.attach(
                    command.expenseId,
                    sourceUri()
                )

            val second =
                supportingRepository.attach(
                    command.expenseId,
                    sourceUri()
                )

            assertNotEquals(
                first.documentId,
                second.documentId
            )

            assertNotEquals(
                first.mediaId,
                second.mediaId
            )

            val documents =
                database.documentMediaDao()
                    .getDocumentsForSubject(
                        SubjectType.EXPENSE.name,
                        command.expenseId
                    )

            assertEquals(2, documents.size)

            val readBack =
                supportingRepository
                    .getForExpense(command.expenseId)

            assertEquals(2, readBack.size)

            assertTrue(
                readBack.all {
                    it.media.size == 1 &&
                        it.media.single().mediaType ==
                        MediaType.DOCUMENT_ATTACHMENT
                }
            )

            val expenses =
                database.expenseDao()
                    .getExpensesForPeriod(
                        business.id,
                        branch.id,
                        0L,
                        Long.MAX_VALUE
                    )

            assertEquals(
                1,
                expenses.count {
                    it.id == command.expenseId
                }
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
    fun attachmentFailsClosedForExpenseOutsideActiveBranch() =
        runBlocking {

            val command =
                command("branch")

            executeExpense(command)

            val otherBranch =
                branch.copy(
                    id = "expense-document-other-branch",
                    code = "OTHER",
                    name = "Other"
                )

            database.businessBranchDao()
                .insertBranch(otherBranch)

            val otherMembership =
                membership.copy(
                    id = "expense-document-other-membership",
                    branchId = otherBranch.id
                )

            database.userDao()
                .insertMembership(otherMembership)

            SessionManager.setSession(
                ActiveSession(
                    business,
                    otherBranch,
                    user,
                    otherMembership
                )
            )

            val failure =
                runCatching {
                    supportingRepository.attach(
                        command.expenseId,
                        sourceUri()
                    )
                }

            assertTrue(failure.isFailure)

            assertTrue(
                database.documentMediaDao()
                    .getDocumentsForSubject(
                        SubjectType.EXPENSE.name,
                        command.expenseId
                    )
                    .isEmpty()
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
    fun readBackUsesPersistedPrivateEvidenceAndCreatesNoFinancialWrites() =
        runBlocking {

            val command =
                command("read")

            executeExpense(command)

            supportingRepository.attach(
                command.expenseId,
                sourceUri()
            )

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            val firstRead =
                supportingRepository
                    .getForExpense(command.expenseId)

            val secondRead =
                supportingRepository
                    .getForExpense(command.expenseId)

            assertEquals(1, firstRead.size)
            assertEquals(1, secondRead.size)

            assertEquals(
                firstRead.single().document.id,
                secondRead.single().document.id
            )

            assertEquals(
                firstRead.single().media.single().id,
                secondRead.single().media.single().id
            )

            assertTrue(
                File(
                    firstRead.single()
                        .media.single()
                        .localReference
                ).isFile
            )

            assertEquals(
                settlementsBefore,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
            )
        }

    @Test
    fun jpegMimeTypePersistsPrivateJpgExtension() =
        runBlocking {

            val command =
                command("mime-jpeg")

            executeExpense(command)

            val expenseBefore =
                database.expenseDao()
                    .getExpenseById(command.expenseId)!!

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            val result =
                supportingRepository.attach(
                    command.expenseId,
                    sourceUri("jpeg")
                )

            assertTrue(
                result.localReference.endsWith(
                    ".jpg",
                    ignoreCase = true
                )
            )

            assertTrue(
                File(result.localReference).isFile
            )

            assertEquals(
                expenseBefore,
                database.expenseDao()
                    .getExpenseById(command.expenseId)
            )

            assertEquals(
                settlementsBefore,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
            )
        }

    @Test
    fun pdfMimeTypePersistsPrivatePdfExtension() =
        runBlocking {

            val command =
                command("mime-pdf")

            executeExpense(command)

            val expenseBefore =
                database.expenseDao()
                    .getExpenseById(command.expenseId)!!

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            val result =
                supportingRepository.attach(
                    command.expenseId,
                    sourceUri("pdf")
                )

            assertTrue(
                result.localReference.endsWith(
                    ".pdf",
                    ignoreCase = true
                )
            )

            assertTrue(
                File(result.localReference).isFile
            )

            assertEquals(
                expenseBefore,
                database.expenseDao()
                    .getExpenseById(command.expenseId)
            )

            assertEquals(
                settlementsBefore,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
            )
        }

    @Test
    fun unknownMimeTypeFallsBackToPrivateBinExtension() =
        runBlocking {

            val command =
                command("mime-unknown")

            executeExpense(command)

            val expenseBefore =
                database.expenseDao()
                    .getExpenseById(command.expenseId)!!

            val settlementsBefore =
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )

            val result =
                supportingRepository.attach(
                    command.expenseId,
                    sourceUri("unknown")
                )

            assertTrue(
                result.localReference.endsWith(
                    ".bin",
                    ignoreCase = true
                )
            )

            assertTrue(
                File(result.localReference).isFile
            )

            assertEquals(
                expenseBefore,
                database.expenseDao()
                    .getExpenseById(command.expenseId)
            )

            assertEquals(
                settlementsBefore,
                database.transactionDao()
                    .getSettlementsForOperation(
                        command.operationId.value
                    )
            )
        }
}
