package com.dailymoneyregister

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.PinThrottler
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionRestorer
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository
import com.dailymoneyregister.feature.admin.data.CreateCashierParams
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.settings.data.SettingsLanguageResult
import com.dailymoneyregister.feature.settings.data.SettingsRepository
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
class TrustedSessionIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var trustedSessionStore: TrustedSessionStore
    private lateinit var setupRepository: SetupRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var cashierRepository: CashierManagementRepository
    private lateinit var restorer: TrustedSessionRestorer

    @Before
    fun setUp() {
        Log.i("BOARDLOCK", "setUp: start")

        context = ApplicationProvider.getApplicationContext()
        Log.i("BOARDLOCK", "setUp: got context")

        trustedSessionStore = TrustedSessionStore(context)
        Log.i("BOARDLOCK", "setUp: created TrustedSessionStore")

        trustedSessionStore.clear()
        Log.i("BOARDLOCK", "setUp: cleared TrustedSessionStore")

        SessionManager.clearSession()
        Log.i("BOARDLOCK", "setUp: cleared SessionManager")

        Log.i("BOARDLOCK", "setUp: before createInMemory")
        database = DmrDatabase.createInMemory(context)
        Log.i("BOARDLOCK", "setUp: after createInMemory")

        val commandExecutor = CommandExecutor(database)
        Log.i("BOARDLOCK", "setUp: created CommandExecutor")

        setupRepository = SetupRepository(
            database,
            commandExecutor,
            DeviceIdentityProvider(context),
            trustedSessionStore
        )
        Log.i("BOARDLOCK", "setUp: created SetupRepository")

        authRepository = AuthRepository(
            database,
            PinThrottler(context = context),
            LanguageManager(context),
            trustedSessionStore
        )
        Log.i("BOARDLOCK", "setUp: created AuthRepository")

        cashierRepository = CashierManagementRepository(
            database,
            commandExecutor,
            DeviceIdentityProvider(context)
        )
        Log.i("BOARDLOCK", "setUp: created CashierManagementRepository")

        restorer = TrustedSessionRestorer(
            database,
            trustedSessionStore,
            LanguageManager(context)
        )
        Log.i("BOARDLOCK", "setUp: done")
    }

    @After
    fun tearDown() {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun authenticationPersistsAdminAndCashierIdsWithoutCredentials() = runBlocking {
        Log.i("BOARDLOCK", "TEST: authenticationPersistsAdminAndCashierIdsWithoutCredentials - start")
        Log.i("BOARDLOCK", "test: before createSetup")
        val setup = createSetup()
        Log.i("BOARDLOCK", "test: after createSetup")

        val insertedAdmin = database.userDao().getUserByUsername(
            setup.businessId.value,
            "admin@example"
        )
        check(insertedAdmin != null) {
            "BOARDLOCK: Admin was NOT inserted into user_accounts"
        }
        Log.i(
            "BOARDLOCK",
            "BOARDLOCK: hash=${insertedAdmin.credentialHash} salt=${insertedAdmin.credentialSalt}"
        )
        setupRepository.establishAdminSession(setup)
        Log.i("BOARDLOCK", "test: after establishAdminSession")
        val admin = authRepository.loginAdmin("admin@example", "AdminPass123")
        Log.i("BOARDLOCK", "test: after loginAdmin, result=$admin")

       assertTrue(
       "loginAdmin returned: $admin",
       admin is AuthResult.Success)
        val adminIds = trustedSessionStore.load()
        assertNotNull(adminIds)
        assertEquals(setup.businessId.value, adminIds?.businessId)
        assertEquals(setup.branchId.value, adminIds?.branchId)
        assertEquals(setup.adminUserId.value, adminIds?.userId)

        val preferences = context.getSharedPreferences(TrustedSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(
            setOf("business_id", "branch_id", "user_id"),
            preferences.all.keys
        )
        assertTrue(preferences.all.values.none { value ->
            value.toString().contains("password", ignoreCase = true) ||
                value.toString().contains("pin", ignoreCase = true) ||
                value.toString().contains("hash", ignoreCase = true) ||
                value.toString().contains("salt", ignoreCase = true)
        })

        val cashierId = (cashierRepository.createCashier(
            CreateCashierParams(
                businessId = setup.businessId.value,
                branchId = setup.branchId.value,
                adminUserId = setup.adminUserId.value,
                displayName = "Trusted Cashier",
                username = "cashier@example",
                phone = "92345678",
                pin = "246810"
            )
        ) as CommandResult.Success).data

        val cashier = authRepository.loginCashier("92345678", "246810")
        assertTrue(cashier is AuthResult.Success)
        assertEquals(cashierId.value, trustedSessionStore.load()?.userId)
    }

    @Test
    fun restoreRebuildsAdminAndRestrictedCashierFromRoom() = runBlocking {
        Log.i("BOARDLOCK", "TEST: restoreRebuildsAdminAndRestrictedCashierFromRoom - start")
        val setup = createSetup()
        Log.i("BOARDLOCK", "TEST: restoreRebuildsAdminAndRestrictedCashierFromRoom - after createSetup")
        setupRepository.establishAdminSession(setup)
        Log.i("BOARDLOCK", "TEST: restoreRebuildsAdminAndRestrictedCashierFromRoom - after establishAdminSession")
        SessionManager.clearSession()

        val adminRestored = restorer.restore()
        Log.i("BOARDLOCK", "TEST: restoreRebuildsAdminAndRestrictedCashierFromRoom - after first restore")
        assertNotNull(adminRestored)
        assertEquals(setup.adminUserId.value, adminRestored?.user?.id)
        assertEquals(setup.businessId.value, adminRestored?.business?.id)

        val cashierId = (cashierRepository.createCashier(
            CreateCashierParams(
                businessId = setup.businessId.value,
                branchId = setup.branchId.value,
                adminUserId = setup.adminUserId.value,
                displayName = "Restricted Cashier",
                username = "restricted@example",
                phone = "92345678",
                pin = "246810"
            )
        ) as CommandResult.Success).data
        Log.i("BOARDLOCK", "TEST: restoreRebuildsAdminAndRestrictedCashierFromRoom - after createCashier")
        val cashier = authRepository.loginCashier("92345678", "246810") as AuthResult.Success
        Log.i("BOARDLOCK", "TEST: restoreRebuildsAdminAndRestrictedCashierFromRoom - after loginCashier")
        assertTrue(cashier.session.requiresPinChange)
        SessionManager.clearSession()

        val restoredCashier = restorer.restore()
        Log.i("BOARDLOCK", "TEST: restoreRebuildsAdminAndRestrictedCashierFromRoom - after second restore - DONE")
        assertNotNull(restoredCashier)
        assertEquals(cashierId.value, restoredCashier?.user?.id)
        assertTrue(restoredCashier?.requiresPinChange == true)
    }

    @Test
    fun restoreReappliesPersistedUserLanguageFromRoom() = runBlocking {
        Log.i("BOARDLOCK", "TEST: restoreReappliesPersistedUserLanguageFromRoom - start")
        val setup = createSetup()
        val testLanguageManager = LanguageManager(context)
        val originalLanguage = testLanguageManager.currentLanguage.value.code
        restorer = TrustedSessionRestorer(database, trustedSessionStore, testLanguageManager)
        val beforeBusiness = database.businessBranchDao().getBusinessById(setup.businessId.value)!!
        val beforeBranch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val settingsRepository = SettingsRepository(database.userDao(), database.businessBranchDao())
        Log.i("BOARDLOCK", "TEST: restoreReappliesPersistedUserLanguageFromRoom - before try block")

        try {
            setupRepository.establishAdminSession(setup)
            Log.i("BOARDLOCK", "TEST: restoreReappliesPersistedUserLanguageFromRoom - after establishAdminSession")
            val beforeUser = database.userDao().getUserById(setup.adminUserId.value)!!
            assertEquals("en", beforeUser.languageCode)

            val updateResult = settingsRepository.updateLanguage(
                userId = beforeUser.id,
                businessId = beforeUser.businessId,
                requestedLanguageCode = "ar"
            )
            Log.i("BOARDLOCK", "TEST: restoreReappliesPersistedUserLanguageFromRoom - after updateLanguage")
            assertTrue(updateResult is SettingsLanguageResult.Success)
            val persistedUser = database.userDao().getUserById(beforeUser.id)!!
            assertEquals("ar", persistedUser.languageCode)

            testLanguageManager.setLanguage("en")
            assertEquals("en", testLanguageManager.currentLanguage.value.code)
            assertNotNull(trustedSessionStore.load())
            SessionManager.clearSession()

            val restored = restorer.restore() ?: error("Trusted session was not restored")
            Log.i("BOARDLOCK", "TEST: restoreReappliesPersistedUserLanguageFromRoom - after restore - DONE")

            assertEquals(persistedUser, restored.user)
            assertEquals("ar", restored.user.languageCode)
            assertEquals("ar", testLanguageManager.currentLanguage.value.code)
            assertEquals(beforeBusiness, restored.business)
            assertEquals(beforeBranch, restored.branch)
            assertEquals(beforeBranch.currencyCode, restored.branch.currencyCode)
            assertEquals(beforeBranch.currencyDecimalPlaces, restored.branch.currencyDecimalPlaces)
        } finally {
            SessionManager.clearSession()
            testLanguageManager.setLanguage(originalLanguage)
        }
    }

    @Test
    fun invalidRememberedIdentityIsClearedFailClosed() = runBlocking {
        Log.i("BOARDLOCK", "TEST: invalidRememberedIdentityIsClearedFailClosed - start")
        trustedSessionStore.save("missing-business", "missing-branch", "missing-user")
        SessionManager.clearSession()

        assertNull(restorer.restore())
        Log.i("BOARDLOCK", "TEST: invalidRememberedIdentityIsClearedFailClosed - after restore - DONE")
        assertNull(trustedSessionStore.load())
        assertNull(SessionManager.currentSession.value)
    }

    @Test
    fun explicitTrustedStoreClearRevokesRememberedSession() {
        Log.i("BOARDLOCK", "TEST: explicitTrustedStoreClearRevokesRememberedSession - start")
        trustedSessionStore.save("biz", "branch", "user")
        SessionManager.clearSession()
        trustedSessionStore.clear()
        Log.i("BOARDLOCK", "TEST: explicitTrustedStoreClearRevokesRememberedSession - DONE")

        assertNull(trustedSessionStore.load())
        assertFalse(context.getSharedPreferences(TrustedSessionStore.PREFS_NAME, Context.MODE_PRIVATE).contains("password"))
    }

    private suspend fun createSetup(): com.dailymoneyregister.feature.setup.data.SetupResult {
        val result = setupRepository.executeSetup(
            SetupParams(
                businessName = "Trusted Session Business",
                branchName = "Main Branch",
                branchCode = "TRS",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Asia/Muscat",
                taxRoundingMode = TaxRoundingMode.HALF_UP,
                defaultTaxRateBps = 0,
                adminDisplayName = "Trusted Admin",
                adminUsername = "admin@example",
                adminPhone = "91234567",
                adminPasswordPlain = "AdminPass123",
                languageCode = "en"
            )
        )
        return (result as CommandResult.Success).data
    }
}