package com.dailymoneyregister.core.cloud.sync

import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.CashShiftDao
import com.dailymoneyregister.core.database.dao.CloudStaffIdentityDao
import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.database.dao.LedgerDao
import com.dailymoneyregister.core.database.dao.ExpenseDao
import com.dailymoneyregister.core.database.dao.FinancialSyncOutboxDao
import com.dailymoneyregister.core.database.dao.OperationDao
import com.dailymoneyregister.core.database.dao.TransactionDao
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.CashShiftEntity
import com.dailymoneyregister.core.database.entity.CashShiftEventEntity
import com.dailymoneyregister.core.database.entity.CloudStaffIdentityEntity
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.database.entity.ExpenseEntity
import com.dailymoneyregister.core.database.entity.OperationEntity
import com.dailymoneyregister.core.database.entity.RefundEventEntity
import com.dailymoneyregister.core.database.entity.TransactionEntity
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.command.CommandFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import java.time.Instant

abstract class RemoteEnvelopeMaterializerTestSupport {
    protected val database = mockk<DmrDatabase>()
    protected val branchDao = mockk<BusinessBranchDao>()
    protected val cashShiftDao = mockk<CashShiftDao>()
    protected val identityDao = mockk<CloudStaffIdentityDao>()
    protected val customerDao = mockk<CustomerDao>()
    protected val ledgerDao = mockk<LedgerDao>()
    protected val customer = mockk<CustomerEntity>()
    protected val operationDao = mockk<OperationDao>()
    protected val expenseDao = mockk<ExpenseDao>()
    protected val outboxDao = mockk<FinancialSyncOutboxDao>()
    protected val transactionDao = mockk<TransactionDao>()
    protected val userDao = mockk<UserDao>()

    protected val businessId = "business-a"
    protected val branchId = "branch-a"
    protected val actorUserId = "cashier-user-a"
    protected val transactionId = "0199c102-1234-7abc-8def-0123456789ab"
    protected val operationId = "0199c101-1234-7abc-8def-0123456789ab"
    protected val fingerprint = "cash-sale-request-fingerprint"
    protected var storedOperation: OperationEntity? = null
    protected lateinit var cloudIdentity: CloudStaffIdentityEntity

