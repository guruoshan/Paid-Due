package com.dailymoneyregister.feature.register.ui.transactiondetails

import androidx.compose.foundation.layout.Arrangement
import com.dailymoneyregister.feature.register.data.VoidBlockReason
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.database.entity.AmountCorrectionEventEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentAllocationEntity
import com.dailymoneyregister.core.database.entity.CreditRepaymentEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionItemEntity
import com.dailymoneyregister.core.database.entity.VoidEventEntity
import com.dailymoneyregister.core.model.currency.CurrencyConfig
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.financial.FinancialLifecycleState
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale


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
