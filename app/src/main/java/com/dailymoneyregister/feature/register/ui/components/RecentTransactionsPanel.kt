package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.core.model.currency.CurrencyConfig

import com.dailymoneyregister.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.core.model.financial.FinancialTransactionView
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.financial.RecentActivityType
import com.dailymoneyregister.core.model.financial.RecentActivitySourceType
import com.dailymoneyregister.core.model.financial.RecentActivityTotals
import com.dailymoneyregister.core.model.financial.RecentFinancialActivity
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
    CORRECTED,
    PAYMENT_METHOD_CHANGED
}

internal fun recentLifecycleBadges(
    transaction: FinancialTransactionView
): List<RecentLifecycleBadge> = buildList {
    val states = transaction.lifecycleStates
    if (states.isNotEmpty()) {
        if (FinancialLifecycleState.VOIDED in states) add(RecentLifecycleBadge.VOIDED)
        if (FinancialLifecycleState.FULLY_REFUNDED in states) {
            add(RecentLifecycleBadge.FULLY_REFUNDED)
        } else if (FinancialLifecycleState.PARTIALLY_REFUNDED in states) {
            add(RecentLifecycleBadge.PARTIALLY_REFUNDED)
        }
        if (FinancialLifecycleState.CORRECTED in states) add(RecentLifecycleBadge.CORRECTED)
        if (FinancialLifecycleState.PAYMENT_METHOD_CHANGED in states) {
            add(RecentLifecycleBadge.PAYMENT_METHOD_CHANGED)
        }
    } else {
        if (transaction.isVoided) add(RecentLifecycleBadge.VOIDED)
        if (transaction.isFullyRefunded) {
            add(RecentLifecycleBadge.FULLY_REFUNDED)
        } else if (transaction.isPartiallyRefunded) {
            add(RecentLifecycleBadge.PARTIALLY_REFUNDED)
        }
        if (transaction.hasAmountCorrection) add(RecentLifecycleBadge.CORRECTED)
    }
}

