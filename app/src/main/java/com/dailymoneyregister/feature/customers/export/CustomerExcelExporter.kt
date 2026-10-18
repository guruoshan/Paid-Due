package com.dailymoneyregister.feature.customers.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.dailymoneyregister.core.database.DmrDatabase
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
            val businessId = SessionManager.currentSession.value?.business?.id ?: error("No active business session")
            val db = database.openHelper.readableDatabase
            data class Customer(val id: String, val name: String, val phone: String, val updated: Long)
            data class Activity(val date: Long, val branch: String, val name: String, val phone: String, val type: String, val ref: String, val amount: Double, val currency: String)
            val customers = linkedMapOf<String, Customer>()
            db.query("SELECT id,name,canonical_phone,updated_at FROM customers WHERE business_id=? AND TRIM(canonical_phone)<>'' ORDER BY updated_at DESC", arrayOf(businessId)).use { c ->
                while (c.moveToNext()) {
                    val phone = c.getString(2).orEmpty().trim()
                    if (phone.isNotEmpty()) {
                        val key = phone.filter { it.isDigit() }.ifEmpty { phone }
                        val row = Customer(c.getString(0), c.getString(1), phone, c.getLong(3))
                        val old = customers[key]
                        if (old == null || row.updated >= old.updated) customers[key] = row
                    }
                }
            }
            val branches = mutableMapOf<String,String>()
            db.query("SELECT id,name FROM branches WHERE business_id=?", arrayOf(businessId)).use { c -> while(c.moveToNext()) branches[c.getString(0)] = c.getString(1) }
            val activity = mutableListOf<Activity>()
            db.query("SELECT t.branch_id,t.type,t.customer_id,t.grand_total_minor,t.currency_decimal_places,t.created_at,t.display_ref,t.currency_code,c.name,c.canonical_phone FROM transactions t JOIN branches b ON b.id=t.branch_id AND b.business_id=? JOIN customers c ON c.id=t.customer_id ORDER BY t.created_at", arrayOf(businessId)).use { c ->
                while(c.moveToNext()) {
                    val phone = c.getString(9).orEmpty()
                    val key = phone.filter { it.isDigit() }.ifEmpty { phone }
                    val customer = customers[key] ?: continue
                    val amount = c.getLong(3).toDouble() / Math.pow(10.0,c.getInt(4).toDouble())
                    activity.add(Activity(c.getLong(5),branches[c.getString(0)].orEmpty(),customer.name,customer.phone,c.getString(1),c.getString(6),amount,c.getString(7)))
                }
            }
            // Include repayments and issued invoices as first-class history events.
            runCatching {
                db.query("SELECT r.customer_id,r.amount_minor,r.currency_code,r.occurred_at,r.id,r.payment_category,b.name,b.currency_decimal_places FROM credit_repayments r JOIN customers c ON c.id=r.customer_id JOIN operations o ON o.id=r.operation_id JOIN branches b ON b.id=o.branch_id WHERE c.business_id=? ORDER BY r.occurred_at", arrayOf(businessId)).use { c ->
                    while(c.moveToNext()) {
                        val phoneCursor = db.query("SELECT name,canonical_phone FROM customers WHERE id=?", arrayOf(c.getString(0)))
                        phoneCursor.use { p ->
                            if (p.moveToFirst()) {
                                val phone = p.getString(1).orEmpty()
                                val key = phone.filter { it.isDigit() }.ifEmpty { phone }
                                val customer = customers[key] ?: continue
                                val amount = c.getLong(1).toDouble() / Math.pow(10.0,c.getInt(7).toDouble())
                                activity.add(Activity(c.getLong(3),c.getString(6),customer.name,customer.phone,"CREDIT_REPAYMENT",c.getString(4),amount,c.getString(2)))
                            }
                        }
                    }
                }
            }
            runCatching {
                db.query("SELECT d.customer_id,d.created_at,d.display_number,d.document_type FROM documents d JOIN operations o ON o.id=d.operation_id WHERE o.business_id=? AND d.customer_id IS NOT NULL ORDER BY d.created_at", arrayOf(businessId)).use { c ->
                    while(c.moveToNext()) {
                        val customerRow = db.query("SELECT name,canonical_phone FROM customers WHERE id=?", arrayOf(c.getString(0)))
                        customerRow.use { p ->
                            if (p.moveToFirst()) {
                                val phone = p.getString(1).orEmpty()
                                val key = phone.filter { it.isDigit() }.ifEmpty { phone }
                                val customer = customers[key] ?: continue
                                activity.add(Activity(c.getLong(1),"",customer.name,customer.phone,c.getString(3),c.getString(2),0.0,""))
                            }
                        }
                    }
                }
            }
            val groups = activity.groupBy { it.phone.filter { ch -> ch.isDigit() }.ifEmpty { it.phone } }
            val directory = customers.values.map { customer ->
                val rows = groups[customer.phone.filter { it.isDigit() }.ifEmpty { customer.phone }].orEmpty()
                val sales = rows.filter { it.type.contains("SALE",true) && !it.type.contains("REPAY",true) }
                val credit = rows.filter { it.type.contains("CREDIT",true) }
                val repaid = rows.filter { it.type.contains("REPAY",true) }
                val refunds = rows.filter { it.type.contains("REFUND",true) }
                val invoices = try { db.query("SELECT COUNT(*) FROM documents WHERE customer_id=? AND document_type='INVOICE'",arrayOf(customer.id)).use { q -> if(q.moveToFirst()) q.getInt(0) else 0 } } catch (_:Exception) { 0 }
                listOf(customer.name,customer.phone,fmt(rows.minOfOrNull { it.date }),fmt(rows.maxOfOrNull { it.date }),rows.size,sales.sumOf { it.amount },credit.sumOf { it.amount },repaid.sumOf { it.amount },credit.sumOf { it.amount }-repaid.sumOf { it.amount },invoices,refunds.size,if(rows.isEmpty()) "No recorded transaction" else "Active")
            }
            val summary = customers.values.map { customer ->
                val rows = groups[customer.phone.filter { it.isDigit() }.ifEmpty { customer.phone }].orEmpty()
                listOf(customer.name,customer.phone,rows.count { it.type.contains("SALE",true) },rows.filter { it.type.contains("SALE",true) }.sumOf { it.amount },rows.filter { it.type.contains("CREDIT",true) }.sumOf { it.amount },rows.filter { it.type.contains("REPAY",true) }.sumOf { it.amount },rows.filter { it.type.contains("REFUND",true) }.sumOf { it.amount },rows.filter { it.type.contains("COUPON",true) }.sumOf { it.amount },rows.filter { it.type.contains("CREDIT",true) }.sumOf { it.amount }-rows.filter { it.type.contains("REPAY",true) }.sumOf { it.amount },rows.sumOf { it.amount })
            }
            val history = activity.map { listOf(fmt(it.date),SimpleDateFormat("HH:mm:ss",Locale.US).format(Date(it.date)),it.branch,it.name,it.phone,it.type,it.ref,it.amount,it.currency,"Recorded") }
            val day = SimpleDateFormat("yyyy_MM_dd",Locale.US).format(Date())
            val filename = "PaidDue_Customers_" + day + ".xlsx"
            val bytes = xlsx(listOf(
                listOf("Customer Directory",listOf("Customer Name","Phone Number","First Activity Date","Last Activity Date","Total Transactions","Total Sales","Credit Amount","Credit Repaid","Outstanding Credit","Invoice Count","Refund Count","Status"),directory),
                listOf("Customer Financial Summary",listOf("Customer Name","Phone Number","Sales Count","Sales Value","Credit Sales","Credit Repaid","Refund Value","Coupon Value","Outstanding Credit","Net Customer Value"),summary),
                listOf("Full Transaction History",listOf("Date","Time","Branch","Customer Name","Phone Number","Transaction Type","Reference Number","Amount","Payment Method","Status"),history)
            ))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,filename)
                put(MediaStore.Downloads.MIME_TYPE,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH,"Download/PaidDue")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Could not create Downloads/PaidDue export")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write workbook")
            uri
        }
    }

    fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply { type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(Intent.createChooser(send,"Share customer Excel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
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
