package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import net.ankio.ledger.ui.MainActivity
import net.ankio.ledger.core.*
import net.ankio.ledger.data.LedgerRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetBankUiTest {
    @Test fun blankNameAndMissingBalanceExplainTheirRequiredFields() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>();val before=app.store.accounts().map { it.id }.toSet()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui=WebUiProbe(scenario);ui.ready();ui.run("go('assets');document.querySelector('#add-account').click();document.querySelector('#account-kind').value='wechat';document.querySelector('#account-kind').dispatchEvent(new Event('change'));document.querySelector('#account-name').value='';document.querySelector('#account-amount').value='';document.querySelector('#account-form').requestSubmit()")
            ui.await("document.querySelector('#account-name').getAttribute('aria-invalid')==='true'")
            assertEquals("请输入账户名称",ui.text("document.querySelector('#account-name-error').textContent"))
            ui.run("document.querySelector('#account-name').value='微信';document.querySelector('#account-form').requestSubmit()")
            ui.await("document.querySelector('#account-amount').getAttribute('aria-invalid')==='true'")
            assertEquals("请输入余额，可填 0",ui.text("document.querySelector('#account-amount-error').textContent"))
            ui.run("document.querySelector('#account-amount').value='0';document.querySelector('#account-form').requestSubmit()")
            ui.await("!document.querySelector('#sheet').open")
            val saved=app.store.accounts().last { it.kind=="wechat" && it.amountCents==0L }
            assertEquals("微信",saved.name)
            for((kind,name) in listOf("bank_ccb" to "建设银行","custom" to "其他账户")) {
                ui.run("editAccount();document.querySelector('#account-kind').value='${if(kind.startsWith("bank_"))"bank" else kind}';document.querySelector('#account-kind').dispatchEvent(new Event('change'));document.querySelector('#account-bank').value='$kind';document.querySelector('#account-bank').dispatchEvent(new Event('change'));document.querySelector('#account-amount').value='oops';document.querySelector('#account-form').requestSubmit()")
                ui.await("document.querySelector('#account-amount').getAttribute('aria-invalid')==='true'")
                assertEquals(before.size+if(kind=="bank_ccb")1 else 2,app.store.accounts().size)
                ui.run("document.querySelector('#account-amount').value='12.34';document.querySelector('#account-form').requestSubmit()")
                ui.await("!document.querySelector('#sheet').open")
                assertEquals(name,app.store.accounts().last { it.kind==kind && it.id !in before }.name)
            }
        } } finally {app.store.accounts().filter { it.id !in before }.forEach { app.store.deleteAccount(it.id) };app.changed()}
    }
    @Test fun pausedActivityDoesNotRebuildInvisibleLedgerOnRevision() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("window.__backgroundRefreshes=0;window.__originalLedgerEvent=LedgerUI.event;LedgerUI.event=function(type,data){if(type==='refresh')window.__backgroundRefreshes++;return window.__originalLedgerEvent(type,data)}")
            var activity:MainActivity?=null
            scenario.onActivity { activity=it }
            scenario.moveToState(Lifecycle.State.CREATED)
            val latch=java.util.concurrent.CountDownLatch(1)
            var count=""
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity!!.webView.evaluateJavascript("window.__backgroundRefreshes=0",null)
            }
            app.changed();android.os.SystemClock.sleep(300)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity!!.webView.evaluateJavascript("window.__backgroundRefreshes") { count=it;latch.countDown() }
            }
            assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("0",count)
            scenario.moveToState(Lifecycle.State.RESUMED)
            web.await("window.__backgroundRefreshes>0")
        }
    }

    @Test fun selectedBankPersistsThroughEditorAndShowsOfflineLogo() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        var id=0L
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("go('assets');document.querySelector('#add-account').click()")
            web.await("!!document.querySelector('#account-form')")
            web.run("document.querySelector('#account-kind').value='bank';document.querySelector('#account-kind').dispatchEvent(new Event('change'));document.querySelector('#account-bank').value='bank_bocom';document.querySelector('#account-bank').dispatchEvent(new Event('change'));document.querySelector('#account-name').value='界面测试交通卡';document.querySelector('#account-amount').value='123.45';document.querySelector('#account-form').requestSubmit()")
            web.await("accounts.some(a=>a.name==='界面测试交通卡'&&a.kind==='bank_bocom')")
            id=web.value("accounts.find(a=>a.name==='界面测试交通卡').id").toLong()
            web.await("document.querySelector('[data-account=\"$id\"] .account-bank-logo')?.naturalWidth>0")
            web.run("editAccount($id)")
            assertEquals("bank_bocom",web.text("document.querySelector('#account-bank').value"))
            web.await("document.querySelector('[data-account-kind=bank]').classList.contains('selected')")
        } } finally { if(id>0)app.store.deleteAccount(id);app.changed() }
    }

    @Test fun bankSelectionIsDependentAndPreservesCustomName() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("go('assets');editAccount();document.querySelector('#account-kind').value='bank';document.querySelector('#account-kind').dispatchEvent(new Event('change'))")
            web.await("document.querySelector('#account-bank') && !document.querySelector('#account-bank-field').hidden")
            assertEquals("6",web.value("document.querySelector('#account-bank').options.length"))
            web.run("document.querySelector('#account-bank').value='bank_icbc';document.querySelector('#account-bank').dispatchEvent(new Event('change'))")
            assertEquals("工商银行",web.text("document.querySelector('#account-name').value"))
            web.run("document.querySelector('#account-name').value='工资卡';document.querySelector('#account-bank').value='bank_ccb';document.querySelector('#account-bank').dispatchEvent(new Event('change'))")
            assertEquals("工资卡",web.text("document.querySelector('#account-name').value"))
            web.run("document.querySelector('#account-kind').value='cash';document.querySelector('#account-kind').dispatchEvent(new Event('change'))")
            assertEquals("true",web.value("document.querySelector('#account-bank-field').hidden"))
        }
    }

    @Test fun directionPrecedesCurrencyInEditorAndLedger() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val now=System.currentTimeMillis()
        val record=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual","",now),
            bill=ParsedBill(2250,Direction.EXPENSE,"符号测试057","餐饮",now,null,true,"",true),state=RecordState.POSTED))
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("newDraft();updateAmountSign()")
            assertEquals("− ¥",web.text("document.querySelector('#amount-sign').textContent"))
            web.run("go('ledger');query='符号测试057';month=localMonth(Date.now());filter='expense';viewKey='';refresh()")
            assertEquals("− ¥ 22.5",web.text("document.querySelector('.entry-amount').textContent"))
            web.run("showCategoryDetails('餐饮','expense')")
            assertEquals("− ¥ 22.5",web.text("document.querySelector('[data-category-record=\"${record.id}\"] strong').textContent"))
            app.store.saveEdited(record.copy(bill=record.bill.copy(direction=Direction.INCOME)));app.changed()
            web.run("closeSheet();filter='income';viewKey='';refresh()")
            assertEquals("+ ¥ 22.5",web.text("document.querySelector('.entry-amount').textContent"))
        } } finally { app.store.delete(record.id);app.changed() }
    }
}
