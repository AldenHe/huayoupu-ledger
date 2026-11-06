package net.ankio.ledger.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ReportWorkbookTest {
    @Test fun workbookIsActualOoxmlWithNumericAmountsAndLiteralUntrustedText() {
        val report=BillReport("c928d5b6-f833-46b9-aacb-cc01bc6f6bf9",1793808000000,
            BillReport.Criteria("2026-11-01","2026-11-30",listOf("报销","研发 & 项目"),true),
            listOf(BillReport.Row("r1",1793808000000,"=HYPERLINK(\"bad\") & <店>","交通","EXPENSE",12345,listOf("报销"),"文字\u0001测试")))
        val out=ByteArrayOutputStream();ReportWorkbook.write(report,out)
        val entries=mutableMapOf<String,ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while(true){val e=z.nextEntry?:break;entries[e.name]=z.readBytes()} }
        entries.values.forEach { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it.inputStream()) }
        val sheet=entries.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8)
        assertTrue(sheet.contains("<v>123.45</v>"));assertTrue(sheet.contains("&amp; &lt;店&gt;"));assertFalse(sheet.contains("<f>"))
        assertTrue(sheet.contains("r=\"A2\""));assertTrue(sheet.contains("r=\"E2\""));assertTrue(sheet.contains("inlineStr"));assertFalse(sheet.contains('\u0001'))
        assertTrue(entries.containsKey("[Content_Types].xml"))
    }
}
