package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.SessionManager

/**
 * Read-only authoritative Coupon Wallet projection.
 *
 * No Coupon balance is persisted here. Balance and history are derived
 * from existing authoritative Coupon ledger evidence.
 */
internal class CouponWalletReadRepository(
    private val database: DmrDatabase
) {
    private val customerDao = database.customerDao()
    private val ledgerDao = database.ledgerDao()

    suspend fun getCouponWalletDetails(
        customerId: String,
        currencyCode: String
    ): RegisterRepository.CouponWalletDetails? {
        val session =
            SessionManager.currentSession.value
                ?: return null

        val customer =
            customerDao.getCustomerById(customerId)
                ?: return null

        require(
            customer.businessId == session.business.id &&
                currencyCode == session.branch.currencyCode
        )

        val wallet =
            ledgerDao.getCouponWallet(
                customerId,
                currencyCode
            ) ?: return null

        val ledgerEvents = ledgerDao.getCouponLedgerForWallet(wallet.id)
        val purchaseTransactionIds = ledgerEvents
            .filter { it.type == com.dailymoneyregister.core.model.enums.CouponLedgerType.PURCHASE }
            .mapNotNull { it.transactionId }
            .distinct()
        val transactionsById = purchaseTransactionIds
            .chunked(900)
            .flatMap { ids -> database.transactionDao().getTransactionsByIds(ids) }
            .associateBy { it.id }
        val activityKinds = ledgerEvents.associate { event ->
            val kind = when (event.type) {
                com.dailymoneyregister.core.model.enums.CouponLedgerType.PURCHASE ->
                    if (transactionsById[event.transactionId]?.description == "Coupon Top Up") {
                        RegisterRepository.CouponWalletActivityKind.TOP_UP
                    } else {
                        RegisterRepository.CouponWalletActivityKind.SALE
                    }
                com.dailymoneyregister.core.model.enums.CouponLedgerType.PROMOTION_BONUS ->
                    RegisterRepository.CouponWalletActivityKind.BONUS
                com.dailymoneyregister.core.model.enums.CouponLedgerType.REDEMPTION ->
                    RegisterRepository.CouponWalletActivityKind.REDEMPTION
                com.dailymoneyregister.core.model.enums.CouponLedgerType.REFUND_CREDIT ->
                    RegisterRepository.CouponWalletActivityKind.REFUND_TO_COUPON
            }
            event.id to kind
        }

        val authorizedUsers =
            ledgerDao
                .getActiveAuthorizedUsers(wallet.id)
                .map { user ->
                    RegisterRepository.CouponAuthorizedUserDetails(
                        user,
                        ledgerDao.getAuthorizedUserEvents(user.id)
                    )
                }

        return RegisterRepository.CouponWalletDetails(
            wallet = wallet,
            balanceMinor =
                ledgerDao.getCouponBalance(wallet.id),
            ledgerEvents = ledgerEvents,
            activityKinds = activityKinds,
            authorizedUsers = authorizedUsers,
            customer = customer
        )
    }
}
