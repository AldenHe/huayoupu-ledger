package net.ankio.ledger

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RefundHistoryUiTest {
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun allInteractiveWebElementsHaveTransparentTapHighlights() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            assertEquals("true",web.value("Array.from(document.querySelectorAll('button,input,select,a,[role=button]')).every(e=>getComputedStyle(e).webkitTapHighlightColor==='rgba(0, 0, 0, 0)')"))
        }
    }

    @Test fun editingAutomaticNoteRetainsCaptureTimeProvenanceForLaterShake() {
        val now=System.currentTimeMillis()
        val automatic=(app.store.ingest(capture(now+15_000,"支付成功"),
            bill("先改备注-${UUID.randomUUID()}",now+15_000,null).copy(timeExplicit=false,
                reason="已识别即时支付成功页；页面未显示交易时间，使用本机捕获时间"),screenshot=image(Color.BLUE)) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${automatic.id})")
                web.run("document.querySelector('#note').value='备注先编辑';document.querySelector('#import-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("!document.querySelector('#record-panel').open")
                assertFalse(app.store.get(automatic.id)!!.bill.timeExplicit)
                assertEquals(automatic.bill.occurredAt,app.store.get(automatic.id)!!.bill.occurredAt)
                val supplement=app.store.ingest(capture(now+3_600_000,"原付款详情"),
                    automatic.bill.copy(occurredAt=now,transactionId="EDIT-${UUID.randomUUID()}",timeExplicit=true,reason=""),
                    screenshot=image(Color.GREEN)) as IngestResult.Saved
                assertEquals(automatic.id,supplement.record.id);assertEquals("备注先编辑",supplement.record.note)
            }
        } finally { app.store.delete(automatic.id);app.changed() }
    }

    @Test fun unmatchedCompleteRefundOpensAsPostedExpenseWithoutAssociationPrompt() {
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(capture(now,"完整原付款与已成功累计退款"),
            bill("独立净额-${UUID.randomUUID()}",now,"NET-${UUID.randomUUID()}").copy(amountCents=3654,
                refund=RefundInfo("wechat",10000,6346)),screenshot=image(Color.GREEN)) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${record.id})")
                web.await("document.querySelector('#amount').value==='36.54' && document.querySelector('#refund-match').hidden && !document.querySelector('#import-form .primary').hidden")
                assertEquals("true",web.value("document.querySelector('#refund-summary').textContent.includes('实付 ¥36.54') && !document.querySelector('#refund-summary').textContent.includes('待关联')"))
            }
        } finally { app.store.delete(record.id);app.changed() }
    }

    @Test fun incompleteRefundWithoutCandidatesHasNoMisleadingAssociationAction() {
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(capture(now,"只有单次退款"),
            bill("单次待补全-${UUID.randomUUID()}",now,"SINGLE-${UUID.randomUUID()}").copy(amountCents=3654,
                refund=RefundInfo("wechat",10000,6346,"single","ONE-${UUID.randomUUID()}")),screenshot=image(Color.GREEN)) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${record.id})")
                web.await("document.querySelector('#refund-match').hidden")
                assertEquals("true",web.value("!document.querySelector('#refund-summary').textContent.includes('待关联') && document.querySelector('#review-reason').textContent.includes('最终支出')"))
                assertEquals("true",web.value("!document.querySelector('#record-supplement').open && document.querySelector('#record-supplement').contains(document.querySelector('#review-reason'))"))
                web.run("document.querySelector('#amount').focus()")
                assertEquals("none",web.text("getComputedStyle(document.querySelector('#amount')).outlineStyle"))
            }
        } finally { app.store.delete(record.id);app.changed() }
    }

    @Test fun choosingLegacyOriginalUpdatesSameRecordAndBothScreenshotsReopenInOrder() {
        val merchant = "退款界面-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val original = (app.store.ingest(capture(now,"原付款截图"), bill(merchant,now,null),
            screenshot=image(Color.BLUE)) as IngestResult.Saved).record
        val refundBill=bill(merchant,now,"REFUND-UI-${UUID.randomUUID()}").copy(amountCents=3654,
            refund=RefundInfo("wechat",10000,6346))
        val pending=(app.store.ingest(capture(now+1000,"退款截图原文"),refundBill,
            screenshot=image(Color.GREEN)) as IngestResult.Saved).record
        try {
            assertEquals(RecordState.REVIEW,pending.state)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("openRecord(${pending.id})")
                web.await("!document.querySelector('#refund-match').hidden && document.querySelector('#import-form .primary').hidden")
                val blocked=JSONObject(web.text("LedgerNative.save(JSON.stringify({...readDraft(),id:${pending.id}}))"))
                assertFalse(blocked.getBoolean("ok"))
                web.run("document.querySelector('#category').click();document.querySelector('#record-more-button').click()")
                web.run("document.querySelector('#refund-match').click()")
                web.await("document.querySelector('[data-refund-target=\"${original.id}\"]')")
                web.run("document.querySelector('[data-refund-target=\"${original.id}\"]').click();document.querySelector('#confirm-refund').click()")
                web.await("document.querySelector('#amount').value==='36.54' && document.querySelector('#refund-summary').textContent.includes('63.46') && document.querySelectorAll('[data-history-sequence]').length===2")
                assertEquals(3654L,app.store.get(original.id)!!.bill.amountCents)
                assertNull(app.store.get(pending.id))
                web.await("!document.querySelector('.fluid-pending,.fluid-active') && !recordPanelClosing && !sheetClosing")
                web.run("document.querySelector('#record-original').click()")
                web.await("document.querySelector('#sheet-title').textContent.includes('1 / 2') && document.querySelector('#sheet .capture-full').naturalWidth>0 && document.querySelector('#sheet .raw-text').textContent==='原付款截图'")
                web.run("document.querySelector('#capture-next').click()")
                web.await("document.querySelector('#sheet-title').textContent.includes('2 / 2') && document.querySelector('#sheet .raw-text').textContent==='退款截图原文'")
                web.run("closeSheet();cancelEditor()")
                scenario.recreate();web.ready();web.run("openRecord(${original.id})")
                web.await("document.querySelectorAll('[data-history-sequence]').length===2 && !document.querySelector('#recognition-history') && document.querySelector('#capture-gallery').textContent.includes('付款截图') && document.querySelector('#capture-gallery').textContent.includes('退款截图')")
            }
        } finally { app.store.delete(pending.id);app.store.delete(original.id);app.changed() }
    }

    @Test fun fullRefundDisplaysZeroAndOnlyCategoryAndNoteEditsCanSaveIt() {
        val now=System.currentTimeMillis()
        val merchant="全退界面-${UUID.randomUUID()}"
        val transaction="FULL-UI-${UUID.randomUUID()}"
        val bill=bill(merchant,now,transaction)
        val original=(app.store.ingest(capture(now,"付款"),bill,screenshot=image(Color.BLUE)) as IngestResult.Saved).record
        try {
            val result=app.store.ingest(capture(now+1000,"全部退款"),bill.copy(amountCents=0,
                refund=RefundInfo("wechat",10000,10000)),screenshot=image(Color.GREEN)) as IngestResult.Saved
            assertEquals(original.id,result.record.id)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${original.id})")
                web.await("document.querySelector('#amount').value==='0' && document.querySelector('#amount').readOnly && document.querySelector('#refund-summary').textContent.includes('已全额退款')")
                val changedAmount=JSONObject(web.text("LedgerNative.save(JSON.stringify({...readDraft(),id:${original.id},amount:'1.00'}))"))
                assertFalse(changedAmount.getBoolean("ok"))
                val changedDirection=JSONObject(web.text("LedgerNative.save(JSON.stringify({...readDraft(),id:${original.id},direction:'INCOME'}))"))
                assertFalse(changedDirection.getBoolean("ok"))
                web.run("document.querySelector('#category').click()")
                web.await("!document.querySelector('[data-direction]') && document.querySelector('[data-category=\"购物\"]')")
                web.run("document.querySelector('[data-category=\"购物\"]').click();document.querySelector('#note').value='全退后保留备注';document.querySelector('#import-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("!document.querySelector('#record-panel').open")
                val saved=app.store.get(original.id)!!
                assertEquals(0L,saved.bill.amountCents);assertEquals("购物",saved.bill.category)
                assertEquals("全退后保留备注",saved.note);assertEquals(original.uid,saved.uid)
            }
        } finally { app.store.delete(original.id);app.changed() }
    }

    @Test fun fourScreenshotsOpenDirectlyInViewerAndSwipeBothWaysAtNarrowWidth() {
        val now=System.currentTimeMillis()
        val bill=bill("示例商户 · ${UUID.randomUUID().toString().take(6)}",now,"GALLERY-${UUID.randomUUID()}")
        val original=(app.store.ingest(capture(now,"付款成功，原付100元，${bill.transactionId}"),bill,screenshot=receipt("支付成功","100.00",Color.rgb(42,166,135))) as IngestResult.Saved).record
        try {
            assertEquals(RecordState.POSTED,original.state)
            app.store.ingest(capture(now+1000,"原付款详情"),bill,screenshot=receipt("付款详情","100.00",Color.rgb(87,145,215)))
            app.store.ingest(capture(now+2000,"已成功退款30元，实际支出70元"),bill.copy(amountCents=7000,
                refund=RefundInfo("wechat",10000,3000)),screenshot=receipt("退款成功","30.00",Color.rgb(86,167,132)))
            app.store.ingest(capture(now+3000,"再次打开原付款页面，金额不得恢复为100"),bill,
                screenshot=receipt("补充详情","100.00",Color.rgb(110,150,180)))
            assertEquals(7000L,app.store.get(original.id)!!.bill.amountCents)
            assertEquals(4,app.store.captureHistory(original.id).size)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${original.id})")
                web.await("document.querySelectorAll('[data-history-sequence]').length===4 && document.querySelector('#amount').value==='70'")
                assertEquals("true",web.value("!document.querySelector('#recognition-history') && document.querySelector('.scanner').hidden && document.querySelector('#record-originals').hidden && !!(document.querySelector('#record-fields').compareDocumentPosition(document.querySelector('#capture-gallery')) & Node.DOCUMENT_POSITION_FOLLOWING)"))
                web.await("!document.querySelector('.fluid-pending,.fluid-active') && !recordPanelClosing")
                web.run("document.querySelector('#record-original').click()")
                web.await("document.querySelector('#capture-position').textContent==='1 / 4' && document.querySelector('#sheet .capture-full').naturalWidth>0")
                web.run("document.querySelector('#capture-pages').style.maxWidth='280px';document.querySelector('#capture-pages').scrollLeft=document.querySelector('#capture-pages').clientWidth*2")
                web.await("document.querySelector('#sheet-title').textContent==='退款截图 · 3 / 4' && document.querySelector('#capture-description').textContent.includes('实付 ¥70')")
                web.run("document.querySelector('#capture-next').click()")
                web.await("document.querySelector('#capture-position').textContent==='4 / 4' && document.querySelector('#capture-next').disabled")
                web.run("document.querySelector('#capture-pages').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true}))")
                web.await("document.querySelector('#capture-position').textContent==='3 / 4' && document.querySelector('#sheet .raw-text').textContent==='已成功退款30元，实际支出70元'")
                // Scroll is the same primitive used by native touch swiping; it must update metadata too.
                web.run("document.querySelector('#capture-pages').scrollLeft=0")
                web.await("document.querySelector('#capture-position').textContent==='1 / 4' && document.querySelector('#capture-previous').disabled")
                assertEquals("true",web.value("document.querySelector('#capture-pages').getBoundingClientRect().right<=innerWidth && document.querySelector('#capture-pages').scrollWidth>document.querySelector('#capture-pages').clientWidth*3"))
                web.run("closeSheet(false)")
            }
        } finally { app.store.delete(original.id);app.changed() }
    }

    private fun receipt(title:String,amount:String,color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color }
        canvas.drawCircle(150f,110f,36f,paint)
        paint.color=Color.rgb(36,55,50);paint.textAlign=Paint.Align.CENTER;paint.textSize=24f
        canvas.drawText(title,150f,190f,paint);paint.textSize=34f;canvas.drawText("¥ $amount",150f,260f,paint)
        paint.textSize=18f;paint.color=Color.GRAY;canvas.drawText("示例商户",150f,330f,paint)
        canvas.drawText("仅用于界面验证",150f,395f,paint)
        return ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG,90,stream);bitmap.recycle();stream.toByteArray() }
    }

    private fun capture(at:Long,text:String)=Capture(Source.OCR,"com.tencent.mm",text,at)
    private fun bill(merchant:String,at:Long,transaction:String?)=
        ParsedBill(10000,Direction.EXPENSE,merchant,"交通",at,transaction,true,"",true)
    private fun image(color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(64,120,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        // Each synthetic bill needs distinct pixels, just as real screenshots contain
        // their own merchant/order text. A solid-color fixture collides with crash leftovers.
        Canvas(bitmap).drawText(UUID.randomUUID().toString(),1f,60f,Paint().apply { this.color=Color.WHITE; textSize=8f })
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG,90,stream);bitmap.recycle();stream.toByteArray()
        }
    }
}
