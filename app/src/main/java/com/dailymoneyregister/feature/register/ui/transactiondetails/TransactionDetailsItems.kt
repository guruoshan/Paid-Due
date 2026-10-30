package com.dailymoneyregister.feature.register.ui.transactiondetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.money.MoneyMinorUnits


@Composable
internal fun TransactionItemCard(
    item: TransactionItemEntity,
    currency: CurrencyConfig
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = item.productNameSnapshot,
                fontWeight = FontWeight.SemiBold
            )

            val description =
                item.descriptionSnapshot

            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                stringResource(
                    R.string.tx_details_quantity,
                    item.quantity
                )
            )

            Text(
                stringResource(
                    R.string.tx_details_unit_price,
                    MoneyMinorUnits(
                        item.unitPriceMinor
                    ).formatDisplay(currency)
                )
            )

            Text(
                stringResource(
                    R.string.tx_details_line_total,
                    MoneyMinorUnits(
                        item.lineTotalMinor
                    ).formatDisplay(currency)
                )
            )

            if (item.itemPackingChargeMinor != 0L) {
                Text(
                    stringResource(
                        R.string.tx_details_item_packing,
                        MoneyMinorUnits(
                            item.itemPackingChargeMinor
                        ).formatDisplay(currency)
                    )
                )
            }

            if (item.itemShippingChargeMinor != 0L) {
                Text(
                    stringResource(
                        R.string.tx_details_item_shipping,
                        MoneyMinorUnits(
                            item.itemShippingChargeMinor
                        ).formatDisplay(currency)
                    )
                )
            }
        }
    }
}
