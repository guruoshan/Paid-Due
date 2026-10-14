package com.dailymoneyregister.core.cloud.sync

import android.util.Log
import com.dailymoneyregister.BuildConfig
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Cashier phone's local permissions equal to the server's.
 *
 * Admin edits permissions on the server (business_memberships). Before this
 * class existed the Cashier app copied permissions exactly once, at first
 * restore/activation, so later Admin changes never reached the Cashier phone.
 *
 * Authority is the server: GET /staff/metadata with the Cashier sync-session
 * bearer token returns this membership's current permission flags. The result
 * is written to the local Room membership row and to the in-memory
 * ActiveSession so the UI reacts immediately.
 *
 * Fail-safe rules (any of these changes nothing and keeps the last known
 * permissions):
 *  - no valid cloud session
 *  - response for a different membership, business, branch or role
 *  - response missing any of the 14 flags
 *  - network or HTTP failure
 */
@Singleton
class CashierPermissionRefresher @Inject constructor(
    database: DmrDatabase
) {
    private val userDao = database.userDao()

    /** Returns true only when local permissions actually changed. */
    suspend fun refresh(membershipId: String): Boolean {
        return try {
            refreshInternal(membershipId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "CASHIER_PERMISSION_REFRESH_FAILED", error)
            false
        }
    }

    private suspend fun refreshInternal(membershipId: String): Boolean {
        val token = CashierCloudSession.tokenFor(membershipId) ?: return false
        val session = SessionManager.currentSession.value ?: return false
        val sessionMembership = session.membership ?: return false
        if (sessionMembership.id != membershipId) return false

        val metadata = fetchMetadata(
            token = token,
            businessId = session.business.id,
            branchId = session.branch.id,
            membershipId = membershipId
        ) ?: return false

        if (
            metadata.optString("membership_id") != membershipId ||
            metadata.optString("business_id") != session.business.id ||
            metadata.optString("branch_id") != session.branch.id ||
            metadata.optString("role") != "CASHIER"
        ) {
            Log.w(TAG, "CASHIER_PERMISSION_REFRESH_IDENTITY_MISMATCH")
            return false
        }
        val remote = metadata.optJSONObject("permissions") ?: return false

        val flags = REQUIRED_KEYS.associateWith { key ->
            if (!remote.has(key) || remote.isNull(key)) {
                Log.w(TAG, "CASHIER_PERMISSION_REFRESH_INCOMPLETE")
                return false
            }
            remote.getBoolean(key)
        }

        val stored = userDao.getMembership(
            sessionMembership.userId,
            sessionMembership.branchId
        ) ?: return false
        if (stored.id != membershipId) return false

        val updated = stored.copy(
            canCreateSale = flags.getValue("can_create_sale"),
            canAcceptCash = flags.getValue("can_accept_cash"),
            canAcceptOnline = flags.getValue("can_accept_online"),
            canCreateCreditSale = flags.getValue("can_create_credit_sale"),
            canUseCoupon = flags.getValue("can_use_coupon"),
            canIssueInvoice = flags.getValue("can_issue_invoice"),
            canIssueReceipt = flags.getValue("can_issue_receipt"),
            canRefund = flags.getValue("can_refund"),
            canVoid = flags.getValue("can_void"),
            canCorrectAmount = flags.getValue("can_correct_amount"),
            canChangePaymentMethod = flags.getValue("can_change_payment_method"),
            canViewBroadHistory = flags.getValue("can_view_broad_history"),
            canManageProducts = flags.getValue("can_manage_products"),
            canViewReports = flags.getValue("can_view_reports")
        )

        val changed = updated != stored
        if (changed) userDao.updateMembership(updated)

        // The UI reads permissions from the in-memory session, so update it too.
        // Re-read the live session: the cashier may have signed out meanwhile.
        val live = SessionManager.currentSession.value
        if (live != null && live.membership?.id == membershipId && live.membership != updated) {
            SessionManager.setSession(live.copy(membership = updated))
        }

        if (changed) Log.i(TAG, "CASHIER_PERMISSIONS_REFRESHED")
        return changed
    }

    private suspend fun fetchMetadata(
        token: String,
        businessId: String,
        branchId: String,
        membershipId: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.PAID_DUE_BACKEND_BASE_URL.trimEnd('/')
        if (!baseUrl.startsWith("https://")) return@withContext null

        val connection = URL("$baseUrl/staff/metadata").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.setRequestProperty("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $token")

            val status = connection.responseCode
            if (status == 401 || status == 403) {
                // Same behaviour as upload/download: a rejected session is dropped.
                CashierCloudSession.clear()
                Log.w(TAG, "CASHIER_METADATA_SESSION_REJECTED")
                return@withContext null
            }
            if (status !in 200..299) {
                Log.w(TAG, "CASHIER_METADATA_HTTP_$status")
                return@withContext null
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "PaidDueFinancialSync"

        // Exact keys returned by public.fetch_cashier_metadata().permissions
        val REQUIRED_KEYS = listOf(
            "can_create_sale",
            "can_accept_cash",
            "can_accept_online",
            "can_create_credit_sale",
            "can_use_coupon",
            "can_issue_invoice",
            "can_issue_receipt",
            "can_refund",
            "can_void",
            "can_correct_amount",
            "can_change_payment_method",
            "can_view_broad_history",
            "can_manage_products",
            "can_view_reports"
        )
    }
}
