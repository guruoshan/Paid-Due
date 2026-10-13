package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.BuildConfig
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Refreshes the active cashier's authoritative membership scope and permissions.
 * Network failures defer sync; revoked, moved, or role-changed memberships fail closed.
 */
@Singleton
class CashierMetadataSynchronizer @Inject constructor(
    private val database: DmrDatabase
) {
    suspend fun refresh(session: ActiveSession): Boolean = withContext(Dispatchers.IO) {
        if (session.user.role.name != "CASHIER") return@withContext true
        val membership = session.membership ?: run {
            SessionManager.clearSession()
            return@withContext false
        }
        val token = CashierCloudSession.tokenFor(membership.id)
            ?: run {
                SessionManager.clearSession()
                return@withContext false
            }
        val url = BuildConfig.PAID_DUE_SUPABASE_URL.trimEnd('/') + "/functions/v1/api/api/staff/metadata"
        require(url.startsWith("https://")) { "Supabase endpoint configuration is invalid." }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", BuildConfig.PAID_DUE_SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status == 401 || status == 403 || status == 404) {
                SessionManager.clearSession()
                return@withContext false
            }
            if (status !in 200..299) return@withContext false
            val row = JSONObject(raw)
            if (
                row.optString("membership_id") != membership.id ||
                row.optString("business_id") != session.business.id ||
                row.optString("branch_id") != session.branch.id ||
                row.optString("role") != "CASHIER"
            ) {
                SessionManager.clearSession()
                return@withContext false
            }
            val permissions = row.optJSONObject("permissions")
                ?: return@withContext false
            val current = database.userDao().getMembership(session.user.id, session.branch.id)
                ?: run {
                    SessionManager.clearSession()
                    return@withContext false
                }
            val refreshed = current.copy(
                canCreateSale = permissions.optBoolean("can_create_sale", current.canCreateSale),
                canAcceptCash = permissions.optBoolean("can_accept_cash", current.canAcceptCash),
                canAcceptOnline = permissions.optBoolean("can_accept_online", current.canAcceptOnline),
                canCreateCreditSale = permissions.optBoolean("can_create_credit_sale", current.canCreateCreditSale),
                canUseCoupon = permissions.optBoolean("can_use_coupon", current.canUseCoupon),
                canIssueInvoice = permissions.optBoolean("can_issue_invoice", current.canIssueInvoice),
                canIssueReceipt = permissions.optBoolean("can_issue_receipt", current.canIssueReceipt),
                canRefund = permissions.optBoolean("can_refund", current.canRefund),
                canVoid = permissions.optBoolean("can_void", current.canVoid),
                canCorrectAmount = permissions.optBoolean("can_correct_amount", current.canCorrectAmount),
                canChangePaymentMethod = permissions.optBoolean("can_change_payment_method", current.canChangePaymentMethod),
                canViewBroadHistory = permissions.optBoolean("can_view_broad_history", current.canViewBroadHistory),
                canAccessCustomerDirectory = permissions.optBoolean("can_access_customer_directory", false),
                canManageProducts = permissions.optBoolean("can_manage_products", current.canManageProducts),
                canViewReports = permissions.optBoolean("can_view_reports", current.canViewReports)
            )
            database.userDao().updateMembership(refreshed)
            true
        } finally {
            connection.disconnect()
        }
    }
}
