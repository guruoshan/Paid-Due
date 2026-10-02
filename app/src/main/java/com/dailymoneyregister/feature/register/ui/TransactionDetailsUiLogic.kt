package com.dailymoneyregister.feature.register.ui

import java.time.ZoneId

internal fun validateTransactionDetailsTimezoneId(
    timezoneId: String?
): String? {
    val candidate =
        timezoneId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

    return candidate.takeIf {
        runCatching {
            ZoneId.of(candidate)
        }.isSuccess
    }
}

internal fun <T> sortTransactionDetailsTimeline(
    items: List<T>,
    occurredAt: (T) -> Long,
    stableId: (T) -> String
): List<T> {
    return items.sortedWith(
        compareBy<T> {
            occurredAt(it)
        }.thenBy {
            stableId(it)
        }
    )
}

internal enum class CreditReturnUiRoute {
    NON_CREDIT,
    UNPAID_CREDIT,
    REPAID_CREDIT,
    INVALID_CREDIT
}

internal fun creditReturnUiRoute(
    paymentCategory:
        com.dailymoneyregister.core.model.enums.PaymentCategory?,
    paymentChannel:
        com.dailymoneyregister.core.model.enums.PaymentChannel?,
    creditRepaymentAllocationCount: Int
): CreditReturnUiRoute {

    require(
        creditRepaymentAllocationCount >= 0
    )

    if (
        paymentCategory !=
        com.dailymoneyregister.core.model.enums.PaymentCategory.CREDIT
    ) {
        return CreditReturnUiRoute.NON_CREDIT
    }

    if (
        paymentChannel !=
        com.dailymoneyregister.core.model.enums.PaymentChannel.CREDIT_ACCOUNT
    ) {
        return CreditReturnUiRoute.INVALID_CREDIT
    }

    if (
        creditRepaymentAllocationCount > 0
    ) {
        return CreditReturnUiRoute.REPAID_CREDIT
    }

    return CreditReturnUiRoute.UNPAID_CREDIT
}
