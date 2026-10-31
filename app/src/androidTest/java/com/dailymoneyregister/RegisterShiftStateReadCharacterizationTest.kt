package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.register.data.CloseRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.CountRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.OpenRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.data.RegisterShiftResult
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.setup.data.SetupResult
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterizes RegisterRepository.readRegisterShift before that implementation
 * is moved behind a dedicated read-only collaborator.
 *
 * Reflection is deliberately limited to this pre-extraction characterization:
 * it exercises the existing private implementation without changing production
 * visibility or the public repository API.
 */
@RunWith(AndroidJUnit4::class)
class RegisterShiftStateReadCharacterizationTest {
    private lateinit var database: DmrDatabase
    private lateinit var repository: RegisterRepository
    private lateinit var setupRepository: SetupRepository
    private lateinit var trustedSessionStore: TrustedSessionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SessionManager.clearSession()
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
        SessionManager.clearSession()
        trustedSessionStore.clear()
        database.close()
    }

    @Test
    fun readProjectionPreservesOpenCountCloseAndDoesNotWrite() = runBlocking {
        val setup = createSetup()
        setAdminSession(setup)
        val open = openCommand(setup)
        val opened = repository.openRegisterShift(open) as CommandResult.Success

        assertEquals(open.shiftId, opened.data.shiftId)
        assertEquals(setup.branchId.value, opened.data.branchId)
        assertEquals(setup.adminUserId.value, opened.data.actorUserId)
        assertEquals(1_000L, opened.data.openedAt)
        assertTrue(opened.data.isOpen)
        assertNull(opened.data.countedCashMinor)
        assertNull(opened.data.countedAt)
        assertNull(opened.data.closedAt)

        val beforeReadShift = database.cashShiftDao().getShiftById(open.shiftId.value)
        val beforeReadEvents = database.cashShiftDao().getEventsForShift(open.shiftId.value)
        val openProjection = readRegisterShift(
            open.shiftId.value,
            setup.branchId.value,
            setup.businessId.value
        )
        assertEquals(opened.data, openProjection)
        assertEquals(beforeReadShift, database.cashShiftDao().getShiftById(open.shiftId.value))
        assertEquals(beforeReadEvents, database.cashShiftDao().getEventsForShift(open.shiftId.value))

        val firstCount = countCommand(setup, open.shiftId, "projection-count-first", 8_750L, 2_000L)
        val firstCountResult = repository.countRegisterShift(firstCount) as CommandResult.Success
        assertEquals(8_750L, firstCountResult.data.countedCashMinor)
        assertEquals(2_000L, firstCountResult.data.countedAt)

        val laterCount = countCommand(setup, open.shiftId, "projection-count-later", 9_900L, 3_000L)
        val laterCountResult = repository.countRegisterShift(laterCount) as CommandResult.Success
        assertEquals(9_900L, laterCountResult.data.countedCashMinor)
        assertEquals(3_000L, laterCountResult.data.countedAt)

        val countedProjection = readRegisterShift(
            open.shiftId.value,
            setup.branchId.value,
            setup.businessId.value
        )
        assertEquals(9_900L, countedProjection.countedCashMinor)
        assertEquals(3_000L, countedProjection.countedAt)
        assertEquals(setup.adminUserId.value, countedProjection.actorUserId)

        val close = closeCommand(setup, open.shiftId)
        val closedResult = repository.closeRegisterShift(close) as CommandResult.Success
        assertFalse(closedResult.data.isOpen)
        assertEquals(9_900L, closedResult.data.countedCashMinor)
        assertEquals(3_000L, closedResult.data.countedAt)
        assertEquals(4_000L, closedResult.data.closedAt)

        val beforeClosedReadShift = database.cashShiftDao().getShiftById(open.shiftId.value)
        val beforeClosedReadEvents = database.cashShiftDao().getEventsForShift(open.shiftId.value)
        val closedProjection = readRegisterShift(
            open.shiftId.value,
            setup.branchId.value,
            setup.businessId.value
        )
        assertEquals(closedResult.data, closedProjection)
        assertEquals(beforeClosedReadShift, database.cashShiftDao().getShiftById(open.shiftId.value))
        assertEquals(beforeClosedReadEvents, database.cashShiftDao().getEventsForShift(open.shiftId.value))
    }

    @Test
    fun readProjectionRejectsMissingShiftIdentityMismatchAndMissingOpenedEvent() {
        runBlocking {
        val setup = createSetup()
        setAdminSession(setup)
        val open = openCommand(setup, operationId = "projection-open-validation", shiftId = "projection-shift-validation")
        repository.openRegisterShift(open)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                readRegisterShift(
                    open.shiftId.value,
                    "different-branch",
                    setup.businessId.value
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                readRegisterShift(
                    open.shiftId.value,
                    setup.branchId.value,
                    "different-business"
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                readRegisterShift(
                    "missing-shift",
                    setup.branchId.value,
                    setup.businessId.value
                )
            }
        }

        val missingOpenedShiftId = "projection-shift-without-opened"
        database.cashShiftDao().insertShift(
            CashShiftEntity(
                id = missingOpenedShiftId,
                branchId = setup.branchId.value,
                userId = setup.adminUserId.value,
                createdAt = 5_000L
            )
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                readRegisterShift(
                    missingOpenedShiftId,
                    setup.branchId.value,
                    setup.businessId.value
                )
            }
        }
        }
    }

    @Test
    fun replayReturnsSameShiftProjection() = runBlocking {
        val setup = createSetup()
        setAdminSession(setup)
        val open = openCommand(setup, operationId = "projection-replay-open", shiftId = "projection-replay-shift")
        val first = repository.openRegisterShift(open) as CommandResult.Success
        val replay = repository.openRegisterShift(open) as CommandResult.Success

        assertTrue(replay.isIdempotentReplay)
        assertEquals(first.data, replay.data)
        assertEquals(
            first.data,
            readRegisterShift(open.shiftId.value, setup.branchId.value, setup.businessId.value)
        )
    }

    private suspend fun readRegisterShift(
        shiftId: String,
        expectedBranchId: String,
        expectedBusinessId: String
    ): RegisterShiftResult = suspendCancellableCoroutine { continuation ->
        val method = RegisterRepository::class.java.getDeclaredMethod(
            "readRegisterShift",
            String::class.java,
            String::class.java,
            String::class.java,
            Continuation::class.java
        )
        method.isAccessible = true
        try {
            val result = method.invoke(
                repository,
                shiftId,
                expectedBranchId,
                expectedBusinessId,
                continuation
            )
            if (result !== COROUTINE_SUSPENDED) {
                continuation.resume(result as RegisterShiftResult)
            }
        } catch (error: InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }

    private suspend fun createSetup(): SetupResult =
        (setupRepository.executeSetup(
            SetupParams(
                businessName = "Shift State Read Test Business",
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
        val business = requireNotNull(database.businessBranchDao().getBusinessById(setup.businessId.value))
        val branch = requireNotNull(database.businessBranchDao().getBranchById(setup.branchId.value))
        val user = requireNotNull(database.userDao().getUserById(setup.adminUserId.value))
        val membership = requireNotNull(database.userDao().getMembership(setup.adminUserId.value, setup.branchId.value))
        SessionManager.setSession(ActiveSession(business, branch, user, membership))
    }

    private fun openCommand(
        setup: SetupResult,
        operationId: String = "projection-open",
        shiftId: String = "projection-shift"
    ) = OpenRegisterShiftCommand(
        operationId = OperationId(operationId),
        businessId = setup.businessId.value,
        branchId = setup.branchId.value,
        shiftId = CashShiftId(shiftId),
        actorUserId = setup.adminUserId.value,
        deviceId = "shift-state-read-test-device",
        occurredAt = 1_000L
    )

    private fun countCommand(
        setup: SetupResult,
        shiftId: CashShiftId,
        operationId: String,
        amountMinor: Long,
        occurredAt: Long
    ) = CountRegisterShiftCommand(
        operationId = OperationId(operationId),
        businessId = setup.businessId.value,
        branchId = setup.branchId.value,
        shiftId = shiftId,
        countedCashMinor = amountMinor,
        actorUserId = setup.adminUserId.value,
        deviceId = "shift-state-read-test-device",
        occurredAt = occurredAt
    )

    private fun closeCommand(
        setup: SetupResult,
        shiftId: CashShiftId
    ) = CloseRegisterShiftCommand(
        operationId = OperationId("projection-close"),
        businessId = setup.businessId.value,
        branchId = setup.branchId.value,
        shiftId = shiftId,
        actorUserId = setup.adminUserId.value,
        deviceId = "shift-state-read-test-device",
        occurredAt = 4_000L
    )
}
