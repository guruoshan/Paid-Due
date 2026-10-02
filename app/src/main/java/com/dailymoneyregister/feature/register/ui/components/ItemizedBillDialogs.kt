package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.core.model.currency.CurrencyConfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.core.database.entity.ProductEntity
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRateParser
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
    onEditItem: (String) -> Unit,
    onUpdateQuantity: (String, Int) -> Unit,
    onRemoveItem: (String) -> Unit,
    onOpenShippingPacking: () -> Unit,
    onCreateInvoice: (() -> Unit)? = null
) {
    val invoiceButton: (@Composable () -> Unit)? =
        if (onCreateInvoice != null && draftState.items.isNotEmpty()) {
            @Composable {
                TextButton(onClick = onCreateInvoice) {
                    Text(stringResource(R.string.inv_create))
                }
            }
        } else null

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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.no_items_added_yet),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = onOpenAddItem
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.add_item))
                        }
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
                                onEdit = { onEditItem(item.id) },
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

                    if (draftState.isTaxEnabled && draftState.taxRateBps > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.tax_with_rate, TaxRateParser.formatBps(draftState.taxRateBps)),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(draftState.taxAmount.formatDisplay(currency), fontSize = 13.sp)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Shipping & packing (transaction-level) - lives here, on the bill
                    // screen, separate from the per-item Add Item form.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenShippingPacking),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.shipping_packing_charges),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val hasCharges = !draftState.txShipping.isZero || !draftState.txPacking.isZero
                        Text(
                            if (hasCharges) {
                                (draftState.txShipping + draftState.txPacking).formatDisplay(currency)
                            } else {
                                stringResource(R.string.add_optional)
                            },
                            fontSize = 14.sp,
                            color = if (hasCharges) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.grand_total), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            draftState.grandTotal.formatDisplay(currency),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
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
        },
        dismissButton = invoiceButton
    )
}

@Composable
private fun DraftItemRow(
    item: DraftItem,
    currency: CurrencyConfig,
    onEdit: () -> Unit,
    onUpdateQuantity: (Int) -> Unit,
    onRemoveItem: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
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
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_item))
                    }

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

/**
 * Add Item / Edit Item form.
 *
 * Deliberately holds ONLY name, price, quantity, description and (for the
 * whole sale) the tax rate. Shipping & packing are transaction-level and
 * live on the bill screen instead (see ItemizedBillDialog).
 *
 * Tax here is a single, sale-wide rate (there is no per-item tax rate in
 * this app) - it is surfaced in this form because the cashier is already
 * looking at product entry, and the resulting tax is always computed on
 * the item subtotal only, never on shipping or packing.
 */
@Composable
fun AddItemDialog(
    currency: CurrencyConfig,
    draftState: DraftState,
    editingItem: DraftItem?,
    suggestions: List<ProductEntity>,
    onNameQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSaveTaxRate: (String) -> Unit,
    onAddItem: (name: String, priceText: String, qty: Int, desc: String?) -> Unit,
    onUpdateItem: (name: String, priceText: String, qty: Int, desc: String?) -> Unit
) {
    var name by remember(editingItem?.id) { mutableStateOf(editingItem?.name.orEmpty()) }
    var priceText by remember(editingItem?.id) {
        mutableStateOf(
            editingItem?.unitPrice?.let { if (it.isZero) "" else it.formatDisplay(currency.decimalPlaces, "").trim() }.orEmpty()
        )
    }
    var quantityText by remember(editingItem?.id) { mutableStateOf((editingItem?.quantity ?: 1).toString()) }
    var description by remember(editingItem?.id) { mutableStateOf(editingItem?.description.orEmpty()) }
    var taxPercentText by remember(draftState.taxRateBps) {
        mutableStateOf(
            if (draftState.taxRateBps > 0) TaxRateParser.bpsToInputString(draftState.taxRateBps) else ""
        )
    }
    var showSuggestions by remember(editingItem?.id) { mutableStateOf(false) }

    val isEditing = editingItem != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.edit_item else R.string.add_item)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                showSuggestions = it.isNotBlank()
                                onNameQueryChange(it)
                            },
                            label = { Text(stringResource(R.string.required_field, stringResource(R.string.item_name))) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (showSuggestions && suggestions.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Column {
                                    suggestions.take(5).forEach { product ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    name = product.name
                                                    priceText = MoneyMinorUnits(product.unitPriceMinor)
                                                        .formatDisplay(currency.decimalPlaces, "").trim()
                                                    description = product.description.orEmpty()
                                                    showSuggestions = false
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(product.name, fontSize = 14.sp)
                                            Text(
                                                MoneyMinorUnits(product.unitPriceMinor)
                                                    .formatDisplay(currency),
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

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

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    stringResource(R.string.tax_for_whole_sale),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = taxPercentText,
                    onValueChange = {
                        taxPercentText = it
                        onSaveTaxRate(it)
                    },
                    label = { Text(stringResource(R.string.tax_rate_percent)) },
                    placeholder = { Text(stringResource(R.string.tax_rate_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = quantityText.toIntOrNull() ?: 1
                    if (isEditing) {
                        onUpdateItem(name, priceText, qty, description)
                    } else {
                        onAddItem(name, priceText, qty, description)
                    }
                },
                enabled = name.isNotBlank() && priceText.isNotBlank()
            ) {
                Text(stringResource(if (isEditing) R.string.save else R.string.add_item))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
