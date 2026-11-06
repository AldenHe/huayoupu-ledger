package net.ankio.ledger

import android.app.UiAutomation
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShakeSensorLifecycleTest {
    @Test fun screenOffReleasesSensorAndUnlockRestoresOnlyEnabledShake() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue("Sensor recovery writes only to disposable QA settings",app.packageName=="net.ankio.ledger.qa")
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val oldServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val oldEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val oldShake=app.channels.enabled(ChannelSettings.Channel.OCR)
        val oldAuto=app.channels.automaticEnabled();val oldApps=app.channels.selectedAutoPackages()
        fun shell(command:String) { automation.executeShellCommand(command).close() }
        fun await(message:String,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+15_000
            while(!condition() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(50)
            assertTrue("$message; snapshot=${app.shakeRuntime.snapshot()}; freshAt=${app.shakeRuntime.lastFreshSampleAtMs}",condition())
        }
        // Observe the real registration, not a fake sensor implementation.
        fun listening():Boolean=CaptureRuntime.instance?.let { runtime ->
            runtime.javaClass.getDeclaredField("registered").apply { isAccessible=true }.getBoolean(runtime)
        }?:false
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            Settings.Secure.putString(resolver,"enabled_accessibility_services","")
            Settings.Secure.putInt(resolver,"accessibility_enabled",0)
            await("Previous accessibility connection closes") { ScreenCaptureService.current==null }
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            await("Service binds") { ScreenCaptureService.current!=null }
            app.channels.set(ChannelSettings.Channel.OCR,true);CaptureRuntime.refreshHealth()
            await("Enabled shake listens while unlocked") { listening() }
            await("Registration receives fresh real accelerometer samples") { app.shakeRuntime.snapshot().optBoolean("running") }
            val runtime=CaptureRuntime.instance!!
            val recoveriesBefore=runtime.recoveryCount
            // Model an orphaned platform subscription while the app still believes it is registered.
            // Only SensorManager is intervened on: recovery must notice missing actual delivery itself.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // Listener maps belong to the service's SystemSensorManager, not the app context's manager.
                ScreenCaptureService.current!!.getSystemService(android.hardware.SensorManager::class.java).unregisterListener(runtime)
            }
            assertTrue("An old registration flag must not mask a stopped stream",listening())
            SystemClock.sleep(250) // Drain any callback already dispatched before unregisterListener.
            val samplesBefore=app.shakeRuntime.lastFreshSampleAtMs
            SystemClock.sleep(300)
            assertEquals("Fault injection actually stops delivery before recovery",samplesBefore,app.shakeRuntime.lastFreshSampleAtMs)
            await("Stalled stream is re-registered and receives fresh samples") {
                runtime.recoveryCount>recoveriesBefore && app.shakeRuntime.lastFreshSampleAtMs>samplesBefore && app.shakeRuntime.snapshot().optBoolean("running")
            }
            assertTrue(app.captureHealth.snapshot().getBoolean("shakeReady"))
            shell("input keyevent KEYCODE_SLEEP")
            await("Screen turns off") { !app.getSystemService(PowerManager::class.java).isInteractive }
            await("Screen off releases shake sensor") { !listening() }
            assertFalse("Sleeping sensor cannot report ready",app.shakeRuntime.snapshot().getBoolean("running"))
            assertTrue("Automatic runtime remains available",CaptureRuntime.running)
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            await("Unlock restores enabled shake") { listening() }
            await("Unlock restores actual sensor delivery") { app.shakeRuntime.snapshot().getBoolean("running") }
            app.channels.setAutoPackage("com.tencent.mm",true);app.channels.setAutomaticEnabled(true)
            app.channels.set(ChannelSettings.Channel.OCR,false);CaptureRuntime.refreshHealth()
            await("Automatic-only mode has no shake listener") { CaptureRuntime.running && !listening() }
            assertFalse(app.captureHealth.snapshot().getBoolean("shakeReady"))
            shell("input keyevent KEYCODE_SLEEP")
            await("Screen turns off again") { !app.getSystemService(PowerManager::class.java).isInteractive }
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            SystemClock.sleep(300)
            assertFalse("Unlock must not reactivate paused shake",listening())
            assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
        } finally {
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            CaptureRuntime.stopSafely()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",oldServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",oldEnabled)
            app.channels.set(ChannelSettings.Channel.OCR,oldShake)
            net.ankio.ledger.core.PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in oldApps) }
            app.channels.setAutomaticEnabled(oldAuto);CaptureRuntime.refreshHealth()
            automation.dropShellPermissionIdentity()
        }
    }
}
