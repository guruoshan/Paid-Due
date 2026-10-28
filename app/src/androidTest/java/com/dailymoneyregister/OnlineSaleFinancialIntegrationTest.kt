package com.dailymoneyregister

import android.content.Context
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
import com.dailymoneyregister.feature.register.data.OnlineSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class OnlineSaleFinancialIntegrationTest {
    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository
    private lateinit var context: Context

    private val business = BusinessEntity("biz-online", "Online Test Business", 0L, 0L)
    private val branch = BranchEntity(
        id = "branch-online", businessId = business.id, name = "Online Branch", code = "ONL",
        countryCode = "OM", currencyCode = "OMR", currencyDecimalPlaces = 3,
        currencySymbol = "OMR", timezoneId = "Asia/Muscat", createdAt = 0L, updatedAt = 0L
    )
    private val user = UserAccountEntity(
        id = "online-cashier", businessId = business.id, displayName = "Online Admin",
        username = "online_admin", role = UserRole.ADMIN, authType = AuthType.PIN,
        credentialHash = "hash", credentialSalt = "salt", createdAt = 0L, updatedAt = 0L
    )
    private val membership = UserBranchMembershipEntity(
        id = "online-membership", userId = user.id, branchId = branch.id, role = UserRole.ADMIN,
        canCreateSale = true, canAcceptCash = true, canAcceptOnline = true, createdAt = 0L
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = DmrDatabase.createInMemory(context)
        repository = RegisterRepository(database, CommandExecutor(database))
        database.businessBranchDao().insertBusiness(business)
        database.businessBranchDao().insertBranch(branch)
        database.userDao().insertUser(user)
        database.userDao().insertMembership(membership)
        SessionManager.setSession(ActiveSession(business, branch, user, membership))
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun simpleOnlineSaleUsesOneExactManualQrFunding() = runBlocking {
        val command = command(draft = DraftState(manualAmount = MoneyMinorUnits(5000L)))
        val result = repository.executeOnlineSale(command)
        assertTrue(result is CommandResult.Success)
        val transaction = (result as CommandResult.Success).data
        assertEquals(TransactionType.SALE, transaction.type)
        assertEquals(5000L, transaction.grandTotalMinor)
        assertEquals("OMR", transaction.currencyCode)
        val settlements = database.transactionDao().getSettlementsForTransaction(transaction.id)
        assertEquals(1, settlements.size)
        assertEquals(EntryPurpose.SALE_FUNDING, settlements.single().entryPurpose)
        assertEquals(PaymentCategory.ONLINE, settlements.single().category)
        assertEquals(PaymentChannel.MANUAL_QR, settlements.single().channel)
        assertEquals(ConfirmationType.ADMIN_CONFIRMED, settlements.single().confirmationType)
        assertEquals(5000L, settlements.single().amountSignedMinor)
    }

    @Test
    fun itemizedOnlineSalePreservesTaxShippingPackingAndItems() = runBlocking {
        val draft = DraftState(
            items = listOf(
                DraftItem("item-1", "Tea", unitPrice = MoneyMinorUnits(2000L), quantity = 2, itemPackingCharge = MoneyMinorUnits(100L), itemShippingCharge = MoneyMinorUnits(50L)),
                DraftItem("item-2", "Milk", unitPrice = MoneyMinorUnits(1000L), quantity = 1)
            ),
            isTaxEnabled = true,
            taxRateBps = 500,
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            txShipping = MoneyMinorUnits(300L),
            txPacking = MoneyMinorUnits(200L)
        )
        val result = repository.executeOnlineSale(command(draft = draft))
        assertTrue(result is CommandResult.Success)
        assertEquals(6050L, (result as CommandResult.Success).data.grandTotalMinor)
        assertEquals(2, database.transactionDao().getItemsForTransaction("online-tx").size)
        assertEquals(1, database.transactionDao().getSettlementsForTransaction("online-tx").size)
    }

    @Test
    fun identicalRetryIsIdempotentAndAlteredRequestConflicts() = runBlocking {
        val first = repository.executeOnlineSale(command())
        val second = repository.executeOnlineSale(command())
        assertTrue(first is CommandResult.Success)
        assertTrue(second is CommandResult.Success)
        assertFalse((first as CommandResult.Success).isIdempotentReplay)
        assertTrue((second as CommandResult.Success).isIdempotentReplay)
        val altered = command(draft = DraftState(manualAmount = MoneyMinorUnits(6000L)))
        assertTrue(repository.executeOnlineSale(altered) is CommandResult.Conflict)
        assertEquals(1, database.transactionDao().getSettlementsForTransaction("online-tx").size)
    }

    @Test
    fun concurrentDuplicateSubmissionCreatesOneFinancialFact() = runBlocking {
        val command = command()
        val results = listOf(async { repository.executeOnlineSale(command) }, async { repository.executeOnlineSale(command) }).awaitAll()
        assertTrue(results.all { it is CommandResult.Success })
        assertEquals(1, database.transactionDao().getSettlementsForTransaction(command.subjectId).size)
        assertEquals(OperationStatus.SUCCEEDED, database.operationDao().getOperationById(command.operationId.value)?.status)
    }

    @Test
    fun missingAcceptOnlineRejectsBeforeMutation() = runBlocking {
        val deniedUser = user.copy(id = "denied-online", username = "denied_online", role = UserRole.CASHIER)
        val deniedMembership = membership.copy(id = "denied-membership", userId = deniedUser.id, role = UserRole.CASHIER, canAcceptOnline = false)
        database.userDao().insertUser(deniedUser)
        database.userDao().insertMembership(deniedMembership)
        SessionManager.setSession(ActiveSession(business, branch, deniedUser, deniedMembership))
        val result = repository.executeOnlineSale(command(actor = deniedUser.id, operation = "denied-op", transaction = "denied-tx"))
        assertTrue(result is CommandResult.PermissionDenied)
        assertEquals(null, database.transactionDao().getTransactionById("denied-tx"))
        assertEquals(null, database.operationDao().getOperationById("denied-op"))
    }

    @Test
    fun missingCreateSaleRejectsBeforeMutation() = runBlocking {
        val deniedUser = user.copy(id = "no-create", username = "no_create", role = UserRole.CASHIER)
        val deniedMembership = membership.copy(id = "no-create-membership", userId = deniedUser.id, role = UserRole.CASHIER, canCreateSale = false)
        database.userDao().insertUser(deniedUser)
        database.userDao().insertMembership(deniedMembership)
        SessionManager.setSession(ActiveSession(business, branch, deniedUser, deniedMembership))
        val result = repository.executeOnlineSale(command(actor = deniedUser.id, operation = "no-create-op", transaction = "no-create-tx"))
        assertTrue(result is CommandResult.PermissionDenied)
        assertEquals(null, database.transactionDao().getTransactionById("no-create-tx"))
    }

    @Test
    fun wrongBusinessOrBranchRejectsBeforeMutation() = runBlocking {
        val result = repository.executeOnlineSale(command(branchId = "other-branch", operation = "wrong-op", transaction = "wrong-tx"))
        assertTrue(result is CommandResult.PermissionDenied)
        assertEquals(null, database.operationDao().getOperationById("wrong-op"))
    }

    @Test
    fun readModelClassifiesOnlineAndCountsFundingOnce() = runBlocking {
        repository.executeOnlineSale(command())
        val view = repository.getFinancialTransactionById("online-tx")
        assertNotNull(view)
        assertEquals(PaymentCategory.ONLINE, view?.currentPaymentCategory)
        assertEquals(PaymentChannel.MANUAL_QR, view?.currentPaymentChannel)
        assertEquals(MoneyMinorUnits(5000L), view?.netAmount)
        assertEquals(5000L, view?.settlementTotal?.amount)
    }

    @Test
    fun validPendingOnlinePayloadRecoversExactlyOnce() = runBlocking {
        val command = command(operation = "pending-op", transaction = "pending-tx")
        database.operationDao().insertOperation(pendingOperation(command))
        val recovered = repository.recoverPendingOnlineSales()
        assertEquals(1, recovered.size)
        assertEquals(OperationStatus.SUCCEEDED, database.operationDao().getOperationById("pending-op")?.status)
        assertEquals(1, database.transactionDao().getSettlementsForTransaction("pending-tx").size)
        assertEquals(0, repository.recoverPendingOnlineSales().size)
    }

    @Test
    fun malformedPendingOnlinePayloadFailsClosed() = runBlocking {
        val op = OperationEntity(
            id = "malformed-op", businessId = business.id, branchId = branch.id,
            operationType = OperationType.RECORD_SALE, subjectType = SubjectType.TRANSACTION,
            subjectId = "malformed-tx", transactionId = "malformed-tx", actorUserId = user.id,
            deviceId = "device-1", occurredAt = 10L, requestFingerprint = "bad-fingerprint",
            commandSchemaVersion = 1, intentPayload = "v=1\ncommandType=ONLINE_SALE\n", status = OperationStatus.PENDING
        )
        database.operationDao().insertOperation(op)
        repository.recoverPendingOnlineSales()
        assertEquals(OperationStatus.REQUIRES_RECONCILIATION, database.operationDao().getOperationById(op.id)?.status)
        assertEquals(null, database.transactionDao().getTransactionById("malformed-tx"))
    }

    private fun command(
        actor: String = user.id,
        operation: String = "online-op",
        transaction: String = "online-tx",
        branchId: String = branch.id,
        draft: DraftState = DraftState(manualAmount = MoneyMinorUnits(5000L))
    ) = OnlineSaleCommand(
        operationId = OperationId(operation), businessId = business.id, branchId = branchId,
        actorUserId = actor, transactionIdVal = TransactionId(transaction), draftState = draft,
        currencyCode = "OMR", currencyDecimalPlaces = 3, deviceId = "device-1", occurredAt = 1700000000000L
    )

    private fun pendingOperation(command: OnlineSaleCommand) = OperationEntity(
        id = command.operationId.value, businessId = command.businessId, branchId = command.branchId,
        operationType = command.operationType, subjectType = command.subjectType, subjectId = command.subjectId,
        transactionId = command.transactionId, actorUserId = command.actorUserId, deviceId = command.deviceId,
        occurredAt = command.occurredAt, requestFingerprint = command.requestFingerprint,
        commandSchemaVersion = command.commandSchemaVersion, intentPayload = command.intentPayload,
        status = OperationStatus.PENDING
    )
}
