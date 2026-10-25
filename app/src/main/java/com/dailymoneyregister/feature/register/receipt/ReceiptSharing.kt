package com.dailymoneyregister.feature.register.receipt

import com.dailymoneyregister.R

import android.content.Context
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.customers.ui.PreparedWhatsAppMessage
import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import com.dailymoneyregister.feature.customers.ui.WhatsAppAttachment
import com.dailymoneyregister.feature.customers.ui.WhatsAppTransport
import java.io.File

/**
 * BOARDLOCK_UNIVERSAL_RECEIPT_IMAGE_V4
 *
 * The single "receipt image + WhatsApp text" adapter. All launch mechanics
 * stay in WhatsAppTransport; all wording stays in
 * UniversalWhatsAppMessageComposer; all drawing stays in ReceiptRenderer.
 *
 * If the person is not allowed to issue receipts, nothing is sent and a
 * "Permission denied" toast is shown. Any other failure (for example the PNG
 * could not be drawn) is thrown so the caller can decide what to do.
 */
object ReceiptSharing {

    /** Sale-style receipt (items, totals, payment): cash, credit, online, coupon payment. */
    fun shareWhatsApp(
        context: Context,
        receipt: ReceiptReadModel,
        messageOverride: String? = null
    ) {
        if (!isIssuePermitted(context)) return

        val message = messageOverride ?: UniversalWhatsAppMessageComposer.compose(
            UniversalWhatsAppMessageComposer.Receipt(
                party = UniversalWhatsAppMessageComposer.Party(
                    businessName = receipt.businessName,
                    customerName = receipt.customerName,
                    customerPhone = receipt.customerPhone
                    labels = UniversalWhatsAppMessageComposer.MessageLabels.from(context)
                ),
                formattedAmount = receipt.formattedMoney(receipt.totalMinor),
                paymentSummary = receipt.paymentSummary(),
                reference = receipt.reference,
                transactionDateTime = receipt.formattedTransactionDateTime(),
                note = receipt.note
            )
        )

        launch(
            context = context,
            file = ReceiptRenderer.render(context.applicationContext, receipt),
            message = message,
            recipientPhone = receipt.customerPhone.orEmpty(),
            reference = receipt.reference
        )
    }

    /** Coupon purchase / top-up / refund-to-coupon. */
    fun shareCouponEventWhatsApp(
        context: Context,
        event: UniversalWhatsAppMessageComposer.CouponEvent
    ) = shareEventReceipt(
        context = context,
        image = ReceiptRenderer.EventReceipt(
            businessName = event.party.businessName,
            title = UniversalWhatsAppMessageComposer.couponEventTitle(event),
            reference = event.reference.orEmpty(),
            dateTime = event.eventDateTime,
            customerName = event.party.customerName,
            rows = UniversalWhatsAppMessageComposer.couponEventRows(event).toImageRows()
        ),
        message = UniversalWhatsAppMessageComposer.compose(event),
        recipientPhone = event.party.customerPhone.orEmpty()
    )

    /** Customer paid back Credit. */
    fun sharePaymentReceivedWhatsApp(
        context: Context,
        event: UniversalWhatsAppMessageComposer.PaymentReceived
    ) = shareEventReceipt(
        context = context,
        image = ReceiptRenderer.EventReceipt(
            businessName = event.party.businessName,
            title = UniversalWhatsAppMessageComposer.PAYMENT_RECEIVED_TITLE,
            reference = event.reference.orEmpty(),
            dateTime = event.eventDateTime,
            customerName = event.party.customerName,
            rows = UniversalWhatsAppMessageComposer.paymentReceivedRows(event).toImageRows()
        ),
        message = UniversalWhatsAppMessageComposer.compose(event),
        recipientPhone = event.party.customerPhone.orEmpty()
    )

    /** Money paid out: a "Payment Voucher" image for the person who was paid. */
    fun shareExpenseVoucherWhatsApp(
        context: Context,
        event: UniversalWhatsAppMessageComposer.Expense,
        recipientPhone: String
    ) = shareEventReceipt(
        context = context,
        image = ReceiptRenderer.EventReceipt(
            businessName = event.party.businessName,
            title = UniversalWhatsAppMessageComposer.EXPENSE_TITLE,
            reference = event.reference,
            dateTime = event.eventDateTime,
            customerName = event.party.customerName,
            rows = UniversalWhatsAppMessageComposer.expenseRows(event).toImageRows() +
                listOfNotNull(
                    event.note?.takeIf(String::isNotBlank)?.let {
                        ReceiptRenderer.EventReceipt.Row("Note", it)
                    }
                ),
            heading = "PAYMENT VOUCHER",
            partyLabel = "Paid to"
        ),
        message = UniversalWhatsAppMessageComposer.compose(event),
        recipientPhone = recipientPhone
    )

    /** The one place that draws an event image and opens WhatsApp with it. */
    fun shareEventReceipt(
        context: Context,
        image: ReceiptRenderer.EventReceipt,
        message: String,
        recipientPhone: String
    ) {
        if (!isIssuePermitted(context)) return

        launch(
            context = context,
            file = ReceiptRenderer.renderEvent(context.applicationContext, image),
            message = message,
            recipientPhone = recipientPhone,
            reference = image.reference
        )
    }

    private fun List<UniversalWhatsAppMessageComposer.DetailRow>.toImageRows() =
        map { ReceiptRenderer.EventReceipt.Row(it.label, it.value) }

    private fun isIssuePermitted(context: Context): Boolean {
        val permitted = PermissionChecker.isPermitted(
            SessionManager.currentSession.value,
            PermissionAction.ISSUE_RECEIPT
        )
        if (!permitted) {
            Toast.makeText(context, context.getString(R.string.audit_permission_denied), Toast.LENGTH_SHORT).show()
        }
        return permitted
    }

    private fun launch(
        context: Context,
        file: File,
        message: String,
        recipientPhone: String,
        reference: String
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        WhatsAppTransport.share(
            context = context,
            prepared = PreparedWhatsAppMessage(
                text = message,
                recipientPhone = recipientPhone,
                attachment = WhatsAppAttachment(
                    contentUri = uri,
                    mimeType = "image/png",
                    subject = "Receipt $reference"
                )
            )
        )
    }
}
