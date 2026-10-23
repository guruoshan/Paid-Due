package com.dailymoneyregister.core.cloud.sync

/** Release-certification contract; execution evidence is required before PASS. */
internal object FinancialCertificationCaseRegistry {
    data class Case(
        val operationType: String,
        val requiredStages: List<String>,
        val requiredRecordFamilies: List<String>,
        val requiredReports: List<String>
    )

    val requiredStages = listOf(
        "create_operation", "verify_outbox", "upload", "verify_envelope", "verify_digest",
        "download", "before", "materialize", "after", "compare_records", "replay",
        "replay-invariance", "generate_reports", "assertions"
    )
    val requiredRecordFamilies = listOf(
        "operation_and_outbox", "transaction_and_items", "settlements",
        "append_only_events", "ledger_and_allocations", "customer_and_coupon_state",
        "cash_shift_state", "derived_read_models"
    )
    val requiredReports = listOf(
        "daily_financial_summary", "sales_and_refunds", "expenses",
        "credit_and_customer_balances", "coupon_balances", "payment_method_summary",
        "cash_shift_report", "financial_health", "export_totals"
    )
    val cases = listOf(
        "RECORD_SALE", "RECORD_REFUND", "RECORD_VOID", "RECORD_EXPENSE",
        "RECORD_CREDIT_REPAYMENT", "RECORD_COUPON_PURCHASE",
        "RECORD_PAYMENT_METHOD_CHANGE", "RECORD_AMOUNT_CORRECTION",
        "OPEN_CASH_SHIFT", "COUNT_CASH_SHIFT", "CLOSE_CASH_SHIFT",
        "RECORD_EXPENSE_CORRECTION", "RECORD_EXPENSE_VOID"
    ).map { type -> Case(type, requiredStages, requiredRecordFamilies, requiredReports) }

    fun initialEvidence(operationType: String): Map<String, String> {
        require(cases.any { it.operationType == operationType }) {
            "Operation type is not in the release certification registry: $operationType"
        }
        return requiredStages.associateWith { "NOT_RUN" }
    }
}
