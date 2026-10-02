package com.dailymoneyregister

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.*
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.*
import com.dailymoneyregister.core.model.enums.*
import com.dailymoneyregister.core.model.ids.*
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.*
import com.dailymoneyregister.feature.register.data.*
import com.dailymoneyregister.feature.register.domain.DraftState
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CouponSaleIntegrationTest {
    private lateinit var db: DmrDatabase
    private lateinit var repository: RegisterRepository
    private val business = BusinessEntity("coupon-biz", "Coupon Test", 0, 0)
    private val branch = BranchEntity("coupon-branch", business.id, "Main", "MAIN", "OM", "OMR", 3,
        "OMR", "Asia/Muscat", createdAt = 0, updatedAt = 0)
    private val user = UserAccountEntity(id = "coupon-user", businessId = business.id, displayName = "Cashier",
        username = "coupon-cashier", role = UserRole.CASHIER, authType = AuthType.PIN,
        credentialHash = "hash", credentialSalt = "salt", createdAt = 0, updatedAt = 0)
    private val member = UserBranchMembershipEntity("coupon-member", user.id, branch.id, UserRole.CASHIER,
        canCreateSale = true, canAcceptCash = true, canAcceptOnline = true, canCreateCreditSale = true,
        canUseCoupon = true, canRefund = true, createdAt = 0)

    @Before fun setup() = runBlocking {
        db = DmrDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        repository = RegisterRepository(db, CommandExecutor(db))
        db.businessBranchDao().insertBusiness(business)
        db.businessBranchDao().insertBranch(branch)
        db.userDao().insertUser(user)
        db.userDao().insertMembership(member)
        SessionManager.setSession(ActiveSession(business, branch, user, member))
    }
    @After fun close() { SessionManager.clearSession(); db.close() }

    private fun command(category: PaymentCategory = PaymentCategory.CASH) = CouponSaleCommand(
        OperationId("coupon-op"), business.id, branch.id, user.id, TransactionId("coupon-tx"),
        "coupon-customer", "Purchaser", "+96891234567", true, 5000, "OMR", 3, category,
        listOf("Family Member"), "coupon-device", 1700000000000)
    private fun pending(c: CouponSaleCommand) = OperationEntity(
        id = c.operationId.value, businessId = c.businessId, branchId = c.branchId,
        operationType = c.operationType, subjectType = c.subjectType, subjectId = c.subjectId,
        transactionId = c.transactionId, actorUserId = c.actorUserId, deviceId = c.deviceId,
        occurredAt = c.occurredAt, requestFingerprint = c.requestFingerprint,
        commandSchemaVersion = c.commandSchemaVersion, intentPayload = c.intentPayload, status = OperationStatus.PENDING)

    @Test fun cashPurchaseCommitsCustomerFundingLedgerAndAuditExactlyOnce() = runBlocking {
        val c = command()
        val first = repository.executeCouponSale(c)
        assertTrue(first.toString(), first is CommandResult.Success)
        val result = (first as CommandResult.Success).data
        assertEquals(TransactionType.COUPON_PURCHASE, result.transaction.type)
        assertEquals(c.customerId, result.transaction.customerId)
        assertEquals(c.amountMinor, db.ledgerDao().getCouponBalance(result.walletId))
        val settlement = db.transactionDao().getSettlementsForTransaction(c.subjectId).single()
        assertEquals(EntryPurpose.COUPON_PURCHASE_FUNDING, settlement.entryPurpose)
        assertEquals(c.operationId.value, settlement.operationId)
        assertEquals(PaymentCategory.CASH, settlement.category)
        val users = db.ledgerDao().getActiveAuthorizedUsers(result.walletId)
        assertEquals(2, users.size)
        val purchaser = users.single { it.isPurchaser }
        assertEquals(c.customerName, db.ledgerDao().getAuthorizedUserEvents(purchaser.id).single().nameValue)
        val retry = repository.executeCouponSale(c) as CommandResult.Success
        assertTrue(retry.isIdempotentReplay)
        assertEquals(result, retry.data)
        assertEquals(1, db.ledgerDao().getCouponLedgerForWallet(result.walletId).size)
        assertEquals(1, db.transactionDao().getSettlementsForTransaction(c.subjectId).size)
        assertTrue(repository.executeCouponSale(c.copy(amountMinor = 6000)) is CommandResult.Conflict)
    }

    @Test fun onlinePurchaseUsesExistingCustomerAndProjectionCountsOnce() = runBlocking {
        val c = command(PaymentCategory.ONLINE).copy(createCustomer = false)
        db.customerDao().insertCustomer(CustomerEntity(c.customerId, c.businessId, c.canonicalPhone, c.customerName, 0, 0))
        val result = repository.executeCouponSale(c)
        assertTrue(result.toString(), result is CommandResult.Success)
        val view = repository.getFinancialTransactionById(c.subjectId)!!
        assertEquals(PaymentCategory.ONLINE, view.currentPaymentCategory)
        assertEquals(PaymentChannel.MANUAL_QR, view.currentPaymentChannel)
        assertEquals(5000L, view.periodFinancialEffect.amount)
        assertEquals(5000L, view.settlementTotal.amount)
        assertEquals(1, db.customerDao().getCustomersForBusiness(business.id).size)
    }

    @Test fun customerSearchIncludesLedgerDerivedWalletAndNoWalletState() = runBlocking {
        val c = command()
        assertTrue(repository.executeCouponSale(c) is CommandResult.Success)
        db.customerDao().insertCustomer(
            CustomerEntity("coupon-no-wallet", business.id, "+96898765432", "No Wallet", 1, 1)
        )

        val customers = repository.searchCouponSaleCustomers("")
        val funded = customers.single { it.customer.id == c.customerId }
        assertNotNull(funded.wallet)
        assertTrue(funded.wallet!!.isActive)
        assertEquals("OMR", funded.wallet.currencyCode)
        assertEquals(c.amountMinor, funded.balanceMinor)
        assertEquals(db.ledgerDao().getCouponBalance(funded.wallet.id), funded.balanceMinor)

        val empty = customers.single { it.customer.id == "coupon-no-wallet" }
        assertNull(empty.wallet)
        assertEquals(0L, empty.balanceMinor)
    }

    @Test fun concurrentSubmissionsCreditOnlyOnce() = runBlocking {
        val results = listOf(async { repository.executeCouponSale(command()) }, async { repository.executeCouponSale(command()) }).awaitAll()
        assertTrue(results.toString(), results.all { it is CommandResult.Success })
        val wallet = db.ledgerDao().getCouponWallet(command().customerId, "OMR")!!
        assertEquals(5000L, db.ledgerDao().getCouponBalance(wallet.id))
    }

    @Test fun invalidPhoneRollsBackCustomerWalletFundingAndLedger() = runBlocking {
        val result = repository.executeCouponSale(command().copy(canonicalPhone = "+96811111111"))
        assertTrue(result is CommandResult.Error)
        assertNull(db.customerDao().getCustomerById(command().customerId))
        assertNull(db.ledgerDao().getCouponWallet(command().customerId, "OMR"))
        assertNull(db.transactionDao().getTransactionById(command().subjectId))
        assertTrue(db.transactionDao().getSettlementsForOperation(command().operationId.value).isEmpty())
    }

    @Test fun wrongCurrencyRollsBackWithoutFinancialWrites() = runBlocking {
        assertTrue(repository.executeCouponSale(command().copy(currencyCode = "INR", currencyDecimalPlaces = 2)) is CommandResult.Error)
        assertNull(db.customerDao().getCustomerById(command().customerId))
        assertNull(db.transactionDao().getTransactionById(command().subjectId))
    }

    @Test fun permissionsAndSessionAreRequiredBeforeAdmission() = runBlocking {
        SessionManager.clearSession()
        assertTrue(repository.executeCouponSale(command()) is CommandResult.PermissionDenied)
        assertNull(db.operationDao().getOperationById(command().operationId.value))
        val denied = user.copy(id = "denied", username = "denied")
        val deniedMember = member.copy(id = "denied-member", userId = denied.id, canUseCoupon = false)
        db.userDao().insertUser(denied)
        db.userDao().insertMembership(deniedMember)
        SessionManager.setSession(ActiveSession(business, branch, denied, deniedMember))
        assertTrue(repository.executeCouponSale(command().copy(actorUserId = denied.id)) is CommandResult.PermissionDenied)
        assertNull(db.operationDao().getOperationById(command().operationId.value))
    }

    @Test fun pendingPurchaseRecoversFromDurableIntentWithoutSession() = runBlocking {
        val c = command()
        db.operationDao().insertOperation(pending(c))
        SessionManager.clearSession()
        repository.recoverPendingCouponSales()
        repository.recoverPendingCouponSales()
        assertEquals(OperationStatus.SUCCEEDED, db.operationDao().getOperationById(c.operationId.value)!!.status)
        val wallet = db.ledgerDao().getCouponWallet(c.customerId, c.currencyCode)!!
        assertEquals(5000L, db.ledgerDao().getCouponBalance(wallet.id))
        assertEquals(1, db.ledgerDao().getCouponLedgerForWallet(wallet.id).size)
    }

    @Test fun malformedPendingIntentBlocksRecoveryRepeatedly() = runBlocking {
        val c = command()
        db.operationDao().insertOperation(pending(c).copy(intentPayload = "broken"))
        repeat(2) { assertTrue(runCatching { repository.recoverPendingCouponSales() }.isFailure) }
        assertEquals(OperationStatus.REQUIRES_RECONCILIATION, db.operationDao().getOperationById(c.operationId.value)!!.status)
        assertNull(db.transactionDao().getTransactionById(c.subjectId))
    }

    @Test fun existingPhoneAndExistingFundedWalletCannotCreateDuplicates() = runBlocking {
        val c = command()
        assertTrue(repository.executeCouponSale(c) is CommandResult.Success)
        val duplicatePhone = c.copy(operationId = OperationId("other-op"), transactionIdVal = TransactionId("other-tx"), customerId = "other-customer")
        assertTrue(repository.executeCouponSale(duplicatePhone) is CommandResult.Error)
        val secondPurchase = c.copy(operationId = OperationId("topup-op"), transactionIdVal = TransactionId("topup-tx"), createCustomer = false)
        assertTrue(repository.executeCouponSale(secondPurchase) is CommandResult.Error)
        assertEquals(1, db.customerDao().getCustomersForBusiness(c.businessId).size)
        assertEquals(5000L, db.ledgerDao().getCouponBalance(db.ledgerDao().getCouponWallet(c.customerId, c.currencyCode)!!.id))
    }

    @Test fun partialRedemptionAndRefundToCouponAreAtomicReplaySafeAndVisibleInReadModel() = runBlocking {
        val purchase = repository.executeCouponSale(command()) as CommandResult.Success
        val wallet = db.ledgerDao().getCouponWallet(command().customerId, "OMR")!!
        val purchaser = db.ledgerDao().getActiveAuthorizedUsers(wallet.id).single { it.isPurchaser }
        val purchaserName = db.ledgerDao().getAuthorizedUserEvents(purchaser.id).last().nameValue
        val redemption = CouponRedemptionCommand(
            OnlineSaleCommand(OperationId("redeem-op"), business.id, branch.id, user.id,
                TransactionId("redeem-tx"), DraftState(manualAmount = MoneyMinorUnits(7000)),
                "OMR", 3, "coupon-device", 1700000010000),
            command().customerId, wallet.id, purchaser.id, purchaserName, 3000, PaymentCategory.CASH
        )
        val redeemed = repository.executeCouponRedemption(redemption)
        assertTrue(redeemed.toString(), redeemed is CommandResult.Success)
        assertEquals(2000L, db.ledgerDao().getCouponBalance(wallet.id))
        val record = db.transactionDao().getFinancialRecordById("redeem-tx")!!
        val view = com.dailymoneyregister.core.model.financial.FinancialReadModelResolver.resolve(record)
        assertEquals(setOf(PaymentCategory.COUPON, PaymentCategory.CASH), view.fundingBreakdown.map { it.category }.toSet())
        assertEquals(7000L, view.settlementTotal.amount)

        val refund = RefundCommand(OperationId("refund-coupon-op"), business.id, branch.id, "redeem-tx",
            user.id, "coupon-device", 1700000020000, 1000, PaymentCategory.COUPON,
            PaymentChannel.COUPON_WALLET, "Return to wallet")
        val first = repository.executeRefund(refund) as CommandResult.Success
        val replay = repository.executeRefund(refund) as CommandResult.Success
        assertFalse(first.isIdempotentReplay)
        assertTrue(replay.isIdempotentReplay)
        assertEquals(wallet.id, first.data.couponWalletId)
        assertEquals(3000L, first.data.couponBalanceAfterMinor)
        assertEquals(3000L, db.ledgerDao().getCouponBalance(wallet.id))
        assertEquals(1, db.ledgerDao().getCouponEventsForOperation("refund-coupon-op").size)
    }

    @Test fun authorizedUserCreateEditDeactivateUsesAppendOnlyEvents() = runBlocking {
        repository.executeCouponSale(command())
        val wallet = db.ledgerDao().getCouponWallet(command().customerId, "OMR")!!
        fun userCommand(op: String, name: String, type: AuthorizedUserEventType) =
            CouponAuthorizedUserCommand(OperationId(op), business.id, branch.id, user.id,
                command().customerId, wallet.id, "authorized-extra", name, type, "OMR",
                "coupon-device", 1700000030000 + op.length)
        assertTrue(repository.executeCouponAuthorizedUser(userCommand("add-user", "Alex", AuthorizedUserEventType.ADDED)) is CommandResult.Success)
        assertTrue(repository.executeCouponAuthorizedUser(userCommand("edit-user", "Alex Updated", AuthorizedUserEventType.NAME_CORRECTED)) is CommandResult.Success)
        assertTrue(repository.executeCouponAuthorizedUser(userCommand("disable-user", "Alex Updated", AuthorizedUserEventType.DEACTIVATED)) is CommandResult.Success)
        val events = db.ledgerDao().getAuthorizedUserEvents("authorized-extra")
        assertEquals(listOf(AuthorizedUserEventType.ADDED, AuthorizedUserEventType.NAME_CORRECTED,
            AuthorizedUserEventType.DEACTIVATED), events.map { it.eventType })
        assertFalse(repository.getCouponWalletDetails(command().customerId, "OMR")!!.authorizedUsers
            .single { it.user.id == "authorized-extra" }.isActive)
    }
}
