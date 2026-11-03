package com.dailymoneyregister.core.database

import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity

/**
 * Inserts cloud-only actor fixtures without exposing a production write API on the DAO.
 */
internal fun DmrDatabase.insertCloudStaffIdentitiesForTest(
    identities: List<CloudStaffIdentityEntity>
) {
    val sqlite = openHelper.writableDatabase
    identities.forEach { identity ->
        sqlite.execSQL(
            """
            INSERT OR REPLACE INTO cloud_staff_identities (
                membership_id, local_user_id, business_id, branch_id, role,
                permissions_json, is_active, is_removed, updated_at, display_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                identity.membershipId,
                identity.localUserId,
                identity.businessId,
                identity.branchId,
                identity.role,
                identity.permissionsJson,
                if (identity.isActive) 1 else 0,
                if (identity.isRemoved) 1 else 0,
                identity.updatedAt,
                identity.displayName
            )
        )
    }
}
