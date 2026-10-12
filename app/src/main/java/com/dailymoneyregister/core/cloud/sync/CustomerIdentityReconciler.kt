package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CustomerIdAliasEntity

/**
 * Reconciles identity projections without rewriting historical financial references.
 * Device IDs become aliases to a stable local customer row and one server-canonical ID.
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
        val incomingAlias = dao.getCustomerIdAliasByAliasId(businessId, canonicalCustomerId)
        if (incomingAlias != null) {
            require(incomingAlias.canonicalPhone == canonicalPhone) {
                "Customer ID alias was previously associated with another phone"
            }
            val local = dao.getCustomerById(incomingAlias.localCustomerId)
                ?: error("Customer alias references a missing local customer")
            require(local.businessId == businessId && local.canonicalPhone == canonicalPhone)
            return local.id
        }

        val knownPhoneAlias = dao.getCustomerIdAliasByPhone(businessId, canonicalPhone)
        val serverCanonicalId = knownPhoneAlias?.canonicalCustomerId ?: canonicalCustomerId
        val local = if (knownPhoneAlias != null) {
            dao.getCustomerById(knownPhoneAlias.localCustomerId)
                ?: error("Customer phone alias references a missing local customer")
        } else {
            dao.getCustomerByPhone(businessId, canonicalPhone)
                ?: dao.getCustomerById(canonicalCustomerId)?.also {
                    require(it.businessId == businessId && it.canonicalPhone == canonicalPhone) {
                        "Canonical customer ID collides with another local identity"
                    }
                }
                ?: CustomerEntity(
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
            (existingLocalAlias.canonicalCustomerId == serverCanonicalId &&
                existingLocalAlias.canonicalPhone == canonicalPhone)) {
            "Local customer ID was previously associated with another canonical customer"
        }

        val now = maxOf(updatedAt, System.currentTimeMillis())
        // Keep an alias for every ID seen from another device and for the canonical ID.
        listOf(canonicalCustomerId, serverCanonicalId).distinct().forEach { aliasId ->
            dao.insertCustomerIdAlias(
                CustomerIdAliasEntity(
                    businessId = businessId,
                    aliasCustomerId = aliasId,
                    localCustomerId = local.id,
                    canonicalCustomerId = serverCanonicalId,
                    canonicalPhone = canonicalPhone,
                    updatedAt = now
                )
            )
            val saved = dao.getCustomerIdAliasByAliasId(businessId, aliasId)
            require(saved?.localCustomerId == local.id &&
                saved.canonicalCustomerId == serverCanonicalId &&
                saved.canonicalPhone == canonicalPhone) {
                "Customer alias persistence conflict"
            }
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
        val alias = dao.getCustomerIdAliasByAliasId(businessId, incomingCustomerId)
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
