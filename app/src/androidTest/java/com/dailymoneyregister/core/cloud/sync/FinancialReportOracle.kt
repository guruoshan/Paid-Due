package com.dailymoneyregister.core.cloud.sync

import org.json.JSONObject

/**
 * Independent report oracle. Inputs are immutable fixture facts captured before
 * running production report code; expected totals are calculated here using minor units.
 */
object FinancialReportOracle {
    data class Entry(
        val operationType: String,
        val amountMinor: Long = 0,
        val paymentMethod: String? = null,
        val fundingMinor: Long? = null,
        val refundableBalanceBeforeMinor: Long? = null,
        val voidableBalanceBeforeMinor: Long? = null,
        val creditOpeningBalanceMinor: Long? = null,
        val creditRepaymentsMinor: Long? = null,
        val creditCurrentBalanceMinor: Long? = null,
        val couponPurchasesMinor: Long? = null,
        val couponRedemptionsMinor: Long? = null,
        val couponWalletBalanceMinor: Long? = null,
        val paymentMethodValueBeforeMinor: Long? = null,
        val paymentMethodValueAfterMinor: Long? = null,
        val correctionDeltaMinor: Long? = null,
        val expenseOriginalMinor: Long? = null,
        val expenseCorrectionsMinor: Long? = null,
        val expenseVoidsMinor: Long? = null,
        val expenseCurrentMinor: Long? = null,
        val cashOpeningMinor: Long? = null,
        val cashMovementsMinor: Long? = null,
        val cashClosingMinor: Long? = null
    )

    data class Assertion(val name: String, val passed: Boolean, val expected: Long?, val actual: Long?, val detail: String)
    data class Result(val expectedReportsJson: String, val assertions: List<Assertion>) {
        val passed: Boolean get() = assertions.all { it.passed }
        fun requirePass() = check(passed) {
            assertions.filterNot { it.passed }.joinToString("\n") { it.name + ": " + it.detail }
        }
    }

