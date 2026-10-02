package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.TrustedSessionStore
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
class RegisterShiftFoundationTest {
    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository
    private lateinit var setupRepository: SetupRepository
    private lateinit var trustedSessionStore: TrustedSessionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()
        database = DmrDatabase.createInMemory(context)
        val executor = CommandExecutor(database)
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
        trustedSessionStore.clear()
        database.close()
    }

    @Test
    fun openingShiftIsDurableAndIdempotent() = runBlocking {
        val setup = createSetup()
        val command = openCommand(setup.businessId.value, setup.branchId.value, setup.adminUserId.value)

        val first = repository.openRegisterShift(command)
        val replay = repository.openRegisterShift(command)

        assertTrue(first is CommandResult.Success)
        assertTrue(replay is CommandResult.Success && replay.isIdempotentReplay)
        assertEquals(1, database.cashShiftDao().getEventsForShift(command.shiftId.value).size)
        assertEquals(
            OperationStatus.SUCCEEDED,
            database.operationDao().getOperationById(command.operationId.value)?.status
        )
    }

    @Test
    fun pendingOpeningRecoversWithoutDuplicateEvent() = runBlocking {
        val setup = createSetup()
        val command = openCommand(setup.businessId.value, setup.branchId.value, setup.adminUserId.value)
        database.operationDao().insertOperation(
            OperationEntity(
                id = command.operationId.value,
                businessId = command.businessId,
                branchId = command.branchId,
                operationType = OperationType.OPEN_CASH_SHIFT,
                subjectType = SubjectType.CASH_SHIFT,
                subjectId = command.shiftId.value,
                actorUserId = command.actorUserId,
                deviceId = command.deviceId,
                occurredAt = command.occurredAt,
                requestFingerprint = command.requestFingerprint,
                commandSchemaVersion = command.commandSchemaVersion,
                intentPayload = command.intentPayload,
                status = OperationStatus.PENDING
            )
        )

        val recovered = repository.recoverPendingRegisterShifts()

        assertEquals(1, recovered.size)
        assertEquals(1, database.cashShiftDao().getEventsForShift(command.shiftId.value).size)
        assertEquals(
            OperationStatus.SUCCEEDED,
            database.operationDao().getOperationById(command.operationId.value)?.status
        )
    }

    private suspend fun createSetup(): SetupResult =
        (setupRepository.executeSetup(
            SetupParams(
                businessName = "Shift Test Business",
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

    private fun openCommand(businessId: String, branchId: String, userId: String) =
        OpenRegisterShiftCommand(
            operationId = OperationId("open-${System.nanoTime()}"),
            businessId = businessId,
            branchId = branchId,
            shiftId = CashShiftId("shift-${System.nanoTime()}"),
            actorUserId = userId,
            deviceId = "test-device",
            occurredAt = 1000L
        )
}
