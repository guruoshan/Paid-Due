package com.dailymoneyregister.core.cloud.sync

import android.os.Bundle
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordExpenseCertificationTest {
    private val missingIntegration =
        "Missing dependency: isolated non-production Supabase project, protected test credentials, and real app-path CertificationExecutionEngine.Driver. Production project access is intentionally not accepted."

    @Test
    fun recordExpense_passes_through_real_driver() = runBlocking {
        val engine = FinancialCertificationExecutionEngine(
            driver = FinancialCertificationDriverSelector(missingIntegration),
            runId = "record-expense-certification-test",
            commitSha = "instrumentation"
        )
        val result = engine.executeAll()
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "financial-certification-evidence")
        engine.writeEvidenceBundle(output, result)
        val expense = result.operations.single { it.operationType == "RECORD_EXPENSE" }
        assertEquals("RECORD_EXPENSE must pass every real execution stage", FinancialCertificationExecutionEngine.Status.PASS, expense.status)
        assertNotNull(expense.operationId)
        assertTrue(
            "A passing operation cannot contain unexecuted stages",
            expense.stages.values.none { it.status == FinancialCertificationExecutionEngine.Status.NOT_RUN }
        )
    }

    @Test
    fun blocked_when_supabase_args_missing() = runBlocking {
        // An empty argument bundle is the explicit no-credentials fixture; no production URL can be inherited.
        val driver = FinancialCertificationDriverSelector(
            missingReason = missingIntegration,
            expense = SupabaseCertificationDriver(Bundle())
        )
        val result = FinancialCertificationExecutionEngine(
            driver = driver,
            runId = "record-expense-missing-supabase-args",
            commitSha = "instrumentation"
        ).executeAll()
        val expense = result.operations.single { it.operationType == "RECORD_EXPENSE" }
        assertEquals(FinancialCertificationExecutionEngine.Status.BLOCKED, expense.status)
        assertTrue(expense.failure.orEmpty().contains("Local Supabase arguments missing"))
        assertTrue(
            "A blocked operation must not be relabeled as passed",
            expense.stages.values.none { it.status == FinancialCertificationExecutionEngine.Status.PASS }
        )
    }
}
