package net.ankio.ledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real packaged account and income flows against disposable native financial state. */
@RunWith(AndroidJUnit4::class)
class AccountsReferenceUiTest {
    @Test fun referenceScreensAndIncomeSelectionSurviveRecreationAndPostAtomically() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val prior=app.store.defaultAccount()?.uid;val channels=app.store.channelDefaultAccounts()
        val a=app.store.saveAccount(LedgerAccount(name="合成日常账户",kind="wechat",amountCents=288800))
        val b=app.store.saveAccount(LedgerAccount(name="合成工商银行卡",kind="bank_icbc",amountCents=1200000))
        val recordIds=mutableListOf<Long>()
        val stamp=java.time.ZonedDateTime.now().minusMonths(1).withDayOfMonth(9).withHour(14).withMinute(35).withSecond(0).withNano(0).toInstant().toEpochMilli()
        try {
            app.store.setDefaultAccount(b.uid);app.store.setChannelDefaultAccount("wechat",a.uid)
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                val web=WebUiProbe(scenario);web.ready();SystemClock.sleep(800)
                web.run("go('ledger');refresh()")
                web.run("document.querySelector('#ledger [aria-label=资产]').click()")
                web.await("document.querySelectorAll('[data-preference]').length===3")
                shot(app,scenario,web,"assets")
                web.run("document.querySelector('[data-preference=alipay]').click()")
                shot(app,scenario,web,"preference-picker")
                web.run("closeSheet(false)")
                web.run("document.getElementById('add-account').click()")
                web.await("document.querySelectorAll('[data-account-kind]').length===4")
                shot(app,scenario,web,"add-account")
                web.run("document.querySelector('[data-account-kind=bank]').click()")
                assertEquals("false",web.value("document.getElementById('account-bank-field').hidden"))
                web.run("document.getElementById('account-type').value='LIABILITY';document.getElementById('account-amount').value='oops';document.getElementById('account-form').requestSubmit()")
                assertEquals("false",web.value("document.getElementById('account-amount-error').hidden"))
                shot(app,scenario,web,"account-error")
                web.run("closeSheet(false);AccountExperience.open(${a.id})")
                web.await("page==='account-detail' && document.querySelectorAll('[data-account-filter]').length===4")
                shot(app,scenario,web,"account-detail")
                web.run("document.getElementById('account-add-bill').click();document.querySelector('#manual-type [data-type=income]').click()")
                web.await("manualOpen && document.querySelector('#manual').classList.contains('income-entry')")
                assertEquals("true",web.value("getComputedStyle(document.querySelector('#manual .entry-import')).display==='none'"))
                assertEquals("8",web.value("document.querySelectorAll('#category-grid button').length"))
                web.run("document.getElementById('manual-amount').value='50.25';document.getElementById('manual-note').value='合成指定时间收入';RecordExperience.setManualAccount('${b.uid}');document.getElementById('manual-time').value=localTime($stamp);document.getElementById('manual-time').dispatchEvent(new Event('change'))")
                shot(app,scenario,web,"income")
                assertEquals("true",web.value("(()=>{const g=document.getElementById('category-grid').getBoundingClientRect(),k=document.querySelector('#manual .entry-keyboard').getBoundingClientRect();return [...document.querySelectorAll('#category-grid button')].every(e=>{const r=e.getBoundingClientRect();return r.top>=g.top && r.bottom<=g.bottom && r.bottom<=k.top})})()"))
                web.run("document.getElementById('manual-account').click()")
                shot(app,scenario,web,"income-account-picker")
                web.run("closeSheet(false)")
                scenario.recreate();web.ready()
                web.await("manualOpen && document.getElementById('manual-amount').value==='50.25'")
                assertEquals(b.uid,web.text("RecordExperience.manualValues().accountUid"));assertEquals(stamp,web.value("RecordExperience.manualValues().occurredAt").toDouble().toLong())
                web.run("document.getElementById('manual-form').requestSubmit()")
                web.await("!manualOpen && page==='ledger'")
                val saved=app.store.all().single {it.note=="合成指定时间收入"};recordIds+=saved.id
                assertEquals(b.uid,saved.accountUid);assertEquals(stamp,saved.bill.occurredAt)
                assertEquals(1205025L,app.store.getAccount(b.id)!!.amountCents)
                web.run("openRecord(${saved.id})")
                web.await("recordPanelOpen && document.getElementById('import').classList.contains('reference-record')")
                shot(app,scenario,web,"record-detail")
                assertEquals("true",web.value("[...document.querySelectorAll('#import .actions button')].filter(e=>!e.hidden).every(e=>{const r=e.getBoundingClientRect();return r.width>=48 && r.height>=48 && r.top>=0 && r.bottom<=innerHeight && e.contains(document.elementFromPoint(r.left+r.width/2,r.top+r.height/2))})"))
                web.run("document.getElementById('record-reimbursement-switch').click();document.getElementById('category').click();document.getElementById('record-more-button').click()")
                web.run("document.getElementById('record-supplement').scrollIntoView({block:'center'})")
                shot(app,scenario,web,"record-supplement")
                web.run("closeSheet(false);document.getElementById('record-panel').scrollTop=0")
                web.run("document.getElementById('record-reimbursement-switch').click()")
                web.run("document.getElementById('record-account').click()")
                web.await("document.querySelector('[data-account-uid=\"${a.uid}\"]')!==null")
                web.run("document.querySelector('[data-account-uid=\"${a.uid}\"]').click();document.getElementById('import-form').requestSubmit()")
                web.await("!recordPanelOpen")
                assertEquals(1200000L,app.store.getAccount(b.id)!!.amountCents);assertEquals(293825L,app.store.getAccount(a.id)!!.amountCents)
                web.run("go('assets');AccountExperience.open(${a.id})")
                web.await("page==='account-detail' && getComputedStyle(document.getElementById('import')).display==='none'")
                web.run("document.getElementById('account-month').click();document.getElementById('account-month-picker').value=localMonth($stamp);document.getElementById('account-month-apply').click()")
                shot(app,scenario,web,"account-records")
                web.run("document.getElementById('account-month').click();document.getElementById('account-month-picker').value=localMonth(Date.now());document.getElementById('account-month-apply').click();document.getElementById('account-overflow').click();document.getElementById('account-transfer-action').click()")
                web.await("document.getElementById('transfer-form')!==null")
                shot(app,scenario,web,"transfer-form")
                web.run("document.getElementById('transfer-to').value='${b.uid}';document.getElementById('transfer-amount').value='10.00';document.getElementById('transfer-form').requestSubmit()")
                web.await("!document.getElementById('sheet').open")
                assertEquals(292825L,app.store.getAccount(a.id)!!.amountCents);assertEquals(1201000L,app.store.getAccount(b.id)!!.amountCents)
                web.run("document.querySelector('[data-account-filter=transfer]').click()")
                web.await("document.querySelector('[data-transfer-uid]')!==null")
                shot(app,scenario,web,"account-transfers")
                assertEquals(1,AccountTransfers(app.store).list(a.uid).size)
            }
        }finally {
            AccountTransfers(app.store).list(a.uid).forEach {AccountTransfers(app.store).delete(it.uid)}
            recordIds.forEach {app.store.delete(it)};app.store.deleteAccount(a.id);app.store.deleteAccount(b.id)
            app.store.setDefaultAccount(prior);listOf("wechat","alipay").forEach {app.store.setChannelDefaultAccount(it,channels[it])};app.changed()
        }
    }
    @Test fun pagedAccountHistoryReloadsWithoutDuplicatesAndNativeChangesReachVisibleDetail() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val prior=app.store.defaultAccount()?.uid
        val account=app.store.saveAccount(LedgerAccount(name="合成分页账户",kind="cash",amountCents=100000))
        val ids=mutableListOf<Long>();val now=System.currentTimeMillis()
        fun add(index:Int):LedgerRecord {
            val at=now+index*1000
            return app.store.saveEdited(LedgerRecord(capture=net.ankio.ledger.core.Capture(net.ankio.ledger.core.Source.MANUAL,"manual","",at),
                bill=net.ankio.ledger.core.ParsedBill(100,net.ankio.ledger.core.Direction.EXPENSE,"合成分页$index","日用",at,null,true,"",true),state=net.ankio.ledger.core.RecordState.POSTED),selectedAccountUid=account.uid,accountSelectionSpecified=true).also{ids+=it.id}
        }
        try {
            repeat(105){add(it)}
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                val web=WebUiProbe(scenario);web.ready();SystemClock.sleep(800)
                web.run("refresh();AccountExperience.open(${account.id})")
                web.await("document.querySelectorAll('[data-account-record]').length===100")
                web.run("document.getElementById('account-more').click()")
                assertEquals("105",web.value("document.querySelectorAll('[data-account-record]').length"))
                web.run("document.querySelector('[data-account-record]').click();cancelEditor(true)")
                web.await("!recordPanelClosing && document.querySelectorAll('[data-account-record]').length===100")
                web.run("document.getElementById('account-more').click()")
                assertEquals("105",web.value("new Set([...document.querySelectorAll('[data-account-record]')].map(e=>e.dataset.accountRecord)).size"))
                val newest=add(106);app.changed()
                web.await("document.querySelector('[data-account-record=\"${newest.id}\"]')!==null && document.querySelectorAll('[data-account-record]').length===100")
                assertEquals("894",web.text("document.querySelector('.account-balance-line strong').textContent").substringAfter("¥ "))
                scenario.recreate();web.ready()
                web.await("page==='account-detail' && document.querySelectorAll('[data-account-record]').length===100")
                web.run("LedgerUI.back()")
                web.await("page==='assets'")
            }
        } finally {ids.forEach{app.store.delete(it)};app.store.deleteAccount(account.id);app.store.setDefaultAccount(prior);app.changed()}
    }
    private fun shot(app:LedgerApp,scenario:ActivityScenario<MainActivity>,web:WebUiProbe,name:String) {
        web.run("window.__refPaint=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__refPaint=true))")
        web.await("window.__refPaint && !document.querySelector('.fluid-pending,.fluid-active,.toast.show') && !document.getElementById('sheet')._morph")
        val painted=java.util.concurrent.CountDownLatch(1)
        scenario.onActivity {a->a.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){override fun onComplete(id:Long){a.webView.invalidate();painted.countDown()}})}
        check(painted.await(5,java.util.concurrent.TimeUnit.SECONDS));InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(500)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.recycle();SystemClock.sleep(300)
        val bounds=web.value("JSON.stringify({page,viewport:[innerWidth,innerHeight],screens:[...document.querySelectorAll('.screen')].map(e=>[e.id,getComputedStyle(e).display,e.getBoundingClientRect().y,e.getBoundingClientRect().height]),rows:[...document.querySelectorAll('#manual .category-choice')].map(e=>{const r=e.getBoundingClientRect();return [r.y,r.height]}),grid:(()=>{const e=document.querySelector('#category-grid');return [e.clientHeight,e.scrollHeight,getComputedStyle(e).gridAutoRows]})()})")
        app.openFileOutput("reference-after-$name-layout.json",0).use{it.write(bounds.toByteArray())}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try{app.openFileOutput("reference-after-$name.png",0).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
}
