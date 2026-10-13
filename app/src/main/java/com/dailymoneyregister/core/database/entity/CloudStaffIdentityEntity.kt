package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cloud-authenticated staff identity projection used only to validate remote financial actors.
 * It deliberately contains no credentials, PINs, salts, or login material.
 */
@Entity(
    tableName = "cloud_staff_identities",
    indices = [
        Index(value = ["local_user_id", "business_id", "branch_id"], unique = true),
        Index(value = ["business_id", "branch_id", "is_active", "is_removed"])
    ]
)
data class CloudStaffIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "membership_id")
    val membershipId: String,

    @ColumnInfo(name = "local_user_id")
    val localUserId: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "permissions_json")
    val permissionsJson: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean,

    @ColumnInfo(name = "is_removed")
    val isRemoved: Boolean,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
