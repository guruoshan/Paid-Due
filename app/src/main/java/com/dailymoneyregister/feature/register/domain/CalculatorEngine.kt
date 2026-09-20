package com.dailymoneyregister.feature.register.domain

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import java.math.BigInteger

sealed class KeypadKey {
    data class Digit(val char: Char) : KeypadKey() // '0'..'9'
    data object Decimal : KeypadKey() // '.'
    data object Plus : KeypadKey() // '+'
    data object Minus : KeypadKey() // '−'
    data object Multiply : KeypadKey() // '×'
    data object Percent : KeypadKey() // '%'
    data object Equals : KeypadKey() // '='
    data object Backspace : KeypadKey() // '⌫'
    data object Clear : KeypadKey() // 'C'
}

data class CalculatorState(
    val expression: String = "",
    val currentInput: String = "0",
    val evaluatedAmount: MoneyMinorUnits = MoneyMinorUnits.ZERO,
    val pendingOperator: String? = null,
    val previousAmount: MoneyMinorUnits? = null
)

class CalculatorEngine(val decimalPlaces: Int = 3) {

    fun processKey(state: CalculatorState, key: KeypadKey): CalculatorState {
        return try {
            when (key) {
            is KeypadKey.Digit -> handleDigit(state, key.char)
            KeypadKey.Decimal -> handleDecimal(state)
            KeypadKey.Plus -> handleOperator(state, "+")
            KeypadKey.Minus -> handleOperator(state, "−")
            KeypadKey.Multiply -> handleOperator(state, "×")
            KeypadKey.Percent -> handlePercent(state)
            KeypadKey.Equals -> handleEquals(state)
            KeypadKey.Backspace -> handleBackspace(state)
            KeypadKey.Clear -> CalculatorState()
            }
        } catch (_: ArithmeticException) {
            state
        }
    }

    private fun handleDigit(state: CalculatorState, digit: Char): CalculatorState {
        val newInput = if (state.currentInput == "0") {
            digit.toString()
        } else {
            // Check decimal places limit
            if (state.currentInput.contains('.')) {
                val decimals = state.currentInput.substringAfter('.')
                if (decimals.length >= decimalPlaces) return state
            }
            state.currentInput + digit
        }

        val parsed = MoneyMinorUnits.parse(newInput, decimalPlaces).getOrElse { return state }
        val newExpr = expressionWithUpdatedCurrentInput(state, newInput)

        return state.copy(
            currentInput = newInput,
            evaluatedAmount = parsed,
            expression = newExpr
        )
    }

    private fun handleDecimal(state: CalculatorState): CalculatorState {
        if (decimalPlaces == 0) return state
        if (state.currentInput.contains('.')) return state

        val newInput = "${state.currentInput}."
        val newExpr = expressionWithUpdatedCurrentInput(state, newInput)

        return state.copy(
            currentInput = newInput,
            expression = newExpr
        )
    }

    private fun handleOperator(state: CalculatorState, op: String): CalculatorState {
        var currentVal = state.evaluatedAmount

        // If there was a pending operation, evaluate it first
        if (state.pendingOperator != null && state.previousAmount != null) {
            currentVal = evaluateBinary(state.previousAmount, state.pendingOperator, currentVal)
        }

        val newExpr = if (state.pendingOperator != null && state.previousAmount != null) {
            "${state.expression.trimEnd()} $op "
        } else {
            "${state.currentInput} $op "
        }

        return state.copy(
            expression = newExpr,
            currentInput = "0",
            evaluatedAmount = currentVal,
            pendingOperator = op,
            previousAmount = currentVal
        )
    }

