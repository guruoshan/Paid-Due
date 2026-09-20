package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.core.model.currency.CurrencyConfig

import com.dailymoneyregister.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.ui.theme.RegisterColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class RecentLifecycleBadge {
    VOIDED,
    PARTIALLY_REFUNDED,
    FULLY_REFUNDED,
    CORRECTED
}

internal fun recentLifecycleBadges(
    transaction: FinancialTransactionView
): List<RecentLifecycleBadge> = buildList {
    if (transaction.isVoided) add(RecentLifecycleBadge.VOIDED)
    if (transaction.isFullyRefunded) {
        add(RecentLifecycleBadge.FULLY_REFUNDED)
    } else if (transaction.isPartiallyRefunded) {
        add(RecentLifecycleBadge.PARTIALLY_REFUNDED)
    }
    if (transaction.hasAmountCorrection) add(RecentLifecycleBadge.CORRECTED)
}

@Composable
fun RecentTransactionsPanel(
    transactions: List<FinancialTransactionView>,
    isExpanded: Boolean,
    currency: CurrencyConfig,
    timezoneId: String = "Asia/Muscat",
    onToggleExpanded: () -> Unit,
    maxListHeight: androidx.compose.ui.unit.Dp = 220.dp,
    modifier: Modifier = Modifier
) {
    val todayTotal = transactions.fold(MoneyMinorUnits.ZERO) { acc, tx ->
        acc + tx.netAmount
    }
    val todayTotalFormatted = todayTotal.formatDisplay(currency)
    val listState = rememberLazyListState()
    val newestTransactionId = transactions.firstOrNull()?.id
    val previousNewestId = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(newestTransactionId) {
        val previous = previousNewestId.value
        if (newestTransactionId != null && previous != null && newestTransactionId != previous) {
            listState.animateScrollToItem(0)
        }
        previousNewestId.value = newestTransactionId
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = stringResource(R.string.recent_transactions),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(com.dailymoneyregister.R.string.todays_sales, transactions.size),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(com.dailymoneyregister.R.string.today_total, todayTotalFormatted),
                        fontWeight = FontWeight.Bold,
                        color = RegisterColors.CashPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(R.string.toggle_panel)
                    )
                }
            }

            // Expanded List View
            AnimatedVisibility(visible = isExpanded) {
                if (transactions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(com.dailymoneyregister.R.string.no_transactions_today),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxListHeight)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(transactions, key = { it.id }) { tx ->
                            RecentTransactionRow(
                                transaction = tx,
                                currency = currency,
                                timezoneId = timezoneId
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTransactionRow(
    transaction: FinancialTransactionView,
    currency: CurrencyConfig,
    timezoneId: String
) {
    val timeStr = remember(transaction.createdAt, timezoneId) {
        try {
            val zoneId = ZoneId.of(timezoneId)
            val zonedDateTime = Instant.ofEpochMilli(transaction.createdAt).atZone(zoneId)
            zonedDateTime.format(DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.getDefault()))
        } catch (_: Exception) {
            java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(transaction.createdAt))
        }
    }
    val amountFormatted = transaction.netAmount.formatDisplay(currency)
    val lifecycleBadges = recentLifecycleBadges(transaction)
    val paymentLabel = when (transaction.currentPaymentCategory) {
        PaymentCategory.CASH -> stringResource(R.string.cash_sale_label)
        PaymentCategory.ONLINE -> stringResource(R.string.online_sale_label)
        PaymentCategory.CREDIT -> stringResource(R.string.credit_sale_label)
        PaymentCategory.COUPON -> stringResource(R.string.coupon_sale_label)
        null -> stringResource(R.string.sale_label)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = paymentLabel,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                if (lifecycleBadges.isNotEmpty()) {
                    val lifecycleLabels = mutableListOf<String>()
                    for (badge in lifecycleBadges) {
                        lifecycleLabels += stringResource(
                            when (badge) {
                                RecentLifecycleBadge.VOIDED -> R.string.recent_status_voided
                                RecentLifecycleBadge.PARTIALLY_REFUNDED -> R.string.recent_status_partially_refunded
                                RecentLifecycleBadge.FULLY_REFUNDED -> R.string.recent_status_fully_refunded
                                RecentLifecycleBadge.CORRECTED -> R.string.recent_status_corrected
                            }
                        )
                    }
                    Text(
                        text = lifecycleLabels.joinToString(" \u2022 "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = timeStr,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            Text(
                text = stringResource(R.string.positive_amount, amountFormatted),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontWeight = FontWeight.Bold,
                color = RegisterColors.CashPrimary,
                fontSize = 15.sp,
                maxLines = 1
            )
        }
    }
}
