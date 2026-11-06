package net.ankio.ledger

import android.app.UiAutomation
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.RecognitionBanner
import net.ankio.ledger.capture.ScreenCaptureService
import net.ankio.ledger.capture.captureServiceComponent
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the no-native-carrier path on hardware, using only the isolated QA store. */
@RunWith(AndroidJUnit4::class)
class QuietRecognitionFeedbackTest {
    @Test fun unsupportedProgressStaysQuietAndSuccessOpensOneResultCard() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        assertFalse("Run with QA notification permission disabled",app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
        val phase=InstrumentationRegistry.getArguments().getString("feedbackPhase","after")!!
        val automation=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val services=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val enabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成提示回归 ${UUID.randomUUID()}",now),
            ParsedBill(2250,Direction.EXPENSE,"合成测试商户","餐饮",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",timeExplicit=true)) as IngestResult.Saved).record
        var banner:RecognitionBanner?=null
        fun await(check:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+8_000
            while(!check() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(25)
            assertTrue("Feedback condition did not settle",check())
        }
        fun capture(state:String) {
            SystemClock.sleep(550)
            val shot=automation.takeScreenshot()!!
            try {app.openFileOutput("feedback-$phase-$state.png",0).use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {shot.recycle()}
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            val component=captureServiceComponent(app).flattenToString()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",(services.orEmpty().split(':').filter {it.isNotEmpty()}+component).distinct().joinToString(":"))
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            await {ScreenCaptureService.current!=null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                web.await("document.querySelector('#settings').classList.contains('mobile-active')")
                SystemClock.sleep(600)
                web.run("notify('合成提示样式测试')")
                capture("web-hint")
                web.run("document.querySelector('.toast').classList.remove('show')")
                inst.runOnMainSync {
                    val getter=ScreenCaptureService::class.java.getDeclaredMethod("getBanner").apply {isAccessible=true}
                    banner=getter.invoke(ScreenCaptureService.current!!) as RecognitionBanner
                    banner!!.show("正在识别…",loading=true)
                }
                inst.waitForIdleSync();capture("progress")
                if(phase!="before")assertFalse("Unsupported progress must not attach a green overlay",banner!!.diagnostics().getBoolean("attached"))
                inst.runOnMainSync {banner!!.show("已记账 ¥ 22.50",recordId=record.id)}
                await {banner!!.diagnostics().getBoolean("attached")}
                capture("success")
                val card=RecognitionBanner::class.java.getDeclaredField("cardView").apply {isAccessible=true}.get(banner)
                assertNotNull("A saved record must open its full result card",card)
                var repaired=false
                inst.runOnMainSync {banner!!.show("请开启页面识别权限",onRepair={repaired=true})}
                inst.waitForIdleSync();capture("repair")
                val overlay=RecognitionBanner::class.java.getDeclaredField("view").apply {isAccessible=true}
                inst.runOnMainSync {(overlay.get(banner) as android.view.View).performClick()}
                assertTrue("Permission repair remains actionable",repaired)
                if(phase!="before") {
                    inst.runOnMainSync {banner!!.show("已记账 ¥ 22.50",recordId=Long.MAX_VALUE)}
                    inst.waitForIdleSync()
                    assertFalse("Missing result identities must not revive the old success capsule",banner!!.diagnostics().getBoolean("attached"))
                    inst.runOnMainSync {banner!!.show("未识别到金额，请重试")}
                    inst.waitForIdleSync()
                    assertTrue("Failures still explain how to recover",banner!!.diagnostics().getBoolean("attached"))
                    capture("failure")
                    inst.runOnMainSync {banner!!.show("正在识别…",loading=true);banner!!.close()}
                    inst.waitForIdleSync();SystemClock.sleep(350)
                    assertFalse("Cancelled feedback cannot revive a progress fallback",banner!!.diagnostics().getBoolean("attached"))
                }
            }
        } finally {
            inst.runOnMainSync {banner?.removeNow()}
            app.store.delete(record.id)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",services)
            Settings.Secure.putInt(resolver,"accessibility_enabled",enabled)
            automation.dropShellPermissionIdentity()
        }
    }
}
