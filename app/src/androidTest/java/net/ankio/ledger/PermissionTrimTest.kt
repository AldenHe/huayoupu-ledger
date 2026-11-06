package net.ankio.ledger

import android.app.NotificationManager
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionTrimTest {
    @Test fun recognitionNeedsNoNotificationsOrOverlayPermission() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val resolver=app.contentResolver
        val services=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val enabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val shake=app.channels.enabled(ChannelSettings.Channel.OCR)
        val automatic=app.channels.automaticEnabled();val packages=app.channels.selectedAutoPackages()
        val prefs=app.getSharedPreferences("background_setup",0);val previous=prefs.all
        fun waitFor(test:()->Boolean) { val until=SystemClock.elapsedRealtime()+7000;while(!test() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(80);assertTrue(test()) }
        fun screenshot(name:String) {
            val bitmap=automation.takeScreenshot()!!
            try { app.openFileOutput(name,0).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { bitmap.recycle() }
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor { ScreenCaptureService.current!=null && app.channels.permitted(ChannelSettings.Channel.OCR) }
            prefs.edit().clear().putBoolean("battery",true).putBoolean("autostart",true).putBoolean("task_lock",true)
                .putBoolean("battery_skipped",true).commit()
            app.channels.set(ChannelSettings.Channel.OCR,true);app.channels.enablePaymentApplication("com.tencent.mm")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                waitFor { app.captureHealth.state().runtimeRunning }
                web.run("go('settings')")
                scenario.onActivity { it.permissionSetup.startAutomatic("com.tencent.mm") }
                web.await("document.querySelector('#detailDialog').open && document.querySelector('#setup-title')?.textContent==='设置已就绪'")
                SystemClock.sleep(1000)
                scenario.onActivity { it.permissionSetup.startAutomatic("com.tencent.mm") }
                web.await("document.querySelector('#detailDialog').open && document.querySelector('#setup-title')?.textContent==='设置已就绪'")
                SystemClock.sleep(1000);screenshot("permission-summary.png")
                web.run("LedgerNative.setupAction('close')")
                web.await("!document.querySelector('#detailDialog').open")
                automation.performGlobalAction(4);SystemClock.sleep(700);screenshot("permission-notifications.png")
                automation.performGlobalAction(1)
                val requested=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
                assertFalse("Recognition does not need an overlay",requested.contains("android.permission.SYSTEM_ALERT_WINDOW"))
                // Native live results optionally use notifications; sensor listening
                // itself still runs without a foreground service or notification.
                assertFalse("No separate foreground service is needed",requested.contains("android.permission.FOREGROUND_SERVICE"))
                assertFalse(requested.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
                assertTrue("Recognition must not post notifications",app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
                scenario.onActivity { it.permissionSetup.startAutomatic("com.tencent.mm") }
                web.await("document.querySelector('#setup-title')?.textContent==='设置已就绪'")
                val summary=web.text("document.querySelector('#detailContent').textContent")
                assertFalse(summary.contains("通知"));assertFalse(summary.contains("胶囊"));assertFalse(summary.contains("屏幕提示"))
                web.run("LedgerNative.setupAction('close')")
                automation.performGlobalAction(2);SystemClock.sleep(1800)
                val sensor=app.shakeRuntime.snapshot()
                assertTrue("Background sensor samples continue without a foreground notification: $sensor",sensor.getLong("lastSampleReceivedAgoMs") in 0..1500)
                assertEquals("ledger-shake-sensor",sensor.getString("deliveryThread"))
                automation.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW deny").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
                val service=ScreenCaptureService.current!!
                service.showQueuedFeedback("正在识别…",true,null)
                waitFor { service.paymentDiagnostics().getJSONObject("feedback").optBoolean("attached") }
                assertFalse(Settings.canDrawOverlays(app))
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,service.paymentDiagnostics().getJSONObject("feedback").getInt("windowType"))
                SystemClock.sleep(700);screenshot("permission-popup.png")
                service.cancelRecognition()
                automation.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default").close()
                assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            }
        } finally {
            app.channels.set(ChannelSettings.Channel.OCR,false);app.channels.setAutomaticEnabled(false)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",services)
            Settings.Secure.putInt(resolver,"accessibility_enabled",enabled)
            app.channels.set(ChannelSettings.Channel.OCR,shake)
            net.ankio.ledger.core.PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in packages) }
            app.channels.setAutomaticEnabled(automatic)
            prefs.edit().clear().commit();val edit=prefs.edit();previous.forEach { (key,value)->if(value is Boolean)edit.putBoolean(key,value) };edit.commit()
            automation.dropShellPermissionIdentity();app.changed()
        }
    }
}
