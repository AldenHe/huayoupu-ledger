package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

class LedgerCsvExportTest {
    @Test fun reimbursementPropertyExportsWithoutChangingCategoryOrAmount() {
        val bill=record().copy(tags=listOf("项目A","出差\"交通"),requiresReimbursement=true)
        val rows=parse(export(listOf(bill),LedgerCsvExport.Format.GENERAL).second)
        val values=rows[0].zip(rows[1]).toMap()
        assertEquals("[\"项目A\",\"出差\\\"交通\"]",values["标签"])
        assertEquals("是",values["需要报销"])
        assertEquals(bill.bill.category,values["分类"])
        assertEquals("128.01",values["金额"])
    }

    private val january = 1_768_435_200_000L
    private val march = 1_773_532_800_000L

    private fun record(cents: Long = 12801, at: Long = january, direction: Direction = Direction.EXPENSE) =
        LedgerRecord(uid = "synthetic-$cents-$at-$direction",
            capture = Capture(Source.OCR, "com.taobao.taobao", "private OCR text must not be exported", at + 1000),
            bill = ParsedBill(cents, direction, "合成店铺", "日用购物", at, "512781660045037738", true, "", true),
            state = RecordState.POSTED, note = "合成备注")

    private fun export(records: List<LedgerRecord>, format: LedgerCsvExport.Format): Pair<Int, ByteArray> {
        val out = ByteArrayOutputStream()
        val count = LedgerCsvExport.write(records, format, out)
        return count to out.toByteArray()
    }

