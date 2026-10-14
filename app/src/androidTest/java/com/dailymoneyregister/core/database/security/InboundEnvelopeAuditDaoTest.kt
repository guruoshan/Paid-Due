package com.dailymoneyregister.core.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.InboundEnvelopeAuditEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboundEnvelopeAuditDaoTest {

    @Test
    fun rejectionRemainsOpenUntilRecoveryAndAuditRowsAreImmutable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = DmrDatabase.createInMemory(context)
        try {
            val dao = database.inboundEnvelopeAuditDao()
            dao.append(
                InboundEnvelopeAuditEventEntity(
                    id = "audit-rejected",
                    operationId = "replay-operation",
                    businessId = "audit-business",
                    branchId = "audit-branch",
                    operationType = "RECORD_SALE",
                    receivedAtIso = "2026-10-14T10:00:00Z",
                    canonicalSha256 = "a".repeat(64),
                    eventType = "REJECTED",
                    reason = "Missing synchronized actor identity",
                    occurredAt = 100
                )
            )

            assertTrue(dao.hasRejection("replay-operation"))
            assertEquals(1, dao.recentForBranch("audit-business", "audit-branch").size)

            dao.append(
                InboundEnvelopeAuditEventEntity(
                    id = "audit-recovered",
                    operationId = "replay-operation",
                    businessId = "audit-business",
                    branchId = "audit-branch",
                    operationType = "RECORD_SALE",
                    receivedAtIso = "2026-10-14T10:00:00Z",
                    canonicalSha256 = "a".repeat(64),
                    eventType = "RECOVERED",
                    reason = "Applied successfully after the identity was synchronized",
                    occurredAt = 200
                )
            )

            assertFalse(dao.hasRejection("replay-operation"))
            assertEquals(2, dao.recentForBranch("audit-business", "audit-branch").size)

            val sql = database.openHelper.writableDatabase
            assertTrue(
                "Audit updates must be rejected",
                runCatching {
                    sql.execSQL(
                        "UPDATE inbound_envelope_audit_events SET reason='tampered' WHERE id='audit-rejected'"
                    )
                }.isFailure
            )
            assertTrue(
                "Audit deletes must be rejected",
                runCatching {
                    sql.execSQL(
                        "DELETE FROM inbound_envelope_audit_events WHERE id='audit-rejected'"
                    )
                }.isFailure
            )
            assertEquals(
                "Missing synchronized actor identity",
                sql.query(
                    "SELECT reason FROM inbound_envelope_audit_events WHERE id='audit-rejected'"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
            )
        } finally {
            database.close()
        }
    }
}
