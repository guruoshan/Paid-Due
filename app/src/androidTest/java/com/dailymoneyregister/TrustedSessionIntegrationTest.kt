package com.dailymoneyregister

import android.content.Context
import androidx.room.Room
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
        context = ApplicationProvider.getApplicationContext()
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()
        SessionManager.clearSession()
        database = Room.inMemoryDatabaseBuilder(context, DmrDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val commandExecutor = CommandExecutor(database)
        setupRepository = SetupRepository(
            database,
            commandExecutor,
            DeviceIdentityProvider(context),
            trustedSessionStore
        )
        authRepository = AuthRepository(
            database,
            PinThrottler(context = context),
            LanguageManager(context),
            trustedSessionStore
        )
        cashierRepository = CashierManagementRepository(
            database,
            commandExecutor,
            DeviceIdentityProvider(context)
        )
        restorer = TrustedSessionRestorer(
            database,
            trustedSessionStore,
            LanguageManager(context)
        )
    }

    @After
    fun tearDown() {
        trustedSessionStore.clear()
        SessionManager.clearSession()
        database.close()
    }

    @Test
    fun authenticationPersistsAdminAndCashierIdsWithoutCredentials() = runBlocking {
        val setup = createSetup()
        setupRepository.establishAdminSession(setup)
        val admin = authRepository.loginAdmin("admin@example", "AdminPass123")
        assertTrue(admin is AuthResult.Success)
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
        val setup = createSetup()
        setupRepository.establishAdminSession(setup)
        SessionManager.clearSession()

        val adminRestored = restorer.restore()
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
        val cashier = authRepository.loginCashier("92345678", "246810") as AuthResult.Success
        assertTrue(cashier.session.requiresPinChange)
        SessionManager.clearSession()

        val restoredCashier = restorer.restore()
        assertNotNull(restoredCashier)
        assertEquals(cashierId.value, restoredCashier?.user?.id)
        assertTrue(restoredCashier?.requiresPinChange == true)
    }

    @Test
    fun invalidRememberedIdentityIsClearedFailClosed() = runBlocking {
        trustedSessionStore.save("missing-business", "missing-branch", "missing-user")
        SessionManager.clearSession()

        assertNull(restorer.restore())
        assertNull(trustedSessionStore.load())
        assertNull(SessionManager.currentSession.value)
    }

    @Test
    fun explicitTrustedStoreClearRevokesRememberedSession() {
        trustedSessionStore.save("biz", "branch", "user")
        SessionManager.clearSession()
        trustedSessionStore.clear()

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
