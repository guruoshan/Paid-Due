package com.dailymoneyregister

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRateParser
import com.dailymoneyregister.feature.register.domain.CalculatorEngine
import com.dailymoneyregister.feature.register.domain.CalculatorState
import com.dailymoneyregister.feature.register.domain.KeypadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyCorrectnessTest {

    @Test
    fun rejectsExcessDecimalPrecision() {
        assertTrue(MoneyMinorUnits.parse("1.2345", 3).isFailure)
    }

    @Test
    fun rejectsMoneyOverflow() {
        assertTrue(
            MoneyMinorUnits.parse(
                "999999999999999999999999",
                3
            ).isFailure
        )
    }

    @Test
    fun rejectsMissingMoneyDigits() {
        assertTrue(MoneyMinorUnits.parse(".", 3).isFailure)
    }

    @Test
    fun overflowingCalculatorInputKeepsPreviousValidState() {
        val engine = CalculatorEngine(0)
        var state = CalculatorState()

        Long.MAX_VALUE.toString().forEach { digit ->
            state = engine.processKey(
                state,
                KeypadKey.Digit(digit)
            )
        }

        assertEquals(Long.MAX_VALUE, state.evaluatedAmount.amount)

        val previous = state

        val overflowed = engine.processKey(
            previous,
            KeypadKey.Digit('0')
        )

        assertEquals(previous, overflowed)
    }

    @Test
    fun overflowingMultiplicationDoesNotCorruptState() {
        val engine = CalculatorEngine(0)

        val state = CalculatorState(
            expression = "3037000500 * 3037000500",
            currentInput = "3037000500",
            evaluatedAmount = MoneyMinorUnits(3_037_000_500L),
            pendingOperator = "*",
            previousAmount = MoneyMinorUnits(3_037_000_500L)
        )

        assertEquals(
            state,
            engine.processKey(state, KeypadKey.Equals)
        )
    }

    @Test
    fun overflowingPercentageDoesNotCorruptState() {
        val engine = CalculatorEngine(0)

        val state = CalculatorState(
            expression = "overflow percent",
            currentInput = Long.MAX_VALUE.toString(),
            evaluatedAmount = MoneyMinorUnits(Long.MAX_VALUE),
            pendingOperator = "+",
            previousAmount = MoneyMinorUnits(Long.MAX_VALUE)
        )

        assertEquals(
            state,
            engine.processKey(state, KeypadKey.Percent)
        )
    }

    @Test
    fun strictTaxParserRejectsBadInput() {
        assertEquals(
            Integer.valueOf(525),
            TaxRateParser.parseToBpsOrNull("5.25")
        )

        assertNull(TaxRateParser.parseToBpsOrNull("5.999"))
        assertNull(TaxRateParser.parseToBpsOrNull("abc"))
        assertNull(TaxRateParser.parseToBpsOrNull("101"))
    }
}