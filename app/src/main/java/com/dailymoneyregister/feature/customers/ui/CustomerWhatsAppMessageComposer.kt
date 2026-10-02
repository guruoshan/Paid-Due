package com.dailymoneyregister.feature.customers.ui

/**
 * CUSTOMER_WHATSAPP_V1
 *
 * Pure communication formatter.
 *
 * BOARDLOCK:
 * - No database access.
 * - No repository access.
 * - No financial writes.
 * - Financial values are supplied by authoritative read projections.
 */
object CustomerWhatsAppMessageComposer {

    private const val FOOTER = "Powered by Paid&Due"

    data class Party(
        val businessName: String,
        val customerName: String,
        val customerPhone: String
    )

    fun creditBalance(
        party: Party,
        formattedAmount: String,
        messageDateTime: String
    ): String =
        """
        ${party.businessName}

        Credit Balance

        Hello ${party.customerName},

        Your current credit balance with ${party.businessName} is:

        Amount Due: $formattedAmount

        Customer: ${party.customerName}
        Phone: ${party.customerPhone}
        Date & Time: $messageDateTime
        Business: ${party.businessName}

        $FOOTER
        """.trimIndent()

    fun paymentReminder(
        party: Party,
        outstandingMinor: Long,
        formattedAmount: String,
        messageDateTime: String
    ): String {
        require(outstandingMinor > 0L) {
            "Payment reminder requires an outstanding balance greater than zero."
        }

        return """
        ${party.businessName}

        Payment Reminder

        Hello ${party.customerName},

        This is a friendly reminder that your current outstanding credit balance with ${party.businessName} is:

        Amount Due: $formattedAmount

        Please contact us if you have already made this payment.

        Customer: ${party.customerName}
        Phone: ${party.customerPhone}
        Date & Time: $messageDateTime
        Business: ${party.businessName}

        $FOOTER
        """.trimIndent()
    }

    fun couponBalance(
        party: Party,
        formattedBalance: String,
        messageDateTime: String
    ): String =
        """
        ${party.businessName}

        Coupon Balance

        Hello ${party.customerName},

        Your current Coupon balance with ${party.businessName} is:

        Coupon Balance: $formattedBalance

        Customer: ${party.customerName}
        Phone: ${party.customerPhone}
        Date & Time: $messageDateTime
        Business: ${party.businessName}

        $FOOTER
        """.trimIndent()

    fun couponPurchaseOrTopUp(
        party: Party,
        formattedEventAmount: String,
        formattedCurrentBalance: String,
        transactionDateTime: String,
        messageDateTime: String
    ): String =
        """
        ${party.businessName}

        Coupon Purchase / Top-up

        Hello ${party.customerName},

        Coupon value added: $formattedEventAmount
        Current Coupon Balance: $formattedCurrentBalance

        Transaction Date & Time: $transactionDateTime
        Customer: ${party.customerName}
        Phone: ${party.customerPhone}
        Message Date & Time: $messageDateTime
        Business: ${party.businessName}

        $FOOTER
        """.trimIndent()
}
