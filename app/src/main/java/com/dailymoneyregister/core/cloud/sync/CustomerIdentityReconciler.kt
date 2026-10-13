package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.CustomerIdAliasEntity
import org.json.JSONObject

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
        updatedAt: Long,
        isServerCanonical: Boolean = false
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
            if (isServerCanonical && incomingAlias.canonicalCustomerId != canonicalCustomerId) {
                dao.updateCanonicalIdForLocalAliases(
                    businessId, local.id, canonicalCustomerId, canonicalPhone,
                    maxOf(updatedAt, System.currentTimeMillis())
                )
            }
            return local.id
        }

        val knownPhoneAlias = dao.getCustomerIdAliasByPhone(businessId, canonicalPhone)
        val serverCanonicalId = if (isServerCanonical) canonicalCustomerId
            else knownPhoneAlias?.canonicalCustomerId ?: canonicalCustomerId
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
        if (isServerCanonical) {
            dao.updateCanonicalIdForLocalAliases(
                businessId, local.id, serverCanonicalId, canonicalPhone,
                maxOf(updatedAt, System.currentTimeMillis())
            )
        }
        val existingLocalAlias = dao.getCustomerIdAliasByLocalId(businessId, local.id)
        require(isServerCanonical || existingLocalAlias == null ||
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

    /**
     * Reconcile only the customer identity snapshots carried by an already
     * tenant-validated inbound envelope. This changes identity aliases, never
     * historical financial IDs or amounts.
     */
    suspend fun reconcileEvidenceCustomers(
        database: DmrDatabase,
        businessId: String,
        evidence: JSONObject
    ) {
        val array = evidence.optJSONArray("customers")
        val rows = if (array != null) {
            (0 until array.length()).map { array.getJSONObject(it) }
        } else {
            listOfNotNull(evidence.optJSONObject("customer"))
        }
        for (row in rows) {
            val rowBusinessId = row.optString("business_id").ifBlank { businessId }
            require(rowBusinessId == businessId) {
                "Customer evidence belongs to another business"
            }
            val customerId = row.optString("id").ifBlank { row.optString("customer_id") }
            require(customerId.isNotBlank()) { "Customer evidence is missing its ID" }
            reconcileRemoteCustomer(
                database = database,
                businessId = businessId,
                canonicalCustomerId = customerId,
                canonicalPhone = row.optString("canonical_phone"),
                name = row.optString("name").ifBlank { row.optString("display_name") },
                createdAt = row.optLong("created_at", System.currentTimeMillis()),
                updatedAt = row.optLong("updated_at", row.optLong("created_at", System.currentTimeMillis())),
                isServerCanonical = false
            )
        }
    }

    /**
     * Resolve a remote/local ID when a same-business alias is known. If no
     * directory snapshot or alias has arrived yet, preserve the incoming ID;
     * do not reject an otherwise valid financial envelope merely due to sync
     * ordering. Existing local IDs that belong to another business are rejected.
     */
    suspend fun resolveLocalCustomerIdOrSelf(
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
        val alias = dao.getCustomerIdAliasByAliasId(businessId, incomingCustomerId) ?: return incomingCustomerId
        val local = dao.getCustomerById(alias.localCustomerId)
            ?: error("Customer alias references a missing local customer")
        require(local.businessId == businessId && local.canonicalPhone == alias.canonicalPhone) {
            "Customer alias belongs to another business or identity"
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
