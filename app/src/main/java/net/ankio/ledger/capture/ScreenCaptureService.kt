package net.ankio.ledger.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import net.ankio.ledger.ocr.RecognitionException
import net.ankio.ledger.core.*
import net.ankio.ledger.ledger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** Acquire a validated frame first; local recognition continues independently of the foreground page. */
open class ScreenCaptureService:AccessibilityService() {
    private val busy=AtomicBoolean(false)
    private var job:Job?=null
    private var connectionRestore:Job?=null
    @Volatile private var requestId=0L
    private val banner by lazy { RecognitionBanner(this) }
    private val knownWindows=java.util.concurrent.ConcurrentHashMap<Int,String>()
    private val windowProbe=CaptureProbe()
    private val rootProbe=CaptureProbe()
    private val paymentMonitor by lazy { PaymentPageMonitor(this,::captureAutomatic) }
    internal var processorFactory:()->CaptureProcessor={ CaptureProcessor(ledger) }
    @Volatile private var activeAutomatic:Boolean?=null
    @Volatile private var activePackage:String?=null
    @Volatile private var cacheMode="platform_default"
    @Volatile private var runtimeRestoreAt=-1L
    @Volatile private var runtimeRestoreNotBefore=0L
    @Volatile private var runtimeRestoreAttempts=0L
    @Volatile private var runtimeRestoreOutcome="not_requested"
    private var lastScreenshotAt=0L
    private val paymentBoot by lazy { runCatching {
        android.provider.Settings.Global.getString(contentResolver,android.provider.Settings.Global.BOOT_COUNT)
            ?.toLongOrNull()?.takeIf { it>=0 }?.toString()
    }.getOrNull()?:java.util.UUID.randomUUID().toString() }
    @Volatile internal var screenshotRequests=0L;private set
    private class PrivacyBlocked(val userMessage:String="页面暂不可读，请稍后再试"):Exception()

    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event==null || !ledger.channels.captureEnabled())return
        if(event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED!=0)
            knownWindows.remove(event.windowId)
        if(event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            val pkg=event.packageName?.toString()
            if(CapturePrivacy.manualPackageAllowed(pkg) && event.windowId>=0) {
                if(knownWindows.size>64)knownWindows.clear()
                knownWindows[event.windowId]=pkg!!
            }
        }
        // Restore only an already enabled capability after a paused runtime.
        val eventPackage=event.packageName?.toString()
        if(CapturePrivacy.manualPackageAllowed(eventPackage) &&
            (ledger.channels.autoAllowed(eventPackage!!) || ledger.channels.enabled(ChannelSettings.Channel.OCR)))
            restoreEnabledRuntime("payment_event")
        paymentMonitor.event(event)
    }
    private fun restoreEnabledRuntime(reason:String) {
        if(current!==this || !ledger.channels.captureEnabled())return
        if(CaptureRuntime.running && (!ledger.channels.enabled(ChannelSettings.Channel.OCR) || CaptureRuntime.sensorStatus()=="ready"))return
        val now=SystemClock.elapsedRealtime()
        if(now<runtimeRestoreNotBefore)return
        if(!ledger.channels.permitted(ChannelSettings.Channel.OCR))return
        runtimeRestoreAt=now;runtimeRestoreNotBefore=now+2_000;runtimeRestoreAttempts++
        runCatching { CaptureRuntime.refreshHealth() }
            .onSuccess {
                runtimeRestoreOutcome="requested:$reason"
                ledger.executionLog.event("system","capture_service","runtime_restore_requested",
                    org.json.JSONObject().put("reason",reason).put("attempt",runtimeRestoreAttempts))
            }.onFailure {
                runtimeRestoreOutcome="failed:${it.javaClass.simpleName}"
                ledger.executionLog.event("system","capture_service","runtime_restore_failed",
                    org.json.JSONObject().put("reason",reason).put("errorType",it.javaClass.simpleName))
                ledger.channels.status("识别运行服务未恢复，请检查后台运行设置");ledger.changed()
            }
    }
    override fun onServiceConnected() {
        knownWindows.clear()
        serviceInfo=serviceInfo.apply {
            // Alipay groups the result description and hides its individual status/amount
            // nodes from the default tree. Read those nodes before the existing privacy gate.
            flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        refreshScope()
        if(Build.VERSION.SDK_INT>=33)cacheMode=if(runCatching { setCacheEnabled(false) }.getOrDefault(false))"disabled" else "disable_failed"
        current=this
        CaptureRuntime.attach(this)
        CaptureRuntime.refreshHealth()
        // The OS may rebind accessibility while our Activity is closed after reclaiming
        // the process. Restore only previously enabled capture, without requiring an app visit.
        connectionRestore?.cancel()
        connectionRestore=ledger.scope.launch(Dispatchers.Main.immediate) {
            repeat(12) {
                if(current!==this@ScreenCaptureService || !ledger.channels.captureEnabled())return@launch
                if(ledger.channels.permitted(ChannelSettings.Channel.OCR)) {
                    restoreEnabledRuntime("accessibility_reconnected")
                    // The permission list can lag service binding. The immediate
                    // resume below may have refused it before the permission list caught up.
                    ledger.captureQueue.resume()
                    ledger.executionLog.event("system","capture_service","accessibility_reconnected")
                    return@launch
                }
                delay(250)
            }
        }
        ledger.captureQueue.resume()
        ledger.changed()
    }
    private suspend fun windowSnapshot(timeout:Long=180):CaptureProbeResult<List<CaptureWindow>> = windowProbe.readResult(timeout) {
        val available=windows
        try { available.map { CaptureWindow(it.id,it.type,it.isActive,it.isFocused,it.layer,null,it.isInPictureInPictureMode) } }
        finally { available.forEach { it.recycle() } }
    }
    private suspend fun foregroundPackage(trace:(String)->Unit):String? {
        val snapshot=windowSnapshot(700)
        val metadata=snapshot.value
        if(metadata==null) { trace("windows=${snapshot.outcome}; cache=$cacheMode");return null }
        if(metadata.isNotEmpty())knownWindows.keys.retainAll(metadata.map { it.id }.toSet())
        val selected=selectCaptureWindow(metadata)
        var detail="windows=${metadata.size}; selected=${selected?.id}; type=${selected?.type}; focused=${selected?.focused}; active=${selected?.active}; event=${selected?.id?.let { knownWindows[it] }}; cache=$cacheMode"
        val owner=lookupCaptureOwner(metadata,knownWindows.toMap()) { windowId ->
            val result=rootProbe.readResult(700) {
                val node=if(windowId==null) {
                    if(Build.VERSION.SDK_INT>=33)getRootInActiveWindow(0) else rootInActiveWindow
                } else {
                    val available=windows
                    try {
                        available.firstOrNull { it.id==windowId }?.let { if(Build.VERSION.SDK_INT>=33)it.getRoot(0) else it.root }
                    } finally { available.forEach { it.recycle() } }
                }
                node?.let { try { CaptureRoot(it.windowId,it.packageName?.toString()) } finally { it.recycle() } }
            }
            detail+="; root=${result.outcome}; rootWindow=${result.value?.windowId}"
            result.value
        }
        // lookupCaptureOwner validates root/window identity before accepting an owner. Remember
        // that proof for the immediate screenshot check, including the first shake after binding.
        if(selected?.type==AccessibilityWindowInfo.TYPE_APPLICATION && CapturePrivacy.manualPackageAllowed(owner))
            knownWindows[selected.id]=owner!!
        trace("$detail; owner=${owner?:"unavailable"}")
        return owner
    }
    private suspend fun currentTarget(pkg:String,expectedWindow:Int?=null):CaptureWindow? {
        val metadata=windowSnapshot().value?:return null
        val selected=selectCaptureWindow(metadata)?:return null
        if(selected.type!=AccessibilityWindowInfo.TYPE_APPLICATION || !selected.active && !selected.focused)return null
        if(expectedWindow!=null && selected.id!=expectedWindow)return null
        return selected.takeIf { knownWindows[it.id]==pkg }
    }

    private fun cancelAcquisition() {
        requestId++;job?.cancel();job=null;busy.set(false);activeAutomatic=null;activePackage=null;banner.close()
    }
    fun cancelRecognition() { cancelAcquisition();ledger.captureQueue.cancelAll() }
    fun cancelAutomaticRecognition(packageName:String?=null) {
        if(activeAutomatic==true && (packageName==null || activePackage==packageName))cancelAcquisition()
        ledger.captureQueue.cancelAutomatic(packageName)
        paymentMonitor.stop()
        refreshScope()
    }
    fun cancelChannel(channel:ChannelSettings.Channel) {
        if(channel==ChannelSettings.Channel.OCR) {
            if(activeAutomatic==false)cancelAcquisition()
            ledger.captureQueue.cancelShake()
            refreshScope()
        }
    }
    /** Connection teardown must not discard already acquired, queued frames. */
    fun captureServiceStopped() {
        // A completed runtime being stopped is a new recovery opportunity, not
        // another duplicate start request from the previous event burst.
        runtimeRestoreNotBefore=0L
        cancelAcquisition();paymentMonitor.stop()
    }
    fun refreshScope() {
        serviceInfo=serviceInfo.apply {
            // Android filters package-less TYPE_WINDOWS_CHANGED out when packageNames
            // is set. Those system events are needed when an activity is announced
            // before its window can be queried. Receive their metadata, then enforce
            // application selection in the monitor BEFORE reading a view tree or pixels.
            // Keep the XML filter empty too so that the first service connection
            // can receive system-window events before this runtime update.
            packageNames=null
        }
    }
    fun checkPaymentPages() { refreshScope();paymentMonitor.schedule() }
    internal fun automaticCaptureAvailable()=!busy.get() && ledger.captureQueue.hasCapacity()
    fun paymentDiagnostics()=paymentMonitor.diagnostics()
        .put("feedback",banner.diagnostics())
        .put("captureBusy",busy.get()).put("queueSize",ledger.captureQueue.size())
        .put("runtimeRestore",org.json.JSONObject().put("attempts",runtimeRestoreAttempts)
            .put("outcome",runtimeRestoreOutcome)
            .put("lastAttemptAgoMs",if(runtimeRestoreAt<0)-1 else SystemClock.elapsedRealtime()-runtimeRestoreAt))
    /** ADB can inspect the running listener without bringing MainActivity forward. */
    override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>?) {
        super.dump(fd,writer,args)
        writer.println("ZhiyuCapture="+paymentDiagnostics().toString())
    }
    fun revoke() {
        ledger.channels.setAutomaticEnabled(false)
        ledger.channels.set(ChannelSettings.Channel.OCR,false)
        cancelRecognition();CaptureRuntime.stopSafely();disableSelf();ledger.changed()
    }
    fun captureOnShake()=captureOnShake(ShakeTrigger.now())
    internal fun captureOnShake(trigger:ShakeTrigger)=capture(trigger)
    internal fun captureAutomatic(page:PaymentMatch)=capture(ShakeTrigger.now(),page)
    private fun capture(trigger:ShakeTrigger,page:PaymentMatch?=null) {
        val automatic=page!=null
        val channel=ChannelSettings.Channel.OCR
        if(!trigger.freshAt(SystemClock.elapsedRealtime()))return
        val enabled=if(automatic)ledger.channels.autoAllowed(page!!.packageName) else ledger.channels.enabled(channel)
        if(!CaptureRuntime.running || !enabled)return
        if(!automatic && !ledger.channels.permitted(channel)) { ledger.captureHealth.blocked();return }
        if(!ledger.captureQueue.hasCapacity()) { if(!automatic)feedback("待识别账单较多，请稍候再摇一摇");return }
        if(!busy.compareAndSet(false,true)) { if(!automatic)feedback("正在获取上一张账单，请稍候");return }
        activeAutomatic=automatic;activePackage=page?.packageName
        val generation=if(automatic)ledger.channels.automaticGeneration(page!!.packageName) else ledger.channels.generation(channel)
        val recognitionGeneration=ledger.recognitionSession.generation
        val id=++requestId
        fun allowed()=requestId==id && CaptureRuntime.running && ledger.channels.permitted(channel) && ledger.recognitionSession.generation==recognitionGeneration &&
            if(automatic)ledger.channels.autoAllowed(page!!.packageName) && ledger.channels.automaticGeneration(page.packageName)==generation
            else ledger.channels.enabled(channel) && ledger.channels.generation(channel)==generation
        job=ledger.scope.launch {
            var task:Long?=null
            var bitmap:Bitmap?=null
            var enqueued=false
            val observations=linkedSetOf<String>()
            val windowDetails=linkedSetOf<String>()
            suspend fun readOwner():String?=foregroundPackage { windowDetails.add(it) }
            try {
                val startAtMs=SystemClock.elapsedRealtime()
                if(!trigger.freshAt(startAtMs) || !allowed())throw CancellationException()
                // No acquisition banner or artificial delay before the frame: a result can vanish
                // as soon as the user taps Done. Any progress banner is shown after persistence.
                withContext(Dispatchers.Main) { banner.removeNow() }
                val pkg=if(automatic)page!!.packageName else {
                    var observed:String?=null
                    resolveCaptureTarget { readOwner().also { observed=it;observations.add(it?:"unavailable") } }
                        ?:throw RecognitionException(if(observed==null || observed=="com.android.systemui")"暂时无法确认账单窗口，请收起浮层并在账单页重试" else "当前不是已支持的应用，请进入账单详情页重试")
                }
                activePackage=pkg
                val remaining=400L-(SystemClock.elapsedRealtime()-lastScreenshotAt)
                if(lastScreenshotAt!=0L && remaining>0)delay(remaining)
                if(!allowed())throw CancellationException()
                val target=currentTarget(pkg,page?.windowId)
                    ?:throw RecognitionException("页面正在切换，未获取本次截图，可打开账单详情摇一摇补录")
                // A screenshot may NEVER be used as the probe that decides whether a page
                // contains a password. Unknown/unreadable shared containers fail closed.
                val manual=if(automatic)null else paymentMonitor.manualCheck(pkg,target.id)
                if(manual?.value?.let(net.ankio.ledger.core.CapturePrivacy::sensitive)==true)
                    throw PrivacyBlocked("支付验证页，暂不截图")
                val safe=if(automatic)paymentMonitor.confirms(page!!) else manual?.value?.let(net.ankio.ledger.core.CapturePrivacy::manualAllowed)==true
                if(!safe || !allowed() || currentTarget(pkg,target.id)==null)throw PrivacyBlocked()
                task=ledger.tasks.create(Source.OCR,if(automatic)"payment_auto:$pkg" else "shake:$pkg")
                if(automatic)ledger.tasks.diagnose(task,"payment_identity","payment_page:$paymentBoot:${page!!.eventIdentity}")
                ledger.tasks.diagnose(task,"trigger",(if(automatic)"payment_auto; " else "")+trigger.details(startAtMs))
                ledger.changed()
                ledger.tasks.diagnose(task,"stage","screenshot")
                ledger.tasks.diagnose(task,"window","$pkg; id=${target.id}; automatic=$automatic")
                lastScreenshotAt=SystemClock.elapsedRealtime()
                bitmap=withTimeoutOrNull(4_000) { screenshot(if(Build.VERSION.SDK_INT>=34)target.id else null) }
                    ?:throw RecognitionException("系统截图响应超时，请检查无障碍状态后重试")
                if(!allowed())throw CancellationException()
                // A specified-window frame belongs to the validated target even if the user has
                // returned since acquisition. Legacy full-display capture needs a second owner check.
                if(Build.VERSION.SDK_INT<34 && currentTarget(pkg,target.id)==null)
                    throw RecognitionException("页面已经切换，本次截图未发送，可打开详情摇一摇补录")
                if(automatic)ledger.tasks.diagnose(task,"capture_context","payment_success_result:${System.currentTimeMillis()}")
                ledger.tasks.diagnose(task,"timing","captured_age_ms=${SystemClock.elapsedRealtime()-trigger.eventAtMs}")
                val image=withContext(Dispatchers.Default) { ledger.recognizer.prepareScreenshot(bitmap!!) }
                // Queueing and inference need only the prepared JPEG. Release the full display copy first.
                bitmap?.recycle();bitmap=null
                if(!allowed())throw CancellationException()
                if(!ledger.tasks.queued(task,image))throw CancellationException("识别记录已删除")
                ledger.tasks.diagnose(task,"window_details",windowDetails.toList().takeLast(2).joinToString(" | "))
                val feedbackTask=task
                enqueued=ledger.captureQueue.enqueue(task,pkg,automatic,processorFactory) { message,loading ->
                    feedback(message,loading,if(!loading && message.startsWith("已记账"))ledger.tasks.recordId(feedbackTask) else null)
                }
                if(!enqueued)throw RecognitionException("识别任务较多，请稍后重新摇一摇补录")
                ledger.changed()
            } catch(blocked:PrivacyBlocked) {
                if(page!=null)paymentMonitor.rejectFrame(page)
                task?.let(ledger.tasks::delete)
                ledger.executionLog.event(if(automatic)"payment_auto" else "ocr","capture","privacy_skipped")
                // Automatic probes stay silent. An explicit shake must explain its refusal,
                // without acquiring pixels or leaving a failed financial record behind.
                if(!automatic && allowed())feedback(blocked.userMessage)
            } catch(cancelled:CancellationException) {
                if(page!=null && !enqueued)paymentMonitor.rejectFrame(page)
                if(!enqueued)task?.let { ledger.tasks.update(it,"CANCELLED","识别已停止，未新增账单");ledger.changed() }
            } catch(error:Exception) {
                if(page!=null && !enqueued)paymentMonitor.rejectFrame(page)
                val message=(error as? RecognitionException)?.userMessage?:"获取截图失败，请在账单详情页重试"
                ledger.executionLog.event(if(automatic)"payment_auto" else channel.key,"capture","failed",org.json.JSONObject().put("message",message).put("errorType",error.javaClass.simpleName))
                task?.let {
                    ledger.tasks.diagnose(it,"window",observations.joinToString(" → "))
                    ledger.tasks.diagnose(it,"window_details",windowDetails.toList().takeLast(2).joinToString(" | "))
                    ledger.tasks.update(it,"FAILED",message);ledger.changed()
                }
                if(allowed())feedback(message)
            } finally {
                bitmap?.recycle()
                if(requestId==id) { busy.set(false);activeAutomatic=null;activePackage=null;job=null;paymentMonitor.schedule() }
            }
        }
    }
    private suspend fun screenshot(windowId:Int?):Bitmap=suspendCancellableCoroutine { continuation ->
        screenshotRequests++
        val callback=object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                val buffer=result.hardwareBuffer
                val image=try { Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)?.let { hardware ->
                    try { hardware.copy(Bitmap.Config.ARGB_8888,false) } finally { hardware.recycle() }
                } } catch(_:Exception) { null } finally { buffer.close() }
                if(!continuation.isActive) { image?.recycle();return }
                if(image==null)continuation.resumeWithException(RecognitionException("无法读取截图，请尝试导入截图"))
                else continuation.resume(image) { _,value,_ -> value.recycle() }
            }
            override fun onFailure(errorCode:Int) {
                if(continuation.isActive)continuation.resumeWithException(
                    if(errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)PrivacyBlocked("当前页面受系统保护，未获取截图；请使用账单截图导入")
                    else RecognitionException("当前页面无法截图（系统代码 $errorCode），请尝试导入截图"))
            }
        }
        try {
            if(Build.VERSION.SDK_INT>=34 && windowId!=null)takeScreenshotOfWindow(windowId,mainExecutor,callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,callback)
        } catch(_:Exception) {
            if(continuation.isActive)continuation.resumeWithException(RecognitionException("无法获取截图，请检查无障碍状态"))
        }
    }
    private fun feedback(message:String,loading:Boolean=false,recordId:Long?=null) {
        ledger.channels.status(message);ledger.changed()
        if(current===this)banner.show(message,loading,recordId=recordId)
    }
    internal fun showReadinessFeedback(message:String) { if(current===this)banner.show(message) }
    /** Also used by disk-backed jobs resumed without a new foreground capture callback. */
    internal fun showQueuedFeedback(message:String,loading:Boolean,recordId:Long?)=feedback(message,loading,recordId)
    override fun onInterrupt() { cancelAcquisition() }
    private fun releaseConnection() {
        connectionRestore?.cancel();connectionRestore=null
        captureServiceStopped();knownWindows.clear()
        ledger.captureHealth.suspendForMissingPermission()
        CaptureRuntime.detach(this)
        if(current===this)current=null
        ledger.changed()
    }
    override fun onUnbind(intent:Intent?):Boolean { releaseConnection();return super.onUnbind(intent) }
    override fun onDestroy() { releaseConnection();paymentMonitor.close();windowProbe.close();rootProbe.close();super.onDestroy() }
    companion object {
        val PACKAGES=PaymentApps.entries.map { it.packageName }.toSet()
        @Volatile var current:ScreenCaptureService?=null;private set
    }
}
