package net.ankio.ledger

import android.app.UiAutomation
import android.app.NotificationManager
import android.app.Notification
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.RecognitionBanner
import net.ankio.ledger.capture.RecognitionLiveUpdate
import net.ankio.ledger.capture.ScreenCaptureService
import net.ankio.ledger.capture.captureServiceComponent
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.LedgerAccount
import net.ankio.ledger.data.AccountType
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual accessibility overlay captures; fixture names and amounts are synthetic. */
@RunWith(AndroidJUnit4::class)
class ResultAccountPopupTest {
    @Test fun touchGesturesResumeExpiryOutsideAndKeepMenuLifetime() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue("Requires disposable QA application",app.packageName=="net.ankio.ledger.qa")
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val previousServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val previousEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成手势时限 ${UUID.randomUUID()}",now),
            ParsedBill(2250,Direction.EXPENSE,"合成测试商户","餐饮",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",timeExplicit=true)) as IngestResult.Saved).record
        var banner:RecognitionBanner?=null
        val evidence=org.json.JSONArray()
        var removal=org.json.JSONObject()
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor {ScreenCaptureService.current!=null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                instrumentation.runOnMainSync {banner=ScreenCaptureService::class.java.getDeclaredMethod("getBanner").apply {isAccessible=true}.invoke(ScreenCaptureService.current) as RecognitionBanner}
                val active=banner!!
                fun show() {
                    instrumentation.runOnMainSync {active.removeNow();active.show("已记账 ¥22.50",recordId=record.id)}
                    waitFor {active.diagnostics().getBoolean("attached") && resultRoot(active).width>0 && panelRoot(active).hasWindowFocus()}
                    automation.waitForIdle(100,1_000)
                    removal=org.json.JSONObject()
                    instrumentation.runOnMainSync {panelRoot(active).addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view:View) {}
                        override fun onViewDetachedFromWindow(view:View) {
                            removal.put("atElapsedMs",SystemClock.elapsedRealtime()).put("stack",Thread.currentThread().stackTrace.joinToString("\n"))
                        }
                    })}
                }
                fun timerState():org.json.JSONObject {
                    val state=org.json.JSONObject()
                    instrumentation.runOnMainSync {
                        state.put("attached",active.diagnostics().getBoolean("attached"))
                            .put("menuOpen",active.diagnostics().getBoolean("accountPanelOpen"))
                            .put("deadline",RecognitionBanner::class.java.getDeclaredField("resultDeadline").apply {isAccessible=true}.getLong(active))
                            .put("timerScheduled",RecognitionBanner::class.java.getDeclaredField("hide").apply {isAccessible=true}.get(active)!=null)
                            .put("atElapsedMs",SystemClock.elapsedRealtime())
                            .put("generation",(RecognitionBanner::class.java.getDeclaredField("generation").apply {isAccessible=true}.get(active) as java.util.concurrent.atomic.AtomicInteger).get())
                        val owner=RecognitionBanner::class.java.getDeclaredField("handler").apply {isAccessible=true}.get(active) as android.os.Handler
                        val callback=RecognitionBanner::class.java.getDeclaredField("hide").apply {isAccessible=true}.get(active) as? Runnable
                        state.put("viewHandlerIsOwner",if(active.diagnostics().getBoolean("attached"))panelRoot(active).handler===owner else false)
                            .put("callbackQueued",callback!=null && owner.hasCallbacks(callback))
                    }
                    return state
                }
                fun event(action:Int,time:Long,x:Float,y:Float,mayDrop:Boolean=false):Boolean {
                    val motion=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                    return try {automation.injectInputEvent(motion,true).also {if(!mayDrop)assertTrue(it)}} finally {motion.recycle()}
                }
                fun gesture(kind:String):org.json.JSONObject {
                    val card=resultRoot(active);val root=panelRoot(active);val at=IntArray(2);card.getLocationOnScreen(at)
                    val received=org.json.JSONArray()
                    instrumentation.runOnMainSync {card.setOnTouchListener {_,motion ->
                        received.put(org.json.JSONObject().put("action",motion.actionMasked).put("xWithinCard",motion.x).put("yWithinCard",motion.y).put("rawX",motion.rawX).put("rawY",motion.rawY).put("atElapsedMs",SystemClock.elapsedRealtime()));false
                    }}
                    val fromX=at[0]+card.width/2f;val fromY=at[1]+app.resources.displayMetrics.density*18
                    val endX=if(kind=="inside")fromX else at[0]+card.width+app.resources.displayMetrics.density*(if(kind=="beyond-window")32 else 8)
                    val downAt=SystemClock.uptimeMillis()
                    event(MotionEvent.ACTION_DOWN,downAt,fromX,fromY)
                    waitFor {timerState().getLong("deadline")==Long.MAX_VALUE}
                    val down=timerState()
                    assertEquals("A real inside press must pause this result",Long.MAX_VALUE,down.getLong("deadline"))
                    SystemClock.sleep(350)
                    event(MotionEvent.ACTION_MOVE,downAt,endX,fromY)
                    val terminalAt=SystemClock.elapsedRealtime()
                    event(if(kind=="cancel-shadow")MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,downAt,endX,fromY)
                    var terminal=timerState();val dispatchDeadline=SystemClock.elapsedRealtime()+1_000
                    while(terminal.getBoolean("attached") && !terminal.getBoolean("timerScheduled") && !terminal.getBoolean("menuOpen") && SystemClock.elapsedRealtime()<dispatchDeadline) {SystemClock.sleep(25);terminal=timerState()}
                    val row=org.json.JSONObject().put("gesture",kind).put("windowWidth",root.width).put("cardWidth",card.width).put("fromX",fromX).put("fromY",fromY).put("endX",endX).put("down",down).put("terminal",terminal).put("terminalAtElapsedMs",terminalAt).put("receivedCardEvents",received)
                    app.openFileOutput("popup-gesture-last-terminal.json",0).use {it.write(row.toString(2).toByteArray())}
                    return row
                }
                for(kind in listOf("up-shadow","beyond-window","cancel-shadow","inside")) {
                    show();val row=gesture(kind);val deadline=SystemClock.elapsedRealtime()+5_600
                    val terminal=row.getJSONObject("terminal")
                    assertTrue("A closed-menu terminal restores the owner's real timer: $row",terminal.getBoolean("timerScheduled") && terminal.getBoolean("callbackQueued") && terminal.getLong("deadline")!=Long.MAX_VALUE)
                    while(active.diagnostics().getBoolean("attached") && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50)
                    row.put("expired",!active.diagnostics().getBoolean("attached")).put("closedAfterTerminalMs",SystemClock.elapsedRealtime()-row.getLong("terminalAtElapsedMs")).put("removal",removal);evidence.put(row)
                    app.openFileOutput("popup-gesture-expiry-evidence.json",0).use {it.write(evidence.toString(2).toByteArray())}
                    assertTrue("A completed $kind gesture must resume result expiry: $row",row.getBoolean("expired"))
                    assertTrue("The old show-time callback must not shorten fresh expiry: $row",row.getLong("closedAfterTerminalMs")>=4_900)
                }
                show()
                val account=button(resultRoot(active),"账户：");val accountAt=IntArray(2);account.getLocationOnScreen(accountAt);val accountTime=SystemClock.uptimeMillis()
                val accountX=accountAt[0]+account.width/2f;val accountY=accountAt[1]+account.height/2f
                event(MotionEvent.ACTION_DOWN,accountTime,accountX,accountY)
                waitFor {timerState().getLong("deadline")==Long.MAX_VALUE}
                event(MotionEvent.ACTION_UP,accountTime,accountX,accountY)
                waitFor {active.diagnostics().getBoolean("accountPanelOpen") && visualReady(panelRoot(active))}
                val menu=gesture("up-shadow");SystemClock.sleep(5_200)
                menu.put("gesture","menu-up-shadow").put("retained",active.diagnostics().getBoolean("attached") && active.diagnostics().getBoolean("accountPanelOpen"))
                evidence.put(menu)
                // Removal/replacement may cancel an in-flight stream; its old terminal must not revive a card.
                for(replace in listOf(false,true)) {
                    show();val card=resultRoot(active);val at=IntArray(2);card.getLocationOnScreen(at);val time=SystemClock.uptimeMillis()
                    val x=at[0]+card.width/2f;val y=at[1]+app.resources.displayMetrics.density*18
                    event(MotionEvent.ACTION_DOWN,time,x,y)
                    instrumentation.runOnMainSync {if(replace)active.show("正在识别…",loading=true) else active.removeNow()}
                    instrumentation.waitForIdleSync()
                    val accepted=event(MotionEvent.ACTION_UP,time,at[0]+card.width+app.resources.displayMetrics.density*8,y,mayDrop=true)
                    SystemClock.sleep(200)
                    val noCard=RecognitionBanner::class.java.getDeclaredField("cardView").apply {isAccessible=true}.get(active)==null
                    evidence.put(org.json.JSONObject().put("gesture",if(replace)"new-generation" else "removed").put("noRevival",noCard).put("oldTerminalAccepted",accepted))
                }
                app.openFileOutput("popup-gesture-expiry-evidence.json",0).use {it.write(evidence.toString(2).toByteArray())}
                assertTrue("Every closed-menu terminal must restore expiry: $evidence",(0 until 4).all {evidence.getJSONObject(it).getBoolean("expired")})
                assertTrue("The open menu continues owning lifetime",menu.getBoolean("retained"))
                assertTrue((5..6).all {evidence.getJSONObject(it).getBoolean("noRevival")})
            }
        } finally {
            instrumentation.runOnMainSync {banner?.removeNow()};app.store.delete(record.id)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",previousServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",previousEnabled)
            automation.dropShellPermissionIdentity()
        }
    }
    @Test fun failedReimbursementRollsBackAndSameSwitchCanRetry() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue("Requires disposable QA application",app.packageName=="net.ankio.ledger.qa")
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val previousServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val previousEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val previousDefault=app.store.defaultAccount()?.uid
        val account=app.store.saveAccount(LedgerAccount(name="合成日常账户",amountCents=10_000))
        app.store.setDefaultAccount(account.uid)
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成报销恢复 ${UUID.randomUUID()}",now),
            ParsedBill(2250,Direction.EXPENSE,"合成测试商户","餐饮",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",timeExplicit=true)) as IngestResult.Saved).record
        val trigger="qa_reimbursement_retry_${record.id}"
        var banner:RecognitionBanner?=null
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor {ScreenCaptureService.current!=null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                instrumentation.runOnMainSync {
                    banner=ScreenCaptureService::class.java.getDeclaredMethod("getBanner").apply {isAccessible=true}.invoke(ScreenCaptureService.current) as RecognitionBanner
                    banner!!.show("已记账 ¥22.50",recordId=record.id)
                }
                waitFor {banner!!.diagnostics().getBoolean("attached")}
                val root=resultRoot(banner!!)
                val toggle=button(root,"标记报销") as android.widget.CompoundButton
                app.store.writableDatabase.execSQL("CREATE TEMP TRIGGER $trigger BEFORE UPDATE OF requires_reimbursement ON records WHEN OLD.id=${record.id} BEGIN SELECT RAISE(ABORT,'synthetic retry failure'); END")
                instrumentation.runOnMainSync {toggle.performClick()}
                assertFalse(app.store.get(record.id)!!.requiresReimbursement)
                assertFalse(toggle.isChecked)
                assertTrue(banner!!.diagnostics().getBoolean("attached"))
                val error=findText(root,"更改失败，请重试")
                assertEquals(View.VISIBLE,error.visibility)
                SystemClock.sleep(250);capture(app,automation,"popup-reimbursement-error.png")
                app.store.writableDatabase.execSQL("DROP TRIGGER $trigger")
                instrumentation.runOnMainSync {toggle.performClick()}
                assertTrue("The same switch must persist after the transient failure ends",app.store.get(record.id)!!.requiresReimbursement)
                assertTrue(toggle.isChecked)
                assertEquals(View.GONE,error.visibility)
                SystemClock.sleep(250);capture(app,automation,"popup-reimbursement-retry.png")
                instrumentation.runOnMainSync {toggle.performClick()}
                assertFalse(app.store.get(record.id)!!.requiresReimbursement)
                assertFalse(toggle.isChecked)
                assertEquals(7_750L,app.store.getAccount(account.id)!!.amountCents)
            }
        } finally {
            app.store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            instrumentation.runOnMainSync {banner?.removeNow()}
            app.store.delete(record.id);app.store.deleteAccount(account.id);app.store.setDefaultAccount(previousDefault)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",previousServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",previousEnabled)
            automation.dropShellPermissionIdentity()
        }
    }
    private fun findText(root:View,label:String):android.widget.TextView {
        if(root is android.widget.TextView && root.text.toString()==label)return root
        if(root is ViewGroup)for(index in 0 until root.childCount)runCatching {findText(root.getChildAt(index),label)}.getOrNull()?.let {return it}
        error("Visible feedback missing: $label")
    }
    @Test fun longAccountNamesAndManySignedBalancesStayScrollable() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue("Requires disposable QA application",app.packageName=="net.ankio.ledger.qa")
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val previousServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val previousEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val previousDefault=app.store.defaultAccount()?.uid
        val fixtures=(0..9).map {index ->app.store.saveAccount(LedgerAccount(
            name=when(index) {0->"工商银行信用卡 (尾号8848)";1->"工商银行信用卡 (尾号8899)";2->"合成"+"很长的账户名称".repeat(6).take(37);else->"合成备用账户${index+1}"},
            kind=if(index<2)"bank_icbc" else "custom",
            amountCents=if(index==0)-123_456 else 10_000L+index,
            type=if(index==2)AccountType.LIABILITY else AccountType.ASSET))}
        app.store.setDefaultAccount(fixtures.first().uid)
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成长账户 ${UUID.randomUUID()}",now),
            ParsedBill(2250,Direction.EXPENSE,"合成测试商户","餐饮",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",timeExplicit=true)) as IngestResult.Saved).record
        var banner:RecognitionBanner?=null
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor {ScreenCaptureService.current!=null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                instrumentation.runOnMainSync {
                    banner=ScreenCaptureService::class.java.getDeclaredMethod("getBanner").apply {isAccessible=true}.invoke(ScreenCaptureService.current) as RecognitionBanner
                    banner!!.show("已记账 ¥ 22.50",recordId=record.id)
                }
                waitFor {banner!!.diagnostics().getBoolean("attached")}
                SystemClock.sleep(300);capture(app,automation,"popup-long-account-result.png")
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"账户：").performClick()}
                waitFor {banner!!.diagnostics().getBoolean("accountPanelOpen") && panelRoot(banner!!).isLaidOut}
                waitFor {visualReady(panelRoot(banner!!).findViewWithTag("account-option:${fixtures.first().name}"))}
                val scroll=findScroll(panelRoot(banner!!))
                assertFalse("System scrollbars are replaced by mint UI",scroll.isVerticalScrollBarEnabled)
                assertFalse(scroll.isHorizontalScrollBarEnabled)
                val frame=scroll.parent as ViewGroup
                assertEquals("mint-scroll-frame",frame.tag)
                assertTrue(frame.clipToOutline)
                val card=resultRoot(banner!!) as ViewGroup
                assertTrue("Rounded result clips its expanding menu",card.clipToOutline)
                val merchant=card.findViewWithTag<android.widget.LinearLayout>("result-merchant")
                val first=merchant.getChildAt(0);val last=merchant.getChildAt(merchant.childCount-1)
                assertTrue("Merchant icon and name are centered as one group",kotlin.math.abs((first.left+last.right)/2f-merchant.width/2f)<=2)
                val labelEvidence=org.json.JSONArray()
                fixtures.take(3).forEachIndexed {index,account ->
                    val label=panelRoot(banner!!).findViewWithTag<android.widget.TextView>("account-name:${account.name}")
                    assertNotNull(label.layout)
                    assertEquals("Account labels default to one line",1,label.layout.lineCount)
                    val ellipsisStart=label.layout.getEllipsisStart(0)
                    val ellipsisCount=label.layout.getEllipsisCount(0)
                    if(index<2)assertTrue("Keep the distinguishing account tail visible",ellipsisCount==0 || ellipsisStart+ellipsisCount<=account.name.indexOf(if(index==0)"8848" else "8899"))
                    else assertTrue("The very long account name must be visibly ellipsized",ellipsisCount>0)
                    val choice=panelRoot(banner!!).findViewWithTag<View>("account-option:${account.name}")
                    assertTrue("The accessible account label remains complete",choice.contentDescription.toString().contains(account.name))
                    // Existing QA accounts may precede these fixtures in the compact list.
                    instrumentation.runOnMainSync {scroll.scrollTo(0,choice.top)}
                    instrumentation.waitForIdleSync()
                    val visible=android.graphics.Rect();assertTrue(label.getGlobalVisibleRect(visible))
                    assertEquals("Do not clip the final account digits",label.height,visible.height())
                    labelEvidence.put(org.json.JSONObject().put("name",account.name).put("lineCount",label.layout.lineCount).put("ellipsisStart",ellipsisStart).put("ellipsisCount",ellipsisCount).put("rowHeightDp",choice.height/app.resources.displayMetrics.density).put("accessibleName",choice.contentDescription))
                }
                app.openFileOutput("popup-account-label-evidence.json",0).use {it.write(labelEvidence.toString(2).toByteArray())}
                instrumentation.runOnMainSync {scroll.scrollTo(0,0)}
                instrumentation.waitForIdleSync()
                capture(app,automation,"popup-many-accounts-top.png")
                assertTrue("Many accounts must scroll at the native window boundary",scroll.canScrollVertically(1))
                assertTrue(button(panelRoot(banner!!),"账户：").getGlobalVisibleRect(android.graphics.Rect()))
                instrumentation.runOnMainSync {scroll.fullScroll(View.FOCUS_DOWN)}
                SystemClock.sleep(300);capture(app,automation,"popup-many-accounts-bottom.png")
                val none=button(panelRoot(banner!!),"不关联账户")
                assertTrue(none.getGlobalVisibleRect(android.graphics.Rect()))
                instrumentation.runOnMainSync {none.performClick()}
                assertNull(app.store.get(record.id)!!.accountUid)
                assertEquals(-123_456L,app.store.getAccount(fixtures.first().id)!!.amountCents)
            }
        } finally {
            instrumentation.runOnMainSync {banner?.removeNow()};app.store.delete(record.id)
            fixtures.forEach {app.store.deleteAccount(it.id)};app.store.setDefaultAccount(previousDefault)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",previousServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",previousEnabled)
            automation.dropShellPermissionIdentity()
        }
    }
    private fun findScroll(root:View):android.widget.ScrollView {
        if(root is android.widget.ScrollView)return root
        if(root is ViewGroup)for(index in 0 until root.childCount)runCatching {findScroll(root.getChildAt(index))}.getOrNull()?.let {return it}
        error("Account list scroll view missing")
    }
    private fun visualReady(view:View):Boolean {
        var node:View?=view
        while(node!=null) {if(!node.isLaidOut || node.alpha<.99f || node.transitionAlpha<.99f)return false;node=node.parent as? View}
        return view.isShown
    }
    @Test fun accountChoicePausesExpiryAndClosedOrReplacedActionsStayClosed() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue("Requires disposable QA application",app.packageName=="net.ankio.ledger.qa")
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val previousServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val previousEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val previousDefault=app.store.defaultAccount()?.uid
        val first=app.store.saveAccount(LedgerAccount(name="合成日常账户",amountCents=10_000))
        val second=app.store.saveAccount(LedgerAccount(name="合成备用账户",amountCents=5_000))
        app.store.setDefaultAccount(first.uid)
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成账户弹窗 ${UUID.randomUUID()}",now),
            ParsedBill(2250,Direction.EXPENSE,"合成测试商户","餐饮",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",timeExplicit=true)) as IngestResult.Saved).record
        var banner:RecognitionBanner?=null
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor {ScreenCaptureService.current!=null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                val service=ScreenCaptureService.current!!
                instrumentation.runOnMainSync {
                    banner=ScreenCaptureService::class.java.getDeclaredMethod("getBanner").apply {isAccessible=true}.invoke(service) as RecognitionBanner
                    banner!!.show("已记账 ¥ 22.50",recordId=record.id)
                }
                waitFor {banner!!.diagnostics().getBoolean("attached")}
                assertEquals(first.uid,app.store.get(record.id)!!.accountUid)
                instrumentation.runOnMainSync {assertFalse(banner!!.showAccountPanel(record.id,"stale-uid"))}
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"账户：").performClick()}
                waitFor {banner!!.diagnostics().getBoolean("accountPanelOpen")}
                SystemClock.sleep(350);capture(app,automation,"popup-account-panel.png")
                instrumentation.runOnMainSync {button(panelRoot(banner!!),"标记报销").performClick()}
                assertTrue(app.store.get(record.id)!!.requiresReimbursement)
                assertTrue(banner!!.diagnostics().getBoolean("accountPanelOpen"))
                SystemClock.sleep(250);capture(app,automation,"popup-menu-reimbursement-on.png")
                instrumentation.runOnMainSync {button(panelRoot(banner!!),"标记报销").performClick()}
                assertFalse(app.store.get(record.id)!!.requiresReimbursement)
                // A choice can take longer than the original five-second result lifetime.
                SystemClock.sleep(5_200)
                assertTrue(banner!!.diagnostics().getBoolean("accountPanelOpen"))
                val root=panelRoot(banner!!)
                val focus="hasWindowFocus=${root.hasWindowFocus()}, hasFocus=${root.hasFocus()}, focused=${root.findFocus()?.javaClass?.name}, flags=${(root.layoutParams as android.view.WindowManager.LayoutParams).flags}"
                val keyAt=SystemClock.uptimeMillis()
                automation.injectInputEvent(KeyEvent(keyAt,keyAt,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true)
                automation.injectInputEvent(KeyEvent(keyAt,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true)
                waitFor({"Back did not close account selector and resume result: $focus ${banner!!.diagnostics()}"}) {!banner!!.diagnostics().getBoolean("accountPanelOpen") && banner!!.diagnostics().getBoolean("attached")}
                SystemClock.sleep(300);capture(app,automation,"popup-after-back.png")
                val dismissAt=SystemClock.uptimeMillis()
                automation.injectInputEvent(KeyEvent(dismissAt,dismissAt,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true)
                automation.injectInputEvent(KeyEvent(dismissAt,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true)
                waitFor { !banner!!.diagnostics().getBoolean("attached") }
                instrumentation.runOnMainSync {banner!!.show("已记账 ¥22.50",recordId=record.id)}
                waitFor {banner!!.diagnostics().getBoolean("attached")}
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"账户：").performClick()}
                waitFor {banner!!.diagnostics().getBoolean("accountPanelOpen")}
                waitFor {panelRoot(banner!!).hasWindowFocus() && panelRoot(banner!!).isLaidOut}
                val target=org.json.JSONObject(web.text("JSON.stringify((()=>{const r=document.querySelector('#settings .nav-item[aria-label=\"资产\"]').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth}})())"))
                val webOffset=IntArray(2);var webWidth=0
                scenario.onActivity {it.webView.getLocationOnScreen(webOffset);webWidth=it.webView.width}
                val touchAt=SystemClock.uptimeMillis()
                val x=webOffset[0]+(target.getDouble("x")*webWidth/target.getDouble("width")).toFloat()
                val y=webOffset[1]+(target.getDouble("y")*webWidth/target.getDouble("width")).toFloat()
                listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEach {action ->
                    val event=MotionEvent.obtain(touchAt,SystemClock.uptimeMillis(),action,x,y,0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try {automation.injectInputEvent(event,true)} finally {event.recycle()}
                }
                waitFor({"Outside touch did not close selector: ${banner!!.diagnostics()}"}) {!banner!!.diagnostics().getBoolean("accountPanelOpen") && banner!!.diagnostics().getBoolean("attached")}
                web.await("page==='assets'")
                web.run("go('settings')")
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"账户：").performClick()}
                waitFor {banner!!.diagnostics().getBoolean("accountPanelOpen")}
                val oldChoice=button(panelRoot(banner!!),second.name)
                instrumentation.runOnMainSync {oldChoice.performClick()}
                waitFor {app.store.get(record.id)!!.accountUid==second.uid && !banner!!.diagnostics().getBoolean("accountPanelOpen")}
                assertEquals(10_000L,app.store.getAccount(first.id)!!.amountCents)
                assertEquals(2_750L,app.store.getAccount(second.id)!!.amountCents)
                SystemClock.sleep(350);capture(app,automation,"popup-account-selected.png")
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"标记报销").performClick()}
                assertTrue(app.store.get(record.id)!!.requiresReimbursement)
                assertTrue(banner!!.diagnostics().getBoolean("attached"))
                SystemClock.sleep(300);capture(app,automation,"popup-reimbursement-on.png")
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"标记报销").performClick()}
                assertFalse(app.store.get(record.id)!!.requiresReimbursement)
                SystemClock.sleep(300);capture(app,automation,"popup-reimbursement-off.png")
                val oldSwitch=button(resultRoot(banner!!),"标记报销")
                instrumentation.runOnMainSync {oldChoice.performClick()}
                assertFalse(banner!!.diagnostics().getBoolean("accountPanelOpen"))
                assertEquals(2_750L,app.store.getAccount(second.id)!!.amountCents)
                // A new capture invokes this exact synchronous cleanup before acquiring pixels.
                instrumentation.runOnMainSync {banner!!.removeNow();banner!!.show("已记账 ¥ 22.50",recordId=record.id)}
                waitFor {banner!!.diagnostics().getBoolean("attached")}
                instrumentation.runOnMainSync {assertTrue(banner!!.showAccountPanel(record.id,record.uid));banner!!.removeNow()}
                assertFalse(banner!!.diagnostics().getBoolean("accountPanelOpen"))
                assertFalse(banner!!.diagnostics().getBoolean("attached"))
                instrumentation.runOnMainSync {oldSwitch.performClick()}
                assertFalse(app.store.get(record.id)!!.requiresReimbursement)
                unifiedResultCancelsNativeProgress(app,service,banner!!,record.id)
            }
        } finally {
            instrumentation.runOnMainSync {banner?.removeNow()}
            app.store.delete(record.id);app.store.deleteAccount(first.id);app.store.deleteAccount(second.id)
            app.store.setDefaultAccount(previousDefault)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",previousServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",previousEnabled)
            automation.dropShellPermissionIdentity()
        }
    }

    private fun unifiedResultCancelsNativeProgress(app:LedgerApp,service:ScreenCaptureService,banner:RecognitionBanner,recordId:Long) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val manager=app.getSystemService(NotificationManager::class.java)
        val live=RecognitionLiveUpdate(service)
        instrumentation.runOnMainSync {
            banner.removeNow()
            assertTrue(live.postFeedback("正在识别账单",true))
            RecognitionBanner::class.java.getDeclaredField("nativeDelivery").apply {isAccessible=true}.setBoolean(banner,true)
            banner.show("已记账 ¥22.50",recordId=recordId)
        }
        waitFor {banner.diagnostics().getBoolean("attached") && manager.activeNotifications.none {it.id in setOf(4700,4701)}}
        assertEquals("capsule",banner.diagnostics().getString("delivery"))
        assertNotNull(button(resultRoot(banner),"标记报销"))
        // API30 has no OEM admission. Publish through the actual NotificationManager seam,
        // then call the same guarded scheduler used after admission; no physical-island claim.
        val record=app.store.get(recordId)!!
        val schedule=RecognitionBanner::class.java.getDeclaredMethod("scheduleNativeResult",net.ankio.ledger.data.LedgerRecord::class.java,Int::class.javaPrimitiveType).apply {isAccessible=true}
        fun admitForTest() {
            RecognitionBanner::class.java.getDeclaredMethod("clearOverlay").apply {isAccessible=true}.invoke(banner)
            assertTrue(live.postResult("已记账 ¥22.50","合成测试商户 · 餐饮","已记账"))
            val generation=RecognitionBanner::class.java.getDeclaredField("generation").apply {isAccessible=true}.get(banner) as java.util.concurrent.atomic.AtomicInteger
            schedule.invoke(banner,record,generation.get())
        }
        val start=SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {admitForTest()}
        SystemClock.sleep(500)
        assertFalse("Automatic card must wait for the native one-second result",banner.diagnostics().getBoolean("attached"))
        assertTrue(manager.activeNotifications.any {it.id==4701})
        waitFor {banner.diagnostics().getBoolean("attached")}
        assertTrue(SystemClock.elapsedRealtime()-start>=1_000)
        assertTrue(manager.activeNotifications.none {it.id in setOf(4700,4701)})
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val backAt=SystemClock.uptimeMillis()
        automation.injectInputEvent(KeyEvent(backAt,backAt,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true)
        automation.injectInputEvent(KeyEvent(backAt,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true)
        waitFor {!banner.diagnostics().getBoolean("attached")}
        assertEquals(record.accountUid,app.store.get(record.id)!!.accountUid)
        instrumentation.runOnMainSync {banner.show("已记账 ¥22.50",recordId=record.id)}
        waitFor {banner.diagnostics().getBoolean("attached")}
        instrumentation.runOnMainSync {admitForTest();banner.removeNow()}
        SystemClock.sleep(1_200);assertFalse(banner.diagnostics().getBoolean("attached"))
        instrumentation.runOnMainSync {banner.show("已记账 ¥22.50",recordId=record.id)}
        waitFor {banner.diagnostics().getBoolean("attached")}
        instrumentation.runOnMainSync {admitForTest();banner.show("正在识别…",loading=true)}
        SystemClock.sleep(1_200)
        assertNull(RecognitionBanner::class.java.getDeclaredField("cardView").apply {isAccessible=true}.get(banner))
        instrumentation.runOnMainSync {banner.removeNow()}
    }
    private fun resultRoot(banner:RecognitionBanner)=RecognitionBanner::class.java.getDeclaredField("cardView").apply {isAccessible=true}.get(banner) as View
    private fun panelRoot(banner:RecognitionBanner):View {
        return RecognitionBanner::class.java.getDeclaredField("view").apply {isAccessible=true}.get(banner) as View
    }
    private fun button(root:View,label:String):View {
        if(label=="账户：" && root.tag=="result-account")return root
        if(label=="标记报销" && root.tag=="result-reimbursement")return root
        if(root.isClickable && ((root is Button && root.text.toString().startsWith(label)) || root.contentDescription?.toString()==label || root.tag=="account-option:$label"))return root
        if(root is ViewGroup)for(index in 0 until root.childCount)runCatching {button(root.getChildAt(index),label)}.getOrNull()?.let {return it}
        error("Button missing: $label")
    }
    @Test fun captureResultPopup() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue("Requires disposable QA application",app.packageName=="net.ankio.ledger.qa")
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val previousServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val previousEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val tag=InstrumentationRegistry.getArguments().getString("popupCapture","before")!!
        val matched=tag.startsWith("matched")
        val previousDefault=app.store.defaultAccount()?.uid
        val account=app.store.saveAccount(LedgerAccount(name=if(matched)"合成日常账户" else "工商银行信用卡 (尾号8848)",kind=if(matched)"custom" else "bank_icbc",amountCents=if(matched)10_000 else 100_000))
        val extras=if(matched)emptyList() else listOf(app.store.saveAccount(LedgerAccount(name="微信零钱",kind="wechat",amountCents=50_000)),app.store.saveAccount(LedgerAccount(name="支付宝",kind="alipay",amountCents=50_000)),app.store.saveAccount(LedgerAccount(name="储蓄卡",kind="bank",amountCents=50_000)))
        app.store.setDefaultAccount(account.uid)
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成弹窗截图 ${UUID.randomUUID()}",now),
            ParsedBill(if(matched)2250 else 12800,Direction.EXPENSE,if(matched)"合成测试商户" else "星巴克臻选",if(matched)"餐饮" else "餐饮美食",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",timeExplicit=true),
            merchantIcon=if(matched)null else java.io.File(app.filesDir,"reference-merchant-icon-qa.png").takeIf {it.isFile}?.readBytes()) as IngestResult.Saved).record
        var banner:RecognitionBanner?=null
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor { ScreenCaptureService.current!=null }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("go('assets')")
                web.await("document.querySelector('#assets')?.classList.contains('active') || getComputedStyle(document.querySelector('#assets')).display!=='none'")
                web.run("window.__popupPaintReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__popupPaintReady=true))")
                web.await("window.__popupPaintReady===true")
                automation.waitForIdle(250,2_000)
                SystemClock.sleep(400)
                capture(app,automation,"assets-$tag.png")
                web.run("go('settings')")
                web.await("document.querySelector('#settings').classList.contains('mobile-active') && getComputedStyle(document.querySelector('#settings')).display!=='none' && document.querySelector('#settings').getBoundingClientRect().height>0")
                web.run("window.__popupPaintReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__popupPaintReady=true))")
                web.await("window.__popupPaintReady===true && getComputedStyle(document.querySelector('#settings')).opacity==='1' && !manualClosing && !recordPanelClosing && !sheetClosing")
                val webReadiness=web.text("JSON.stringify({page,active:document.querySelector('#settings').classList.contains('mobile-active'),opacity:getComputedStyle(document.querySelector('#settings')).opacity,getAnimations:typeof document.getAnimations,manualClosing,recordPanelClosing,sheetClosing})")
                app.openFileOutput("popup-$tag-web-readiness.json",0).use {it.write(webReadiness.toByteArray())}
                automation.waitForIdle(250,2_000);SystemClock.sleep(400)
                val webPainted=java.util.concurrent.CountDownLatch(1)
                scenario.onActivity {activity ->
                    activity.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback() {
                        override fun onComplete(requestId:Long) {activity.webView.postInvalidateOnAnimation();webPainted.countDown()}
                    })
                }
                assertTrue("Background WebView must finish painting before overlay capture",webPainted.await(10,java.util.concurrent.TimeUnit.SECONDS))
                instrumentation.waitForIdleSync();SystemClock.sleep(100)
                automation.takeScreenshot()?.let {shot -> val bytes=java.io.ByteArrayOutputStream();shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);shot.recycle();app.store.attachScreenshot(record.id,bytes.toByteArray())}
                instrumentation.runOnMainSync {
                    val service=ScreenCaptureService.current!!
                    val getter=ScreenCaptureService::class.java.getDeclaredMethod("getBanner").apply { isAccessible=true }
                    banner=getter.invoke(service) as RecognitionBanner
                    banner!!.show("已记账 ¥ 22.50",recordId=record.id)
                }
                SystemClock.sleep(700)
                capture(app,automation,"popup-$tag.png")
                val card=resultRoot(banner!!);val density=app.resources.displayMetrics.density
                val closedHeight=card.height
                val dimensions=org.json.JSONObject().put("widthDp",card.width/density).put("heightDp",card.height/density).put("fontScale",app.resources.configuration.fontScale)
                val window=panelRoot(banner!!);val windowParams=window.layoutParams as android.view.WindowManager.LayoutParams
                val closedPosition=IntArray(2);card.getLocationOnScreen(closedPosition)
                val systemTop=app.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()).top
                assertEquals("The result must use a height-bounded top window",android.view.ViewGroup.LayoutParams.WRAP_CONTENT,windowParams.height)
                assertTrue(window.height<app.resources.displayMetrics.heightPixels/2)
                assertTrue("Keep result below system bars/cutouts",closedPosition[1]>=systemTop)
                assertTrue("Keep result near the top safe area",closedPosition[1]<=systemTop+32*density)
                assertTrue(windowParams.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL!=0)
                assertTrue(windowParams.flags and android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH!=0)
                dimensions.put("topPx",closedPosition[1]).put("safeTopPx",systemTop).put("windowHeightDp",window.height/density)
                assertTrue("Result width exceeds phone budget: $dimensions",card.width/density<=344.5f)
                assertNull("The compact result has no separate Done action",card.findViewWithTag<View>("result-done"))
                val targets=org.json.JSONObject()
                for(control in listOf("result-account","result-reimbursement")) {
                    val target=card.findViewWithTag<View>(control)
                    targets.put(control,org.json.JSONObject().put("widthDp",target.width/density).put("heightDp",target.height/density))
                    assertTrue("$control must keep a 48dp touch target",target.width/density>=48f && target.height/density>=48f)
                }
                if(!matched) {
                    val header=card.findViewWithTag<android.widget.TextView>("result-account")
                    val layout=header.layout
                    val omittedEnd=layout.getEllipsisStart(0)+layout.getEllipsisCount(0)
                    assertTrue("Current-account suffix must survive ellipsis",layout.getEllipsisCount(0)==0 || omittedEnd<=header.text.toString().indexOf("8848"))
                }
                dimensions.put("touchTargets",targets)
                assertTrue("Closed card exceeds bounded viewport",card.height<app.resources.displayMetrics.heightPixels-100)
                if(app.resources.configuration.fontScale<=1f)assertTrue("Closed result exceeds compact height budget: $dimensions",card.height/density<=180f)
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"账户：").performClick()}
                SystemClock.sleep(400);capture(app,automation,"popup-$tag-expanded.png")
                dimensions.put("expandedHeightDp",card.height/density)
                val expandedPosition=IntArray(2);card.getLocationOnScreen(expandedPosition)
                assertEquals("Opening accounts must grow downward from the same top",closedPosition[1],expandedPosition[1])
                dimensions.put("expandedTopPx",expandedPosition[1])
                assertTrue("Expanded result must fit the phone viewport",card.height/density<=500f)
                app.openFileOutput("popup-$tag-geometry.json",0).use {it.write(dimensions.toString().toByteArray())}
                instrumentation.runOnMainSync {button(resultRoot(banner!!),"标记报销").performClick()}
                SystemClock.sleep(300);capture(app,automation,"popup-$tag-expanded-on.png")
                assertTrue(app.store.get(record.id)!!.requiresReimbursement)
                if(!matched) {
                    for((index,chosen) in extras.withIndex()) {
                        if(index>0)instrumentation.runOnMainSync {button(resultRoot(banner!!),"账户：").performClick()}
                        waitFor {banner!!.diagnostics().getBoolean("accountPanelOpen")}
                        instrumentation.runOnMainSync {button(panelRoot(banner!!),chosen.name).performClick()}
                        waitFor {card.height==closedHeight && !banner!!.diagnostics().getBoolean("accountPanelOpen")}
                        assertEquals(chosen.uid,app.store.get(record.id)!!.accountUid)
                        assertEquals(100_000L,app.store.getAccount(account.id)!!.amountCents)
                        assertEquals(37_200L,app.store.getAccount(chosen.id)!!.amountCents)
                        val header=button(resultRoot(banner!!),"账户：") as android.widget.TextView
                        assertNotNull("Selected account must keep its icon",header.compoundDrawables[0])
                        assertTrue("Selected account label must retain full accessible name",header.contentDescription.toString().contains(chosen.name))
                        capture(app,automation,"popup-$tag-selected-${chosen.kind}.png")
                    }
                }
                assertEquals("capsule",banner!!.diagnostics().getString("delivery"))
                assertTrue(banner!!.diagnostics().getBoolean("attached"))
                val backAt=SystemClock.uptimeMillis()
                automation.injectInputEvent(KeyEvent(backAt,backAt,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true)
                automation.injectInputEvent(KeyEvent(backAt,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true)
                waitFor {!banner!!.diagnostics().getBoolean("attached")}
                assertTrue("Dismissal preserves the already-saved tag",app.store.get(record.id)!!.requiresReimbursement)
            }
        } finally {
            instrumentation.runOnMainSync { banner?.removeNow() }
            app.store.delete(record.id)
            app.store.deleteAccount(account.id);extras.forEach {app.store.deleteAccount(it.id)};app.store.setDefaultAccount(previousDefault)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",previousServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",previousEnabled)
            automation.dropShellPermissionIdentity()
        }
    }
    private fun capture(app:LedgerApp,automation:UiAutomation,name:String) {
        // Capture the final rendered state, including native transitionAlpha on ancestors.
        // A laid-out child can still be hidden by AutoTransition's entering ScrollView.
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var signature="";var stableSince=0L
        waitFor {
            var ready=true
            var currentSignature="none"
            instrumentation.runOnMainSync {
                val service=ScreenCaptureService.current
                if(service!=null) {
                    val banner=ScreenCaptureService::class.java.getDeclaredMethod("getBanner").apply {isAccessible=true}.invoke(service) as RecognitionBanner
                    val root=RecognitionBanner::class.java.getDeclaredField("view").apply {isAccessible=true}.get(banner) as? View
                    fun settled(view:View):Boolean = view.visibility!=View.VISIBLE || (view.alpha>=.99f && view.transitionAlpha>=.99f && (view !is ViewGroup || (0 until view.childCount).all {settled(view.getChildAt(it))}))
                    ready=root==null || settled(root)
                    fun layoutSignature(view:View):String = "${view.left},${view.top},${view.width},${view.height},${view.alpha},${view.transitionAlpha},${view.translationX},${view.translationY}"+(if(view is ViewGroup)(0 until view.childCount).joinToString {layoutSignature(view.getChildAt(it))} else "")
                    currentSignature=root?.let {layoutSignature(it)}?:"none"
                }
            }
            if(currentSignature!=signature || !ready) {signature=currentSignature;stableSince=SystemClock.elapsedRealtime()}
            ready && SystemClock.elapsedRealtime()-stableSince>=220
        }
        val screenshot=automation.takeScreenshot()!!
        try { app.openFileOutput(name,0).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        finally { screenshot.recycle() }
    }
    private fun waitFor(message:()->String={"Timed out waiting for popup state"},condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+8_000
        while(!condition() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(50)
        assertTrue(message(),condition())
    }
}
