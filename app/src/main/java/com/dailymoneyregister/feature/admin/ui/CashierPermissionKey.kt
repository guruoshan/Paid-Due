package com.dailymoneyregister.feature.admin.ui

import androidx.annotation.StringRes
import com.dailymoneyregister.R
import com.dailymoneyregister.feature.admin.data.CashierPermissionsConfig

/** One row per cashier permission shown in the "Edit permissions" dialog. */
enum class CashierPermissionKey(@StringRes val labelRes: Int) {
    CREATE_SALE(R.string.staff_perm_create_sale),
    ACCEPT_CASH(R.string.staff_perm_accept_cash),
    ACCEPT_ONLINE(R.string.staff_perm_accept_online),
    CREATE_CREDIT_SALE(R.string.staff_perm_credit_sale),
    USE_COUPON(R.string.staff_perm_coupon),
    ISSUE_INVOICE(R.string.staff_perm_invoice),
    ISSUE_RECEIPT(R.string.staff_perm_receipt),
    REFUND(R.string.staff_perm_refund),
    VOID(R.string.staff_perm_void),
    CORRECT_AMOUNT(R.string.staff_perm_correct_amount),
    CHANGE_PAYMENT_METHOD(R.string.staff_perm_change_payment),
    VIEW_BROAD_HISTORY(R.string.staff_perm_broad_history),
    CUSTOMER_DIRECTORY_ACCESS(R.string.staff_perm_customer_directory),
    MANAGE_PRODUCTS(R.string.staff_perm_manage_products),
    VIEW_REPORTS(R.string.staff_perm_view_reports);

    companion object {

        /**
         * Only permissions whose backend enforcement has been proven are
         * editable from Manage Cashiers.
         *
         * ISSUE_RECEIPT, MANAGE_PRODUCTS and VIEW_REPORTS remain part of the
         * authoritative membership model. Their persisted values are kept
         * unchanged; they are only hidden from this security UI until their
         * backend execution/read boundaries are closed.
         */
        val editableValues: List<CashierPermissionKey> =
            values().filterNot {
                it == ISSUE_RECEIPT ||
                    it == MANAGE_PRODUCTS ||
                    it == VIEW_REPORTS
            }
    }
}

fun CashierPermissionsConfig.isGranted(key: CashierPermissionKey): Boolean = when (key) {
    CashierPermissionKey.CREATE_SALE -> canCreateSale
    CashierPermissionKey.ACCEPT_CASH -> canAcceptCash
    CashierPermissionKey.ACCEPT_ONLINE -> canAcceptOnline
    CashierPermissionKey.CREATE_CREDIT_SALE -> canCreateCreditSale
    CashierPermissionKey.USE_COUPON -> canUseCoupon
    CashierPermissionKey.ISSUE_INVOICE -> canIssueInvoice
    CashierPermissionKey.ISSUE_RECEIPT -> canIssueReceipt
    CashierPermissionKey.REFUND -> canRefund
    CashierPermissionKey.VOID -> canVoid
    CashierPermissionKey.CORRECT_AMOUNT -> canCorrectAmount
    CashierPermissionKey.CHANGE_PAYMENT_METHOD -> canChangePaymentMethod
    CashierPermissionKey.VIEW_BROAD_HISTORY -> canViewBroadHistory
    CashierPermissionKey.CUSTOMER_DIRECTORY_ACCESS -> canAccessCustomerDirectory
    CashierPermissionKey.MANAGE_PRODUCTS -> canManageProducts
    CashierPermissionKey.VIEW_REPORTS -> canViewReports
}

fun CashierPermissionsConfig.withPermission(
    key: CashierPermissionKey,
    value: Boolean
): CashierPermissionsConfig = when (key) {
    CashierPermissionKey.CREATE_SALE -> copy(canCreateSale = value)
    CashierPermissionKey.ACCEPT_CASH -> copy(canAcceptCash = value)
    CashierPermissionKey.ACCEPT_ONLINE -> copy(canAcceptOnline = value)
    CashierPermissionKey.CREATE_CREDIT_SALE -> copy(canCreateCreditSale = value)
    CashierPermissionKey.USE_COUPON -> copy(canUseCoupon = value)
    CashierPermissionKey.ISSUE_INVOICE -> copy(canIssueInvoice = value)
    CashierPermissionKey.ISSUE_RECEIPT -> copy(canIssueReceipt = value)
    CashierPermissionKey.REFUND -> copy(canRefund = value)
    CashierPermissionKey.VOID -> copy(canVoid = value)
    CashierPermissionKey.CORRECT_AMOUNT -> copy(canCorrectAmount = value)
    CashierPermissionKey.CHANGE_PAYMENT_METHOD -> copy(canChangePaymentMethod = value)
    CashierPermissionKey.VIEW_BROAD_HISTORY -> copy(canViewBroadHistory = value)
    CashierPermissionKey.CUSTOMER_DIRECTORY_ACCESS -> copy(canAccessCustomerDirectory = value)
    CashierPermissionKey.MANAGE_PRODUCTS -> copy(canManageProducts = value)
    CashierPermissionKey.VIEW_REPORTS -> copy(canViewReports = value)
}