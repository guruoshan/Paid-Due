package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.ids.CashShiftId
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.PinThrottler
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.admin.data.CashierLifecycleRepository
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository
import com.dailymoneyregister.feature.admin.data.CashierPermissionsConfig
import com.dailymoneyregister.feature.admin.data.CreateCashierParams
import com.dailymoneyregister.feature.admin.data.StaffTarget
import com.dailymoneyregister.feature.admin.data.UpdateCashierParams
import com.dailymoneyregister.feature.admin.ui.CashierPermissionKey
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import com.dailymoneyregister.feature.register.data.OpenRegisterShiftCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class CashierLifecycleIntegrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var database: DmrDatabase

    private lateinit var commandExecutor: CommandExecutor
    private lateinit var setupRepository: SetupRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var cashierManagementRepository: CashierManagementRepository
    private lateinit var lifecycle: CashierLifecycleRepository
    private lateinit var context: Context
    private lateinit var trustedSessionStore: TrustedSessionStore

    private data class Shop(val businessId: String, val branchId: String, val adminId: String)

    @Before
    fun setUp() {
        hiltRule.inject()
        context = isolatedAndroidTestContext(ApplicationProvider.getApplicationContext())
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()

        val device = com.dailymoneyregister.core.device.DeviceIdentityProvider(context)
        commandExecutor = CommandExecutor(database)
        setupRepository = SetupRepository(database, commandExecutor, device, trustedSessionStore)
        authRepository = AuthRepository(database, PinThrottler(context = context), LanguageManager(context), trustedSessionStore)
        cashierManagementRepository = CashierManagementRepository(database, commandExecutor, device)
        lifecycle = CashierLifecycleRepository(database, commandExecutor, device)
    }

    @After
    fun tearDown() {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        database.close()
    }

    private suspend fun setupShop(): Shop {
        val result = setupRepository.executeSetup(
            SetupParams(
                businessName = "Lifecycle Shop",
                branchName = "Main",
                branchCode = "MAIN",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                taxRoundingMode = TaxRoundingMode.HALF_UP,
                defaultTaxRateBps = 500,
                adminDisplayName = "Owner",
                adminUsername = "owner1",
                adminPasswordPlain = "OwnerPass123",
                languageCode = "en"
            )
        ) as CommandResult.Success
        val data = result.data
        return Shop(data.businessId.value, data.branchId.value, data.adminUserId.value)
    }

    private suspend fun createCashier(shop: Shop, name: String, phone: String, pin: String = "482915"): String {
        val result = cashierManagementRepository.createCashier(
            CreateCashierParams(
                businessId = shop.businessId,
                branchId = shop.branchId,
                adminUserId = shop.adminId,
                displayName = name,
                username = phone,
                phone = phone,
                pin = pin,
                preferredLanguage = "en"
            )
        )
        assertTrue("Cashier creation should succeed", result is CommandResult.Success)
        return (result as CommandResult.Success).data.value
    }

    private fun target(shop: Shop, cashierId: String, actorId: String = shop.adminId) =
        StaffTarget(shop.businessId, shop.branchId, actorId, cashierId)

    @Test
    fun editDetailsChangesNameLanguagePhoneAndLoginIdentity() {
        runBlocking {
            val shop = setupShop()
            val cashierId = createCashier(shop, "Rahul", "91123456")

            val result = lifecycle.updateCashier(
                UpdateCashierParams(target(shop, cashierId), "Rahul Kumar", "99450798", "hi")
            )
            assertTrue("Update should succeed: $result", result is CommandResult.Success)

            val user = database.userDao().getUserById(cashierId)!!
            assertEquals("Rahul Kumar", user.displayName)
            assertEquals("hi", user.languageCode)
            assertEquals("99450798", user.username)

            // The old number no longer signs in, the new one does.
            assertTrue(authRepository.loginCashier("91123456", "482915") !is AuthResult.Success)
            assertTrue(authRepository.loginCashier("99450798", "482915") is AuthResult.Success)
        }
    }

    @Test
    fun editDetailsRejectsNumberOwnedByAnotherAccount() {
        runBlocking {
            val shop = setupShop()
            createCashier(shop, "First", "91123456")
            val secondId = createCashier(shop, "Second", "99450798")

            val result = lifecycle.updateCashier(
                UpdateCashierParams(target(shop, secondId), "Second", "91123456", "en")
            )
            assertTrue("Duplicate number must be a Conflict: $result", result is CommandResult.Conflict)
            assertEquals("99450798", database.userDao().getUserById(secondId)!!.username)
        }
    }

    @Test
    fun editPermissionsPersistsAllFourteenFlags() {
        runBlocking {
            val shop = setupShop()
            val cashierId = createCashier(shop, "Perm Cashier", "91123456")

            val wanted = CashierPermissionsConfig(
                canCreateSale = true,
                canAcceptCash = false,
                canAcceptOnline = true,
                canCreateCreditSale = true,
                canUseCoupon = true,
                canIssueInvoice = false,
                canIssueReceipt = true,
                canRefund = true,
                canVoid = true,
                canCorrectAmount = true,
                canChangePaymentMethod = true,
                canViewBroadHistory = true,
                canManageProducts = true,
                canViewReports = true
            )

            val result = lifecycle.updateCashierPermissions(target(shop, cashierId), wanted)
            assertTrue("Permission update should succeed: $result", result is CommandResult.Success)

            val m = database.userDao().getMembership(cashierId, shop.branchId)!!
            assertEquals(wanted.canCreateSale, m.canCreateSale)
            assertEquals(wanted.canAcceptCash, m.canAcceptCash)
            assertEquals(wanted.canAcceptOnline, m.canAcceptOnline)
            assertEquals(wanted.canCreateCreditSale, m.canCreateCreditSale)
            assertEquals(wanted.canUseCoupon, m.canUseCoupon)
            assertEquals(wanted.canIssueInvoice, m.canIssueInvoice)
            assertEquals(wanted.canIssueReceipt, m.canIssueReceipt)
            assertEquals(wanted.canRefund, m.canRefund)
            assertEquals(wanted.canVoid, m.canVoid)
            assertEquals(wanted.canCorrectAmount, m.canCorrectAmount)
            assertEquals(wanted.canChangePaymentMethod, m.canChangePaymentMethod)
            assertEquals(wanted.canViewBroadHistory, m.canViewBroadHistory)
            assertEquals(wanted.canManageProducts, m.canManageProducts)
            assertEquals(wanted.canViewReports, m.canViewReports)

            // Saving the same values again is reported, not silently written.
            val again = lifecycle.updateCashierPermissions(target(shop, cashierId), wanted)
            assertTrue(again is CommandResult.Conflict)
        }
    }

    @Test
    fun disableIsRejectedWhileCashierHasOpenShift() {
        runBlocking {

            val shop =
                setupShop()

            val cashierId =
                createCashier(
                    shop,
                    "Shift Staff",
                    "91123456"
                )

            val registerRepository =
                RegisterRepository(
                    database,
                    commandExecutor
                )

            val openResult =
                registerRepository
                    .openRegisterShift(
                        OpenRegisterShiftCommand(
                            operationId =
                                OperationId.generate(),

                            businessId =
                                shop.businessId,

                            branchId =
                                shop.branchId,

                            shiftId =
                                CashShiftId.generate(),

                            actorUserId =
                                cashierId,

                            deviceId =
                                "staff-lifecycle-test-device"
                        )
                    )

            assertTrue(
                "Open shift should succeed before disable guard is tested: $openResult",
                openResult is CommandResult.Success
            )

            val disableOperationId =
                OperationId.generate()

            val disableResult =
                lifecycle.disableCashier(
                    target(
                        shop,
                        cashierId
                    ),
                    disableOperationId
                )

            assertTrue(
                "Disable must be rejected while the cashier owns an open shift: $disableResult",
                disableResult is CommandResult.Conflict
            )

            assertTrue(
                database.userDao()
                    .getUserById(
                        cashierId
                    )!!
                    .isActive
            )

            /*
             * Rejection occurs before durable staff-operation admission.
             */
            assertNull(
                "Rejected disable must not create a durable staff operation",
                database.operationDao()
                    .getOperationById(
                        disableOperationId.value
                    )
            )
        }
    }

    @Test
    fun disableBlocksLoginAndEnableRestoresIt() {
        runBlocking {
            val shop = setupShop()
            val cashierId = createCashier(shop, "Temp Staff", "91123456")
            val ref = target(shop, cashierId)

            assertTrue(lifecycle.disableCashier(ref) is CommandResult.Success)
            assertFalse(database.userDao().getUserById(cashierId)!!.isActive)
            assertTrue(authRepository.loginCashier("91123456", "482915") is AuthResult.InvalidCredentials)

            assertTrue(lifecycle.enableCashier(ref) is CommandResult.Success)
            assertTrue(database.userDao().getUserById(cashierId)!!.isActive)
            assertTrue(authRepository.loginCashier("91123456", "482915") is AuthResult.Success)
        }
    }

    @Test
    fun disableTwiceIsRejectedAndSameOperationReplaysSafely() {
        runBlocking {
            val shop = setupShop()
            val cashierId = createCashier(shop, "Replay Staff", "91123456")
            val ref = target(shop, cashierId)

            val opId = OperationId.generate()
            val first = lifecycle.disableCashier(ref, opId)
            assertTrue(first is CommandResult.Success)
            assertFalse((first as CommandResult.Success).isIdempotentReplay)

            val replay = lifecycle.disableCashier(ref, opId)
            assertTrue(replay is CommandResult.Success)
            assertTrue((replay as CommandResult.Success).isIdempotentReplay)

            // A brand new request for an already-disabled cashier is a Conflict.
            assertTrue(lifecycle.disableCashier(ref) is CommandResult.Conflict)
        }
    }

    @Test
    fun resetPinInvalidatesOldPinAndForcesChange() {
        runBlocking {
            val shop = setupShop()
            val cashierId = createCashier(shop, "Forgot Pin", "91123456", pin = "482915")
            val ref = target(shop, cashierId)

            assertTrue(lifecycle.resetCashierPin(ref, "482915") is CommandResult.Conflict)
            assertTrue(lifecycle.resetCashierPin(ref, "12ab") is CommandResult.Error)

            val result = lifecycle.resetCashierPin(ref, "736254")
            assertTrue("Reset should succeed: $result", result is CommandResult.Success)
            assertTrue(database.userDao().getUserById(cashierId)!!.mustChangePin)

            assertTrue(authRepository.loginCashier("91123456", "482915") is AuthResult.InvalidCredentials)
            val login = authRepository.loginCashier("91123456", "736254")
            assertTrue(login is AuthResult.Success)
            assertTrue((login as AuthResult.Success).session.requiresPinChange)
        }
    }

    @Test
    fun permissionEditorHidesPermissionsWithoutProvenBackendEnforcement() {

        val editable =
            CashierPermissionKey
                .editableValues
                .toSet()

        assertFalse(
            CashierPermissionKey.ISSUE_RECEIPT
                in editable
        )

        assertFalse(
            CashierPermissionKey.MANAGE_PRODUCTS
                in editable
        )

        assertFalse(
            CashierPermissionKey.VIEW_REPORTS
                in editable
        )

        /*
         * Known enforced permissions remain available.
         */
        assertTrue(
            CashierPermissionKey.CREATE_SALE
                in editable
        )

        assertTrue(
            CashierPermissionKey.REFUND
                in editable
        )

        assertTrue(
            CashierPermissionKey.VOID
                in editable
        )
    }

    @Test
    fun cashierCannotRunStaffCommands() {
        runBlocking {
            val shop = setupShop()
            val actorId = createCashier(shop, "Actor", "91123456")
            val victimId = createCashier(shop, "Victim", "99450798")

            val result = lifecycle.disableCashier(target(shop, victimId, actorId = actorId))
            assertTrue("Cashier actor must be denied: $result", result is CommandResult.PermissionDenied)
            assertTrue(database.userDao().getUserById(victimId)!!.isActive)
        }
    }

    @Test
    fun adminAccountCannotBeTargeted() {
        runBlocking {
            val shop = setupShop()
            val result = lifecycle.disableCashier(target(shop, shop.adminId))
            assertTrue("Admin must not be a lifecycle target: $result", result is CommandResult.PermissionDenied)
            assertTrue(database.userDao().getUserById(shop.adminId)!!.isActive)
        }
    }
}