    private fun handlePercent(state: CalculatorState): CalculatorState {
        if (state.previousAmount != null && state.pendingOperator != null) {
            // E.g., 100 + 10% -> 10% of 100 = 10
            val base = state.previousAmount.amount
            val percentVal = state.evaluatedAmount.amount
            // percentResult = (base * percentVal) / (100 * 10^decimalPlaces)
            val divisor = BigInteger.valueOf(100L)
                .multiply(BigInteger.TEN.pow(decimalPlaces))
            val calculatedPercentMinor = BigInteger.valueOf(base)
                .multiply(BigInteger.valueOf(percentVal))
                .divide(divisor)
                .longValueExact()

            val percentAmount = MoneyMinorUnits(calculatedPercentMinor)
            val evaluated = evaluateBinary(state.previousAmount, state.pendingOperator, percentAmount)

            val percentStr = state.currentInput

            return CalculatorState(
                expression = "${state.expression.trimEnd()}% = ${evaluated.formatDisplay(decimalPlaces, "").trim()}",
                currentInput = evaluated.formatDisplay(decimalPlaces, "").trim(),
                evaluatedAmount = evaluated,
                pendingOperator = null,
                previousAmount = null
            )
        } else {
            // Direct percentage: 50% = 50 / 100
            val currentMinor = state.evaluatedAmount.amount
            val newMinor = currentMinor / 100L
            val percentAmount = MoneyMinorUnits(newMinor)
            return state.copy(
                currentInput = percentAmount.formatDisplay(decimalPlaces, "").trim(),
                evaluatedAmount = percentAmount,
                expression = "${state.currentInput}%"
            )
        }
    }

    private fun handleEquals(state: CalculatorState): CalculatorState {
        if (state.pendingOperator == null || state.previousAmount == null) {
            return state
        }

        val result = evaluateBinary(state.previousAmount, state.pendingOperator, state.evaluatedAmount)
        val resultStr = result.formatDisplay(decimalPlaces, "").trim()

        return CalculatorState(
            expression = "${state.expression} = $resultStr",
            currentInput = resultStr,
            evaluatedAmount = result,
            pendingOperator = null,
            previousAmount = null
        )
    }

    private fun handleBackspace(state: CalculatorState): CalculatorState {
        if (state.currentInput.length <= 1 || (state.currentInput.length == 2 && state.currentInput.startsWith("-"))) {
            val parsed = MoneyMinorUnits.ZERO
            return state.copy(
                currentInput = "0",
                evaluatedAmount = parsed,
                expression = expressionWithUpdatedCurrentInput(state, "0")
            )
        }

        val newInput = state.currentInput.dropLast(1)
        val cleanInput = if (newInput.endsWith(".")) newInput.dropLast(1) else newInput
        val parsed = MoneyMinorUnits.parse(cleanInput, decimalPlaces).getOrElse { return state }

        val newExpr = expressionWithUpdatedCurrentInput(state, newInput)

        return state.copy(
            currentInput = newInput,
            evaluatedAmount = parsed,
            expression = newExpr
        )
    }

    private fun expressionWithUpdatedCurrentInput(
        state: CalculatorState,
        newInput: String
    ): String {
        if (state.pendingOperator == null || state.previousAmount == null) {
            return newInput
        }

        val currentInput = state.currentInput
        return when {
            currentInput.isNotEmpty() && state.expression.endsWith(currentInput) ->
                state.expression.dropLast(currentInput.length) + newInput
            state.expression.endsWith(" ") ->
                state.expression + newInput
            else ->
                state.expression
        }
    }

    private fun evaluateBinary(left: MoneyMinorUnits, operator: String, right: MoneyMinorUnits): MoneyMinorUnits {
        return when (operator) {
            "+" -> left + right
            "−", "-" -> left - right
            "×", "*" -> {
                val divisor = MoneyMinorUnits.integerPowerOfTen(decimalPlaces)
                val leftBig = BigInteger.valueOf(left.amount)
                val rightBig = BigInteger.valueOf(right.amount)
                val prod = leftBig.multiply(rightBig)
                val res = prod.divide(BigInteger.valueOf(divisor))
                MoneyMinorUnits(res.longValueExact())
            }
            else -> right
        }
    }
}
