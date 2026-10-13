package com.dailymoneyregister.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only local audit evidence for rejected inbound envelopes and later recovery. */
@Entity(
    tableName = "inbound_envelope_audit_events",
    indices = [
        Index(value = ["operation_id", "occurred_at"]),
        Index(value = ["business_id", "branch_id", "occurred_at"])
    ]
)
data class InboundEnvelopeAuditEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "business_id")
    val businessId: String,

    @ColumnInfo(name = "branch_id")
    val branchId: String?,

    @ColumnInfo(name = "operation_type")
    val operationType: String,

    @ColumnInfo(name = "received_at_iso")
    val receivedAtIso: String,

    @ColumnInfo(name = "canonical_sha256")
    val canonicalSha256: String?,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "reason")
    val reason: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)
