package com.dailymoneyregister.feature.notifications

import androidx.lifecycle.ViewModelStore
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.feature.notifications.data.NotificationsRepository
import com.dailymoneyregister.feature.notifications.ui.NotificationsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelRealtimeTest {
    private var store: ViewModelStore? = null

    @Before
    fun setUp() {
        SessionManager.setSession(adminSession())
    }

    @After
    fun tearDown() {
        store?.clear()
        store = null
        SessionManager.clearSession()
        Dispatchers.resetMain()
    }

    @Test
    fun realtimeSubscriptionRetriesAfterDisconnect() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<NotificationsRepository>()
        val subscriptionAttempts = AtomicInteger()
        every { repository.observeAdminNotificationInserts() } answers {
            flow<Unit> {
                if (subscriptionAttempts.incrementAndGet() == 1) {
                    throw IOException("controlled socket disconnect")
                }
                awaitCancellation()
            }
        }
        coEvery { repository.listNotifications() } returns emptyList()
        coEvery { repository.listPermissionRequests() } returns emptyList()

        val viewModel = NotificationsViewModel(repository)
        store = ViewModelStore().apply { put("notifications-test", viewModel) }
        viewModel.refresh()
        runCurrent()

        assertTrue("Initial Realtime subscription must be attempted", subscriptionAttempts.get() >= 1)
        advanceTimeBy(3_001)
        runCurrent()
        assertTrue("Realtime subscription must be retried after disconnect", subscriptionAttempts.get() >= 2)
    }

    @Test
    fun durableInboxPollingContinuesWhileRealtimeFlowRemainsOpen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<NotificationsRepository>()
        val notificationLoads = AtomicInteger()
        every { repository.observeAdminNotificationInserts() } returns flow<Unit> {
            awaitCancellation()
        }
        coEvery { repository.listNotifications() } coAnswers {
            notificationLoads.incrementAndGet()
            emptyList()
        }
        coEvery { repository.listPermissionRequests() } returns emptyList()

        val viewModel = NotificationsViewModel(repository)
        store = ViewModelStore().apply { put("notifications-test", viewModel) }
        viewModel.refresh()
        runCurrent()
        val initialLoads = notificationLoads.get()

        advanceTimeBy(20_001)
        runCurrent()

        assertTrue("Polling must refresh the durable inbox while the Realtime flow is still open",
            notificationLoads.get() > initialLoads)
    }

    private fun adminSession(): ActiveSession {
        val business = BusinessEntity(
            id = "test-business",
            name = "Test Business",
            createdAt = 1L,
            updatedAt = 1L
        )
        val branch = BranchEntity(
            id = "test-branch",
            businessId = business.id,
            name = "Main",
            code = "MAIN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            createdAt = 1L,
            updatedAt = 1L
        )
        val admin = UserAccountEntity(
            id = "test-admin",
            businessId = business.id,
            displayName = "Test Admin",
            username = "test-admin",
            role = UserRole.ADMIN,
            authType = AuthType.PASSWORD,
            credentialHash = "test-only",
            credentialSalt = null,
            createdAt = 1L,
            updatedAt = 1L
        )
        return ActiveSession(business, branch, admin, membership = null)
    }
}
