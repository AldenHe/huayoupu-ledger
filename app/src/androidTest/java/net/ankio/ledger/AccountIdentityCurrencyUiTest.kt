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

/** Same real states before/after; all records are disposable QA fixtures. */
@RunWith(AndroidJUnit4::class)
class AccountIdentityCurrencyUiTest {
    @Test fun captureAndVerifyIdentityCurrencyAndSpacing() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val verify=InstrumentationRegistry.getArguments().getString("currencyStage")=="after"
        val previous=app.store.defaultAccount()?.uid
        val channels=app.store.channelDefaultAccounts()
        val account=app.store.saveAccount(LedgerAccount(name="合成日常账户",kind="wechat",amountCents=300000))
        val legacyLong=LedgerAccount(name="合成超长名称工商银行日常收支储蓄账户尾号8848",kind="bank_icbc",amountCents=945331)
        app.store.merge(emptyList(),listOf(legacyLong))
        val long=app.store.accounts().single {it.uid==legacyLong.uid}
        val at=java.time.LocalDateTime.of(2026,11,6,18,3).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun bill(direction:Direction)=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual","",at),bill=ParsedBill(if(direction==Direction.INCOME)300000 else 5469,direction,if(direction==Direction.INCOME)"合成金额工资" else "合成金额咖啡","餐饮",at,null,true,"",true),state=RecordState.POSTED),account.uid,true)
        val expense=bill(Direction.EXPENSE);val income=bill(Direction.INCOME)
        try {
            app.store.setDefaultAccount(account.uid)
            app.store.setChannelDefaultAccount("wechat",account.uid)
            app.store.setChannelDefaultAccount("alipay",account.uid)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();SystemClock.sleep(1600)
                fun shot(name:String) {
                    SystemClock.sleep(1000)
                    web.await("!document.querySelector('.fluid-pending,.fluid-active,.toast.show') && !document.getElementById('sheet')._morph")
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(300)
                    val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                    try {app.openFileOutput("currency-$name.png",0).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
                    val metrics=web.text("JSON.stringify({width:innerWidth,height:innerHeight,elements:[...document.querySelectorAll('#net-amount,#net-amount>small,.summary-amount,.summary-amount>small,.analysis-total,.analysis-total>small,.account-detail-identity,.account-detail-identity b,.account-detail-tags,.account-detail-actions,.account-detail-actions button,.account-balance-line,.account-balance-line strong,.account-detail-row>strong,.entry-amount,.amount-field,#amount-sign,#manual-account,.manual-date,.manual-account-time,.entry-number>span,#manual-amount,.account-balance-field>span,#account-amount')].filter(e=>e.getBoundingClientRect().width).map(e=>({selector:e.id||e.className,text:e.textContent,rect:[e.getBoundingClientRect().x,e.getBoundingClientRect().y,e.getBoundingClientRect().width,e.getBoundingClientRect().height],fontSize:getComputedStyle(e).fontSize,fontWeight:getComputedStyle(e).fontWeight,scrollWidth:e.scrollWidth,clientWidth:e.clientWidth}))})")
                    app.openFileOutput("currency-$name.json",0).use {it.write(metrics.toByteArray())}
                }
                web.run("refresh();go('assets')");web.await("page==='assets'");shot("assets")
                if(verify) {
                    assertEquals("总资产(CNY)",web.text("document.querySelector('.asset-top>span').textContent.trim()"))
                    assertEquals("true",web.value("getComputedStyle(document.querySelector('#net-amount>small')).fontSize===getComputedStyle(document.querySelector('#net-amount')).fontSize"))
                }
                web.run("AccountExperience.open(${account.id})");web.await("page==='account-detail'");shot("account-detail")
                if(verify) {
                    assertTrue(web.text("document.querySelector('.account-detail-identity').textContent").contains(account.name))
                    assertTrue(web.text("document.querySelector('.account-detail-tags').textContent").contains("微信默认"))
                    assertEquals("true",web.value("(()=>{const a=document.querySelector('.account-detail-actions').getBoundingClientRect(),b=document.querySelector('.account-balance-line').getBoundingClientRect();return a.top<b.top && a.left>=b.right+7})()"))
                    assertTrue(web.text("document.querySelector('#account-detail-rows').textContent").contains("− ¥ 54.69"))
                    assertTrue(web.text("document.querySelector('#account-detail-rows').textContent").contains("+ ¥ 3,000"))
                }
                web.run("AccountExperience.open(${long.id})");shot("account-detail-long")
                if(verify)assertEquals("true",web.value("(()=>{const a=document.querySelector('.account-detail-identity').getBoundingClientRect(),b=document.querySelector('.account-detail-actions').getBoundingClientRect();return a.right<=b.left-7})()"))
                web.run("go('ledger');query='合成金额';month='2026-11';filter='expense';viewKey='';refresh()");shot("ledger-expense")
                if(verify)assertEquals("− ¥ 54.69",web.text("document.querySelector('[data-record=\"${expense.id}\"] .entry-amount').textContent"))
                web.run("filter='income';viewKey='';refresh()");shot("ledger-income")
                if(verify)assertEquals("+ ¥ 3,000",web.text("document.querySelector('[data-record=\"${income.id}\"] .entry-amount').textContent"))
                web.run("openRecord(${expense.id})");web.await("recordPanelOpen && draft?.id===${expense.id}");shot("expense-detail")
                if(verify)assertEquals("− ¥",web.text("document.getElementById('amount-sign').textContent"))
                web.run("closeRecordPanel(false);openRecord(${income.id})");web.await("recordPanelOpen && draft?.id===${income.id}");shot("income-detail")
                if(verify)assertEquals("+ ¥",web.text("document.getElementById('amount-sign').textContent"))
                web.run("closeRecordPanel(false);beginManual();document.querySelector('#manual-type [data-type=income]').click();RecordExperience.setManualAccount('${long.uid}')");shot("manual-income")
                if(verify)assertEquals("true",web.value("(()=>{const a=document.querySelector('#manual-account').getBoundingClientRect(),b=document.querySelector('.manual-date').getBoundingClientRect();return b.left-a.right>=15 && getComputedStyle(document.querySelector('.entry-number>span')).fontSize===getComputedStyle(document.querySelector('#manual-amount')).fontSize})()"))
                web.run("closeManual(false);go('stats');drawStats()");shot("statistics")
                if(verify)assertEquals("true",web.value("getComputedStyle(document.querySelector('.analysis-total>small')).fontSize===getComputedStyle(document.querySelector('.analysis-total')).fontSize"))
                web.run("go('assets');editAccount(${account.id})");shot("account-editor")
                if(verify)assertEquals("true",web.value("getComputedStyle(document.querySelector('.account-balance-field>span')).fontSize===getComputedStyle(document.querySelector('#account-amount')).fontSize"))
                web.run("closeSheet(false)")
            }
        } finally {
            app.store.delete(expense.id);app.store.delete(income.id)
            app.store.deleteAccount(account.id);app.store.deleteAccount(long.id)
            app.store.setDefaultAccount(previous)
            for(channel in listOf("wechat","alipay"))app.store.setChannelDefaultAccount(channel,channels[channel])
            app.changed()
        }
    }
    @Test fun nameLimitIsActionableAndHistoricalBalancesRemainEditable() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val legacy=LedgerAccount(name="合成历史长名称工商银行日常储蓄账户尾号8848",amountCents=5410)
        app.store.merge(emptyList(),listOf(legacy))
        val account=app.store.accounts().single {it.uid==legacy.uid}
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            val web=WebUiProbe(scenario);web.ready();SystemClock.sleep(1600)
            fun edit(){web.run("go('assets');editAccount(${account.id})");web.await("document.getElementById('sheet').open && !document.getElementById('sheet')._morph");SystemClock.sleep(500)}
            edit()
            assertTrue(web.text("document.getElementById('account-name-count').textContent").contains("原名称可保留"))
            web.run("document.getElementById('account-amount').value='54.11';document.getElementById('account-form').requestSubmit()")
            web.await("!document.getElementById('sheet').open")
            assertEquals(account.name,app.store.getAccount(account.id)!!.name)
            assertEquals(5411L,app.store.getAccount(account.id)!!.amountCents)
            edit()
            web.run("document.getElementById('account-name').value='合'.repeat(21);document.getElementById('account-name').dispatchEvent(new Event('input'));document.getElementById('account-form').requestSubmit()")
            assertEquals("21/20",web.text("document.getElementById('account-name-count').textContent"))
            assertEquals("账户名称最多 20 个字符",web.text("document.getElementById('account-name-error').textContent"))
            assertEquals("true",web.text("document.getElementById('account-name').getAttribute('aria-invalid')"))
            assertEquals(account.name,app.store.getAccount(account.id)!!.name)
            SystemClock.sleep(1000)
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            try {app.openFileOutput("currency-account-name-limit.png",0).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
            web.run("document.getElementById('account-name').value='😀'.repeat(20);document.getElementById('account-name').dispatchEvent(new Event('input'))")
            assertEquals("20/20",web.text("document.getElementById('account-name-count').textContent"))
            web.run("document.getElementById('account-form').requestSubmit()")
            web.await("!document.getElementById('sheet').open")
            assertEquals("😀".repeat(20),app.store.getAccount(account.id)!!.name)
            assertEquals(account.uid,app.store.getAccount(account.id)!!.uid)
            assertEquals(5411L,app.store.getAccount(account.id)!!.amountCents)
        }} finally {app.store.deleteAccount(account.id);app.changed()}
    }

}
