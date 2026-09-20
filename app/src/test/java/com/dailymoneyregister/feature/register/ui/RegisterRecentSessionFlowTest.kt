package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterRecentSessionFlowTest {

    private val business = BusinessEntity(
        id = "business-1",
        name = "Test Business",
        createdAt = 1L,
        updatedAt = 1L
    )

    private val user = UserAccountEntity(
        id = "user-1",
        businessId = business.id,
        displayName = "Cashier",
        username = "cashier",
        role = UserRole.CASHIER,
        authType = AuthType.PIN,
        credentialHash = "hash",
        credentialSalt = "salt",
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun recentFlowSwitchesBranchStopsOldBranchAndClearsWithoutSession() = runTest {
        val sessions = MutableStateFlow<ActiveSession?>(
            session("branch-a", "Asia/Muscat")
        )

        val branchA = MutableStateFlow(listOf("a-transaction"))
        val branchB = MutableStateFlow(listOf("b-transaction"))
        val emissions = mutableListOf<List<String>>()

        backgroundScope.launch {
            observeRecentForSession(sessions) { branchId, _ ->
                when (branchId) {
                    "branch-a" -> branchA
                    "branch-b" -> branchB
                    else -> error("Unexpected branch: $branchId")
                }
            }.collect { emissions += it }
        }

        runCurrent()
        assertEquals(listOf("a-transaction"), emissions.last())

        sessions.value = session("branch-b", "Asia/Kolkata")
        runCurrent()
        assertEquals(listOf("b-transaction"), emissions.last())

        branchA.value = listOf("old-a-must-not-return")
        runCurrent()
        assertEquals(listOf("b-transaction"), emissions.last())

        sessions.value = null
        runCurrent()
        assertEquals(emptyList<String>(), emissions.last())
    }

    @Test
    fun recentFlowResubscribesWhenBranchTimezoneChanges() = runTest {
        val sessions = MutableStateFlow<ActiveSession?>(
            session("branch-a", "Asia/Muscat")
        )

        val subscriptions = mutableListOf<Pair<String, String>>()

        backgroundScope.launch {
            observeRecentForSession<String>(sessions) { branchId, timezoneId ->
                subscriptions += branchId to timezoneId
                flowOf(emptyList())
            }.collect()
        }

        runCurrent()

        sessions.value = session("branch-a", "UTC")
        runCurrent()

        assertEquals(
            listOf(
                "branch-a" to "Asia/Muscat",
                "branch-a" to "UTC"
            ),
            subscriptions
        )
    }

    private fun session(
        branchId: String,
        timezoneId: String
    ) = ActiveSession(
        business = business,
        branch = BranchEntity(
            id = branchId,
            businessId = business.id,
            name = branchId,
            code = branchId.uppercase(),
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = timezoneId,
            createdAt = 1L,
            updatedAt = 1L
        ),
        user = user,
        membership = null
    )
}
