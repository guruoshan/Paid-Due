package com.dailymoneyregister.feature.register.receipt

import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.feature.register.data.TransactionDetailsReadModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read-only receipt projection over an already-persisted merchandise sale. */
data class ReceiptReadModel(
    val businessName: String,
    val customerName: String?,
    val customerPhone: String?,
    val reference: String,
    val transactionTimestamp: Long,
    val currencyCode: String,
    val currencyDecimalPlaces: Int,
    val subtotalMinor: Long,
    val taxMinor: Long,
    val shippingMinor: Long,
    val packingMinor: Long,
    val totalMinor: Long,
    val items: List<Item>,
    val tenders: List<Tender>
) {
    data class Item(
        val name: String,
        val quantity: Int,
        val lineTotalMinor: Long
    )

    data class Tender(
        val category: PaymentCategory,
        val amountMinor: Long
    )

    fun formattedMoney(amountMinor: Long): String =
        "$currencyCode " + BigDecimal.valueOf(amountMinor)
            .movePointLeft(currencyDecimalPlaces)
            .setScale(currencyDecimalPlaces, RoundingMode.UNNECESSARY)
            .toPlainString()

    fun formattedTransactionDateTime(): String =
        DateTimeFormatter
            .ofPattern("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(transactionTimestamp))

    fun paymentSummary(): String =
        if (tenders.isEmpty()) {
            "Recorded payment"
        } else {
            tenders.joinToString(" + ") {
                "${it.category.displayName()}: ${formattedMoney(it.amountMinor)}"
            }
        }

    companion object {
        fun from(
            details: TransactionDetailsReadModel,
            businessName: String,
            customerName: String?,
            customerPhone: String?
        ): ReceiptReadModel {
            val transaction = details.financial.transaction
            require(transaction.type == TransactionType.SALE) {
                "Receipt requires a persisted merchandise sale."
            }

            return ReceiptReadModel(
                businessName = businessName.trim().ifBlank { "Paid&Due" },
                customerName = customerName?.trim()?.takeIf(String::isNotBlank),
                customerPhone = customerPhone?.trim()?.takeIf(String::isNotBlank),
                reference = transaction.displayRef,
                transactionTimestamp = transaction.createdAt,
                currencyCode = transaction.currencyCode,
                currencyDecimalPlaces = transaction.currencyDecimalPlaces,
                subtotalMinor = transaction.subtotalMinor,
                taxMinor = transaction.taxAmountMinor,
                shippingMinor = transaction.txShippingMinor,
                packingMinor = transaction.txPackingMinor,
                totalMinor = transaction.grandTotalMinor,
                items = details.items.map {
                    Item(
                        name = it.productNameSnapshot.takeIf(String::isNotBlank)
                            ?: it.descriptionSnapshot?.takeIf(String::isNotBlank)
                            ?: "Item",
                        quantity = it.quantity,
                        lineTotalMinor = it.lineTotalMinor
                    )
                },
                tenders = details.financial.fundingBreakdown.map {
                    Tender(category = it.category, amountMinor = it.amountMinor)
                }
            )
        }
    }
}

private fun PaymentCategory.displayName(): String =
    name.lowercase().replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