    @Test fun generalExportsAllMonthsBothDirectionsAndPreservesPostedLegacyDuplicatePointer() {
        val expense = record().copy(duplicateId = 99)
        val income = record(250000, march, Direction.INCOME)
        val review = record(500).copy(state = RecordState.REVIEW)
        val (count, bytes) = export(listOf(expense, income, review), LedgerCsvExport.Format.GENERAL)
        assertEquals(2, count)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3).toByteArray())
        val rows = parse(bytes)
        assertEquals(3, rows.size)
        val expenseRow = rows[0].zip(rows[1]).toMap()
        val incomeRow = rows[0].zip(rows[2]).toMap()
        assertEquals("支出", expenseRow["类型"])
        assertEquals("128.01", expenseRow["金额"])
        assertEquals("收入", incomeRow["类型"])
        assertEquals("2500.00", incomeRow["金额"])
        assertEquals("CNY", expenseRow["币种"])
        assertEquals(expense.uid, expenseRow["账单UID"])
        assertEquals("'512781660045037738", expenseRow["交易订单号"])
        assertEquals("屏幕识别", expenseRow["识别来源"])
        assertTrue(expenseRow.getValue("时间").startsWith("2026-01"))
        assertTrue(incomeRow.getValue("时间").startsWith("2026-03"))
        assertFalse(String(bytes, Charsets.UTF_8).contains("private OCR text"))
    }

    @Test fun independentParserRoundTripsCommasQuotesNewlinesAndUnicode() {
        val bill = record().copy(note = "购物,\"尺码\"\r\n第二行：🙂\n第三行",
            bill = record().bill.copy(merchant = "小王,\"精选\"商店🙂"))
        val rows = parse(export(listOf(bill), LedgerCsvExport.Format.GENERAL).second)
        val values = rows[0].zip(rows[1]).toMap()
        assertEquals(bill.note, values["备注"])
        assertEquals(bill.bill.merchant, values["商户"])
        assertEquals(rows[0].size, rows[1].size)
    }

    @Test fun qianjiMatchesOfficialV10TemplateAndDoesNotInventAnAccountOrImageUrl() {
        val expectedHeader = listOf("时间", "分类", "二级分类", "类型", "金额", "账户1", "账户2", "备注",
            "账单标记", "手续费", "优惠券", "标签", "账单图片")
        val rows = parse(export(listOf(record()), LedgerCsvExport.Format.QIANJI).second)
        assertEquals(expectedHeader, rows[0])
        val values = rows[0].zip(rows[1]).toMap()
        assertTrue(values.getValue("时间").matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertEquals("日用购物", values["分类"])
        assertEquals("支出", values["类型"])
        assertEquals("128.01", values["金额"])
        for (column in listOf("二级分类", "账户1", "账户2", "手续费", "优惠券", "标签", "账单图片"))
            assertEquals("", values[column])
        assertTrue(values.getValue("备注").contains("商户：合成店铺"))
        assertTrue(values.getValue("备注").contains("交易订单号：512781660045037738"))
        assertTrue(values.getValue("备注").contains("合成备注"))
    }

    @Test fun partialAndFullRefundsKeepNetAmountAndNeverTurnIntoIncomeOrDisappear() {
        val partial = record(3654).copy(bill = record(3654).bill.copy(refund = RefundInfo("wechat", 10000, 6346)))
        val full = record(0).copy(bill = record(0).bill.copy(refund = RefundInfo("alipay", 10000, 10000)))
        for (format in LedgerCsvExport.Format.entries) {
            val (count, bytes) = export(listOf(partial, full), format)
            assertEquals(2, count)
            val rows = parse(bytes)
            val partialValues = rows[0].zip(rows[1]).toMap()
            val fullValues = rows[0].zip(rows[2]).toMap()
            assertEquals("36.54", partialValues["金额"])
            assertEquals("0.00", fullValues["金额"])
            assertEquals("支出", fullValues["类型"])
            if (format == LedgerCsvExport.Format.GENERAL) {
                assertEquals("100.00", fullValues["原付款金额"])
                assertEquals("100.00", fullValues["已退款金额"])
            } else {
                assertEquals("不计收支", fullValues["账单标记"])
                assertTrue(fullValues.getValue("备注").contains("原付款：100.00；已退款：100.00"))
            }
        }
    }

    @Test fun spreadsheetFormulaInjectionIsNeutralizedIncludingWhitespacePrefixes() {
        val dangerous = listOf("=HYPERLINK(\"https://example.invalid\")", "+1+2", "-1+2", "@SUM(1)",
            "   =1+2", "\t=1+2", "\r=1+2", "\n=1+2")
        for (text in dangerous) {
            val bill = record().copy(note = text, bill = record().bill.copy(merchant = text, category = text,
                transactionId = text))
            val rows = parse(export(listOf(bill), LedgerCsvExport.Format.GENERAL).second)
            val values = rows[0].zip(rows[1]).toMap()
            for (column in listOf("分类", "商户", "备注", "交易订单号")) assertEquals("'$text", values[column])
            val qianji = parse(export(listOf(bill), LedgerCsvExport.Format.QIANJI).second)
            assertEquals("'$text", qianji[1][1])
        }
    }

    @Test fun amountsAreExactDecimalInAnyLocaleAndOutputRemainsOpen() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val out = object : ByteArrayOutputStream() {
                var closedByExporter = false
                override fun close() { closedByExporter = true; super.close() }
            }
            assertEquals(2, LedgerCsvExport.write(listOf(record(1), record(Money.MAX_CENTS)),
                LedgerCsvExport.Format.GENERAL, out))
            val rows = parse(out.toByteArray())
            assertEquals("0.01", rows[1][3])
            val expected = "${Money.MAX_CENTS / 100}.${(Money.MAX_CENTS % 100).toString().padStart(2, '0')}"
            assertEquals(expected, rows[2][3])
            assertFalse(out.closedByExporter)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun emptyExportHasHeaderAndInvalidDataOrDestinationCannotReportSuccess() {
        val (count, bytes) = export(emptyList(), LedgerCsvExport.Format.GENERAL)
        assertEquals(0, count)
        assertEquals(1, parse(bytes).size)
        val out = ByteArrayOutputStream()
        try {
            LedgerCsvExport.write(listOf(record().copy(bill = record().bill.copy(amountCents = null))),
                LedgerCsvExport.Format.GENERAL, out)
            fail("invalid posted record accepted")
        } catch (_: IllegalArgumentException) { assertEquals(0, out.size()) }
        val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("disk full") } }
        try {
            LedgerCsvExport.write(listOf(record()), LedgerCsvExport.Format.GENERAL, broken)
            fail("destination failure reported as success")
        } catch (_: IOException) { }
        assertEquals(LedgerCsvExport.Format.QIANJI, LedgerCsvExport.Format.fromKey("qianji"))
        try { LedgerCsvExport.Format.fromKey("wechat"); fail("unimplemented official format accepted") }
        catch (_: IllegalArgumentException) { }
    }

    /** Independent RFC 4180 state machine: does not share serialization logic with production. */
    private fun parse(bytes: ByteArray): List<List<String>> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var afterQuote = false
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') { cell.append('"'); index++ }
                    else { quoted = false; afterQuote = true }
                } else cell.append(char)
            } else when (char) {
                '"' -> { check(cell.isEmpty() && !afterQuote); quoted = true }
                ',' -> { row.add(cell.toString()); cell.setLength(0); afterQuote = false }
                '\r' -> {
                    check(index < text.length && text[index++] == '\n') { "row delimiter must be CRLF" }
                    row.add(cell.toString()); rows.add(row); row = mutableListOf(); cell.setLength(0); afterQuote = false
                }
                '\n' -> error("unquoted LF row delimiter")
                else -> { check(!afterQuote); cell.append(char) }
            }
        }
        check(!quoted && cell.isEmpty() && row.isEmpty()) { "CSV did not end at a complete CRLF row" }
        return rows
    }
}
