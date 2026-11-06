package net.ankio.ledger.data

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class BillReportTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun at(day:String)=LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun row(time:Long,tags:List<String>,amount:Long=1200,direction:Direction=Direction.EXPENSE)=
        LedgerRecord(capture=Capture(Source.MANUAL,"report-test","",time),
            bill=ParsedBill(amount,direction,"汇总 & <测试>","交通",time,null,true,"",true),state=RecordState.POSTED,tags=tags)
    @Test fun dateAndExactAnyAllFiltersIncludeEveryPostedPage() {
        val name="report-${System.nanoTime()}.db";val store=LedgerStore(context,name)
        try {
            val first=at("2026-10-31");val end=at("2026-11-02")
            store.merge((0..205).map { row(first+it,listOf("项目A","差旅")) }+listOf(
                row(end-1,listOf("项目A")),row(end-2,listOf("差旅")),row(end-3,listOf("项目AB")),
                row(first-1,listOf("项目A","差旅")),row(end,listOf("项目A","差旅")),row(first+900,listOf("项目A"),2000,Direction.INCOME)))
            val removed=store.saveEdited(row(first+1000,listOf("项目A","差旅")));store.softDelete(removed.id)
            val reports=BillReports(store);val c=BillReport.Criteria("2026-10-31","2026-11-01",listOf("项目A","差旅"))
            val any=reports.generate(c);assertEquals(209,any.rows.size);assertEquals(208*1200L,any.expense);assertEquals(2000L,any.income)
            val all=reports.generate(c.copy(matchAll=true));assertEquals(206,all.rows.size)
            assertEquals(207,reports.generate(c.copy(tags=listOf("项目A"),direction="EXPENSE")).rows.size)
            assertEquals(0,reports.generate(c.copy(category="餐饮")).rows.size)
            assertEquals(40,any.preview().getJSONArray("rows").length());assertTrue(any.preview().getBoolean("hasMore"))
            assertEquals(9,any.preview(200).getJSONArray("rows").length())
            assertEquals(listOf("差旅","项目A","项目AB"),reports.labels())
            val copy=BillReport.decode(any.encode());assertEquals(any,copy)
            store.saveEdited(row(first+4000,listOf("项目A"),9999))
            assertEquals(209,any.rows.size);assertEquals(any.expense,copy.expense)
        } finally {store.close();context.deleteDatabase(name)}
    }
    @Test fun reimbursementAndCategoryAreIndependentSnapshotConditions() {
        val name="report-reimbursement-${System.nanoTime()}.db";val store=LedgerStore(context,name)
        try {
            val at=at("2026-11-01")
            val required=store.saveEdited(row(at,listOf("差旅","项目A")).copy(requiresReimbursement=true))
            store.saveEdited(row(at+1,listOf("差旅"),2300))
            store.saveEdited(row(at+2,listOf("差旅"),3400).copy(bill=row(at+2,emptyList()).bill.copy(amountCents=3400,category="餐饮"),requiresReimbursement=true))
            val reports=BillReports(store);val all=reports.generate(BillReport.Criteria("2026-11-01","2026-11-01"))
            val criteria=all.criteria.copy(category="交通",reimbursement="required",tags=listOf("差旅"))
            val report=reports.generate(criteria)
            assertEquals(6900L,all.expense);assertEquals(3,all.rows.size)
            assertEquals(1200L,report.expense);assertEquals(listOf(required.uid),report.rows.map { it.uid })
            assertTrue(report.rows.single().requiresReimbursement);assertEquals(listOf("差旅","项目A"),report.rows.single().tags)
            assertEquals("交通",report.categories.single().name);assertEquals(1,report.categories.single().count)
            assertTrue(report.preview().getJSONArray("rows").getJSONObject(0).getBoolean("requiresReimbursement"))
            assertEquals("required",report.preview().getJSONObject("criteria").getString("reimbursement"))
            assertEquals(report,BillReport.decode(report.encode()))
            assertEquals(2300L,reports.generate(criteria.copy(reimbursement="notRequired")).expense)
            store.setRequiresReimbursement(required.id,required.uid,false)
            assertTrue(report.rows.single().requiresReimbursement);assertEquals(1200L,report.expense)
            assertEquals(0,reports.generate(criteria).rows.size)
        } finally {store.close();context.deleteDatabase(name)}
    }
    @Test fun localDateRangeHandlesDstAndValidatesBeforeQuery() {
        val original=TimeZone.getDefault();val name="report-dst-${System.nanoTime()}.db";val store=LedgerStore(context,name)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val first=at("2026-03-08");val end=at("2026-03-09")
            assertEquals(23*60*60*1000L,end-first)
            store.merge(listOf(row(first-1,emptyList()),row(first,emptyList()),row(end-1,emptyList()),row(end,emptyList())))
            val report=BillReports(store).generate(BillReport.Criteria("2026-03-08","2026-03-08"))
            assertEquals(2,report.rows.size);assertEquals("America/New_York",report.zoneId)
            listOf("2026-02-30" to "2026-03-01","2026-03-02" to "2026-03-01").forEach { (a,b) ->
                try{BillReport.Criteria(a,b);fail("invalid dates accepted")}catch(_:Exception){}
            }
        } finally {TimeZone.setDefault(original);store.close();context.deleteDatabase(name)}
    }
    @Test fun filesContainFullSnapshotAndPngStripsAreContiguous() {
        val rows=(1..85).map { BillReport.Row("uid-$it",at("2026-11-01")+it,"商户第 $it 笔","交通","EXPENSE",1234,listOf("差旅","项目A"),"备注：& < >\n第二行",it%2==0) }
        val report=BillReport(java.util.UUID.randomUUID().toString(),System.currentTimeMillis(),BillReport.Criteria("2026-11-01","2026-11-01"),rows)
        val workbook=ByteArrayOutputStream();ReportWorkbook.write(report,workbook)
        val entries=mutableMapOf<String,String>()
        ZipInputStream(workbook.toByteArray().inputStream()).use { zip -> while(true) {val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes().toString(Charsets.UTF_8)} }
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("商户第 85 笔"))
        assertEquals(86,Regex("<row(?: [^>]*)?>").findAll(entries.getValue("xl/worksheets/sheet2.xml")).count())
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("1048.90"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("报销条件"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("需要报销"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("A1:J86"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("是"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("否"))
        val png=ByteArrayOutputStream();ReportPaper(report).writePng(png)
        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(png.toByteArray(),0,png.size(),options)
        assertEquals(1080,options.outWidth);assertTrue(options.outHeight>20_000)
        val file=java.io.File(context.cacheDir,"report-test-${System.nanoTime()}.pdf")
        try {
            file.outputStream().use {ReportPaper(report).writePdf(it)}
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer -> assertTrue(renderer.pageCount>5);renderer.openPage(renderer.pageCount-1).use { assertEquals(595,it.width) } }
            }
        } finally {file.delete()}
        val strips=ByteArrayOutputStream()
        StreamingPng.write(12,1030,strips) { top,bitmap -> val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(if(top==0)android.graphics.Color.RED else if(top==512)android.graphics.Color.GREEN else android.graphics.Color.BLUE) }
        val bitmap=BitmapFactory.decodeByteArray(strips.toByteArray(),0,strips.size())!!
        try {assertEquals(android.graphics.Color.RED,bitmap.getPixel(0,511));assertEquals(android.graphics.Color.GREEN,bitmap.getPixel(0,512));assertEquals(android.graphics.Color.BLUE,bitmap.getPixel(0,1029))}finally{bitmap.recycle()}
    }
    @Test fun pdfBillPaginationFixturesRenderOrdinaryAndOversizedRecords() {
        val rows=(1..4).map { BillReport.Row("pdf-$it",at("2026-11-01")+it*60_000,
            "分页商户$it","交通","EXPENSE",it*100L,listOf("分页标签$it"),"分页备注$it",it%2==0) }
        val report=BillReport(java.util.UUID.randomUUID().toString(),at("2026-11-01"),
            BillReport.Criteria("2026-11-01","2026-11-01"),rows)
        val oversized=report.copy(rows=listOf(rows.first().copy(merchant="超长账单商户",
            tags=listOf("超长标签 "+"跨省出差项目归集".repeat(40)),
            note=(1..63).joinToString("\n") { "长备注第${it.toString().padStart(2,'0')}行" })))
        val retain=context.packageName=="net.ankio.ledger.qa" &&
            InstrumentationRegistry.getArguments().getString("evidence")=="true"
        val directory=if(retain)java.io.File(context.filesDir,"report-evidence").apply {mkdirs()} else context.cacheDir
        listOf("pagination-ordinary" to report,"pagination-long" to oversized).forEach { (name,snapshot) ->
            val file=java.io.File(directory,"$name.pdf")
            try {
                file.outputStream().use {ReportPaper(snapshot).writePdf(it)}
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        assertTrue(renderer.pageCount>=2)
                        if(name=="pagination-long")assertTrue(renderer.pageCount>=4)
                        repeat(renderer.pageCount) { index -> renderer.openPage(index).use {page ->
                            val bitmap=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                if(retain)java.io.File(directory,"$name-native-${index+1}.png").outputStream().use {
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                                }
                            }finally{bitmap.recycle()}
                        } }
                    }
                }
            } finally { if(!retain)file.delete() }
        }
    }
    @Test fun workbookLongNotesKeepOneFilterableBillAndStableSourceIdentity() {
        val note=(1..70).joinToString("\n"){"备注${it.toString().padStart(2,'0')}：完整保留支付用途 🚌"}
        val uid="long-note-record"
        val report=BillReport(java.util.UUID.randomUUID().toString(),at("2026-11-01"),BillReport.Criteria("2026-11-01","2026-11-01"),
            listOf(BillReport.Row(uid,at("2026-11-01"),"长备注商户","交通","EXPENSE",1234,listOf("差旅"),note,true)))
        val bytes=ByteArrayOutputStream().also {ReportWorkbook.write(report,it)}.toByteArray()
        val entries=mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.inputStream()).use {zip->while(true){val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes()}}
        fun document(name:String)=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(entries.getValue(name).inputStream())
        val bills=document("xl/worksheets/sheet2.xml")
        assertEquals(2,bills.getElementsByTagName("row").length)
        assertEquals("A1:J2",(bills.getElementsByTagName("autoFilter").item(0) as org.w3c.dom.Element).getAttribute("ref"))
        assertTrue(bills.documentElement.textContent.contains("完整内容见「长文本」L001"))
        assertEquals(1,Regex("<v>12.34</v>").findAll(entries.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8)).count())
        val appendix=document("xl/worksheets/sheet3.xml");val rows=appendix.getElementsByTagName("row")
        val actual=StringBuilder()
        for(index in 1 until rows.length){
            val cells=(rows.item(index) as org.w3c.dom.Element).getElementsByTagName("c")
            assertEquals("L001",cells.item(0).textContent);assertEquals("账单明细",cells.item(1).textContent)
            assertEquals(uid,cells.item(2).textContent);assertEquals("备注",cells.item(3).textContent)
            actual.append(cells.item(5).textContent)
        }
        assertEquals(note,actual.toString())
    }
}
