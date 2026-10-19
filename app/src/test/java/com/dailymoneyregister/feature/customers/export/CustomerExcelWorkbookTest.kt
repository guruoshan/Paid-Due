package com.dailymoneyregister.feature.customers.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class CustomerExcelWorkbookTest {

    @Test
    fun workbookIsACompleteReadableOpenXmlPackageWithAllReconciliationSheets() {
        val sheets: List<List<Any?>> = listOf(
            listOf("Customer Directory", listOf("Customer ID", "Customer Name"), listOf(listOf("cust-1", "Example"))),
            listOf("Customer Financial Summary", listOf("Currency", "Sales Value"), listOf(listOf("OMR", BigDecimal("12.345")))),
            listOf("Full Transaction History", listOf("Reference", "Amount"), listOf(listOf("SALE-1", BigDecimal("12.345")))),
            listOf("Sale Refund Reconciliation", listOf("Net Sales", "Control Status"), listOf(listOf(BigDecimal("8.500"), "PASS"))),
            listOf("Credit Reconciliation", listOf("Outstanding Credit", "Control Status"), listOf(listOf(BigDecimal("0.000"), "PASS"))),
            listOf("Coupon Reconciliation", listOf("Wallet Balance", "Control Status"), listOf(listOf(BigDecimal("5.000"), "PASS")))
        )

        val bytes = CustomerExcelWorkbook.build(sheets)
        assertTrue("An XLSX package should not be empty", bytes.isNotEmpty())

        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                entries[entry.name] = output.toByteArray()
            }
        }

        val requiredParts = listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels"
        ) + (1..6).map { "xl/worksheets/sheet$it.xml" }
        requiredParts.forEach { assertNotNull("Missing XLSX part: $it", entries[it]) }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, data) ->
            try {
                factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
            } catch (error: Exception) {
                throw AssertionError("Malformed XML in XLSX part $name", error)
            }
        }

        val workbook = factory.newDocumentBuilder().parse(ByteArrayInputStream(entries.getValue("xl/workbook.xml")))
        val sheetNodes = workbook.getElementsByTagNameNS("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "sheet")
        assertEquals(6, sheetNodes.length)
        assertEquals(
            listOf(
                "Customer Directory",
                "Customer Financial Summary",
                "Full Transaction History",
                "Sale Refund Reconciliation",
                "Credit Reconciliation",
                "Coupon Reconciliation"
            ),
            (0 until sheetNodes.length).map { (sheetNodes.item(it) as Element).getAttribute("name") }
        )

        val summary = factory.newDocumentBuilder().parse(
            ByteArrayInputStream(entries.getValue("xl/worksheets/sheet2.xml"))
        )
        val numericValues = summary.getElementsByTagNameNS(
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
            "v"
        )
        assertTrue("Financial amount should be serialized as a numeric cell", numericValues.length > 0)
        assertEquals("12.345", numericValues.item(0).textContent)
    }
}
