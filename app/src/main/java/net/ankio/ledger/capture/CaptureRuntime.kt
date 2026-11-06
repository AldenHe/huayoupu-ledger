package net.ankio.ledger.capture

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.*
import android.os.*
import net.ankio.ledger.core.ShakeDetector
import net.ankio.ledger.ledger

/** Sensor listening shares the system-bound accessibility connection; no second service. */
class CaptureRuntime private constructor(private val service:ScreenCaptureService):SensorEventListener,AutoCloseable {
    private val sensors=service.getSystemService(SensorManager::class.java)
    private var detector=ShakeDetector()
    private val mainHandler=Handler(Looper.getMainLooper())
    private var sensorThread:HandlerThread?=null
    @Volatile private var registered=false
    @Volatile private var registeredAt=-1L
    @Volatile private var retryDelayMs=HEALTH_INTERVAL
    @Volatile private var retryAt=0L
    internal val sensorRegistered get()=registered
    @Volatile internal var recoveryCount=0;private set
    @Volatile internal var lastRecoveryAt=0L;private set
    private val sensorHealth=object:Runnable {
        override fun run() {
            if(instance!==this@CaptureRuntime)return
            if(!service.ledger.channels.captureEnabled() || !service.ledger.channels.permitted(ChannelSettings.Channel.OCR)) {
                refreshReadiness();return
            }
            updateSensor()
        }
    }
    private val permissionObserver=object:android.database.ContentObserver(mainHandler) {
        override fun onChange(selfChange:Boolean) { refreshReadiness() }
    }
    private val screenReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==Intent.ACTION_SCREEN_OFF)stopSensor()
            else refreshReadiness()
        }
    }
    init {
        service.contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor("enabled_accessibility_services"),false,permissionObserver)
        val filter=IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)
        }
        if(Build.VERSION.SDK_INT>=33)service.registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else service.registerReceiver(screenReceiver,filter)
    }
    private fun refreshReadiness() {
        if(instance!==this || ScreenCaptureService.current!==service)return
        val app=service.ledger
        app.captureHealth.suspendForMissingPermission()
        if(!app.channels.captureEnabled() || !app.channels.permitted(ChannelSettings.Channel.OCR)) { stop();return }
        updateSensor()
        val wasRunning=running
        running=app.channels.captureEnabled()
        if(!running)stop()
        else {
            if(!wasRunning)app.executionLog.event("system","capture_service","accessibility_runtime_started")
            app.captureHealth.clearNotice();service.checkPaymentPages();app.captureQueue.resume()
        }
        app.changed()
    }
    private fun awake()=service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    /** A successful registration is not proof that continuous sensor events still arrive. */
    private fun updateSensor() {
        mainHandler.removeCallbacks(sensorHealth)
        val app=service.ledger
        if(!app.channels.enabled(ChannelSettings.Channel.OCR) || !awake()) { stopSensor();return }
        val now=SystemClock.elapsedRealtime()
        val freshAt=app.shakeRuntime.lastFreshSampleAtMs
        val stalled=registered && now-maxOf(registeredAt,freshAt)>=HEALTH_INTERVAL
        if((!registered || stalled) && now>=retryAt) {
            val recovery=registered || registeredAt>=0
            stopSensor(resetRetry=false)
            registeredAt=now
            retryAt=now+retryDelayMs
            retryDelayMs=(retryDelayMs*2).coerceAtMost(MAX_RETRY_INTERVAL)
            val sensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if(sensor!=null) {
                val thread=HandlerThread("ledger-shake-sensor").also { it.start();sensorThread=it }
                registered=sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME,0,Handler(thread.looper))
                if(registered)app.shakeRuntime.started() else {thread.quitSafely();sensorThread=null}
            }
            if(recovery) {
                recoveryCount++;lastRecoveryAt=System.currentTimeMillis()
                app.executionLog.event("ocr","sensor","listener_recovery",app.shakeRuntime.snapshot())
            }
            app.changed()
        }
        mainHandler.postDelayed(sensorHealth,HEALTH_INTERVAL)
    }
    private fun sensorState():String {
        val app=service.ledger
        if(!app.channels.enabled(ChannelSettings.Channel.OCR))return "disabled"
        if(!service.getSystemService(PowerManager::class.java).isInteractive)return "screen_off"
        if(service.getSystemService(KeyguardManager::class.java).isKeyguardLocked)return "locked"
        if(!registered)return if(registeredAt<0)"starting" else "unavailable"
        val freshAt=app.shakeRuntime.lastFreshSampleAtMs
        if(freshAt>=registeredAt && SystemClock.elapsedRealtime()-freshAt<HEALTH_INTERVAL)return "ready"
        if(SystemClock.elapsedRealtime()-registeredAt>=HEALTH_INTERVAL)return "stalled"
        return if(recoveryCount>0)"recovering" else "starting"
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(!running || !registered)return
        val at=event.timestamp/1_000_000
        val received=SystemClock.elapsedRealtime()
        if(at<registeredAt)return
        val firstFreshSample=service.ledger.shakeRuntime.lastFreshSampleAtMs<registeredAt && received-at in 0..1500L
        service.ledger.shakeRuntime.sample(at,received)
        if(received-at in 0..1500L) {retryDelayMs=HEALTH_INTERVAL;retryAt=0}
        if(firstFreshSample)service.ledger.changed()
        if(!service.getSystemService(PowerManager::class.java).isInteractive || service.getSystemService(KeyguardManager::class.java).isKeyguardLocked)return
        if(detector.sample(at,event.values[0],event.values[1],event.values[2],received)) {
            service.ledger.shakeRuntime.trigger(received-at)
            processShakeTrigger(ShakeTrigger(at,received))
        }
    }
    internal fun processShakeTrigger(trigger:ShakeTrigger) {
        if(!running || !registered || !service.ledger.channels.enabled(ChannelSettings.Channel.OCR) || !trigger.freshAt(SystemClock.elapsedRealtime()))return
        service.ledger.executionLog.event("ocr","shake","received",service.ledger.shakeRuntime.snapshot())
        service.captureOnShake(trigger)
    }
    private fun stopSensor(resetRetry:Boolean=true) {
        mainHandler.removeCallbacks(sensorHealth)
        registered=false;sensors.unregisterListener(this);sensorThread?.quitSafely();sensorThread=null
        detector=ShakeDetector()
        if(resetRetry) {registeredAt=-1;retryAt=0;retryDelayMs=HEALTH_INTERVAL}
    }
    private fun stop() {
        val wasRunning=running
        running=false;stopSensor()
        if(wasRunning) { service.captureServiceStopped();service.ledger.changed() }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    override fun close() {
        stop();service.contentResolver.unregisterContentObserver(permissionObserver);service.unregisterReceiver(screenReceiver)
        mainHandler.removeCallbacksAndMessages(null)
        if(instance===this)instance=null
    }
    companion object {
        private const val HEALTH_INTERVAL=5_000L
        private const val MAX_RETRY_INTERVAL=60_000L
        @Volatile var running=false;private set
        @Volatile internal var instance:CaptureRuntime?=null;private set
        internal fun sensorStatus()=instance?.sensorState()?:"disconnected"
        internal fun attach(service:ScreenCaptureService) {
            instance?.close();instance=CaptureRuntime(service);refreshHealth()
        }
        internal fun detach(service:ScreenCaptureService) { instance?.takeIf { it.service===service }?.close() }
        fun refreshHealth() { instance?.let { runtime->runtime.mainHandler.post { runtime.refreshReadiness() } } }
        fun stopSafely() { instance?.let { runtime->runtime.mainHandler.post { if(instance===runtime)runtime.stop() } } }
    }
}
