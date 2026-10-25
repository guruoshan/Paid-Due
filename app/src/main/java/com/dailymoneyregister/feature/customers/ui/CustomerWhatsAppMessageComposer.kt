package com.dailymoneyregister.feature.customers.ui

/** Compatibility facade for the preserved Customer message-preview UX. */
object CustomerWhatsAppMessageComposer {
    data class Party(
        val businessName: String,
        val customerName: String,
        val customerPhone: String,
        val labels: UniversalWhatsAppMessageComposer.MessageLabels = UniversalWhatsAppMessageComposer.MessageLabels.ENGLISH
    ) {
        internal fun universal() = UniversalWhatsAppMessageComposer.Party(
            businessName = businessName,
            customerName = customerName,
            customerPhone = customerPhone,
            labels = labels
        )
    }

    fun creditBalance(
        party: Party,
        formattedAmount: String,
        messageDateTime: String
    ): String = UniversalWhatsAppMessageComposer.compose(
        UniversalWhatsAppMessageComposer.CreditBalance(
            party = party.universal(),
            formattedAmount = formattedAmount,
            messageDateTime = messageDateTime
        )
    )

    fun paymentReminder(
        party: Party,
        outstandingMinor: Long,
        formattedAmount: String,
        messageDateTime: String
    ): String = UniversalWhatsAppMessageComposer.compose(
        UniversalWhatsAppMessageComposer.PaymentReminder(
            party = party.universal(),
            outstandingMinor = outstandingMinor,
            formattedAmount = formattedAmount,
            messageDateTime = messageDateTime
        )
    )

    fun couponBalance(
        party: Party,
        formattedBalance: String,
        messageDateTime: String
    ): String = UniversalWhatsAppMessageComposer.compose(
        UniversalWhatsAppMessageComposer.CouponBalance(
            party = party.universal(),
            formattedBalance = formattedBalance,
            messageDateTime = messageDateTime
        )
    )

    fun couponPurchaseOrTopUp(
        party: Party,
        formattedEventAmount: String,
        formattedCurrentBalance: String,
        transactionDateTime: String,
        messageDateTime: String
    ): String = UniversalWhatsAppMessageComposer.compose(
        UniversalWhatsAppMessageComposer.CouponEvent(
            party = party.universal(),
            kind =
                UniversalWhatsAppMessageComposer
                    .CouponEventKind
                    .PURCHASE_OR_TOP_UP,
            title = "Coupon Purchase / Top-up",
            formattedAmount = formattedEventAmount,
            formattedBalance = formattedCurrentBalance,
            eventDateTime = transactionDateTime,
            messageDateTime = messageDateTime
        )
    )
}
