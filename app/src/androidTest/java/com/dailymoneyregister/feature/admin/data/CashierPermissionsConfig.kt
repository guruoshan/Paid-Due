package com.dailymoneyregister.feature.admin.data

/**
 * Legacy permission snapshot retained only to decode historical staff lifecycle
 * operation payloads. No app UI or runtime repository can create cashier access.
 */
data class CashierPermissionsConfig(
    val canCreateSale: Boolean = true,
    val canAcceptCash: Boolean = true,
    val canAcceptOnline: Boolean = true,
    val canCreateCreditSale: Boolean = false,
    val canUseCoupon: Boolean = false,
    val canIssueInvoice: Boolean = true,
    val canIssueReceipt: Boolean = true,
    val canRefund: Boolean = false,
    val canVoid: Boolean = false,
    val canCorrectAmount: Boolean = false,
    val canChangePaymentMethod: Boolean = false,
    val canViewBroadHistory: Boolean = false,
    val canManageProducts: Boolean = false,
    val canViewReports: Boolean = false
)
