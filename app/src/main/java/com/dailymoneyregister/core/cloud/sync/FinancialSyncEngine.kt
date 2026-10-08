package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import android.util.Log
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinancialSyncEngine @Inject constructor(
    private val database: DmrDatabase,
    private val remoteSyncRepository: RemoteFinancialSyncRepository,
    @ApplicationContext private val context: Context? = null
) {
    constructor(
        database: DmrDatabase,
        remoteSyncRepository: RemoteFinancialSyncRepository
    ) : this(database, remoteSyncRepository, null)

    private val inMemoryCursors = mutableMapOf<String, Pair<String, String>>()

    private fun getStoredCursor(businessId: String): Pair<String, String>? {
        val prefs = context?.getSharedPreferences("dmr_inbound_cursors", Context.MODE_PRIVATE)
        if (prefs == null) {
            return inMemoryCursors[businessId]
        }
        val raw = prefs.getString("inbound_cursor_$businessId", null) ?: return null
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Pair(parts[0], parts[1])
    }

    private fun saveStoredCursor(businessId: String, receivedAtIso: String, operationId: String) {
        val prefs = context?.getSharedPreferences("dmr_inbound_cursors", Context.MODE_PRIVATE)
        if (prefs == null) {
            inMemoryCursors[businessId] = Pair(receivedAtIso, operationId)
            return
        }
        prefs.edit()
            .putString("inbound_cursor_$businessId", "$receivedAtIso|$operationId")
            .apply()
    }

    suspend fun syncOutbound(actorMembershipId: String): Int {
        require(actorMembershipId.isNotBlank()) { "Financial sync requires an authorized membership" }
        val outboxDao = database.financialSyncOutboxDao()
        val operationDao = database.operationDao()
        val transactionDao = database.transactionDao()
        val expenseDao = database.expenseDao()

        val pendingList = outboxDao.getPendingUpload(50)
        
        val statusCounts = outboxDao.getStatusCounts()
        val countsStr = statusCounts.joinToString(", ") { "${it.operationType}[${it.status}]=${it.count}" }
        Log.i("PaidDueFinancialSync", "SYNC CYCLE OUTBOUND start: pending=${pendingList.size} outbox_totals={$countsStr}")

        var syncedCount = 0

        for (outboxItem in pendingList) {
            val currentState = runCatching {
                FinancialSyncDeliveryState.valueOf(outboxItem.status)
            }.getOrDefault(FinancialSyncDeliveryState.QUEUED)

            val inFlightState = try {
                FinancialSyncOutboxPolicy.beginUpload(currentState)
            } catch (e: Exception) {
                continue
            }

            outboxDao.updateStatus(outboxItem.operationId, inFlightState.name)

            val opEntity = operationDao.getOperationById(outboxItem.operationId)
            if (opEntity == null) {
                outboxDao.updateStatus(outboxItem.operationId, FinancialSyncDeliveryState.WAITING_RETRY.name, incrementAttempt = 1)
                continue
            }
            val txEntity = outboxItem.transactionId.let { transactionDao.getTransactionById(it) }
            val items = outboxItem.transactionId.let { transactionDao.getItemsForTransaction(it) }
            val settlements = transactionDao.getSettlementsForOperation(outboxItem.operationId)
            val expense = expenseDao.getExpenseByOperationId(outboxItem.operationId)

            val envelope = FinancialEnvelopeCodec.encode(
                operation = opEntity,
                transaction = txEntity,
                items = items,
                settlements = settlements,
                expense = expense
            )

            val receipt = remoteSyncRepository.uploadEnvelope(
                envelope = envelope,
                actorMembershipId = actorMembershipId
            )

            val identity = FinancialSyncIdentity(
                operationId = envelope.operationId,
                businessId = envelope.businessId,
                branchId = envelope.branchId,
                actorMembershipId = actorMembershipId,
                deviceId = envelope.deviceId,
                commandSchemaVersion = envelope.commandSchemaVersion,
                canonicalSha256 = envelope.canonicalSha256
            )

            val nextState = FinancialSyncOutboxPolicy.applyReceipt(
                identity = identity,
                state = inFlightState,
                receipt = receipt
            )

            outboxDao.updateStatus(outboxItem.operationId, nextState.name, incrementAttempt = 1)

            if (nextState == FinancialSyncDeliveryState.ACKNOWLEDGED) {
                outboxDao.deleteAcknowledged(outboxItem.operationId)
                syncedCount++
            }
        }

        return syncedCount
    }

    suspend fun syncInbound(businessId: String): Int {
        val session = SessionManager.currentSession.value
        val sessionBusinessId = session?.business?.id ?: businessId
        val sessionBranchId = session?.membership?.branchId

        val storedCursor = getStoredCursor(businessId)
        var currentAfterReceivedAt: String? = storedCursor?.first
        var currentAfterOperationId: String? = storedCursor?.second

        var totalApplied = 0
        var totalFetched = 0
        var totalSkipped = 0

        val limit = 100

        while (true) {
            val envelopes = remoteSyncRepository.fetchRemoteEnvelopesV2(
                businessId = businessId,
                afterReceivedAt = currentAfterReceivedAt,
                afterOperationId = currentAfterOperationId,
                limit = limit
            )

            if (envelopes.isEmpty()) {
                break
            }

            totalFetched += envelopes.size
            Log.i("PaidDueFinancialSync", "INBOUND_FETCHED ${envelopes.size}")

            var pageLastProcessedEnvelope: RemoteEnvelopeV2? = null

            for (envelope in envelopes) {
                val result = RemoteEnvelopeMaterializer.materializeInboundEnvelope(
                    database = database,
                    envelope = envelope,
                    sessionBusinessId = sessionBusinessId,
                    sessionBranchId = sessionBranchId
                )

                when (result) {
                    is InboundProcessResult.Applied -> {
                        totalApplied++
                        pageLastProcessedEnvelope = envelope
                    }
                    is InboundProcessResult.Skipped -> {
                        totalSkipped++
                        pageLastProcessedEnvelope = envelope
                    }
                    is InboundProcessResult.UnsupportedYet -> {
                        Log.i("PaidDueFinancialSync", "INBOUND_UNSUPPORTED_YET type=${result.operationType}")
                        totalSkipped++
                        pageLastProcessedEnvelope = envelope
                    }
                    is InboundProcessResult.Rejected -> {
                        Log.e("PaidDueFinancialSync", "INBOUND_REJECTED reason=${result.reason}")
                        if (pageLastProcessedEnvelope != null) {
                            saveStoredCursor(
                                businessId = businessId,
                                receivedAtIso = pageLastProcessedEnvelope.receivedAtIso,
                                operationId = pageLastProcessedEnvelope.operationId
                            )
                        }
                        if (totalApplied > 0) Log.i("PaidDueFinancialSync", "INBOUND_APPLIED $totalApplied")
                        if (totalSkipped > 0) Log.i("PaidDueFinancialSync", "INBOUND_SKIPPED $totalSkipped")
                        return totalApplied
                    }
                }
            }

            if (pageLastProcessedEnvelope != null) {
                currentAfterReceivedAt = pageLastProcessedEnvelope.receivedAtIso
                currentAfterOperationId = pageLastProcessedEnvelope.operationId
                saveStoredCursor(
                    businessId = businessId,
                    receivedAtIso = currentAfterReceivedAt!!,
                    operationId = currentAfterOperationId!!
                )
            }

            if (envelopes.size < limit) {
                break
            }
        }

        if (totalApplied > 0) Log.i("PaidDueFinancialSync", "INBOUND_APPLIED $totalApplied")
        if (totalSkipped > 0) Log.i("PaidDueFinancialSync", "INBOUND_SKIPPED $totalSkipped")

        return totalApplied
    }
}
