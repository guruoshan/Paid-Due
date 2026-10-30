package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.register.data.CloseRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.CountRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.OpenRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.setup.data.SetupResult
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegisterShiftLifecycleRecoveryCharacterizationTest {
    private lateinit var database: DmrDatabase
    private lateinit var executor: CommandExecutor
    private lateinit var repository: RegisterRepository
    private lateinit var setupRepository: SetupRepository
    private lateinit var trustedSessionStore: TrustedSessionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SessionManager.clearSession()
        database = DmrDatabase.createInMemory(context)
        executor = CommandExecutor(database)
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()
        setupRepository = SetupRepository(
            database,
            executor,
            DeviceIdentityProvider(context),
            trustedSessionStore
        )
        repository = RegisterRepository(database, executor)
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        trustedSessionStore.clear()
        database.close()
    }

    @Test
    fun countAndCloseReplaySameOperationAndRejectAlteredIntent() = runBlocking {
        val setup = createSetup()
        setAdminSession(setup)
        val open = openCommand(setup)
        val opened = repository.openRegisterShift(open)
        assertTrue(opened.toString(), opened is CommandResult.Success)

        val count = countCommand(setup, open.shiftId)
        val firstCount = repository.countRegisterShift(count)
        assertTrue(firstCount.toString(), firstCount is CommandResult.Success)
        val replayCount = repository.countRegisterShift(count)
        assertTrue(replayCount.toString(), replayCount is CommandResult.Success)
        assertTrue((replayCount as CommandResult.Success).isIdempotentReplay)

        val alteredCount = repository.countRegisterShift(
            count.copy(countedCashMinor = 8_751L)
        )
        assertTrue(alteredCount.toString(), alteredCount is CommandResult.Conflict)

        val close = closeCommand(setup, open.shiftId)
        val firstClose = repository.closeRegisterShift(close)
        assertTrue(firstClose.toString(), firstClose is CommandResult.Success)
        val replayClose = repository.closeRegisterShift(close)
        assertTrue(replayClose.toString(), replayClose is CommandResult.Success)
        assertTrue((replayClose as CommandResult.Success).isIdempotentReplay)

        val alteredClose = repository.closeRegisterShift(
            close.copy(occurredAt = 3_001L)
        )
        assertTrue(alteredClose.toString(), alteredClose is CommandResult.Conflict)

        val events = database.cashShiftDao().getEventsForShift(open.shiftId.value)
        assertEquals(3, events.size)
        val counted = events.single { it.type == CashShiftEventType.COUNTED }
        val closed = events.single { it.type == CashShiftEventType.CLOSED }
        assertEquals(8_750L, counted.amountMinor)
        assertEquals(0L, closed.amountMinor)
        assertEquals("count-replay-op:COUNTED", counted.id)
        assertEquals("close-replay-op:CLOSED", closed.id)
        assertEquals(
            1,
            events.count { it.type == CashShiftEventType.OPENED }
        )
    }

    @Test
    fun pendingCountAndCloseRecoverWithoutCompletingOriginalWrites() = runBlocking {
        val setup = createSetup()
        setAdminSession(setup)
        val open = openCommand(setup, operationId = "recovery-open-op", shiftId = "recovery-shift")
        val opened = repository.openRegisterShift(open)
        assertTrue(opened.toString(), opened is CommandResult.Success)

        val count = countCommand(setup, open.shiftId, operationId = "pending-count-op")
        database.operationDao().insertOperation(pendingOperation(count))

        // Match pendingOpeningRecoversWithoutDuplicateEvent: persist only PENDING intent,
        // then invoke recovery without running the original count command.
        val recoveredCount = repository.recoverPendingRegisterShifts()
        assertEquals(1, recoveredCount.size)
        assertEquals(
            OperationStatus.SUCCEEDED,
            database.operationDao().getOperationById(count.operationId.value)?.status
        )
        assertEquals(
            1,
            database.cashShiftDao().getEventsForShift(open.shiftId.value)
                .count { it.type == CashShiftEventType.COUNTED }
        )
        assertEquals(
            8_750L,
            database.cashShiftDao().getEventsForShift(open.shiftId.value)
                .single { it.type == CashShiftEventType.COUNTED }.amountMinor
        )

        val close = closeCommand(setup, open.shiftId, operationId = "pending-close-op")
        database.operationDao().insertOperation(pendingOperation(close))

        // The close write is likewise not executed before recoverPendingRegisterShifts().
        val recoveredClose = repository.recoverPendingRegisterShifts()
        assertEquals(1, recoveredClose.size)
        assertEquals(
            OperationStatus.SUCCEEDED,
            database.operationDao().getOperationById(close.operationId.value)?.status
        )
        val closed = database.cashShiftDao().getEventsForShift(open.shiftId.value)
            .single { it.type == CashShiftEventType.CLOSED }
        assertEquals(0L, closed.amountMinor)
        assertEquals("pending-close-op:CLOSED", closed.id)
        assertEquals(3, database.cashShiftDao().getEventsForShift(open.shiftId.value).size)
    }

    private suspend fun createSetup(): SetupResult =
        (setupRepository.executeSetup(
            SetupParams(
                businessName = "Shift Recovery Test Business",
                branchName = "Main Store",
                branchCode = "MAIN",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                taxRoundingMode = TaxRoundingMode.HALF_UP,
                defaultTaxRateBps = 0,
                adminDisplayName = "Owner",
                adminUsername = "owner",
                adminPasswordPlain = "OwnerSecurePass123",
                languageCode = "en"
            )
        ) as CommandResult.Success).data

    private suspend fun setAdminSession(setup: SetupResult) {
        val business = requireNotNull(
            database.businessBranchDao().getBusinessById(setup.businessId.value)
        )
        val branch = requireNotNull(
            database.businessBranchDao().getBranchById(setup.branchId.value)
        )
        val user = requireNotNull(
            database.userDao().getUserById(setup.adminUserId.value)
        )
        val membership = requireNotNull(
            database.userDao().getMembership(setup.adminUserId.value, setup.branchId.value)
        )
        SessionManager.setSession(ActiveSession(business, branch, user, membership))
    }

    private fun openCommand(
        setup: SetupResult,
        operationId: String = "shift-open-op",
        shiftId: String = "shift-id"
    ) = OpenRegisterShiftCommand(
        operationId = OperationId(operationId),
        businessId = setup.businessId.value,
        branchId = setup.branchId.value,
        shiftId = CashShiftId(shiftId),
        actorUserId = setup.adminUserId.value,
        deviceId = "shift-test-device",
        occurredAt = 1_000L
    )

    private fun countCommand(
        setup: SetupResult,
        shiftId: CashShiftId,
        operationId: String = "count-replay-op"
    ) = CountRegisterShiftCommand(
        operationId = OperationId(operationId),
        businessId = setup.businessId.value,
        branchId = setup.branchId.value,
        shiftId = shiftId,
        countedCashMinor = 8_750L,
        actorUserId = setup.adminUserId.value,
        deviceId = "shift-test-device",
        occurredAt = 2_000L
    )

    private fun closeCommand(
        setup: SetupResult,
        shiftId: CashShiftId,
        operationId: String = "close-replay-op"
    ) = CloseRegisterShiftCommand(
        operationId = OperationId(operationId),
        businessId = setup.businessId.value,
        branchId = setup.branchId.value,
        shiftId = shiftId,
        actorUserId = setup.adminUserId.value,
        deviceId = "shift-test-device",
        occurredAt = 3_000L
    )

    private fun pendingOperation(command: OperationCommand) =
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
}
