package net.ankio.ledger.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import net.ankio.ledger.core.CaptureBlocker
import net.ankio.ledger.core.CaptureReadiness
import net.ankio.ledger.ledger
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject

/** Permission repair reports metadata only; never acquires a frame or grants a system permission. */
class CaptureHealth(private val context:Context) {
    private val prefs=context.getSharedPreferences("capture_health",Context.MODE_PRIVATE)
    private var lastNoticeAt=-30_000L
    private var lastNotice=""
    /** Persist only a verified, live posted bill, never model fields or a stale task message. */
    fun successful(recordId:Long,origin:String):Boolean {
        val record=context.ledger.store.get(recordId)?:return false
        if(record.state!=net.ankio.ledger.core.RecordState.POSTED || record.bill.amountCents==null)return false
        prefs.edit().putLong("lastSuccessfulAt",System.currentTimeMillis()).putLong("lastSuccessfulRecordId",recordId)
            .putString("lastSuccessfulOrigin",origin.take(150)).apply()
        return true
    }
    fun state()=CaptureReadiness(context.ledger.channels.captureEnabled(),
        context.ledger.channels.permitted(ChannelSettings.Channel.OCR),ScreenCaptureService.current!=null,CaptureRuntime.running)
    fun message(blocker:CaptureBlocker=state().blocker)=when(blocker) {
        CaptureBlocker.PERMISSION -> DevicePermissionGuidance.current.permissionMessage
        CaptureBlocker.CONNECTION -> "页面识别服务未连接，请检查系统服务"
        CaptureBlocker.RUNTIME -> "后台监听未运行，请恢复识别服务"
        CaptureBlocker.DISABLED -> "识别已关闭"
        CaptureBlocker.NONE -> "识别已就绪"
    }
    fun repairIntent()=Intent(context,MainActivity::class.java).setAction(REPAIR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    fun repairShake()=prefs.getBoolean("repairShake",false)
    fun runtimeRequested()=context.ledger.channels.captureEnabled()
    /** Confirm the OS list as well, so a delayed AccessibilityManager callback cannot disable a valid grant. */
    fun permissionDefinitelyMissing():Boolean {
        if(context.ledger.channels.permitted(ChannelSettings.Channel.OCR))return false
        val enabled=android.provider.Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services").orEmpty()
        return enabled.split(':').mapNotNull(android.content.ComponentName::unflattenFromString).none { it==captureServiceComponent(context) }
    }
    fun suspendForMissingPermission():Boolean {
        if(!context.ledger.channels.captureEnabled() || !permissionDefinitelyMissing())return false
        prefs.edit().putBoolean("repairShake",context.ledger.channels.enabled(ChannelSettings.Channel.OCR)).apply()
        blocked()
        context.ledger.channels.pauseForMissingPermission()
        ScreenCaptureService.current?.cancelRecognition()
        context.ledger.changed()
        return true
    }
    fun snapshot():JSONObject {
        val state=state()
        return JSONObject().put("enabled",state.enabled).put("ready",state.blocker==CaptureBlocker.NONE)
            .put("shakeReady",CaptureRuntime.sensorStatus()=="ready")
            .put("shakeStatus",CaptureRuntime.sensorStatus()).put("shakeMessage",context.ledger.shakeRuntime.statusMessage())
            .put("blocker",state.blocker.name).put("message",message(state.blocker))
            .put("permitted",state.permitted).put("connected",state.connected).put("runtimeRunning",state.runtimeRunning)
            .put("deviceGuide",DevicePermissionGuidance.payload())
            .put("lastBlockedAt",prefs.getLong("lastBlockedAt",0)).put("lastBlockedReason",prefs.getString("lastBlockedReason",""))
            .put("lastSuccessfulAt",prefs.getLong("lastSuccessfulAt",0)).put("lastSuccessfulRecordId",prefs.getLong("lastSuccessfulRecordId",0))
            .put("lastSuccessfulOrigin",prefs.getString("lastSuccessfulOrigin",""))
    }
    @Synchronized fun blocked(showToast:Boolean=true,blocker:CaptureBlocker=state().blocker,userTriggered:Boolean=false):Boolean {
        if(blocker in setOf(CaptureBlocker.NONE,CaptureBlocker.DISABLED))return false
        val message=message(blocker)
        prefs.edit().putLong("lastBlockedAt",System.currentTimeMillis()).putString("lastBlockedReason",blocker.name).apply()
        context.ledger.executionLog.event("ocr","readiness","blocked",JSONObject().put("reason",blocker.name))
        context.ledger.changed()
        val now=SystemClock.elapsedRealtime()
        if(!userTriggered && lastNotice==blocker.name && now-lastNoticeAt<10_000)return true
        lastNotice=blocker.name;lastNoticeAt=now
        if(showToast)Handler(Looper.getMainLooper()).post {
            val service=ScreenCaptureService.current
            if(service!=null && context.ledger.channels.permitted(ChannelSettings.Channel.OCR))service.showReadinessFeedback(message)
            else Toast.makeText(context,message,Toast.LENGTH_SHORT).show()
        }
        return true
    }
    fun clearNotice() {
        lastNotice=""
    }
    companion object { const val REPAIR="net.ankio.ledger.REPAIR_CAPTURE" }
}
