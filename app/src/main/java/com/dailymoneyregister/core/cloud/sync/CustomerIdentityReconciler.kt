package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CustomerIdAliasEntity

/**
 * Reconciles identity projections without rewriting historical financial references.
 * A server ID is an alias for the device's stable local customer row, not a replacement
 * primary key. This preserves invoices, credit/coupon ledgers, expenses and operations.
 */
internal object CustomerIdentityReconciler {

    suspend fun reconcileRemoteCustomer(
        database: DmrDatabase,
        businessId: String,
        canonicalCustomerId: String,
        canonicalPhone: String,
        name: String,
        createdAt: Long,
        updatedAt: Long
    ): String {
        require(businessId.isNotBlank() && canonicalCustomerId.isNotBlank())
        require(canonicalPhone.matches(Regex("^\\+[1-9][0-9]{7,14}$")))
        val dao = database.customerDao()
        val knownAlias = dao.getCustomerIdAliasByCanonicalId(businessId, canonicalCustomerId)
        val localByPhone = dao.getCustomerByPhone(businessId, canonicalPhone)
        val local = when {
            knownAlias != null -> {
                require(knownAlias.canonicalPhone == canonicalPhone) {
                    "Canonical customer ID was previously associated with another phone"
                }
                dao.getCustomerById(knownAlias.localCustomerId)
                    ?: error("Customer alias references a missing local customer")
            }
            localByPhone != null -> localByPhone
            else -> dao.getCustomerById(canonicalCustomerId)?.also {
                require(it.businessId == businessId && it.canonicalPhone == canonicalPhone) {
                    "Canonical customer ID collides with another local identity"
                }
            } ?: CustomerEntity(
                id = canonicalCustomerId,
                businessId = businessId,
                canonicalPhone = canonicalPhone,
                name = name,
                createdAt = createdAt,
                updatedAt = updatedAt
            ).also { dao.insertCustomer(it) }
        }

        require(local.businessId == businessId && local.canonicalPhone == canonicalPhone) {
            "Customer phone is already linked to a different local customer ID"
        }
        val existingLocalAlias = dao.getCustomerIdAliasByLocalId(businessId, local.id)
        require(existingLocalAlias == null ||
            (existingLocalAlias.canonicalCustomerId == canonicalCustomerId &&
                existingLocalAlias.canonicalPhone == canonicalPhone)) {
            "Local customer ID was previously associated with another canonical customer"
        }
        val existingCanonicalAlias = dao.getCustomerIdAliasByCanonicalId(businessId, canonicalCustomerId)
        require(existingCanonicalAlias == null ||
            (existingCanonicalAlias.localCustomerId == local.id &&
                existingCanonicalAlias.canonicalPhone == canonicalPhone)) {
            "Canonical customer ID maps to a different local customer"
        }
        dao.insertCustomerIdAlias(
            CustomerIdAliasEntity(
                businessId = businessId,
                localCustomerId = local.id,
                canonicalCustomerId = canonicalCustomerId,
                canonicalPhone = canonicalPhone,
                updatedAt = maxOf(updatedAt, System.currentTimeMillis())
            )
        )
        val verified = dao.getCustomerIdAliasByLocalId(businessId, local.id)
        require(verified?.canonicalCustomerId == canonicalCustomerId &&
            verified.canonicalPhone == canonicalPhone) {
            "Customer alias persistence conflict"
        }
        return local.id
    }

    suspend fun resolveLocalCustomerId(
        database: DmrDatabase,
        businessId: String,
        incomingCustomerId: String
    ): String {
        val dao = database.customerDao()
        val direct = dao.getCustomerById(incomingCustomerId)
        if (direct != null) {
            require(direct.businessId == businessId) { "Customer belongs to another business" }
            return direct.id
        }
        val alias = dao.getCustomerIdAliasByCanonicalId(businessId, incomingCustomerId)
            ?: error("Customer identity has no local canonical mapping")
        val local = dao.getCustomerById(alias.localCustomerId)
            ?: error("Customer alias references a missing local customer")
        require(local.businessId == businessId && local.canonicalPhone == alias.canonicalPhone)
        return local.id
    }

    suspend fun resolveCanonicalCustomerId(
        database: DmrDatabase,
        businessId: String,
        localCustomerId: String
    ): String {
        val local = database.customerDao().getCustomerById(localCustomerId)
            ?: error("Local customer does not exist")
        require(local.businessId == businessId) { "Customer belongs to another business" }
        return database.customerDao()
            .getCustomerIdAliasByLocalId(businessId, localCustomerId)
            ?.canonicalCustomerId ?: localCustomerId
    }
}
