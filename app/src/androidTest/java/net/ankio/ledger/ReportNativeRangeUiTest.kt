package net.ankio.ledger

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReportNativeRangeUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun capture(name:String,ui:WebUiProbe,scenario:ActivityScenario<MainActivity>,native:Boolean=false) {
        val drawn=java.util.concurrent.CountDownLatch(1)
        scenario.onActivity {activity->
            activity.webView.postVisualStateCallback(0,object:android.webkit.WebView.VisualStateCallback(){
                override fun onComplete(requestId:Long){activity.webView.postOnAnimation {activity.webView.postOnAnimation {drawn.countDown()}}}
            })
        }
        assertTrue("The requested frame must be drawn before capture",drawn.await(10,java.util.concurrent.TimeUnit.SECONDS))
        if(!native)tap(ui,scenario,"#sheet-title")
        SystemClock.sleep(900)
        val directory=File(instrument.targetContext.filesDir,"native-range-evidence").apply {mkdirs()}
        instrument.uiAutomation.executeShellCommand("screencap -p").use {descriptor->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input->File(directory,"$name.png").outputStream().use(input::copyTo)}
        }
    }
    private fun tap(ui:WebUiProbe,scenario:ActivityScenario<MainActivity>,selector:String) {
        val point=org.json.JSONObject(ui.text("JSON.stringify((()=>{const r=document.querySelector('$selector').getBoundingClientRect();return {x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})())"))
        val offset=IntArray(2);scenario.onActivity {it.webView.getLocationOnScreen(offset)}
        instrument.uiAutomation.executeShellCommand("input tap ${point.getInt("x")+offset[0]} ${point.getInt("y")+offset[1]}").use {descriptor->ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}}
        SystemClock.sleep(550)
    }
    private fun back(){instrument.uiAutomation.executeShellCommand("input keyevent 4").use {descriptor->ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}};SystemClock.sleep(400)}
    @Test fun bothEndpointsOpenChineseNativeDateOnlyAndCrossingDatesStayLinked() {
        assumeTrue(instrument.targetContext.packageName=="net.ankio.ledger.qa")
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("closeSheet(false);go('settings');BillReportUI.open()")
            ui.await("!!document.querySelector('#report-from')")
            val diagnostics=ui.text("JSON.stringify((()=>{const r=e=>e.getBoundingClientRect().toJSON();return{box:r(document.querySelector('#report-date-range')),from:r(document.querySelector('#report-from-value')),through:r(document.querySelector('#report-through-value')),line:r(document.querySelector('.report-date-link')),style:getComputedStyle(document.querySelector('.report-date-link')).borderTopStyle}})())")
            File(instrument.targetContext.filesDir,"date-geometry.json").writeText(diagnostics)
            capture("report-form",ui,scenario)
            assertEquals(diagnostics,"true",ui.value("(()=>{const box=document.querySelector('#report-date-range').getBoundingClientRect(),a=document.querySelector('#report-from-value').getBoundingClientRect(),b=document.querySelector('#report-through-value').getBoundingClientRect(),line=document.querySelector('.report-date-link'),r=line.getBoundingClientRect();return box.height<76 && r.width>=20 && r.x>a.right && r.right<b.x && Math.abs(r.y-(a.y+a.height/2))<4 && getComputedStyle(line).borderTopStyle==='solid'})()"))
            val original=ui.text("JSON.stringify(BillReportUI.state().criteria)")
            for(key in listOf("from","through")) {
                assertEquals("date",ui.text("document.querySelector('#report-$key').type"))
                tap(ui,scenario,"#report-$key")
                capture("report-$key-native",ui,scenario,native=true)
                val root=instrument.uiAutomation.rootInActiveWindow
                fun countPickers(node:android.view.accessibility.AccessibilityNodeInfo):Int =
                    (if(node.className=="android.widget.NumberPicker")1 else 0)+(0 until node.childCount).sumOf { index -> node.getChild(index)?.let(::countPickers)?:0 }
                assertEquals("Native date has year, month and day wheels",3,countPickers(root))
                assertTrue("Chinese native positive button expected",root.findAccessibilityNodeInfosByText("设置").isNotEmpty())
                assertTrue("Date range must not ask for a time",root.findAccessibilityNodeInfosByText("设置日期和时间").isEmpty())
                back()
                assertEquals(original,ui.text("JSON.stringify(BillReportUI.state().criteria)"))
            }
            ui.run("document.querySelector('#report-through').value='2024-02-29';document.querySelector('#report-through').dispatchEvent(new Event('change'))")
            assertEquals("2024-02-29",ui.text("BillReportUI.state().criteria.from"));assertEquals("2024-02-29",ui.text("BillReportUI.state().criteria.through"))
            ui.run("document.querySelector('#report-from').value='2025-01-01';document.querySelector('#report-from').dispatchEvent(new Event('change'))")
            assertEquals("2025-01-01",ui.text("BillReportUI.state().criteria.through"))
            scenario.recreate();ui.ready();ui.await("!!document.querySelector('#report-form')")
            assertEquals("2025-01-01",ui.text("document.querySelector('#report-from').value"));assertEquals("2025-01-01",ui.text("document.querySelector('#report-through').value"))
            ui.run("document.querySelector('#report-filters').click()")
            ui.await("!document.querySelector('#report-filter-panel').hidden");capture("report-filters-glass",ui,scenario)
            ui.run("BillReportUI.back();closeSheet(false)")
        }
    }
}
