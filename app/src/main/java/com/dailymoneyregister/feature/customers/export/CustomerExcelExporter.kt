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
                val payment: String = "", val status: String = "Recorded"
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
                            if (isVoid) "VOIDED — excluded from net sales" else "Recorded"
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
                    val decimals = if (branch?.currencyCode.equals(currency, true) && branch != null) {
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
                    "r.occurred_at,r.id,t.id FROM refund_events r JOIN transactions t ON t.id=r.transaction_id " +
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
                    activities.add(
                        Activity(
                            customer.id, c.getLong(5), branches[c.getString(1)]?.name.orEmpty(),
                            customer.name, customer.phone, "REFUND", c.getString(6), currency, decimals,
                            -kotlin.math.abs(c.getLong(4)),
                            payment = paymentMethodForTransaction(db, c.getString(7), "SALE")
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
                "SELECT e.customer_id,e.currency_code,e.type,e.amount_signed_minor,o.branch_id,b.currency_code,b.currency_decimal_places " +
                    "FROM credit_ledger_events e JOIN operations o ON o.id=e.operation_id AND o.business_id=? " +
                    "LEFT JOIN branches b ON b.id=o.branch_id ORDER BY e.occurred_at,e.id",
                arrayOf(businessId)
            ).use { c ->
                while (c.moveToNext()) {
                    val customerId = c.getString(0)
                    require(customers.containsKey(customerId)) { "Credit ledger has no matching business customer: " + customerId }
                    val currency = c.getString(1).uppercase(Locale.ROOT)
                    val branchCurrency = c.getString(5)
                    val decimals = if (branchCurrency.equals(currency, true) && !c.isNull(6)) {
                        c.getInt(6)
                    } else {
                        com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(currency).decimalPlaces
                    }
                    registerCurrency(currency, decimals)
                    val ledger = ledgerByKey.getOrPut(Key(customerId, currency)) { Ledger() }
                    val amount = c.getLong(3)
                    when (c.getString(2)) {
                        "OBLIGATION" -> ledger.obligations = Math.addExact(ledger.obligations, amount)
                        "REPAYMENT" -> ledger.repaymentsSigned = Math.addExact(ledger.repaymentsSigned, amount)
                        "ADJUSTMENT" -> ledger.adjustments = Math.addExact(ledger.adjustments, amount)
                        else -> error("Unknown credit ledger type: " + c.getString(2))
                    }
                    ledger.balance = Math.addExact(ledger.balance, amount)
                }
            }

            val invoiceCounts = mutableMapOf<String, Int>()
            db.query(
                "SELECT d.customer_id,COUNT(*) FROM documents d JOIN operations o ON o.id=d.operation_id " +
                    "WHERE o.business_id=? AND d.document_type='INVOICE' AND d.customer_id IS NOT NULL GROUP BY d.customer_id",
                arrayOf(businessId)
            ).use { c -> while (c.moveToNext()) invoiceCounts[c.getString(0)] = c.getInt(1) }

            val byCustomer = activities.groupBy { it.customerId }
            val directory = customers.values.map { customer ->
                val rows = byCustomer[customer.id].orEmpty()
                listOf(
                    customer.id, customer.name, customer.phone, fmt(rows.minOfOrNull { it.date }),
                    fmt(rows.maxOfOrNull { it.date }), rows.size, invoiceCounts[customer.id] ?: 0,
                    rows.count { it.type == "SALE" && it.status == "Recorded" },
                    rows.count { it.type == "REFUND" },
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
                val decimals = decimalsByCurrency[key.currency]
                    ?: com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(key.currency).decimalPlaces
                val salesMinor = sales.sumOf { it.amountMinor ?: 0L }
                val creditFundingMinor = sales.sumOf { it.creditMinor }
                val repaidMinor = repayments.sumOf { it.amountMinor ?: 0L }
                val refundMinor = refunds.sumOf { kotlin.math.abs(it.amountMinor ?: 0L) }
                val couponMinor = couponPurchases.sumOf { it.amountMinor ?: 0L }
                val ledger = ledgerByKey[key] ?: Ledger()
                val variance = ledger.obligations - creditFundingMinor
                listOf(
                    customer.id, customer.name, customer.phone, key.currency, sales.size,
                    money(salesMinor, decimals), money(creditFundingMinor, decimals),
                    money(repaidMinor, decimals), money(refundMinor, decimals), money(couponMinor, decimals),
                    money(salesMinor - refundMinor, decimals), money(ledger.obligations, decimals),
                    money(-ledger.repaymentsSigned, decimals), money(ledger.adjustments, decimals),
                    money(ledger.balance, decimals), money(variance, decimals),
                    if (variance == 0L) "OK" else "REVIEW — ledger obligations differ from sale credit funding"
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
                val funding = rows.filter { it.type == "SALE" && it.status == "Recorded" }.sumOf { it.creditMinor }
                val decimals = decimalsByCurrency[key.currency]
                    ?: com.dailymoneyregister.core.model.currency.CurrencyRegistry.findByCode(key.currency).decimalPlaces
                listOf(
                    customer.id, customer.name, key.currency, money(funding, decimals),
                    money(ledger.obligations, decimals), money(ledger.obligations - funding, decimals),
                    money(-ledger.repaymentsSigned, decimals), money(ledger.adjustments, decimals),
                    money(ledger.balance, decimals),
                    if (ledger.obligations == funding) "PASS" else "INVESTIGATE: credit funding / obligation variance"
                )
            }

            val day = SimpleDateFormat("yyyy_MM_dd", Locale.US).format(Date())
            val filename = "Customer_Financial_Reconciliation_" + day + ".xlsx"
            val bytes = xlsx(
                listOf(
                    listOf("Customer Directory", listOf("Customer ID", "Customer Name", "Phone Number", "First Activity Date", "Last Activity Date", "Recorded Activity Rows", "Invoice Count", "Valid Sale Count", "Refund Count", "Status"), directory),
                    listOf("Customer Financial Summary", listOf("Customer ID", "Customer Name", "Phone Number", "Currency", "Valid Sale Count", "Sales Value", "Credit Sales Funding", "Credit Repaid (Collections)", "Refund Value", "Coupon Purchases", "Net Sales After Refunds", "Credit Ledger Obligations", "Credit Ledger Repayments", "Credit Ledger Adjustments", "Outstanding Credit (Authoritative Ledger)", "Obligation Variance (Ledger - Sale Funding)", "Reconciliation Status"), summary),
                    listOf("Full Transaction History", listOf("Date", "Time", "Branch", "Customer ID", "Customer Name", "Phone Number", "Event Type", "Reference Number", "Currency", "Amount (refunds negative)", "Payment Method", "Status"), history),
                    listOf("Credit Reconciliation", listOf("Customer ID", "Customer Name", "Currency", "Sale Credit Funding", "Ledger Obligations", "Obligation Variance", "Ledger Repayments", "Ledger Adjustments", "Outstanding Credit Ledger Balance", "Control Status"), reconciliation)
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
    private fun esc(s: String): String = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun col(number: Int): String { var n=number; var out=""; while(n>0){ val r=(n-1)%26; out=(('A'.code+r).toChar())+out; n=(n-1)/26 }; return out }

    private fun xlsx(sheets: List<List<Any?>>): ByteArray {
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
