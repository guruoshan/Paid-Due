package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.core.model.currency.CurrencyConfig

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.model.tax.TaxRateParser
import com.dailymoneyregister.feature.register.domain.DraftState

@Composable
fun TaxDialog(
    draftState: DraftState,
    currency: CurrencyConfig,
    onDismiss: () -> Unit,
    onSaveTaxRate: (String) -> Unit
) {
    var percentText by remember(draftState.taxRateBps) {
        mutableStateOf(
            if (draftState.taxRateBps > 0) {
                TaxRateParser.bpsToInputString(draftState.taxRateBps)
            } else {
                ""
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tax)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = percentText,
                    onValueChange = { percentText = it },
                    label = { Text(stringResource(R.string.tax_rate_percent)) },
                    placeholder = { Text(stringResource(R.string.tax_rate_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (draftState.isTaxEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.label_with_colon, stringResource(R.string.tax)),
                            fontSize = 14.sp
                        )
                        Text(
                            draftState.taxAmount.formatDisplay(currency),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveTaxRate(percentText)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.save))
            }
        }
    )
}
@Composable
fun ShippingPackingDialog(
    draftState: DraftState,
    currency: CurrencyConfig,
    onDismiss: () -> Unit,
    onSave: (shippingText: String, packingText: String) -> Unit
) {
    var shippingText by remember {
        mutableStateOf(
            if (draftState.txShipping.isZero) "" else draftState.txShipping.formatDisplay(currency.decimalPlaces, "").trim()
        )
    }
    var packingText by remember {
        mutableStateOf(
            if (draftState.txPacking.isZero) "" else draftState.txPacking.formatDisplay(currency.decimalPlaces, "").trim()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shipping_packing_charges)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = shippingText,
                    onValueChange = { shippingText = it },
                    label = { Text(stringResource(R.string.currency_field, stringResource(R.string.tx_shipping_charge), currency.symbol)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = packingText,
                    onValueChange = { packingText = it },
                    label = { Text(stringResource(R.string.currency_field, stringResource(R.string.tx_packing_charge), currency.symbol)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(shippingText, packingText) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

