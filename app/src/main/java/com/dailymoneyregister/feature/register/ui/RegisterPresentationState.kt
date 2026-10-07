package com.dailymoneyregister.feature.register.ui

/** Register-owned destinations. Financial projections remain outside navigation state. */
sealed interface RegisterDestination {
    data object Register : RegisterDestination
    data object History : RegisterDestination

    data class TransactionDetails(
        val transactionId: String,
        val origin: DetailsOrigin
    ) : RegisterDestination

    data class ExpenseDetails(
        val expenseId: String,
        val origin: DetailsOrigin
    ) : RegisterDestination
}

enum class DetailsOrigin {
    REGISTER,
    HISTORY
}

internal fun RegisterDestination.detailsOrigin(): DetailsOrigin =
    if (this == RegisterDestination.History) DetailsOrigin.HISTORY else DetailsOrigin.REGISTER

internal fun RegisterDestination.backDestination(): RegisterDestination =
    when (this) {
        RegisterDestination.Register -> RegisterDestination.Register
        RegisterDestination.History -> RegisterDestination.Register
        is RegisterDestination.TransactionDetails ->
            if (origin == DetailsOrigin.HISTORY) RegisterDestination.History else RegisterDestination.Register
        is RegisterDestination.ExpenseDetails ->
            if (origin == DetailsOrigin.HISTORY) RegisterDestination.History else RegisterDestination.Register
    }

enum class CouponDialogStartMode(val startsWithRedemption: Boolean) {
    ENTRY(false),
    REDEMPTION(true)
}

/** Exactly one Register-owned modal workflow may be visible at a time. */
sealed interface RegisterOverlay {
    data object ItemizedBill : RegisterOverlay
    data class AddItem(val returnToItemizedBill: Boolean) : RegisterOverlay
    data object Tax : RegisterOverlay
    data class ShippingPacking(val returnToItemizedBill: Boolean) : RegisterOverlay
    data object CashCustomer : RegisterOverlay
    data object OnlinePayment : RegisterOverlay
    data class Coupon(val startMode: CouponDialogStartMode) : RegisterOverlay
    data object CouponWallet : RegisterOverlay
    data object CreditSale : RegisterOverlay
    data object Expense : RegisterOverlay
    data object DayClosing : RegisterOverlay
}

internal fun RegisterOverlay?.afterChildClosed(): RegisterOverlay? =
    when (this) {
        is RegisterOverlay.AddItem ->
            if (returnToItemizedBill) RegisterOverlay.ItemizedBill else null
        is RegisterOverlay.ShippingPacking ->
            if (returnToItemizedBill) RegisterOverlay.ItemizedBill else null
        else -> null
    }
