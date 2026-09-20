package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun stoppedSharingExpiresOldBranchBeforeResubscription() = runTest {
        val sessions = MutableStateFlow<ActiveSession?>(session("branch-a", "Asia/Muscat"))
        val recent = observeRecentForSession(sessions) { branchId, _ ->
            flowOf(listOf(branchId))
        }.stateIn(backgroundScope, registerRecentSharingStarted, emptyList())
        val firstCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            recent.collect()
        }
        runCurrent()
        assertEquals(listOf("branch-a"), recent.value)

        firstCollector.cancel()
        runCurrent()
        advanceTimeBy(4_999)
        assertEquals(listOf("branch-a"), recent.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(emptyList<String>(), recent.value)
        assertEquals(listOf(emptyList<String>()), recent.replayCache)

        sessions.value = session("branch-b", "Asia/Kolkata")
        runCurrent()
        assertEquals(emptyList<String>(), recent.value)
        val resumed = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            recent.collect { resumed += it }
        }
        assertEquals(emptyList<String>(), resumed.first())
        runCurrent()
        assertEquals(listOf("branch-b"), resumed.last())
        assertFalse(resumed.any { "branch-a" in it })
    }

    @Test
    fun activeBranchSwitchClearsOldRowsWhileNewQueryIsStillWaiting() = runTest {
        val sessions = MutableStateFlow<ActiveSession?>(session("branch-a", "Asia/Muscat"))
        val branchB = MutableSharedFlow<List<String>>()
        val recent = observeRecentForSession(sessions) { branchId, _ ->
            if (branchId == "branch-a") flowOf(listOf("a")) else branchB
        }.stateIn(backgroundScope, registerRecentSharingStarted, emptyList())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recent.collect() }
        runCurrent()
        assertEquals(listOf("a"), recent.value)
        sessions.value = session("branch-b", "Asia/Kolkata")
        runCurrent()
        assertEquals(emptyList<String>(), recent.value)
        branchB.emit(listOf("b"))
        runCurrent()
        assertEquals(listOf("b"), recent.value)
        sessions.value = null
        runCurrent()
        assertEquals(emptyList<String>(), recent.value)
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
