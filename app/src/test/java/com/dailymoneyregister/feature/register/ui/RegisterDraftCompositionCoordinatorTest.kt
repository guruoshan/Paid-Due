package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.feature.register.domain.CalculatorEngine
import com.dailymoneyregister.feature.register.domain.CalculatorState
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.register.domain.KeypadKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegisterDraftCompositionCoordinatorTest {
    private lateinit var uiState: MutableStateFlow<RegisterUiState>
    private lateinit var coordinator: RegisterDraftCompositionCoordinator

    @Before
    fun setUp() {
        uiState = MutableStateFlow(RegisterUiState())
        coordinator = RegisterDraftCompositionCoordinator(uiState)
    }

    @Test
    fun addEditQuantityAndRemovePreserveItemIdentityAndCharges() {
        val remembered = mutableListOf<Triple<String, String?, Long>>()
        coordinator.addItem(
            name = " Tea ",
            priceText = "1.250",
            quantity = 2,
            description = " loose leaf ",
            packingText = "0.100",
            shippingText = "0.200",
            decimalPlaces = 3
        ) { name, description, price -> remembered += Triple(name, description, price) }

        val original = uiState.value.draftState.items.single()
        assertEquals("Tea", original.name)
        assertEquals("loose leaf", original.description)
        assertEquals(MoneyMinorUnits(1250), original.unitPrice)
        assertEquals(2, original.quantity)
        assertEquals(MoneyMinorUnits(100), original.itemPackingCharge)
        assertEquals(MoneyMinorUnits(200), original.itemShippingCharge)
        assertEquals(listOf(Triple("Tea", "loose leaf", 1250L)), remembered)

        coordinator.editItem(
            itemId = original.id,
            name = " Green Tea ",
            priceText = "1.500",
            quantity = 3,
            description = " premium ",
            decimalPlaces = 3
        ) { name, description, price -> remembered += Triple(name, description, price) }

        val edited = uiState.value.draftState.items.single()
        assertEquals(original.id, edited.id)
        assertEquals("Green Tea", edited.name)
        assertEquals("premium", edited.description)
        assertEquals(MoneyMinorUnits(1500), edited.unitPrice)
        assertEquals(3, edited.quantity)
        assertEquals(MoneyMinorUnits(100), edited.itemPackingCharge)
        assertEquals(MoneyMinorUnits(200), edited.itemShippingCharge)
        assertEquals(Triple("Green Tea", "premium", 1500L), remembered.last())

        coordinator.updateItemQuantity(original.id, 4)
        assertEquals(4, uiState.value.draftState.items.single().quantity)

        coordinator.removeItem(original.id)
        assertTrue(uiState.value.draftState.items.isEmpty())
    }

    @Test
    fun nonPositiveQuantityRemovesItem() {
        val item = DraftItem(
            id = "item-1",
            name = "Item",
            unitPrice = MoneyMinorUnits(100)
        )
        uiState.value = uiState.value.copy(draftState = DraftState(items = listOf(item)))

        coordinator.updateItemQuantity(item.id, 0)

        assertTrue(uiState.value.draftState.items.isEmpty())
    }

    @Test
    fun clearDraftResetsDraftAndCalculatorButPreservesRecentExpansion() {
        val engine = CalculatorEngine(3)
        val calculator = engine.processKey(CalculatorState(), KeypadKey.Digit('9'))
        uiState.value = uiState.value.copy(
            calculatorState = calculator,
            draftState = DraftState(
                items = listOf(DraftItem("item-1", "Item", unitPrice = MoneyMinorUnits(900))),
                manualAmount = MoneyMinorUnits(900),
                manualDescription = "note",
                taxRateBps = 500,
                isTaxEnabled = true,
                txShipping = MoneyMinorUnits(100)
            ),
            manualDescription = "note",
            editingItemId = "item-1",
            overlay = RegisterOverlay.AddItem(false),
            isRecentTransactionsExpanded = true
        )

        coordinator.clearDraft(null)

        assertEquals(CalculatorState(), uiState.value.calculatorState)
        assertEquals(DraftState(taxRoundingMode = TaxRoundingMode.HALF_UP), uiState.value.draftState)
        assertEquals("", uiState.value.manualDescription)
        assertNull(uiState.value.editingItemId)
        assertNull(uiState.value.overlay)
        assertTrue(uiState.value.isRecentTransactionsExpanded)
    }

    @Test
    fun taxEnableAndRateUpdatesPreserveExistingParsingBehavior() {
        coordinator.setTaxEnabled(true)
        assertTrue(uiState.value.draftState.isTaxEnabled)

        coordinator.updateTaxRatePercent("8.25")
        assertEquals(825, uiState.value.draftState.taxRateBps)
        assertTrue(uiState.value.draftState.isTaxEnabled)

        coordinator.updateTaxRatePercent("not-a-rate")
        assertEquals(825, uiState.value.draftState.taxRateBps)
        assertTrue(uiState.value.draftState.isTaxEnabled)

        coordinator.updateTaxRatePercent("0")
        assertEquals(0, uiState.value.draftState.taxRateBps)
        assertFalse(uiState.value.draftState.isTaxEnabled)
    }

    @Test
    fun shippingAndPackingUpdatesUseExactMinorUnitsAndCloseChildOverlay() {
        uiState.value = uiState.value.copy(overlay = RegisterOverlay.ShippingPacking(true))

        coordinator.updateTxShippingPacking("1.25", "2.50", decimalPlaces = 2)

        assertEquals(MoneyMinorUnits(125), uiState.value.draftState.txShipping)
        assertEquals(MoneyMinorUnits(250), uiState.value.draftState.txPacking)
        assertEquals(RegisterOverlay.ItemizedBill, uiState.value.overlay)

        coordinator.updateTxShippingPacking("-1.00", "0.50", decimalPlaces = 2)
        assertEquals(MoneyMinorUnits(125), uiState.value.draftState.txShipping)
        assertEquals(MoneyMinorUnits(250), uiState.value.draftState.txPacking)
    }

    @Test
    fun itemEditingLifecyclePreservesParentOverlayAndClearsEditingIdentity() {
        uiState.value = uiState.value.copy(overlay = RegisterOverlay.ItemizedBill)

        coordinator.startEditingItem("item-42")

        assertEquals("item-42", uiState.value.editingItemId)
        assertEquals(RegisterOverlay.AddItem(true), uiState.value.overlay)

        coordinator.cancelEditingItem()
        assertNull(uiState.value.editingItemId)
        assertEquals(RegisterOverlay.AddItem(true), uiState.value.overlay)

        coordinator.startEditingItem("item-43")
        coordinator.closeAddItemDialog(RegisterOverlay.AddItem(true))

        assertNull(uiState.value.editingItemId)
        assertEquals(RegisterOverlay.ItemizedBill, uiState.value.overlay)
    }

    @Test
    fun calculatorInputKeepsCalculatorAndManualDraftAmountInSync() {
        val engine = CalculatorEngine(decimalPlaces = 2)
        val process: (CalculatorState, KeypadKey) -> CalculatorState = engine::processKey

        listOf(
            KeypadKey.Digit('1'),
            KeypadKey.Decimal,
            KeypadKey.Digit('2'),
            KeypadKey.Digit('3'),
            KeypadKey.Plus,
            KeypadKey.Digit('1'),
            KeypadKey.Equals
        ).forEach { coordinator.onKeypadPress(it, process, sessionChanged = false) }

        assertEquals(MoneyMinorUnits(223), uiState.value.calculatorState.evaluatedAmount)
        assertEquals(MoneyMinorUnits(223), uiState.value.draftState.manualAmount)
        assertNull(uiState.value.errorMessage)
    }

    @Test
    fun manualDescriptionUpdatesBothPresentationAndDraft() {
        coordinator.onManualDescriptionChanged("  lunch  ")

        assertEquals("  lunch  ", uiState.value.manualDescription)
        assertEquals("  lunch  ", uiState.value.draftState.manualDescription)

        coordinator.onManualDescriptionChanged("")

        assertEquals("", uiState.value.manualDescription)
        assertNull(uiState.value.draftState.manualDescription)
    }
}
