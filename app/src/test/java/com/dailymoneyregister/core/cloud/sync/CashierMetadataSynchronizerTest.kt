package com.dailymoneyregister.core.cloud.sync

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CashierMetadataSynchronizerTest {
    @Test
    fun acceptsTheExistingProductionFourteenPermissionKeys() {
        assertTrue(hasCompatibleCashierPermissionSchema(validPermissions()))
    }

    @Test
    fun rejectsMissingPermissionKey() {
        val permissions = validPermissions().apply {
            remove("can_view_reports")
        }

        assertFalse(hasCompatibleCashierPermissionSchema(permissions))
    }

    @Test
    fun rejectsAnAdditionalPermissionKeyInsteadOfSilentlyChangingTheContract() {
        val permissions = validPermissions().put("can_access_customer_directory", true)

        assertFalse(hasCompatibleCashierPermissionSchema(permissions))
    }

    @Test
    fun rejectsNonBooleanPermissionValues() {
        val permissions = validPermissions().put("can_view_reports", "true")

        assertFalse(hasCompatibleCashierPermissionSchema(permissions))
    }

    private fun validPermissions(): JSONObject = JSONObject().apply {
        put("can_create_sale", true)
        put("can_accept_cash", true)
        put("can_accept_online", true)
        put("can_create_credit_sale", true)
        put("can_use_coupon", true)
        put("can_issue_invoice", true)
        put("can_issue_receipt", true)
        put("can_refund", true)
        put("can_void", true)
        put("can_correct_amount", true)
        put("can_change_payment_method", true)
        put("can_view_broad_history", true)
        put("can_manage_products", true)
        put("can_view_reports", true)
    }
}
