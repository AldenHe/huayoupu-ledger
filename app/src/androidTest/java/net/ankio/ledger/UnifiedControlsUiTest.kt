package net.ankio.ledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.ui.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UnifiedControlsUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun guard()=assumeTrue(instrument.targetContext.packageName=="net.ankio.ledger.qa")
    private fun capture(name:String) {
        // Capture the actual Android window, including native controls and dialogs.
        SystemClock.sleep(1200)
        val drawn=java.util.concurrent.CountDownLatch(1)
        instrument.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
            activity.webView.postVisualStateCallback(0,object:android.webkit.WebView.VisualStateCallback() {
                override fun onComplete(requestId:Long) {
                    activity.webView.postOnAnimation {activity.webView.postOnAnimation {drawn.countDown()}}
                }
            })
        }
        assertTrue("The requested WebView frame must be drawn before capture",drawn.await(10,java.util.concurrent.TimeUnit.SECONDS))
        val directory=File(instrument.targetContext.filesDir,"unified-controls-evidence").apply {mkdirs()}
        instrument.uiAutomation.executeShellCommand("screencap -p").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                File(directory,"${InstrumentationRegistry.getArguments().getString("capturePrefix").orEmpty()}$name.png").outputStream().use {input.copyTo(it)}
            }
        }
    }
    private fun tap(ui:WebUiProbe,scenario:ActivityScenario<MainActivity>,selector:String) {
        ui.await("!document.querySelector('.fluid-pending,.fluid-active') && !document.getElementById('sheet')._morph")
        ui.run("document.querySelector('$selector').scrollIntoView({block:'center'})")
        val point=org.json.JSONObject(ui.text("JSON.stringify((()=>{const r=document.querySelector('$selector').getBoundingClientRect();return {x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})())"))
        val offset=IntArray(2);scenario.onActivity {it.webView.getLocationOnScreen(offset)}
        instrument.uiAutomation.executeShellCommand("input tap ${point.getInt("x")+offset[0]} ${point.getInt("y")+offset[1]}").close()
        SystemClock.sleep(500)
    }
    private fun back() {instrument.uiAutomation.executeShellCommand("input keyevent 4").close();SystemClock.sleep(350)}
    private fun nativeText(text:String):android.view.accessibility.AccessibilityNodeInfo {
        val deadline=SystemClock.elapsedRealtime()+5000
        do {
            val node=instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull {it.text?.toString()==text}
            if(node!=null)return node
            SystemClock.sleep(50)
        }while(SystemClock.elapsedRealtime()<deadline)
        error("Native control missing: $text")
    }
    @Test fun nativeMonthAndTimePreserveCancelAndCommitOnlyOnApply() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("closeSheet(false);month='2026-11';filter='expense';go('ledger');refresh();document.querySelector('#month').click()")
            tap(ui,scenario,"#month-picker");capture("after-month-native")
            assertTrue("Real system picker expected",instrument.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("设置月份").isNotEmpty())
            back();assertEquals("2026-11",ui.text("document.querySelector('#month-picker').value"))
            tap(ui,scenario,"#month-picker");assertTrue(nativeText("设置").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            ui.await("document.querySelector('#month-picker').value==='2026-11'")
            assertEquals("2026-11",ui.text("month"))
            ui.run("document.querySelector('#month-picker').value='2000-01';document.querySelector('#month-prev').click()")
            assertEquals("2000-01",ui.text("document.querySelector('#month-picker').value"))
            ui.run("document.querySelector('#month-picker').value='2100-12';document.querySelector('#month-next').click()")
            assertEquals("2100-12",ui.text("document.querySelector('#month-picker').value"))
            ui.run("document.querySelector('#month-picker').value='2024-02';LedgerUI.back();closeSheet(false)")
            assertEquals("2026-11",ui.text("month"))
            ui.run("document.querySelector('#month').click();document.querySelector('#month-picker').value='2024-02';document.querySelector('#month-apply').click()")
            assertEquals("2024-02",ui.text("month"))
            ui.run("newDraft();go('import');document.querySelector('#occurred-at').value='2026-11-05T05:32';document.querySelector('#time-label').click()")
            tap(ui,scenario,"#time-picker");capture("after-time-native")
            assertTrue(nativeText("设置日期和时间").isVisibleToUser)
            for(label in listOf("清除","取消","设置"))assertTrue(nativeText(label).isVisibleToUser)
            assertTrue("English month names must not leak into the native chooser",instrument.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Nov").isEmpty())
            back()
            assertEquals("2026-11-05T05:32",ui.text("document.querySelector('#occurred-at').value"))
            ui.run("document.querySelector('#time-picker').value='';document.querySelector('#time-apply').click()")
            assertEquals("true",ui.value("document.querySelector('#sheet').open"))
            ui.run("document.querySelector('#time-picker').value='2024-02-29T07:09';document.querySelector('#time-apply').click()")
            assertEquals("2024-02-29T07:09",ui.text("document.querySelector('#occurred-at').value"))
            ui.run("document.querySelector('#time-label').click();document.querySelector('#time-picker').value='2025-01-01T12:30';LedgerUI.back();closeSheet(false)")
            assertEquals("2024-02-29T07:09",ui.text("document.querySelector('#occurred-at').value"))
            ui.run("month='2026-11';cancelEditor(true);refresh()")
        }
    }
    @Test fun nativeDateTimeStaysChineseOnEnglishSystemAfterRecreation() {
        guard()
        fun systemConfiguration()=instrument.uiAutomation.executeShellCommand("am get-config").use {descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {it.readText()}
        }
        val before=systemConfiguration()
        assumeTrue("This acceptance case requires an English system",before.contains("-en-rUS-"))
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready()
            repeat(2) {iteration ->
                scenario.onActivity {activity ->
                    assertEquals("zh",activity.resources.configuration.locales[0].language)
                    assertEquals("zh",java.util.Locale.getDefault().language)
                }
                ui.run("closeSheet(false);newDraft();go('import');document.querySelector('#occurred-at').value='2026-11-05T05:32';document.querySelector('#time-label').click()")
                tap(ui,scenario,"#time-picker")
                assertTrue(nativeText("设置日期和时间").isVisibleToUser)
                assertTrue(nativeText("上午").isVisibleToUser)
                assertTrue(nativeText("取消").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                assertEquals("2026-11-05T05:32",ui.text("document.querySelector('#occurred-at').value"))
                ui.run("closeSheet(false);cancelEditor(true)")
                if(iteration==0){scenario.recreate();ui.ready()}
            }
        }
        assertEquals("App localization must not modify the system configuration",before,systemConfiguration())
    }
    @Test fun nativeAccountChoicesPreserveNamesAndBackClosesOnlyChooser() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('assets');editAccount()")
            assertEquals("4",ui.value("document.querySelectorAll('[data-account-kind]').length"))
            tap(ui,scenario,"[data-account-kind=bank]");capture("after-account-native")
            ui.await("document.querySelector('#account-kind').value==='bank'")
            assertEquals("false",ui.value("document.querySelector('#account-bank-field').hidden"))
            ui.run("document.querySelector('#account-name').value='工资卡'")
            tap(ui,scenario,"#account-bank");capture("after-bank-native");back()
            assertEquals("工资卡",ui.text("document.querySelector('#account-name').value"))
            tap(ui,scenario,"#account-type");back()
            assertEquals("true",ui.value("document.querySelector('#sheet').open"))
            assertEquals("ASSET",ui.text("document.querySelector('#account-type').value"))
            tap(ui,scenario,"#account-type");assertTrue(nativeText("信用卡").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            ui.await("document.querySelector('#account-type').value==='LIABILITY'")
            ui.run("closeSheet(false)")
        }
    }
    @Test fun nativePickersRemainUsableWithLargeTextAndDarkSystem() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready()
            scenario.onActivity {it.webView.settings.textZoom=150}
            ui.run("document.body.classList.add('large-text');newDraft();go('import');document.querySelector('#occurred-at').value='2026-11-05T05:32';document.querySelector('#time-label').click()")
            tap(ui,scenario,"#time-picker");capture("after-time-large-native");back()
            ui.run("closeSheet(false);go('assets');editAccount()")
            tap(ui,scenario,"[data-account-kind=bank]");tap(ui,scenario,"#account-bank");capture("after-account-large-native");back()
            assertEquals("true",ui.value("document.querySelector('#sheet').scrollWidth<=document.querySelector('#sheet').clientWidth+1"))
            ui.run("closeSheet(false);document.body.classList.remove('large-text')")
            scenario.onActivity {it.webView.settings.textZoom=100}
        }
    }
    @Test fun restoreConfirmationCanBeCancelledWithoutOpeningBackup() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('settings')")
            SystemClock.sleep(500)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply {isAccessible=true}
                    .invoke(activity,MainActivity.RESTORE_REQUEST,Activity.RESULT_OK,Intent().setData(Uri.parse("content://qa-unopened-backup/fixture")))
            }
            capture("after-restore")
            // The dialog text is checked through accessibility, not a fabricated WebView preview.
            val root=instrument.uiAutomation.rootInActiveWindow
            assertTrue("Restore dialog must be visible",root.findAccessibilityNodeInfosByText("恢复本地备份？").isNotEmpty())
            root.findAccessibilityNodeInfosByText("取消").first {it.isClickable}.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(300)
            assertTrue(instrument.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("恢复本地备份？").isEmpty())
        }
    }
    @Test fun exportChoicesUseTheSelectedFormat() {
        guard()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('settings');showBillExport()")
            capture("after-export")
            run {
                ui.run("document.querySelector('input[value=\"qianji\"]').click()")
                assertEquals("qianji",ui.text("document.querySelector('.export-format.is-selected input').value"))
            }
            ui.run("closeSheet(false)")
        }
    }
    @Test fun captureMainSurfaces() {
        guard()
        val prefix=if(InstrumentationRegistry.getArguments().getString("neutralBefore")=="true")"before-white" else "after-preserved"
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val ui=WebUiProbe(scenario);ui.ready()
            for(page in listOf("ledger","stats","assets","settings")) {
                ui.run("closeSheet(false);go('$page')")
                ui.await("document.querySelector('.screen.mobile-active').id==='$page'")
                capture("$prefix-$page")
                assertEquals(page,ui.text("document.querySelector('.screen.mobile-active').id"))
            }
        }
    }
}
