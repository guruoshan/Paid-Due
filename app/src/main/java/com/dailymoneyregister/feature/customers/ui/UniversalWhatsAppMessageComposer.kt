package com.dailymoneyregister.feature.customers.ui

/**
 * Pure application-wide WhatsApp wording boundary. Every value supplied here
 * has already been resolved by the owning feature. This object performs no
 * I/O, persistence, repository access, or financial calculation.
 */
object UniversalWhatsAppMessageComposer {
    const val FOOTER = "Powered by Paid&Due"

    data class Party(
        val businessName: String,
        val customerName: String? = null,
        val customerPhone: String? = null
    )

    data class LineItem(
        val name: String,
        val quantity: String,
        val formattedAmount: String
    )

    sealed interface Message { val party: Party }

    const val EXPENSE_TITLE = "Expense Details"
    const val PAYMENT_RECEIVED_TITLE = "Payment Received"

    data class CreditBalance(
        override val party: Party,
        val formattedAmount: String,
        val messageDateTime: String
    ) : Message

    data class PaymentReminder(
        override val party: Party,
        val outstandingMinor: Long,
        val formattedAmount: String,
        val messageDateTime: String
    ) : Message

    data class CouponBalance(
        override val party: Party,
        val formattedBalance: String,
        val messageDateTime: String? = null
    ) : Message
    enum class CouponEventKind {
        GENERIC,
        PURCHASE_OR_TOP_UP
    }

    data class CouponEvent(
        override val party: Party,
        val kind: CouponEventKind = CouponEventKind.GENERIC,
        val title: String,
        val formattedAmount: String,
        val formattedBalance: String? = null,
        val reference: String? = null,
        val eventDateTime: String? = null,
        val messageDateTime: String? = null
    ) : Message

    data class Sale(
        override val party: Party,
        val formattedAmount: String,
        val paymentMethod: String,
        val items: List<LineItem>,
        val reference: String,
        val transactionDateTime: String,
        val note: String? = null
    ) : Message

    data class CreditSale(
        override val party: Party,
        val formattedCreditGiven: String,
        val formattedPurchaseOutstanding: String,
        val formattedTotalOutstanding: String,
        val reference: String,
        val transactionDateTime: String
    ) : Message

    data class Expense(
        override val party: Party,
        val formattedAmount: String,
        val category: String,
        val paymentMethod: String,
        val paymentChannel: String,
        val reference: String,
        val eventDateTime: String,
        val note: String? = null
    ) : Message

    data class Invoice(
        override val party: Party,
        val formattedAmount: String,
        val reference: String,
        val documentDateTime: String
    ) : Message

    data class Receipt(
        override val party: Party,
        val formattedAmount: String,
        val paymentSummary: String,
        val reference: String,
        val transactionDateTime: String
    ) : Message
    /** Customer paid back (part of) their Credit. */
    data class PaymentReceived(
        override val party: Party,
        val formattedAmount: String,
        val paymentMethod: String,
        val formattedCreditPending: String? = null,
        val reference: String? = null,
        val eventDateTime: String
    ) : Message

    fun compose(message: Message): String =
        when (message) {
            is CreditBalance -> creditBalance(message)
            is PaymentReminder -> paymentReminder(message)
            is CouponBalance -> couponBalance(message)
            is CouponEvent -> couponEvent(message)
            is Sale -> sale(message)
            is CreditSale -> creditSale(message)
            is Expense -> expense(message)
            is Invoice -> invoice(message)
            is Receipt -> receipt(message)
            is PaymentReceived -> paymentReceived(message)
        }.normalized()

    /** One label/value line shared by the WhatsApp text and the receipt image. */
    data class DetailRow(val label: String, val value: String)

    /** Single source for the Coupon event title (text and image must agree). */
    fun couponEventTitle(event: CouponEvent): String =
        when (event.kind) {
            CouponEventKind.PURCHASE_OR_TOP_UP -> "Coupon Purchase / Top-up"
            CouponEventKind.GENERIC -> event.title
        }

