package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
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
class RegisterShiftAuthorizationTest {

    private lateinit var database: DmrDatabase
    private lateinit var executor: CommandExecutor
    private lateinit var repository: RegisterRepository
    private lateinit var setupRepository: SetupRepository

    @Before
    fun setUp() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        SessionManager.clearSession()

        database =
            DmrDatabase.createInMemory(context)

        executor =
            CommandExecutor(database)

        setupRepository =
            SetupRepository(
                database,
                executor,
                DeviceIdentityProvider(context),
                TrustedSessionStore(context)
            )

        repository =
            RegisterRepository(
                database,
                executor
            )
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun adminCanCountAndCloseCashierShiftWithoutChangingOwner() =
        runBlocking {

            val setup = createSetup()
            val cashierId = seedCashier(setup)

            setSession(
                setup = setup,
                userId = cashierId
            )

            val cashierShift =
                openCommand(
                    setup = setup,
                    userId = cashierId
                )

            assertTrue(
                repository.openRegisterShift(
                    cashierShift
                ) is CommandResult.Success
            )

            setSession(
                setup = setup,
                userId = setup.adminUserId.value
            )

            val count =
                CountRegisterShiftCommand(
                    operationId =
                        OperationId(
                            "admin-count-cashier-${System.nanoTime()}"
                        ),
                    businessId =
                        setup.businessId.value,
                    branchId =
                        setup.branchId.value,
                    shiftId =
                        cashierShift.shiftId,
                    countedCashMinor =
                        8_750L,
                    actorUserId =
                        setup.adminUserId.value,
                    deviceId =
                        "test-device",
                    occurredAt =
                        2_000L
                )

            val countResult =
                repository.countRegisterShift(count)

            assertTrue(
                countResult is CommandResult.Success
            )

            /*
             * Durable replay must not depend on a future live session.
             */
            SessionManager.clearSession()

            val countReplay =
                repository.countRegisterShift(count)

            assertTrue(
                countReplay is CommandResult.Success &&
                    countReplay.isIdempotentReplay
            )

            setSession(
                setup = setup,
                userId = setup.adminUserId.value
            )

            val close =
                CloseRegisterShiftCommand(
                    operationId =
                        OperationId(
                            "admin-close-cashier-${System.nanoTime()}"
                        ),
                    businessId =
                        setup.businessId.value,
                    branchId =
                        setup.branchId.value,
                    shiftId =
                        cashierShift.shiftId,
                    actorUserId =
                        setup.adminUserId.value,
                    deviceId =
                        "test-device",
                    occurredAt =
                        3_000L
                )

            val closeResult =
                repository.closeRegisterShift(close)

            assertTrue(
                closeResult is CommandResult.Success
            )

            val closeOutbox = database.financialSyncOutboxDao()
                .findByOperationId(close.operationId.value)
            assertEquals(setup.adminUserId.value, closeOutbox?.actorUserId)
            assertEquals(setup.businessId.value, closeOutbox?.businessId)
            assertEquals(setup.branchId.value, closeOutbox?.branchId)
            assertEquals("QUEUED", closeOutbox?.status)
            assertTrue(
                "The close must be returned by the strict active-admin actor/tenant outbox filter.",
                database.financialSyncOutboxDao().getPendingUploadForActor(
                    businessId = setup.businessId.value,
                    branchId = setup.branchId.value,
                    actorUserId = setup.adminUserId.value,
                    limit = 100
                ).any { it.operationId == close.operationId.value }
            )

            /*
             * RegisterShiftResult.actorUserId is the historical shift owner.
             * It must remain the cashier, not the Admin who closed it.
             */
            assertEquals(
                cashierId,
                (closeResult as CommandResult.Success)
                    .data
                    .actorUserId
            )

            SessionManager.clearSession()

            val closeReplay =
                repository.closeRegisterShift(close)

            assertTrue(
                closeReplay is CommandResult.Success &&
                    closeReplay.isIdempotentReplay
            )

            val events =
                database.cashShiftDao()
                    .getEventsForShift(
                        cashierShift.shiftId.value
                    )

            val opened =
                events.single {
                    it.type ==
                        CashShiftEventType.OPENED
                }

            val counted =
                events.single {
                    it.type ==
                        CashShiftEventType.COUNTED
                }

            val closed =
                events.single {
                    it.type ==
                        CashShiftEventType.CLOSED
                }

            assertEquals(
                cashierId,
                opened.actorUserId
            )

            assertEquals(
                setup.adminUserId.value,
                counted.actorUserId
            )

            assertEquals(
                setup.adminUserId.value,
                closed.actorUserId
            )

            val storedShift =
                database.cashShiftDao()
                    .getShiftById(
                        cashierShift.shiftId.value
                    )

            assertEquals(
                cashierId,
                storedShift?.userId
            )
        }

