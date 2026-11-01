package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.register.data.CouponSaleCustomerWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns customer selection and lookup state for sale, coupon, and expense overlays.
 *
 * Financial-session ownership and generation tracking remain in RegisterViewModel.
 * This coordinator only performs read-only customer lookups and updates their UI state.
 */
internal class RegisterCustomerSelectionCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>,
    private val scope: CoroutineScope,
    private val currentSession: () -> ActiveSession?,
    private val findSaleCustomerByPhone: suspend (String, String) -> CustomerEntity?,
    private val findCouponCustomers: suspend (String) -> List<CouponSaleCustomerWallet>,
    private val synchronizeFinancialSession: (ActiveSession?) -> Boolean,
    private val financialContext: () -> RegisterFinancialSessionContext?,
    private val financialSessionGeneration: () -> Long,
    private val isFinancialSessionCurrent: (
        RegisterFinancialSessionContext?,
        ActiveSession?,
        Long,
        Long
    ) -> Boolean
) {
    fun setSaleCustomerName(value: String) {
        val state = uiState.value
        if (state.isExecuting || state.inFlightSaleCommand != null || state.inFlightOnlineSaleCommand != null) return
        uiState.value = state.copy(saleCustomerName = value)
    }

    fun setSaleCustomerPhone(value: String) {
        val state = uiState.value
        if (state.isExecuting || state.inFlightSaleCommand != null || state.inFlightOnlineSaleCommand != null) return
        uiState.value = state.copy(
            saleCustomerPhone = value,
            saleCustomerLookupLoading = false,
            saleCustomerLookupResult = null,
            saleCustomerLookupCompletedPhone = null
        )
    }

    fun lookupSaleCustomerByPhone(canonicalPhone: String) {
        val session = currentSession() ?: return
        val phone = canonicalPhone.trim()
        val state = uiState.value

        if (state.isExecuting || state.inFlightSaleCommand != null || state.inFlightOnlineSaleCommand != null) return

        if (phone.isBlank()) {
            uiState.value = state.copy(
                saleCustomerLookupLoading = false,
                saleCustomerLookupResult = null,
                saleCustomerLookupCompletedPhone = null
            )
            return
        }

        uiState.value = state.copy(
            saleCustomerPhone = phone,
            saleCustomerLookupLoading = true,
            saleCustomerLookupResult = null,
            saleCustomerLookupCompletedPhone = null
        )

        scope.launch {
            try {
                val customer = findSaleCustomerByPhone(session.business.id, phone)
                val current = uiState.value
                if (current.saleCustomerPhone.trim() != phone) return@launch

                uiState.value = current.copy(
                    saleCustomerLookupLoading = false,
                    saleCustomerLookupResult = customer,
                    saleCustomerLookupCompletedPhone = phone,
                    saleCustomerName = customer?.name ?: current.saleCustomerName
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val current = uiState.value
                if (current.saleCustomerPhone.trim() != phone) return@launch

                uiState.value = current.copy(
                    saleCustomerLookupLoading = false,
                    saleCustomerLookupResult = null,
                    saleCustomerLookupCompletedPhone = null,
                    errorMessage = UiText.Dynamic(error.message ?: "Customer lookup failed")
                )
            }
        }
    }

    fun clearSaleCustomer() {
        val state = uiState.value
        if (state.isExecuting || state.inFlightSaleCommand != null || state.inFlightOnlineSaleCommand != null) return
        uiState.value = state.copy(
            saleCustomerName = "",
            saleCustomerPhone = "",
            saleCustomerLookupLoading = false,
            saleCustomerLookupResult = null,
            saleCustomerLookupCompletedPhone = null
        )
    }

    fun searchCouponCustomers(query: String) {
        if (uiState.value.couponSearching || uiState.value.isExecuting) return
        val session = currentSession()
        if (synchronizeFinancialSession(session) || session == null) return
        val generation = financialSessionGeneration()
        val context = financialContext()
        uiState.value = uiState.value.copy(couponSearching = true)

        scope.launch {
            try {
                val customers = findCouponCustomers(query)
                if (
                    isFinancialSessionCurrent(
                        context,
                        currentSession(),
                        generation,
                        financialSessionGeneration()
                    ) &&
                    (uiState.value.overlay is RegisterOverlay.Coupon ||
                        uiState.value.overlay == RegisterOverlay.CreditSale)
                ) {
                    uiState.value = uiState.value.copy(
                        couponCustomers = customers,
                        couponSearching = false
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == financialSessionGeneration()) {
                    uiState.value = uiState.value.copy(
                        couponSearching = false,
                        errorMessage = UiText.Dynamic(error.message ?: "Customer search failed")
                    )
                }
            }
        }
    }

    fun lookupExpensePayeeByPhone(canonicalPhone: String?) {
        val session = currentSession() ?: return
        val phone = canonicalPhone?.trim().orEmpty()
        val state = uiState.value

        if (
            state.overlay != RegisterOverlay.Expense ||
            state.isExecuting ||
            state.inFlightExpenseCommand != null
        ) {
            return
        }

        if (phone.isBlank()) {
            uiState.value = state.copy(
                expensePayeeLookupLoading = false,
                expensePayeeLookupPhone = null,
                expensePayeeLookupResult = null
            )
            return
        }

        if (state.expensePayeeLookupPhone == phone && !state.expensePayeeLookupLoading) return

        uiState.value = state.copy(
            expensePayeeLookupLoading = true,
            expensePayeeLookupPhone = phone,
            expensePayeeLookupResult = null
        )

        scope.launch {
            try {
                val customer = findSaleCustomerByPhone(session.business.id, phone)
                val current = uiState.value
                if (
                    current.overlay != RegisterOverlay.Expense ||
                    current.expensePayeeLookupPhone != phone
                ) {
                    return@launch
                }

                uiState.value = current.copy(
                    expensePayeeLookupLoading = false,
                    expensePayeeLookupResult = customer
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val current = uiState.value
                if (current.expensePayeeLookupPhone != phone) return@launch

                uiState.value = current.copy(
                    expensePayeeLookupLoading = false,
                    expensePayeeLookupResult = null,
                    errorMessage = UiText.Dynamic(error.message ?: "Customer lookup failed.")
                )
            }
        }
    }
}
