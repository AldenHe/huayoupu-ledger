package net.ankio.ledger.capture

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import net.ankio.ledger.ledger

/** Timing and system flags only. No motion vectors, bill data or screen content. */
class ShakeRuntime(private val context:Context) {
    private val preferences=context.getSharedPreferences("shake_runtime",Context.MODE_PRIVATE)
    @Volatile private var lastSampleAgeMs=-1L
    @Volatile private var lastSampleAtMs=-1L
    @Volatile internal var lastFreshSampleAtMs=-1L;private set
    @Volatile private var deliveryThread="not_received"
    @Volatile private var lastPersistedDelayAtMs=-3000L
    fun started() { preferences.edit().putLong("startedAt",System.currentTimeMillis()).putString("sensorThread","dedicated").apply() }
    fun sample(eventAtMs:Long,receivedAtMs:Long) {
        val age=receivedAtMs-eventAtMs
        lastSampleAgeMs=age;lastSampleAtMs=receivedAtMs
        if(age in 0..1500L)lastFreshSampleAtMs=receivedAtMs
        deliveryThread=Thread.currentThread().name
        if(age !in 0..1500L && receivedAtMs-lastPersistedDelayAtMs>=3000) {
            lastPersistedDelayAtMs=receivedAtMs
            preferences.edit().putLong("lastDelayedSampleAgeMs",age).putLong("lastDelayedSampleAt",System.currentTimeMillis()).apply()
        }
    }
    fun trigger(ageMs:Long) { preferences.edit().putLong("lastTriggerAgeMs",ageMs).putLong("lastTriggerAt",System.currentTimeMillis()).apply() }
    fun statusMessage()=when(CaptureRuntime.sensorStatus()) {
        "ready" -> "摇一摇运行中"
        "disabled" -> "摇一摇已关闭"
        "disconnected" -> "页面识别服务未连接"
        "screen_off","locked" -> "解锁后恢复摇一摇"
        "starting" -> "正在启动摇动监听"
        "recovering","stalled" -> "摇动监听恢复中，请检查后台运行设置"
        else -> "摇动监听不可用，请使用截图导入"
    }
    fun snapshot():JSONObject {
        val power=context.getSystemService(PowerManager::class.java)
        val activity=context.getSystemService(ActivityManager::class.java)
        val status=CaptureRuntime.sensorStatus()
        return JSONObject().put("running",status=="ready")
            .put("sensorStatus",status).put("sensorRegistered",CaptureRuntime.instance?.sensorRegistered==true)
            .put("recoveryCount",CaptureRuntime.instance?.recoveryCount?:0)
            .put("lastRecoveryAt",CaptureRuntime.instance?.lastRecoveryAt?:0)
            .put("accessibilityConnected",ScreenCaptureService.current!=null)
            .put("backgroundRestricted",activity.isBackgroundRestricted)
            .put("batteryExempt",power.isIgnoringBatteryOptimizations(context.packageName)).put("powerSave",power.isPowerSaveMode)
            .put("sensorThread",preferences.getString("sensorThread","not_started"))
            .put("deliveryThread",deliveryThread)
            .put("startedAt",preferences.getLong("startedAt",0)).put("lastSampleAgeMs",lastSampleAgeMs)
            .put("lastSampleReceivedAgoMs",if(lastSampleAtMs>=0)SystemClock.elapsedRealtime()-lastSampleAtMs else -1)
            .put("lastDelayedSampleAgeMs",preferences.getLong("lastDelayedSampleAgeMs",-1))
            .put("lastDelayedSampleAt",preferences.getLong("lastDelayedSampleAt",0))
            .put("lastTriggerAgeMs",preferences.getLong("lastTriggerAgeMs",-1)).put("lastTriggerAt",preferences.getLong("lastTriggerAt",0))
    }
}

internal data class ShakeTrigger(val eventAtMs:Long,val receivedAtMs:Long) {
    fun freshAt(nowMs:Long)=nowMs-eventAtMs in 0..1500L
    fun details(nowMs:Long)="sensor_age_ms=${receivedAtMs-eventAtMs}; dispatch_age_ms=${nowMs-eventAtMs}; queue_ms=${nowMs-receivedAtMs}"
    companion object { fun now()=SystemClock.elapsedRealtime().let { ShakeTrigger(it,it) } }
}
