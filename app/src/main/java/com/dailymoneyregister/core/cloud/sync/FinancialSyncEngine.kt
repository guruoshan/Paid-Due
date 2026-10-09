package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import android.util.Log
import com.dailymoneyregister.BuildConfig
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class FinancialSyncEngine @Inject constructor(
    private val database: DmrDatabase,
    private val remoteSyncRepository: RemoteFinancialSyncRepository,
    private val inboundEnvelopeApplier: InboundEnvelopeApplier,
    @ApplicationContext private val context: Context? = null
) {
    constructor(
        database: DmrDatabase,
        remoteSyncRepository: RemoteFinancialSyncRepository,
        inboundEnvelopeApplier: InboundEnvelopeApplier = MaterializingInboundEnvelopeApplier()
    ) : this(database, remoteSyncRepository, inboundEnvelopeApplier, null)

    private val inMemoryCursors = mutableMapOf<String, VersionedInboundCursor>()
    private val syncMutex = Mutex()
    private val retryNotBefore = mutableMapOf<String, Long>()
    private var outboxInitialized = false
    private val lastInboundRejectionLogAt = mutableMapOf<String, Long>()

    private fun cursorKey(businessId: String, adminAccountId: String): String =
        "inbound_cursor_${businessId}_$adminAccountId"

    private suspend fun getStoredCursor(
        businessId: String,
        adminAccountId: String
    ): VersionedInboundCursor? {
        if (
            database.businessBranchDao().getBusinessById(businessId) == null ||
            database.userDao().getUserByIdInBusiness(adminAccountId, businessId) == null
        ) {
            clearStoredCursor(businessId, adminAccountId)
            return null
        }
        val key = cursorKey(businessId, adminAccountId)
        val prefs = context?.getSharedPreferences("dmr_inbound_cursors", Context.MODE_PRIVATE)
        if (prefs == null) {
            return inMemoryCursors[key]
        }
        val raw = prefs.getString(key, null) ?: return null
        return InboundCursorCodec.decode(raw)
    }

    private fun saveStoredCursor(
        businessId: String,
        adminAccountId: String,
        receivedAtIso: String,
        operationId: String
    ) {
        val key = cursorKey(businessId, adminAccountId)
        val prefs = context?.getSharedPreferences("dmr_inbound_cursors", Context.MODE_PRIVATE)
        if (prefs == null) {
            inMemoryCursors[key] = VersionedInboundCursor(
                InboundCursorCodec.CURRENT_MATERIALIZER_VERSION,
                receivedAtIso,
                operationId
            )
            return
        }
        prefs.edit()
            .putString(
                key,
                InboundCursorCodec.encode(
                    VersionedInboundCursor(
                        InboundCursorCodec.CURRENT_MATERIALIZER_VERSION,
                        receivedAtIso,
                        operationId
                    )
                )
            )
            .commit()
    }

    fun clearStoredCursor(businessId: String, adminAccountId: String) {
        val key = cursorKey(businessId, adminAccountId)
        inMemoryCursors.remove(key)
        context?.getSharedPreferences("dmr_inbound_cursors", Context.MODE_PRIVATE)
            ?.edit()?.remove(key)?.commit()
    }

    suspend fun syncOutbound(
        actorMembershipId: String,
        role: UserRole = SessionManager.currentSession.value?.user?.role ?: UserRole.ADMIN
    ): Int = syncMutex.withLock {
        require(actorMembershipId.isNotBlank()) { "Financial sync requires an authorized membership" }
        val outboxDao = database.financialSyncOutboxDao()
        val operationDao = database.operationDao()
        val transactionDao = database.transactionDao()
        val expenseDao = database.expenseDao()

        if (!outboxInitialized) {
            outboxDao.classifyExistingRows()
            outboxDao.resetStaleInFlight()
            outboxInitialized = true
        }

        val now = System.currentTimeMillis()
        val pendingList = outboxDao.getPendingUpload(50)
            .filter { now >= (retryNotBefore[it.operationId] ?: Long.MIN_VALUE) }
        
        val statusCounts = outboxDao.getStatusCounts()
        val countsStr = statusCounts.joinToString(", ") { "${it.operationType}[${it.status}]=${it.count}" }
        Log.i("PaidDueFinancialSync", "SYNC CYCLE OUTBOUND start: pending=${pendingList.size} outbox_totals={$countsStr}")

        var syncedCount = 0

        for (outboxItem in pendingList) {
            try {
                val opEntity = operationDao.getOperationById(outboxItem.operationId)
                    ?: error("Operation ${outboxItem.operationId} is missing")
                val policyState = FinancialSyncOperationPolicy.initialState(role, opEntity.operationType)
                if (policyState != FinancialSyncDeliveryState.QUEUED) {
                    withContext(NonCancellable) {
                        outboxDao.updateStatus(outboxItem.operationId, policyState.name)
                    }
                    continue
                }

                val currentState = FinancialSyncDeliveryState.valueOf(outboxItem.status)
                val inFlightState = FinancialSyncOutboxPolicy.beginUpload(currentState)
                withContext(NonCancellable) {
                    outboxDao.updateStatus(outboxItem.operationId, inFlightState.name)
                }

                val branch = database.businessBranchDao().getBranchById(outboxItem.branchId)
                    ?: error("Branch ${outboxItem.branchId} is missing")
                require(branch.businessId == outboxItem.businessId) {
                    "Outbox branch does not belong to its business"
                }
                val txEntity = transactionDao.getTransactionById(outboxItem.transactionId)
                val items = transactionDao.getItemsForTransaction(outboxItem.transactionId)
                val settlements = transactionDao.getSettlementsForOperation(outboxItem.operationId)
                val expense = expenseDao.getExpenseByOperationId(outboxItem.operationId)
                val envelope = FinancialEnvelopeCodec.encode(opEntity, txEntity, items, settlements, expense)
                val receipt = remoteSyncRepository.uploadEnvelope(
                    envelope = envelope,
                    actorMembershipId = actorMembershipId,
                    branchTimezoneId = branch.timezoneId
                )
                val identity = FinancialSyncIdentity(
                    envelope.operationId,
                    envelope.businessId,
                    envelope.branchId,
                    actorMembershipId,
                    envelope.deviceId,
                    envelope.commandSchemaVersion,
                    envelope.canonicalSha256
                )
                val nextState = FinancialSyncOutboxPolicy.applyReceipt(identity, inFlightState, receipt)
                withContext(NonCancellable) {
                    outboxDao.updateStatus(outboxItem.operationId, nextState.name, incrementAttempt = 1)
                    when (receipt) {
                        is FinancialSyncReceipt.NeedsAttention -> persistAttention(
                            outboxItem.operationId,
                            receipt.message
                        )
                        else -> Unit
                    }
                    if (nextState == FinancialSyncDeliveryState.ACKNOWLEDGED) {
                        outboxDao.deleteAcknowledged(outboxItem.operationId)
                    }
                }
                if (nextState == FinancialSyncDeliveryState.WAITING_RETRY) {
                    retryNotBefore[outboxItem.operationId] = now + retryDelayMillis(outboxItem.attemptCount + 1)
                } else {
                    retryNotBefore.remove(outboxItem.operationId)
                }
                if (nextState == FinancialSyncDeliveryState.ACKNOWLEDGED) syncedCount++
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    outboxDao.updateStatus(
                        outboxItem.operationId,
                        FinancialSyncDeliveryState.WAITING_RETRY.name,
                        incrementAttempt = 1
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e("PaidDueFinancialSync", "OUTBOUND_ITEM_FAILED operation=${outboxItem.operationId}", error)
                withContext(NonCancellable) {
                    outboxDao.updateStatus(
                        outboxItem.operationId,
                        FinancialSyncDeliveryState.WAITING_RETRY.name,
                        incrementAttempt = 1
                    )
                }
                retryNotBefore[outboxItem.operationId] = now + retryDelayMillis(outboxItem.attemptCount + 1)
            }
        }

        return syncedCount
    }

    private fun persistAttention(operationId: String, message: String) {
        context?.getSharedPreferences("dmr_financial_sync_attention", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(operationId, message)
            ?.apply()
        FinancialSyncStatus.report(FinancialSyncProblem(operationId, message))
    }

    internal fun retryDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 6)
        return (15_000L shl exponent).coerceAtMost(15 * 60_000L)
    }

    suspend fun syncInbound(
        businessId: String,
        adminAccountId: String
    ): Int = syncMutex.withLock {
        val session = SessionManager.currentSession.value
        val sessionBusinessId = session?.business?.id ?: businessId
        val sessionBranchId = session?.membership?.branchId

        val storedCursor = getStoredCursor(businessId, adminAccountId)
        var currentAfterReceivedAt: String? = storedCursor?.let {
            java.time.Instant.parse(it.receivedAtIso).minusSeconds(5).toString()
        }
        var currentAfterOperationId: String? = storedCursor?.operationId

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

            if (!BuildConfig.APPLY_INBOUND_FINANCIAL_ENVELOPES) {
                Log.w("PaidDueFinancialSync", "INBOUND_APPLY_DISABLED fetched=${envelopes.size}")
                return totalApplied
            }

            var pageLastProcessedEnvelope: RemoteEnvelopeV2? = null

            for (envelope in envelopes) {
                val result = try {
                    inboundEnvelopeApplier.apply(
                        database = database,
                        envelope = envelope,
                        sessionBusinessId = sessionBusinessId,
                        sessionBranchId = sessionBranchId
                    )
                } catch (error: Exception) {
                    InboundProcessResult.Rejected(
                        error.message ?: "Inbound envelope processing failed"
                    )
                }

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
                        FinancialSyncStatus.recordUnsupported(
                            context,
                            businessId,
                            envelope.operationId
                        )
                        totalSkipped++
                        pageLastProcessedEnvelope = envelope
                    }
                    is InboundProcessResult.Rejected -> {
                        reportInboundRejection(envelope.operationId, result.reason)
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
                    adminAccountId = adminAccountId,
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

    suspend fun syncInbound(businessId: String): Int =
        syncInbound(
            businessId,
            SessionManager.currentSession.value?.user?.id ?: "test-admin"
        )

    private fun reportInboundRejection(operationId: String, reason: String) {
        val message = "Inbound operation $operationId was rejected: $reason"
        FinancialSyncStatus.report(FinancialSyncProblem(operationId, message))
        context?.getSharedPreferences("dmr_financial_sync_attention", Context.MODE_PRIVATE)
            ?.edit()?.putString("inbound_$operationId", message)?.apply()
        val now = System.currentTimeMillis()
        val lastLoggedAt = lastInboundRejectionLogAt[operationId] ?: Long.MIN_VALUE
        if (lastLoggedAt == Long.MIN_VALUE || now - lastLoggedAt >= 60_000L) {
            Log.e("PaidDueFinancialSync", "INBOUND_REJECTED operation=$operationId reason=$reason")
            lastInboundRejectionLogAt[operationId] = now
        }
    }

}
