package net.ankio.ledger

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportAndDetailUiTest {
    @Test fun exportSheetUsesAllMonthsAndAllowsTemplateChoice() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("go('settings');document.querySelector('#backup').click();document.querySelector('#export-bills').click()")
            web.await("document.querySelector('#export-start')!==null")
            assertEquals("true",web.value("document.querySelector('.export-scope').textContent.includes('所有月份')"))
            assertEquals("2",web.value("document.querySelectorAll('input[name=\"export-format\"]').length"))
            web.run("document.querySelector('input[value=\"qianji\"]').click()")
            assertEquals("qianji",web.text("document.querySelector('input[name=\"export-format\"]:checked').value"))
            web.run("LedgerUI.event('fileOperation','正在导出全部账单')")
            assertEquals("true",web.value("document.querySelector('#sheet').open && document.querySelector('[role=status]')!==null"))
            web.run("LedgerUI.event('fileOperation',null)")
            web.await("!document.querySelector('#sheet').open")
            assertEquals("false",web.value("document.querySelector('#sheet').open"))
        }
    }

    @Test fun resultLinkOpensOldMonthIncomeAndSnapshotOmitsPrivateDetailPayload() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val at=java.time.ZonedDateTime.now().minusMonths(2).withDayOfMonth(3).toInstant().toEpochMilli()
        val fixture=LedgerRecord(capture=Capture(Source.IMAGE,"synthetic","private-details-${UUID.randomUUID()}",at),
            bill=ParsedBill(2250,Direction.INCOME,"跨月导出测试","其他收入",at,"test-${UUID.randomUUID()}",true,"",true),state=RecordState.POSTED)
        app.store.merge(listOf(fixture));val saved=app.store.all().single { it.uid==fixture.uid }
        app.store.restoreHistory(saved.id,listOf(RestoredCaptureHistory(RecordCaptureHistory(1,at,"payment",2250,0,fixture.capture.text,false))))
        try {
            val intent=Intent(app,MainActivity::class.java).apply { action=MainActivity.OPEN_RECORD;putExtra("recordId",saved.id);addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.await("recordPanelOpen && draft?.id===${saved.id}")
                assertEquals("income",web.text("filter"));assertEquals("ledger",web.text("page"))
                assertEquals("true",web.value("month===localMonth($at)"))
                assertEquals(fixture.capture.text,web.text("draft.rawText"))
                assertEquals("true",web.value("(()=>{const r=JSON.parse(LedgerNative.snapshot()).records.find(r=>r.id===${saved.id});return !('rawText' in r)&&!('history' in r)&&'historyRevision' in r})()"))
                assertEquals("1",web.value("draft.history.length"))
            }
        } finally { app.store.delete(saved.id);app.changed() }
    }

    @Test fun supplementalHistoryRefreshPreservesUnsavedMerchantAndNote() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val at=System.currentTimeMillis()
        val fixture=LedgerRecord(capture=Capture(Source.IMAGE,"synthetic","initial",at),
            bill=ParsedBill(2250,Direction.EXPENSE,"历史延迟读取","其他",at,null,true,"",true),state=RecordState.POSTED)
        app.store.merge(listOf(fixture));val saved=app.store.all().single { it.uid==fixture.uid }
        app.store.restoreHistory(saved.id,listOf(RestoredCaptureHistory(RecordCaptureHistory(1,at,"payment",2250,0,fixture.capture.text,false))))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${saved.id})")
                web.await("recordPanelOpen && draft?.id===${saved.id}")
                web.run("document.querySelector('#merchant').value='未保存商户';document.querySelector('#note').value='未保存备注'")
                val db=app.store.writableDatabase
                db.execSQL("INSERT INTO capture_history(record_id,sequence,at,kind,amount,refunded,raw_text,refund_mode) VALUES(?,2,?,'payment_supplement',2250,0,'more','cumulative')",arrayOf(saved.id,at+1))
                app.changed()
                web.await("draft.history.length===2")
                assertEquals("未保存商户",web.text("document.querySelector('#merchant').value"))
                assertEquals("未保存备注",web.text("document.querySelector('#note').value"))
            }
        } finally { app.store.delete(saved.id);app.changed() }
    }
}
