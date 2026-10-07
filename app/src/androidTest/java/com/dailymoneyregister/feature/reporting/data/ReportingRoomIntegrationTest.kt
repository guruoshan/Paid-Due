package com.dailymoneyregister.feature.reporting.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.AndroidTestDatabaseFactory
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.security.ActiveSession
import com.dailymoneyregister.feature.reporting.domain.ReportingPeriod
import com.dailymoneyregister.feature.reporting.domain.ReportingScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportingRoomIntegrationTest {

    private lateinit var database: DmrDatabase

    private val business = BusinessEntity(
        id = "reporting-phase3b-business",
        name = "Reporting Phase 3B",
        createdAt = 1L,
        updatedAt = 1L
    )

    private val branch = BranchEntity(
        id = "reporting-phase3b-branch",
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

    private val admin = UserAccountEntity(
        id = "reporting-phase3b-admin",
        identityId = null,
        businessId = business.id,
        displayName = "Admin",
        username = "phase3b-admin",
        role = UserRole.ADMIN,
        authType = AuthType.PASSWORD,
        credentialHash = "test-hash",
        credentialSalt = "test-salt",
        isActive = true,
        createdAt = 1L,
        updatedAt = 1L
    )

    private val adminMembership = UserBranchMembershipEntity(
        id = "reporting-phase3b-admin-membership",
        userId = admin.id,
        branchId = branch.id,
        role = UserRole.ADMIN,
        canViewReports = false,
        createdAt = 1L
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AndroidTestDatabaseFactory.createInMemory(context)
        database.businessBranchDao().insertBusiness(business)
        database.businessBranchDao().insertBranch(branch)
        database.userDao().insertUser(admin)
        database.userDao().insertMembership(adminMembership)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistedSaleFlowsThroughRoomRepositoryIntoAuthoritativeSummary() = runBlocking {
        val occurredAt = 1_500L
        val transactionId = "reporting-phase3b-sale"
        val operationId = "reporting-phase3b-sale-op"

        database.transactionDao().insertTransaction(
            TransactionEntity(
                id = transactionId,
                branchId = branch.id,
                type = TransactionType.SALE,
                customerId = null,
                subtotalMinor = 1_250L,
                taxRateBps = 0,
                taxableBaseMinor = 1_250L,
                taxAmountMinor = 0L,
                txShippingMinor = 0L,
                txPackingMinor = 0L,
                grandTotalMinor = 1_250L,
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces = branch.currencyDecimalPlaces,
                createdByUserId = admin.id,
                deviceId = "reporting-phase3b-device",
                createdAt = occurredAt,
                description = null,
                displayRef = "P3B-001"
            )
        )

        database.operationDao().insertOperation(
            OperationEntity(
                id = operationId,
                businessId = business.id,
                branchId = branch.id,
                operationType = OperationType.RECORD_SALE,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transactionId,
                transactionId = transactionId,
                actorUserId = admin.id,
                deviceId = "reporting-phase3b-device",
                occurredAt = occurredAt,
                requestFingerprint = "reporting-phase3b-sale-fingerprint",
                commandSchemaVersion = 1,
                intentPayload = "reporting-phase3b-sale-payload",
                status = OperationStatus.SUCCEEDED
            )
        )

        database.transactionDao().insertSettlement(
            SettlementEntryEntity(
                id = "reporting-phase3b-settlement",
                operationId = operationId,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transactionId,
                transactionId = transactionId,
                entryPurpose = EntryPurpose.SALE_FUNDING,
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountSignedMinor = 1_250L,
                currencyCode = branch.currencyCode,
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = occurredAt,
                actorUserId = admin.id
            )
        )

        val repository = RoomReportingReadRepository(
            database = database,
            sessionProvider = {
                ActiveSession(
                    business = business,
                    branch = branch,
                    user = admin,
                    membership = adminMembership
                )
            }
        )

        val result = repository.read(
            ReportingScope(
                businessId = business.id,
                branchId = branch.id,
                cashierUserId = null,
                currencyCode = branch.currencyCode,
                period = ReportingPeriod(
                    fromInclusive = 1_000L,
                    toExclusive = 2_000L
                )
            )
        )

        assertEquals(1, result.rows.size)
        assertEquals(1_250L, result.summary.periodSaleInflow.amount)
        assertEquals(1_250L, result.summary.periodNetSalesActivity.amount)
        assertEquals(1_250L, result.summary.cashSalesCollected.amount)
        assertEquals(0L, result.summary.onlineSalesCollected.amount)
        assertEquals(0L, result.summary.creditSalesOriginated.amount)
        assertEquals(1, result.summary.counts.sales)
    }

    @Test
    fun cashierRemainsDeniedEvenWhenLegacyCanViewReportsIsTrue() = runBlocking {
        val cashier = UserAccountEntity(
            id = "reporting-phase3b-cashier",
            identityId = null,
            businessId = business.id,
            displayName = "Cashier",
            username = "phase3b-cashier",
            role = UserRole.CASHIER,
            authType = AuthType.PIN,
            credentialHash = "cashier-hash",
            credentialSalt = "cashier-salt",
            isActive = true,
            createdAt = 1L,
            updatedAt = 1L
        )
        val cashierMembership = UserBranchMembershipEntity(
            id = "reporting-phase3b-cashier-membership",
            userId = cashier.id,
            branchId = branch.id,
            role = UserRole.CASHIER,
            canViewReports = true,
            createdAt = 1L
        )
        database.userDao().insertUser(cashier)
        database.userDao().insertMembership(cashierMembership)

        val repository = RoomReportingReadRepository(
            database = database,
            sessionProvider = {
                ActiveSession(
                    business = business,
                    branch = branch,
                    user = cashier,
                    membership = cashierMembership
                )
            }
        )

        val result = runCatching {
            repository.read(
                ReportingScope(
                    businessId = business.id,
                    branchId = branch.id,
                    cashierUserId = null,
                    currencyCode = branch.currencyCode,
                    period = ReportingPeriod(1_000L, 2_000L)
                )
            )
        }

        assertTrue(result.exceptionOrNull() is ReportingAccessDeniedException)
    }
}