    /** Single source for the Coupon event amount/balance lines. */
    fun couponEventRows(event: CouponEvent): List<DetailRow> {
        val addedValue = event.kind == CouponEventKind.PURCHASE_OR_TOP_UP
        return buildList {
            add(
                DetailRow(
                    if (addedValue) "Coupon value added" else "Amount",
                    event.formattedAmount
                )
            )
            event.formattedBalance?.takeIf(String::isNotBlank)?.let {
                add(
                    DetailRow(
                        if (addedValue) "Current Coupon Balance" else "Coupon Balance",
                        it
                    )
                )
            }
        }
    }

    /** Single source for the Payment Received lines (text and image must agree). */
    fun paymentReceivedRows(event: PaymentReceived): List<DetailRow> =
        buildList {
            add(DetailRow("Amount Received", event.formattedAmount))
            add(DetailRow("Payment", event.paymentMethod))
            event.formattedCreditPending?.takeIf(String::isNotBlank)?.let {
                add(DetailRow("Total Credit Pending", it))
            }
        }

    /** Single source for the first Expense lines (text and image must agree). */
    fun expenseRows(event: Expense): List<DetailRow> =
        listOf(
            DetailRow("Amount", event.formattedAmount),
            DetailRow("Category", event.category),
            DetailRow("Payment", event.paymentMethod),
            DetailRow("Payment Channel", event.paymentChannel)
        )

    private fun creditBalance(message: CreditBalance) = buildMessage(
        party = message.party, title = "Credit Balance", greeting = true
    ) {
        line("Your current credit balance is:")
        blank()
        line("Amount Due: ${message.formattedAmount}")
        partyDetails(message.party)
        line("Date & Time: ${message.messageDateTime}")
    }

    private fun paymentReminder(message: PaymentReminder): String {
        require(message.outstandingMinor > 0L) {
            "Payment reminder requires an outstanding balance greater than zero."
        }
        return buildMessage(
            party = message.party, title = "Payment Reminder", greeting = true
        ) {
            line("This is a friendly reminder of your current outstanding credit balance.")
            blank()
            line("Amount Due: ${message.formattedAmount}")
            blank()
            line("Please contact us if you have already made this payment.")
            partyDetails(message.party)
            line("Date & Time: ${message.messageDateTime}")
        }
    }

    private fun couponBalance(message: CouponBalance) = buildMessage(
        party = message.party, title = "Coupon Balance", greeting = true
    ) {
        line("Your current Coupon balance is:")
        blank()
        line("Coupon Balance: ${message.formattedBalance}")
        partyDetails(message.party)
        message.messageDateTime?.takeIf(String::isNotBlank)?.let {
            line("Date & Time: $it")
        }
    }

    private fun couponEvent(message: CouponEvent) = buildMessage(
        party = message.party,
        title = couponEventTitle(message),
        greeting = true
    ) {
        couponEventRows(message).forEach { line("${it.label}: ${it.value}") }

        partyDetails(message.party)

        message.reference
            ?.takeIf(String::isNotBlank)
            ?.let {
                line("Reference: $it")
            }

        message.eventDateTime
            ?.takeIf(String::isNotBlank)
            ?.let {
                line("Transaction Date & Time: $it")
            }

        message.messageDateTime
            ?.takeIf(String::isNotBlank)
            ?.let {
                line("Message Date & Time: $it")
            }
    }
    private fun sale(message: Sale) = buildMessage(
        party = message.party,
        title = "Sale",
        greeting = message.party.customerName != null
    ) {
        line("Amount: ${message.formattedAmount}")
        line("Payment: ${message.paymentMethod}")
        if (message.items.isNotEmpty()) {
            blank()
            line("Items:")
            message.items.forEach { item ->
                val quantity = item.quantity.takeIf { it.isNotBlank() && it != "1" }
                    ?.let { " x $it" }.orEmpty()
                line("${item.name}$quantity - ${item.formattedAmount}")
            }
        }
        message.note?.takeIf(String::isNotBlank)?.let {
            blank()
            line("Note: $it")
        }
        line("Reference: ${message.reference}")
        line("Transaction Date & Time: ${message.transactionDateTime}")
        blank()
        line("Thank you for your business.")
    }

