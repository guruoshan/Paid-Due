package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.feature.register.data.CouponSaleCustomerWallet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterCustomerSelectionCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var uiState: MutableStateFlow<RegisterUiState>
    private val sessionA = session("branch-a")
    private val sessionB = session("branch-b")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        uiState = MutableStateFlow(RegisterUiState())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun olderPhoneLookupCannotOverwriteNewerResult() = runTest(dispatcher) {
        val older = CompletableDeferred<CustomerEntity?>()
        val newer = CompletableDeferred<CustomerEntity?>()
        val coordinator = coordinator(
            scope = backgroundScope,
            findSaleCustomer = { _, phone ->
                if (phone == "+96890000001") older.await() else newer.await()
            }
        )

        coordinator.lookupSaleCustomerByPhone("+96890000001")
        coordinator.lookupSaleCustomerByPhone("+96890000002")
        runCurrent()

        older.complete(customer("older", "+96890000001", "Older"))
        runCurrent()
        assertNull(uiState.value.saleCustomerLookupResult)
        assertTrue(uiState.value.saleCustomerLookupLoading)

        val expected = customer("newer", "+96890000002", "Newer")
        newer.complete(expected)
        runCurrent()

        assertEquals(expected, uiState.value.saleCustomerLookupResult)
        assertEquals("+96890000002", uiState.value.saleCustomerLookupCompletedPhone)
        assertEquals("Newer", uiState.value.saleCustomerName)
    }

    @Test
    fun clearSaleCustomerResetsAllCustomerLookupState() = runTest(dispatcher) {
        val pending = CompletableDeferred<CustomerEntity?>()
        val coordinator = coordinator(scope = backgroundScope, findSaleCustomer = { _, _ -> pending.await() })

        coordinator.setSaleCustomerName("Existing name")
        coordinator.setSaleCustomerPhone("+96890000003")
        coordinator.lookupSaleCustomerByPhone("+96890000003")
        runCurrent()
        assertTrue(uiState.value.saleCustomerLookupLoading)

        coordinator.clearSaleCustomer()

        assertEquals("", uiState.value.saleCustomerName)
        assertEquals("", uiState.value.saleCustomerPhone)
        assertFalse(uiState.value.saleCustomerLookupLoading)
        assertNull(uiState.value.saleCustomerLookupResult)
        assertNull(uiState.value.saleCustomerLookupCompletedPhone)

        pending.complete(customer("late", "+96890000003", "Late"))
        runCurrent()
        assertNull(uiState.value.saleCustomerLookupResult)
    }

    @Test
    fun expensePayeeResultIsIgnoredAfterOverlayClosure() = runTest(dispatcher) {
        val pending = CompletableDeferred<CustomerEntity?>()
        val coordinator = coordinator(findSaleCustomer = { _, _ -> pending.await() })
        uiState.value = uiState.value.copy(overlay = RegisterOverlay.Expense)

        coordinator.lookupExpensePayeeByPhone("+96890000004")
        runCurrent()
        uiState.value = uiState.value.copy(overlay = null)

        pending.complete(customer("late-payee", "+96890000004", "Late Payee"))
        runCurrent()

        assertNull(uiState.value.expensePayeeLookupResult)
    }

    @Test
    fun couponSearchResultIsIgnoredAfterFinancialSessionChanges() = runTest(dispatcher) {
        val pending = CompletableDeferred<List<CouponSaleCustomerWallet>>()
        var activeSession = sessionA
        var generation = 0L
        val coordinator = coordinator(
            scope = backgroundScope,
            findCouponCustomers = { pending.await() },
            currentSession = { activeSession },
            financialSessionGeneration = { generation },
            isFinancialSessionCurrent = { _, current, captured, latest ->
                current != null && captured == latest
            }
        )
        uiState.value = uiState.value.copy(overlay = RegisterOverlay.Coupon(CouponDialogStartMode.ENTRY))

        coordinator.searchCouponCustomers("customer")
        runCurrent()
        assertTrue(uiState.value.couponSearching)

        activeSession = sessionB
        generation++
        pending.complete(emptyList())
        runCurrent()

        assertTrue(uiState.value.couponSearching)
        assertTrue(uiState.value.couponCustomers.isEmpty())
    }

    private fun coordinator(
        scope: CoroutineScope,
        findSaleCustomer: suspend (String, String) -> CustomerEntity? = { _, _ -> null },
        findCouponCustomers: suspend (String) -> List<CouponSaleCustomerWallet> = { emptyList() },
        currentSession: () -> ActiveSession? = { sessionA },
        financialSessionGeneration: () -> Long = { 0L },
        isFinancialSessionCurrent: (
            RegisterFinancialSessionContext?,
            ActiveSession?,
            Long,
            Long
        ) -> Boolean = { _, current, captured, latest -> current != null && captured == latest }
    ) = RegisterCustomerSelectionCoordinator(
        uiState = uiState,
        scope = scope,
        currentSession = currentSession,
        findSaleCustomerByPhone = findSaleCustomer,
        findCouponCustomers = findCouponCustomers,
        synchronizeFinancialSession = { false },
        financialContext = { null },
        financialSessionGeneration = financialSessionGeneration,
        isFinancialSessionCurrent = isFinancialSessionCurrent
    )

    private fun customer(id: String, phone: String, name: String) = CustomerEntity(
        id = id,
        businessId = "business-1",
        canonicalPhone = phone,
        name = name,
        createdAt = 1,
        updatedAt = 1
    )

    private fun session(branchId: String) = ActiveSession(
        business = BusinessEntity(
            id = "business-1",
            name = "Business",
            createdAt = 1,
            updatedAt = 1
        ),
        branch = BranchEntity(
            id = branchId,
            businessId = "business-1",
            name = branchId,
            code = branchId,
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            defaultTaxRateBps = 0,
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            createdAt = 1,
            updatedAt = 1
        ),
        user = UserAccountEntity(
            id = "admin",
            businessId = "business-1",
            displayName = "Admin",
            username = "admin",
            role = UserRole.ADMIN,
            authType = AuthType.PIN,
            credentialHash = "hash",
            credentialSalt = "salt",
            createdAt = 1,
            updatedAt = 1
        ),
        membership = null
    )
}
