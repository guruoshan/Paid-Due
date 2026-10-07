package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity

/**
 * BOARDLOCK sale-customer read boundary.
 *
 * Read-only decomposition extracted from RegisterRepository.
 *
 * This component may:
 * - normalize a customer phone;
 * - look up persisted customer identity;
 * - verify persisted sale-customer evidence.
 *
 * It MUST NOT create or update customers.
 * Customer creation remains in RegisterRepository's existing
 * resolveOptionalSaleCustomerId() admission path.
 */
internal class RegisterCustomerReadRepository(
    database: DmrDatabase
) {
    private val customerDao =
        database.customerDao()

    suspend fun findSaleCustomerByPhone(
        businessId: String,
        canonicalPhone: String
    ): CustomerEntity? {

        val normalized =
            com.dailymoneyregister.feature.customers.data.CustomerIdentity
                .canonicalPhone(canonicalPhone)
                ?: return null

        return customerDao.getCustomerByPhone(
            businessId = businessId,
            canonicalPhone = normalized
        )
    }

    suspend fun existingOptionalSaleCustomerId(
        businessId: String,
        customerPhone: String?
    ): String? {

        val phone =
            customerPhone
                ?.trim()
                .orEmpty()

        if (phone.isEmpty()) {
            return null
        }

        val canonicalPhone =
            com.dailymoneyregister.feature.customers.data.CustomerIdentity
                .canonicalPhone(phone)

        val customer =
            checkNotNull(
                customerDao.getCustomerByPhone(
                    businessId,
                    canonicalPhone
                )
            ) {
                "Persisted Sale customer evidence is missing"
            }

        check(
            customer.businessId ==
                businessId
        ) {
            "Persisted Sale customer business mismatch"
        }

        return customer.id
    }
}