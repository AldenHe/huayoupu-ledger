package net.ankio.ledger

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

@RunWith(AndroidJUnit4::class)
class FilterBudgetUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as LedgerApp
    private var captureProbe:WebUiProbe?=null
    private fun guard(){assumeTrue(app.packageName=="net.ankio.ledger.qa")}
    private fun record(amount:Long):LedgerRecord {
        val now=System.currentTimeMillis()
        return app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"qa-budget-filter","",now),
            bill=ParsedBill(amount,Direction.EXPENSE,"进度与筛选验证","交通",now,null,true,"",true),
            state=RecordState.POSTED,tags=listOf("差旅"))).also {app.changed()}
    }
    private fun capture(name:String){
        if(InstrumentationRegistry.getArguments().getString("evidence")!="true")return
        val ui=captureProbe!!
        val point=org.json.JSONObject(ui.text("JSON.stringify((()=>{const menu=document.querySelector('.stats-reimbursement[open] .stats-reimbursement-options'),e=menu||document.querySelector('#sheet[open] #sheet-title')||document.querySelector('.screen.mobile-active h1');const r=e.getBoundingClientRect();return {x:(r.x+r.width/2)*devicePixelRatio,y:(menu?r.y+2:r.y+r.height/2)*devicePixelRatio}})())"))
        val offset=IntArray(2)
        instrument.runOnMainSync {androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single().webView.getLocationOnScreen(offset)}
        instrument.uiAutomation.executeShellCommand("input tap ${point.getInt("x")+offset[0]} ${point.getInt("y")+offset[1]}").close()
        SystemClock.sleep(1400)
        val directory=File(app.filesDir,"filter-budget-evidence").apply {mkdirs()}
        instrument.uiAutomation.executeShellCommand("screencap -p").use {descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input ->
                File(directory,"$name.png").outputStream().use {input.copyTo(it)}
            }
        }
    }
    @Test fun inlineCascadingFiltersApplyImmediatelyRestoreAndSupportLargeText(){
        guard();val row=record(12345)
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);captureProbe=ui;ui.ready();ui.run("go('settings');BillReportUI.open();document.querySelector('#report-filters').click()")
            assertEquals("true",ui.value("!document.querySelector('.report-picker-layer')&&!document.querySelector('#report-form').inert&&!document.querySelector('#report-filters-apply')"))
            assertEquals("4",ui.value("document.querySelectorAll('[data-filter-group]').length"));capture("report-category-open")
            ui.run("document.querySelector('[data-filter-key=category][data-filter-value=交通]').click()")
            assertEquals("交通",ui.text("BillReportUI.state().criteria.category"));assertEquals("true",ui.value("document.querySelector('#report-filter-panel').hidden"))
            ui.run("document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=差旅]').click();BillReportUI.back()")
            assertTrue(ui.text("document.querySelector('#report-filter-summary').textContent").contains("差旅"))
            scenario.recreate();ui.ready();ui.await("!!document.querySelector('#report-filters')")
            assertTrue(ui.text("document.querySelector('#report-filter-summary').textContent").contains("交通"))
            instrument.runOnMainSync {androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single().webView.settings.textZoom=150}
            ui.run("LedgerUI.event('fontScale',1.5);document.querySelector('#report-filters').click()");capture("report-choices-large")
            assertEquals("true",ui.value("(()=>{const r=document.querySelector('#report-generate').getBoundingClientRect();return r.bottom<=innerHeight&&r.width>200&&document.documentElement.scrollWidth<=innerWidth})()"))
            ui.run("document.querySelector('#report-filters-reset').click();BillReportUI.back()")
            assertEquals("全部账单",ui.text("document.querySelector('#report-filter-summary').textContent"))
            assertEquals("true",ui.value("document.activeElement===document.querySelector('#report-filters')"))
        }}finally{app.store.delete(row.id);app.changed()}
    }
    @Test fun statisticsDropdownKeepsTheSameFilteredTotalsAndDismissesOutside(){
        guard();val first=record(20000);val second=record(15000)
        app.store.setRequiresReimbursement(first.id,first.uid,true);app.changed()
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);captureProbe=ui;ui.ready();ui.run("go('stats');document.querySelector('#stats-reimbursement summary').click()")
            ui.await("document.querySelector('#stats-reimbursement').open");capture("stats-dropdown")
            instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.await("!document.querySelector('#stats-reimbursement').open")
            assertEquals("stats",ui.text("page"))
            assertEquals("true",ui.value("document.activeElement===document.querySelector('#stats-reimbursement summary')"))
            instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.await("page==='ledger'")
            ui.run("go('stats');document.querySelector('#stats-reimbursement summary').click()")
            ui.run("document.querySelector('#stats-reimbursement [data-reimbursement=required]').click()")
            assertEquals("需要报销",ui.text("document.querySelector('#stats-reimbursement-value').textContent"))
            assertEquals("false",ui.value("document.querySelector('#stats-reimbursement').open"))
            assertTrue(ui.text("document.querySelector('#analysis-total').textContent").contains("200.00"))
            capture("stats-reimbursement")
            ui.run("document.querySelector('#stats-reimbursement summary').click();document.querySelector('#stats-type [data-type=income]').click()")
            assertEquals("false",ui.value("document.querySelector('#stats-reimbursement').open"))
            assertTrue(ui.text("document.querySelector('#analysis-total').textContent").contains("0.00"))
        }} finally {app.store.delete(first.id);app.store.delete(second.id);app.changed()}
    }
    @Test fun budgetKeepsUnsetLabelAndRolloverOnOneLineAndAnimatesUnseenSpendingOnReturn(){
        guard();val original=app.monthlyBudget.cents();val rows=mutableListOf<LedgerRecord>()
        try {
            app.monthlyBudget.set(0);app.changed()
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val ui=WebUiProbe(scenario);captureProbe=ui;ui.ready();ui.run("go('ledger');filter='expense';render()")
                assertEquals("true",ui.value("(()=>{const a=document.querySelector('.budget-label').getBoundingClientRect(),b=document.querySelector('.budget-status').getBoundingClientRect();return Math.abs(a.y+a.height/2-b.y-b.height/2)<2&&a.right<b.left})()"))
                capture("budget-unset")
                app.monthlyBudget.set(100000);rows+=record(20000);app.changed()
                ui.run("viewKey='';refresh()")
                val settled="(()=>{const e=document.querySelector('.budget-fill'),m=document.querySelector('.budget-meter');return Math.abs(new DOMMatrix(getComputedStyle(e).transform).a-Number(m.getAttribute('aria-valuenow'))/Number(m.getAttribute('aria-valuemax')))<.000001})()"
                ui.await(settled);SystemClock.sleep(40)
                ui.run("go('stats')")
                rows+=record(15000);app.changed();ui.run("viewKey='';refresh()")
                assertEquals("0",ui.value("document.querySelector('#ledger').getClientRects().length"))
                ui.run("go('ledger')")
                assertEquals("true",ui.value("(()=>{const e=document.querySelector('.budget-fill'),m=document.querySelector('.budget-meter');return new DOMMatrix(getComputedStyle(e).transform).a<Number(m.getAttribute('aria-valuenow'))/Number(m.getAttribute('aria-valuemax'))})()"))
                ui.await(settled);SystemClock.sleep(40)
                assertEquals("true",ui.value("(()=>{const e=document.querySelector('.budget-fill'),m=document.querySelector('.budget-meter');return Math.abs(new DOMMatrix(getComputedStyle(e).transform).a-Number(m.getAttribute('aria-valuenow'))/Number(m.getAttribute('aria-valuemax')))<.001})()"))
                capture("budget-progress")
                ui.run("go('stats');go('ledger')")
                assertEquals("true",ui.value(settled))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                rows+=record(10000);app.changed()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);ui.ready()
                ui.await("Number(document.querySelector('.budget-meter').getAttribute('aria-valuenow'))>=45000")
                ui.await(settled)
                assertTrue(ui.text("document.querySelector('.budget-status').textContent").contains("550.00"))
                ui.run("filter='income';render()")
                assertEquals("true",ui.value("document.querySelector('#monthly-budget').hidden"))
                val motionScale=android.provider.Settings.Global.getString(app.contentResolver,"animator_duration_scale")?:"1"
                try {
                    instrument.uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").close()
                    ui.await("LedgerNative.reportReduceMotion()")
                    rows+=record(5000);app.changed();ui.run("filter='expense';viewKey='';refresh()")
                    assertEquals("true",ui.value(settled))
                } finally {instrument.uiAutomation.executeShellCommand("settings put global animator_duration_scale $motionScale").close()}
            }
        } finally {rows.forEach {app.store.delete(it.id)};app.monthlyBudget.set(original);app.changed()}
    }
}
