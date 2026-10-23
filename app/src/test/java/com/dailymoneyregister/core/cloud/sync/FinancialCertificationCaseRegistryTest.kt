package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialCertificationCaseRegistryTest {
    @Test
    fun registryContainsExactlyTheThirteenReleaseOperationTypes() {
        val expected = setOf(
            "RECORD_SALE", "RECORD_REFUND", "RECORD_VOID", "RECORD_EXPENSE",
            "RECORD_CREDIT_REPAYMENT", "RECORD_COUPON_PURCHASE",
            "RECORD_PAYMENT_METHOD_CHANGE", "RECORD_AMOUNT_CORRECTION",
            "OPEN_CASH_SHIFT", "COUNT_CASH_SHIFT", "CLOSE_CASH_SHIFT",
            "RECORD_EXPENSE_CORRECTION", "RECORD_EXPENSE_VOID"
        )
        assertEquals(13, FinancialCertificationCaseRegistry.cases.size)
        assertEquals(expected, FinancialCertificationCaseRegistry.cases.map { it.operationType }.toSet())
        assertEquals(13, FinancialCertificationCaseRegistry.cases.map { it.operationType }.distinct().size)
    }

    @Test
    fun everyOperationRequiresAllParityStagesRecordFamiliesAndReports() {
        FinancialCertificationCaseRegistry.cases.forEach { case ->
            assertEquals(FinancialCertificationCaseRegistry.requiredStages, case.requiredStages)
            assertEquals(FinancialCertificationCaseRegistry.requiredRecordFamilies, case.requiredRecordFamilies)
            assertEquals(FinancialCertificationCaseRegistry.requiredReports, case.requiredReports)
            assertTrue(case.requiredStages.contains("verify_digest"))
            assertTrue(case.requiredStages.contains("replay"))
            assertTrue(case.requiredStages.contains("compare_records"))
        }
    }

    @Test
    fun evidenceStartsNotRunAndCannotBeMistakenForPass() {
        val evidence = FinancialCertificationCaseRegistry.initialEvidence("RECORD_SALE")
        assertEquals(FinancialCertificationCaseRegistry.requiredStages.toSet(), evidence.keys)
        assertTrue(evidence.values.all { it == "NOT_RUN" })
        assertFalse(evidence.values.any { it == "PASS" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOperationCannotEnterCertificationRegistry() {
        FinancialCertificationCaseRegistry.initialEvidence("UNKNOWN_OPERATION")
    }
}