    @Before
    fun setUp() {
        every { database.businessBranchDao() } returns branchDao
        every { database.cashShiftDao() } returns cashShiftDao
        every { database.cloudStaffIdentityDao() } returns identityDao
        every { database.customerDao() } returns customerDao
        every { database.ledgerDao() } returns ledgerDao
        every { customer.businessId } returns businessId
        every { customer.id } returns "credit-customer-id"
        every { database.operationDao() } returns operationDao
        every { database.expenseDao() } returns expenseDao
        every { database.financialSyncOutboxDao() } returns outboxDao
        every { database.transactionDao() } returns transactionDao
        every { database.userDao() } returns userDao
        val branch = BranchEntity(
            id = branchId,
            businessId = businessId,
            name = "Main",
            code = "MAIN",
            countryCode = "OM",
            currencyCode = "OMR",
            currencyDecimalPlaces = 3,
            currencySymbol = "OMR",
            timezoneId = "Asia/Muscat",
            taxRoundingMode = TaxRoundingMode.HALF_UP,
            defaultTaxRateBps = 0,
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery { branchDao.getBranchById(branchId) } returns branch
        coEvery { cashShiftDao.getEventByOperationId(any()) } returns null
        coEvery { cashShiftDao.getShiftById(any()) } returns null
        coEvery { cashShiftDao.getEventsForShift(any()) } returns emptyList()
        coEvery { cashShiftDao.getLatestShiftForUser(any(), any()) } returns null
        coEvery { cashShiftDao.insertShift(any()) } returns Unit
        coEvery { cashShiftDao.insertShiftEvent(any()) } returns Unit
        cloudIdentity = CloudStaffIdentityEntity(
                membershipId = "membership-a",
                localUserId = actorUserId,
                businessId = businessId,
                branchId = branchId,
                role = "CASHIER",
                permissionsJson = "{}",
                isActive = true,
                isRemoved = false,
                updatedAt = 1L
            )
        coEvery { identityDao.findIdentity(actorUserId, businessId, branchId) } coAnswers { cloudIdentity }
        coEvery { userDao.getUserByIdInBusiness(actorUserId, businessId) } returns null
        coEvery { customerDao.getCustomerById(any()) } returns customer
        coEvery { customerDao.getCustomerIdAliasByAliasId(any(), any()) } returns null
        coEvery { ledgerDao.getCreditRepaymentById(any()) } returns null
        coEvery { ledgerDao.getCreditRepaymentsForOperation(any()) } returns emptyList()
        coEvery { ledgerDao.getCreditRepaymentAllocations(any()) } returns emptyList()
        coEvery { ledgerDao.getCreditEventsForOperations(any()) } returns emptyList()
        coEvery { ledgerDao.insertCreditRepayment(any()) } returns Unit
        coEvery { ledgerDao.insertCreditRepaymentAllocations(any()) } returns Unit
        coEvery { ledgerDao.insertCreditEvent(any()) } returns Unit
        coEvery { ledgerDao.getCouponWalletById(any()) } returns null
        coEvery { ledgerDao.getCouponEventsForOperation(any()) } returns emptyList()
        coEvery { ledgerDao.insertCouponWallet(any()) } returns Unit
        coEvery { ledgerDao.insertCouponEvent(any()) } returns Unit
        coEvery { operationDao.getOperationById(any()) } coAnswers { storedOperation }
        coEvery { operationDao.insertOperationIfAbsent(any()) } coAnswers {
            if (storedOperation == null) {
                storedOperation = firstArg<OperationEntity>()
                1L
            } else {
                0L
            }
        }
        coEvery { operationDao.markSucceededIfPending(any(), any(), any(), any()) } coAnswers {
            val current = storedOperation
            val operationIdArg = firstArg<String>()
            val fingerprintArg = secondArg<String>()
            if (
                current != null &&
                current.id == operationIdArg &&
                current.status == OperationStatus.PENDING &&
                current.requestFingerprint == fingerprintArg
            ) {
                storedOperation = current.copy(
                    status = OperationStatus.SUCCEEDED,
                    resultReferenceType = thirdArg<String>(),
                    resultReferenceId = args[3] as String,
                    reason = null
                )
                1
            } else {
                0
            }
        }
        coEvery { operationDao.isOperationSucceeded(any()) } answers { storedOperation?.status == OperationStatus.SUCCEEDED }
        coEvery { transactionDao.getTransactionById(transactionId) } returns null
        coEvery { transactionDao.insertTransaction(any()) } returns Unit
        coEvery { transactionDao.getItemsForTransaction(transactionId) } returns emptyList()
        coEvery { transactionDao.getSettlementsForOperation(any()) } returns emptyList()
        coEvery { transactionDao.insertSettlements(any()) } returns Unit
        coEvery { transactionDao.getPaymentMethodChanges(transactionId) } returns emptyList()
        coEvery { transactionDao.insertPaymentMethodChange(any()) } returns Unit
        coEvery { transactionDao.getRefundEvents(any()) } returns emptyList()
        coEvery { transactionDao.getRefundEventByOperationId(any()) } returns null
        coEvery { transactionDao.insertRefund(any()) } returns Unit
        coEvery { transactionDao.getAmountCorrections(any()) } returns emptyList()
        coEvery { transactionDao.getAmountCorrectionsByOperationId(any()) } returns emptyList()
        coEvery { transactionDao.insertAmountCorrection(any()) } returns Unit
        coEvery { transactionDao.getVoidEvent(any()) } returns null
        coEvery { transactionDao.getVoidEventByOperationId(any()) } returns null
        coEvery { transactionDao.insertVoid(any()) } returns Unit
        coEvery { expenseDao.getExpenseById(any()) } returns null
        coEvery { expenseDao.insertExpense(any()) } returns Unit
        coEvery { outboxDao.findByOperationId(any()) } returns null
        coEvery { outboxDao.insertOutbox(any()) } returns 1L

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            // The generated static call receives the RoomDatabase receiver first
            // and the transaction lambda second.
            secondArg<suspend () -> Any>().invoke()
        }
    }


