package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.*
import com.dailymoneyregister.core.model.enums.*
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.register.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All records, including deliberate invalid fixtures, live only in an in-memory DB. */
@RunWith(AndroidJUnit4::class)
class PaymentMethodChangeWriterTest {
    private lateinit var db: DmrDatabase
    private lateinit var repository: RegisterRepository
    private val business = BusinessEntity("pm-business", "Test", 1000L, 1000L)
    private val branch = BranchEntity(
        id = "pm-branch", businessId = business.id, name = "Test", code = "TEST",
        countryCode = "OM", currencyCode = "OMR", currencyDecimalPlaces = 3,
        currencySymbol = "OMR", timezoneId = "Asia/Muscat", createdAt = 1000L, updatedAt = 1000L
    )
    private val actor = UserAccountEntity(
        id = "pm-user", businessId = business.id, displayName = "Test Admin", username = "payment_method_admin",
        role = UserRole.ADMIN, authType = AuthType.PIN, credentialHash = "test",
        credentialSalt = "test", createdAt = 1000L, updatedAt = 1000L
    )
    private val membership = UserBranchMembershipEntity(
        id = "pm-membership", userId = actor.id, branchId = branch.id,
        role = UserRole.ADMIN, canChangePaymentMethod = true, createdAt = 1000L
    )
    private val original = TransactionEntity(
        id = "pm-sale", branchId = branch.id, type = TransactionType.SALE,
        subtotalMinor = 5000L, taxRateBps = 0, taxableBaseMinor = 5000L,
        taxAmountMinor = 0L, grandTotalMinor = 5000L, currencyCode = "OMR",
        currencyDecimalPlaces = 3, createdByUserId = actor.id, deviceId = "test-device",
        createdAt = 1000L, displayRef = "TEST-1"
    )
    private val funding = SettlementEntryEntity(
        id = "pm-funding", operationId = "pm-sale-operation", subjectType = SubjectType.TRANSACTION,
        subjectId = original.id, transactionId = original.id, entryPurpose = EntryPurpose.SALE_FUNDING,
        category = PaymentCategory.CASH, channel = PaymentChannel.CASH_TENDER,
        amountSignedMinor = 5000L, currencyCode = "OMR",
        confirmationType = ConfirmationType.CASHIER_CONFIRMED, occurredAt = 1000L, actorUserId = actor.id
    )

