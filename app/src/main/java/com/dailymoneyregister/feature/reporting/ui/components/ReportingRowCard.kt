package com.dailymoneyregister.feature.reporting.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.reporting.domain.ReportActivityType
import com.dailymoneyregister.feature.reporting.ui.ReportingRowUiModel

@Composable
internal fun ReportingRowCard(
    row: ReportingRowUiModel,
    onOpenCustomer: (String) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val flowColor = when (row.activityType) {
        ReportActivityType.SALE,
        ReportActivityType.CREDIT_SALE,
        ReportActivityType.CREDIT_REPAYMENT,
        ReportActivityType.COUPON_PURCHASE,
        ReportActivityType.COUPON_TOP_UP,
        ReportActivityType.COUPON_CREDIT_REPAYMENT ->
            if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
        ReportActivityType.REFUND,
        ReportActivityType.COUPON_REFUND,
        ReportActivityType.EXPENSE,
        ReportActivityType.EXPENSE_VOID,
        ReportActivityType.VOID ->
            if (isDarkTheme) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
        ReportActivityType.AMOUNT_CORRECTION -> when {
            row.amountText?.startsWith("-") == true ->
                if (isDarkTheme) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
            row.amountText?.startsWith("+") == true ->
                if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
            else -> if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF616161)
        }
        ReportActivityType.COUPON_BONUS ->
            if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
        ReportActivityType.COUPON_REDEMPTION,
        ReportActivityType.PAYMENT_METHOD_CHANGE ->
            if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF616161)
    }
    val typeLabel = when (row.activityType) {
        ReportActivityType.SALE -> stringResource(R.string.audit_status_sale_recorded)
        ReportActivityType.COUPON_PURCHASE -> stringResource(R.string.audit_status_coupon_funds_sold)
        ReportActivityType.COUPON_TOP_UP -> stringResource(R.string.audit_status_coupon_topup)
        ReportActivityType.CREDIT_SALE,
        ReportActivityType.CREDIT_REPAYMENT,
        ReportActivityType.COUPON_CREDIT_REPAYMENT -> stringResource(R.string.audit_status_credit)
        ReportActivityType.EXPENSE -> stringResource(R.string.audit_status_expense_recorded)
        ReportActivityType.EXPENSE_VOID -> stringResource(R.string.audit_status_expense_void)
        ReportActivityType.REFUND,
        ReportActivityType.COUPON_REFUND -> stringResource(R.string.audit_status_refund_to_coupon)
        ReportActivityType.VOID -> stringResource(R.string.audit_status_void)
        ReportActivityType.AMOUNT_CORRECTION -> stringResource(R.string.audit_status_amount_correction)
        ReportActivityType.PAYMENT_METHOD_CHANGE -> stringResource(R.string.audit_status_payment_method_change)
        ReportActivityType.COUPON_REDEMPTION -> stringResource(R.string.audit_status_coupon_use)
        ReportActivityType.COUPON_BONUS -> stringResource(R.string.audit_status_other_income)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.widthIn(min = 112.dp, max = 180.dp).heightIn(min = 24.dp),
                    color = flowColor.copy(alpha = if (isDarkTheme) 0.20f else 0.12f),
                    contentColor = flowColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            typeLabel,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = row.occurredAtText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    textAlign = TextAlign.End
                )
            }

            row.amountText?.let { amount ->
                Text(
                    text = amount,
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.ExtraBold,
                    color = flowColor,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2
                )
            }

            row.referenceText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (row.customerName != null || row.customerPhone != null) {
                val customerText = listOfNotNull(row.customerName, row.customerPhone)
                    .joinToString(" · ")
                Text(
                    text = customerText,
                    modifier = if (row.customerId != null) {
                        Modifier.clickable { onOpenCustomer(row.customerId) }
                    } else Modifier,
                    color = if (row.customerId != null) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            row.paymentText?.let {
                Text(
                    text = stringResource(R.string.reporting_payment_value, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            row.reasonText?.let {
                Text(
                    text = stringResource(R.string.reporting_reason_value, it),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = listOfNotNull(row.branchName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
