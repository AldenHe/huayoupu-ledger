package net.ankio.ledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.data.LedgerAccount
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives the actual asset form and JavascriptInterface, with disposable synthetic accounts. */
@RunWith(AndroidJUnit4::class)
class DefaultAccountUiTest {
    @Test fun defaultSelectionPersistsAndSignedBalanceCanBeCorrected() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val previous=app.store.defaultAccount()?.uid
        val previousChannels=app.store.channelDefaultAccounts()
        val a=app.store.saveAccount(LedgerAccount(name="合成日常账户",amountCents=10000))
        val b=app.store.saveAccount(LedgerAccount(name="合成备用账户",amountCents=5000))
        try {
            app.store.setDefaultAccount(a.uid)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('assets');refresh();editAccount(${b.id})")
                web.await("document.getElementById('account-amount')!==null")
                web.run("document.getElementById('account-amount').value='-12.34';document.getElementById('account-form').requestSubmit()")
                web.await("!document.getElementById('account-form')")
                assertEquals(-1234L,app.store.getAccount(b.id)!!.amountCents)
                for(channel in listOf("common","wechat","alipay")) {
                    web.run("document.querySelector('[data-preference=\"$channel\"]').click()")
                    web.await("document.querySelector('[data-account-uid=\"${b.uid}\"]')!==null")
                    web.run("document.querySelector('[data-account-uid=\"${b.uid}\"]').click()")
                    web.await("!document.getElementById('sheet').open")
                }
                assertEquals(b.uid,app.store.defaultAccount()!!.uid)
                assertEquals(mapOf("wechat" to b.uid,"alipay" to b.uid),app.store.channelDefaultAccounts())
                scenario.recreate();web.ready();web.run("go('assets');refresh()")
                web.await("accounts.some(a=>a.id===${b.id} && a.isDefault && a.amountCents===-1234)")
                assertEquals("3",web.value("document.querySelectorAll('[data-preference]').length"))
                web.run("document.querySelector('[data-preference=wechat]').click()")
                web.await("document.querySelector('[data-account-uid=\"\"]')!==null")
                web.run("document.querySelector('[data-account-uid=\"\"]').click()")
                web.await("!document.getElementById('sheet').open")
                assertNull(app.store.channelDefaultAccounts()["wechat"])
                assertEquals(b.uid,app.store.channelDefaultAccounts()["alipay"])
                web.run("editAccount(${a.id});document.getElementById('account-form').requestSubmit()")
                web.await("!document.getElementById('account-form')")
                assertEquals(b.uid,app.store.channelDefaultAccounts()["alipay"])
                assertEquals(b.uid,app.store.defaultAccount()!!.uid)
            }
        } finally {
            app.store.deleteAccount(a.id);app.store.deleteAccount(b.id)
            app.store.setDefaultAccount(previous);app.changed()
            listOf("wechat","alipay").forEach {app.store.setChannelDefaultAccount(it,previousChannels[it])}
        }
    }
    @Test fun channelFlagsValidateAtomicallyAndOldCallersPreserveAssignments() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val previous=app.store.channelDefaultAccounts()
        val account=app.store.saveAccount(LedgerAccount(name="合成渠道银行卡",kind="bank_icbc",amountCents=2300))
        try {
            app.store.setChannelDefaultAccount("wechat",account.uid)
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready()
                val input=org.json.JSONObject().put("id",account.id).put("name",account.name).put("description","")
                    .put("kind",account.kind).put("type","ASSET").put("amount","77.00").put("isWechatDefault","yes")
                val invalid=web.text("native.saveAccount(${org.json.JSONObject.quote(input.toString())})")
                assertFalse(org.json.JSONObject(invalid).getBoolean("ok"))
                assertEquals(2300L,app.store.getAccount(account.id)!!.amountCents)
                assertEquals(account.uid,app.store.channelDefaultAccounts()["wechat"])
                input.remove("isWechatDefault")
                val saved=web.text("native.saveAccount(${org.json.JSONObject.quote(input.toString())})")
                assertTrue(org.json.JSONObject(saved).getBoolean("ok"))
                assertEquals(7700L,app.store.getAccount(account.id)!!.amountCents)
                assertEquals(account.uid,app.store.channelDefaultAccounts()["wechat"])
            }
        } finally {
            app.store.deleteAccount(account.id)
            listOf("wechat","alipay").forEach {app.store.setChannelDefaultAccount(it,previous[it])}
            app.changed()
        }
    }
    private fun settled(scenario:ActivityScenario<MainActivity>,web:WebUiProbe) {
        web.await("!document.querySelector('.fluid-pending,.fluid-active,.toast.show') && !document.getElementById('sheet')._morph")
        val painted=CountDownLatch(1)
        scenario.onActivity {a->a.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){
            override fun onComplete(id:Long){a.webView.invalidate();painted.countDown()}
        })}
        check(painted.await(5,TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(100)
    }
    private fun screenshot(app:LedgerApp,name:String) {
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try {app.openFileOutput(name,0).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
}
