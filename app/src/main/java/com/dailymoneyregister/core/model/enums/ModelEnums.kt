package com.dailymoneyregister.core.model.enums

enum class OperationStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    REQUIRES_RECONCILIATION
}

enum class OperationType {
    CREATE_BUSINESS,
    CREATE_BRANCH,
    CREATE_ADMIN,
    // Retired cashier-management operation tokens remain only for historical Room decoding.
    CREATE_CASHIER,
    UPDATE_USER_LANGUAGE,
    UPDATE_BUSINESS_PROFILE,
    UPDATE_CASHIER,
    UPDATE_CASHIER_PERMISSIONS,
    DISABLE_CASHIER,
    ENABLE_CASHIER,
    ADMIN_RESET_PIN,
    RECORD_SALE,
    RECORD_CREDIT_REPAYMENT,
    RECORD_COUPON_PURCHASE,
    RECORD_AMOUNT_CORRECTION,
    RECORD_PAYMENT_METHOD_CHANGE,
    RECORD_VOID,
    RECORD_REFUND,
    RECORD_EXPENSE,
    RECORD_EXPENSE_CORRECTION,
    RECORD_EXPENSE_VOID,
    OPEN_CASH_SHIFT,
    COUNT_CASH_SHIFT,
    CLOSE_CASH_SHIFT,
    GENERATE_DOCUMENT,
    SHARE_DOCUMENT
}

enum class SubjectType {
    BUSINESS,
    BRANCH,
    USER,
    TRANSACTION,
    REPAYMENT,
    CUSTOMER,
    COUPON_WALLET,
    COUPON_AUTHORIZED_USER,
    CASH_SHIFT,
    EXPENSE,
    EXPENSE_PAYEE,
    DOCUMENT,
    MEDIA
}

enum class PaymentCategory {
    CASH,
    ONLINE,
    CREDIT,
    COUPON
}

enum class PaymentChannel {
    CASH_TENDER,
    MANUAL_QR,
    SOFTPOS,
    COUPON_WALLET,
    CREDIT_ACCOUNT
}

enum class ConfirmationType {
    // Persisted settlement token retained for historical and current cash-tender evidence.
    // Do not rename or remove without a versioned data-decoding migration.
    CASHIER_CONFIRMED,
    PROVIDER_APPROVED
}

enum class EntryPurpose {
    SALE_FUNDING,
    CREDIT_REPAYMENT_COLLECTION,
    COUPON_PURCHASE_FUNDING,
    REFUND_OUTFLOW,
    EXPENSE_OUTFLOW,
    EXPENSE_CORRECTION,
    FUNDING_CORRECTION,
    PAYMENT_METHOD_RECLASSIFICATION,
    VOID_REVERSAL
}

enum class TransactionType {
    SALE,
    COUPON_PURCHASE
}

enum class CreditLedgerType {
    OBLIGATION,  // Positive (+)
    REPAYMENT,   // Negative (-)
    ADJUSTMENT   // Signed (+/-)
}

enum class CouponLedgerType {
    PURCHASE,        // Positive (+)
    PROMOTION_BONUS, // Positive (+)
    REDEMPTION,      // Negative (-)
    REFUND_CREDIT    // Positive (+)
}

enum class AuthorizedUserEventType {
    ADDED,
    NAME_CORRECTED,
    DEACTIVATED
}

enum class CashShiftEventType {
    OPENED,
    COUNTED,
    CLOSED,
    ADJUSTED
}

enum class UserRole {
    ADMIN,
    // Legacy persisted role retained for Room and inbound-sync decoding; authorization rejects it.
    CASHIER
}

enum class AuthType {
    // Legacy persisted credential type retained for decoding; login/restoration do not accept it.
    PIN,
    PASSWORD
}

enum class DocumentType {
    INVOICE,
    RECEIPT,
    CREDIT_STATEMENT,
    SUPPORTING_DOCUMENT
}

enum class MediaType {
    MERCHANT_QR,
    BUSINESS_LOGO,
    PRODUCT_PHOTO,
    DOCUMENT_ATTACHMENT
}
