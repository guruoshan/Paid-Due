package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Maps a server-canonical customer ID to this device's stable local customer ID.
 * Financial rows continue to reference their original local IDs; this table is
 * an identity projection, never a destructive rewrite of historical evidence.
 */
@Entity(
    tableName = "customer_id_aliases",
    primaryKeys = ["business_id", "local_customer_id"],
    indices = [
        Index(value = ["business_id", "canonical_customer_id"], unique = true),
        Index(value = ["business_id", "canonical_phone"])
    ]
)
data class CustomerIdAliasEntity(
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "local_customer_id") val localCustomerId: String,
    @ColumnInfo(name = "canonical_customer_id") val canonicalCustomerId: String,
    @ColumnInfo(name = "canonical_phone") val canonicalPhone: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
