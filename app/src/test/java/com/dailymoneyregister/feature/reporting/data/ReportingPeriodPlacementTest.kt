package com.dailymoneyregister.feature.reporting.data

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.AmountCorrectionReportRow
import com.dailymoneyregister.feature.reporting.domain.ReportBranch
import com.dailymoneyregister.feature.reporting.domain.ReportCashier
import com.dailymoneyregister.feature.reporting.domain.ReportPerson
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportingPeriodPlacementTest {

    private val branch = ReportBranch("branch-1", "Main")
    private val cashier = ReportCashier("admin-1", "Admin")
    private val person = ReportPerson("customer-1", "Customer", "+96890000000")

    @Test
    fun amountCorrectionInLaterPeriodProducesOnlyItsSignedDelta() {
        // Original sale was 1.000 on an earlier day. This correction happens later and raises it by 0.250.
        val laterCorrection = AmountCorrectionReportRow(
            stableId = "correction:later",
            operationId = "op-later",
            transactionId = "old-sale",
            reference = "OLD",
            occurredAt = 900L,
            currencyCode = "OMR",
            branch = branch,
            cashier = cashier,
            person = person,
            eventId = "later",
            previousAmount = MoneyMinorUnits(1_000L),
            correctedAmount = MoneyMinorUnits(1_250L),
            reason = null
        )

        val summary = ReportingAggregator.build(listOf(laterCorrection))

        assertEquals(250L, summary.periodCorrectionEffect.amount)
        assertEquals(250L, summary.periodNetSalesActivity.amount)
        assertEquals(0L, summary.periodSaleInflow.amount)
    }
}