    private suspend fun createSetup(): SetupResult =
        (
            setupRepository.executeSetup(
                SetupParams(
                    businessName =
                        "Shift Authorization Business",
                    branchName =
                        "Main Store",
                    branchCode =
                        "MAIN",
                    countryCode =
                        "OM",
                    currencyCode =
                        "OMR",
                    currencyDecimalPlaces =
                        3,
                    currencySymbol =
                        "OMR",
                    timezoneId =
                        "Asia/Muscat",
                    taxRoundingMode =
                        TaxRoundingMode.HALF_UP,
                    defaultTaxRateBps =
                        0,
                    adminDisplayName =
                        "Owner",
                    adminUsername =
                        "owner",
                    adminPasswordPlain =
                        "OwnerSecurePass123",
                    languageCode =
                        "en"
                )
            ) as CommandResult.Success
        ).data

    private suspend fun seedCashier(
        setup: SetupResult
    ): String {

        val cashierId =
            "cashier-${System.nanoTime()}"

        val now = 100L

        database.userDao().insertUser(
            UserAccountEntity(
                id = cashierId,
                identityId = null,
                businessId =
                    setup.businessId.value,
                displayName =
                    "Test Cashier",
                username =
                    "cashier_$cashierId",
                role =
                    UserRole.CASHIER,
                authType =
                    AuthType.PIN,
                credentialHash =
                    "test-only-hash",
                credentialSalt =
                    null,
                languageCode =
                    "en",
                isActive =
                    true,
                mustChangePin =
                    false,
                createdAt =
                    now,
                updatedAt =
                    now
            )
        )

        database.userDao().insertMembership(
            UserBranchMembershipEntity(
                id =
                    "membership-$cashierId",
                userId =
                    cashierId,
                branchId =
                    setup.branchId.value,
                role =
                    UserRole.CASHIER,
                createdAt =
                    now
            )
        )

        return cashierId
    }

    private suspend fun setSession(
        setup: SetupResult,
        userId: String
    ) {
        val business =
            requireNotNull(
                database.businessBranchDao()
                    .getBusinessById(
                        setup.businessId.value
                    )
            )

        val branch =
            requireNotNull(
                database.businessBranchDao()
                    .getBranchById(
                        setup.branchId.value
                    )
            )

        val user =
            requireNotNull(
                database.userDao()
                    .getUserById(userId)
            )

        val membership =
            requireNotNull(
                database.userDao()
                    .getMembership(
                        userId,
                        setup.branchId.value
                    )
            )

        SessionManager.setSession(
            ActiveSession(
                business = business,
                branch = branch,
                user = user,
                membership = membership
            )
        )
    }

    private fun openCommand(
        setup: SetupResult,
        userId: String
    ) =
        OpenRegisterShiftCommand(
            operationId =
                OperationId(
                    "open-${System.nanoTime()}"
                ),
            businessId =
                setup.businessId.value,
            branchId =
                setup.branchId.value,
            shiftId =
                CashShiftId(
                    "shift-${System.nanoTime()}"
                ),
            actorUserId =
                userId,
            deviceId =
                "test-device",
            occurredAt =
                1_000L
        )
}