package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRateParser
import com.dailymoneyregister.feature.register.domain.CalculatorState
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.KeypadKey
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns unsaved sale-draft composition, calculator-to-draft synchronization,
 * and item editing presentation state.
 *
 * RegisterViewModel remains responsible for financial-session synchronization,
 * calculator-engine precision/lifecycle, and repository-backed product memory.
 */
internal class RegisterDraftCompositionCoordinator(
    private val uiState: MutableStateFlow<RegisterUiState>
) {
    fun onKeypadPress(
        key: KeypadKey,
        processKey: (CalculatorState, KeypadKey) -> CalculatorState,
        sessionChanged: Boolean
    ) {
        val state = uiState.value
        val nextCalculatorState = processKey(state.calculatorState, key)
        val nextDraftState = state.draftState.copy(manualAmount = nextCalculatorState.evaluatedAmount)
        uiState.value = state.copy(
            calculatorState = nextCalculatorState,
            draftState = nextDraftState,
            errorMessage = null,
            infoMessage = if (sessionChanged) state.infoMessage else null
        )
    }

    fun onManualDescriptionChanged(description: String) {
        uiState.value = uiState.value.copy(
            manualDescription = description,
            draftState = uiState.value.draftState.copy(manualDescription = description.ifBlank { null })
        )
    }

    fun clearDraft(branch: BranchEntity?) {
        uiState.value = RegisterUiState(
            calculatorState = CalculatorState(),
            draftState = clearedRegisterDraft(branch),
            isRecentTransactionsExpanded = uiState.value.isRecentTransactionsExpanded
        )
    }

    fun setTaxEnabled(enabled: Boolean) {
        val updatedDraft = uiState.value.draftState.copy(isTaxEnabled = enabled)
        uiState.value = uiState.value.copy(draftState = updatedDraft)
    }

    fun updateTaxRatePercent(percentText: String) {
        val basisPoints = TaxRateParser.parseToBpsOrNull(percentText) ?: return
        val updatedDraft = uiState.value.draftState.copy(
            taxRateBps = basisPoints,
            isTaxEnabled = basisPoints > 0
        )
        uiState.value = uiState.value.copy(draftState = updatedDraft)
    }

    fun updateTxShippingPacking(shippingText: String, packingText: String, decimalPlaces: Int) {
        val shipping = MoneyMinorUnits.parse(shippingText, decimalPlaces).getOrElse { return }
        val packing = MoneyMinorUnits.parse(packingText, decimalPlaces).getOrElse { return }
        if (shipping.isNegative || packing.isNegative) return

        val updatedDraft = uiState.value.draftState.copy(txShipping = shipping, txPacking = packing)
        uiState.value = uiState.value.copy(
            draftState = updatedDraft,
            overlay = uiState.value.overlay.afterChildClosed()
        )
    }

    fun addItem(
        name: String,
        priceText: String,
        quantity: Int,
        description: String?,
        packingText: String,
        shippingText: String,
        decimalPlaces: Int,
        rememberProduct: (String, String?, Long) -> Unit
    ) {
        if (name.isBlank() || quantity <= 0) return
        val unitPrice = MoneyMinorUnits.parse(priceText, decimalPlaces).getOrElse { return }
        val packing = MoneyMinorUnits.parse(packingText, decimalPlaces).getOrElse { return }
        val shipping = MoneyMinorUnits.parse(shippingText, decimalPlaces).getOrElse { return }
        if (unitPrice.isNegative || packing.isNegative || shipping.isNegative) return

        val newItem = DraftItem(
            id = IdGenerator.newId(),
            name = name.trim(),
            description = description?.trim()?.ifBlank { null },
            unitPrice = unitPrice,
            quantity = quantity,
            itemPackingCharge = packing,
            itemShippingCharge = shipping
        )
        val currentItems = uiState.value.draftState.items.toMutableList().apply { add(newItem) }
        uiState.value = uiState.value.copy(
            draftState = uiState.value.draftState.copy(items = currentItems),
            overlay = uiState.value.overlay.afterChildClosed(),
            editingItemId = null
        )
        rememberProduct(newItem.name, newItem.description, unitPrice.amount)
    }

    fun editItem(
        itemId: String,
        name: String,
        priceText: String,
        quantity: Int,
        description: String?,
        decimalPlaces: Int,
        rememberProduct: (String, String?, Long) -> Unit
    ) {
        if (name.isBlank() || quantity <= 0) return
        val unitPrice = MoneyMinorUnits.parse(priceText, decimalPlaces).getOrElse { return }
        if (unitPrice.isNegative) return

        val currentItems = uiState.value.draftState.items.map { item ->
            if (item.id == itemId) {
                item.copy(
                    name = name.trim(),
                    description = description?.trim()?.ifBlank { null },
                    unitPrice = unitPrice,
                    quantity = quantity
                )
            } else item
        }
        uiState.value = uiState.value.copy(
            draftState = uiState.value.draftState.copy(items = currentItems),
            overlay = uiState.value.overlay.afterChildClosed(),
            editingItemId = null
        )
        rememberProduct(name.trim(), description?.trim()?.ifBlank { null }, unitPrice.amount)
    }

    fun startEditingItem(itemId: String) {
        val returnToItemized = uiState.value.overlay == RegisterOverlay.ItemizedBill
        uiState.value = uiState.value.copy(
            editingItemId = itemId,
            overlay = RegisterOverlay.AddItem(returnToItemized)
        )
    }

    fun cancelEditingItem() {
        uiState.value = uiState.value.copy(editingItemId = null)
    }

    fun closeAddItemDialog(overlay: RegisterOverlay.AddItem) {
        uiState.value = uiState.value.copy(
            overlay = overlay.afterChildClosed(),
            editingItemId = null
        )
    }

    fun removeItem(itemId: String) {
        val currentItems = uiState.value.draftState.items.filterNot { it.id == itemId }
        uiState.value = uiState.value.copy(draftState = uiState.value.draftState.copy(items = currentItems))
    }

    fun updateItemQuantity(itemId: String, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeItem(itemId)
            return
        }
        val currentItems = uiState.value.draftState.items.map { item ->
            if (item.id == itemId) item.copy(quantity = newQuantity) else item
        }
        uiState.value = uiState.value.copy(draftState = uiState.value.draftState.copy(items = currentItems))
    }
}

internal fun clearedRegisterDraft(branch: BranchEntity?) =
    com.dailymoneyregister.feature.register.domain.DraftState(
        taxRateBps = 0,
        isTaxEnabled = false,
        taxRoundingMode = branch?.taxRoundingMode
            ?: com.dailymoneyregister.core.model.tax.TaxRoundingMode.HALF_UP
    )
