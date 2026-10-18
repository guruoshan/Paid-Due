package com.dailymoneyregister.feature.customers.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.dailymoneyregister.core.database.DmrDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import com.dailymoneyregister.core.security.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Singleton
class CustomerExcelExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DmrDatabase
) {
    suspend fun export(): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val businessId = SessionManager.currentSession.value?.business?.id
                ?: error("No active business session")
            val db = database.openHelper.readableDatabase
            data class Customer(val id: String, val name: String, val phone: String)
            data class Branch(val name: String, val currencyCode: String, val decimals: Int)
            data class Activity(
                val customerId: String, val date: Long, val branch: String, val name: String,
                val phone: String, val type: String, val ref: String, val currency: String,
                val decimals: Int, val amountMinor: Long?, val creditMinor: Long = 0L,
                val payment: String = "", val status: String = "Recorded",
                val relatedTransactionId: String? = null
            )
            data class Ledger(
                var obligations: Long = 0L, var repaymentsSigned: Long = 0L,
                var adjustments: Long = 0L, var balance: Long = 0L
            )
            data class Key(val customerId: String, val currency: String)

            // Customer ID is the financial identity. Never merge customer balances by phone digits.
            val customers = linkedMapOf<String, Customer>()
            db.query(
                "SELECT id,name,canonical_phone FROM customers WHERE business_id=? ORDER BY name,id",
                arrayOf(businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customer = Customer(c.getString(0), c.getString(1), c.getString(2).orEmpty())
                    customers[customer.id] = customer
                }
            }
            val branches = mutableMapOf<String, Branch>()
            db.query(
                "SELECT id,name,currency_code,currency_decimal_places FROM branches WHERE business_id=?",
                arrayOf(businessId)
            ).use { c ->
                while (c.moveToNext()) branches[c.getString(0)] = Branch(c.getString(1), c.getString(2), c.getInt(3))
            }

            val activities = mutableListOf<Activity>()
            val decimalsByCurrency = mutableMapOf<String, Int>()
            fun registerCurrency(codeValue: String, decimals: Int) {
                val code = codeValue.trim().uppercase(Locale.ROOT)
                require(code.isNotBlank()) { "A financial record has no currency code" }
                require(decimals in 0..4) { "Unsupported decimal precision for " + code }
                val prior = decimalsByCurrency.putIfAbsent(code, decimals)
                require(prior == null || prior == decimals) {
                    "Conflicting decimal precision for " + code + "; refusing to publish mixed-precision totals"
                }
            }
            fun correctedAmount(transactionId: String, original: Long): Long =
                db.query(
                    SimpleSQLiteQuery(
                        "SELECT corrected_amount_minor FROM amount_correction_events WHERE transaction_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1",
                        arrayOf<Any?>(transactionId)
                    )
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else original }
            fun voided(transactionId: String): Boolean =
                db.query(SimpleSQLiteQuery(
                    "SELECT 1 FROM void_events WHERE transaction_id=? LIMIT 1",
                    arrayOf<Any?>(transactionId)
                )).use { it.moveToFirst() }
            val voidedTransactionIds = mutableSetOf<String>()

            db.query(
                "SELECT t.branch_id,t.type,t.customer_id,t.grand_total_minor,t.currency_decimal_places,t.created_at," +
                    "t.display_ref,t.currency_code,t.id FROM transactions t " +
                    "JOIN branches b ON b.id=t.branch_id AND b.business_id=? " +
                    "JOIN customers c ON c.id=t.customer_id AND c.business_id=? ORDER BY t.created_at,t.id",
                arrayOf(businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customer = customers[c.getString(2)]
                        ?: error("Transaction has no matching business customer: " + c.getString(2))
                    val transactionId = c.getString(8)
                    val currency = c.getString(7).uppercase(Locale.ROOT)
                    val decimals = c.getInt(4)
                    registerCurrency(currency, decimals)
                    val isVoid = voided(transactionId)
                    if (isVoid) voidedTransactionIds.add(transactionId)
                    val creditMinor = if (c.getString(1) == "SALE") {
                        db.query(SimpleSQLiteQuery(
                            "SELECT COALESCE(SUM(amount_signed_minor),0) FROM settlement_entries " +
                                "WHERE transaction_id=? AND currency_code=? AND category='CREDIT' " +
                                "AND entry_purpose IN ('SALE_FUNDING','PAYMENT_METHOD_RECLASSIFICATION')",
                            arrayOf<Any?>(transactionId, currency)
                        )).use { q -> if (q.moveToFirst()) q.getLong(0).coerceAtLeast(0L) else 0L }
                    } else 0L
                    activities.add(
                        Activity(
                            customer.id, c.getLong(5), branches[c.getString(0)]?.name.orEmpty(),
                            customer.name, customer.phone, c.getString(1), c.getString(6).orEmpty(),
                            currency, decimals, correctedAmount(transactionId, c.getLong(3)), creditMinor,
                            paymentMethodForTransaction(db, transactionId, c.getString(1)),
                            if (isVoid) "VOIDED — excluded from net sales" else "Recorded",
                            relatedTransactionId = transactionId
                        )
                    )
                }
            }

            // Lifecycle rows are first-class audit history. Do not hide corrections, voids,
            // or payment-method reclassifications behind the current transaction snapshot.
            db.query(
                "SELECT a.transaction_id,a.previous_amount_minor,a.corrected_amount_minor,a.occurred_at,a.id," +
                    "b.name,t.currency_code,t.currency_decimal_places,t.display_ref,c.id,c.name,c.canonical_phone " +
                    "FROM amount_correction_events a JOIN transactions t ON t.id=a.transaction_id " +
                    "JOIN operations o ON o.id=a.operation_id AND o.business_id=? " +
                    "JOIN branches b ON b.id=t.branch_id AND b.business_id=? " +
                    "JOIN customers c ON c.id=t.customer_id AND c.business_id=? ORDER BY a.occurred_at,a.id",
                arrayOf(businessId, businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val transactionId = c.getString(0)
                    val previous = c.getLong(1)
                    val corrected = c.getLong(2)
                    val currency = c.getString(6).uppercase(Locale.ROOT)
                    val decimals = c.getInt(7)
                    registerCurrency(currency, decimals)
                    activities.add(
                        Activity(
                            c.getString(9), c.getLong(3), c.getString(5), c.getString(10), c.getString(11),
                            "AMOUNT_CORRECTION", c.getString(8) + " / correction " + c.getString(4),
                            currency, decimals, Math.subtractExact(corrected, previous),
                            status = "Correction event — previous " + money(previous, decimals) +
                                "; corrected " + money(corrected, decimals),
                            relatedTransactionId = transactionId
                        )
                    )
                }
            }

            db.query(
                "SELECT v.transaction_id,v.occurred_at,v.id,v.reason,b.name,t.currency_code," +
                    "t.currency_decimal_places,t.display_ref,c.id,c.name,c.canonical_phone " +
                    "FROM void_events v JOIN operations o ON o.id=v.operation_id AND o.business_id=? " +
                    "JOIN transactions t ON t.id=v.transaction_id " +
                    "JOIN branches b ON b.id=t.branch_id AND b.business_id=? " +
                    "JOIN customers c ON c.id=t.customer_id AND c.business_id=? ORDER BY v.occurred_at,v.id",
                arrayOf(businessId, businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val transactionId = c.getString(0)
                    val currency = c.getString(5).uppercase(Locale.ROOT)
                    val decimals = c.getInt(6)
                    registerCurrency(currency, decimals)
                    activities.add(
                        Activity(
                            c.getString(8), c.getLong(1), c.getString(4), c.getString(9), c.getString(10),
                            "VOID", c.getString(7), currency, decimals, null,
                            status = "Void event — " + c.getString(3).orEmpty().ifBlank { "reason not recorded" } +
                                "; original transaction excluded from valid sales",
                            relatedTransactionId = transactionId
                        )
                    )
                }
            }

            db.query(
                "SELECT p.transaction_id,p.occurred_at,p.id,p.previous_method,p.previous_channel," +
                    "p.new_method,p.new_channel,p.reason,b.name,t.currency_code,t.currency_decimal_places," +
                    "t.display_ref,c.id,c.name,c.canonical_phone " +
                    "FROM payment_method_change_events p JOIN operations o ON o.id=p.operation_id AND o.business_id=? " +
                    "JOIN transactions t ON t.id=p.transaction_id " +
                    "JOIN branches b ON b.id=t.branch_id AND b.business_id=? " +
                    "JOIN customers c ON c.id=t.customer_id AND c.business_id=? ORDER BY p.occurred_at,p.id",
                arrayOf(businessId, businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val transactionId = c.getString(0)
                    val currency = c.getString(9).uppercase(Locale.ROOT)
                    val decimals = c.getInt(10)
                    registerCurrency(currency, decimals)
                    val previous = paymentMethodLabel(c.getString(3), c.getString(4))
                    val current = paymentMethodLabel(c.getString(5), c.getString(6))
                    activities.add(
                        Activity(
                            c.getString(12), c.getLong(1), c.getString(8), c.getString(13), c.getString(14),
                            "PAYMENT_METHOD_CHANGE", c.getString(11), currency, decimals, null,
                            payment = previous + " → " + current,
                            status = c.getString(7).orEmpty().ifBlank { "Payment method changed" },
                            relatedTransactionId = transactionId
                        )
                    )
                }
            }

            // Repayments are balance movements, not additional sales. Keep the original customer ID.
            db.query(
                "SELECT r.customer_id,r.amount_minor,r.currency_code,r.occurred_at,r.id,r.payment_category,o.branch_id " +
                    "FROM credit_repayments r JOIN customers c ON c.id=r.customer_id AND c.business_id=? " +
                    "JOIN operations o ON o.id=r.operation_id AND o.business_id=? ORDER BY r.occurred_at,r.id",
                arrayOf(businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customer = customers[c.getString(0)]
                        ?: error("Repayment has no matching business customer: " + c.getString(0))
                    val currency = c.getString(2).uppercase(Locale.ROOT)
                    val branch = branches[c.getString(6)]
                    val decimals = decimalsByCurrency[currency] ?: if (branch?.currencyCode.equals(currency, true) && branch != null) {
                        branch.decimals
                    } else {
                        com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(currency).decimalPlaces
                    }
                    registerCurrency(currency, decimals)
                    activities.add(
                        Activity(
                            customer.id, c.getLong(3), branch?.name.orEmpty(), customer.name, customer.phone,
                            "CREDIT_REPAYMENT", c.getString(4), currency, decimals, c.getLong(1),
                            payment = paymentCategoryLabel(c.getString(5))
                        )
                    )
                }
            }

            // Refunds retain the original transaction currency and are negative in transaction history.
            db.query(
                "SELECT t.customer_id,t.branch_id,t.currency_code,t.currency_decimal_places,r.refund_amount_minor," +
                    "r.occurred_at,r.id,t.id,t.type FROM refund_events r JOIN transactions t ON t.id=r.transaction_id " +
                    "JOIN branches b ON b.id=t.branch_id AND b.business_id=? " +
                    "JOIN customers c ON c.id=t.customer_id AND c.business_id=? ORDER BY r.occurred_at,r.id",
                arrayOf(businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customer = customers[c.getString(0)]
                        ?: error("Refund has no matching business customer: " + c.getString(0))
                    val currency = c.getString(2).uppercase(Locale.ROOT)
                    val decimals = c.getInt(3)
                    registerCurrency(currency, decimals)
                    val refundTransactionId = c.getString(7)
                    val originalSaleWasVoided = refundTransactionId in voidedTransactionIds
                    activities.add(
                        Activity(
                            customer.id, c.getLong(5), branches[c.getString(1)]?.name.orEmpty(),
                            customer.name, customer.phone,
                            if (c.getString(8) == "COUPON_PURCHASE") "COUPON_PURCHASE_REFUND" else "REFUND",
                            c.getString(6), currency, decimals, -kotlin.math.abs(c.getLong(4)),
                            payment = paymentMethodForTransaction(db, refundTransactionId, c.getString(8)),
                            status = if (originalSaleWasVoided)
                                "Refund history — original sale also voided; not deducted twice"
                            else "Recorded",
                            relatedTransactionId = refundTransactionId
                        )
                    )
                }
            }

            // Documents are retained as history rows but never added to sales/credit totals.
            db.query(
                "SELECT d.customer_id,d.created_at,d.display_number,d.document_type,o.branch_id " +
                    "FROM documents d JOIN operations o ON o.id=d.operation_id AND o.business_id=? " +
                    "JOIN customers c ON c.id=d.customer_id AND c.business_id=? ORDER BY d.created_at,d.id",
                arrayOf(businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customer = customers[c.getString(0)]
                        ?: error("Document has no matching business customer: " + c.getString(0))
                    activities.add(
                        Activity(
                            customer.id, c.getLong(1), branches[c.getString(4)]?.name.orEmpty(),
                            customer.name, customer.phone, c.getString(3), c.getString(2), "", 0, null,
                            status = "Document only — excluded from financial totals"
                        )
                    )
                }
            }

            // Outstanding credit comes from the signed credit ledger, not a reconstructed sales-minus-repayments guess.
            val ledgerByKey = mutableMapOf<Key, Ledger>()
            db.query(
                "SELECT e.customer_id,e.currency_code,e.type,e.amount_signed_minor,o.branch_id,b.currency_code,b.currency_decimal_places," +
                    "e.occurred_at,e.id,e.transaction_id " +
                    "FROM credit_ledger_events e JOIN operations o ON o.id=e.operation_id AND o.business_id=? " +
                    "LEFT JOIN branches b ON b.id=o.branch_id ORDER BY e.occurred_at,e.id",
                arrayOf(businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customerId = c.getString(0)
                    require(customers.containsKey(customerId)) { "Credit ledger has no matching business customer: " + customerId }
                    val currency = c.getString(1).uppercase(Locale.ROOT)
                    val branchCurrency = c.getString(5)
                    val decimals = decimalsByCurrency[currency] ?: if (branchCurrency.equals(currency, true) && !c.isNull(6)) {
                        c.getInt(6)
                    } else {
                        com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(currency).decimalPlaces
                    }
                    registerCurrency(currency, decimals)
                    val ledger = ledgerByKey.getOrPut(Key(customerId, currency)) { Ledger() }
                    val amount = c.getLong(3)
                    when (c.getString(2)) {
                        "OBLIGATION" -> {
                            require(amount > 0L) { "Credit obligation must be positive for event " + c.getString(8) }
                            ledger.obligations = Math.addExact(ledger.obligations, amount)
                        }
                        "REPAYMENT" -> {
                            require(amount < 0L) { "Credit repayment ledger movement must be negative for event " + c.getString(8) }
                            ledger.repaymentsSigned = Math.addExact(ledger.repaymentsSigned, amount)
                        }
                        "ADJUSTMENT" -> {
                            ledger.adjustments = Math.addExact(ledger.adjustments, amount)
                            val customer = customers[customerId] ?: error("Missing customer for credit adjustment")
                            val branch = branches[c.getString(4)]
                            val occurredAt = c.getLong(7)
                            val eventId = c.getString(8)
                            val transactionId = c.getString(9)
                            activities.add(
                                Activity(
                                    customer.id, occurredAt, branch?.name.orEmpty(), customer.name, customer.phone,
                                    "CREDIT_ADJUSTMENT", eventId, currency, decimals, amount,
                                    status = "Credit ledger adjustment — excluded from sales totals",
                                    relatedTransactionId = transactionId
                                )
                            )
                        }
                        else -> error("Unknown credit ledger type: " + c.getString(2))
                    }
                    ledger.balance = Math.addExact(ledger.balance, amount)
                }
            }

            // Coupon value is a separate stored-value ledger. Report purchases, bonuses,
            // redemptions and refund credits separately; never add these again to sales revenue.
            data class CouponEvent(
                val customerId: String, val currency: String, val decimals: Int,
                val type: String, val amountMinor: Long, val occurredAt: Long, val id: String,
                val transactionId: String?, val branch: String
            )
            val couponEvents = mutableListOf<CouponEvent>()
            db.query(
                "SELECT w.customer_id,w.currency_code,e.currency_code,e.type,e.amount_signed_minor,e.occurred_at," +
                    "e.id,e.transaction_id,o.branch_id,b.currency_code,b.currency_decimal_places " +
                    "FROM coupon_ledger_events e JOIN coupon_wallets w ON w.id=e.wallet_id " +
                    "JOIN customers c ON c.id=w.customer_id AND c.business_id=? " +
                    "JOIN operations o ON o.id=e.operation_id AND o.business_id=? " +
                    "LEFT JOIN branches b ON b.id=o.branch_id ORDER BY e.occurred_at,e.id",
                arrayOf(businessId, businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customerId = c.getString(0)
                    require(customers.containsKey(customerId)) {
                        "Coupon ledger has no matching business customer: " + customerId
                    }
                    val walletCurrency = c.getString(1).uppercase(Locale.ROOT)
                    val eventCurrency = c.getString(2).uppercase(Locale.ROOT)
                    require(walletCurrency == eventCurrency) {
                        "Coupon wallet currency mismatch for event " + c.getString(6) +
                            "; export stopped rather than mixing currencies"
                    }
                    val branchCurrency = c.getString(9)
                    val decimals = decimalsByCurrency[eventCurrency] ?: if (
                        branchCurrency.equals(eventCurrency, true) && !c.isNull(10)
                    ) {
                        c.getInt(10)
                    } else {
                        com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(eventCurrency).decimalPlaces
                    }
                    registerCurrency(eventCurrency, decimals)
                    val eventAmount = c.getLong(4)
                    when (c.getString(3)) {
                        "PURCHASE", "PROMOTION_BONUS", "REFUND_CREDIT" ->
                            require(eventAmount > 0L) { "Coupon credit movement must be positive for event " + c.getString(6) }
                        "REDEMPTION" ->
                            require(eventAmount < 0L) { "Coupon redemption movement must be negative for event " + c.getString(6) }
                        else -> error("Unknown coupon ledger type: " + c.getString(3))
                    }
                    couponEvents.add(
                        CouponEvent(
                            customerId, eventCurrency, decimals, c.getString(3), eventAmount,
                            c.getLong(5), c.getString(6), c.getString(7),
                            branches[c.getString(8)]?.name.orEmpty()
                        )
                    )
                }
            }

            couponEvents.forEach { event ->
                val customer = customers[event.customerId]
                    ?: error("Coupon ledger event has no matching business customer: " + event.customerId)
                activities.add(
                    Activity(
                        customer.id, event.occurredAt, event.branch, customer.name, customer.phone,
                        "COUPON_LEDGER_" + event.type, event.id, event.currency, event.decimals,
                        event.amountMinor, status = "Coupon ledger evidence — excluded from sales totals",
                        relatedTransactionId = event.transactionId
                    )
                )
            }

            val invoiceCounts = mutableMapOf<String, Int>()
            db.query(
                "SELECT d.customer_id,COUNT(*) FROM documents d " +
                    "JOIN operations o ON o.id=d.operation_id AND o.business_id=? " +
                    "JOIN customers c ON c.id=d.customer_id AND c.business_id=? " +
                    "WHERE d.document_type='INVOICE' AND d.customer_id IS NOT NULL GROUP BY d.customer_id",
                arrayOf(businessId, businessId)
            ).use { c -> while (c.moveToNext()) invoiceCounts[c.getString(0)] = c.getInt(1) }

            val byCustomer = activities.groupBy { it.customerId }
            val directory = customers.values.map { customer ->
                val rows = byCustomer[customer.id].orEmpty()
                listOf(
                    customer.id, customer.name, customer.phone, fmt(rows.minOfOrNull { it.date }),
                    fmt(rows.maxOfOrNull { it.date }), rows.size, invoiceCounts[customer.id] ?: 0,
                    rows.count { it.type == "SALE" && it.status == "Recorded" },
                    rows.count { it.type == "REFUND" || it.type == "COUPON_PURCHASE_REFUND" },
                    if (rows.isEmpty()) "No recorded activity" else "Active"
                )
            }

            val keys = linkedSetOf<Key>()
            activities.filter { it.currency.isNotBlank() }.forEach { keys.add(Key(it.customerId, it.currency)) }
            keys.addAll(ledgerByKey.keys)

            val summary = keys.map { key ->
                val customer = customers[key.customerId] ?: error("Missing customer in financial summary")
                val rows = activities.filter { it.customerId == key.customerId && it.currency == key.currency }
                val sales = rows.filter { it.type == "SALE" && it.status == "Recorded" }
                val couponPurchases = rows.filter { it.type == "COUPON_PURCHASE" && it.status == "Recorded" }
                val repayments = rows.filter { it.type == "CREDIT_REPAYMENT" }
                val refunds = rows.filter { it.type == "REFUND" }
                val couponRefunds = rows.filter { it.type == "COUPON_PURCHASE_REFUND" }
                val decimals = decimalsByCurrency[key.currency]
                    ?: com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(key.currency).decimalPlaces
                val salesMinor = CustomerFinancialReconciliationMath.sumMinor(sales.map { it.amountMinor ?: 0L })
                val creditFundingMinor = CustomerFinancialReconciliationMath.sumMinor(sales.map { it.creditMinor })
                val repaidMinor = CustomerFinancialReconciliationMath.sumMinor(repayments.map { it.amountMinor ?: 0L })
                val refundMinor = CustomerFinancialReconciliationMath.sumMinor(refunds.map { CustomerFinancialReconciliationMath.absoluteMinor(it.amountMinor ?: 0L) })
                val couponRefundMinor = CustomerFinancialReconciliationMath.sumMinor(couponRefunds.map { CustomerFinancialReconciliationMath.absoluteMinor(it.amountMinor ?: 0L) })
                val couponMinor = CustomerFinancialReconciliationMath.sumMinor(couponPurchases.map { it.amountMinor ?: 0L })
                val ledger = ledgerByKey[key] ?: Ledger()
                val allSaleCreditFundingMinor = CustomerFinancialReconciliationMath.sumMinor(rows.filter { it.type == "SALE" }.map { it.creditMinor })
                val variance = Math.subtractExact(ledger.obligations, allSaleCreditFundingMinor)
                listOf(
                    customer.id, customer.name, customer.phone, key.currency, sales.size,
                    money(salesMinor, decimals), money(creditFundingMinor, decimals),
                    money(repaidMinor, decimals), money(refundMinor, decimals), money(couponRefundMinor, decimals), money(couponMinor, decimals),
                    money(CustomerFinancialReconciliationMath.netSalesAfterRefunds(
                        salesMinor,
                        refunds.map { (it.relatedTransactionId ?: error("Refund history is missing its original transaction ID")) to (it.amountMinor ?: 0L) },
                        voidedTransactionIds
                    ), decimals), money(ledger.obligations, decimals),
                    money(Math.negateExact(ledger.repaymentsSigned), decimals), money(ledger.adjustments, decimals),
                    money(ledger.balance, decimals), money(variance, decimals),
                    if (variance == 0L) "OK" else "REVIEW — ledger obligations differ from sale credit funding"
                )
            }

            val couponKeys = linkedSetOf<Key>()
            couponEvents.forEach { couponKeys.add(Key(it.customerId, it.currency)) }
            activities.filter { it.type == "COUPON_PURCHASE" }.forEach {
                couponKeys.add(Key(it.customerId, it.currency))
            }
            val couponReconciliation = couponKeys
                .sortedWith(compareBy<Key> { it.customerId }.thenBy { it.currency })
                .map { key ->
                    val customer = customers[key.customerId] ?: error("Missing customer in coupon reconciliation")
                    val events = couponEvents.filter { it.customerId == key.customerId && it.currency == key.currency }
                    val transactionRows = activities.filter {
                        it.customerId == key.customerId && it.currency == key.currency &&
                            it.type == "COUPON_PURCHASE" && it.status == "Recorded"
                    }
                    val decimals = decimalsByCurrency[key.currency]
                        ?: com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(key.currency).decimalPlaces
                    val transactionPurchase = CustomerFinancialReconciliationMath.sumMinor(transactionRows.map { it.amountMinor ?: 0L })
                    val ledgerPurchase = CustomerFinancialReconciliationMath.sumMinor(events.filter { it.type == "PURCHASE" }.map { it.amountMinor })
                    val bonuses = CustomerFinancialReconciliationMath.sumMinor(events.filter { it.type == "PROMOTION_BONUS" }.map { it.amountMinor })
                    val redemptions = CustomerFinancialReconciliationMath.sumMinor(events.filter { it.type == "REDEMPTION" }.map { CustomerFinancialReconciliationMath.absoluteMinor(it.amountMinor) })
                    val refundCredits = CustomerFinancialReconciliationMath.sumMinor(events.filter { it.type == "REFUND_CREDIT" }.map { it.amountMinor })
                    val walletBalance = CustomerFinancialReconciliationMath.sumMinor(events.map { it.amountMinor })
                    val purchaseVariance = Math.subtractExact(ledgerPurchase, transactionPurchase)
                    listOf(
                        customer.id, customer.name, key.currency, events.size,
                        money(transactionPurchase, decimals), money(ledgerPurchase, decimals),
                        money(purchaseVariance, decimals), money(bonuses, decimals),
                        money(redemptions, decimals), money(refundCredits, decimals), money(walletBalance, decimals),
                        if (purchaseVariance == 0L) "PASS" else "INVESTIGATE: transaction value differs from coupon ledger purchase"
                    )
                }

            val saleRefundReconciliation = activities
                .filter { it.type == "SALE" }
                .sortedWith(compareBy<Activity> { it.customerId }.thenBy { it.currency }.thenBy { it.date }.thenBy { it.ref })
                .map { sale ->
                    val transactionId = sale.relatedTransactionId
                        ?: error("Sale history is missing its original transaction ID")
                    val saleMinor = sale.amountMinor
                        ?: error("Sale history is missing its corrected amount")
                    val refundRows = activities.filter {
                        it.type == "REFUND" && it.relatedTransactionId == transactionId
                    }
                    val refundMinor = CustomerFinancialReconciliationMath.sumMinor(
                        refundRows.map { CustomerFinancialReconciliationMath.absoluteMinor(it.amountMinor ?: 0L) }
                    )
                    val isVoided = sale.status != "Recorded"
                    val netContribution = if (isVoided) 0L else Math.subtractExact(saleMinor, refundMinor)
                    listOf(
                        sale.customerId, sale.name, sale.currency, sale.ref, money(saleMinor, sale.decimals),
                        if (isVoided) "VOIDED" else "VALID", refundRows.size, money(refundMinor, sale.decimals),
                        money(netContribution, sale.decimals),
                        CustomerFinancialReconciliationMath.saleRefundControlStatus(saleMinor, refundMinor, isVoided)
                    )
                }

            val history = activities.sortedWith(compareBy<Activity> { it.date }.thenBy { it.ref }.thenBy { it.customerId }).map { row ->
                listOf(
                    fmt(row.date), SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(row.date)),
                    row.branch, row.customerId, row.name, row.phone, row.type, row.ref, row.currency,
                    if (row.amountMinor == null) "" else money(row.amountMinor, row.decimals),
                    row.payment, row.status
                )
            }
            val reconciliation = keys.map { key ->
                val customer = customers[key.customerId] ?: error("Missing customer in credit reconciliation")
                val ledger = ledgerByKey[key] ?: Ledger()
                val rows = activities.filter { it.customerId == key.customerId && it.currency == key.currency }
                val funding = CustomerFinancialReconciliationMath.sumMinor(rows.filter { it.type == "SALE" }.map { it.creditMinor })
                val decimals = decimalsByCurrency[key.currency]
                    ?: com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(key.currency).decimalPlaces
                listOf(
                    customer.id, customer.name, key.currency, money(funding, decimals),
                    money(ledger.obligations, decimals), money(Math.subtractExact(ledger.obligations, funding), decimals),
                    money(-ledger.repaymentsSigned, decimals), money(ledger.adjustments, decimals),
                    money(ledger.balance, decimals),
                    if (ledger.obligations == funding) "PASS" else "INVESTIGATE: credit funding / obligation variance"
                )
            }

            val day = SimpleDateFormat("yyyy_MM_dd", Locale.US).format(Date())
            val filename = "Customer_Financial_Reconciliation_" + day + ".xlsx"
            val bytes = CustomerExcelWorkbook.build(
                listOf(
                    listOf("Customer Directory", listOf("Customer ID", "Customer Name", "Phone Number", "First Activity Date", "Last Activity Date", "Recorded Activity Rows", "Invoice Count", "Valid Sale Count", "Refund Count", "Status"), directory),
                    listOf("Customer Financial Summary", listOf("Customer ID", "Customer Name", "Phone Number", "Currency", "Valid Sale Count", "Sales Value", "Credit Sales Funding", "Credit Repaid (Collections)", "Sale Refund Value", "Coupon Purchase Refund Value", "Coupon Purchases", "Net Sales After Sale Refunds", "Credit Ledger Obligations", "Credit Ledger Repayments", "Credit Ledger Adjustments", "Outstanding Credit (Authoritative Ledger)", "Obligation Variance (Ledger - All Sale Funding)", "Reconciliation Status"), summary),
                    listOf("Full Transaction History", listOf("Date", "Time", "Branch", "Customer ID", "Customer Name", "Phone Number", "Event Type", "Reference Number", "Currency", "Amount (refunds negative)", "Payment Method", "Status"), history),
                    listOf("Sale Refund Reconciliation", listOf("Customer ID", "Customer Name", "Currency", "Transaction Reference", "Corrected Sale Value", "Sale Status", "Refund Event Count", "Refund Value", "Net Sales Contribution", "Control Status"), saleRefundReconciliation),
                    listOf("Credit Reconciliation", listOf("Customer ID", "Customer Name", "Currency", "Sale Credit Funding (including voided originals)", "Ledger Obligations", "Obligation Variance", "Ledger Repayments", "Ledger Adjustments", "Outstanding Credit Ledger Balance", "Control Status"), reconciliation),
                    listOf("Coupon Reconciliation", listOf("Customer ID", "Customer Name", "Currency", "Ledger Event Count", "Coupon Purchase Transaction Value", "Coupon Ledger Purchase Value", "Purchase Variance (Ledger - Transaction)", "Promotion Bonuses", "Redemptions", "Refund Credits", "Ledger Wallet Balance", "Control Status"), couponReconciliation)
                )
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/PaidDue")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads/PaidDue export")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Could not open output stream for customer workbook")
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
            uri
        }
    }

    private fun money(minor: Long, decimalPlaces: Int): java.math.BigDecimal =
        java.math.BigDecimal.valueOf(minor, decimalPlaces)

    fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply { type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(Intent.createChooser(send,"Share customer Excel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
    }

    private fun creditAmountForTransaction(db: SupportSQLiteDatabase, transactionId: String, decimalPlaces: Int): Double {
        // Credit exposure is the signed CREDIT tender balance after any
        // authoritative payment-method reclassification events.
        val amountMinor = db.query(
            SimpleSQLiteQuery(
                "SELECT COALESCE(SUM(amount_signed_minor),0) FROM settlement_entries WHERE transaction_id=? AND category='CREDIT' AND entry_purpose IN ('SALE_FUNDING','PAYMENT_METHOD_RECLASSIFICATION')",
                arrayOf<Any?>(transactionId)
            )
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        return amountMinor.coerceAtLeast(0L).toDouble() / Math.pow(10.0, decimalPlaces.toDouble())
    }

    private fun paymentMethodForTransaction(db: SupportSQLiteDatabase, transactionId: String, transactionType: String): String {
        // The immutable payment-method-change event is the latest authoritative
        // tender snapshot. Otherwise use original sale/coupon funding evidence,
        // never the transaction currency code or an unrelated repayment.
        db.query(
            SimpleSQLiteQuery(
                "SELECT new_method,new_channel FROM payment_method_change_events WHERE transaction_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1",
                arrayOf<Any?>(transactionId)
            )
        ).use { cursor ->
            if (cursor.moveToFirst()) return paymentMethodLabel(cursor.getString(0), cursor.getString(1))
        }
        val purpose = if (transactionType == "COUPON_PURCHASE") "COUPON_PURCHASE_FUNDING" else "SALE_FUNDING"
        val methods = linkedSetOf<String>()
        db.query(
            SimpleSQLiteQuery(
                "SELECT DISTINCT category,channel FROM settlement_entries WHERE transaction_id=? AND entry_purpose=? AND amount_signed_minor>0 ORDER BY occurred_at,id",
                arrayOf<Any?>(transactionId, purpose)
            )
        ).use { cursor ->
            while (cursor.moveToNext()) methods.add(paymentMethodLabel(cursor.getString(0), cursor.getString(1)))
        }
        return methods.joinToString(" + ").ifBlank { "Not recorded" }
    }

    private fun paymentMethodLabel(category: String?, channel: String?): String {
        val normalizedCategory = category.orEmpty().uppercase(Locale.ROOT)
        val normalizedChannel = channel.orEmpty().uppercase(Locale.ROOT)
        return when {
            normalizedChannel == "CASH_TENDER" || normalizedCategory == "CASH" -> "Cash"
            normalizedChannel == "SOFTPOS" -> "Card"
            normalizedChannel == "MANUAL_QR" -> "Online / Manual QR"
            normalizedChannel == "CREDIT_ACCOUNT" || normalizedCategory == "CREDIT" -> "Credit"
            normalizedChannel == "COUPON_WALLET" || normalizedCategory == "COUPON" -> "Coupon"
            normalizedChannel.isNotBlank() -> normalizedChannel.replace('_', ' ').lowercase(Locale.ROOT)
                .replaceFirstChar { it.uppercase(Locale.ROOT) }
            normalizedCategory.isNotBlank() -> normalizedCategory.replace('_', ' ').lowercase(Locale.ROOT)
                .replaceFirstChar { it.uppercase(Locale.ROOT) }
            else -> "Not recorded"
        }
    }

    private fun paymentCategoryLabel(category: String?): String = when (category.orEmpty().uppercase(Locale.ROOT)) {
        "CASH" -> "Cash"
        "ONLINE" -> "Online / Manual QR"
        "CREDIT" -> "Credit"
        "COUPON" -> "Coupon"
        else -> "Not recorded"
    }

    private fun fmt(value: Long?): String = value?.let { SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(it)) }.orEmpty()
}

internal object CustomerExcelWorkbook {
    private fun esc(s: String): String = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun col(number: Int): String { var n=number; var out=""; while(n>0){ val r=(n-1)%26; out=(('A'.code+r).toChar())+out; n=(n-1)/26 }; return out }

    fun build(sheets: List<List<Any?>>): ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(path:String, text:String){ z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>"""+sheets.indices.joinToString(""){ "<Override PartName=\"/xl/worksheets/sheet"+(it+1)+".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}+"</Types>")
            put("_rels/.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml","""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>"""+sheets.mapIndexed{i,s->"<sheet name=\""+esc(s[0].toString())+"\" sheetId=\""+(i+1)+"\" r:id=\"rId"+(i+1)+"\"/>"}.joinToString("")+"</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""+sheets.indices.joinToString(""){"<Relationship Id=\"rId"+(it+1)+"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet"+(it+1)+".xml\"/>"}+"</Relationships>")
            sheets.forEachIndexed { si,s ->
                val rows=listOf(s[1] as List<Any?>)+ (s[2] as List<List<Any?>>)
                val body=rows.mapIndexed { ri,row -> "<row r=\""+(ri+1)+"\">"+row.mapIndexed { ci,v ->
                    val cell=col(ci+1)+(ri+1)
                    if(v is Number) "<c r=\""+cell+"\"><v>"+v+"</v></c>" else "<c r=\""+cell+"\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"+esc(v?.toString().orEmpty())+"</t></is></c>"
                }.joinToString("")+"</row>" }.joinToString("")
                put("xl/worksheets/sheet"+(si+1)+".xml","""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>"""+body+"</sheetData></worksheet>")
            }
        }
        return out.toByteArray()
    }
}


internal object CustomerFinancialReconciliationMath {
    fun sumMinor(values: Iterable<Long>): Long =
        values.fold(0L) { total, value -> Math.addExact(total, value) }

    fun absoluteMinor(value: Long): Long {
        require(value != Long.MIN_VALUE) { "Financial amount cannot be represented as an absolute minor-unit value" }
        return kotlin.math.abs(value)
    }

    fun saleRefundControlStatus(saleMinor: Long, refundMinor: Long, isVoided: Boolean): String = when {
        refundMinor > saleMinor -> "REVIEW — refund value exceeds corrected sale value"
        isVoided && refundMinor > 0L -> "VOIDED — refund history retained; net contribution is zero"
        isVoided -> "VOIDED — excluded from net sales"
        refundMinor > 0L -> "PASS — refunds deducted once"
        else -> "PASS"
    }

    /** Refunds against already-voided sales are historical evidence, not a second net-sales deduction. */
    fun netSalesAfterRefunds(
        validSalesMinor: Long,
        refunds: Iterable<Pair<String, Long>>,
        voidedTransactionIds: Set<String>
    ): Long {
        val deductableRefunds = sumMinor(
            refunds.filter { (transactionId, _) -> transactionId !in voidedTransactionIds }
                .map { kotlin.math.abs(it.second) }
        )
        return Math.subtractExact(validSalesMinor, deductableRefunds)
    }
}
