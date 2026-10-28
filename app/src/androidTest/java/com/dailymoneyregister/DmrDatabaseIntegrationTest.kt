package com.dailymoneyregister

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.command.OperationCommand
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.financial.FinancialReadModelResolver
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.PinThrottler
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.customers.data.CreditRepaymentCommand
import com.dailymoneyregister.feature.customers.export.CustomerExcelExporter
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.CreditSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.setup.data.SetupResult
import com.dailymoneyregister.feature.settings.data.SettingsLanguageResult
import com.dailymoneyregister.feature.settings.data.MerchantQrRepository
import com.dailymoneyregister.feature.settings.data.SettingsRepository
import com.dailymoneyregister.feature.settings.ui.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.inject.Inject

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class DmrDatabaseIntegrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var database: DmrDatabase
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var setupRepository: SetupRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var context: Context
    private lateinit var trustedSessionStore: TrustedSessionStore
    private val ownedViewModelStores = mutableListOf<ViewModelStore>()
    private val ownedViewModels = mutableListOf<SettingsViewModel>()

    private fun trackViewModel(viewModel: SettingsViewModel): SettingsViewModel {
        val store = ViewModelStore()
        store.put("settings-${ownedViewModelStores.size}", viewModel)
        ownedViewModelStores += store
        ownedViewModels += viewModel
        return viewModel
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        context = isolatedAndroidTestContext(ApplicationProvider.getApplicationContext())
        trustedSessionStore = TrustedSessionStore(context)
        trustedSessionStore.clear()

        commandExecutor = CommandExecutor(database)
        setupRepository = SetupRepository(database, commandExecutor, com.dailymoneyregister.core.device.DeviceIdentityProvider(context), trustedSessionStore)
        authRepository = AuthRepository(database, PinThrottler(context = context), LanguageManager(context), trustedSessionStore)
    }

    @After
    fun tearDown() {
        // Clear stores first to request ViewModel-scope cancellation, then join the QR startup
        // coroutine before closing Room. Store clearing requests cancellation but does not join jobs.
        ownedViewModelStores.forEach { it.clear() }
        runBlocking {
            ownedViewModels.forEach { it.cancelInitialMerchantQrLoadAndJoin() }
        }
        ownedViewModels.clear()
        ownedViewModelStores.clear()
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
    fun testRecentTodayCohortIncludesOlderLifecycleActivity() = runBlocking {
        val setup = createLanguageTestSetup()
        val branch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val zoneId = ZoneId.of(branch.timezoneId)
        val startOfToday = LocalDate.of(2026, 9, 20)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val oldTransactionId = "recent-old"
        val todayOlderTransactionId = "recent-today-older"
        val todayNewerTransactionId = "recent-today-newer"
        val actorUserId = setup.adminUserId.value

        fun transaction(id: String, createdAt: Long, amount: Long) = TransactionEntity(
            id = id,
            branchId = branch.id,
            type = TransactionType.SALE,
            subtotalMinor = amount,
            taxRateBps = 0,
            taxableBaseMinor = amount,
            taxAmountMinor = 0L,
            grandTotalMinor = amount,
            currencyCode = branch.currencyCode,
            currencyDecimalPlaces = branch.currencyDecimalPlaces,
            createdByUserId = actorUserId,
            deviceId = "recent-test-device",
            createdAt = createdAt,
            displayRef = "REC-$id"
        )

        fun saleOperation(transactionId: String, operationId: String, occurredAt: Long) = OperationEntity(
            id = operationId,
            businessId = branch.businessId,
            branchId = branch.id,
            operationType = OperationType.RECORD_SALE,
            subjectType = SubjectType.TRANSACTION,
            subjectId = transactionId,
            transactionId = transactionId,
            actorUserId = actorUserId,
            deviceId = "recent-test-device",
            occurredAt = occurredAt,
            requestFingerprint = "fingerprint-$operationId",
            commandSchemaVersion = 1,
            intentPayload = "payload-$operationId",
            status = OperationStatus.SUCCEEDED
        )

        fun settlement(transactionId: String, operationId: String, occurredAt: Long, amount: Long) =
            SettlementEntryEntity(
                id = "settlement-$transactionId",
                operationId = operationId,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transactionId,
                transactionId = transactionId,
                entryPurpose = EntryPurpose.SALE_FUNDING,
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountSignedMinor = amount,
                currencyCode = branch.currencyCode,
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = occurredAt,
                actorUserId = actorUserId
            )

        val oldTransaction = transaction(oldTransactionId, startOfToday - 1_000L, 1_000L)
        val todayOlderTransaction = transaction(todayOlderTransactionId, startOfToday + 1_000L, 2_000L)
        val todayNewerTransaction = transaction(todayNewerTransactionId, startOfToday + 2_000L, 3_000L)
        val oldSaleOperationId = "operation-$oldTransactionId-sale"
        val olderSaleOperationId = "operation-$todayOlderTransactionId-sale"
        val newerSaleOperationId = "operation-$todayNewerTransactionId-sale"
        val oldVoidOperationId = "operation-$oldTransactionId-void"

        database.operationDao().insertOperation(saleOperation(oldTransactionId, oldSaleOperationId, oldTransaction.createdAt))
        database.operationDao().insertOperation(saleOperation(todayOlderTransactionId, olderSaleOperationId, todayOlderTransaction.createdAt))
        database.operationDao().insertOperation(saleOperation(todayNewerTransactionId, newerSaleOperationId, todayNewerTransaction.createdAt))
        database.operationDao().insertOperation(
            OperationEntity(
                id = oldVoidOperationId,
                businessId = branch.businessId,
                branchId = branch.id,
                operationType = OperationType.RECORD_VOID,
                subjectType = SubjectType.TRANSACTION,
                subjectId = oldTransactionId,
                transactionId = oldTransactionId,
                actorUserId = actorUserId,
                deviceId = "recent-test-device",
                occurredAt = startOfToday + 3_000L,
                requestFingerprint = "fingerprint-$oldVoidOperationId",
                commandSchemaVersion = 1,
                intentPayload = "payload-$oldVoidOperationId",
                status = OperationStatus.SUCCEEDED
            )
        )
        database.transactionDao().insertTransaction(oldTransaction)
        database.transactionDao().insertTransaction(todayOlderTransaction)
        database.transactionDao().insertTransaction(todayNewerTransaction)
        database.transactionDao().insertSettlements(
            listOf(
                settlement(oldTransactionId, oldSaleOperationId, oldTransaction.createdAt, 1_000L),
                settlement(todayOlderTransactionId, olderSaleOperationId, todayOlderTransaction.createdAt, 2_000L),
                settlement(todayNewerTransactionId, newerSaleOperationId, todayNewerTransaction.createdAt, 3_000L)
            )
        )
        database.transactionDao().insertVoid(
            VoidEventEntity(
                id = "void-$oldTransactionId",
                transactionId = oldTransactionId,
                operationId = oldVoidOperationId,
                actorUserId = actorUserId,
                occurredAt = startOfToday + 3_000L
            )
        )
        assertEquals(
            startOfToday + 3_000L,
            database.transactionDao().getVoidEvent(oldTransactionId)?.occurredAt
        )

        val records = database.transactionDao()
            .observeTodayFinancialRecords(
                branch.id,
                startOfToday,
                startOfToday + 86_400_000L
            )
            .first()

        assertEquals(
            listOf(todayNewerTransactionId, todayOlderTransactionId, oldTransactionId),
            records.map { it.transaction.id }
        )
        assertEquals(
            -1_000L,
            FinancialReadModelResolver.resolve(
                records.single { it.transaction.id == oldTransactionId },
                startOfToday,
                startOfToday + 86_400_000L
            ).periodFinancialEffect.amount
        )
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
        assertTrue(auth.loginAdmin("unknown_admin", "WrongPassword123") is AuthResult.InvalidCredentials)
        val locked = auth.loginAdmin("unknown_admin", "999999")
        assertTrue(locked is AuthResult.LockedOut)
        assertTrue(throttler.isLockedOut("pre:v1:ADMIN:${setup.businessId.value}:${setup.branchId.value}:USERNAME:unknown_admin"))
        assertNull(database.userDao().getUserByUsername(setup.businessId.value, "unknown_admin"))
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
    fun testAdminLoginFlowWorks() = runBlocking {
        // Setup
        val setupParams = SetupParams(
            businessName = "Shop Auth",
            branchName = "Branch A",
            branchCode = "BRA",
            countryCode = "IN",
            currencyCode = "INR",
            currencyDecimalPlaces = 2,
            currencySymbol = "₹",
            timezoneId = "Asia/Kolkata",
            taxRoundingMode = TaxRoundingMode.HALF_EVEN,
            defaultTaxRateBps = 1800,
            adminDisplayName = "Admin User",
            adminUsername = "admin_user",
            adminPasswordPlain = "AdminPassSecret",
            languageCode = "en"
        )
        val setupResult = (setupRepository.executeSetup(setupParams) as CommandResult.Success).data

        // Admin login success
        val adminLogin = authRepository.loginAdmin("admin_user", "AdminPassSecret")
        assertTrue(adminLogin is AuthResult.Success)
        assertEquals((adminLogin as AuthResult.Success).session.user.id, trustedSessionStore.load()?.userId)

        // Admin login wrong password
        val adminFail = authRepository.loginAdmin("admin_user", "WrongPass")
        assertTrue(adminFail is AuthResult.InvalidCredentials)
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
    fun testOperationFingerprintMismatchIsRejected() = runBlocking {
        val opId = OperationId.generate()
        var counter = 0

        fun command(fingerprint: String): OperationCommand =
            object : OperationCommand {
                override val operationId = opId
                override val businessId = "biz-fingerprint"
                override val branchId = "branch-fingerprint"
                override val operationType = OperationType.CREATE_BUSINESS
                override val subjectType = SubjectType.BUSINESS
                override val subjectId = "biz-fingerprint"
                override val transactionId = null
                override val actorUserId = "admin-fingerprint"
                override val deviceId = "DEV-FINGERPRINT"
                override val occurredAt = 1000L
                override val requestFingerprint = fingerprint
                override val intentPayload = "Payload content"
            }

        val firstResult = commandExecutor.execute(
            command = command("fingerprint-original"),
            replayResolver = { op ->
                op.resultReferenceId
                    ?: throw IllegalStateException("Replay failed: missing business result reference")
            }
        ) {
            counter++
            database.businessBranchDao().insertBusiness(
                BusinessEntity("biz-fingerprint", "Fingerprint Test", 1000L, 1000L)
            )
            Pair("biz-fingerprint", Pair("BUSINESS", "biz-fingerprint"))
        }
        assertTrue(firstResult is CommandResult.Success)
        assertEquals(1, counter)

        val storedBefore = database.operationDao().getOperationById(opId.value)
        assertNotNull(storedBefore)
        assertEquals(OperationStatus.SUCCEEDED, storedBefore?.status)

        val rejected = commandExecutor.execute(
            command = command("fingerprint-changed"),
            replayResolver = { op ->
                op.resultReferenceId
                    ?: throw IllegalStateException("Replay failed: missing business result reference")
            }
        ) {
            counter++
            Pair("biz-fingerprint", Pair("BUSINESS", "biz-fingerprint"))
        }
        assertTrue(rejected is CommandResult.Conflict)

        val storedAfter = database.operationDao().getOperationById(opId.value)
        assertEquals(storedBefore, storedAfter)
        assertEquals(OperationStatus.SUCCEEDED, storedAfter?.status)
        assertEquals(1, counter)
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
        val originalLanguage = languageManager.currentLanguage.value.code

        try {
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
        } finally {
            languageManager.setLanguage(originalLanguage)
        }
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
        val repository = SettingsRepository(database.userDao(), database.businessBranchDao())

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
        val repository = SettingsRepository(database.userDao(), database.businessBranchDao())

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
        val repository = SettingsRepository(database.userDao(), database.businessBranchDao())

        val result = repository.updateLanguage(
            userId = before.id,
            businessId = "wrong-business",
            requestedLanguageCode = "hi"
        )

        assertTrue(result is SettingsLanguageResult.PersistenceFailure)
        assertEquals(before, database.userDao().getUserById(before.id))
    }

    @Test
    fun testSettingsViewModelPersistsLanguageThenRefreshesSessionAndRuntime() = runBlocking {
        val setup = createLanguageTestSetup()
        val beforeUser = database.userDao().getUserById(setup.adminUserId.value)!!
        val beforeBusiness = database.businessBranchDao().getBusinessById(setup.businessId.value)!!
        val beforeBranch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val languageManager = LanguageManager(context)
        val originalLanguage = languageManager.currentLanguage.value.code
        val viewModel = trackViewModel(SettingsViewModel(
            languageManager,
            SettingsRepository(database.userDao(), database.businessBranchDao()),
            MerchantQrRepository(database.documentMediaDao(), context)
        ))
        val initialSession = ActiveSession(
            business = beforeBusiness,
            branch = beforeBranch,
            user = beforeUser,
            membership = null
        )

        try {
            languageManager.setLanguage("en")
            SessionManager.setSession(initialSession)

            viewModel.updateLanguage("ar")

            withTimeout(3_000L) {
                while (
                    database.userDao().getUserById(beforeUser.id)?.languageCode != "ar" ||
                    SessionManager.currentSession.value?.user?.languageCode != "ar" ||
                    languageManager.currentLanguage.value.code != "ar"
                ) {
                    delay(10L)
                }
            }

            val persisted = database.userDao().getUserById(beforeUser.id)!!
            val sessionUser = SessionManager.currentSession.value?.user
            assertEquals("ar", persisted.languageCode)
            assertEquals(persisted, sessionUser)
            assertEquals("ar", languageManager.currentLanguage.value.code)
            assertEquals(
                beforeUser.copy(languageCode = "ar", updatedAt = persisted.updatedAt),
                persisted
            )
            assertEquals(beforeBusiness, database.businessBranchDao().getBusinessById(beforeBusiness.id))
            assertEquals(beforeBranch, database.businessBranchDao().getBranchById(beforeBranch.id))
        } finally {
            SessionManager.clearSession()
            languageManager.setLanguage(originalLanguage)
        }
    }

    @Test
    fun testSettingsViewModelUnsupportedLanguageLeavesDatabaseSessionAndRuntimeUnchanged() = runBlocking {
        val setup = createLanguageTestSetup()
        val beforeUser = database.userDao().getUserById(setup.adminUserId.value)!!
        val beforeBusiness = database.businessBranchDao().getBusinessById(setup.businessId.value)!!
        val beforeBranch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val languageManager = LanguageManager(context)
        val originalLanguage = languageManager.currentLanguage.value.code
        val viewModel = trackViewModel(SettingsViewModel(
            languageManager,
            SettingsRepository(database.userDao(), database.businessBranchDao()),
            MerchantQrRepository(database.documentMediaDao(), context)
        ))
        val initialSession = ActiveSession(
            business = beforeBusiness,
            branch = beforeBranch,
            user = beforeUser,
            membership = null
        )

        try {
            languageManager.setLanguage("en")
            SessionManager.setSession(initialSession)

            viewModel.updateLanguage("xx")
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(beforeUser, database.userDao().getUserById(beforeUser.id))
            assertEquals(initialSession, SessionManager.currentSession.value)
            assertEquals("en", languageManager.currentLanguage.value.code)
        } finally {
            SessionManager.clearSession()
            languageManager.setLanguage(originalLanguage)
        }
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
    fun testSettlementTransactionForeignKeyRejectsOrphanAndAllowsNull() = runBlocking {
        database.openHelper.readableDatabase
            .query("PRAGMA foreign_keys")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "Foreign-key enforcement must be enabled",
                    1,
                    cursor.getInt(0)
                )
            }

        val missingTransactionId =
            "settlement-fk-missing-transaction"

        val orphanOperation =
            operationFixture(
                id = "settlement-fk-orphan-operation"
            ).copy(
                operationType = OperationType.RECORD_SALE,
                subjectType = SubjectType.TRANSACTION,
                subjectId = missingTransactionId,
                transactionId = missingTransactionId
            )

        database.operationDao()
            .insertOperation(orphanOperation)

        val orphanSettlement =
            SettlementEntryEntity(
                id = "settlement-fk-orphan",
                operationId = orphanOperation.id,
                subjectType = SubjectType.TRANSACTION,
                subjectId = missingTransactionId,
                transactionId = missingTransactionId,
                entryPurpose = EntryPurpose.SALE_FUNDING,
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountSignedMinor = 100L,
                currencyCode = "OMR",
                confirmationType =
                    ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = 1_000L,
                actorUserId = orphanOperation.actorUserId
            )

        var orphanFailure: Throwable? = null

        try {
            database.transactionDao()
                .insertSettlement(orphanSettlement)
        } catch (throwable: Throwable) {
            orphanFailure = throwable
        }

        assertNotNull(
            "Fresh v4 database must reject a settlement whose non-null transaction_id has no parent transaction",
            orphanFailure
        )

        assertTrue(
            "Rejected orphan settlement must not be persisted",
            database.transactionDao()
                .getSettlementsForTransaction(
                    missingTransactionId
                )
                .isEmpty()
        )

        val nullableOperation =
            operationFixture(
                id = "settlement-fk-null-operation"
            ).copy(
                operationType = OperationType.RECORD_SALE,
                subjectType = SubjectType.TRANSACTION,
                subjectId = "settlement-fk-null-subject",
                transactionId = null
            )

        database.operationDao()
            .insertOperation(nullableOperation)

        val nullableSettlement =
            orphanSettlement.copy(
                id = "settlement-fk-null",
                operationId = nullableOperation.id,
                subjectId = "settlement-fk-null-subject",
                transactionId = null,
                actorUserId = nullableOperation.actorUserId
            )

        database.transactionDao()
            .insertSettlement(nullableSettlement)

        database.openHelper.readableDatabase
            .query(
                """
                SELECT transaction_id
                FROM settlement_entries
                WHERE id = 'settlement-fk-null'
                """.trimIndent()
            )
            .use { cursor ->
                assertTrue(
                    "Settlement with NULL transaction_id must be persisted",
                    cursor.moveToFirst()
                )

                assertTrue(
                    "transaction_id must remain NULL",
                    cursor.isNull(0)
                )
            }
    }

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
    fun testPhoneAdminLoginUsesCanonicalPhone() = runBlocking {
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

    }

    @Test
    fun customerExcelExportIncludesReconciledSaleAndAllControlWorksheets() = runBlocking {
        val setup = createLanguageTestSetup()
        setupRepository.establishAdminSession(setup)
        val business = database.businessBranchDao().getBusinessById(setup.businessId.value)!!
        val branch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val admin = database.userDao().getUserById(setup.adminUserId.value)!!
        val phone = "+96890000001"
        val operationId = OperationId.generate()
        val transactionId = TransactionId.generate()
        val registerRepository = RegisterRepository(database, commandExecutor)
        val saleResult = registerRepository.executeCashSale(
            CashSaleCommand(
                operationId = operationId,
                businessId = setup.businessId.value,
                branchId = branch.id,
                actorUserId = admin.id,
                transactionIdVal = transactionId,
                draftState = DraftState(manualAmount = MoneyMinorUnits(12345L)),
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                deviceId = "customer-export-test-device",
                customerName = "Excel Export Customer",
                customerPhone = phone
            ),
            admin,
            database.userDao().getMembership(admin.id, branch.id)
        )
        assertTrue("Fixture sale should succeed: " + saleResult, saleResult is CommandResult.Success)
        val customer = database.customerDao().getCustomerByPhone(setup.businessId.value, phone)!!

        val creditOperationId = OperationId.generate()
        val creditTransactionId = TransactionId.generate()
        val creditSaleResult = registerRepository.executeCreditSale(
            CreditSaleCommand(
                operationId = creditOperationId,
                businessId = setup.businessId.value,
                branchId = branch.id,
                actorUserId = admin.id,
                transactionIdVal = creditTransactionId,
                customerPhone = phone,
                customerName = "Excel Export Customer",
                draftState = DraftState(manualAmount = MoneyMinorUnits(5000L)),
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                deviceId = "customer-export-credit-test-device"
            )
        )
        assertTrue("Fixture credit sale should succeed: " + creditSaleResult, creditSaleResult is CommandResult.Success)
        val repaymentResult = registerRepository.executeCreditRepayment(
            CreditRepaymentCommand(
                operationId = OperationId.generate(),
                businessId = setup.businessId.value,
                branchId = branch.id,
                actorUserId = admin.id,
                repaymentId = "customer-export-repayment-" + java.util.UUID.randomUUID(),
                customerId = customer.id,
                amountMinor = 2000L,
                currencyCode = branch.currencyCode,
                paymentCategory = PaymentCategory.CASH,
                paymentChannel = PaymentChannel.CASH_TENDER,
                deviceId = "customer-export-repayment-test-device"
            )
        )
        assertTrue("Fixture credit repayment should succeed: " + repaymentResult, repaymentResult is CommandResult.Success)
        SessionManager.setSession(ActiveSession(business = business, branch = branch, user = admin, membership = null))

        val exportResult = CustomerExcelExporter(context, database).export()
        assertTrue("Customer Excel export should succeed: " + exportResult.exceptionOrNull(), exportResult.isSuccess)
        val uri = exportResult.getOrThrow()
        try {
            var workbookXml = ""
            var directoryXml = ""
            var summaryXml = ""
            var historyXml = ""
            var creditReconciliationXml = ""
            context.contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val entryText = zip.readBytes().toString(Charsets.UTF_8)
                        when (entry.name) {
                            "xl/workbook.xml" -> workbookXml = entryText
                            "xl/worksheets/sheet1.xml" -> directoryXml = entryText
                            "xl/worksheets/sheet2.xml" -> summaryXml = entryText
                            "xl/worksheets/sheet3.xml" -> historyXml = entryText
                            "xl/worksheets/sheet4.xml" -> creditReconciliationXml = entryText
                        }
                    }
                }
            }
            assertTrue("Workbook should contain credit reconciliation sheet", workbookXml.contains("Credit Reconciliation"))
            assertTrue("Workbook should contain coupon reconciliation sheet", workbookXml.contains("Coupon Reconciliation"))
            assertTrue("Directory should retain the exact customer ID", directoryXml.contains(customer.id))
            assertTrue("Summary should show total OMR sales of 17.345", summaryXml.contains("17.345"))
            assertTrue("Summary should show credit sale value of 5.000", summaryXml.contains("5.000"))
            assertTrue("Summary should show credit repayment collection of 2.000", summaryXml.contains("2.000"))
            assertTrue("Summary should show ledger outstanding credit of 3.000 after repayment", summaryXml.contains("3.000"))
            assertTrue("History should retain the cash-sale transaction ID", historyXml.contains(transactionId.value))
            assertTrue("History should retain the credit-sale transaction ID", historyXml.contains(creditTransactionId.value))
            assertTrue("Credit control should reconcile funding and ledger obligation", creditReconciliationXml.contains("PASS"))
        } finally {
            context.contentResolver.delete(uri, null, null)
            SessionManager.clearSession()
        }
    }

    @Test
    fun testSaleWithoutSessionIsDeniedAndWritesNothing() = runBlocking {
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

        val adminId = setup.adminUserId.value
        val admin = database.userDao().getUserById(adminId)!!
        val branch = database.businessBranchDao().getBranchById(setup.branchId.value)!!
        val membership = database.userDao().getMembership(adminId, branch.id)!!

        val registerRepository = RegisterRepository(database, commandExecutor)
        val restrictedOperationId = OperationId.generate()
        val restrictedTransactionId = TransactionId.generate()
        val restrictedCommand = CashSaleCommand(
            operationId = restrictedOperationId,
            businessId = setup.businessId.value,
            branchId = branch.id,
            actorUserId = adminId,
            transactionIdVal = restrictedTransactionId,
            draftState = DraftState(manualAmount = MoneyMinorUnits(1000L)),
            currencyCode = branch.currencyCode,
            currencyDecimalPlaces = branch.currencyDecimalPlaces,
            deviceId = "test-device"
        )

        SessionManager.clearSession()
        val restrictedResult = registerRepository.executeCashSale(restrictedCommand, admin, membership)
        assertTrue(restrictedResult is CommandResult.PermissionDenied)
        assertNull(database.transactionDao().getTransactionById(restrictedTransactionId.value))
        assertTrue(database.transactionDao().getSettlementsForTransaction(restrictedTransactionId.value).isEmpty())
        assertNull(database.operationDao().getOperationById(restrictedOperationId.value))

        SessionManager.clearSession()
        val bypassAttempt = registerRepository.executeCashSale(restrictedCommand, admin, membership)
        assertTrue(bypassAttempt is CommandResult.PermissionDenied)
        assertNull(database.transactionDao().getTransactionById(restrictedTransactionId.value))
        assertTrue(database.transactionDao().getSettlementsForTransaction(restrictedTransactionId.value).isEmpty())
        assertNull(database.operationDao().getOperationById(restrictedOperationId.value))
    }
}
