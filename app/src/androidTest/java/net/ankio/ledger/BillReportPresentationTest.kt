package net.ankio.ledger

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/** Deliberately generated QA evidence; guarded so it cannot seed a real user's ledger. */
@RunWith(AndroidJUnit4::class)
class BillReportPresentationTest {
    @Test fun captureConfirmedReportFlow() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        assumeTrue(InstrumentationRegistry.getArguments().getString("evidence")=="true")
        val app=context.applicationContext as LedgerApp;val ids=mutableListOf<Long>()
        val directory=File(context.filesDir,"report-evidence").apply {mkdirs()}
        var uiForCapture:WebUiProbe?=null
        fun capture(name:String) {
            val painted=java.util.concurrent.CountDownLatch(1)
            instrument.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
                activity.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){
                    override fun onComplete(requestId:Long){activity.webView.invalidate();painted.countDown()}
                })
            }
            check(painted.await(5,java.util.concurrent.TimeUnit.SECONDS))
            File(directory,"$name-layout.json").writeText(uiForCapture!!.text("JSON.stringify((()=>{const ids=['sheet','sheet-title','sheet-content','report-root'];const r={};ids.forEach(id=>{let e=document.getElementById(id);if(e)r[id]={rect:e.getBoundingClientRect().toJSON(),scrollTop:e.scrollTop,scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,overflow:getComputedStyle(e).overflow,flex:getComputedStyle(e).flex}});return r})())"))
            val point=org.json.JSONObject(uiForCapture!!.text("JSON.stringify((()=>{const e=document.querySelector('#report-status')||document.querySelector('#sheet[open] #sheet-title')||document.querySelector('#record-panel:not([hidden]) #bill-reimbursement-editor>span')||document.querySelector('.screen.mobile-active h1');const r=e.getBoundingClientRect();return {x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})())"))
            val screenOffset=IntArray(2)
            instrument.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single().webView.getLocationOnScreen(screenOffset)
            }
            instrument.uiAutomation.executeShellCommand("input tap ${point.getInt("x")+screenOffset[0]} ${point.getInt("y")+screenOffset[1]}").close()
            SystemClock.sleep(1400)
            instrument.uiAutomation.executeShellCommand("screencap -p").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    File(directory,"$name.png").outputStream().use { input.copyTo(it) }
                }
            }
        }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);uiForCapture=ui;ui.ready();ui.run("closeSheet(false);go('ledger')");capture("after-home")
            ui.run("go('settings')");capture("after-my")
            val names=listOf("高铁 · 上海虹桥","城市书店","项目会议午餐","机场接送","酒店住宿","晚餐","打印材料","地铁通勤")
            names.forEachIndexed {index,name ->
                val at=LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()+index*86_400_000L+10*3_600_000
                val category=listOf("交通","教育","餐饮","交通","居住","餐饮","日用","交通")[index]
                val row=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"qa-evidence","",at),
                    bill=ParsedBill(listOf(24900L,5800L,12800L,6800L,39800L,5600L,2400L,600L)[index],Direction.EXPENSE,name,category,at,null,true,"",true),
                    state=RecordState.POSTED,tags=if(index%2==0)listOf("差旅") else listOf("家庭"),requiresReimbursement=index%2==0))
                ids+=row.id
            }
            app.changed();ui.run("BillReportUI.open()")
            ui.await("!!document.querySelector('#report-filters')")
            capture("report-form")
            ui.run("document.querySelector('#report-filters').click()")
            ui.await("!document.querySelector('#report-filter-panel').hidden");capture("report-choices")
            ui.run("BillReportUI.back();document.querySelector('.report-scroll').scrollTop=document.querySelector('.report-scroll').scrollHeight");capture("report-form-expanded")
            val buttonPoint=org.json.JSONObject(ui.text("JSON.stringify((()=>{const r=document.querySelector('#report-generate').getBoundingClientRect();return {x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})())"))
            val webOffset=IntArray(2)
            scenario.onActivity {it.webView.getLocationOnScreen(webOffset)}
            val touchX=buttonPoint.getInt("x")+webOffset[0]
            val touchY=buttonPoint.getInt("y")+webOffset[1]
            val down=android.os.SystemClock.uptimeMillis()
            fun touch(action:Int) {
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,touchX.toFloat(),touchY.toFloat(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {assertTrue(instrument.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
            }
            touch(android.view.MotionEvent.ACTION_DOWN)
            try {
                touch(android.view.MotionEvent.ACTION_MOVE)
                SystemClock.sleep(130)
                File(directory,"button-pressed-style.json").writeText(ui.text("JSON.stringify((()=>{const e=document.querySelector('#report-generate');const s=getComputedStyle(e);return {active:e.matches(':active'),transform:s.transform,shadow:s.boxShadow,background:s.backgroundImage}})())"))
                assertEquals("true",ui.value("document.querySelector('#report-generate').matches(':active')"))
                instrument.uiAutomation.executeShellCommand("screencap -p").use {descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input ->
                        File(directory,"report-button-pressed.png").outputStream().use {input.copyTo(it)}
                    }
                }
            } finally {touch(android.view.MotionEvent.ACTION_CANCEL)}
            ui.run("document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=\"差旅\"]').click();BillReportUI.back();document.querySelector('#report-form').requestSubmit()")
            ui.await("!!document.querySelector('#report-receipt')")
            ui.run("document.querySelector('#report-skip').click()")
            ui.await("document.querySelector('#report-skip').hidden")
            File(directory,"receipt-state.txt").writeText(ui.text("JSON.stringify({text:document.querySelector('#report-root').innerText,rect:document.querySelector('#report-receipt').getBoundingClientRect().toJSON(),display:getComputedStyle(document.querySelector('#report-receipt')).display})"))
            capture("report-receipt")
            val token=ui.text("BillReportUI.state().token")
            val snapshot=GZIPInputStream(File(context.cacheDir,"bill-reports/$token.json.gz").inputStream()).bufferedReader().use {BillReport.decode(it.readText())}
            assertEquals(4,snapshot.rows.size)
            File(directory,"sample.xlsx").outputStream().use {ReportWorkbook.write(snapshot,it)}
            val longReport=snapshot.copy(criteria=snapshot.criteria.copy(tags=listOf("跨省出差项目费用和备用资金报销申请","家庭共同支出与节假日旅行费用归集")),rows=snapshot.rows.mapIndexed { index,row -> if(index==0)row.copy(merchant="很长的商户名称用于检查导出表格是否完整展示",amountCents=99999999999L) else row })
            File(directory,"sample-long.xlsx").outputStream().use {ReportWorkbook.write(longReport,it)}
            File(directory,"sample.pdf").outputStream().use {ReportPaper(snapshot).writePdf(it)}
            File(directory,"sample.png").outputStream().use {ReportPaper(snapshot).writePng(it)}
            ui.run("document.querySelector('.receipt-scroll').scrollTop=document.querySelector('.receipt-scroll').scrollHeight")
            capture("report-receipt-bottom")
            ui.run("closeSheet(false);openRecord(${ids[0]});document.querySelector('#bill-labels-button').click()")
            ui.await("!!document.querySelector('#new-label-form')");capture("label-picker")
            ui.run("closeSheet(false);document.querySelector('#bill-reimbursement-editor').scrollIntoView({block:'center'})");capture("bill-reimbursement")
            ui.run("cancelEditor(true);go('stats');document.querySelector('#stats-reimbursement [data-reimbursement=\"required\"]').click()")
            capture("stats-reimbursement")
            ui.run("go('settings');BillReportUI.open();document.querySelector('#report-from').value='2000-01-01';document.querySelector('#report-through').value='2000-01-02';document.querySelector('#report-form').requestSubmit()")
            ui.await("document.querySelector('#report-status')?.textContent==='0 笔账单已汇总'");capture("report-empty")
            instrument.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single().webView.settings.textZoom=150
            }
            ui.run("LedgerUI.event('fontScale',1.5);document.querySelector('#report-edit').click()")
            capture("report-form-large")
            ui.run("document.querySelector('#report-filters').click()")
            capture("report-choices-large")
            ui.run("BillReportUI.back()")
            ui.run("document.querySelector('#report-form').requestSubmit()")
            ui.await("document.querySelector('#report-status')?.textContent==='0 笔账单已汇总'");capture("report-empty-large")
            ui.run("document.querySelector('#report-close').click()")
            ui.await("!document.querySelector('#sheet').open")
            ui.run("BillReportUI.open();document.querySelector('#report-form').requestSubmit()")
            ui.await("document.querySelector('#report-status')?.textContent==='0 笔账单已汇总'")
            instrument.uiAutomation.executeShellCommand("input keyevent 4").close()
            ui.await("!document.querySelector('#sheet').open")
            assertEquals("settings",ui.text("page"))
        } } finally {ids.forEach(app.store::delete);app.changed()}
    }
}
