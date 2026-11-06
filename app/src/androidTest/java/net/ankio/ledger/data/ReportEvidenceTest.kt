package net.ankio.ledger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import net.ankio.ledger.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ReportEvidenceTest {
    @Test fun reportFreezesOriginalMaterialsAndUsesMatchingHumanReferencesInSeparateFormats() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val key="report-freeze-${UUID.randomUUID()}";val images=ScreenshotStore(context,"$key-images")
        val ledger=LedgerStore(context,"$key.db",images)
        val retain=context.packageName=="net.ankio.ledger.qa"&&InstrumentationRegistry.getArguments().getString("evidence")=="true"
        val out=File(if(retain)context.filesDir else context.cacheDir,if(retain)"report-export-evidence"else"$key-evidence").apply {mkdirs()}
        val frozen=File(context.cacheDir,"$key-frozen")
        try {
            val at=java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val record=ledger.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"qa-report","",at),bill=ParsedBill(1234,Direction.EXPENSE,"交通凭证快照","交通",at,null,true,"",true),state=RecordState.POSTED,requiresReimbursement=true))
            val image=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);image.eraseColor(Color.rgb(25,100,70))
            val screenshot=ByteArrayOutputStream().also {image.compress(Bitmap.CompressFormat.JPEG,95,it)}.toByteArray();image.recycle();ledger.attachScreenshot(record.id,screenshot)
            val reimbursements=ReimbursementStore(context,ledger);val original="<?xml version=\"1.0\"?><invoice>原始材料不可转换</invoice>".toByteArray()
            val attachment=reimbursements.importAttachment(listOf(record.uid),"原始凭证.xml","application/xml","invoice",original.inputStream())
            val today=java.time.LocalDate.now().toString();val report=BillReports(ledger).generate(BillReport.Criteria(today,today))
            val evidence=ReportEvidence.freeze(ledger,reimbursements,report,frozen)
            ledger.saveEdited(record.copy(bill=record.bill.copy(amountCents=9999,merchant="后续更改")))
            // Removing a live link must not alter an already frozen report's original.
            reimbursements.command(JSONObject().put("action","unlinkAttachment").put("attachmentUid",attachment.getString("uid")).put("recordUid",record.uid))
            assertTrue(reimbursements.attachmentsForRecord(record.uid).isEmpty())
            assertEquals("A001、S001",evidence.recordAttachmentIds[record.uid])
            val zipFile=File(out,"report-pdf.zip");zipFile.outputStream().use {evidence.writePdfBundle(report,it)}
            val contents=linkedMapOf<String,ByteArray>();ZipInputStream(zipFile.inputStream()).use {zip->while(true){val entry=zip.nextEntry?:break;assertNull(contents.put(entry.name,zip.readBytes()))}}
            assertEquals(setOf("账单汇总.pdf","支付截图/S001.jpg","原始附件/A001-原始凭证.xml","附件索引.json"),contents.keys)
            assertArrayEquals(original,contents.getValue("原始附件/A001-原始凭证.xml"));assertArrayEquals(screenshot,contents.getValue("支付截图/S001.jpg"))
            val manifest=JSONObject(contents.getValue("附件索引.json").toString(Charsets.UTF_8));assertFalse(manifest.toString().contains("后续修改"))
            val xlsx=ByteArrayOutputStream();ReportWorkbook.write(report,xlsx,evidence::screenshot,evidence.recordAttachmentIds,evidence)
            File(out,"report.xlsx").writeBytes(xlsx.toByteArray());File(out,"report.pdf").writeBytes(contents.getValue("账单汇总.pdf"))
            File(out,"report.png").outputStream().use {ReportPaper(report).writePng(it)}
            val parts=linkedMapOf<String,ByteArray>();ZipInputStream(xlsx.toByteArray().inputStream()).use {zip->while(true){val entry=zip.nextEntry?:break;parts[entry.name]=zip.readBytes()}}
            val detail=parts.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8)
            assertTrue(detail.contains("12.34"));assertFalse(detail.contains("99.99"));assertTrue(detail.contains("A001、S001"))
            assertTrue(detail.contains("交通凭证快照"));assertFalse(detail.contains("后续更改"));assertEquals(1,parts.keys.count {it.startsWith("xl/media/")})
            assertTrue(parts.getValue("xl/styles.xml").toString(Charsets.UTF_8).contains("horizontal=\"center\""))
        }finally {
            ledger.reimbursementDirectory.listFiles()?.forEach {it.delete()};ledger.reimbursementDirectory.delete();ledger.close();context.deleteDatabase("$key.db")
            images.close();context.deleteDatabase("$key-images.db")
            File(context.filesDir,"$key-images").let {dir->dir.listFiles()?.forEach {it.delete()};dir.delete()}
            frozen.listFiles()?.forEach {it.delete()};frozen.delete()
            if(!retain){out.listFiles()?.forEach {it.delete()};out.delete()}
        }
    }
}
