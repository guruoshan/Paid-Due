package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoneyregister.core.database.entity.InboundEnvelopeAuditEventEntity

@Dao
interface InboundEnvelopeAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(event: InboundEnvelopeAuditEventEntity)

    @Query("""
        SELECT (
            SELECT COUNT(*) FROM inbound_envelope_audit_events
            WHERE operation_id = :operationId AND event_type = 'REJECTED'
        ) > (
            SELECT COUNT(*) FROM inbound_envelope_audit_events
            WHERE operation_id = :operationId AND event_type = 'RECOVERED'
        )
    """)
    suspend fun hasRejection(operationId: String): Boolean

    @Query("""
        SELECT * FROM inbound_envelope_audit_events
        WHERE business_id = :businessId AND branch_id = :branchId
        ORDER BY occurred_at DESC
        LIMIT 200
    """)
    suspend fun recentForBranch(
        businessId: String,
        branchId: String
    ): List<InboundEnvelopeAuditEventEntity>
}
