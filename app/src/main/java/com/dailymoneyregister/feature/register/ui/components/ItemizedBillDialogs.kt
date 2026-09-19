package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.core.model.currency.CurrencyConfig

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.domain.DraftItem
import com.dailymoneyregister.feature.register.domain.DraftState

import androidx.compose.ui.res.stringResource
import com.dailymoneyregister.R

@Composable
fun ItemizedBillDialog(
    draftState: DraftState,
    currency: CurrencyConfig,
    onDismiss: () -> Unit,
    onOpenAddItem: () -> Unit,
    onUpdateQuantity: (String, Int) -> Unit,
    onRemoveItem: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.itemized_bill_title, stringResource(R.string.itemized_bill), stringResource(R.string.items_count, draftState.items.size)))
                IconButton(onClick = onOpenAddItem) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_item), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (draftState.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.no_bill_items),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(draftState.items, key = { it.id }) { item ->
                            DraftItemRow(
                                item = item,
                                currency = currency,
                                onUpdateQuantity = { qty -> onUpdateQuantity(item.id, qty) },
                                onRemoveItem = { onRemoveItem(item.id) }
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.subtotal_label), fontWeight = FontWeight.Bold)
                        Text(
                            draftState.subtotal.formatDisplay(currency),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.save))
            }
        }
    )
}

@Composable
private fun DraftItemRow(
    item: DraftItem,
    currency: CurrencyConfig,
    onUpdateQuantity: (Int) -> Unit,
    onRemoveItem: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    item.description?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        stringResource(R.string.unit_price_each, item.unitPrice.formatDisplay(currency)),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onUpdateQuantity(item.quantity - 1) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.decrease))
                    }

                    Text(
                        "${item.quantity}",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    IconButton(
                        onClick = { onUpdateQuantity(item.quantity + 1) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.increase))
                    }

                    IconButton(
                        onClick = onRemoveItem,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun AddItemDialog(
    currency: CurrencyConfig,
    onDismiss: () -> Unit,
    onAddItem: (name: String, priceText: String, qty: Int, desc: String?, packingText: String, shippingText: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var description by remember { mutableStateOf("") }
    var packingText by remember { mutableStateOf("0") }
    var shippingText by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_item)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.required_field, stringResource(R.string.item_name))) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text(stringResource(R.string.required_currency_field, stringResource(R.string.unit_price), currency.symbol)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(stringResource(R.string.quantity)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.item_desc_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = packingText,
                    onValueChange = { packingText = it },
                    label = { Text(stringResource(R.string.currency_field, stringResource(R.string.item_packing_optional), currency.symbol)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = shippingText,
                    onValueChange = { shippingText = it },
                    label = { Text(stringResource(R.string.currency_field, stringResource(R.string.item_shipping_optional), currency.symbol)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = quantityText.toIntOrNull() ?: 1
                    onAddItem(name, priceText, qty, description, packingText, shippingText)
                },
                enabled = name.isNotBlank() && priceText.isNotBlank()
            ) {
                Text(stringResource(R.string.add_item))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
