package net.ankio.ledger.capture

import android.app.KeyguardManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ankio.ledger.core.*
import net.ankio.ledger.ledger
import org.json.JSONObject

/** Automatic reads require a selected payment activity; explicit shakes preflight the user's current app. */
internal class PaymentPageMonitor(private val service:ScreenCaptureService,private val capture:(PaymentMatch)->Unit):AutoCloseable {
    private val probe=CaptureProbe()
    private val readLock=Mutex()
    private val activities=java.util.concurrent.ConcurrentHashMap<Int,String>()
    private val packages=java.util.concurrent.ConcurrentHashMap<Int,String>()
    private val claims=PaymentGate()
    private val visits=PaymentVisits(service)
    private val rejectedEvents=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private var check:Job?=null
    private val checks=Channel<Unit>(Channel.CONFLATED)
    @Volatile private var checking=false
    @Volatile private var checkRequests=0L
    @Volatile private var checkPasses=0L
    private var lateWakeEventAt=-1L
    @Volatile private var lastEventAt=0L
    @Volatile private var lastActivity=""
    @Volatile private var observation=JSONObject()
    @Volatile private var probeDetails=JSONObject()
    private var lastReason=""
    private var lastTraceProbe=""
    private val recent=java.util.ArrayDeque<JSONObject>()
    private fun trace(value:JSONObject) = synchronized(recent) {
        recent.addLast(value.put("at",System.currentTimeMillis()))
        while(recent.size>32)recent.removeFirst()
    }
    @Volatile private var receivedEvents=0L
    @Volatile private var selectedEvents=0L
    @Volatile private var windowEvents=0L
    @Volatile private var lastEventPackage=""
    fun ready()=service.ledger.channels.automaticEnabled() && CaptureRuntime.running &&
        service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun event(event:AccessibilityEvent) {
        receivedEvents++
        lastEventPackage=event.packageName?.toString().orEmpty()
        if(lastEventPackage in ScreenCaptureService.PACKAGES || event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.packageName==null)
            trace(JSONObject().put("kind","event").put("type",event.eventType).put("window",event.windowId)
                .put("changes",event.windowChanges).put("package",lastEventPackage).put("class",event.className?.toString().orEmpty().take(180)))
        if(event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED!=0) {
            activities.remove(event.windowId);packages.remove(event.windowId)
        }
        if(event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            windowEvents++
            // Window-manager events need not contain a package/class. The activity's
            // first event can precede publication of its usable window (HyperOS).
            // Re-read the selected window when it becomes available; readPage still
            // requires cached eligible-activity evidence and verifies its actual owner.
            if(event.packageName==null) {
                lastEventAt=SystemClock.elapsedRealtime()
                schedule()
                return
            }
        }
        val pkg=event.packageName?.toString()?:return
        if(!CapturePrivacy.manualPackageAllowed(pkg))return
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val name=event.className?.toString().orEmpty()
            // Window-state events also come from custom Views. Only a declared
            // activity can identify the page; a View class must not replace it.
            if(name.contains('.') && !name.startsWith("android.") && !name.startsWith("com.android.") &&
                runCatching { service.packageManager.getActivityInfo(android.content.ComponentName(pkg,name),0) }.isSuccess) {
                lastActivity=name
                if(activities.size>64) { activities.clear();packages.clear() }
                activities[event.windowId]=name
                packages[event.windowId]=pkg
            }
        }
        if(!service.ledger.channels.autoAllowed(pkg))return
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val activity=activities[event.windowId].orEmpty()
            // Chat/home content changes cannot become a payment result without
            // an activity/window transition. Keep those transition events for
            // first-result readiness and owner-verified visit resets.
            if(PaymentVisits.isNavigation(pkg,activity) || !PaymentPageDetector.eligibleActivity(pkg,activity))return
        }
        selectedEvents++
        lastEventAt=SystemClock.elapsedRealtime()
        schedule()
    }
    @Synchronized fun schedule() {
        if(!ready())return
        checkRequests++;checks.trySend(Unit)
        if(check?.isActive==true)return
        // A new window can arrive during the final query of an earlier pass.
        // Keep one pending wakeup instead of dropping all events while busy.
        check=service.ledger.scope.launch(start=CoroutineStart.LAZY) {
            for(request in checks) {
                if(!ready())continue
                checking=true;checkPasses++
                try { inspectCurrentPage() } finally { checking=false }
            }
        }
        check!!.start()
    }
    private suspend fun inspectCurrentPage() {
        // Each event-driven pass is bounded. A quiet page does not cause polling.
        repeat(8) {
            if(!ready())return
            val match=readMatch()
            if(match!=null && service.automaticCaptureAvailable()) {
                val key="${match.windowId}|${match.activity}"
                if((rejectedEvents[key]?:-1L)<lastEventAt && claims.observe(match) && claims.claim(match)) {
                    service.ledger.executionLog.event("payment_auto","detection","matched",diagnostics())
                    capture(match);return
                }
            }
            delay(120)
        }
    }
    @Synchronized private fun lateProbeReady() {
        if(ScreenCaptureService.current!==service || !ready() || lateWakeEventAt==lastEventAt)return
        // A timed-out Binder read can finish after all retries. Wake once per
        // external event batch; discard its old page and revalidate the current one.
        lateWakeEventAt=lastEventAt
        trace(JSONObject().put("kind","late_probe_ready"))
        schedule()
    }
    /** A rejected preflight is not a completed visit. Retry on a later UI event,
     * without turning a quiet nonpayment screen into an endless screenshot polling loop. */
    fun rejectFrame(match:PaymentMatch) {
        val key="${match.windowId}|${match.activity}"
        rejectedEvents[key]=lastEventAt;claims.release(match)
    }
    suspend fun readMatch():PaymentMatch?=readLock.withLock {
        if(!ready())return@withLock null
        val result=readPage()
        val match=result.value?.takeIf { service.ledger.channels.autoAllowed(it.packageName) }?.let(visits::detect)
        val reason=if(match!=null)"matched" else "not_completed_single_payment:${result.outcome}"
        observation=JSONObject().put("activity",match?.activity?:lastActivity).put("package",match?.packageName.orEmpty())
            .put("outcome",reason).put("observedAt",System.currentTimeMillis())
        if(reason!=lastReason) { lastReason=reason;service.ledger.executionLog.event("payment_auto","page_probe",reason,diagnostics()) }
        val traceKey="$reason:${probeDetails.optString("stage")}:${probeDetails.optInt("selectedWindow",-1)}"
        if(traceKey!=lastTraceProbe) {
            lastTraceProbe=traceKey
            trace(JSONObject().put("kind","probe").put("outcome",reason).put("probe",JSONObject(probeDetails.toString())))
        }
        match
    }
    /** Query again at the acquisition boundary. Old matches never authorize a new frame. */
    suspend fun confirms(match:PaymentMatch):Boolean=readLock.withLock {
        val page=readPage(match.packageName,match.windowId).value?:return@withLock false
        visits.detect(page)?.let { it.activity==match.activity && it.signature==match.signature && it.eventIdentity==match.eventIdentity }==true
    }
    suspend fun manualCheck(pkg:String,window:Int):CaptureProbeResult<PaymentPage> = readLock.withLock {
        val result=readManualCapturePage { readPage(pkg,window,700,automatic=false) }
        val accepted=result.value?.let(CapturePrivacy::manualAllowed)==true
        service.ledger.executionLog.event("ocr","preflight",if(accepted)"accepted" else "blocked",
            JSONObject().put("package",pkg).put("window",window).put("probeOutcome",result.outcome).put("probe",JSONObject(probeDetails.toString())))
        result
    }
    private suspend fun readPage(expectedPackage:String?=null,expectedWindow:Int?=null,timeout:Long=180,automatic:Boolean=true):CaptureProbeResult<PaymentPage> =
        probe.readResult(timeout,onLateResult=::lateProbeReady) {
            val detail=JSONObject().put("stage","windows").put("at",System.currentTimeMillis())
            fun missing(stage:String):PaymentPage? { probeDetails=JSONObject(detail.toString()).put("stage",stage);return null }
            val available=service.windows
            detail.put("windowCount",available.size)
            val root=try {
                val selected=selectCaptureWindow(available.map { CaptureWindow(it.id,it.type,it.isActive,it.isFocused,it.layer,null,it.isInPictureInPictureMode) })?:return@readResult missing("no_selected_window")
                detail.put("selectedWindow",selected.id).put("selectedType",selected.type)
                if(selected.type!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)return@readResult missing("not_application_window")
                val activity=activities[selected.id].orEmpty()
                val pkg=packages[selected.id] ?: if(!automatic)expectedPackage.orEmpty() else ""
                detail.put("cachedPackage",pkg).put("cachedActivity",activity)
                if(expectedWindow!=null && selected.id!=expectedWindow || expectedPackage!=null && pkg!=expectedPackage)return@readResult missing("target_changed")
                if(automatic && !service.ledger.channels.autoAllowed(pkg))return@readResult missing("application_not_selected")
                if(!automatic && !CapturePrivacy.manualPackageAllowed(pkg))return@readResult missing("activity_not_eligible")
                available.firstOrNull { it.id==selected.id }?.let { if(Build.VERSION.SDK_INT>=33)it.getRoot(0) else it.root }
            } finally { available.forEach { it.recycle() } }?:return@readResult missing("root_unavailable")
            val pkg=root.packageName?.toString().orEmpty()
            detail.put("rootPackage",pkg).put("rootWindow",root.windowId)
            if(root.windowId!=detail.optInt("selectedWindow",-1) ||
                !CapturePrivacy.manualPackageAllowed(pkg) || expectedPackage!=null && pkg!=expectedPackage ||
                automatic && (!service.ledger.channels.autoAllowed(pkg) || pkg!=detail.optString("cachedPackage"))) { root.recycle();return@readResult missing("root_package_mismatch") }
            val window=root.windowId
            val activity=activities[window].orEmpty()
            if(automatic && (PaymentVisits.isNavigation(pkg,activity) || !PaymentPageDetector.eligibleActivity(pkg,activity))) {
                // Navigation is evidence only for the selected, owner-verified
                // window. Return metadata without reading ordinary app content.
                // PaymentVisits consumes it after the probe returns on time;
                // a late Binder result must not mutate the payment identity.
                root.recycle();probeDetails=JSONObject(detail.toString()).put("stage","navigation_metadata")
                return@readResult PaymentPage(pkg,window,activity,emptyList())
            }
            val queue=java.util.ArrayDeque<Pair<AccessibilityNodeInfo,Int>>()
            queue.add(root to 0)
            val texts=mutableListOf<String>();var editable=false;var count=0;var chars=0;var clipped=false
            try {
                while(queue.isNotEmpty()) {
                    val (node,depth)=queue.removeFirst()
                    try {
                        // The measured Alipay result has 100 nodes but reaches depth 28.
                        // Keep bounded traversal and reject any partial tree, including one
                        // whose unvisited children might contain an authentication field.
                        if(++count>400 || depth>64) {
                            detail.put("limit",if(count>400)"nodes" else "depth").put("depth",depth)
                            clipped=true;break
                        }
                        if(node.isVisibleToUser) {
                            // Never read the value or description of a password/input field.
                            if(node.isEditable || node.isPassword) { editable=true;break }
                            listOfNotNull(node.text?.toString(),node.contentDescription?.toString()).distinct().forEach {
                                chars+=it.length
                                if(it.isNotBlank() && it.length<=2048)texts.add(it)
                            }
                        }
                        if(chars>16000) { detail.put("limit","text");clipped=true;break }
                        for(i in 0 until node.childCount.coerceAtMost(80))node.getChild(i)?.let { queue.add(it to depth+1) }
                        if(node.childCount>80) { detail.put("limit","children");clipped=true }
                    } finally { node.recycle() }
                }
            } finally { queue.forEach { it.first.recycle() } }
            detail.put("nodes",count).put("textCount",texts.size).put("editable",editable)
            probeDetails=JSONObject(detail.toString()).put("stage",if(clipped)"tree_limit" else "page_read")
            if(clipped)null else PaymentPage(pkg,window,activity,texts,editable)
        }
    fun diagnostics()=JSONObject(observation.toString()).put("enabled",service.ledger.channels.automaticEnabled())
        .put("probe",JSONObject(probeDetails.toString())).put("flags",service.serviceInfo.flags)
        .put("serviceRunning",CaptureRuntime.running).put("receivedEvents",receivedEvents).put("selectedEvents",selectedEvents)
        .put("windowEvents",windowEvents)
        .put("lastEventPackage",lastEventPackage).put("eventPackages",org.json.JSONArray(service.serviceInfo.packageNames?.toList().orEmpty()))
        .put("applications",org.json.JSONArray(service.ledger.channels.selectedAutoPackages().toList()))
        .put("recent",synchronized(recent) { org.json.JSONArray(recent.map { JSONObject(it.toString()) }) })
        .put("lastActivity",lastActivity)
        .put("checking",checking).put("checkRequests",checkRequests).put("checkPasses",checkPasses)
        .put("lastEventAgoMs",if(lastEventAt==0L)-1 else SystemClock.elapsedRealtime()-lastEventAt)
    @Synchronized fun stop() { check?.cancel();check=null;while(checks.tryReceive().isSuccess)Unit;claims.reset();rejectedEvents.clear() }
    override fun close() { stop();checks.close();probe.close();activities.clear();packages.clear() }
}
