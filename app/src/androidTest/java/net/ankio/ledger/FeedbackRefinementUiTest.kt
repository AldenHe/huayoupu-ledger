package net.ankio.ledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.ui.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FeedbackRefinementUiTest {
    @Test fun keyboardFocusAndPrivacyDisclosureRemainUsable() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("BillReportUI.open()")
            ui.await("!!document.querySelector('#report-from')&&!document.querySelector('.fluid-pending,.fluid-active')")
            scenario.onActivity {it.webView.requestFocus()}
            ui.run("document.querySelector('#report-from').focus()")
            assertEquals("none",ui.text("getComputedStyle(document.querySelector('#report-from').parentElement).outlineStyle"))
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            ui.await("document.querySelector('#report-date-range').classList.contains('keyboard-focus')")
            assertEquals("solid",ui.text("getComputedStyle(document.activeElement.parentElement).outlineStyle"))
            assertEquals("report-through",ui.text("document.activeElement.id"))
            ui.run("closeSheet(false);go('settings')")
            scenario.onActivity {it.privacy()}
            var detail:android.widget.TextView?=null
            var control:android.widget.Button?=null
            fun inspect(view:android.view.View) {
                if(view is android.widget.Button&&view.text.toString()=="查看完整说明")control=view
                if(view is android.widget.TextView&&view.text.startsWith("账单、账户、分类、备注"))detail=view
                if(view is android.view.ViewGroup)(0 until view.childCount).forEach {inspect(view.getChildAt(it))}
            }
            scenario.onActivity {inspect(it.pageHost)}
            assertNotNull(detail);assertNotNull(control)
            assertEquals(android.view.View.GONE,detail!!.visibility)
            scenario.onActivity {control!!.performClick()}
            assertEquals(android.view.View.VISIBLE,detail!!.visibility)
            assertTrue(detail!!.text.contains("未关联的成功任务截图默认保留30天"))
            assertEquals("收起完整说明",control!!.text.toString())
            scenario.onActivity {control!!.performClick()}
            assertEquals(android.view.View.GONE,detail!!.visibility)
        }
    }
    @Test fun captureFeedbackSurfaces() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        assumeTrue(InstrumentationRegistry.getArguments().getString("evidence")=="true")
        val directory=File(context.filesDir,"feedback-evidence").apply {mkdirs()}
        fun capture(name:String) {
            SystemClock.sleep(900)
            inst.uiAutomation.executeShellCommand("screencap -p").use {p -> android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use {input -> File(directory,"$name.png").outputStream().use {input.copyTo(it)}}}
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);ui.ready()
            fun paint() {
                val painted=java.util.concurrent.CountDownLatch(1)
                scenario.onActivity {a -> a.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){override fun onComplete(id:Long){a.webView.invalidate();painted.countDown()}})}
                check(painted.await(5,java.util.concurrent.TimeUnit.SECONDS))
            }
            ui.run("BillReportUI.open()")
            ui.await("!!document.querySelector('.report-date-endpoint input')&&!document.querySelector('.fluid-pending,.fluid-active')")
            paint()
            val point=org.json.JSONObject(ui.text("JSON.stringify((()=>{const r=document.querySelector('#sheet-title').getBoundingClientRect();return{x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})())"))
            val offset=IntArray(2);scenario.onActivity {it.webView.getLocationOnScreen(offset)}
            inst.uiAutomation.executeShellCommand("input tap ${point.getInt("x")+offset[0]} ${point.getInt("y")+offset[1]}").close()
            val datePoint=org.json.JSONObject(ui.text("JSON.stringify((()=>{const r=document.querySelector('#report-from').getBoundingClientRect();return{x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})())"))
            inst.uiAutomation.executeShellCommand("input tap ${datePoint.getInt("x")+offset[0]} ${datePoint.getInt("y")+offset[1]}").close()
            SystemClock.sleep(550)
            val confirm=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("设置").firstOrNull {it.isClickable}
            check(confirm!=null&&confirm.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){"Native date confirmation must be available"}
            SystemClock.sleep(550)
            scenario.onActivity {it.webView.requestFocus()}
            ui.run("document.querySelector('#report-from').focus()")
            paint()
            File(directory,"date-state.json").writeText(ui.text("JSON.stringify({active:document.activeElement.id,focus:document.querySelector('.report-date-endpoint').matches(':focus-within'),outline:getComputedStyle(document.querySelector('.report-date-endpoint')).outlineStyle})"))
            capture("date-focus")
            ui.run("closeSheet(false);go('settings')")
            ui.await("!document.querySelector('.fluid-pending,.fluid-active')")
            paint()
            scenario.onActivity {it.privacy()}
            val until=SystemClock.elapsedRealtime()+5000
            var visible=false
            while(!visible&&SystemClock.elapsedRealtime()<until) {
                scenario.onActivity {a -> visible=a.pageHost.findViewWithTag<android.view.View>("privacy-page")?.let {it.isShown&&it.alpha>=.999f&&!it.clipToOutline}==true}
                SystemClock.sleep(50)
            }
            check(visible){"Privacy page did not finish opening"}
            val heading=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("数据与隐私").firstOrNull()
            check(heading!=null){"Privacy heading must be exposed"}
            val bounds=android.graphics.Rect();heading.getBoundsInScreen(bounds)
            inst.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").close()
            capture("privacy")
        }
        val drawable=context.getDrawable(R.drawable.huayoupu_notification_icon)!!
        val bitmap=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0,0,128,128);drawable.draw(android.graphics.Canvas(bitmap))
        File(directory,"notification-icon.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
