package com.dailymoneyregister.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpFaqScreen(
    onNavigateBack: () -> Unit,
    onOpenFinancialMetrics: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_faq_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(onClick = onOpenFinancialMetrics, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.financial_metrics_explained_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.financial_metrics_explained_subtitle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinancialMetricsExplainedScreen(onNavigateBack: () -> Unit) {
    val questions = listOf(
        R.string.faq_gross_sales_question to R.string.faq_gross_sales_answer,
        R.string.faq_net_sales_question to R.string.faq_net_sales_answer,
        R.string.faq_cash_collected_question to R.string.faq_cash_collected_answer,
        R.string.faq_online_collected_question to R.string.faq_online_collected_answer,
        R.string.faq_credit_sales_created_question to R.string.faq_credit_sales_created_answer,
        R.string.faq_credit_outstanding_question to R.string.faq_credit_outstanding_answer,
        R.string.faq_credit_repayments_question to R.string.faq_credit_repayments_answer,
        R.string.faq_coupon_topups_question to R.string.faq_coupon_topups_answer,
        R.string.faq_coupon_liability_question to R.string.faq_coupon_liability_answer,
        R.string.faq_gross_cash_difference_question to R.string.faq_gross_cash_difference_answer,
        R.string.faq_net_gross_difference_question to R.string.faq_net_gross_difference_answer,
        R.string.faq_refunds_question to R.string.faq_refunds_answer,
        R.string.faq_expenses_question to R.string.faq_expenses_answer
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.financial_metrics_explained_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(questions) { (question, answer) ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(question), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(answer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
