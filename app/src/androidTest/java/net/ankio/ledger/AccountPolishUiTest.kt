package net.ankio.ledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Matched before/after states; fixtures belong only to the disposable QA package. */
@RunWith(AndroidJUnit4::class)
class AccountPolishUiTest {
    private fun fixture(test:(LedgerApp,List<LedgerAccount>,LedgerRecord,LedgerRecord)->Unit) {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val previous=app.store.defaultAccount()?.uid
        val channels=app.store.channelDefaultAccounts()
        val accounts=(1..8).map { n ->app.store.saveAccount(LedgerAccount(name=if(n==8)"合成长名称银行卡储蓄账户尾号8848" else "合成账户$n",kind=if(n%2==0)"wechat" else "alipay",amountCents=100000L*n)) }
        val at=java.time.LocalDateTime.of(2026,11,6,18,3).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun record(direction:Direction)=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual","",at),bill=ParsedBill(if(direction==Direction.INCOME)300000 else 5469,direction,if(direction==Direction.INCOME)"工资" else "合成测试商户",if(direction==Direction.INCOME)"工资" else "餐饮",at,null,true,"",true),state=RecordState.POSTED))
        val income=record(Direction.INCOME);val expense=record(Direction.EXPENSE)
        try {
            app.store.setDefaultAccount(accounts[6].uid)
            app.store.setChannelDefaultAccount("wechat",accounts[4].uid)
            app.store.setChannelDefaultAccount("alipay",accounts[2].uid)
            test(app,accounts,income,expense)
        } finally {
            app.store.delete(income.id);app.store.delete(expense.id)
            accounts.forEach {app.store.deleteAccount(it.id)}
            app.store.setDefaultAccount(previous)
            for(channel in listOf("wechat","alipay"))app.store.setChannelDefaultAccount(channel,channels[channel])
            app.changed()
        }
    }

