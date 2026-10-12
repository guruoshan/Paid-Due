package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Maps any device-local/server customer ID alias to this device's stable local row
 * and the server's business-scoped canonical ID. Multiple device IDs may map to
 * one canonical customer; historical financial references are never rewritten.
 */
@Entity(
    tableName = "customer_id_aliases",
    primaryKeys = ["business_id", "alias_customer_id"],
    indices = [
        Index(value = ["business_id", "local_customer_id"]),
        Index(value = ["business_id", "canonical_customer_id"]),
        Index(value = ["business_id", "canonical_phone"])
    ]
)
data class CustomerIdAliasEntity(
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "alias_customer_id") val aliasCustomerId: String,
    @ColumnInfo(name = "local_customer_id") val localCustomerId: String,
    @ColumnInfo(name = "canonical_customer_id") val canonicalCustomerId: String,
    @ColumnInfo(name = "canonical_phone") val canonicalPhone: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
