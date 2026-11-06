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
class CompactHomeUiTest {
    @Test fun captureCompactSurfaces() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        assumeTrue(InstrumentationRegistry.getArguments().getString("evidence")=="true")
        val app=context.applicationContext as LedgerApp
        val ids=mutableListOf<Long>();val budget=MonthlyBudget(context);val savedBudget=budget.cents()
        val directory=File(context.filesDir,"compact-evidence").apply {mkdirs()}
        try {
            listOf("上海公共交通卡股份有限公司","午间工作餐","城市书店","日常采购","咖啡与茶").forEachIndexed { i,name ->
                val at=System.currentTimeMillis()-i*60_000
                ids+=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"qa-compact","",at),
                    bill=ParsedBill(listOf(600L,3800L,5800L,2390L,1800L)[i],Direction.EXPENSE,name,listOf("交通","餐饮","教育","日用","餐饮")[i],at,null,true,"",true),
                    state=RecordState.POSTED,tags=if(i==0)listOf("差旅","项目A") else emptyList())).id
            }
            budget.set(0);app.changed()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui=WebUiProbe(scenario);ui.ready()
                fun capture(name:String){
                    ui.await("!document.querySelector('.fluid-pending,.fluid-active')")
                    val painted=java.util.concurrent.CountDownLatch(1)
                    scenario.onActivity { a -> a.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){override fun onComplete(id:Long){a.webView.invalidate();painted.countDown()}}) }
                    check(painted.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    val point=org.json.JSONObject(ui.text("JSON.stringify((()=>{const e=document.querySelector('#sheet[open] #sheet-title')||document.querySelector('.screen.mobile-active h1'),r=e.getBoundingClientRect();return{x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})())"))
                    val offset=IntArray(2);scenario.onActivity {it.webView.getLocationOnScreen(offset)}
                    inst.uiAutomation.executeShellCommand("input tap ${point.getInt("x")+offset[0]} ${point.getInt("y")+offset[1]}").close();SystemClock.sleep(1200)
                    ui.await("!document.querySelector('.fluid-pending,.fluid-active')")
                    inst.uiAutomation.executeShellCommand("screencap -p").use {p -> android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use {input -> File(directory,"$name.png").outputStream().use {input.copyTo(it)}}}
                    File(directory,"$name.json").writeText(ui.text("JSON.stringify((()=>{const out={viewport:[innerWidth,innerHeight]};['.balance','.transaction','.category-icon','#monthly-budget','#settings>.shake','#report-filters','#report-filter-panel'].forEach(s=>{const e=document.querySelector(s);if(e)out[s]=e.getBoundingClientRect().toJSON()});return out})())"))
                }
                ui.run("closeSheet(false);go('ledger');refresh()");capture("home-unset")
                budget.set(200000);app.changed();ui.run("refresh()");capture("home-budget")
                ui.run("go('settings')");capture("settings")
                ui.run("BillReportUI.open()");capture("report-form")
                ui.run("document.querySelector('#report-filters').click()");capture("report-filters")
                if(ui.value("!!document.querySelector('[data-filter-group=category]')")=="true"){
                    ui.run("document.querySelector('[data-filter-group=category]').click()");capture("report-category")
                    ui.run("document.querySelector('[data-filter-key=category][data-filter-value=交通]').click()");capture("report-selected")
                    ui.run("document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click();document.querySelector('[data-report-tag=差旅]').click()")
                    assertEquals("false",ui.value("document.querySelector('#report-filter-panel').hidden"))
                    ui.run("document.querySelector('[data-report-tag=项目A]').click();document.querySelector('[data-filter-key=matchAll][data-filter-value=all]').click();document.querySelector('.report-scroll').scrollTop=document.querySelector('.report-scroll').scrollHeight");capture("report-tags")
                    assertEquals("false",ui.value("document.querySelector('#report-filter-panel').hidden"))
                    assertEquals("true",ui.value("BillReportUI.state().criteria.matchAll&&BillReportUI.state().criteria.tags.length===2"))
                    ui.run("BillReportUI.back();document.querySelector('#report-form').requestSubmit()");ui.await("!!document.querySelector('#report-receipt')");ui.run("document.querySelector('#report-skip').click()");capture("report-receipt")
                    scenario.onActivity {it.webView.settings.textZoom=150}
                    ui.run("LedgerUI.event('fontScale',1.5);closeSheet(false);go('ledger')");capture("home-large")
                    ui.run("go('settings')");capture("settings-large")
                    ui.run("document.querySelector('#settings').scrollTop=document.querySelector('#settings').scrollHeight");capture("settings-large-bottom")
                    ui.run("BillReportUI.open();document.querySelector('#report-filters').click();document.querySelector('[data-filter-group=tags]').click()");capture("report-tags-large")
                    ui.run("document.querySelector('.report-scroll').scrollTop=document.querySelector('.report-scroll').scrollHeight;document.querySelector('#report-filter-values').scrollTop=document.querySelector('#report-filter-values').scrollHeight");capture("report-tags-large-bottom")
                    assertEquals("true",ui.value("document.documentElement.scrollWidth<=innerWidth&&document.querySelector('#report-generate').getBoundingClientRect().bottom<=innerHeight"))
                }
            }
        } finally {ids.forEach(app.store::delete);budget.set(savedBudget);app.changed()}
    }
}
