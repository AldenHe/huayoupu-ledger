package net.ankio.ledger.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CompoundButton
import android.widget.FrameLayout
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.ledger
import org.json.JSONObject

/** Result feedback can open its saved bill; removed before every screenshot. */
class RecognitionBanner(private val service:Context) {
    private val handler=Handler(Looper.getMainLooper())
    private val windows=service.getSystemService(WindowManager::class.java)
    private var view:View?=null
    private var hide:Runnable?=null
    private val live=RecognitionLiveUpdate(service)
    private val accountPanel=ResultAccountPanel(service)
    private var resultIdentity:Pair<Long,String>?=null
    private var resultDeadline=0L
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    private var nativeDelivery=false
    private var cardView:LinearLayout?=null
    @Volatile private var lastWindowType=0
    @Volatile private var lastFailure=""
    @Volatile private var delivery="not_shown"
    internal val attached get()=view?.isAttachedToWindow==true
    fun diagnostics()=JSONObject().put("delivery",delivery).put("windowType",lastWindowType).put("lastFailure",lastFailure).put("attached",attached).put("accountPanelOpen",accountPanel.showing).put("liveUpdate",live.diagnostics())
    fun show(message:String,loading:Boolean=false,onRepair:(()->Unit)?=null,recordId:Long?=null) {
        val token=generation.incrementAndGet()
        handler.post {
            if(generation.get()!=token)return@post
            val wasNative=nativeDelivery
            clearOverlay()
            val record=if(!loading && onRepair==null)recordId?.let {service.ledger.store.get(it)}?.takeIf {it.state==RecordState.POSTED} else null
            resultIdentity=record?.let {it.id to it.uid}
            resultDeadline=SystemClock.elapsedRealtime()+if(record==null)3_600 else 5_000
            val display=compactMessage(message)
            if(record!=null) {
                live.cancel();nativeDelivery=false
                if(live.showResult("已记账 ¥ ${Money.display(record.bill.amountCents?:0)}","${record.bill.merchant} · ${record.bill.category}","已记账")) scheduleNativeResult(record,token)
                else showResult(record,token)
                logDelivery();return@post
            }
            if(onRepair==null && (loading || wasNative) && live.show(display,loading)) {
                nativeDelivery=true;delivery="live_update";lastFailure="";lastWindowType=0
                if(loading) {
                    // ColorOS and other system renderers may promote asynchronously.
                    // Keep the accepted quiet notification until completion or cancellation;
                    // absence of an immediate island never calls for a custom progress pill.
                    logDelivery()
                } else { hide=Runnable { if(generation.get()==token)removeNow() };handler.postDelayed(hide!!,live.feedbackDuration);logDelivery() }
            } else {
                live.cancel();nativeDelivery=false
                // Native progress is optional. Unsupported devices go straight to the
                // saved result card; a stale result identity cannot revive a success pill.
                if(loading || onRepair==null && recordId!=null) {
                    delivery="silent";lastFailure="";lastWindowType=0
                } else showCapsule(display,onRepair)
                logDelivery()
            }
        }
    }
    private fun logDelivery() { service.ledger.executionLog.event("ocr","feedback",delivery,diagnostics()) }
    /** A single guarded automatic handoff; new feedback and screenshot cleanup cancel this runnable. */
    private fun scheduleNativeResult(record:LedgerRecord,token:Int) {
        hide?.let(handler::removeCallbacks)
        nativeDelivery=true;delivery="live_update";resultDeadline=SystemClock.elapsedRealtime()+6_000
        hide=object:Runnable {
            override fun run() {
                if(generation.get()==token && hide===this) {
                    val current=currentResult(record.id,record.uid)
                    live.cancel();nativeDelivery=false;hide=null
                    if(current==null)removeNow() else showResult(current,token)
                    logDelivery()
                }
            }
        }.also {handler.postDelayed(it,RecognitionLiveUpdate.RESULT_SUMMARY_DURATION)}
    }
    private fun currentResult(id:Long,uid:String):LedgerRecord? {
        if(resultIdentity!=(id to uid) || SystemClock.elapsedRealtime()>=resultDeadline)return null
        return service.ledger.store.get(id)?.takeIf {it.uid==uid && it.state==RecordState.POSTED}
    }
    private fun scheduleResultExpiry(token:Int,duration:Long=5_000) {
        hide?.let(handler::removeCallbacks)
        resultDeadline=SystemClock.elapsedRealtime()+duration
        hide=Runnable {if(generation.get()==token)removeNow()}.also {handler.postDelayed(it,duration)}
    }
    private fun showResult(record:LedgerRecord,token:Int) {
        if(generation.get()!=token || view!=null)return
        val colors=ResultColors(service)
        val width=(service.resources.displayMetrics.widthPixels-colors.dp(32)).coerceAtMost(colors.dp(344))
        val root=object:FrameLayout(service) {
            private var touchingCard=false
            override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
                if(event.actionMasked==android.view.MotionEvent.ACTION_OUTSIDE) {
                    if(accountPanel.showing)accountPanel.close() else removeNow()
                    return false
                }
                val inside=cardView?.let {event.x>=it.left && event.x<=it.right && event.y>=it.top && event.y<=it.bottom}==true
                if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) {
                    touchingCard=inside
                    if(inside && generation.get()==token && view===this) {hide?.let(this@RecognitionBanner.handler::removeCallbacks);hide=null;resultDeadline=Long.MAX_VALUE}
                }
                val handled=super.dispatchTouchEvent(event)
                if(event.actionMasked==android.view.MotionEvent.ACTION_UP || event.actionMasked==android.view.MotionEvent.ACTION_CANCEL) {
                    val resume=touchingCard;touchingCard=false
                    if(resume && generation.get()==token && view===this && !accountPanel.showing)scheduleResultExpiry(token)
                }
                return handled
            }
            override fun dispatchKeyEvent(event:android.view.KeyEvent):Boolean {
                if(event.keyCode==android.view.KeyEvent.KEYCODE_BACK) { if(event.action==android.view.KeyEvent.ACTION_UP) { if(accountPanel.showing) accountPanel.close() else removeNow() }; return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusableInTouchMode=true;setPadding(colors.dp(16),colors.dp(12),colors.dp(16),colors.dp(16));clipChildren=false;clipToPadding=false
            setOnClickListener { if(accountPanel.showing)accountPanel.close() else removeNow() }
        }
        val card=resultBar(record,token)
        card.setOnClickListener { }
        root.addView(card,FrameLayout.LayoutParams(width,-2).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL })
        val params=WindowManager.LayoutParams(width+colors.dp(32),-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            // Accessibility windows have different default inset fitting across Android versions.
            // Use screen coordinates and the current system inset once, rather than fitting twice.
            setFitInsetsTypes(0)
            gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y=windows.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()).top+colors.dp(4)
        }
        try {
            windows.addView(root,params);view=root;cardView=card;delivery="capsule";lastWindowType=params.type;lastFailure="";root.requestFocus();scheduleResultExpiry(token)
        } catch(error:Exception) {
            if(root.isAttachedToWindow)runCatching {windows.removeViewImmediate(root)}
            lastFailure=error.javaClass.simpleName;delivery="toast";lastWindowType=0
            android.widget.Toast.makeText(service,"已记账，请在账单中查看",android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun resultBar(record:LedgerRecord,token:Int):LinearLayout {
        val colors=ResultColors(service)
        val card=LinearLayout(service).apply {
            orientation=LinearLayout.VERTICAL;setPadding(colors.dp(12),colors.dp(2),colors.dp(12),colors.dp(2))
            background=colors.background(colors.surface,32);elevation=colors.dp(18).toFloat();clipToOutline=true
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val actions=LinearLayout(service).apply {gravity=Gravity.CENTER_VERTICAL}
        val name=record.accountUid?.let {uid ->service.ledger.store.accounts().firstOrNull {it.uid==uid}?.name}?:"未关联账户"
        fun current()=generation.get()==token && cardView===card && view?.isAttachedToWindow==true
        card.addView(colors.text((if(record.bill.direction==net.ankio.ledger.core.Direction.EXPENSE) "− ¥ " else "+ ¥ ")+Money.display(record.bill.amountCents?:0),30,true).apply { gravity=Gravity.CENTER; setTextColor(colors.ink); includeFontPadding=true;tag="result-amount";minimumHeight=colors.dp(40) },LinearLayout.LayoutParams(-1,-2))
        val merchant=LinearLayout(service).apply { gravity=Gravity.CENTER; setPadding(colors.dp(8),0,colors.dp(8),0);tag="result-merchant" }
        val merchantIcon=android.widget.ImageView(service).apply {setImageDrawable(colors.pictogram("merchant"));scaleType=android.widget.ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        merchant.addView(merchantIcon,LinearLayout.LayoutParams(colors.dp(24),colors.dp(24)))
        Thread { val bytes=service.ledger.store.merchantIcon(record.id);val bitmap=bytes?.let {BitmapFactory.decodeByteArray(it,0,it.size,BitmapFactory.Options().apply {inSampleSize=2})}; merchantIcon.post {if(current() && bitmap!=null)merchantIcon.setImageBitmap(bitmap)} }.start()
        merchant.addView(colors.text("${record.bill.merchant} (${record.bill.category})",14,false).apply { setTextColor(colors.secondary);maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END;contentDescription="${record.bill.merchant}，${record.bill.category}" },LinearLayout.LayoutParams(-2,-2).apply {leftMargin=colors.dp(8)})
        card.addView(merchant,LinearLayout.LayoutParams(-1,colors.dp(24)))
        card.addView(View(service).apply {setBackgroundColor(if(colors.dark)0xff3e4a45.toInt() else 0xffe5ece9.toInt())},LinearLayout.LayoutParams(-1,colors.dp(1)).apply {topMargin=colors.dp(2);bottomMargin=colors.dp(2)})
        val reimbursement=LinearLayout(service).apply {gravity=Gravity.CENTER_VERTICAL}
        reimbursement.addView(colors.accountGlyph("receipt").apply {translationY=colors.dp(4).toFloat()},LinearLayout.LayoutParams(colors.dp(24),colors.dp(28)))
        reimbursement.addView(colors.text("标记为待报销",16,true).apply {translationY=colors.dp(4).toFloat()},LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=colors.dp(8)})
        val accountButton=colors.action(name).apply {
            gravity=Gravity.START or Gravity.CENTER_VERTICAL;textSize=13f;setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.MIDDLE
            val kind=service.ledger.store.accounts().firstOrNull {it.uid==record.accountUid}?.kind?:"custom"
            val left=colors.pictogram(kind).apply {setBounds(0,0,colors.dp(20),colors.dp(20))}
            val right=colors.symbol("chevron").apply {setBounds(0,0,colors.dp(12),colors.dp(12))}
            setCompoundDrawables(left,null,right,null)
            compoundDrawablePadding=colors.dp(6);tag="result-account"
            contentDescription="当前扣款账户，$name，点击更换"
            text=name; setOnClickListener {if(current()) {if(accountPanel.showing)accountPanel.close() else showAccountPanel(record.id,record.uid)}}
            // Bring the two visual rows closer without overlapping their 48dp hit regions.
            setTextColor(colors.ink);background=android.graphics.drawable.InsetDrawable(colors.background(if(colors.dark)0xff2c3632.toInt() else 0xfff2f6f7.toInt(),16),0,colors.dp(4),0,colors.dp(4));setPadding(colors.dp(8),0,colors.dp(8),0)
        }
        actions.addView(accountButton,LinearLayout.LayoutParams(-1,colors.dp(48)))
        val error=colors.text("",14).apply {tag="result-error";setTextColor(colors.error);visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        reimbursement.addView(colors.reimbursementSwitch(record.requiresReimbursement).apply {
            setOnCheckedChangeListener(object:CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(button:CompoundButton,enabled:Boolean) {
                    if(!current())return
                    val latest=currentResult(record.id,record.uid)
                    if(latest==null) {removeNow();return}
                    runCatching {service.ledger.store.setRequiresReimbursement(record.id,record.uid,enabled)}
                        .onSuccess {
                            error.visibility=View.GONE;error.text=""
                            service.ledger.changed();if(!accountPanel.showing)scheduleResultExpiry(token)
                        }
                        .onFailure {
                            button.setOnCheckedChangeListener(null);button.isChecked=latest.requiresReimbursement
                            button.setOnCheckedChangeListener(this)
                            error.text="更改失败，请重试";error.visibility=View.VISIBLE
                        }
                }
            })
        },LinearLayout.LayoutParams(-2,-2).apply {leftMargin=colors.dp(12)})
        card.addView(reimbursement,LinearLayout.LayoutParams(-1,colors.dp(48)))
        card.addView(actions,LinearLayout.LayoutParams(-1,-2))
        card.addView(error)
        card.addView(LinearLayout(service).apply { tag="account-menu-host" },LinearLayout.LayoutParams(-1,-2))
        return card
    }
    /** Main-thread only: selection keeps the successful result's identity and expiry. */
    fun showAccountPanel(id:Long,uid:String):Boolean {
        if(Looper.myLooper()!=Looper.getMainLooper())return false
        val record=currentResult(id,uid)?:return false
        if(accountPanel.showing)return false
        val token=generation.get()
        val defaults=service.ledger.store.channelDefaultAccounts().values.toSet()+listOfNotNull(service.ledger.store.defaultAccount()?.uid)
        val opened=accountPanel.show(service.ledger.store.accounts().sortedByDescending {it.uid in defaults},record.accountUid,cardView?:return false,choose={accountUid ->
            if(generation.get()!=token || currentResult(id,uid)==null)"此账单提示已结束，请重新打开账单"
            else runCatching {service.ledger.store.setRecordAccount(id,uid,accountUid);service.ledger.changed(); val account=service.ledger.store.accounts().firstOrNull {it.uid==accountUid};val name=account?.name?:"未关联账户";cardView?.findViewWithTag<TextView>("result-account")?.apply {text=name;contentDescription="当前扣款账户，$name，点击更换"; val icon=ResultColors(service).pictogram(account?.kind?:"custom").apply {setBounds(0,0,ResultColors(service).dp(20),ResultColors(service).dp(20))};setCompoundDrawables(icon,null,compoundDrawables[2],null)};cardView?.findViewWithTag<TextView>("result-error")?.visibility=View.GONE}
                .exceptionOrNull()?.let { cardView?.findViewWithTag<TextView>("result-error")?.apply {text="更换失败，请重试";visibility=View.VISIBLE};"更换失败，请重试" }
        },closed={
            if(generation.get()==token) scheduleResultExpiry(token)
        })
        if(opened) {
            hide?.let(handler::removeCallbacks);hide=null
            // The selection owns focus and lifetime until Back, outside touch, or a choice.
            resultDeadline=Long.MAX_VALUE
            cardView?.requestLayout()
        }
        return opened
    }
    /** Actionable errors and permission guidance only; never an ordinary progress/success carrier. */
    private fun showCapsule(display:String,onRepair:(()->Unit)?) {
            val colors=ResultColors(service)
            fun dp(n:Int)=colors.dp(n)
            val row=LinearLayout(service).apply {
                gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12))
                background=colors.background(colors.surface,28)
                elevation=dp(18).toFloat();importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                if(onRepair!=null)minimumHeight=dp(48)
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            if(onRepair!=null)row.setOnClickListener { removeNow();onRepair() }
            if(onRepair!=null)row.contentDescription="$display，点击补齐设置"
            row.addView(colors.text(display,14).apply {setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=service.resources.displayMetrics.widthPixels-dp(104)},LinearLayout.LayoutParams(-2,-2))
            val params=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or if(onRepair==null)WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
                android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=dp(16) }
            var added=false
            lastFailure="";lastWindowType=0
            try { windows.addView(row,params);view=row;lastWindowType=params.type;added=true }
            catch(error:Exception) {
                lastFailure=error.javaClass.simpleName
                if(row.isAttachedToWindow)runCatching { windows.removeViewImmediate(row) }
            }
            if(added) {
                delivery="capsule"
                if(android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    row.alpha=0f;row.translationY=dp(52).toFloat()
                    row.animate().alpha(1f).translationY(0f).setDuration(440).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()
                }
                hide=Runnable { row.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(220).withEndAction { if(view===row) removeNow() }.start() };handler.postDelayed(hide!!,3600)
            } else {
                view=null
                delivery="toast"
                android.widget.Toast.makeText(service,display,android.widget.Toast.LENGTH_LONG).show()
            }
    }
    private fun compactMessage(message:String):String=when {
        message.startsWith("已记账 ¥") -> message
        "全额退款" in message -> "已全额退款，不予记账"
        "这是退款记录" in message || message==net.ankio.ledger.core.StandaloneRefundReceipt.MESSAGE -> "退款记录，不予记账"
        "无法确认账单窗口" in message -> "请收起浮层后重试"
        "页面正在切换" in message || "页面已经切换" in message -> "页面已切换，请重试"
        "不是已支持的应用" in message -> "请进入支持的账单页面"
        "截图" in message && ("无法" in message || "不能" in message) -> "当前页面无法截图"
        "无障碍" in message || "页面识别权限" in message -> DevicePermissionGuidance.current.permissionMessage
        "未识别到" in message -> "未识别到金额，请重试"
        "正在识别" in message -> "正在识别…"
        message.length<=20 -> message
        "识别失败" in message -> "识别失败，请重试"
        "权限" in message -> "权限不足，请检查设置"
        else -> message.replace("\n"," ").take(19)+"…"
    }
    private fun clearOverlay() { hide?.let(handler::removeCallbacks);hide=null;accountPanel.close(false);view?.let { it.animate().cancel();try { windows.removeViewImmediate(it) } catch(_:Exception) {} };view=null;cardView=null }
    fun removeNow() { generation.incrementAndGet();clearOverlay();live.cancel();nativeDelivery=false;resultIdentity=null;resultDeadline=0 }
    fun close() { val token=generation.incrementAndGet();handler.post { if(generation.get()==token)removeNow() } }
}
