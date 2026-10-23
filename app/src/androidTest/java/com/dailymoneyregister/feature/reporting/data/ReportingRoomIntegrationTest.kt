package com.dailymoneyregister.feature.reporting.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.AndroidTestDatabaseFactory
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.PaymentMethodChangeEventEntity
import com.dailymoneyregister.core.database.entity.SettlementEntryEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.database.entity.UserBranchMembershipEntity
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
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
import com.dailymoneyregister.feature.reporting.domain.SaleReportRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val cloudCashierId = "reporting-phase3b-cloud-cashier"
        database.cloudStaffIdentityDao().upsertAll(
            listOf(
                CloudStaffIdentityEntity(
                    membershipId = "reporting-phase3b-cloud-membership",
                    localUserId = cloudCashierId,
                    businessId = business.id,
                    branchId = branch.id,
                    role = "CASHIER",
                    permissionsJson = "{}",
                    isActive = true,
                    isRemoved = false,
                    updatedAt = 1L,
                    displayName = "Cloud Cashier"
                )
            )
        )
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
                actorUserId = cloudCashierId,
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
                actorUserId = cloudCashierId
            )
        )

        // A close lifecycle operation has no transaction or settlement and must
        // never increase sale counts or cash-sales totals.
        val shiftId = "reporting-phase3b-shift"
        val closeOperationId = "reporting-phase3b-close-op"
        database.operationDao().insertOperation(
            OperationEntity(
                id = closeOperationId,
                businessId = business.id,
                branchId = branch.id,
                operationType = OperationType.CLOSE_CASH_SHIFT,
                subjectType = SubjectType.CASH_SHIFT,
                subjectId = shiftId,
                transactionId = null,
                cashShiftId = shiftId,
                actorUserId = cloudCashierId,
                deviceId = "reporting-phase3b-device",
                occurredAt = 1_800L,
                requestFingerprint = "reporting-phase3b-close-fingerprint",
                commandSchemaVersion = 1,
                intentPayload = "{}",
                status = OperationStatus.SUCCEEDED
            )
        )
        database.cashShiftDao().insertShift(
            CashShiftEntity(
                id = shiftId,
                branchId = branch.id,
                userId = cloudCashierId,
                createdAt = 1_400L
            )
        )
        database.cashShiftDao().insertShiftEvent(
            CashShiftEventEntity(
                id = "$closeOperationId:CLOSED",
                shiftId = shiftId,
                operationId = closeOperationId,
                type = CashShiftEventType.CLOSED,
                amountMinor = 0L,
                occurredAt = 1_800L,
                actorUserId = cloudCashierId
            )
        )

        // An opened and counted shift with no CLOSE is a normal operational
        // state. It must not prevent an Admin from reading financial reports.
        val unclosedShiftId = "reporting-phase3b-unclosed-shift"
        database.cashShiftDao().insertShift(
            CashShiftEntity(
                id = unclosedShiftId,
                branchId = branch.id,
                userId = admin.id,
                createdAt = 1_410L
            )
        )
        val openOperationId = "reporting-phase3b-open-op"
        database.operationDao().insertOperation(
            OperationEntity(
                id = openOperationId,
                businessId = business.id,
                branchId = branch.id,
                operationType = OperationType.OPEN_CASH_SHIFT,
                subjectType = SubjectType.CASH_SHIFT,
                subjectId = unclosedShiftId,
                transactionId = null,
                cashShiftId = unclosedShiftId,
                actorUserId = admin.id,
                deviceId = "reporting-phase3b-device",
                occurredAt = 1_410L,
                requestFingerprint = "reporting-phase3b-open-fingerprint",
                commandSchemaVersion = 1,
                intentPayload = "{}",
                status = OperationStatus.SUCCEEDED
            )
        )
        database.cashShiftDao().insertShiftEvent(
            CashShiftEventEntity(
                id = "$openOperationId:OPENED",
                shiftId = unclosedShiftId,
                operationId = openOperationId,
                type = CashShiftEventType.OPENED,
                amountMinor = 0L,
                occurredAt = 1_410L,
                actorUserId = admin.id
            )
        )
        val countOperationId = "reporting-phase3b-count-op"
        database.operationDao().insertOperation(
            OperationEntity(
                id = countOperationId,
                businessId = business.id,
                branchId = branch.id,
                operationType = OperationType.COUNT_CASH_SHIFT,
                subjectType = SubjectType.CASH_SHIFT,
                subjectId = unclosedShiftId,
                transactionId = null,
                cashShiftId = unclosedShiftId,
                actorUserId = admin.id,
                deviceId = "reporting-phase3b-device",
                occurredAt = 1_700L,
                requestFingerprint = "reporting-phase3b-count-fingerprint",
                commandSchemaVersion = 1,
                intentPayload = "{}",
                status = OperationStatus.SUCCEEDED
            )
        )
        database.cashShiftDao().insertShiftEvent(
            CashShiftEventEntity(
                id = "$countOperationId:COUNTED",
                shiftId = unclosedShiftId,
                operationId = countOperationId,
                type = CashShiftEventType.COUNTED,
                amountMinor = 1_250L,
                occurredAt = 1_700L,
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
        assertNull((result.rows.single() as SaleReportRow).cashier.displayName)

        // A payment-method change is a reclassification of the existing sale,
        // not a second sale. The current snapshot must move the tender from cash
        // to online while keeping the sale count and net sales unchanged.
        val paymentChangeOperationId = "reporting-phase3b-payment-change-op"
        database.operationDao().insertOperation(
            OperationEntity(
                id = paymentChangeOperationId,
                businessId = business.id,
                branchId = branch.id,
                operationType = OperationType.RECORD_PAYMENT_METHOD_CHANGE,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transactionId,
                transactionId = transactionId,
                actorUserId = cloudCashierId,
                deviceId = "reporting-phase3b-device",
                occurredAt = 1_600L,
                requestFingerprint = "reporting-phase3b-payment-change-fingerprint",
                commandSchemaVersion = 1,
                intentPayload = "{}",
                status = OperationStatus.SUCCEEDED
            )
        )
        database.transactionDao().insertPaymentMethodChange(
            PaymentMethodChangeEventEntity(
                id = "reporting-phase3b-payment-change-event",
                transactionId = transactionId,
                operationId = paymentChangeOperationId,
                previousMethod = PaymentCategory.CASH,
                previousChannel = PaymentChannel.CASH_TENDER,
                newMethod = PaymentCategory.ONLINE,
                newChannel = PaymentChannel.MANUAL_QR,
                reason = "Corrected tender",
                actorUserId = cloudCashierId,
                occurredAt = 1_600L
            )
        )
        database.transactionDao().insertSettlement(
            SettlementEntryEntity(
                id = "reporting-phase3b-payment-change-cash",
                operationId = paymentChangeOperationId,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transactionId,
                transactionId = transactionId,
                entryPurpose = EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION,
                category = PaymentCategory.CASH,
                channel = PaymentChannel.CASH_TENDER,
                amountSignedMinor = -1_250L,
                currencyCode = branch.currencyCode,
                confirmationType = ConfirmationType.CASHIER_CONFIRMED,
                occurredAt = 1_600L,
                actorUserId = cloudCashierId
            )
        )
        database.transactionDao().insertSettlement(
            SettlementEntryEntity(
                id = "reporting-phase3b-payment-change-online",
                operationId = paymentChangeOperationId,
                subjectType = SubjectType.TRANSACTION,
                subjectId = transactionId,
                transactionId = transactionId,
                entryPurpose = EntryPurpose.PAYMENT_METHOD_RECLASSIFICATION,
                category = PaymentCategory.ONLINE,
                channel = PaymentChannel.MANUAL_QR,
                amountSignedMinor = 1_250L,
                currencyCode = branch.currencyCode,
                confirmationType = ConfirmationType.PROVIDER_APPROVED,
                occurredAt = 1_600L,
                actorUserId = cloudCashierId
            )
        )

        val afterPaymentChange = repository.read(
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
        assertEquals(2, afterPaymentChange.rows.size)
        assertEquals(1_250L, afterPaymentChange.summary.periodSaleInflow.amount)
        assertEquals(1_250L, afterPaymentChange.summary.periodNetSalesActivity.amount)
        assertEquals(0L, afterPaymentChange.summary.cashSalesCollected.amount)
        assertEquals(1_250L, afterPaymentChange.summary.onlineSalesCollected.amount)
        assertEquals(1, afterPaymentChange.summary.counts.sales)

        val options = ReportingFilterOptionsRepository(database).load(business.id, branch.currencyCode)
        assertTrue(options.cashiers.isEmpty())

        val filtered = repository.read(
            ReportingScope(
                businessId = business.id,
                branchId = branch.id,
                cashierUserId = cloudCashierId,
                currencyCode = branch.currencyCode,
                period = ReportingPeriod(1_000L, 2_000L)
            )
        )
        assertEquals(2, filtered.rows.size)
        assertEquals(1, filtered.summary.counts.sales)
        assertEquals(0L, filtered.summary.cashSalesCollected.amount)
        assertEquals(1_250L, filtered.summary.onlineSalesCollected.amount)
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