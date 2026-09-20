package com.dailymoneyregister.feature.register.domain

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorEngineTest {

    @Test
    fun testOmrThreeDecimalDirectDigitEntry() {
        val engine = CalculatorEngine(decimalPlaces = 3)
        var state = CalculatorState()

        // Type '1'
        state = engine.processKey(state, KeypadKey.Digit('1'))
        assertEquals("1", state.currentInput)
        assertEquals(MoneyMinorUnits(1000L), state.evaluatedAmount)

        // Type '.'
        state = engine.processKey(state, KeypadKey.Decimal)
        assertEquals("1.", state.currentInput)

        // Type '0' '0' '0' -> 1.000 OMR = 1000 baisa
        state = engine.processKey(state, KeypadKey.Digit('0'))
        state = engine.processKey(state, KeypadKey.Digit('0'))
        state = engine.processKey(state, KeypadKey.Digit('0'))
        assertEquals("1.000", state.currentInput)
        assertEquals(MoneyMinorUnits(1000L), state.evaluatedAmount)
    }

    @Test
    fun testInrTwoDecimalDirectDigitEntry() {
        val engine = CalculatorEngine(decimalPlaces = 2)
        var state = CalculatorState()

        // Type '1' '2' '5' '.' '5' '0' -> ₹125.50 = 12550 paise
        state = engine.processKey(state, KeypadKey.Digit('1'))
        state = engine.processKey(state, KeypadKey.Digit('2'))
        state = engine.processKey(state, KeypadKey.Digit('5'))
        state = engine.processKey(state, KeypadKey.Decimal)
        state = engine.processKey(state, KeypadKey.Digit('5'))
        state = engine.processKey(state, KeypadKey.Digit('0'))
        assertEquals("125.50", state.currentInput)
        assertEquals(MoneyMinorUnits(12550L), state.evaluatedAmount)
    }

    @Test
    fun testAdditionAndEquals() {
        val engine = CalculatorEngine(decimalPlaces = 3)
        var state = CalculatorState()

        // Enter 10.000
        state = engine.processKey(state, KeypadKey.Digit('1'))
        state = engine.processKey(state, KeypadKey.Digit('0'))

        // Plus
        state = engine.processKey(state, KeypadKey.Plus)
        assertEquals("+", state.pendingOperator)
        assertEquals(MoneyMinorUnits(10000L), state.previousAmount)

        // Enter 5.5
        state = engine.processKey(state, KeypadKey.Digit('5'))
        state = engine.processKey(state, KeypadKey.Decimal)
        state = engine.processKey(state, KeypadKey.Digit('5'))
        assertEquals(MoneyMinorUnits(5500L), state.evaluatedAmount)

        // Equals -> 10.000 + 5.500 = 15.500 OMR
        state = engine.processKey(state, KeypadKey.Equals)
        assertEquals(MoneyMinorUnits(15500L), state.evaluatedAmount)
        assertNull(state.pendingOperator)
    }

    @Test
    fun testSubtractionAndMultiplication() {
        val engine = CalculatorEngine(decimalPlaces = 3)
        var state = CalculatorState()

        // 20.000 - 5.000 = 15.000
        state = engine.processKey(state, KeypadKey.Digit('2'))
        state = engine.processKey(state, KeypadKey.Digit('0'))
        state = engine.processKey(state, KeypadKey.Minus)
        state = engine.processKey(state, KeypadKey.Digit('5'))
        state = engine.processKey(state, KeypadKey.Equals)
        assertEquals(MoneyMinorUnits(15000L), state.evaluatedAmount)

        // 15.000 * 2 = 30.000
        state = engine.processKey(state, KeypadKey.Multiply)
        state = engine.processKey(state, KeypadKey.Digit('2'))
        state = engine.processKey(state, KeypadKey.Equals)
        assertEquals(MoneyMinorUnits(30000L), state.evaluatedAmount)
    }

    @Test
    fun testPercentOperation() {
        val engine = CalculatorEngine(decimalPlaces = 3)
        var state = CalculatorState()

        // 100.000 + 10% = 110.000 OMR
        state = engine.processKey(state, KeypadKey.Digit('1'))
        state = engine.processKey(state, KeypadKey.Digit('0'))
        state = engine.processKey(state, KeypadKey.Digit('0'))
        state = engine.processKey(state, KeypadKey.Plus)
        state = engine.processKey(state, KeypadKey.Digit('1'))
        state = engine.processKey(state, KeypadKey.Digit('0'))
        state = engine.processKey(state, KeypadKey.Percent)

        assertEquals(MoneyMinorUnits(110000L), state.evaluatedAmount)
    }

    @Test
    fun testBackspaceAndClear() {
        val engine = CalculatorEngine(decimalPlaces = 3)
        var state = CalculatorState()

        // Type '1' '2' '3' -> 123
        state = engine.processKey(state, KeypadKey.Digit('1'))
        state = engine.processKey(state, KeypadKey.Digit('2'))
        state = engine.processKey(state, KeypadKey.Digit('3'))
        assertEquals("123", state.currentInput)

        // Backspace -> "12"
        state = engine.processKey(state, KeypadKey.Backspace)
        assertEquals("12", state.currentInput)

        // Clear -> "0"
        state = engine.processKey(state, KeypadKey.Clear)
        assertEquals("0", state.currentInput)
        assertEquals(MoneyMinorUnits.ZERO, state.evaluatedAmount)
    }

    @Test
    fun longAdditionChainKeepsFinancialResultStable() {
        val engine = CalculatorEngine(decimalPlaces = 3)
        var state = CalculatorState()

        fun press(vararg keys: KeypadKey) {
            keys.forEach { state = engine.processKey(state, it) }
        }

        press(KeypadKey.Digit('1'), KeypadKey.Digit('0'), KeypadKey.Plus)
        press(KeypadKey.Digit('5'), KeypadKey.Plus)
        press(KeypadKey.Digit('8'), KeypadKey.Plus)
        press(KeypadKey.Digit('3'), KeypadKey.Plus)
        press(KeypadKey.Digit('1'), KeypadKey.Digit('2'), KeypadKey.Equals)

        assertEquals(MoneyMinorUnits(38000L), state.evaluatedAmount)
        assertEquals("38.000", state.currentInput)
        assertNull(state.pendingOperator)
    }
}