@Composable
fun RecentTransactionsPanel(
    transactions: List<RecentFinancialActivity>,
    isExpanded: Boolean,
    currency: CurrencyConfig,
    timezoneId: String = "Asia/Muscat",
    onToggleExpanded: () -> Unit,
    onTransactionSelected: (String) -> Unit = {},
    onExpenseSelected: (String) -> Unit = {},
    maxListHeight: androidx.compose.ui.unit.Dp = 180.dp,
    modifier: Modifier = Modifier,
    compactMaxListHeight: androidx.compose.ui.unit.Dp = 140.dp
) {
    // BOARDLOCK_COUPON_TODAY_TOTAL
    // Do not sum raw signedEffect here: Coupon redemption is an
    // internal settlement of previously funded value.
    val todayTotal =
        RecentActivityTotals.todayTotal(transactions)
    val todayTotalFormatted = todayTotal.formatDisplay(currency)
    val listState = rememberLazyListState()
    val newestTransactionId = transactions.firstOrNull()?.activityId
    val previousNewestId = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(newestTransactionId, isExpanded) {
        val previous = previousNewestId.value
        if (isExpanded && newestTransactionId != null &&
            (previous == null || newestTransactionId != previous)) {
            listState.scrollToItem(0)
        }
        // Reopening the expanded list starts at the newest row too.
        previousNewestId.value = if (isExpanded) newestTransactionId else null
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
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                        text = stringResource(com.dailymoneyregister.R.string.recent_activity_count, transactions.size),
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

            // Content stays visible in both modes; only expanded Recent scrolls.
            if (transactions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(com.dailymoneyregister.R.string.no_transactions_today),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else if (isExpanded) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(transactions, key = { it.activityId }) { tx ->
                        RecentTransactionRow(
                        transaction = tx,
                        currency = currency,
                        timezoneId = timezoneId,
                        onTransactionSelected = onTransactionSelected,
                    onExpenseSelected = onExpenseSelected
                )
                    }
                }
            } else {
                CompactRecentRows(
                    transactions = transactions,
                    currency = currency,
                    timezoneId = timezoneId,
                    onTransactionSelected = onTransactionSelected,
                onExpenseSelected = onExpenseSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = compactMaxListHeight * LocalDensity.current.fontScale.coerceAtLeast(1f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

internal class RecentRowHeightBudget(private val maxHeight: Int, private val spacing: Int) {
    var usedHeight: Int = 0
        private set

    fun add(rowHeight: Int): Boolean {
        val nextHeight = usedHeight + (if (usedHeight == 0) 0 else spacing) + rowHeight
        if (nextHeight > maxHeight) return false
        usedHeight = nextHeight
        return true
    }
}

@Composable
private fun CompactRecentRows(
    transactions: List<RecentFinancialActivity>,
    currency: CurrencyConfig,
    timezoneId: String,
    onTransactionSelected: (String) -> Unit,
    onExpenseSelected: (String) -> Unit,
    modifier: Modifier
) {
    // Measure real row heights, including badges and font scaling. Stop before
    // the first incomplete row, without a fixed count or a scroll container.
    SubcomposeLayout(modifier = modifier) { constraints ->
        val spacing = 3.dp.roundToPx()
        val rows = mutableListOf<Pair<Placeable, Int>>()
        val budget = RecentRowHeightBudget(constraints.maxHeight, spacing)
        for (transaction in transactions) {
            val row = subcompose(transaction.activityId) {
                RecentTransactionRow(
                    transaction = transaction,
                    currency = currency,
                    timezoneId = timezoneId,
                    onTransactionSelected = onTransactionSelected,
                    onExpenseSelected = onExpenseSelected
                )
            }.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val top = budget.usedHeight + if (rows.isEmpty()) 0 else spacing
            if (!budget.add(row.height)) break
            rows += row to top
        }
        layout(constraints.maxWidth, constraints.constrainHeight(budget.usedHeight)) {
            rows.forEach { (row, top) -> row.placeRelative(0, top) }
        }
    }
}

@Composable
private fun RecentTransactionRow(
    transaction: RecentFinancialActivity,
    currency: CurrencyConfig,
    timezoneId: String,
    onTransactionSelected: (String) -> Unit,
    onExpenseSelected: (String) -> Unit
) {
    val timeStr = remember(transaction.occurredAt, timezoneId) {
        val zoneId = ZoneId.of(timezoneId)
        val zonedDateTime = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId)
        zonedDateTime.format(DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.getDefault()))
    }
    val isDarkTheme = isSystemInDarkTheme()
    val flowColor = when (transaction.type) {
        RecentActivityType.MERCHANDISE_SALE,
        RecentActivityType.COUPON_SELL,
        RecentActivityType.COUPON_TOP_UP,
        RecentActivityType.CREDIT_REPAYMENT ->
            if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
        RecentActivityType.EXPENSE,
        RecentActivityType.REFUND,
        RecentActivityType.REFUND_TO_COUPON,
        RecentActivityType.VOID ->
            if (isDarkTheme) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
        RecentActivityType.AMOUNT_CORRECTION -> when {
            transaction.signedEffect.isNegative ->
                if (isDarkTheme) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
            transaction.signedEffect.isPositive ->
                if (isDarkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
            else -> if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF616161)
        }
        RecentActivityType.PAYMENT_METHOD_CHANGE ->
            if (isDarkTheme) Color(0xFFBDBDBD) else Color(0xFF616161)
    }
    val typeLabel = when (transaction.type) {
        RecentActivityType.MERCHANDISE_SALE -> "SALE RECORDED"
        RecentActivityType.COUPON_SELL -> "COUPON FUNDS SOLD"
        RecentActivityType.COUPON_TOP_UP -> "PREPAID COUPON FUNDS ADDED"
        RecentActivityType.CREDIT_REPAYMENT -> "CREDIT REPAID (COLLECTION)"
        RecentActivityType.EXPENSE -> "EXPENSE RECORDED"
        RecentActivityType.REFUND,
        RecentActivityType.REFUND_TO_COUPON -> "REFUND"
        RecentActivityType.VOID -> "VOID"
        RecentActivityType.AMOUNT_CORRECTION -> "AMOUNT CORRECTION"
        RecentActivityType.PAYMENT_METHOD_CHANGE -> "PAYMENT METHOD CHANGE"
    }
        val activityStatus = when (transaction.type) {
        RecentActivityType.VOID -> "VOIDED"
        RecentActivityType.REFUND,
        RecentActivityType.REFUND_TO_COUPON -> "REFUNDED"
        RecentActivityType.AMOUNT_CORRECTION -> "CORRECTED"
        RecentActivityType.PAYMENT_METHOD_CHANGE -> "UPDATED"
        else -> transaction.status.name.replace('_', ' ')
    }
    val amountMagnitude = MoneyMinorUnits(kotlin.math.abs(transaction.signedEffect.amount))
        .formatDisplay(currency)
    val amountText = when {
        transaction.type in setOf(
            RecentActivityType.MERCHANDISE_SALE,
            RecentActivityType.COUPON_SELL,
            RecentActivityType.COUPON_TOP_UP,
            RecentActivityType.CREDIT_REPAYMENT
        ) -> stringResource(R.string.positive_amount, amountMagnitude)
        transaction.type in setOf(
            RecentActivityType.EXPENSE,
            RecentActivityType.REFUND,
            RecentActivityType.REFUND_TO_COUPON,
            RecentActivityType.VOID
        ) -> "- $amountMagnitude"
        transaction.type == RecentActivityType.AMOUNT_CORRECTION &&
            transaction.signedEffect.isPositive ->
            stringResource(R.string.positive_amount, amountMagnitude)
        transaction.type == RecentActivityType.AMOUNT_CORRECTION &&
            transaction.signedEffect.isNegative -> "- $amountMagnitude"
        else -> amountMagnitude
    }
    val cashMethodLabel = stringResource(R.string.tx_payment_cash)
    val onlineMethodLabel = stringResource(R.string.tx_payment_online)
    val creditMethodLabel = stringResource(R.string.tx_payment_credit)
    val couponMethodLabel = stringResource(R.string.tx_payment_coupon)

    val creditReducedLabel = stringResource(R.string.dmr20_credit_reduced)
    val couponRefundedLabel = stringResource(R.string.dmr20_coupon_refunded)
    val cashRefundedLabel = stringResource(R.string.dmr20_cash_refunded)
    val onlineRefundedLabel = stringResource(R.string.dmr20_online_refunded)

    val methodLabel = transaction.fundingBreakdown
        .map { it.category }
        .distinct()
        .joinToString(" + ") { category ->
            when (category) {
                PaymentCategory.CASH -> cashMethodLabel
                PaymentCategory.ONLINE -> onlineMethodLabel
                PaymentCategory.CREDIT -> creditMethodLabel
                PaymentCategory.COUPON -> couponMethodLabel
            }
        }

    val refundBreakdownLabel =
        if (
            transaction.type == RecentActivityType.REFUND ||
            transaction.type == RecentActivityType.REFUND_TO_COUPON
        ) {
            transaction.fundingBreakdown
                .filter { it.amountMinor != 0L }
                .joinToString(" Â· ") { part ->
                    val amount =
                        MoneyMinorUnits(
                            kotlin.math.abs(part.amountMinor)
                        ).formatDisplay(currency)

                    when (part.category) {
                        PaymentCategory.CREDIT ->
                            "$amount $creditReducedLabel"

                        PaymentCategory.COUPON ->
                            "$amount $couponRefundedLabel"

                        PaymentCategory.CASH ->
                            "$amount $cashRefundedLabel"

                        PaymentCategory.ONLINE ->
                            "$amount $onlineRefundedLabel"
                    }
                }
        } else {
            ""
        }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled =
                    transaction.sourceType ==
                        RecentActivitySourceType.EXPENSE ||
                        transaction.transactionId != null
            ) { if (
                        transaction.sourceType ==
                        RecentActivitySourceType.EXPENSE
                    ) {
                        onExpenseSelected(
                            transaction.sourceReference
                        )
                    } else {
                        transaction.transactionId
                            ?.let(onTransactionSelected)
                    } },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.widthIn(min = 76.dp).heightIn(min = 22.dp),
                    color = flowColor.copy(alpha = if (isDarkTheme) 0.20f else 0.12f),
                    contentColor = flowColor,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = typeLabel,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 2,
                            softWrap = true,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Text(
                text = amountText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                fontWeight = FontWeight.ExtraBold,
                color = flowColor,
                fontSize = 16.sp,
                maxLines = 1,
                softWrap = true
            )

            Text(
                text = "Status: $activityStatus",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            val lifecycleMethodLabel =
                if (
                    (
                        transaction.type == RecentActivityType.REFUND ||
                        transaction.type == RecentActivityType.REFUND_TO_COUPON
                    ) &&
                    refundBreakdownLabel.isNotBlank()
                ) {
                    refundBreakdownLabel
                } else {
                    methodLabel
                }

            if (lifecycleMethodLabel.isNotBlank()) {
                Text(
                    text = lifecycleMethodLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (
                        transaction.type == RecentActivityType.REFUND ||
                        transaction.type == RecentActivityType.REFUND_TO_COUPON
                    ) 2 else 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

        }
    }
}