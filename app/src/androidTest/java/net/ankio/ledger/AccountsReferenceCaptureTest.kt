package net.ankio.ledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable, labelled fixtures for comparable captures of the shipped WebView. */
@RunWith(AndroidJUnit4::class)
class AccountsReferenceCaptureTest {
    @Test fun captureReferenceSurfaces() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val previous=app.store.defaultAccount()?.uid
        val channels=app.store.channelDefaultAccounts()
        val account=app.store.saveAccount(LedgerAccount(name="合成日常账户",kind="wechat",amountCents=288800))
        val bank=app.store.saveAccount(LedgerAccount(name="合成工商银行卡",kind="bank_icbc",amountCents=1200000))
        val at=System.currentTimeMillis()
        val record=app.store.saveEdited(LedgerRecord(capture=Capture(Source.IMAGE,"synthetic","合成视觉验证账单",at),bill=ParsedBill(3500,Direction.EXPENSE,"合成咖啡店","餐饮",at,null,true,"",true),state=RecordState.POSTED))
        try {
            app.store.setDefaultAccount(bank.uid);app.store.setChannelDefaultAccount("wechat",account.uid)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                SystemClock.sleep(800)
                web.run("go('assets');refresh()")
                web.await("page==='assets' && document.querySelector('#assets').classList.contains('mobile-active')")
                capture(app,scenario,web,"assets")
                web.run("editAccount(0)")
                capture(app,scenario,web,"add-account")
                web.run("closeSheet(false);editAccount(${account.id})")
                capture(app,scenario,web,"account-detail")
                web.run("closeSheet(false);go('ledger');beginManual();document.querySelector('#manual-type [data-type=income]').click()")
                capture(app,scenario,web,"income")
                web.run("closeManual(false);openRecord(${record.id})")
                web.await("recordPanelOpen && draft?.id===${record.id}")
                capture(app,scenario,web,"record-detail")
            }
        } finally {
            app.store.delete(record.id);app.store.deleteAccount(account.id);app.store.deleteAccount(bank.id)
            app.store.setDefaultAccount(previous)
            channels.forEach { (channel,uid)->app.store.setChannelDefaultAccount(channel,uid) }
            if(!channels.containsKey("wechat"))app.store.setChannelDefaultAccount("wechat",null)
            app.changed()
        }
    }
    private fun capture(app:LedgerApp,scenario:ActivityScenario<MainActivity>,web:WebUiProbe,name:String) {
        web.await("!document.querySelector('.fluid-pending,.fluid-active,.toast.show') && !document.getElementById('sheet')._morph")
        val painted=java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { a->a.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){
            override fun onComplete(id:Long){a.webView.invalidate();painted.countDown()}
        }) }
        check(painted.await(5,java.util.concurrent.TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try {app.openFileOutput("reference-before-$name.png",0).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
}
