package net.ankio.ledger

import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BillReportUiTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun record(tags:List<String> = emptyList()):LedgerRecord {
        val now=System.currentTimeMillis()
        return app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual","",now),
            bill=ParsedBill(12345,Direction.EXPENSE,"差旅交通 · 界面验证","交通",now,null,true,"",true),state=RecordState.POSTED,tags=tags)).also { app.changed() }
    }
    @Test fun customMultipleLabelsPreserveDraftAcrossRecreationAndNeverAddHomeFilter() {
        val row=record()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);ui.ready()
            assertEquals("true",ui.value("typeof window.ReimbursementUI==='undefined'&&!document.querySelector('#reimbursement-entry')&&typeof LedgerNative.reimbursementCommand==='undefined'&&typeof LedgerNative.importReimbursementAttachment==='undefined'&&typeof LedgerNative.exportReimbursement==='undefined'"))
            assertEquals("true",ui.value("!document.querySelector('#reimbursement-filter')"))
            ui.run("openRecord(${row.id});document.querySelector('#reimbursement-yes').click();document.querySelector('#bill-labels-button').click()")
            assertEquals("true",ui.value("!document.querySelector('#bill-materials-button')"))
            ui.await("!!document.querySelector('#new-label-form')")
            ui.run("document.querySelector('#new-label').value='项目A';document.querySelector('#new-label-form').requestSubmit();document.querySelector('#new-label').value='差旅';document.querySelector('#new-label-form').requestSubmit();document.querySelector('#labels-apply').click()")
            scenario.recreate();ui.ready();ui.await("recordPanelOpen&&document.querySelector('#bill-labels-value').textContent.includes('差旅')")
            ui.run("submitDraft()")
            ui.await("!recordPanelOpen");assertEquals(listOf("项目A","差旅"),app.store.get(row.id)!!.tags)
            assertTrue(app.store.get(row.id)!!.requiresReimbursement);assertEquals("交通",app.store.get(row.id)!!.bill.category);assertEquals(12345L,app.store.get(row.id)!!.bill.amountCents)
            ui.run("openRecord(${row.id});document.querySelector('#bill-labels-button').click()")
            ui.await("!!document.querySelector('[data-label=\"项目A\"]')")
            ui.run("document.querySelector('[data-label=\"项目A\"]').click();document.querySelector('#labels-apply').click();submitDraft()")
            ui.await("!recordPanelOpen");assertEquals(listOf("差旅"),app.store.get(row.id)!!.tags)
        } } finally {app.store.delete(row.id);app.changed()}
    }
    @Test fun criteriaValidationBothTagModesReceiptSkipAndRestore() {
        val row=record(listOf("项目A","差旅"));val second=record(listOf("项目A"))
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('settings');document.querySelector('#bill-report-entry').click()")
            ui.await("!!document.querySelector('#report-form')")
            ui.run("document.querySelector('#report-from').value='2026-11-15';document.querySelector('#report-through').value='2026-11-01';document.querySelector('#report-form').requestSubmit()")
            ui.await("document.querySelector('#report-error').textContent.includes('结束日期')")
            ui.run("document.querySelector('[data-report-range=\"month\"]').click();document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=\"项目A\"]').click();document.querySelector('[data-report-tag=\"差旅\"]').click();BillReportUI.back();document.querySelector('#report-match').value='all';document.querySelector('#report-match').dispatchEvent(new Event('change'));document.querySelector('#report-form').requestSubmit()")
            ui.await("!!document.querySelector('#report-receipt')")
            ui.run("document.querySelector('#report-skip').click()")
            assertEquals("true",ui.value("document.querySelector('#report-status').textContent==='1 笔账单已汇总'"))
            assertEquals("3",ui.value("document.querySelectorAll('[data-report-export]').length"))
            scenario.recreate();ui.ready();ui.await("!!document.querySelector('#report-receipt')")
            assertEquals("true",ui.value("document.querySelector('#report-receipt').textContent.includes('123.45')"))
            ui.run("document.querySelector('#report-edit').click();document.querySelector('#report-match').value='any';document.querySelector('#report-form').requestSubmit()")
            ui.await("document.querySelector('#report-status')?.textContent==='2 笔账单已汇总'")
            ui.run("LedgerUI.back()")
            ui.await("!document.querySelector('#sheet').open");assertEquals("settings",ui.text("page"))
        } } finally {app.store.delete(row.id);app.store.delete(second.id);app.changed()}
    }
    @Test fun nativeDateRangeChoicesAndStatisticsShareIndependentReimbursementFilter() {
        val first=record(listOf("界面筛选验证"));val second=record(listOf("界面筛选验证"))
        app.store.setRequiresReimbursement(first.id,first.uid,true);app.changed()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('settings');BillReportUI.open()")
            assertEquals("2",ui.value("document.querySelectorAll('#report-form input[type=date]').length"))
            assertEquals("0",ui.value("document.querySelectorAll('#report-calendar').length"))
            val day=java.time.LocalDate.now().toString()
            ui.run("document.querySelector('#report-from').value='$day';document.querySelector('#report-from').dispatchEvent(new Event('change'));document.querySelector('#report-through').value='$day';document.querySelector('#report-through').dispatchEvent(new Event('change'))")
            assertEquals(day,ui.text("document.querySelector('#report-from').value"))
            assertEquals(day,ui.text("document.querySelector('#report-through').value"))
            ui.run("document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=reimbursement]').click()")
            ui.await("!!document.querySelector('[data-filter-key=\"reimbursement\"][data-filter-value=\"required\"]')")
            assertEquals("true",ui.value("(()=>{const panel=document.querySelector('#report-filter-panel').getBoundingClientRect(),trigger=document.querySelector('#report-filters').getBoundingClientRect();return !document.querySelector('.report-picker-layer')&&panel.top>=trigger.bottom&&panel.width<=trigger.width+2})()"))
            ui.run("document.querySelector('[data-filter-key=\"reimbursement\"][data-filter-value=\"required\"]').click();document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=\"界面筛选验证\"]').click();BillReportUI.back()")
            ui.await("!document.querySelector('.report-picker-layer')")
            assertEquals("required",ui.text("document.querySelector('#report-reimbursement').value"))
            ui.run("document.querySelector('#report-form').requestSubmit()")
            ui.await("document.querySelector('#report-status')?.textContent==='1 笔账单已汇总'")
            assertEquals("true",ui.value("document.querySelector('#report-receipt').textContent.includes('需要报销')"))
            assertEquals("true",ui.value("document.querySelector('#sheet').getBoundingClientRect().height>=innerHeight-2"))
            ui.run("closeSheet(false);go('stats');document.querySelector('#stats-reimbursement [data-reimbursement=\"required\"]').click()")
            ui.await("document.querySelector('#stats-reimbursement [data-reimbursement=\"required\"]').getAttribute('aria-pressed')==='true'")
            val result=org.json.JSONObject(ui.text("LedgerNative.statistics(month,'required')"))
            assertTrue(result.getBoolean("ok"));assertEquals(12345L,result.getJSONObject("summary").getJSONObject("totals").getLong("expense"))
            assertEquals("交通",result.getJSONObject("summary").getJSONArray("categories").getJSONObject(0).getString("name"))
            ui.run("document.querySelector('#stats-reimbursement [data-reimbursement=\"notRequired\"]').click()")
            ui.await("document.querySelector('#stats-reimbursement [data-reimbursement=\"notRequired\"]').getAttribute('aria-pressed')==='true'")
            val records=org.json.JSONObject(ui.text("LedgerNative.statisticsRecords(month,'交通','EXPENSE',0,'notRequired')"))
            assertTrue(records.getBoolean("ok"));assertEquals(1,records.getJSONArray("records").length())
            assertEquals(second.id,records.getJSONArray("records").getJSONObject(0).getLong("id"))
        } } finally {app.store.delete(first.id);app.store.delete(second.id);app.changed()}
    }
    @Test fun immediateChoicesSurviveBackAndRecreationAndReset(){
        val row=record(listOf("条件合并验证"))
        try{ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('settings');BillReportUI.open();document.querySelector('#report-filters').click();document.querySelector('[data-filter-key=category][data-filter-value=交通]').click();document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=reimbursement]').click();document.querySelector('[data-filter-key=reimbursement][data-filter-value=required]').click();document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=条件合并验证]').click();BillReportUI.back()")
            scenario.recreate();ui.ready();ui.await("!!document.querySelector('#report-form')")
            assertEquals("交通",ui.text("BillReportUI.state().criteria.category"));assertEquals("required",ui.text("BillReportUI.state().criteria.reimbursement"));assertEquals("条件合并验证",ui.text("BillReportUI.state().criteria.tags[0]"))
            ui.run("document.querySelector('#report-filters').click();document.querySelector('#report-filters-reset').click();BillReportUI.back()")
            assertEquals("全部账单",ui.text("document.querySelector('#report-filter-summary').textContent"));assertEquals("0",ui.value("BillReportUI.state().criteria.tags.length"))
        }}finally{app.store.delete(row.id);app.changed()}
    }
    @Test fun generationDuringAnActiveSaveRespondsAndCanBeRetried() {
        val row=record(listOf("保存期间重试验证"))
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);ui.ready()
            // Hold the native save state deterministically; the neighboring test executes real MediaStore saves.
            val busy=net.ankio.ledger.ui.BillReportController::class.java.getDeclaredField("exporting").apply {isAccessible=true}
            scenario.onActivity {busy.setBoolean(it.billReports,true)}
            try {
                ui.run("go('settings');BillReportUI.open();document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=\"保存期间重试验证\"]').click();BillReportUI.back();document.querySelector('#report-form').requestSubmit()")
                ui.await("document.querySelector('#report-error').textContent==='正在保存文件，请稍后重试'")
                assertEquals("false",ui.value("document.querySelector('#report-generate').disabled"))
            } finally {scenario.onActivity {busy.setBoolean(it.billReports,false)}}
            ui.run("document.querySelector('#report-form').requestSubmit()")
            ui.await("document.querySelector('#report-status')?.textContent==='1 笔账单已汇总'")
        } } finally {app.store.delete(row.id);app.changed()}
    }
    @Test fun savesDirectlyToPhotosAndDownloadsUsingTheSameSnapshotForAllFormats() {
        val row=record(listOf("汇总导出验证"))
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('settings');BillReportUI.open();document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=\"汇总导出验证\"]').click();BillReportUI.back();document.querySelector('#report-form').requestSubmit()")
            ui.await("!!document.querySelector('#report-receipt')");ui.run("document.querySelector('#report-skip').click()")
            val token=ui.text("BillReportUI.state().token")
            app.store.saveEdited(app.store.get(row.id)!!.copy(bill=row.bill.copy(amountCents=999)));app.changed()
            for(format in listOf("png","pdf","xlsx")) {
                val collection=if(format=="png")MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val extension=if(format=="pdf")"zip"else format
                val prefix="花有谱-账单汇总-%-${token.take(8)}.$extension"
                ui.run("document.querySelector('[data-report-export=\"$format\"]').click()")
                val path=if(format=="png")"相册/花有谱" else "下载/花有谱"
                ui.await("document.querySelector('#report-save-status').textContent==='${if(format=="pdf")"PDF 与图片材料包 ZIP"else format.uppercase()} 已保存到$path'")
                assertEquals("true",ui.value("!!document.querySelector('#report-receipt') && document.querySelector('#sheet').open"))
                val saved=app.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.IS_PENDING),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",arrayOf(prefix),"${MediaStore.MediaColumns._ID} DESC")!!.use { cursor ->
                    assertTrue("export file not found",cursor.moveToFirst());assertEquals(0,cursor.getInt(2))
                    assertEquals(if(format=="png")"Pictures/花有谱/" else "Download/花有谱/",cursor.getString(1))
                    android.content.ContentUris.withAppendedId(collection,cursor.getLong(0))
                }
                try {
                    val bytes=app.contentResolver.openInputStream(saved)!!.use {it.readBytes()};assertTrue(bytes.size>100)
                    when(format) {
                        "png"->assertEquals(137.toByte(),bytes[0])
                        "pdf"->{val contents=linkedMapOf<String,ByteArray>();java.util.zip.ZipInputStream(bytes.inputStream()).use {zip->while(true){val entry=zip.nextEntry?:break;contents[entry.name]=zip.readBytes()}}
                            assertTrue(contents.keys.any {it.endsWith(".pdf")});assertFalse(contents.keys.any {it.endsWith(".xlsx")})
                            assertTrue(contents.entries.first {it.key.endsWith(".pdf")}.value.toString(Charsets.ISO_8859_1).startsWith("%PDF"))}
                        "xlsx"->{val zip=java.util.zip.ZipInputStream(bytes.inputStream());var content=""
                            zip.use {while(true){val e=zip.nextEntry?:break;if(e.name=="xl/worksheets/sheet1.xml")content=zip.readBytes().toString(Charsets.UTF_8)}}
                            assertTrue(content.contains("123.45"));assertFalse(content.contains("9.99"))}
                    }
                } finally {app.contentResolver.delete(saved,null,null)}
            }
            ui.run("LedgerNative.exportBillReport('invalid-token','pdf')")
            ui.await("document.querySelector('#report-save-status').textContent.includes('过期')")
            assertEquals(token,ui.text("BillReportUI.state().token"));assertEquals("true",ui.value("!!document.querySelector('#report-receipt')"))
        } } finally {app.store.delete(row.id);app.changed()}
    }
}