    protected fun creditRepaymentEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val repaymentId = "repayment-evidence-id"
        val repaymentOperationId = "credit-repayment-operation"
        val customerId = "credit-customer-id"
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_type", "RECORD_CREDIT_REPAYMENT")
            put("subject_type", SubjectType.REPAYMENT.name)
            put("subject_id", repaymentId)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "$repaymentOperationId:collection")
                put("operation_id", repaymentOperationId)
                put("subject_type", SubjectType.REPAYMENT.name)
                put("subject_id", repaymentId)
                put("entry_purpose", "CREDIT_REPAYMENT_COLLECTION")
                put("category", "CASH")
                put("channel", "CASH_TENDER")
                put("amount_signed_minor", 1_000L)
                put("currency_code", "OMR")
                put("confirmation_type", "CASHIER_CONFIRMED")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
            put("financial_evidence", JSONObject().apply {
                put("credit_repayment", JSONObject().apply {
                    put("id", repaymentId)
                    put("operation_id", repaymentOperationId)
                    put("customer_id", customerId)
                    put("amount_minor", 1_000L)
                    put("currency_code", "OMR")
                    put("payment_category", "CASH")
                    put("occurred_at", occurredAt)
                    put("actor_user_id", actorUserId)
                })
                put("credit_ledger_events", JSONArray().put(JSONObject().apply {
                    put("id", "$repaymentOperationId:credit-repayment")
                    put("operation_id", repaymentOperationId)
                    put("customer_id", customerId)
                    put("type", "REPAYMENT")
                    put("amount_signed_minor", -1_000L)
                    put("currency_code", "OMR")
                    put("repayment_id", repaymentId)
                    put("occurred_at", occurredAt)
                }))
                put("credit_repayment_allocations", JSONArray().put(JSONObject().apply {
                    put("id", java.util.UUID.nameUUIDFromBytes("credit-repayment:$repaymentOperationId:original-credit-transaction".toByteArray(Charsets.UTF_8)).toString())
                    put("repayment_id", repaymentId)
                    put("original_transaction_id", "original-credit-transaction")
                    put("allocated_amount_minor", 1_000L)
                    put("occurred_at", occurredAt)
                }))
            })
        }
        return envelopeFromPayload(base, "RECORD_CREDIT_REPAYMENT", payload, repaymentOperationId)
    }

    protected fun couponCreditRepaymentEnvelope(): RemoteEnvelopeV2 {
        val base = creditRepaymentEnvelope()
        val payload = JSONObject(base.canonicalText)
        val evidence = payload.getJSONObject("financial_evidence")
        val repaymentId = payload.optString("subject_id")
        val customerId = evidence.getJSONObject("credit_repayment").optString("customer_id")
        val occurredAt = payload.getLong("occurred_at")
        val walletId = "coupon-credit-repayment-wallet"
        payload.getJSONArray("settlements").getJSONObject(0).apply {
            put("category", "COUPON")
            put("channel", "COUPON_WALLET")
        }
        evidence.getJSONObject("credit_repayment").put("payment_category", "COUPON")
        evidence.put("coupon_wallets", JSONArray().put(JSONObject().apply {
            put("id", walletId)
            put("customer_id", customerId)
            put("currency_code", "OMR")
            put("is_active", true)
            put("created_at", occurredAt)
        }))
        evidence.put("coupon_ledger_events", JSONArray().put(JSONObject().apply {
            put("id", "${base.operationId}:coupon-credit-repayment")
            put("operation_id", base.operationId)
            put("wallet_id", walletId)
            put("type", "REDEMPTION")
            put("amount_signed_minor", -1_000L)
            put("currency_code", "OMR")
            put("authorized_user_id", "coupon-authorized-user")
            put("authorized_user_name_snapshot", "Family Member")
            put("occurred_at", occurredAt)
        }))
        return envelopeFromPayload(
            base, "RECORD_CREDIT_REPAYMENT", payload, "credit-repayment-operation"
        )
    }

    protected fun couponPurchaseEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val couponOperationId = "coupon-purchase-operation"
        val walletId = "coupon-purchase-wallet"
        val customerId = "coupon-purchase-customer"
        val payload = JSONObject(base.canonicalText).apply {
            put("operation_type", "RECORD_COUPON_PURCHASE")
            getJSONObject("transaction").apply {
                put("type", "COUPON_PURCHASE")
                put("customer_id", customerId)
            }
            getJSONArray("settlements").getJSONObject(0).apply {
                put("operation_id", couponOperationId)
                put("entry_purpose", "COUPON_PURCHASE_FUNDING")
            }
            put("financial_evidence", JSONObject().apply {
                put("coupon_wallets", JSONArray().put(JSONObject().apply {
                    put("id", walletId)
                    put("customer_id", customerId)
                    put("currency_code", "OMR")
                    put("is_active", true)
                    put("created_at", occurredAt)
                }))
                put("coupon_ledger_events", JSONArray().put(JSONObject().apply {
                    put("id", "coupon-purchase-ledger-event")
                    put("operation_id", couponOperationId)
                    put("wallet_id", walletId)
                    put("type", "PURCHASE")
                    put("amount_signed_minor", 1_000L)
                    put("currency_code", "OMR")
                    put("transaction_id", transactionId)
                    put("occurred_at", occurredAt)
                }))
            })
        }
        return envelopeFromPayload(base, "RECORD_COUPON_PURCHASE", payload, couponOperationId)
    }

    protected fun cashShiftEnvelope(
        operationType: String,
        eventShiftId: String = "inbound-shift-id",
        occurredAtOffset: Long = 0L
    ): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val shiftCreatedAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val occurredAt = shiftCreatedAt + occurredAtOffset
        val shiftId = "inbound-shift-id"
        val operation = when (operationType) { "OPEN_CASH_SHIFT" -> "open-shift-operation"; "COUNT_CASH_SHIFT" -> "count-shift-operation"; else -> "close-shift-operation" }
        val eventType = when (operationType) { "OPEN_CASH_SHIFT" -> "OPENED"; "COUNT_CASH_SHIFT" -> "COUNTED"; else -> "CLOSED" }
        val eventAmount = when (operationType) { "OPEN_CASH_SHIFT", "CLOSE_CASH_SHIFT" -> 0L; else -> 1_000L }
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("occurred_at", occurredAt)
            put("operation_id", operation)
            put("operation_type", operationType)
            put("subject_type", SubjectType.CASH_SHIFT.name)
            put("subject_id", shiftId)
            put("settlements", JSONArray())
            put("financial_evidence", JSONObject().apply {
                put("cash_shift", JSONObject().apply {
                    put("id", shiftId)
                    put("branch_id", branchId)
                    put("user_id", actorUserId)
                    put("created_at", shiftCreatedAt)
                })
                put("cash_shift_event", JSONObject().apply {
                    put("id", operation + ":" + eventType)
                    put("shift_id", eventShiftId)
                    put("operation_id", operation)
                    put("type", eventType)
                    put("amount_minor", eventAmount)
                    put("occurred_at", occurredAt)
                    put("actor_user_id", actorUserId)
                })
            })
        }
        return envelopeFromPayload(base, operationType, payload, operation)
    }

    protected fun expenseLifecycleEnvelope(
        operationType: String,
        eventType: String,
        signedSettlementMinor: Long,
        previousAmount: Long,
        newAmount: Long?
    ): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val expenseId = "lifecycle-expense-id"
        val lifecycleOperationId = if (operationType == "RECORD_EXPENSE_VOID") {
            "expense-void-operation"
        } else {
            "expense-correction-operation"
        }
        val purpose = if (operationType == "RECORD_EXPENSE_VOID") "VOID_REVERSAL" else "EXPENSE_CORRECTION"
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_id", lifecycleOperationId)
            put("operation_type", operationType)
            put("subject_type", SubjectType.EXPENSE.name)
            put("subject_id", expenseId)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "$lifecycleOperationId:expense-lifecycle-settlement")
                put("operation_id", lifecycleOperationId)
                put("subject_type", SubjectType.EXPENSE.name)
                put("subject_id", expenseId)
                put("entry_purpose", purpose)
                put("category", "CASH")
                put("channel", "CASH_TENDER")
                put("amount_signed_minor", signedSettlementMinor)
                put("currency_code", "OMR")
                put("confirmation_type", "CASHIER_CONFIRMED")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
            put("financial_evidence", JSONObject().apply {
                put("expense_snapshot", JSONObject().apply {
                    put("id", expenseId)
                    put("business_id", businessId)
                    put("branch_id", branchId)
                    put("currency_code", "OMR")
                    put("payment_category", "CASH")
                })
                put("expense_events", JSONArray().put(JSONObject().apply {
                    put("id", "$lifecycleOperationId:expense-event")
                    put("expense_id", expenseId)
                    put("operation_id", lifecycleOperationId)
                    put("event_type", eventType)
                    put("actor_user_id", actorUserId)
                    put("previous_amount_minor", previousAmount)
                    put("new_amount_minor", newAmount ?: JSONObject.NULL)
                    put("previous_payment_category", "CASH")
                    put("new_payment_category", if (eventType == "CORRECTED") "CASH" else "")
                    put("previous_category", "OTHER")
                    put("new_category", if (eventType == "CORRECTED") "OTHER" else "")
                    put("previous_payee_id", "")
                    put("new_payee_id", "")
                    put("reason", "Inbound lifecycle test")
                    put("occurred_at", occurredAt)
                }))
            })
        }
        return envelopeFromPayload(base, operationType, payload, lifecycleOperationId)
    }

    protected fun transactionLifecycleEnvelope(
        operationType: String,
        lifecycleOperationId: String,
        signedSettlementMinor: Long,
        entryPurpose: String,
        financialEvidence: JSONObject
    ): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val settlement = JSONObject().apply {
            put("id", "$lifecycleOperationId-settlement")
            put("operation_id", lifecycleOperationId)
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("entry_purpose", entryPurpose)
            put("category", "CASH")
            put("channel", "CASH_TENDER")
            put("amount_signed_minor", signedSettlementMinor)
            put("currency_code", "OMR")
            put("confirmation_type", ConfirmationType.CASHIER_CONFIRMED.name)
            put("actor_user_id", actorUserId)
            put("occurred_at", occurredAt)
        }
        val payload = JSONObject(base.canonicalText).apply {
            put("operation_id", lifecycleOperationId)
            put("operation_type", operationType)
            put("occurred_at", occurredAt)
            put("settlements", JSONArray().put(settlement))
            put("financial_evidence", financialEvidence)
        }
        return envelopeFromPayload(base, operationType, payload, lifecycleOperationId)
    }

    protected fun expenseEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val expenseId = "0199c104-1234-7abc-8def-0123456789ab"
        val payload = JSONObject(base.canonicalText).apply {
            remove("transaction")
            remove("transaction_id")
            remove("items")
            put("operation_type", "RECORD_EXPENSE")
            put("subject_type", SubjectType.EXPENSE.name)
            put("subject_id", expenseId)
            put("settlements", JSONArray().put(JSONObject().apply {
                put("id", "$operationId:expense-outflow")
                put("operation_id", operationId)
                put("subject_type", SubjectType.EXPENSE.name)
                put("subject_id", expenseId)
                put("entry_purpose", "EXPENSE_OUTFLOW")
                put("category", "CASH")
                put("channel", "CASH_TENDER")
                put("amount_signed_minor", -1_000L)
                put("currency_code", "OMR")
                put("confirmation_type", "CASHIER_CONFIRMED")
                put("actor_user_id", actorUserId)
                put("occurred_at", occurredAt)
            }))
            put("financial_evidence", JSONObject())
            put("expense", JSONObject().apply {
                put("id", expenseId)
                put("operation_id", operationId)
                put("business_id", businessId)
                put("branch_id", branchId)
                put("actor_user_id", actorUserId)
                put("payee_name_snapshot", "Test payee")
                put("category", "OTHER")
                put("amount_minor", 1_000L)
                put("payment_category", "CASH")
                put("currency_code", "OMR")
                put("occurred_at", occurredAt)
                put("created_at", occurredAt)
            })
        }
        return envelopeFromPayload(base, "RECORD_EXPENSE", payload)
    }

    protected fun paymentMethodChangeEnvelope(): RemoteEnvelopeV2 {
        val base = cashSaleEnvelope()
        val occurredAt = JSONObject(base.canonicalText).getLong("occurred_at")
        val paymentChangeOperationId = "payment-change-operation"
        fun settlement(
            id: String,
            category: String,
            channel: String,
            amount: Long,
            confirmationType: String
        ) = JSONObject().apply {
            put("id", id)
            put("operation_id", paymentChangeOperationId)
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("entry_purpose", "PAYMENT_METHOD_RECLASSIFICATION")
            put("category", category)
            put("channel", channel)
            put("amount_signed_minor", amount)
            put("currency_code", "OMR")
            put("confirmation_type", confirmationType)
            put("actor_user_id", actorUserId)
            put("occurred_at", occurredAt)
        }
        val payload = JSONObject(base.canonicalText).apply {
            put("operation_id", paymentChangeOperationId)
            put("operation_type", "RECORD_PAYMENT_METHOD_CHANGE")
            put("settlements", JSONArray()
                .put(settlement("payment-change-cash", "CASH", "CASH_TENDER", -1_000L, "CASHIER_CONFIRMED"))
                .put(settlement("payment-change-online", "ONLINE", "MANUAL_QR", 1_000L, "PROVIDER_APPROVED")))
            put("financial_evidence", JSONObject().put(
                "payment_method_change_events",
                JSONArray().put(JSONObject().apply {
                    put("id", "payment-change-event")
                    put("transaction_id", transactionId)
                    put("operation_id", paymentChangeOperationId)
                    put("previous_method", "CASH")
                    put("previous_channel", "CASH_TENDER")
                    put("new_method", "ONLINE")
                    put("new_channel", "MANUAL_QR")
                    put("reason", "Corrected tender")
                    put("actor_user_id", actorUserId)
                    put("occurred_at", occurredAt)
                })
            ))
        }
        return envelopeFromPayload(
            base,
            "RECORD_PAYMENT_METHOD_CHANGE",
            payload,
            operationId = paymentChangeOperationId
        )
    }

    protected fun envelopeFromPayload(
        base: RemoteEnvelopeV2,
        operationType: String,
        payload: JSONObject,
        operationId: String = base.operationId
    ): RemoteEnvelopeV2 {
        payload.put("operation_id", operationId)
        payload.put("operation_type", operationType)
        val canonicalText = payload.toString()
        val occurredAtMillis = payload.getLong("occurred_at")
        val occurredAtIso = Instant.ofEpochMilli(occurredAtMillis).toString()
        val receivedAtIso = Instant.ofEpochMilli(Math.addExact(occurredAtMillis, 1L)).toString()
        return base.copy(
            operationId = operationId,
            operationType = operationType,
            occurredAtIso = occurredAtIso,
            receivedAtIso = receivedAtIso,
            canonicalText = canonicalText,
            payloadJson = payload,
            canonicalSha256 = CommandFingerprint.sha256(canonicalText)
        )
    }

    protected fun cashSaleEnvelope(
        actorId: String = actorUserId,
        serverActorMembershipId: String? = null,
        serverActorRole: String? = null
    ): RemoteEnvelopeV2 {
        val occurredAtIso = "2026-10-09T10:00:00Z"
        val occurredAtMillis = Instant.parse(occurredAtIso).toEpochMilli()
        val settlement = JSONObject().apply {
            put("id", "0199c103-1234-7abc-8def-0123456789ab")
            put("operation_id", operationId)
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("entry_purpose", "SALE_FUNDING")
            put("category", "CASH")
            put("channel", "CASH_TENDER")
            put("amount_signed_minor", 1000L)
            put("currency_code", "OMR")
            put("confirmation_type", ConfirmationType.CASHIER_CONFIRMED.name)
            put("actor_user_id", actorId)
        }
        val transaction = JSONObject().apply {
            put("id", transactionId)
            put("branch_id", branchId)
            put("type", "SALE")
            put("subtotal_minor", 1000L)
            put("tax_rate_bps", 0)
            put("taxable_base_minor", 1000L)
            put("tax_amount_minor", 0L)
            put("grand_total_minor", 1000L)
            put("currency_code", "OMR")
            put("currency_decimal_places", 3)
            put("created_by_user_id", actorId)
            put("device_id", "cashier-device")
            put("created_at", occurredAtMillis)
            put("display_ref", "SALE-001")
        }
        val payload = JSONObject().apply {
            put("operation_id", operationId)
            put("business_id", businessId)
            put("branch_id", branchId)
            put("operation_type", "RECORD_SALE")
            put("actor_user_id", actorId)
            put("device_id", "cashier-device")
            put("occurred_at", occurredAtMillis)
            put("command_schema_version", 1)
            put("request_fingerprint", fingerprint)
            put("intent_payload", "{}")
            put("subject_type", SubjectType.TRANSACTION.name)
            put("subject_id", transactionId)
            put("transaction_id", transactionId)
            put("transaction", transaction)
            put("settlements", JSONArray().put(settlement))
        }
        val canonicalText = payload.toString()
        return RemoteEnvelopeV2(
            operationId = operationId,
            businessId = businessId,
            branchId = branchId,
            operationType = "RECORD_SALE",
            occurredAtIso = occurredAtIso,
            receivedAtIso = "2026-10-09T10:00:01Z",
            canonicalText = canonicalText,
            payloadJson = payload,
            canonicalSha256 = CommandFingerprint.sha256(canonicalText),
            actorMembershipId = serverActorMembershipId,
            actorLocalUserId = if (serverActorMembershipId == null) null else actorId,
            actorRole = if (serverActorMembershipId == null) null else serverActorRole
        )
    }
}
