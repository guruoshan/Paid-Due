package com.dailymoneyregister.feature.reporting.pdf

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfDocument
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dailymoneyregister.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.reporting.domain.ReportActivityType
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriodPreset
import com.dailymoneyregister.feature.reporting.ui.ReportingUiState
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ReportingPdfActions(state: ReportingUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pdfPdfTitleDaily = stringResource(R.string.pdf_title_daily)
    val pdfPdfTitleMonthly = stringResource(R.string.pdf_title_monthly)
    val pdfPdfTitleCustom = stringResource(R.string.pdf_title_custom)
    val pdfPdfTitleWeekly = stringResource(R.string.pdf_title_weekly)
    val pdfPdfMetricGrossSales = stringResource(R.string.pdf_metric_gross_sales)
    val pdfPdfMetricNetSales = stringResource(R.string.pdf_metric_net_sales)
    val pdfPdfMetricExpenses = stringResource(R.string.pdf_metric_expenses)
    val pdfPdfMetricRefund = stringResource(R.string.pdf_metric_refund)
    val pdfPdfMetricCreditSales = stringResource(R.string.pdf_metric_credit_sales)
    val pdfPdfMetricCreditRepaid = stringResource(R.string.pdf_metric_credit_repaid)
    val pdfPdfMetricCreditOutstanding = stringResource(R.string.pdf_metric_credit_outstanding)
    val pdfCreditOutstandingNote = stringResource(R.string.pdf_credit_outstanding_note)
    val pdfPdfMetricExpectedCash = stringResource(R.string.pdf_metric_expected_cash)
    val pdfPdfNotAvailable = stringResource(R.string.pdf_not_available)
    val pdfPdfActivitySale = stringResource(R.string.pdf_activity_sale)
    val pdfPdfActivityCreditSale = stringResource(R.string.pdf_activity_credit_sale)
    val pdfPdfActivityCreditRepayment = stringResource(R.string.pdf_activity_credit_repayment)
    val pdfPdfActivityCouponPurchase = stringResource(R.string.pdf_activity_coupon_purchase)
    val pdfPdfActivityCouponTopUp = stringResource(R.string.pdf_activity_coupon_top_up)
    val pdfPdfActivityCouponRedemption = stringResource(R.string.pdf_activity_coupon_redemption)
    val pdfPdfActivityCouponRefund = stringResource(R.string.pdf_activity_coupon_refund)
    val pdfPdfActivityCouponBonus = stringResource(R.string.pdf_activity_coupon_bonus)
    val pdfPdfActivityCouponCreditRepayment = stringResource(R.string.pdf_activity_coupon_credit_repayment)
    val pdfPdfActivityRefund = stringResource(R.string.pdf_activity_refund)
    val pdfPdfActivityExpense = stringResource(R.string.pdf_activity_expense)
    val pdfPdfActivityExpenseVoid = stringResource(R.string.pdf_activity_expense_void)
    val pdfPdfActivityVoid = stringResource(R.string.pdf_activity_void)
    val pdfPdfActivityAmountCorrection = stringResource(R.string.pdf_activity_amount_correction)
    val pdfPdfActivityPaymentMethodChange = stringResource(R.string.pdf_activity_payment_method_change)
    val pdfPdfStatusReportNotReady = stringResource(R.string.pdf_status_report_not_ready)
    val pdfPdfStatusSaved = stringResource(R.string.pdf_status_saved)
    val pdfPdfShareChooser = stringResource(R.string.pdf_share_chooser)
    val pdfPdfStatusShareOpened = stringResource(R.string.pdf_status_share_opened)
    val pdfPdfStatusPrintOpened = stringResource(R.string.pdf_status_print_opened)
    val pdfPdfStatusExportFailedFormat = stringResource(R.string.pdf_status_export_failed, "%s")
    val pdfPdfStatusStoragePermission = stringResource(R.string.pdf_status_storage_permission)
    val pdfPdfFileCreateFailed = stringResource(R.string.pdf_file_create_failed)
    val pdfPdfFileOpenFailed = stringResource(R.string.pdf_file_open_failed)
    val pdfPdfDirectoryCreateFailed = stringResource(R.string.pdf_directory_create_failed)
    val pdfPdfPrintWriteFailed = stringResource(R.string.pdf_print_write_failed)
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    fun generateSnapshot(): ReportingPdfSnapshot? {
        val readModel = state.readModel ?: return null
        val session = SessionManager.currentSession.value ?: return null
        val branch = state.branchOptions.firstOrNull { it.id == state.selectedBranchId }
        val zoneId = runCatching { ZoneId.of(branch?.timezoneId ?: session.branch.timezoneId) }
            .getOrDefault(ZoneId.systemDefault())
        val decimals = branch?.currencyDecimalPlaces ?: session.branch.currencyDecimalPlaces
        val symbol = branch?.currencySymbol ?: session.branch.currencySymbol
        fun money(amount: Long): String {
            val number = BigDecimal.valueOf(amount, decimals).abs().toPlainString()
            val magnitude = if (symbol.isBlank()) number else "$symbol $number"
            return when {
                amount > 0L -> "+ $magnitude"
                amount < 0L -> "- $magnitude"
                else -> magnitude
            }
        }
        fun tone(amount: Long) = when {
            amount > 0L -> ReportValueTone.POSITIVE
            amount < 0L -> ReportValueTone.NEGATIVE
            else -> ReportValueTone.OPERATIONAL
        }
        val summary = readModel.summary
        val repaymentTotal = Math.addExact(
            Math.addExact(summary.cashCreditRepayments.amount, summary.onlineCreditRepayments.amount),
            summary.couponCreditRepayments.amount
        )
        val title = when (state.selectedPreset) {
            ReportingPeriodPreset.TODAY, ReportingPeriodPreset.YESTERDAY -> pdfPdfTitleDaily
            ReportingPeriodPreset.THIS_MONTH -> pdfPdfTitleMonthly
            ReportingPeriodPreset.CUSTOM -> pdfPdfTitleCustom
            ReportingPeriodPreset.THIS_WEEK -> pdfPdfTitleWeekly
        }
        val fromDate = Instant.ofEpochMilli(readModel.scope.period.fromInclusive).atZone(zoneId).toLocalDate()
        val toDate = Instant.ofEpochMilli(readModel.scope.period.toExclusive - 1L).atZone(zoneId).toLocalDate()
        val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy")
        val periodLabel = if (fromDate == toDate) fromDate.format(dateFormat)
            else "${fromDate.format(dateFormat)} – ${toDate.format(dateFormat)}"
        val reportDate = LocalDate.now(zoneId).format(dateFormat)
        val metrics = listOf(
            ReportPdfMetric(pdfPdfMetricGrossSales, money(summary.periodSaleInflow.amount), tone(summary.periodSaleInflow.amount)),
            ReportPdfMetric(pdfPdfMetricNetSales, money(summary.periodNetSalesActivity.amount), tone(summary.periodNetSalesActivity.amount)),
            ReportPdfMetric(pdfPdfMetricExpenses, money(-summary.totalExpenses.amount), ReportValueTone.NEGATIVE),
            ReportPdfMetric(pdfPdfMetricRefund, money(summary.periodRefundEffect.amount), tone(summary.periodRefundEffect.amount)),
            ReportPdfMetric(pdfPdfMetricCreditSales, money(summary.creditSalesOriginated.amount), tone(summary.creditSalesOriginated.amount)),
            ReportPdfMetric(pdfPdfMetricCreditRepaid, money(repaymentTotal), tone(repaymentTotal)),
            ReportPdfMetric(pdfPdfMetricCreditOutstanding, money(summary.creditOutstanding.amount), tone(summary.creditOutstanding.amount)),
            ReportPdfMetric(
                pdfPdfMetricExpectedCash,
                summary.expectedCash?.amount?.let(::money) ?: pdfPdfNotAvailable,
                summary.expectedCash?.amount?.let(::tone) ?: ReportValueTone.OPERATIONAL
            )
        )
        val rows = state.rows.map { row ->
            val amountText = row.amountText ?: "—"
            val valueTone = when {
                row.amountText == null || row.activityType == ReportActivityType.PAYMENT_METHOD_CHANGE ->
                    ReportValueTone.OPERATIONAL
                amountText.trim().startsWith("-") -> ReportValueTone.NEGATIVE
                else -> ReportValueTone.POSITIVE
            }
            ReportPdfTransaction(
                date = row.occurredAtText,
                type = when (row.activityType) {
                    ReportActivityType.SALE -> pdfPdfActivitySale
                    ReportActivityType.CREDIT_SALE -> pdfPdfActivityCreditSale
                    ReportActivityType.CREDIT_REPAYMENT -> pdfPdfActivityCreditRepayment
                    ReportActivityType.COUPON_PURCHASE -> pdfPdfActivityCouponPurchase
                    ReportActivityType.COUPON_TOP_UP -> pdfPdfActivityCouponTopUp
                    ReportActivityType.COUPON_REDEMPTION -> pdfPdfActivityCouponRedemption
                    ReportActivityType.COUPON_REFUND -> pdfPdfActivityCouponRefund
                    ReportActivityType.COUPON_BONUS -> pdfPdfActivityCouponBonus
                    ReportActivityType.COUPON_CREDIT_REPAYMENT -> pdfPdfActivityCouponCreditRepayment
                    ReportActivityType.REFUND -> pdfPdfActivityRefund
                    ReportActivityType.EXPENSE -> pdfPdfActivityExpense
                    ReportActivityType.EXPENSE_VOID -> pdfPdfActivityExpenseVoid
                    ReportActivityType.VOID -> pdfPdfActivityVoid
                    ReportActivityType.AMOUNT_CORRECTION -> pdfPdfActivityAmountCorrection
                    ReportActivityType.PAYMENT_METHOD_CHANGE -> pdfPdfActivityPaymentMethodChange
                },
                reference = row.referenceText.orEmpty(),
                customer = listOfNotNull(row.customerName, row.customerPhone).joinToString(" · "),
                payment = row.paymentText.orEmpty(),
                amount = amountText,
                tone = valueTone
            )
        }
        return ReportingPdfSnapshot(
            businessName = session.business.name,
            branchName = branch?.name ?: session.branch.name,
            reportTitle = title,
            reportDate = reportDate,
            periodLabel = periodLabel,
            metrics = metrics,
            metricNote = pdfCreditOutstandingNote,
            transactions = rows
        )
    }

    fun runAction(action: String) {
        val snapshot = generateSnapshot()
        if (snapshot == null) {
            status = pdfPdfStatusReportNotReady
            return
        }
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ReportingPdfRenderer.render(context, snapshot) }
                when (action) {
                    "download" -> {
                        withContext(Dispatchers.IO) { savePdfToDownloads(context, file) }
                        status = pdfPdfStatusSaved
                    }
                    "share" -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, pdfPdfShareChooser))
                        status = pdfPdfStatusShareOpened
                    }
                    "print" -> {
                        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        printManager.print(
                            snapshot.reportTitle,
                            ReportingPdfPrintAdapter(file, context),
                            PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                .build()
                        )
                        status = pdfPdfStatusPrintOpened
                    }
                }
            } catch (error: Exception) {
                status = String.format(Locale.getDefault(), pdfPdfStatusExportFailedFormat, error.message ?: pdfPdfNotAvailable)
            }
        }
    }

    val enabled = !state.isLoading && state.error == null && state.readModel != null
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runAction("download")
        else status = pdfPdfStatusStoragePermission
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pdf_export_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.pdf_export_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else runAction("download")
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.pdf_download)) }
                OutlinedButton(onClick = { runAction("share") }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pdf_share))
                }
                OutlinedButton(onClick = { runAction("print") }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pdf_print))
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun savePdfToDownloads(context: Context, source: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PaidDue/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error(context.getString(R.string.pdf_file_create_failed))
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error(context.getString(R.string.pdf_file_open_failed))
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    } else {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PaidDue"
        ).apply { if (!exists() && !mkdirs()) error(context.getString(R.string.pdf_directory_create_failed)) }
        source.copyTo(File(directory, source.name), overwrite = true)
    }
}

private class ReportingPdfPrintAdapter(private val source: File, private val context: Context) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(source.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out android.print.PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onWriteCancelled()
            return
        }
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }
            callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
        } catch (error: Exception) {
            callback.onWriteFailed(error.message ?: context.getString(R.string.pdf_print_write_failed))
        }
    }
}
