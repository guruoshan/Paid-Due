package com.dailymoneyregister.feature.register.ui.components

import com.dailymoneyregister.R

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CompactAmountDisplay(
    currencyCode: String,
    expression: String,
    totalText: String,
    totalTextSize: androidx.compose.ui.unit.TextUnit = 29.sp,
    isItemized: Boolean,
    itemCount: Int,
    showTax: Boolean,
    taxText: String,
    showShippingPacking: Boolean,
    shippingPackingText: String,
    onBackspace: () -> Unit,
    onOpenItems: () -> Unit,
    onOpenTax: () -> Unit,
    onClearTax: () -> Unit,
    onOpenShippingPacking: () -> Unit,
    onClearShippingPacking: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Same number pattern as before, moved to the LEFT.
                Column(
                    modifier = Modifier.align(Alignment.CenterStart),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = expression.ifEmpty { "0" },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(1.dp))

                    Text(
                        text = totalText,
                        fontSize = totalTextSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Currency above Delete on the RIGHT.
                Column(
                    modifier = Modifier.align(Alignment.TopEnd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currencyCode,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f)
                    )

                    IconButton(
                        onClick = onBackspace,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            if (isItemized || showTax || showShippingPacking) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isItemized) {
                        AssistChip(
                            onClick = onOpenItems,
                            label = { Text(pluralStringResource(R.plurals.items_count, itemCount, itemCount)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.ShoppingCart,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    if (showTax) {
                        AssistChip(
                            onClick = onOpenTax,
                            label = { Text(taxText) }
                        )

                        IconButton(
                            onClick = onClearTax,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove_tax),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (showShippingPacking) {
                        Spacer(modifier = Modifier.width(2.dp))

                        AssistChip(
                            onClick = onOpenShippingPacking,
                            label = { Text(shippingPackingText) }
                        )

                        IconButton(
                            onClick = onClearShippingPacking,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove_shipping_packing),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
