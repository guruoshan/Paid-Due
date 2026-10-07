package com.dailymoneyregister.feature.invite.data

import android.content.Context
import com.dailymoneyregister.core.model.enums.UserRole
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject

class LocalInviteStore @Inject constructor(
    @ApplicationContext context: Context
) : InviteStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override suspend fun create(invite: StaffInvite) {
        synchronized(lock) {
            val cutoff = invite.createdAt - RETENTION_MILLIS
            val editor = preferences.edit()
            preferences.all.forEach { (key, value) ->
                val stored = (value as? String)?.let(::decodeOrNull)
                if (stored == null || stored.createdAt < cutoff) editor.remove(key)
            }
            check(editor.putString(invite.tokenHash, invite.toJson().toString()).commit())
        }
    }

    override suspend fun findByTokenHash(hash: String): StaffInvite? = synchronized(lock) {
        preferences.getString(hash, null)?.let(::decodeOrNull)
    }

    override suspend fun markUsedIfUnused(hash: String, now: Long): Boolean = synchronized(lock) {
        val invite = preferences.getString(hash, null)?.let(::decodeOrNull) ?: return@synchronized false
        if (invite.usedAt != null || invite.revokedAt != null) return@synchronized false
        preferences.edit()
            .putString(hash, invite.copy(usedAt = now).toJson().toString())
            .commit()
    }

    override suspend fun revoke(hash: String, now: Long) {
        synchronized(lock) {
            val invite = preferences.getString(hash, null)?.let(::decodeOrNull) ?: return@synchronized
            check(
                preferences.edit()
                    .putString(hash, invite.copy(revokedAt = now).toJson().toString())
                    .commit()
            )
        }
    }

    override suspend fun listForBusiness(businessId: String): List<StaffInvite> = synchronized(lock) {
        preferences.all.values.mapNotNull { value ->
            (value as? String)?.let(::decodeOrNull)
        }.filter { it.businessId == businessId }
            .sortedByDescending { it.createdAt }
    }

    private fun StaffInvite.toJson(): JSONObject = JSONObject().apply {
        put("tokenHash", tokenHash)
        put("businessId", businessId)
        put("branchId", branchId)
        put("displayName", displayName)
        put("canonicalPhone", canonicalPhone)
        put("role", role.name)
        put("permissions", JSONObject().apply {
            permissions.forEach { (key, value) -> put(key, value) }
        })
        put("createdAt", createdAt)
        put("expiresAt", expiresAt)
        put("usedAt", usedAt ?: JSONObject.NULL)
        put("revokedAt", revokedAt ?: JSONObject.NULL)
    }

    private fun decodeOrNull(value: String): StaffInvite? = runCatching {
        val json = JSONObject(value)
        val permissionsJson = json.getJSONObject("permissions")
        val permissions = buildMap {
            permissionsJson.keys().forEach { key -> put(key, permissionsJson.getBoolean(key)) }
        }
        StaffInvite(
            tokenHash = json.getString("tokenHash"),
            businessId = json.getString("businessId"),
            branchId = json.getString("branchId"),
            displayName = json.getString("displayName"),
            canonicalPhone = json.getString("canonicalPhone"),
            role = UserRole.valueOf(json.getString("role")),
            permissions = permissions,
            createdAt = json.getLong("createdAt"),
            expiresAt = json.getLong("expiresAt"),
            usedAt = if (json.isNull("usedAt")) null else json.getLong("usedAt"),
            revokedAt = if (json.isNull("revokedAt")) null else json.getLong("revokedAt")
        )
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "dmr_staff_invites"
        const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
