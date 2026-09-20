package com.dailymoneyregister

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.security.FinancialImmutabilityCallback
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.PinThrottler
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository
import com.dailymoneyregister.feature.admin.data.CashierPermissionsConfig
import com.dailymoneyregister.feature.admin.data.CreateCashierParams
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.setup.data.SetupResult
import com.dailymoneyregister.feature.settings.data.SettingsLanguageResult
import com.dailymoneyregister.feature.settings.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DmrDatabaseIntegrationTest {

    private lateinit var database: DmrDatabase
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var setupRepository: SetupRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var cashierManagementRepository: CashierManagementRepository
    private lateinit var context: Context
    private lateinit var trustedSessionStore: TrustedSessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()
        database = Room.inMemoryDatabaseBuilder(context, DmrDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(FinancialImmutabilityCallback)
            .build()

        commandExecutor = CommandExecutor(database)
        setupRepository = SetupRepository(database, commandExecutor, com.dailymoneyregister.core.device.DeviceIdentityProvider(context), trustedSessionStore)
        authRepository = AuthRepository(database, PinThrottler(context = context), LanguageManager(context), trustedSessionStore)
        cashierManagementRepository = CashierManagementRepository(database, commandExecutor, com.dailymoneyregister.core.device.DeviceIdentityProvider(context))
    }

    @After
    fun tearDown() {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun testSetupExecutionCreatesBusinessBranchAdminAndMembership() = runBlocking {
        val params = SetupParams(
            businessName = "Al Noor Supermarket",
            branchName = "Muscat Main",
            branchCode = "SAL",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 500,
            adminDisplayName = "Owner Ahmed",
            adminUsername = "admin_ahmed",
            adminPasswordPlain = "AdminSecurePass123",
            languageCode = "ar"
        )

        val result = setupRepository.executeSetup(params)
        assertTrue("Setup should succeed", result is CommandResult.Success)

        val setupResult = (result as CommandResult.Success).data
        val business = database.businessBranchDao().getBusinessById(setupResult.businessId.value)
        assertNotNull(business)
        assertEquals("Al Noor Supermarket", business?.name)

        val branch = database.businessBranchDao().getBranchById(setupResult.branchId.value)
        assertNotNull(branch)
        assertEquals("SAL", branch?.code)
        assertEquals("OMR", branch?.currencyCode)
        assertEquals(3, branch?.currencyDecimalPlaces)

        val admin = database.userDao().getUserById(setupResult.adminUserId.value)
        assertNotNull(admin)
        assertEquals("Owner Ahmed", admin?.displayName)
        assertEquals(UserRole.ADMIN, admin?.role)
        assertEquals("ar", admin?.languageCode)

        val membership = database.userDao().getMembership(admin!!.id, branch!!.id)
        assertNotNull(membership)
        assertEquals(UserRole.ADMIN, membership?.role)
        assertTrue(membership!!.canRefund)
        assertTrue(membership.canVoid)

        // Verify setup is complete
        assertTrue(setupRepository.isSetupComplete())
    }

    @Test
    fun testSetupSameOperationReplaysSameIdsAndRejectsChangedRequest() = runBlocking {
        val operationId = OperationId.generate()

        val params = SetupParams(
            businessName = "Replay Business",
            branchName = "Replay Branch",
            branchCode = "RP1",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 500,
            adminDisplayName = "Replay Admin",
            adminUsername = "replay_admin",
            adminPasswordPlain = "ReplayPassword123",
            languageCode = "en"
        )

        val first = setupRepository.executeSetup(params, operationId)
        assertTrue(first is CommandResult.Success)

        val firstSuccess = first as CommandResult.Success
        assertFalse(firstSuccess.isIdempotentReplay)

        val second = setupRepository.executeSetup(params, operationId)
        assertTrue(second is CommandResult.Success)

        val secondSuccess = second as CommandResult.Success
        assertTrue(secondSuccess.isIdempotentReplay)

        assertEquals(firstSuccess.data.businessId, secondSuccess.data.businessId)
        assertEquals(firstSuccess.data.branchId, secondSuccess.data.branchId)
        assertEquals(firstSuccess.data.adminUserId, secondSuccess.data.adminUserId)

        val changedDetails = setupRepository.executeSetup(
            params.copy(businessName = "Changed Business"),
            operationId
        )
        assertTrue(changedDetails is CommandResult.Conflict)

        val changedPassword = setupRepository.executeSetup(
            params.copy(adminPasswordPlain = "DifferentPassword123"),
            operationId
        )
        assertTrue(changedPassword is CommandResult.Conflict)
    }

    @Test
    fun testFirstRunSetupStoresAndPreservesCountryCurrencyTimezoneAndAdmin() = runBlocking {
        val params = SetupParams(
            businessName = "Muscat Express Traders",
            branchName = "Seeb Main Branch",
            branchCode = "SEB",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 500,
            adminDisplayName = "Admin Said",
            adminUsername = "said_admin",
            adminPasswordPlain = "OmanSecure2026",
            languageCode = "ar"
        )

        val setupResult = (setupRepository.executeSetup(params) as CommandResult.Success).data

        val business = database.businessBranchDao().getBusinessById(setupResult.businessId.value)
        assertNotNull(business)
        assertEquals("Muscat Express Traders", business?.name)

        val branch = database.businessBranchDao().getBranchById(setupResult.branchId.value)
        assertNotNull(branch)
        assertEquals("SEB", branch?.code)
        assertEquals("OM", branch?.countryCode)
        assertEquals("OMR", branch?.currencyCode)
        assertEquals(3, branch?.currencyDecimalPlaces)
        assertEquals("Asia/Muscat", branch?.timezoneId)

        val admin = database.userDao().getUserById(setupResult.adminUserId.value)
        assertNotNull(admin)
        assertEquals("Admin Said", admin?.displayName)
        assertEquals("said_admin", admin?.username)
        assertEquals(UserRole.ADMIN, admin?.role)
        assertEquals("ar", admin?.languageCode)
    }

    @Test
    fun testCreateCashierSameOperationReplaysSameIdAndRejectsChangedRequest() = runBlocking {
        val setupParams = SetupParams(
            businessName = "Cashier Replay Business",
            branchName = "Main Branch",
            branchCode = "CR1",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Replay Owner",
            adminUsername = "replay_owner",
            adminPasswordPlain = "ReplayOwner123",
            languageCode = "en"
        )

        val setupResult =
            (setupRepository.executeSetup(setupParams) as CommandResult.Success).data

        val operationId = OperationId.generate()

        val params = CreateCashierParams(
            businessId = setupResult.businessId.value,
            branchId = setupResult.branchId.value,
            adminUserId = setupResult.adminUserId.value,
            displayName = "Replay Cashier",
            username = "replay_cashier",
            pin = "123456",
            preferredLanguage = "en",
            permissions = CashierPermissionsConfig(
                canCreateSale = true,
                canAcceptCash = true,
                canRefund = false,
                canVoid = false
            )
        )

        val first = cashierManagementRepository.createCashier(params, operationId)
        assertTrue(first is CommandResult.Success)

        val firstSuccess = first as CommandResult.Success
        assertFalse(firstSuccess.isIdempotentReplay)

        val second = cashierManagementRepository.createCashier(params, operationId)
        assertTrue(second is CommandResult.Success)

        val secondSuccess = second as CommandResult.Success
        assertTrue(secondSuccess.isIdempotentReplay)
        assertEquals(firstSuccess.data, secondSuccess.data)

        val cashiers =
            database.userDao().getCashiers(setupResult.businessId.value)
        assertEquals(1, cashiers.size)

        val changedPermissions = cashierManagementRepository.createCashier(
            params.copy(
                permissions = params.permissions.copy(canVoid = true)
            ),
            operationId
        )
        assertTrue(changedPermissions is CommandResult.Conflict)

        val changedPin = cashierManagementRepository.createCashier(
            params.copy(pin = "654321"),
            operationId
        )
        assertTrue(changedPin is CommandResult.Conflict)
    }

    @Test
    fun testPersistentPinThrottlingLockoutAcrossAppRestart() = runBlocking {
        val prefs = context.getSharedPreferences("test_pin_throttler_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val throttler1 = PinThrottler(context = context, prefs = prefs, maxAttempts = 5, lockoutDurationMillis = 30_000L)
        val username = "cashier_sam"

        // Fail 5 times to trigger lockout
        for (i in 1..5) {
            throttler1.recordFailure(username)
        }
        assertTrue("Throttler 1 should be locked out", throttler1.isLockedOut(username))

        // Simulate app restart by creating a new PinThrottler instance with same prefs
        val throttler2 = PinThrottler(context = context, prefs = prefs, maxAttempts = 5, lockoutDurationMillis = 30_000L)
        assertTrue("Throttler 2 (after restart) MUST still be locked out", throttler2.isLockedOut(username))
        assertTrue("Remaining lockout seconds > 0", throttler2.getRemainingLockoutSeconds(username) > 0L)

        // Reset/success clears lockout state across instances
        throttler2.recordSuccess(username)
        assertFalse("Lockout should be cleared after success/reset", throttler2.isLockedOut(username))

        val throttler3 = PinThrottler(context = context, prefs = prefs, maxAttempts = 5, lockoutDurationMillis = 30_000L)
        assertFalse("Throttler 3 should see lockout cleared", throttler3.isLockedOut(username))
    }

    @Test
    fun testResolvedAccountThrottleIsSharedAcrossEquivalentPhoneInputs() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Throttle Shop",
            branchName = "Main",
            branchCode = "THR",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Throttle Admin",
            adminUsername = "throttle_admin",
            adminPhone = "91234567",
            adminPasswordPlain = "ThrottleAdminPass123",
            languageCode = "en"
        )) as CommandResult.Success).data

        val prefs = context.getSharedPreferences(
            "test_account_scoped_throttling",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val throttledAuth = AuthRepository(
            database,
            PinThrottler(context = context, prefs = prefs),
            LanguageManager(context),
            trustedSessionStore
        )

        assertTrue(
            throttledAuth.loginAdmin("+968 9123 4567", "wrong-password")
                is AuthResult.InvalidCredentials
        )
        assertTrue(
            throttledAuth.loginAdmin("00968 91234567", "ThrottleAdminPass123")
                is AuthResult.Success
        )
        assertEquals(
            0L,
            throttledAuth.getLoginLockoutRemaining("ADMIN", "+96891234567")
        )

        repeat(4) { index ->
            val input = if (index % 2 == 0) "+968 9123 4567" else "00968 91234567"
            assertTrue(
                throttledAuth.loginAdmin(input, "wrong-password")
                    is AuthResult.InvalidCredentials
            )
        }
        assertTrue(
            throttledAuth.loginAdmin("+96891234567", "wrong-password")
                is AuthResult.LockedOut
        )
        assertTrue(
            throttledAuth.getLoginLockoutRemaining("ADMIN", "00968 91234567") > 0L
        )
        assertEquals(setup.adminUserId.value, database.userDao().getFirstAdmin(
            setup.businessId.value
        )?.id)
    }

    @Test
    fun testResolvedLoginReturnsMaximumPreAndAccountLockout() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Maximum Lockout Shop",
            branchName = "Main",
            branchCode = "MAX",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Maximum Admin",
            adminUsername = "maximum_admin",
            adminPhone = "91234567",
            adminPasswordPlain = "MaximumAdminPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
        val prefs = context.getSharedPreferences("test_maximum_lockout", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val throttler = PinThrottler(context, prefs, maxAttempts = 1, lockoutDurationMillis = 30_000L)
        val auth = AuthRepository(database, throttler, LanguageManager(context), trustedSessionStore)
        val preKey = "pre:v1:ADMIN:${setup.businessId.value}:${setup.branchId.value}:PHONE:+96891234567"
        val accountKey = "acct:v1:${setup.businessId.value}:${setup.adminUserId.value}"
        val now = System.currentTimeMillis()
        throttler.recordFailure(preKey, currentTimeMillis = now - 10_000L)
        throttler.recordFailure(accountKey, currentTimeMillis = now)
        val preRemaining = throttler.getRemainingLockoutSeconds(preKey, currentTimeMillis = now)
        val accountRemaining = throttler.getRemainingLockoutSeconds(accountKey, currentTimeMillis = now)
        assertTrue(accountRemaining > preRemaining)

        val result = auth.loginAdmin("+968 9123 4567", "wrong-password")
        assertTrue(result is AuthResult.LockedOut)
        val returnedRemaining = (result as AuthResult.LockedOut).remainingSeconds
        assertTrue(returnedRemaining >= accountRemaining - 1L)
        assertTrue(returnedRemaining >= preRemaining + 5L)
    }

    @Test
    fun testCashierEquivalentPhonesShareCanonicalAccountBudget() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Cashier Throttle Shop",
            branchName = "Main",
            branchCode = "CASH",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Cashier Owner",
            adminUsername = "cashier_owner",
            adminPasswordPlain = "CashierOwnerPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
        val cashier = (cashierManagementRepository.createCashier(CreateCashierParams(
            businessId = setup.businessId.value,
            branchId = setup.branchId.value,
            adminUserId = setup.adminUserId.value,
            displayName = "Phone Cashier",
            username = "phone_cashier",
            phone = "92345678",
            pin = "123456",
            preferredLanguage = "en"
        )) as CommandResult.Success).data
        val prefs = context.getSharedPreferences("test_cashier_account_budget", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val auth = AuthRepository(
            database,
            PinThrottler(context, prefs, maxAttempts = 3, lockoutDurationMillis = 30_000L),
            LanguageManager(context),
            trustedSessionStore
        )
        repeat(2) { index ->
            val input = if (index == 0) "+968 9234 5678" else "00968 92345678"
            assertTrue(auth.loginCashier(input, "999999") is AuthResult.InvalidCredentials)
        }
        val locked = auth.loginCashier("+96892345678", "999999")
        assertTrue(locked is AuthResult.LockedOut)
        assertTrue((locked as AuthResult.LockedOut).remainingSeconds > 0L)
        assertTrue(auth.getLoginLockoutRemaining("CASHIER", "00968 92345678") > 0L)
        assertEquals(cashier.value, database.userDao().getUserByUsername(setup.businessId.value, "phone_cashier")?.id)
    }

    @Test
    fun testUnknownLoginFailuresUsePreResolutionBudget() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Unknown Throttle Shop",
            branchName = "Main",
            branchCode = "UNK",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Unknown Owner",
            adminUsername = "unknown_owner",
            adminPasswordPlain = "UnknownOwnerPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
        val prefs = context.getSharedPreferences("test_unknown_pre_budget", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val throttler = PinThrottler(context, prefs, maxAttempts = 2, lockoutDurationMillis = 30_000L)
        val auth = AuthRepository(database, throttler, LanguageManager(context), trustedSessionStore)
        assertTrue(auth.loginCashier("unknown_cashier", "999999") is AuthResult.InvalidCredentials)
        val locked = auth.loginCashier("unknown_cashier", "999999")
        assertTrue(locked is AuthResult.LockedOut)
        assertTrue(throttler.isLockedOut("pre:v1:CASHIER:${setup.businessId.value}:${setup.branchId.value}:USERNAME:unknown_cashier"))
        assertNull(database.userDao().getUserByUsername(setup.businessId.value, "unknown_cashier"))
    }

    @Test
    fun testResolvedFailureUsesAccountKeyWithoutPreKeyBudget() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Resolved Failure Shop",
            branchName = "Main",
            branchCode = "RES",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Resolved Owner",
            adminUsername = "resolved_owner",
            adminPhone = "93456789",
            adminPasswordPlain = "ResolvedOwnerPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
        val prefs = context.getSharedPreferences("test_resolved_account_only", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val throttler = PinThrottler(context, prefs, maxAttempts = 2, lockoutDurationMillis = 30_000L)
        val auth = AuthRepository(database, throttler, LanguageManager(context), trustedSessionStore)
        val preKey = "pre:v1:ADMIN:${setup.businessId.value}:${setup.branchId.value}:PHONE:+96893456789"
        val accountKey = "acct:v1:${setup.businessId.value}:${setup.adminUserId.value}"
        assertTrue(auth.loginAdmin("+96893456789", "wrong-password") is AuthResult.InvalidCredentials)
        assertFalse(throttler.isLockedOut(preKey))
        assertFalse(throttler.isLockedOut(accountKey))
        assertTrue(auth.loginAdmin("+96893456789", "wrong-password") is AuthResult.LockedOut)
        assertTrue(throttler.isLockedOut(accountKey))
        assertFalse(throttler.isLockedOut(preKey))
    }

    @Test
    fun testSuccessfulResolvedLoginClearsAccountAndCurrentPreKey() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Success Reset Shop",
            branchName = "Main",
            branchCode = "RST",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Reset Owner",
            adminUsername = "reset_owner",
            adminPhone = "94567890",
            adminPasswordPlain = "ResetOwnerPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
        val prefs = context.getSharedPreferences("test_success_reset_keys", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val throttler = PinThrottler(context, prefs, maxAttempts = 5, lockoutDurationMillis = 30_000L)
        val auth = AuthRepository(database, throttler, LanguageManager(context), trustedSessionStore)
        val preKey = "pre:v1:ADMIN:${setup.businessId.value}:${setup.branchId.value}:PHONE:+96894567890"
        val accountKey = "acct:v1:${setup.businessId.value}:${setup.adminUserId.value}"
        throttler.recordFailure(preKey)
        throttler.recordFailure(accountKey)
        assertTrue(auth.loginAdmin("+968 9456 7890", "ResetOwnerPass123") is AuthResult.Success)
        assertFalse(throttler.isLockedOut(preKey))
        assertFalse(throttler.isLockedOut(accountKey))
        assertFalse(prefs.contains("count_$preKey"))
        assertFalse(prefs.contains("count_$accountKey"))
        assertFalse(prefs.contains("lockout_$preKey"))
        assertFalse(prefs.contains("lockout_$accountKey"))
        assertEquals(0L, auth.getLoginLockoutRemaining("ADMIN", "00968 94567890"))
    }

    @Test
    fun testAdminCreatesCashierWithSixDigitPinAndBranchMembership() = runBlocking {
        // First run setup
        val setupParams = SetupParams(
            businessName = "Shop 1",
            branchName = "Branch 1",
            branchCode = "BR1",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 500,
            adminDisplayName = "Admin",
            adminUsername = "admin1",
            adminPasswordPlain = "pass1234",
            languageCode = "en"
        )
        val setupResult = (setupRepository.executeSetup(setupParams) as CommandResult.Success).data

        // Admin creates a Cashier
        val cashierParams = CreateCashierParams(
            businessId = setupResult.businessId.value,
            branchId = setupResult.branchId.value,
            adminUserId = setupResult.adminUserId.value,
            displayName = "Cashier Rahul",
            username = "rahul",
            pin = "654321",
            preferredLanguage = "hi",
            permissions = CashierPermissionsConfig(
                canCreateSale = true,
                canAcceptCash = true,
                canAcceptOnline = true,
                canCreateCreditSale = true,
                canRefund = false,
                canVoid = false
            )
        )

        val cashierResult = cashierManagementRepository.createCashier(cashierParams)
        assertTrue("Cashier creation should succeed", cashierResult is CommandResult.Success)

        val cashierId = (cashierResult as CommandResult.Success).data
        val cashierUser = database.userDao().getUserById(cashierId.value)
        assertNotNull(cashierUser)
        assertEquals("Cashier Rahul", cashierUser?.displayName)
        assertEquals("rahul", cashierUser?.username)
        assertEquals(UserRole.CASHIER, cashierUser?.role)
        assertEquals("hi", cashierUser?.languageCode)
        assertNotEquals("654321", cashierUser?.credentialHash) // Salted hash, not plaintext!

        val membership = database.userDao().getMembership(cashierId.value, setupResult.branchId.value)
        assertNotNull(membership)
        assertTrue(membership!!.canCreateSale)
        assertTrue(membership.canCreateCreditSale)
        assertFalse(membership.canRefund)
        assertFalse(membership.canVoid)
    }

    @Test
    fun testAuthLoginFlowsForAdminAndCashier() = runBlocking {
        // Setup
        val setupParams = SetupParams(
            businessName = "Shop Auth",
            branchName = "Branch A",
            branchCode = "BRA",
            countryCode = "IN",
            currencyCode = "INR",
            currencyDecimalPlaces = 2,
            currencySymbol = "Ã¢â€šÂ¹",
            timezoneId = "Asia/Kolkata",
            taxRoundingMode = TaxRoundingMode.HALF_EVEN,
            defaultTaxRateBps = 1800,
            adminDisplayName = "Admin User",
            adminUsername = "admin_user",
            adminPasswordPlain = "AdminPassSecret",
            languageCode = "en"
        )
        val setupResult = (setupRepository.executeSetup(setupParams) as CommandResult.Success).data

        // Cashier
        cashierManagementRepository.createCashier(
            CreateCashierParams(
                businessId = setupResult.businessId.value,
                branchId = setupResult.branchId.value,
                adminUserId = setupResult.adminUserId.value,
                displayName = "Cashier Bob",
                username = "bob",
                pin = "112233"
            )
        )

        // Admin login success
        val adminLogin = authRepository.loginAdmin("admin_user", "AdminPassSecret")
        assertTrue(adminLogin is AuthResult.Success)
        assertEquals((adminLogin as AuthResult.Success).session.user.id, trustedSessionStore.load()?.userId)

        // Admin login wrong password
        val adminFail = authRepository.loginAdmin("admin_user", "WrongPass")
        assertTrue(adminFail is AuthResult.InvalidCredentials)

        // Cashier login success
        val cashierLogin = authRepository.loginCashier("bob", "112233")
        assertTrue(cashierLogin is AuthResult.Success)
        assertEquals((cashierLogin as AuthResult.Success).session.user.id, trustedSessionStore.load()?.userId)

        // Cashier login wrong PIN
        val cashierFail = authRepository.loginCashier("bob", "999999")
        assertTrue(cashierFail is AuthResult.InvalidCredentials)
    }

    @Test
    fun testOperationIdempotencyAndFingerprintIntegrity() = runBlocking {
        val opId = OperationId.generate()
        var counter = 0

        val command = object : OperationCommand {
            override val operationId = opId
            override val businessId = "biz-1"
            override val branchId = "branch-1"
            override val operationType = OperationType.CREATE_BUSINESS
            override val subjectType = SubjectType.BUSINESS
            override val subjectId = "biz-1"
            override val transactionId = null
            override val actorUserId = "admin-1"
            override val deviceId = "DEV-1"
            override val occurredAt = 1000L
            override val requestFingerprint = "fingerprint-secure"
            override val intentPayload = "Payload content"
        }

        // 1st run
        val res1 = commandExecutor.execute(
            command = command,
            replayResolver = { op ->
                op.resultReferenceId
                    ?: throw IllegalStateException("Replay failed: missing business result reference")
            }
        ) {
            counter++
            database.businessBranchDao().insertBusiness(BusinessEntity("biz-1", "Test", 1000L, 1000L))
            Pair("biz-1", Pair("BUSINESS", "biz-1"))
        }
        assertTrue(res1 is CommandResult.Success)
        assertFalse((res1 as CommandResult.Success).isIdempotentReplay)
        assertEquals(1, counter)

        // 2nd run with same OperationId + same fingerprint
        val res2 = commandExecutor.execute(
            command = command,
            replayResolver = { op ->
                op.resultReferenceId
                    ?: throw IllegalStateException("Replay failed: missing business result reference")
            }
        ) {
            counter++
            Pair("biz-1", Pair("BUSINESS", "biz-1"))
        }
        assertTrue(res2 is CommandResult.Success)
        assertTrue((res2 as CommandResult.Success).isIdempotentReplay)
        assertEquals(1, counter) // Not executed again!

        // Check stored operation status
        val op = database.operationDao().getOperationById(opId.value)
        assertNotNull(op)
        assertEquals(OperationStatus.SUCCEEDED, op?.status)
    }


    @Test
    fun testSucceededOperationCannotBeDowngradedToFailed() = runBlocking {
        val operationId = OperationId.generate().value
        val fingerprint = "terminal-state-guard"

        val pending =
            com.dailymoneyregister.core.database.entity.OperationEntity(
                id = operationId,
                businessId = "biz-terminal-test",
                branchId = null,
                operationType = OperationType.CREATE_BUSINESS,
                subjectType = SubjectType.BUSINESS,
                subjectId = "biz-terminal-test",
                transactionId = null,
                actorUserId = "admin-terminal-test",
                deviceId = "terminal-test-device",
                occurredAt = System.currentTimeMillis(),
                requestFingerprint = fingerprint,
                commandSchemaVersion = 1,
                intentPayload = "terminal-test-payload",
                status = OperationStatus.PENDING
            )

        database.operationDao()
            .insertOperation(pending)

        /*
         * First transition:
         * PENDING -> SUCCEEDED must work.
         */
        val successRows =
            database.operationDao()
                .markSucceededIfPending(
                    operationId = operationId,
                    requestFingerprint = fingerprint,
                    resultReferenceType = "BUSINESS",
                    resultReferenceId = "biz-terminal-test"
                )

        assertEquals(
            "PENDING -> SUCCEEDED should change exactly one row.",
            1,
            successRows
        )

        /*
         * Dangerous transition attempt:
         * SUCCEEDED -> FAILED must be rejected by SQL itself.
         */
        val failedRows =
            database.operationDao()
                .markFailedIfPending(
                    operationId = operationId,
                    requestFingerprint = fingerprint,
                    reason = "Competing execution failed"
                )

        assertEquals(
            "SUCCEEDED -> FAILED must change zero rows.",
            0,
            failedRows
        )

        val stored =
            database.operationDao()
                .getOperationById(operationId)

        assertNotNull(stored)

        assertEquals(
            OperationStatus.SUCCEEDED,
            stored?.status
        )

        assertEquals(
            "BUSINESS",
            stored?.resultReferenceType
        )

        assertEquals(
            "biz-terminal-test",
            stored?.resultReferenceId
        )

        assertNull(
            "A successful operation must not receive a later failure reason.",
            stored?.reason
        )
    }
    @Test
    fun testLanguageManagerRtlAndLanguageSwitch() {
        val languageManager = LanguageManager(context)

        // English -> LTR
        languageManager.setLanguage("en")
        assertEquals("en", languageManager.currentLanguage.value.code)
        assertFalse(languageManager.isRtl)

        // Arabic -> RTL
        languageManager.setLanguage("ar")
        assertEquals("ar", languageManager.currentLanguage.value.code)
        assertTrue(languageManager.isRtl)

        // Urdu -> RTL
        languageManager.setLanguage("ur")
        assertEquals("ur", languageManager.currentLanguage.value.code)
        assertTrue(languageManager.isRtl)

        // Hindi -> LTR
        languageManager.setLanguage("hi")
        assertEquals("hi", languageManager.currentLanguage.value.code)
        assertFalse(languageManager.isRtl)
    }

    @Test
    fun testTargetedLanguageUpdatePreservesUserAccountFieldsAndScopesBusiness() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Language DAO Shop",
            branchName = "Main",
            branchCode = "LANG",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Language Owner",
            adminUsername = "language_owner",
            adminPasswordPlain = "LanguageOwnerPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
        val original = UserAccountEntity(
            id = "language-user-fixed",
            identityId = "identity-fixed",
            businessId = setup.businessId.value,
            displayName = "Fixed Display Name",
            username = "fixed_username",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "fixed-credential-hash",
            credentialSalt = "fixed-credential-salt",
            languageCode = "en",
            isActive = false,
            mustChangePin = true,
            createdAt = 111_111L,
            updatedAt = 222_222L
        )
        database.userDao().insertUser(original)

        val newUpdatedAt = 999_999L
        assertEquals(
            1,
            database.userDao().updateLanguageForUser(
                userId = original.id,
                businessId = original.businessId,
                languageCode = "ar",
                updatedAt = newUpdatedAt
            )
        )
        val updated = database.userDao().getUserById(original.id)!!
        assertEquals("ar", updated.languageCode)
        assertEquals(newUpdatedAt, updated.updatedAt)
        assertEquals(original.identityId, updated.identityId)
        assertEquals(original.businessId, updated.businessId)
        assertEquals(original.displayName, updated.displayName)
        assertEquals(original.username, updated.username)
        assertEquals(original.role, updated.role)
        assertEquals(original.authType, updated.authType)
        assertEquals(original.credentialHash, updated.credentialHash)
        assertEquals(original.credentialSalt, updated.credentialSalt)
        assertEquals(original.isActive, updated.isActive)
        assertEquals(original.mustChangePin, updated.mustChangePin)
        assertEquals(original.createdAt, updated.createdAt)

        val rejectedUpdatedAt = 1_111_111L
        assertEquals(
            0,
            database.userDao().updateLanguageForUser(
                userId = original.id,
                businessId = "wrong-business",
                languageCode = "hi",
                updatedAt = rejectedUpdatedAt
            )
        )
        val afterRejectedWrite = database.userDao().getUserById(original.id)!!
        assertEquals("ar", afterRejectedWrite.languageCode)
        assertEquals(newUpdatedAt, afterRejectedWrite.updatedAt)
        assertEquals(updated, afterRejectedWrite)
    }

    @Test
    fun testUserRereadByIdInBusinessIsTenantScopedAndReadOnly() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Tenant Read Shop",
            branchName = "Main",
            branchCode = "TEN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Tenant Owner",
            adminUsername = "tenant_owner",
            adminPasswordPlain = "TenantOwnerPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
        val before = database.userDao().getUserById(setup.adminUserId.value)!!

        val found = database.userDao().getUserByIdInBusiness(
            userId = setup.adminUserId.value,
            businessId = setup.businessId.value
        )
        assertNotNull(found)
        assertEquals(setup.adminUserId.value, found?.id)
        assertEquals(setup.businessId.value, found?.businessId)

        assertNull(
            database.userDao().getUserByIdInBusiness(
                userId = setup.adminUserId.value,
                businessId = "wrong-business"
            )
        )
        val after = database.userDao().getUserById(setup.adminUserId.value)
        assertEquals(before, after)
    }

    @Test
    fun testSettingsRepositoryPersistsCanonicalLanguageAndReturnsAuthoritativeUser() = runBlocking {
        val setup = createLanguageTestSetup()
        val beforeUser = database.userDao().getUserById(setup.adminUserId.value)!!
        val beforeBusiness = database.businessBranchDao().getBusinessById(setup.businessId.value)!!
        val beforeBranch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val repository = SettingsRepository(database.userDao())

        val result = repository.updateLanguage(
            userId = beforeUser.id,
            businessId = beforeUser.businessId,
            requestedLanguageCode = "AR"
        )

        assertTrue(result is SettingsLanguageResult.Success)
        val authoritative = (result as SettingsLanguageResult.Success).user
        val persisted = database.userDao().getUserById(beforeUser.id)!!
        assertEquals("ar", authoritative.languageCode)
        assertEquals(authoritative, persisted)
        assertEquals("ar", persisted.languageCode)
        assertNotEquals(beforeUser.updatedAt, persisted.updatedAt)
        assertEquals(beforeUser.copy(languageCode = "ar", updatedAt = persisted.updatedAt), persisted)
        assertEquals(beforeBusiness, database.businessBranchDao().getBusinessById(beforeBusiness.id))
        assertEquals(beforeBranch, database.businessBranchDao().getBranchById(beforeBranch.id))
    }

    @Test
    fun testSettingsRepositoryRejectsUnsupportedLanguageWithoutChangingDatabase() = runBlocking {
        val setup = createLanguageTestSetup()
        val before = database.userDao().getUserById(setup.adminUserId.value)!!
        val repository = SettingsRepository(database.userDao())

        val result = repository.updateLanguage(
            userId = before.id,
            businessId = before.businessId,
            requestedLanguageCode = "xx"
        )

        assertTrue(result is SettingsLanguageResult.UnsupportedLanguage)
        assertEquals(before, database.userDao().getUserById(before.id))
    }

    @Test
    fun testSettingsRepositoryRejectsWrongBusinessWithoutChangingDatabase() = runBlocking {
        val setup = createLanguageTestSetup()
        val before = database.userDao().getUserById(setup.adminUserId.value)!!
        val repository = SettingsRepository(database.userDao())

        val result = repository.updateLanguage(
            userId = before.id,
            businessId = "wrong-business",
            requestedLanguageCode = "hi"
        )

        assertTrue(result is SettingsLanguageResult.PersistenceFailure)
        assertEquals(before, database.userDao().getUserById(before.id))
    }

    private suspend fun createLanguageTestSetup(): SetupResult {
        return (setupRepository.executeSetup(SetupParams(
            businessName = "Settings Repository Shop",
            branchName = "Main",
            branchCode = "SET",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Settings Owner",
            adminUsername = "settings_owner",
            adminPasswordPlain = "SettingsOwnerPass123",
            languageCode = "en"
        )) as CommandResult.Success).data
    }

    private fun operationFixture(
        id: String = OperationId.generate().value,
        status: OperationStatus = OperationStatus.PENDING
    ) = com.dailymoneyregister.core.database.entity.OperationEntity(
        id = id,
        businessId = "biz-protection",
        branchId = "branch-protection",
        operationType = OperationType.CREATE_BUSINESS,
        subjectType = SubjectType.BUSINESS,
        subjectId = "subject-protection",
        transactionId = null,
        actorUserId = "actor-protection",
        deviceId = "device-protection",
        occurredAt = 1000L,
        requestFingerprint = "fingerprint-protection",
        commandSchemaVersion = 1,
        intentPayload = "payload-protection",
        status = status
    )

    @Test
    fun testOperationDurableIdentityDeleteAndDuplicateAreDatabaseProtected() = runBlocking {
        val operation = operationFixture()
        database.operationDao().insertOperation(operation)

        val identityUpdates = listOf(
            "id = 'other-id'",
            "business_id = 'other-business'",
            "branch_id = 'other-branch'",
            "operation_type = 'CREATE_BRANCH'",
            "subject_type = 'BRANCH'",
            "subject_id = 'other-subject'",
            "transaction_id = 'other-transaction'",
            "actor_user_id = 'other-actor'",
            "device_id = 'other-device'",
            "occurred_at = 2000",
            "request_fingerprint = 'other-fingerprint'",
            "command_schema_version = 2",
            "intent_payload = 'other-payload'"
        )
        identityUpdates.forEach { assignment ->
            assertThrows(RuntimeException::class.java) {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE operations SET $assignment WHERE id = ?",
                    arrayOf(operation.id)
                )
            }
        }
        assertThrows(RuntimeException::class.java) {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM operations WHERE id = ?", arrayOf(operation.id)
            )
        }
        assertThrows(RuntimeException::class.java) {
            database.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO operations (id, business_id, branch_id, operation_type, subject_type, subject_id, transaction_id, actor_user_id, device_id, occurred_at, request_fingerprint, command_schema_version, intent_payload, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(operation.id, operation.businessId, operation.branchId, "CREATE_BUSINESS", "BUSINESS", operation.subjectId, null, operation.actorUserId, operation.deviceId, operation.occurredAt, operation.requestFingerprint, operation.commandSchemaVersion, operation.intentPayload, "PENDING")
            )
        }
        Unit
    }

    @Test
    fun testOperationRecursiveTriggersAndIgnoreInsertBehavior() = runBlocking {
        database.openHelper.readableDatabase.query("PRAGMA recursive_triggers").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        val operation = operationFixture()
        database.operationDao().insertOperation(operation)

        assertThrows(RuntimeException::class.java) {
            database.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO operations (id, business_id, branch_id, operation_type, subject_type, subject_id, transaction_id, actor_user_id, device_id, occurred_at, request_fingerprint, command_schema_version, intent_payload, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(operation.id, operation.businessId, operation.branchId, "CREATE_BUSINESS", "BUSINESS", operation.subjectId, null, operation.actorUserId, operation.deviceId, operation.occurredAt, operation.requestFingerprint, operation.commandSchemaVersion, "replacement-payload", "PENDING")
            )
        }

        val ignored = database.operationDao().insertOperationIfAbsent(
            operation.copy(intentPayload = "ignored-payload")
        )
        assertEquals(-1L, ignored)
        assertEquals(operation, database.operationDao().getOperationById(operation.id))
    }

    @Test
    fun testOperationAllowedLifecycleTransitionsAreDatabaseProtected() = runBlocking {
        suspend fun transition(from: OperationStatus, to: OperationStatus) {
            val operation = operationFixture(status = from)
            database.operationDao().insertOperation(operation)
            val resultType = if (to == OperationStatus.SUCCEEDED) "BUSINESS" else null
            val resultId = if (to == OperationStatus.SUCCEEDED) "result" else null
            val reason = if (to == OperationStatus.FAILED || to == OperationStatus.REQUIRES_RECONCILIATION) "reason" else null
            database.openHelper.writableDatabase.execSQL(
                "UPDATE operations SET status = ?, result_reference_type = ?, result_reference_id = ?, reason = ? WHERE id = ?",
                arrayOf(to.name, resultType, resultId, reason, operation.id)
            )
            assertEquals(to, database.operationDao().getOperationById(operation.id)?.status)
        }
        transition(OperationStatus.PENDING, OperationStatus.SUCCEEDED)
        transition(OperationStatus.PENDING, OperationStatus.FAILED)
        transition(OperationStatus.PENDING, OperationStatus.REQUIRES_RECONCILIATION)
        transition(OperationStatus.REQUIRES_RECONCILIATION, OperationStatus.SUCCEEDED)
        transition(OperationStatus.REQUIRES_RECONCILIATION, OperationStatus.FAILED)
    }

    @Test
    fun testOperationInvalidAndSameStatusUpdatesAreRejected() = runBlocking {
        suspend fun assertRejected(from: OperationStatus, to: OperationStatus) {
            val operation = operationFixture(status = from)
            database.operationDao().insertOperation(operation)
            assertThrows(RuntimeException::class.java) {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE operations SET status = ?, reason = 'changed' WHERE id = ?",
                    arrayOf(to.name, operation.id)
                )
            }
        }
        assertRejected(OperationStatus.SUCCEEDED, OperationStatus.PENDING)
        assertRejected(OperationStatus.SUCCEEDED, OperationStatus.FAILED)
        assertRejected(OperationStatus.FAILED, OperationStatus.PENDING)
        assertRejected(OperationStatus.FAILED, OperationStatus.SUCCEEDED)
        assertRejected(OperationStatus.PENDING, OperationStatus.PENDING)
        assertRejected(OperationStatus.REQUIRES_RECONCILIATION, OperationStatus.REQUIRES_RECONCILIATION)
    }

    @Test
    fun testPhoneAdminLoginAndCashierMustChangePin() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Phone Shop",
            branchName = "Main",
            branchCode = "PH1",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Phone Admin",
            adminUsername = "+96891234567",
            adminPhone = "91234567",
            adminPasswordPlain = "PhoneAdminPass123",
            languageCode = "en"
        )) as CommandResult.Success).data

        val admin = database.userDao().getUserById(setup.adminUserId.value)!!
        val identity = database.userDao().getHumanIdentityById(admin.id)!!
        assertEquals("+96891234567", identity.canonicalPhone)
        assertTrue(admin.credentialHash != "PhoneAdminPass123")

        setupRepository.establishAdminSession(setup)
        assertEquals(admin.id, SessionManager.activeUser?.id)
        SessionManager.clearSession()
        assertTrue(authRepository.loginAdmin("+968 9123 4567", "PhoneAdminPass123") is AuthResult.Success)
        assertTrue(authRepository.loginAdmin("+968 9123 4567", "wrong-password") is AuthResult.InvalidCredentials)

        val cashierResult = cashierManagementRepository.createCashier(
            CreateCashierParams(
                businessId = setup.businessId.value,
                branchId = setup.branchId.value,
                adminUserId = setup.adminUserId.value,
                displayName = "Phone Cashier",
                username = "+96892345678",
                phone = "92345678",
                pin = "123456"
            )
        ) as CommandResult.Success
        val cashier = database.userDao().getUserById(cashierResult.data.value)!!
        assertTrue(cashier.mustChangePin)
        assertTrue(cashier.credentialHash != "123456")
        val cashierLogin = authRepository.loginCashier("00968 92345678", "123456") as AuthResult.Success
        assertTrue(cashierLogin.session.requiresPinChange)
        assertTrue(authRepository.changeCashierPin("654321") is AuthResult.Success)
        assertFalse(database.userDao().getUserById(cashier.id)!!.mustChangePin)
        assertTrue(authRepository.loginCashier("+96892345678", "123456") is AuthResult.InvalidCredentials)
        assertTrue(authRepository.loginCashier("+96892345678", "654321") is AuthResult.Success)
    }

    @Test
    fun testRestrictedCashierCannotExecuteSaleUntilPinChangeEvenWithoutSession() = runBlocking {
        val setup = (setupRepository.executeSetup(SetupParams(
            businessName = "Restricted Sale Shop",
            branchName = "Main",
            branchCode = "RS1",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            adminDisplayName = "Restricted Admin",
            adminUsername = "+96891234567",
            adminPhone = "91234567",
            adminPasswordPlain = "RestrictedAdminPass123",
            languageCode = "en"
        )) as CommandResult.Success).data

        setupRepository.establishAdminSession(setup)
        val cashierId = (cashierManagementRepository.createCashier(
            CreateCashierParams(
                businessId = setup.businessId.value,
                branchId = setup.branchId.value,
                adminUserId = setup.adminUserId.value,
                displayName = "Restricted Cashier",
                username = "+96892345678",
                phone = "92345678",
                pin = "246810",
                permissions = CashierPermissionsConfig(
                    canCreateSale = true,
                    canAcceptCash = true
                )
            )
        ) as CommandResult.Success).data.value

        val cashier = database.userDao().getUserById(cashierId)!!
        val branch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val membership = database.userDao().getMembership(cashierId, branch.id)!!
        val auth = authRepository.loginCashier("+96892345678", "246810") as AuthResult.Success
        assertTrue(cashier.mustChangePin)
        assertTrue(auth.session.requiresPinChange)

        val registerRepository = RegisterRepository(database, commandExecutor)
        val restrictedOperationId = OperationId.generate()
        val restrictedTransactionId = TransactionId.generate()
        val restrictedCommand = CashSaleCommand(
            operationId = restrictedOperationId,
            businessId = setup.businessId.value,
            branchId = branch.id,
            actorUserId = cashierId,
            transactionIdVal = restrictedTransactionId,
            draftState = DraftState(manualAmount = MoneyMinorUnits(1000L)),
            currencyCode = branch.currencyCode,
            currencyDecimalPlaces = branch.currencyDecimalPlaces,
            deviceId = "test-device"
        )

        val restrictedResult = registerRepository.executeCashSale(restrictedCommand, cashier, membership)
        assertTrue(restrictedResult is CommandResult.PermissionDenied)
        assertNull(database.transactionDao().getTransactionById(restrictedTransactionId.value))
        assertTrue(database.transactionDao().getSettlementsForTransaction(restrictedTransactionId.value).isEmpty())
        assertNull(database.operationDao().getOperationById(restrictedOperationId.value))

        SessionManager.clearSession()
        val bypassAttempt = registerRepository.executeCashSale(restrictedCommand, cashier, membership)
        assertTrue(bypassAttempt is CommandResult.PermissionDenied)
        assertNull(database.transactionDao().getTransactionById(restrictedTransactionId.value))
        assertTrue(database.transactionDao().getSettlementsForTransaction(restrictedTransactionId.value).isEmpty())
        assertNull(database.operationDao().getOperationById(restrictedOperationId.value))

        assertTrue(authRepository.loginCashier("+96892345678", "246810") is AuthResult.Success)
        assertTrue(authRepository.changeCashierPin("135790") is AuthResult.Success)
        assertFalse(database.userDao().getUserById(cashierId)!!.mustChangePin)
        assertTrue(authRepository.loginCashier("+96892345678", "135790") is AuthResult.Success)

        val allowedOperationId = OperationId.generate()
        val allowedTransactionId = TransactionId.generate()
        val allowedResult = registerRepository.executeCashSale(
            CashSaleCommand(
                operationId = allowedOperationId,
                businessId = setup.businessId.value,
                branchId = branch.id,
                actorUserId = cashierId,
                transactionIdVal = allowedTransactionId,
                draftState = DraftState(manualAmount = MoneyMinorUnits(1000L)),
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                deviceId = "test-device"
            ),
            database.userDao().getUserById(cashierId)!!,
            database.userDao().getMembership(cashierId, branch.id)
        )
        assertTrue(allowedResult is CommandResult.Success)
        assertNotNull(database.transactionDao().getTransactionById(allowedTransactionId.value))
        assertEquals(1, database.transactionDao().getSettlementsForTransaction(allowedTransactionId.value).size)
        assertTrue(database.operationDao().isOperationSucceeded(allowedOperationId.value))
    }
}