    @Before fun setUp() = runBlocking {
        db = DmrDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>())
        repository = RegisterRepository(db, CommandExecutor(db))
        db.businessBranchDao().insertBusiness(business)
        db.businessBranchDao().insertBranch(branch)
        db.userDao().insertUser(actor)
        db.userDao().insertMembership(membership)
        SessionManager.setSession(ActiveSession(business, branch, actor, membership))
        db.operationDao().insertOperation(OperationEntity(
            id = funding.operationId, businessId = business.id, branchId = branch.id,
            operationType = OperationType.RECORD_SALE, subjectType = SubjectType.TRANSACTION,
            subjectId = original.id, transactionId = original.id, actorUserId = actor.id,
            deviceId = "test-device", occurredAt = 1000L, requestFingerprint = "fixture",
            intentPayload = "fixture", status = OperationStatus.SUCCEEDED
        ))
        db.transactionDao().insertTransaction(original)
        db.transactionDao().insertSettlement(funding)
    }

    @After fun tearDown() {
        SessionManager.clearSession()
        db.close()
    }

    @Test fun appendsExactBalancedPairAndPreservesOriginalFunding() = runBlocking {
        val command = command()
        assertTrue(repository.executePaymentMethodChange(command) is CommandResult.Success)
        val event = db.transactionDao().getPaymentMethodChanges(original.id).single()
        assertEquals(original.id, event.transactionId)
        assertEquals(command.operationId.value, event.operationId)
        assertEquals(PaymentCategory.CASH, event.previousMethod)
        assertEquals(PaymentChannel.CASH_TENDER, event.previousChannel)
        assertEquals(PaymentCategory.ONLINE, event.newMethod)
        assertEquals(PaymentChannel.MANUAL_QR, event.newChannel)
        assertEquals("test reason", event.reason)
        assertEquals(actor.id, event.actorUserId)
        assertEquals(2000L, event.occurredAt)
        val pair = db.transactionDao().getSettlementsForOperation(command.operationId.value)
        assertEquals(2, pair.size)
        assertEquals(setOf(
            Triple(PaymentCategory.CASH, PaymentChannel.CASH_TENDER, -5000L),
            Triple(PaymentCategory.ONLINE, PaymentChannel.MANUAL_QR, 5000L)
        ), pair.map { Triple(it.category, it.channel, it.amountSignedMinor) }.toSet())
        pair.forEach {
            assertEquals(EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION, it.entryPurpose)
            assertEquals(original.id, it.transactionId)
            assertEquals(original.id, it.subjectId)
            assertEquals(SubjectType.TRANSACTION, it.subjectType)
            assertEquals("OMR", it.currencyCode)
            assertEquals(ConfirmationType.CASHIER_CONFIRMED, it.confirmationType)
            assertEquals(actor.id, it.actorUserId)
            assertEquals(2000L, it.occurredAt)
        }
        assertOriginalUnchanged()
        assertEquals(OperationStatus.SUCCEEDED, db.operationDao().getOperationById(command.operationId.value)!!.status)
    }

    @Test fun retryIsIdempotentAndAlteredRequestConflicts() = runBlocking {
        val requested = command()
        val first = repository.executePaymentMethodChange(requested) as CommandResult.Success
        val replay = repository.executePaymentMethodChange(requested) as CommandResult.Success
        assertEquals(first.data, replay.data)
        assertTrue(replay.isIdempotentReplay)
        assertTrue(repository.executePaymentMethodChange(requested.copy(reason = "changed")) is CommandResult.Conflict)
        assertEquals(1, db.transactionDao().getPaymentMethodChanges(original.id).size)
        assertEquals(3, db.transactionDao().getSettlementsForTransaction(original.id).size)
        assertOriginalUnchanged()
    }

    @Test fun secondChangeRetainsFirstEventAndBothPairs() = runBlocking {
        assertTrue(repository.executePaymentMethodChange(command()) is CommandResult.Success)
        val first = db.transactionDao().getPaymentMethodChanges(original.id).single()
        assertTrue(repository.executePaymentMethodChange(command("pm-second", 3000L).copy(
            newCategory = PaymentCategory.CASH, newChannel = PaymentChannel.CASH_TENDER
        )) is CommandResult.Success)
        assertEquals(first, db.transactionDao().getPaymentMethodChanges(original.id).first())
        assertEquals(2, db.transactionDao().getPaymentMethodChanges(original.id).size)
        assertEquals(5, db.transactionDao().getSettlementsForTransaction(original.id).size)
        assertOriginalUnchanged()
    }

    @Test fun newMutationRequiresSessionAndAuthoritativePermission() = runBlocking {
        val deniedActor = actor.copy(
            id = "pm-denied-cashier", displayName = "Denied Cashier",
            username = "pm_denied_cashier", role = UserRole.CASHIER
        )
        val deniedMembership = membership.copy(
            id = "pm-denied-membership", userId = deniedActor.id,
            role = UserRole.CASHIER, canChangePaymentMethod = false
        )
        db.userDao().insertUser(deniedActor)
        db.userDao().insertMembership(deniedMembership)
        SessionManager.clearSession()
        assertTrue(repository.executePaymentMethodChange(command().copy(actorUserId = deniedActor.id)) is CommandResult.PermissionDenied)
        SessionManager.setSession(ActiveSession(business, branch, deniedActor, deniedMembership))
        assertTrue(repository.executePaymentMethodChange(command().copy(actorUserId = deniedActor.id)) is CommandResult.PermissionDenied)
        assertNull(db.operationDao().getOperationById(command().operationId.value))
        assertTrue(db.transactionDao().getPaymentMethodChanges(original.id).isEmpty())
    }

    @Test fun admittedPendingRecoveryUsesSameWriterWithoutNewSession() = runBlocking {
        val admitted = admitFixture(command())
        persistPending(admitted)
        SessionManager.clearSession()
        assertEquals(1, repository.recoverPendingPaymentMethodChanges().size)
        assertTrue(repository.recoverPendingPaymentMethodChanges().isEmpty())
        assertEquals(1, db.transactionDao().getPaymentMethodChanges(original.id).size)
        assertEquals(3, db.transactionDao().getSettlementsForTransaction(original.id).size)
        assertOriginalUnchanged()
    }

    @Test fun changedStateAfterAdmissionRequiresReconciliation() = runBlocking {
        val admitted = admitFixture(command("pm-pending"))
        persistPending(admitted)
        assertTrue(repository.executePaymentMethodChange(command("pm-intervening", 1500L)) is CommandResult.Success)
        assertTrue(repository.executePaymentMethodChange(admitted) is CommandResult.Conflict)
        assertEquals(OperationStatus.REQUIRES_RECONCILIATION,
            db.operationDao().getOperationById("pm-pending")!!.status)
        assertTrue(db.transactionDao().getSettlementsForOperation("pm-pending").isEmpty())
        assertEquals(1, db.transactionDao().getPaymentMethodChanges(original.id).size)
        assertOriginalUnchanged()
    }

    @Test fun missingPendingPreconditionRequiresReconciliation() = runBlocking {
        persistPending(command())
        assertTrue(repository.executePaymentMethodChange(command()) is CommandResult.Conflict)
        assertEquals(OperationStatus.REQUIRES_RECONCILIATION,
            db.operationDao().getOperationById(command().operationId.value)!!.status)
        assertTrue(db.transactionDao().getPaymentMethodChanges(original.id).isEmpty())
        assertOriginalUnchanged()
    }

    @Test fun malformedPendingIntentRequiresReconciliation() = runBlocking {
        persistPending(command(), payload = "malformed")
        assertTrue(repository.executePaymentMethodChange(command()) is CommandResult.Conflict)
        assertEquals(OperationStatus.REQUIRES_RECONCILIATION,
            db.operationDao().getOperationById(command().operationId.value)!!.status)
        assertTrue(db.transactionDao().getPaymentMethodChanges(original.id).isEmpty())
        assertOriginalUnchanged()
    }

    @Test fun databaseGuardsRejectRewriteDeleteAndReplace() = runBlocking {
        assertTrue(repository.executePaymentMethodChange(command()) is CommandResult.Success)
        val sql = db.openHelper.writableDatabase
        for (table in listOf("transactions", "settlement_entries", "payment_method_change_events")) {
            for (statement in listOf("UPDATE $table SET id = id", "DELETE FROM $table",
                "INSERT OR REPLACE INTO $table SELECT * FROM $table")) {
                var rejected = false
                try { sql.execSQL(statement) } catch (_: android.database.SQLException) { rejected = true }
                assertTrue("Must reject $statement", rejected)
            }
        }
        assertEquals(1, db.transactionDao().getPaymentMethodChanges(original.id).size)
        assertEquals(3, db.transactionDao().getSettlementsForTransaction(original.id).size)
        assertOriginalUnchanged()
    }

    @Test fun backdatedSecondChangeMustNotCommitBrokenHistory() = runBlocking {
        assertTrue(repository.executePaymentMethodChange(command()) is CommandResult.Success)
        val result = repository.executePaymentMethodChange(command("pm-backdated", 1500L).copy(
            newCategory = PaymentCategory.CASH, newChannel = PaymentChannel.CASH_TENDER
        ))
        assertFalse("A backdated reversal would sort before its predecessor and corrupt the lifecycle chain",
            result is CommandResult.Success)
        assertEquals(1, db.transactionDao().getPaymentMethodChanges(original.id).size)
        assertEquals(2, db.transactionDao().getSettlementsForOperation("pm-change").size)
        assertTrue(db.transactionDao().getSettlementsForOperation("pm-backdated").isEmpty())
        assertOriginalUnchanged()
    }

    @Test fun equalTimestampSecondChangeFailsClosedWithoutMutation() = runBlocking {
        assertTrue(repository.executePaymentMethodChange(command()) is CommandResult.Success)
        val result = repository.executePaymentMethodChange(command("pm-equal", 2000L).copy(
            newCategory = PaymentCategory.CASH, newChannel = PaymentChannel.CASH_TENDER
        ))
        assertTrue(result is CommandResult.Error)
        assertTrue((result as CommandResult.Error).exception is PaymentMethodChangeOrderingException)
        assertEquals(1, db.transactionDao().getPaymentMethodChanges(original.id).size)
        assertEquals(2, db.transactionDao().getSettlementsForOperation("pm-change").size)
        assertTrue(db.transactionDao().getSettlementsForOperation("pm-equal").isEmpty())
        assertOriginalUnchanged()
    }

    private fun command(id: String = "pm-change", time: Long = 2000L) = PaymentMethodChangeCommand(
        operationId = OperationId(id), businessId = business.id, branchId = branch.id,
        subjectId = original.id, actorUserId = actor.id, deviceId = "test-device", occurredAt = time,
        newCategory = PaymentCategory.ONLINE, newChannel = PaymentChannel.MANUAL_QR, reason = "test reason"
    )

    // Production fingerprint is used only to construct an admitted input fixture;
    // expected statuses, exact amounts, identities and row counts above are independent.
    private suspend fun admitFixture(request: PaymentMethodChangeCommand): PaymentMethodChangeCommand {
        val record = db.transactionDao().getFinancialRecordById(original.id)!!
        return request.copy(financialPrecondition = PaymentMethodChangeFinancialPrecondition(
            stateFingerprint = PaymentMethodChangeFinancialIntegrity.fingerprint(original.id, record, emptyList()),
            expectedCurrentCategory = PaymentCategory.CASH,
            expectedCurrentChannel = PaymentChannel.CASH_TENDER,
            expectedReclassificationAmountMinor = 5000L
        ))
    }

    private suspend fun persistPending(command: PaymentMethodChangeCommand, payload: String = command.intentPayload) {
        db.operationDao().insertOperation(OperationEntity(
            id = command.operationId.value, businessId = command.businessId, branchId = command.branchId,
            operationType = command.operationType, subjectType = command.subjectType,
            subjectId = command.subjectId, transactionId = command.transactionId, actorUserId = command.actorUserId,
            deviceId = command.deviceId, occurredAt = command.occurredAt,
            requestFingerprint = command.requestFingerprint, commandSchemaVersion = command.commandSchemaVersion,
            intentPayload = payload, status = OperationStatus.PENDING
        ))
    }

    private suspend fun assertOriginalUnchanged() {
        assertEquals(original, db.transactionDao().getTransactionById(original.id))
        assertEquals(listOf(funding), db.transactionDao().getSettlementsForOperation(funding.operationId))
    }
}
