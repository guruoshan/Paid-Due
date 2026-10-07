package com.dailymoneyregister

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.model.timezone.InvalidTimezoneException
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimezoneIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: DmrDatabase
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var trustedSessionStore: TrustedSessionStore
    private lateinit var setupRepository: SetupRepository

    @Before
    fun setUp() {
        context = com.dailymoneyregister.isolatedAndroidTestContext(ApplicationProvider.getApplicationContext())

        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()

        database = DmrDatabase.createInMemory(context)
        commandExecutor = CommandExecutor(database)

        setupRepository = SetupRepository(
            database = database,
            commandExecutor = commandExecutor,
            deviceIdentityProvider = DeviceIdentityProvider(context),
            trustedSessionStore = trustedSessionStore
        )
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        trustedSessionStore.clear()
        database.close()
    }

    @Test
    fun invalidTimezoneDoesNotFallBackForRecent() {
        val repository = RegisterRepository(
            database = database,
            commandExecutor = commandExecutor
        )

        assertThrows(InvalidTimezoneException::class.java) {
            repository.observeTodayTransactions(
                branchId = "branch-timezone-test",
                timezoneId = "Not/A_Timezone"
            )
        }
    }

    @Test
    fun setupRejectsInvalidTimezoneBeforePersistence() = runBlocking {
        val operationId = OperationId.generate()

        val result = setupRepository.executeSetup(
            params = SetupParams(
                businessName = "Timezone Test Business",
                branchName = "Main",
                branchCode = "TZ1",
                countryCode = "OM",
                currencyCode = "OMR",
                currencyDecimalPlaces = 3,
                currencySymbol = "OMR",
                timezoneId = "Not/A_Timezone",
                taxRoundingMode = TaxRoundingMode.HALF_UP,
                defaultTaxRateBps = 0,
                adminDisplayName = "Timezone Admin",
                adminUsername = "timezone_admin",
                adminPasswordPlain = "TimezoneTestPass123!",
                languageCode = "en"
            ),
            operationId = operationId
        )

        assertTrue(result is CommandResult.Error)
        assertTrue(
            (result as CommandResult.Error).exception is InvalidTimezoneException
        )

        assertNull(
            database.operationDao()
                .getOperationById(operationId.value)
        )

        assertNull(
            database.businessBranchDao()
                .getFirstBusiness()
        )
    }
}

