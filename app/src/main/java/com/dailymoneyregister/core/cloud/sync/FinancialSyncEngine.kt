package com.dailymoneyregister.core.cloud.sync

import android.content.Context
import android.util.Log
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
    // Owner-only mode must not instantiate the cashier identity/metadata sync service.
    // Keep its implementation and legacy guarded branches in source, but inject no
    // cashier runtime dependency into the active financial sync engine.
    private val staffIdentitySync: StaffIdentitySync = StaffIdentitySync.NoOp

    constructor(
        database: DmrDatabase,
        remoteSyncRepository: RemoteFinancialSyncRepository,
        inboundEnvelopeApplier: InboundEnvelopeApplier = MaterializingInboundEnvelopeApplier()
    ) : this(database, remoteSyncRepository, inboundEnvelopeApplier, null)

    private val inMemoryCursors = mutableMapOf<String, VersionedInboundCursor>()
    private val syncMutex = Mutex()
    private val retryBackoffStore = RetryBackoffStore(
        context?.getSharedPreferences("dmr_financial_sync_retry", Context.MODE_PRIVATE)
    )
    private var outboxInitialized = false
    private val lastInboundRejectionLogAt = mutableMapOf<String, Long>()

    private fun cursorKey(
        businessId: String,
        accountId: String,
        branchScopeId: String?
    ): String = buildString {
        append("inbound_cursor_").append(businessId).append("_").append(accountId)
        if (!branchScopeId.isNullOrBlank()) append("_branch_").append(branchScopeId)
    }

    private suspend fun getStoredCursor(
        businessId: String,
        accountId: String,
        branchScopeId: String?
    ): VersionedInboundCursor? {
        val activeSession = SessionManager.currentSession.value
        val activeSessionOwnsCursor =
            activeSession?.business?.id == businessId &&
                activeSession.user.id == accountId
        if (
            database.businessBranchDao().getBusinessById(businessId) == null ||
            (!activeSessionOwnsCursor &&
                database.userDao().getUserByIdInBusiness(accountId, businessId) == null)
        ) {
            clearStoredCursor(businessId, accountId, branchScopeId)
            return null
        }
        val key = cursorKey(businessId, accountId, branchScopeId)
        val prefs = context?.getSharedPreferences("dmr_inbound_cursors", Context.MODE_PRIVATE)
        if (prefs == null) {
            return inMemoryCursors[key]
        }
        val raw = prefs.getString(key, null) ?: return null
        return InboundCursorCodec.decode(raw)
    }

    private fun saveStoredCursor(
        businessId: String,
        accountId: String,
        branchScopeId: String?,
        receivedAtIso: String,
        operationId: String
    ) {
        val key = cursorKey(businessId, accountId, branchScopeId)
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

    fun clearStoredCursor(
        businessId: String,
        accountId: String,
        branchScopeId: String? = null
    ) {
        val key = cursorKey(businessId, accountId, branchScopeId)
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

        val activeSession = SessionManager.currentSession.value
            ?: run {
                Log.w("PaidDueFinancialSync", "OUTBOUND_SESSION_REQUIRED")
                return@withLock 0
            }
        if (
            activeSession.membership?.id != actorMembershipId ||
            activeSession.user.role != role
        ) {
            Log.w("PaidDueFinancialSync", "OUTBOUND_SESSION_SCOPE_MISMATCH")
            return@withLock 0
        }
        if (role != UserRole.ADMIN || activeSession.user.role != UserRole.ADMIN) {
            Log.i("PaidDueFinancialSync", "OUTBOUND_SKIPPED_NON_OWNER_SESSION")
            return@withLock 0
        }

        if (activeSession.user.role == UserRole.CASHIER) {
            // Refresh the authoritative cloud identity before startup reconciliation.
            // Otherwise a first-sync cloud-only cashier can be misclassified as
            // NOT_APPLICABLE simply because the local identity cache is still empty.
            val refreshed = runCatching {
                staffIdentitySync.refreshCashierPermissions(activeSession)
            }.onFailure { error ->
                Log.w("PaidDueFinancialSync", "CASHIER_PERMISSION_REFRESH_DEFERRED", error)
            }.getOrDefault(false)
            if (!refreshed) return@withLock 0
        }

        if (!outboxInitialized) {
            outboxDao.classifyExistingRows()
            outboxDao.resetStaleInFlight()
            outboxInitialized = true
        }
        if (
            role == UserRole.ADMIN &&
            activeSession != null &&
            activeSession.user.role == UserRole.ADMIN &&
            activeSession.membership?.id == actorMembershipId &&
            activeSession.business.id.isNotBlank() &&
            activeSession.branch.id.isNotBlank()
        ) {
            val authBlockedCount = outboxDao.countAuthRequiredForActor(
                businessId = activeSession.business.id,
                branchId = activeSession.branch.id,
                actorUserId = activeSession.user.id
            )
            if (
                authBlockedCount > 0 &&
                remoteSyncRepository.isAdminSessionAuthorized(
                    expectedMembershipId = actorMembershipId,
                    expectedBusinessId = activeSession.business.id,
                    expectedBranchId = activeSession.branch.id
                )
            ) {
                // Recover blocked delivery metadata only for this verified Admin's own
                // operations in the active tenant. Financial operations and IDs remain unchanged.
                val recovered = outboxDao.requeueAuthRequiredForActor(
                    businessId = activeSession.business.id,
                    branchId = activeSession.branch.id,
                    actorUserId = activeSession.user.id
                )
                if (recovered > 0) {
                    Log.i(
                        "PaidDueFinancialSync",
                        "AUTH_REQUIRED_RECOVERED count=$recovered actor=${activeSession.user.id}"
                    )
                }
            }
        }

        val now = System.currentTimeMillis()
        // Never upload another user's or another branch's operation under the
        // currently signed-in membership. The server receipt binds the envelope
        // to this membership, so cross-actor upload would corrupt actor attribution
        // and cause the receiving device to reject the acknowledged envelope.
        val pendingList = outboxDao.getPendingUploadForActor(
            businessId = activeSession.business.id,
            branchId = activeSession.branch.id,
            actorUserId = activeSession.user.id,
            limit = 50
        ).filter { now >= (retryBackoffStore.nextRetryAt(it.operationId) ?: Long.MIN_VALUE) }
        
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
                val financialEvidence = FinancialEnvelopeEvidenceLoader.load(
                    database = database,
                    operation = opEntity,
                    settlements = settlements
                )
                val envelope = FinancialEnvelopeCodec.encode(
                    operation = opEntity,
                    transaction = txEntity,
                    items = items,
                    settlements = settlements,
                    expense = expense,
                    financialEvidence = financialEvidence
                )
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
                    retryBackoffStore.save(outboxItem.operationId, now + retryDelayMillis(outboxItem.attemptCount + 1))
                } else {
                    retryBackoffStore.clear(outboxItem.operationId)
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
                retryBackoffStore.save(outboxItem.operationId, now + retryDelayMillis(outboxItem.attemptCount + 1))
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
        if (session?.user?.role != UserRole.ADMIN) {
            Log.i("PaidDueFinancialSync", "INBOUND_SKIPPED_NON_OWNER_SESSION")
            return@withLock 0
        }
        val sessionBusinessId = session.business.id
        val sessionBranchId = session.membership?.branchId
        // Owner download is business-scoped across every authorized branch.
        // Cashier identity-directory refresh is deliberately not launched in owner-only mode.
        val materializationBranchScope: String? = null

        // Cashier downloads are scoped to one branch and may have no local
        // user_accounts row. Keep their cursor branch-specific and trust the
        // matching active session for local cursor ownership.
        val cursorBranchScopeId =
            if (session?.user?.role == UserRole.CASHIER) sessionBranchId else null
        val storedCursor = getStoredCursor(businessId, adminAccountId, cursorBranchScopeId)
        var currentAfterReceivedAt: String? = storedCursor?.let {
            java.time.Instant.parse(it.receivedAtIso).minusSeconds(5).toString()
        }
        var currentAfterOperationId: String? = storedCursor?.operationId

        var totalApplied = 0
        var totalFetched = 0
        var totalSkipped = 0

        // Commit only through the last contiguous success before the first unresolved
        // envelope. Continue paging with a transient cursor so later valid envelopes run,
        // but leave the durable cursor behind the first poison item for the next sync retry.
        var unresolvedEnvelopeSeen = false
        var durableCursorCandidate: VersionedInboundCursor? = storedCursor

        if (session?.user?.role == UserRole.CASHIER) {
            val refreshed = runCatching {
                staffIdentitySync.refreshCashierPermissions(session)
            }.onFailure { error ->
                Log.w("PaidDueFinancialSync", "CASHIER_PERMISSION_REFRESH_DEFERRED", error)
            }.getOrDefault(false)
            if (!refreshed) return@withLock 0
        }

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
                if (!InboundApplicationGate.enabledFor(envelope.operationType)) {
                    Log.w(
                        "PaidDueFinancialSync",
                        "INBOUND_APPLY_DISABLED type=${envelope.operationType} fetched=${envelopes.size}"
                    )
                    FinancialSyncStatus.recordUnsupported(
                        context,
                        businessId,
                        envelope.operationId
                    )
                    quarantineInboundEnvelope(
                        envelope = envelope,
                        businessId = businessId,
                        sessionBranchId = sessionBranchId,
                        reason = "Inbound application is not enabled for operation ${envelope.operationType}; deferred"
                    )
                    unresolvedEnvelopeSeen = true
                    pageLastProcessedEnvelope = envelope
                    continue
                }

                // Verify the digest returned by the backend before parsing or applying
                // any financial evidence. A missing digest is not treated as trusted data.
                val remoteDigest = envelope.canonicalSha256
                val calculatedDigest = com.dailymoneyregister.core.command.CommandFingerprint
                    .sha256(envelope.canonicalText)
                if (
                    remoteDigest.isNullOrBlank() ||
                    !remoteDigest.equals(calculatedDigest, ignoreCase = true)
                ) {
                    quarantineInboundEnvelope(
                        envelope = envelope,
                        businessId = businessId,
                        sessionBranchId = sessionBranchId,
                        reason = if (remoteDigest.isNullOrBlank()) {
                            "Remote envelope is missing canonical_sha256"
                        } else {
                            "Remote canonical_sha256 does not match canonical_text"
                        }
                    )
                    unresolvedEnvelopeSeen = true
                    pageLastProcessedEnvelope = envelope
                    continue
                }

                val result = try {
                    inboundEnvelopeApplier.apply(
                        database = database,
                        envelope = envelope,
                        sessionBusinessId = sessionBusinessId,
                        sessionBranchId = materializationBranchScope
                    )
                } catch (error: Exception) {
                    InboundProcessResult.Rejected(
                        error.message ?: "Inbound envelope processing failed"
                    )
                }

                when (result) {
                    is InboundProcessResult.Applied -> {
                        appendRecoveryIfPreviouslyRejected(envelope, businessId, sessionBranchId)
                        totalApplied++
                        clearInboundRejection(envelope.operationId, businessId)
                        if (!unresolvedEnvelopeSeen) durableCursorCandidate = cursorFor(envelope)
                        pageLastProcessedEnvelope = envelope
                    }
                    is InboundProcessResult.Skipped -> {
                        appendRecoveryIfPreviouslyRejected(envelope, businessId, sessionBranchId)
                        totalSkipped++
                        clearInboundRejection(envelope.operationId, businessId)
                        if (!unresolvedEnvelopeSeen) durableCursorCandidate = cursorFor(envelope)
                        pageLastProcessedEnvelope = envelope
                    }
                    is InboundProcessResult.UnsupportedYet -> {
                        Log.i("PaidDueFinancialSync", "INBOUND_UNSUPPORTED_YET type=${result.operationType}")
                        FinancialSyncStatus.recordUnsupported(
                            context,
                            businessId,
                            envelope.operationId
                        )
                        quarantineInboundEnvelope(
                            envelope = envelope,
                            businessId = businessId,
                            sessionBranchId = sessionBranchId,
                            reason = "Unsupported inbound operation ${result.operationType}; deferred"
                        )
                        unresolvedEnvelopeSeen = true
                        pageLastProcessedEnvelope = envelope
                        continue
                    }
                    is InboundProcessResult.Rejected -> {
                        quarantineInboundEnvelope(
                            envelope = envelope,
                            businessId = businessId,
                            sessionBranchId = sessionBranchId,
                            reason = result.reason
                        )
                        unresolvedEnvelopeSeen = true
                        pageLastProcessedEnvelope = envelope
                        continue
                    }
                }
            }

            if (pageLastProcessedEnvelope != null) {
                // This cursor is only for traversing the current run's pages. Do not
                // persist it here: it may lie after an unresolved envelope.
                currentAfterReceivedAt = pageLastProcessedEnvelope.receivedAtIso
                currentAfterOperationId = pageLastProcessedEnvelope.operationId
            }

            if (envelopes.size < limit) {
                break
            }
        }

        durableCursorCandidate?.let { candidate ->
            saveStoredCursor(
                businessId = businessId,
                accountId = adminAccountId,
                branchScopeId = cursorBranchScopeId,
                receivedAtIso = candidate.receivedAtIso,
                operationId = candidate.operationId
            )
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

    private fun cursorFor(envelope: RemoteEnvelopeV2): VersionedInboundCursor =
        VersionedInboundCursor(
            InboundCursorCodec.CURRENT_MATERIALIZER_VERSION,
            envelope.receivedAtIso,
            envelope.operationId
        )

    private fun clearInboundRejection(operationId: String, businessId: String) {
        context?.getSharedPreferences("dmr_financial_sync_attention", Context.MODE_PRIVATE)
            ?.edit()?.remove("inbound_$operationId")?.commit()
        if (FinancialSyncStatus.adminVisibleProblem.value?.operationId == operationId) {
            FinancialSyncStatus.clearVisibleProblem()
        }
        FinancialSyncStatus.clearUnsupported(context, businessId, operationId)
    }

    /**
     * Persist rejection evidence before returning control to the sync loop. Repeated
     * attempts for the same unresolved operation do not create unbounded duplicate
     * audit rows; a RECOVERED event closes the current rejection state.
     */
    private suspend fun quarantineInboundEnvelope(
        envelope: RemoteEnvelopeV2,
        businessId: String,
        sessionBranchId: String?,
        reason: String
    ) {
        val auditDao = database.inboundEnvelopeAuditDao()
        if (!auditDao.hasRejection(envelope.operationId)) {
            auditDao.append(
                com.dailymoneyregister.core.database.entity.InboundEnvelopeAuditEventEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    operationId = envelope.operationId,
                    businessId = businessId,
                    branchId = envelope.branchId ?: sessionBranchId,
                    operationType = envelope.operationType,
                    receivedAtIso = envelope.receivedAtIso,
                    canonicalSha256 = envelope.canonicalSha256,
                    eventType = "REJECTED",
                    reason = reason,
                    occurredAt = System.currentTimeMillis()
                )
            )
        }
        reportInboundRejection(envelope.operationId, reason)
    }

    private suspend fun appendRecoveryIfPreviouslyRejected(
        envelope: RemoteEnvelopeV2,
        businessId: String,
        sessionBranchId: String?
    ) {
        val auditDao = database.inboundEnvelopeAuditDao()
        if (!auditDao.hasRejection(envelope.operationId)) return
        auditDao.append(
            com.dailymoneyregister.core.database.entity.InboundEnvelopeAuditEventEntity(
                id = java.util.UUID.randomUUID().toString(),
                operationId = envelope.operationId,
                businessId = businessId,
                branchId = envelope.branchId ?: sessionBranchId,
                operationType = envelope.operationType,
                receivedAtIso = envelope.receivedAtIso,
                canonicalSha256 = envelope.canonicalSha256,
                eventType = "RECOVERED",
                reason = "Envelope was successfully applied or verified as a duplicate after prior rejection.",
                occurredAt = System.currentTimeMillis()
            )
        )
    }

    private fun reportInboundRejection(operationId: String, reason: String) {
        val message = "Inbound operation $operationId was rejected: $reason"
        FinancialSyncStatus.report(FinancialSyncProblem(operationId, message))
        context?.getSharedPreferences("dmr_financial_sync_attention", Context.MODE_PRIVATE)
            ?.edit()?.putString("inbound_$operationId", message)?.commit()
        val now = System.currentTimeMillis()
        val lastLoggedAt = lastInboundRejectionLogAt[operationId] ?: Long.MIN_VALUE
        if (lastLoggedAt == Long.MIN_VALUE || now - lastLoggedAt >= 60_000L) {
            Log.e("PaidDueFinancialSync", "INBOUND_REJECTED operation=$operationId reason=$reason")
            lastInboundRejectionLogAt[operationId] = now
        }
    }

}
