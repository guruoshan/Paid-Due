package com.dailymoneyregister.feature.register.domain

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftStateTest {

    @Test
    fun testManualSaleSubtotalAndGrandTotal() {
        val draft = DraftState(
            manualAmount = MoneyMinorUnits(1000L), // 1.000 OMR
            taxRateBps = 500, // 5%
            isTaxEnabled = true,
            taxRoundingMode = TaxRoundingMode.HALF_UP
        )

        assertEquals(MoneyMinorUnits(1000L), draft.subtotal)
        assertEquals(MoneyMinorUnits(50L), draft.taxAmount) // 0.050 OMR
        assertEquals(MoneyMinorUnits(1050L), draft.grandTotal) // 1.050 OMR
    }

    @Test
    fun testLockedGrandTotalFormulaWithItemizedBillTaxShippingAndPacking() {
        // Milk (1.000) + Biscuits (2.000) + Tea (3.000)
        val items = listOf(
            DraftItem("1", "Milk", unitPrice = MoneyMinorUnits(1000L), quantity = 1),
            DraftItem("2", "Biscuits", unitPrice = MoneyMinorUnits(2000L), quantity = 1),
            DraftItem("3", "Tea", unitPrice = MoneyMinorUnits(3000L), quantity = 1)
        )

        val draft = DraftState(
            items = items,
            taxRateBps = 500, // 5.00%
            isTaxEnabled = true,
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            txPacking = MoneyMinorUnits(2000L),  // 2.000 OMR
            txShipping = MoneyMinorUnits(1000L)   // 1.000 OMR
        )

        // Subtotal = 1.000 + 2.000 + 3.000 = 6.000 OMR (6000 baisa)
        assertEquals(MoneyMinorUnits(6000L), draft.subtotal)

        // Tax 5% on 6.000 = 0.300 OMR (300 baisa)
        assertEquals(MoneyMinorUnits(300L), draft.taxAmount)

        // Grand total = 6.000 + 0.300 + 1.000 (shipping) + 2.000 (packing) = 9.300 OMR (9300 baisa)
        assertEquals(MoneyMinorUnits(9300L), draft.grandTotal)
    }

    @Test
    fun testItemLevelShippingAndPackingInGrandTotal() {
        val items = listOf(
            DraftItem(
                id = "1",
                name = "Heavy Box",
                unitPrice = MoneyMinorUnits(5000L), // 5.000
                quantity = 2,
                itemPackingCharge = MoneyMinorUnits(500L), // 0.500 * 2 = 1.000
                itemShippingCharge = MoneyMinorUnits(1000L) // 1.000 * 2 = 2.000
            )
        )

        val draft = DraftState(
            items = items,
            taxRateBps = 0,
            isTaxEnabled = false
        )

        // Item Subtotal = 5.000 * 2 = 10.000
        assertEquals(MoneyMinorUnits(10000L), draft.subtotal)
        assertEquals(MoneyMinorUnits(1000L), draft.itemPackingTotal)
        assertEquals(MoneyMinorUnits(2000L), draft.itemShippingTotal)

        // Grand Total = 10.000 + 1.000 + 2.000 = 13.000 OMR
        assertEquals(MoneyMinorUnits(13000L), draft.grandTotal)
    }

    @Test
    fun testShipPackDisplayTotalUsesDraftAuthoritativeComponents() {
        val draft = DraftState(
            manualAmount = MoneyMinorUnits(10000L),
            txShipping = MoneyMinorUnits(500L),
            txPacking = MoneyMinorUnits(200L),
            items = listOf(
                DraftItem(
                    id = "item",
                    name = "Parcel",
                    unitPrice = MoneyMinorUnits(1000L),
                    itemShippingCharge = MoneyMinorUnits(50L),
                    itemPackingCharge = MoneyMinorUnits(25L)
                )
            )
        )

        val displayedShipPack =
            draft.txShipping + draft.txPacking + draft.itemShippingTotal + draft.itemPackingTotal

        assertEquals(MoneyMinorUnits(775L), displayedShipPack)
    }
}
