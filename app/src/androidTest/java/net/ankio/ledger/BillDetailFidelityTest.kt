package net.ankio.ledger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView reference fixture; only disposable QA accounts and bills are written. */
@RunWith(AndroidJUnit4::class)
class BillDetailFidelityTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun exactReadingDisplayPreservesEditableFactsAndSecondaryControlsAcrossRecreation() {
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val prior=app.store.defaultAccount()?.uid
        val account=app.store.saveAccount(LedgerAccount(name="合成详情验证",kind="cash",amountCents=100000))
        val at=System.currentTimeMillis()
        val record=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual","",at),
            bill=ParsedBill(5400,Direction.EXPENSE,"短商户","其他",at,null,true,"",true),state=RecordState.POSTED),
            selectedAccountUid=account.uid,accountSelectionSpecified=true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${record.id})")
                web.await("recordPanelOpen && !!document.querySelector('.record-amount-value .record-value') && !document.querySelector('.fluid-active,.fluid-pending')")
                assertEquals("54",web.text("document.getElementById('amount').value"))
                assertEquals("54",web.text("document.querySelector('.record-amount-value .record-value').textContent"))
                assertEquals("− ¥",web.text("document.getElementById('amount-sign').textContent"))
                assertEquals("true",web.value("getComputedStyle(document.querySelector('.record-amount-value')).fontSize===getComputedStyle(document.getElementById('amount-sign')).fontSize"))
                assertEquals("false",web.value("editorDirty()"))
                assertEquals("true",web.value("(()=>{const group=document.querySelector('.amount-field').getBoundingClientRect(),card=document.querySelector('.confirm-card').getBoundingClientRect();return Math.abs(group.x+group.width/2-card.x-card.width/2)<2})()"))
                assertEquals("true",web.value("getComputedStyle(document.getElementById('note')).textAlign==='left' && document.getElementById('note').placeholder==='添加备注信息...'"))
                web.run("document.getElementById('category').click();document.getElementById('record-more-button').click();document.getElementById('bill-labels-button').click()")
                web.await("document.getElementById('new-label')!==null")
                assertEquals("true",web.value("document.getElementById('import').contains(document.getElementById('bill-labels-editor')) && document.getElementById('record-supplement').hidden"))
                web.run("document.getElementById('new-label').value='合成标签';document.getElementById('new-label-form').requestSubmit();document.getElementById('labels-apply').click()")
                web.await("!document.getElementById('sheet').open")
                web.run("document.getElementById('category').click();document.querySelector('[data-category=购物]').click()")
                web.await("!document.getElementById('sheet').open")
                assertEquals("购物",web.text("readDraft().category"))
                web.run("document.getElementById('note').value='左对齐备注';document.getElementById('note').dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('record-reimbursement-switch').click()")
                scenario.recreate();web.ready();web.await("recordPanelOpen && document.getElementById('note').value==='左对齐备注'")
                assertEquals("54",web.text("readDraft().amount"))
                assertEquals("true",web.value("readDraft().requiresReimbursement && readDraft().tags.includes('合成标签')"))
                web.run("document.getElementById('import-form').requestSubmit()")
                web.await("!recordPanelOpen && !recordPanelClosing")
                val saved=app.store.get(record.id)!!
                assertEquals(5400L,saved.bill.amountCents);assertEquals("购物",saved.bill.category)
                assertEquals("左对齐备注",saved.note);assertTrue(saved.requiresReimbursement)
                assertEquals(94600L,app.store.getAccount(account.id)!!.amountCents)
                web.run("openRecord(${record.id});document.getElementById('merchant').value='未保存改名';document.getElementById('merchant').dispatchEvent(new Event('input',{bubbles:true}));cancelEditor()")
                web.await("document.getElementById('discard-edits')!==null")
                web.run("document.getElementById('discard-edits').click()")
                web.await("!recordPanelOpen && !recordPanelClosing")
                assertEquals("短商户",app.store.get(record.id)!!.bill.merchant)
                web.run("newDraft();go('import')")
                assertEquals("true",web.value("!document.querySelector('#import .page-title .back svg,#import .note-row svg,#bill-reimbursement-editor>span svg') && !document.querySelector('#import .record-value-shell') && document.getElementById('bill-labels-editor').closest('#import-form')!==null"))
                web.run("go('ledger');openRecord(${record.id})")
                web.await("!document.querySelector('.fluid-pending,.fluid-active')")
                web.run("document.getElementById('amount').value='123456789.12';document.getElementById('amount').dispatchEvent(new Event('input',{bubbles:true}))")
                web.await("(()=>{const f=document.querySelector('.amount-field'),s=f.getBoundingClientRect(),card=f.parentElement.getBoundingClientRect();return s.left>=card.left-1 && s.right<=card.right+1 && f.scrollWidth<=f.clientWidth+1 && getComputedStyle(document.querySelector('.record-amount-value')).fontSize===getComputedStyle(document.getElementById('amount-sign')).fontSize})()")
                web.run("cancelEditor(true)")
                web.await("!recordPanelOpen && !recordPanelClosing")
                web.run("openRecord(${record.id});document.getElementById('delete-record').click();document.getElementById('confirm-delete').click()")
                web.await("!recordPanelOpen && !recordPanelClosing")
                assertNull(app.store.get(record.id));assertEquals(100000L,app.store.getAccount(account.id)!!.amountCents)
            }
        } finally { app.store.delete(record.id);app.store.deleteAccount(account.id);app.store.setDefaultAccount(prior);app.changed() }
    }

    @Test fun captureReferenceAndVariantStates() {
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val prior=app.store.defaultAccount()?.uid
        val channels=app.store.channelDefaultAccounts()
        val account=app.store.saveAccount(LedgerAccount(name="微信零钱",kind="wechat",amountCents=200000))
        val at=java.time.LocalDateTime.of(2026,11,5,20,5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ids=mutableListOf<Long>()
        try {
            app.store.setDefaultAccount(account.uid);app.store.setChannelDefaultAccount("wechat",account.uid)
            val bill=ParsedBill(5469,Direction.EXPENSE,"先充后付","生活缴费 / 充电",at,"FIDELITY-${UUID.randomUUID()}",true,"",true)
            val result=app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成复刻验证支付成功 ¥54.69",at),bill,screenshot=picture(false),merchantIcon=picture(true)) as IngestResult.Saved
            ids+=result.record.id
            app.store.saveEdited(result.record.copy(requiresReimbursement=true))
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                val web=WebUiProbe(scenario);web.ready();SystemClock.sleep(800)
                web.run("go('ledger');refresh();openRecord(${result.record.id})")
                web.await("recordPanelOpen && document.getElementById('import').classList.contains('reference-record')")
                shot(scenario,web,"reference")
                web.run("document.getElementById('record-account').click()")
                web.await("document.querySelector('[data-account-uid=\"${account.uid}\"]')!==null")
                shot(scenario,web,"account-picker")
                web.run("closeSheet(false);document.getElementById('category').click()")
                shot(scenario,web,"category-picker")
                web.run("document.getElementById('record-more-button').click()")
                shot(scenario,web,"supplement")
                web.run("closeSheet(false);document.getElementById('record-panel').scrollTop=0;document.getElementById('record-original').click()")
                shot(scenario,web,"original")
                web.run("closeSheet(false);document.getElementById('record-originals').hidden=true;document.getElementById('record-panel').scrollTop=0")
                web.run("document.getElementById('merchant').value='一家有很长名字的充电服务商（南门停车场店）';document.getElementById('category').textContent='生活缴费 / 新能源汽车充电';document.getElementById('note').value='停车场充电，开发票后报销';RecordExperience.render()")
                shot(scenario,web,"long-fields")
                web.run("document.getElementById('amount').value='123456789.12';document.getElementById('amount').dispatchEvent(new Event('input',{bubbles:true}))")
                shot(scenario,web,"long-amount")
                web.run("cancelEditor(true)")
                web.await("!recordPanelOpen && !recordPanelClosing")
            }
        }finally {
            ids.forEach{app.store.delete(it)};app.store.deleteAccount(account.id)
            app.store.setDefaultAccount(prior);listOf("wechat","alipay").forEach{app.store.setChannelDefaultAccount(it,channels[it])};app.changed()
        }
    }

    private fun picture(icon:Boolean):ByteArray {
        val bitmap=Bitmap.createBitmap(if(icon)96 else 480,if(icon)96 else 800,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(if(icon)Color.TRANSPARENT else Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        if(icon){
            paint.color=Color.rgb(255,122,73);canvas.drawCircle(48f,48f,47f,paint)
            paint.color=Color.WHITE
            val path=Path().apply{moveTo(52f,22f);lineTo(34f,52f);lineTo(47f,52f);lineTo(43f,76f);lineTo(65f,43f);lineTo(52f,43f);close()}
            canvas.drawPath(path,paint)
        }else{
            paint.color=Color.rgb(21,32,41);paint.textSize=30f
            listOf("合成测试付款截图","先充后付","支付成功 ¥ 54.69","2026/11/05 20:05").forEachIndexed{i,text->canvas.drawText(text,32f,100f+i*90,paint)}
        }
        return try{ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()}}finally{bitmap.recycle()}
    }

    private fun shot(scenario:ActivityScenario<MainActivity>,web:WebUiProbe,name:String) {
        web.run("window.__fidelityPaint=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__fidelityPaint=true));document.activeElement?.blur()")
        web.await("window.__fidelityPaint && !document.querySelector('.fluid-pending,.fluid-active,.toast.show')")
        val painted=CountDownLatch(1)
        scenario.onActivity{a->a.webView.postVisualStateCallback(System.nanoTime(),object:android.webkit.WebView.VisualStateCallback(){override fun onComplete(id:Long){a.webView.invalidate();painted.countDown()}})}
        check(painted.await(5,TimeUnit.SECONDS));InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(500)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.recycle();SystemClock.sleep(300)
        val layout=web.value("JSON.stringify({viewport:[innerWidth,innerHeight],scale:devicePixelRatio,flexGap:!document.body.classList.contains('no-flex-gap'),elements:[...document.querySelectorAll('#record-panel,.reference-record>.page-title,.reference-record .confirm-card,.record-identity,.record-value-shell,.record-value,#amount-sign,#amount,#record-fields,.reference-field,#record-account,#category,#time-label,#bill-reimbursement-editor,.note-row,#note,#record-attachment,#record-supplement,.reference-record .actions,.reference-record .actions button')].map(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {id:e.id,classes:e.className,rect:[r.x,r.y,r.width,r.height],display:s.display,font:s.font,fontSize:s.fontSize,align:s.textAlign,padding:s.padding,margin:s.margin,flex:s.flex,overflow:s.overflow,scroll:[e.scrollWidth,e.scrollHeight],value:e.value||e.textContent,hit:e.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))}})})")
        app.openFileOutput("fidelity-$name-layout.json",0).use{it.write(layout.toByteArray())}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try{app.openFileOutput("fidelity-$name.png",0).use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
}