    @Test fun fiveAccountRowsScrollAndSelectionPersistsWithoutDuplicateWrites()=fixture {app,accounts,_,_ ->
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("refresh();go('assets')")
            assertEquals("默认扣款默认扣款默认扣款",web.text("document.getElementById('account-preferences').querySelector('.preference-controls').textContent"))
            val ordered=web.text("JSON.stringify([...document.querySelectorAll('#accounts [data-account]')].slice(0,3).map(e=>Number(e.dataset.account)))")
            assertEquals(listOf(accounts[2].id,accounts[4].id,accounts[6].id),org.json.JSONArray(ordered).let {a ->(0 until a.length()).map {a.getLong(it)}})
            web.run("document.querySelector('[data-preference=wechat]').click()")
            web.await("document.querySelector('.account-picker').clientHeight>0 && !document.querySelector('.fluid-pending,.fluid-active')")
            assertEquals("true",web.value("(()=>{const p=document.querySelector('.account-picker'),r=[...p.children];return p.scrollHeight>p.clientHeight && Math.abs(p.clientHeight-(r[4].offsetTop+r[4].offsetHeight-r[0].offsetTop))<2 && getComputedStyle(p,'::-webkit-scrollbar').display==='none'})()"))
            val rect=org.json.JSONArray(web.text("JSON.stringify((()=>{const r=document.querySelector('.account-picker').getBoundingClientRect();return [r.x+r.width/2,r.bottom-20,r.top+20,innerWidth]})())"))
            var scale=1f;val origin=IntArray(2)
            scenario.onActivity {a ->a.webView.getLocationOnScreen(origin);scale=a.webView.width/rect.getDouble(3).toFloat()}
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            val down=SystemClock.uptimeMillis()
            for(step in 0..8){
                val action=when(step){0->android.view.MotionEvent.ACTION_DOWN;8->android.view.MotionEvent.ACTION_UP;else->android.view.MotionEvent.ACTION_MOVE}
                val y=rect.getDouble(1)+(rect.getDouble(2)-rect.getDouble(1))*step/8
                val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,origin[0]+rect.getDouble(0).toFloat()*scale,origin[1]+y.toFloat()*scale,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                try{assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()};SystemClock.sleep(30)
            }
            web.await("document.querySelector('.account-picker').scrollTop>0")
            web.run("document.querySelector('.ui-scroll-track').dispatchEvent(new KeyboardEvent('keydown',{key:'End',bubbles:true}))")
            web.await("document.querySelector('.account-picker').scrollTop>0")
            web.run("document.querySelector('[data-account-uid=\"${accounts[7].uid}\"]').click()")
            web.await("!document.getElementById('sheet').open")
            assertEquals(accounts[7].uid,app.store.channelDefaultAccounts()["wechat"])
            web.run("AccountExperience.edit(${accounts[1].id});document.getElementById('account-name').value='${accounts[0].name}';document.getElementById('account-amount').value='54.10';document.getElementById('account-form').requestSubmit()")
            web.await("!document.getElementById('account-name-error').hidden")
            assertEquals("账户名称已存在，请使用其他名称",web.text("document.getElementById('account-name-error').textContent"))
            capture(app,scenario,web,"duplicate-error")
            assertEquals(200000L,app.store.getAccount(accounts[1].id)!!.amountCents)
            web.run("document.getElementById('account-name').value='合成更名账户';document.getElementById('account-form').requestSubmit()")
            web.await("!document.getElementById('sheet').open")
            assertEquals(5410L,app.store.getAccount(accounts[1].id)!!.amountCents)
            assertEquals(accounts[1].uid,app.store.getAccount(accounts[1].id)!!.uid)
            scenario.recreate();web.ready();web.run("refresh();go('assets')")
            assertTrue(web.text("document.getElementById('accounts').textContent").contains("54.1"))
            assertEquals(accounts[7].uid,app.store.channelDefaultAccounts()["wechat"])
        }
    }

    @Test fun nativeUnicodeDuplicateRejectionShowsInlineAndKeepsBalances()=fixture {app,accounts,_,_ ->
        val plain=app.store.accounts().firstOrNull {it.name=="合成Unicode"}?:app.store.saveAccount(LedgerAccount(name="合成Unicode",amountCents=200))
        val original=app.store.getAccount(accounts[1].id)!!
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("refresh();AccountExperience.edit(${original.id});document.getElementById('account-name').value='\\uFEFF合成Unicode';document.getElementById('account-amount').value='1.01';document.getElementById('account-form').requestSubmit()")
                web.await("!document.getElementById('account-name-error').hidden")
                assertEquals("账户名称已存在，请使用其他名称",web.text("document.getElementById('account-name-error').textContent"))
                assertEquals(original,app.store.getAccount(original.id))
                assertEquals(plain,app.store.getAccount(plain.id))
                web.run("closeSheet(false)")
            }
        } finally {app.store.deleteAccount(plain.id)}
    }

    @Test fun incomeReadingAndDecimalEditingPreserveFactsAndExpenseControls()=fixture {app,_,income,expense ->
        val seeded=app.store.saveEdited(income.copy(requiresReimbursement=true,tags=listOf("合成保留标签")))
        val priorHistory=app.store.captureHistory(income.id)
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${income.id})")
            web.await("recordPanelOpen && !document.querySelector('.fluid-pending,.fluid-active')")
            assertEquals("3,000",web.text("document.querySelector('.record-amount-value .record-value').textContent"))
            assertEquals("true",web.value("document.getElementById('import').classList.contains('income-record') && document.getElementById('record-attachment').hidden && getComputedStyle(document.getElementById('bill-reimbursement-editor')).display==='none'"))
            web.run("document.getElementById('category').click()")
            assertEquals("true",web.value("document.getElementById('record-more-button').hidden"))
            web.run("closeSheet(false);document.getElementById('amount').value='54.10';document.getElementById('amount').dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('note').value='合成保留备注';document.getElementById('note').dispatchEvent(new Event('input',{bubbles:true}))")
            assertEquals("54.1",web.text("document.querySelector('.record-amount-value .record-value').textContent"))
            scenario.recreate();web.ready();web.await("recordPanelOpen && document.getElementById('amount').value==='54.10'")
            web.run("document.getElementById('import-form').requestSubmit()")
            web.await("!recordPanelOpen && !recordPanelClosing")
            assertEquals(5410L,app.store.get(income.id)!!.bill.amountCents)
            assertEquals("合成保留备注",app.store.get(income.id)!!.note)
            assertEquals(seeded.uid,app.store.get(income.id)!!.uid)
            assertTrue(app.store.get(income.id)!!.requiresReimbursement)
            assertEquals(listOf("合成保留标签"),app.store.get(income.id)!!.tags)
            assertEquals(priorHistory,app.store.captureHistory(income.id))
            web.run("openRecord(${expense.id})")
            web.await("recordPanelOpen && !document.querySelector('.fluid-pending,.fluid-active')")
            assertEquals("true",web.value("!document.getElementById('record-attachment').hidden && getComputedStyle(document.getElementById('bill-reimbursement-editor')).display!=='none'"))
            web.run("document.getElementById('category').click()")
            assertEquals("false",web.value("document.getElementById('record-more-button').hidden"))
            web.run("closeSheet(false);cancelEditor(true)")
        }
    }

    @Test fun monetaryInputsKeepBoundedFocusAndDecimalPrecision()=fixture {app,accounts,_,expense ->
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val web=WebUiProbe(scenario);web.ready();SystemClock.sleep(1600)
            scenario.onActivity {a ->a.webView.requestFocus()}
            val metrics=org.json.JSONArray()
            fun checkFocus(id:String,mode:String) {
                web.await("document.activeElement.id==='$id'")
                val item=org.json.JSONObject(web.text("JSON.stringify((()=>{const e=document.getElementById('$id'),s=getComputedStyle(e),cue=e.closest('.record-value-shell')||e;return {field:'$id',mode:'$mode',focusVisibleSupported:CSS.supports('selector(:focus-visible)'),documentHasFocus:document.hasFocus(),matchesFocus:e.matches(':focus'),outline:s.outlineStyle,opacity:s.opacity,visibility:s.visibility,shadow:s.boxShadow,cueShadow:getComputedStyle(cue).boxShadow,caret:s.caretColor,value:e.value}})())"))
                metrics.put(item)
                app.openFileOutput("monetary-focus-probe.json",0).use {it.write(metrics.toString(2).toByteArray())}
                assertEquals("No external default rectangle for $id ($mode)","none",item.getString("outline"))
                assertTrue("Bounded authored cue for $id ($mode)",item.getString("cueShadow").contains("inset"))
                assertEquals("Focused value must remain visible for $id ($mode)","1",item.getString("opacity"))
                assertEquals("visible",item.getString("visibility"))
            }
            web.run("AccountExperience.edit(${accounts[1].id})");web.await("document.getElementById('sheet').open && !document.querySelector('.fluid-pending,.fluid-active') && !document.getElementById('sheet')._morph");web.run("document.getElementById('account-amount').focus()")
            checkFocus("account-amount","programmatic");capture(app,scenario,web,"account-focus-programmatic")
            tap(scenario,web,"#account-amount");checkFocus("account-amount","touch");capture(app,scenario,web,"account-focus-touch")
            web.run("document.getElementById('account-amount').value='54.10';document.getElementById('account-form').requestSubmit()")
            web.await("!document.getElementById('sheet').open");assertEquals(5410L,app.store.getAccount(accounts[1].id)!!.amountCents)
            hideKeyboard(scenario)
            web.run("openRecord(${expense.id})");web.await("recordPanelOpen && !recordPanelClosing && !document.querySelector('.fluid-pending,.fluid-active')");web.run("document.getElementById('amount').focus()")
            checkFocus("amount","programmatic");capture(app,scenario,web,"bill-focus-programmatic")
            tap(scenario,web,"#amount");checkFocus("amount","touch");capture(app,scenario,web,"bill-focus-touch")
            web.run("document.getElementById('amount').value='54.10';document.getElementById('amount').dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('import-form').requestSubmit()")
            web.await("!recordPanelOpen && !recordPanelClosing");assertEquals(5410L,app.store.get(expense.id)!!.bill.amountCents)
            hideKeyboard(scenario)
            web.run("openRecord(${expense.id})");web.await("Number(document.getElementById('amount').value)===54.1");web.run("cancelEditor(true)");web.await("!recordPanelOpen && !recordPanelClosing")
            web.run("beginManual()");web.await("manualOpen && !manualClosing && !document.querySelector('.fluid-pending,.fluid-active')");web.run("document.getElementById('manual-amount').focus()")
            checkFocus("manual-amount","programmatic");capture(app,scenario,web,"manual-focus-programmatic")
            tap(scenario,web,"#manual-amount");checkFocus("manual-amount","touch");capture(app,scenario,web,"manual-focus-touch")
            web.run("document.activeElement.blur();closeManual(false)")
        }
    }

    @Test fun captureAccountIncomeAndInputStates()=fixture {app,accounts,income,expense ->
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val web=WebUiProbe(scenario);web.ready();SystemClock.sleep(1600)
            web.run("refresh();go('assets')");web.await("page==='assets' && document.getElementById('assets').classList.contains('mobile-active')");capture(app,scenario,web,"assets")
            web.run("document.querySelector('[data-preference=wechat]').click()")
            capture(app,scenario,web,"account-picker")
            web.run("document.querySelector('.account-picker').scrollTop=10000")
            capture(app,scenario,web,"account-picker-bottom")
            web.run("closeSheet(false);AccountExperience.edit(${accounts[1].id})")
            web.run("document.getElementById('account-amount').focus()")
            tap(scenario,web,"#account-amount")
            capture(app,scenario,web,"account-amount-focus")
            hideKeyboard(scenario)
            web.run("document.activeElement.blur();closeSheet(false);openRecord(${income.id})")
            capture(app,scenario,web,"income-detail")
            web.run("cancelEditor(true)");web.await("!recordPanelOpen && !recordPanelClosing");web.run("openRecord(${expense.id})")
            capture(app,scenario,web,"expense-detail")
            web.run("cancelEditor(true)");web.await("!recordPanelOpen && !recordPanelClosing");web.run("beginManual();document.querySelector('#manual-type [data-type=income]').click()");web.await("manualOpen && !manualClosing && !document.getElementById('manual-panel').hidden")
            capture(app,scenario,web,"manual-income")
            web.run("document.getElementById('manual-amount').focus()")
            tap(scenario,web,"#manual-amount")
            capture(app,scenario,web,"manual-amount-focus")
            hideKeyboard(scenario)
            web.run("document.activeElement.blur();closeManual(false)")
        }
    }

    private fun tap(scenario:ActivityScenario<MainActivity>,web:WebUiProbe,selector:String) {
        val rect=org.json.JSONArray(web.text("JSON.stringify((()=>{const r=document.querySelector('$selector').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})())"))
        val at=IntArray(2);var scale=1f
        scenario.onActivity {a ->a.webView.getLocationOnScreen(at);scale=a.webView.width/rect.getDouble(2).toFloat()}
        val down=SystemClock.uptimeMillis();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,at[0]+rect.getDouble(0).toFloat()*scale,at[1]+rect.getDouble(1).toFloat()*scale,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
            try {assertTrue(automation.injectInputEvent(event,true))}finally {event.recycle()};SystemClock.sleep(80)
        }
        SystemClock.sleep(500)
    }
    private fun hideKeyboard(scenario:ActivityScenario<MainActivity>) {
        scenario.onActivity {a ->a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(a.webView.windowToken,0)}
        SystemClock.sleep(400)
    }

    private fun capture(app:LedgerApp,scenario:ActivityScenario<MainActivity>,web:WebUiProbe,name:String) {
        SystemClock.sleep(1000) // Settle the existing entrance transition before validating the capture.
        web.await("!document.querySelector('.fluid-pending,.fluid-active,.toast.show') && !document.getElementById('sheet')._morph")
        val painted=java.util.concurrent.CountDownLatch(1)
        scenario.onActivity {a ->a.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){
            override fun onComplete(id:Long) {a.webView.invalidate();painted.countDown()}
        })}
        check(painted.await(5,java.util.concurrent.TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(250)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try {app.openFileOutput("polish-$name.png",0).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
        val layout=web.text("JSON.stringify({width:innerWidth,height:innerHeight,elements:[...document.querySelectorAll('#account-preferences,#accounts .reference-account-row,#sheet,#sheet-title,.account-picker,.account-choice,.ui-scroll-track,.ui-scroll-thumb,#record-fields,#bill-reimbursement-editor,#record-attachment,#record-supplement,#manual .manual-amount,#manual-amount')].filter(e=>e.getBoundingClientRect().width).map(e=>({id:e.id,classes:e.className,text:e.textContent,rect:[e.getBoundingClientRect().x,e.getBoundingClientRect().y,e.getBoundingClientRect().width,e.getBoundingClientRect().height],scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,scrollTop:e.scrollTop,outline:getComputedStyle(e).outlineStyle}))})")
        app.openFileOutput("polish-$name-layout.json",0).use {it.write(layout.toByteArray())}
    }
}