    fun calculate(entries: List<Entry>): Result {
        val sales = sum(entries.filter { it.operationType == "RECORD_SALE" }.map { it.amountMinor })
        val refunds = sum(entries.filter { it.operationType == "RECORD_REFUND" }.map { it.amountMinor })
        val voids = sum(entries.filter { it.operationType == "RECORD_VOID" }.map { it.amountMinor })
        val expenses = Math.subtractExact(
            Math.addExact(
                sum(entries.filter { it.operationType == "RECORD_EXPENSE" }.map { it.amountMinor }),
                sum(entries.filter { it.operationType == "RECORD_EXPENSE_CORRECTION" }.map { it.amountMinor })
            ),
            sum(entries.filter { it.operationType == "RECORD_EXPENSE_VOID" }.map { it.amountMinor })
        )
        val credits = sum(entries.filter { it.operationType == "RECORD_CREDIT_REPAYMENT" }.map { it.amountMinor })
        val coupons = sum(entries.filter { it.operationType == "RECORD_COUPON_PURCHASE" }.map { it.amountMinor })
        val byMethod = entries.filter { it.operationType == "RECORD_SALE" && it.paymentMethod != null }
            .groupBy { requireNotNull(it.paymentMethod) }
            .toSortedMap()
            .mapValues { (_, rows) -> sum(rows.map { it.amountMinor }) }

        val assertions = mutableListOf<Assertion>()
        entries.forEachIndexed { index, e ->
            val prefix = "entry[$index]/" + e.operationType
            e.fundingMinor?.let { assertions += equal(prefix + "/sale-funding-conservation", e.amountMinor, it,
                "sale funding must equal transaction total") }
            if (e.operationType == "RECORD_VOID" && e.voidableBalanceBeforeMinor != null) {
                assertions += Assertion(prefix + "/void-limit",
                    e.amountMinor >= 0 && e.amountMinor <= e.voidableBalanceBeforeMinor,
                    e.voidableBalanceBeforeMinor, e.amountMinor,
                    "void amount must be non-negative and no greater than remaining voidable balance")
            }
            if (e.operationType == "OPEN_CASH_SHIFT" && e.cashOpeningMinor != null) {
                assertions += equal(prefix + "/cash-opening", e.amountMinor, e.cashOpeningMinor,
                    "opening shift amount must match the opening cash fixture")
            }
            if (e.operationType == "RECORD_EXPENSE" && e.expenseOriginalMinor != null &&
                e.expenseCorrectionsMinor != null && e.expenseVoidsMinor != null && e.expenseCurrentMinor != null) {
                val expected = Math.subtractExact(Math.addExact(e.expenseOriginalMinor, e.expenseCorrectionsMinor), e.expenseVoidsMinor)
                assertions += equal(prefix + "/expense-create-conservation", expected, e.expenseCurrentMinor,
                    "expense create amount must reconcile to current expense value")
            }
            if (e.operationType == "RECORD_REFUND" && e.refundableBalanceBeforeMinor != null) {
                assertions += Assertion(prefix + "/refund-limit",
                    e.amountMinor >= 0 && e.amountMinor <= e.refundableBalanceBeforeMinor,
                    e.refundableBalanceBeforeMinor, e.amountMinor,
                    "refund amount must be non-negative and no greater than refundable balance")
            }
            if (e.creditOpeningBalanceMinor != null && e.creditRepaymentsMinor != null && e.creditCurrentBalanceMinor != null) {
                val expected = Math.subtractExact(e.creditOpeningBalanceMinor, e.creditRepaymentsMinor)
                assertions += equal(prefix + "/credit-balance-conservation", expected, e.creditCurrentBalanceMinor,
                    "opening balance minus repayments must equal current balance")
            }
            if (e.couponPurchasesMinor != null && e.couponRedemptionsMinor != null && e.couponWalletBalanceMinor != null) {
                val expected = Math.subtractExact(e.couponPurchasesMinor, e.couponRedemptionsMinor)
                assertions += equal(prefix + "/coupon-wallet-conservation", expected, e.couponWalletBalanceMinor,
                    "purchases minus redemptions must equal wallet balance")
            }
            if (e.paymentMethodValueBeforeMinor != null && e.paymentMethodValueAfterMinor != null) {
                assertions += equal(prefix + "/payment-method-value-conservation",
                    e.paymentMethodValueBeforeMinor, e.paymentMethodValueAfterMinor,
                    "payment-method change must preserve total value")
            }
            e.correctionDeltaMinor?.let { delta ->
                assertions += Assertion(prefix + "/correction-delta", delta == e.amountMinor,
                    e.amountMinor, delta, "recorded correction delta must equal fixture delta")
            }
            if (e.expenseOriginalMinor != null && e.expenseCorrectionsMinor != null &&
                e.expenseVoidsMinor != null && e.expenseCurrentMinor != null) {
                val expected = Math.subtractExact(
                    Math.addExact(e.expenseOriginalMinor, e.expenseCorrectionsMinor), e.expenseVoidsMinor)
                assertions += equal(prefix + "/expense-reconciliation", expected, e.expenseCurrentMinor,
                    "original expense plus corrections minus voids must equal current expense")
            }
            if (e.cashOpeningMinor != null && e.cashMovementsMinor != null && e.cashClosingMinor != null) {
                val expected = Math.addExact(e.cashOpeningMinor, e.cashMovementsMinor)
                assertions += equal(prefix + "/cash-shift-conservation", expected, e.cashClosingMinor,
                    "opening cash plus net movements must equal closing cash")
            }
        }

        val report = JSONObject()
            .put("currency_unit", "minor")
            .put("sales_minor", sales)
            .put("refunds_minor", refunds)
            .put("voids_minor", voids)
            .put("expenses_minor", expenses)
            .put("credit_repayments_minor", credits)
            .put("coupon_purchases_minor", coupons)
            .put("payment_method_sales_minor", JSONObject().also { obj -> byMethod.forEach { (k, v) -> obj.put(k, v) } })
        return Result(FinancialSnapshotCollector.canonical(report), assertions)
    }

    private fun sum(values: List<Long>): Long = values.fold(0L) { acc, value -> Math.addExact(acc, value) }

    private fun equal(name: String, expected: Long, actual: Long, detail: String) =
        Assertion(name, expected == actual, expected, actual, detail)
}