    private fun creditSale(message: CreditSale) = buildMessage(
        party = message.party, title = "Credit Invoice", greeting = true
    ) {
        line("Credit Given: ${message.formattedCreditGiven}")
        line("Pending for This Purchase: ${message.formattedPurchaseOutstanding}")
        line("Total Credit Pending: ${message.formattedTotalOutstanding}")
        line("Reference: ${message.reference}")
        line("Transaction Date & Time: ${message.transactionDateTime}")
        blank()
        line("Thank you for your business.")
    }

    private fun paymentReceived(message: PaymentReceived) = buildMessage(
        party = message.party, title = PAYMENT_RECEIVED_TITLE, greeting = true
    ) {
        paymentReceivedRows(message).forEach { line("${it.label}: ${it.value}") }
        message.reference?.takeIf(String::isNotBlank)?.let { line("Reference: $it") }
        line("Transaction Date & Time: ${message.eventDateTime}")
        blank()
        line("Thank you for your payment.")
    }

    private fun expense(message: Expense) = buildMessage(
        party = message.party,
        title = EXPENSE_TITLE,
        greeting = message.party.customerName != null
    ) {
        expenseRows(message).forEach { line("${it.label}: ${it.value}") }
        message.party.customerPhone?.takeIf(String::isNotBlank)?.let { line("Phone: $it") }
        line("Reference: ${message.reference}")
        message.note?.takeIf(String::isNotBlank)?.let { line("Note: $it") }
        line("Date & Time: ${message.eventDateTime}")
    }

    private fun invoice(message: Invoice) = buildMessage(
        party = message.party, title = "Invoice", greeting = true
    ) {
        line("Invoice: ${message.reference}")
        line("Amount: ${message.formattedAmount}")
        line("Document Date & Time: ${message.documentDateTime}")
        blank()
        line("Your invoice PDF is attached.")
    }

    private fun receipt(message: Receipt) = buildMessage(
        party = message.party,
        title = "Receipt",
        greeting = message.party.customerName != null
    ) {
        line("Receipt: ${message.reference}")
        line("Amount: ${message.formattedAmount}")
        line("Payment: ${message.paymentSummary}")
        line("Transaction Date & Time: ${message.transactionDateTime}")
        blank()
        line("Your receipt image is attached.")
    }

    private fun buildMessage(
        party: Party,
        title: String,
        greeting: Boolean,
        content: MessageBuilder.() -> Unit
    ): String = MessageBuilder().apply {
        line(party.businessName.trim().ifBlank { "Paid&Due" })
        blank()
        line(title)
        if (greeting) {
            party.customerName?.takeIf(String::isNotBlank)?.let {
                blank()
                line("Hello $it,")
            }
        }
        blank()
        content()
        blank()
        line(FOOTER)
    }.toString()

    private class MessageBuilder {
        private val lines = mutableListOf<String>()
        fun line(value: String) { lines += value.trimEnd() }
        fun blank() { if (lines.lastOrNull()?.isNotEmpty() == true) lines += "" }
        fun partyDetails(party: Party) {
            party.customerName?.takeIf(String::isNotBlank)?.let { line("Customer: $it") }
            party.customerPhone?.takeIf(String::isNotBlank)?.let { line("Phone: $it") }
        }
        override fun toString(): String = lines.joinToString("\n")
    }

    private fun String.normalized(): String =
        lines().joinToString("\n") { it.trimEnd() }.trim()
}

