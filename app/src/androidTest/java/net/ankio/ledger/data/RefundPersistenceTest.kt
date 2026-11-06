package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RefundPersistenceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val namespace="refund-test-${UUID.randomUUID()}"
    private val database="$namespace-ledger.db"
    private val images=ScreenshotStore(context,namespace)
    private var store=LedgerStore(context,database,images)
    private val instant=1_790_000_000_000L
    private val first=byteArrayOf(1,2,3)
    private val second=byteArrayOf(4,5,6)

    @After fun close() {
        store.close();images.close();context.deleteDatabase(database);context.deleteDatabase("$namespace.db")
        File(context.filesDir,namespace).deleteRecursively()
        context.deleteSharedPreferences("${namespace}_policy")
    }

    private fun capture(provider:String="wechat",at:Long=instant,text:String="合成付款页面")=Capture(Source.OCR,
        "shake:${if(provider=="wechat")PaymentApps.WECHAT else PaymentApps.ALIPAY}",text,at)
    private fun bill(id:String?="SYNTHETIC-PAYMENT")=ParsedBill(10000,Direction.EXPENSE,"合成石化商户","交通",instant,id,true,"",true)
    private fun refund(amount:Long=6346,provider:String="wechat",mode:String="cumulative",refundId:String?=null)=
        bill().copy(amountCents=10000-amount,refund=RefundInfo(provider,10000,amount,mode,refundId))
    private fun payment(provider:String="wechat",id:String?="SYNTHETIC-PAYMENT")=(store.ingest(capture(provider),bill(id),screenshot=first) as IngestResult.Saved).record
    private fun ingestRefund(bill:ParsedBill=refund(),provider:String="wechat",at:Long=instant+60_000)=
        store.ingest(capture(provider,at,"合成成功退款页面"),bill,screenshot=second) as IngestResult.Saved

    @Test fun bothProvidersUpdateSameIdentityAndKeepOriginalEditsAndTwoImagesAfterReopen() {
        for(provider in listOf("wechat","alipay")) {
            val original=payment(provider)
            val edited=store.saveEdited(original.copy(note="用户保留的备注",bill=original.bill.copy(category="汽车")))
            val result=ingestRefund(refund(provider=provider),provider)
            assertTrue(result.updated);assertEquals(original.id,result.record.id);assertEquals(original.uid,result.record.uid)
            assertEquals(3654L,result.record.bill.amountCents);assertEquals(edited.note,result.record.note)
            assertEquals("汽车",result.record.bill.category);assertEquals(instant,result.record.bill.occurredAt)
            store.close();store=LedgerStore(context,database,images)
            assertEquals(3654L,store.get(original.id)!!.bill.amountCents)
            assertEquals(listOf(1,2),store.captureHistory(original.id).map { it.sequence })
            assertArrayEquals(first,store.historyScreenshot(original.id,1));assertArrayEquals(second,store.historyScreenshot(original.id,2))
        }
        assertEquals(2,store.all().size)
    }

    @Test fun repeatedAndOlderCumulativePagesNeverDoubleDeductOrRollBackAndFullRefundKeepsZeroRecord() {
        val original=payment()
        assertTrue(ingestRefund().updated)
        assertFalse(ingestRefund(at=instant+120_000).updated)
        assertFalse(ingestRefund(refund(1000)).updated)
        assertEquals(2,store.captureHistory(original.id).size)
        assertEquals(3654L,store.get(original.id)!!.bill.amountCents)
        assertTrue(ingestRefund(refund(10000)).updated)
        val full=store.get(original.id)!!
        assertEquals(0L,full.bill.amountCents);assertEquals(RecordState.POSTED,full.state)
        assertEquals(3,store.captureHistory(original.id).size)
        assertEquals(0L,store.posted(instant,instant+1).sumOf { it.bill.amountCents!! })
        assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture(at=instant+240_000),bill()))
        assertEquals(0L,store.get(original.id)!!.bill.amountCents);assertEquals(1,store.all().size)
        assertEquals("全退后备注",store.saveEdited(full.copy(note="全退后备注")).note)
        assertTrue(runCatching { store.saveEdited(full.copy(bill=full.bill.copy(amountCents=100))) }.isFailure)
    }

    @Test fun shakeOriginalPaymentAfterRefundOnlyAddsEvidenceAndKeepsNetExpense() {
        val original=payment(id=null)
        val pending=ingestRefund().record
        store.confirmRefund(pending.id,original.id)
        assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture(at=instant+120_000),bill(id=null),screenshot=first))
        assertEquals(1,store.all().size);assertEquals(3654L,store.get(original.id)!!.bill.amountCents)
        val uncertain=store.ingest(capture(at=instant+180_000,text="重新打开的原付款详情"),bill(id=null),
            screenshot=byteArrayOf(7,8,9)) as IngestResult.Saved
        assertEquals(RecordState.POSTED,uncertain.record.state);assertEquals(original.id,uncertain.record.id)
        assertEquals(1,store.all().size);assertArrayEquals(byteArrayOf(7,8,9),store.historyScreenshot(original.id,3))
        assertEquals(3654L,store.posted(instant,instant+1).sumOf { it.bill.amountCents!! })
        // Equal merchant and amount well after the old transaction may be a real new purchase.
        val newer=store.ingest(capture(at=instant+3_600_000,text="另一笔真实付款"),bill(id=null).copy(occurredAt=instant+3_600_000),
            screenshot=byteArrayOf(9,9,9)) as IngestResult.Saved
        assertEquals(RecordState.POSTED,newer.record.state)
    }

    @Test fun unknownTransactionRequiresSelectionAndConfirmRechecksAnEditedTarget() {
        val original=payment(id=null)
        val pending=ingestRefund().record
        assertEquals(RecordState.REVIEW,pending.state);assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
        assertEquals(listOf(original.id),store.refundCandidates(pending.id).map { it.id })
        val edited=store.saveEdited(original.copy(bill=original.bill.copy(amountCents=9000)))
        assertTrue(store.refundCandidates(pending.id).isEmpty())
        assertTrue(runCatching { store.confirmRefund(pending.id,edited.id) }.isFailure)
        store.saveEdited(edited.copy(bill=edited.bill.copy(amountCents=10000)))
        val confirmed=store.confirmRefund(pending.id,original.id)
        assertTrue(confirmed.updated);assertEquals(original.id,confirmed.record.id);assertNull(store.get(pending.id))
        assertEquals(2,store.captureHistory(original.id).size);assertArrayEquals(second,store.historyScreenshot(original.id,2))
        assertEquals("SYNTHETIC-PAYMENT",store.get(original.id)!!.bill.transactionId)
    }

    @Test fun sameMerchantAndAmountWithDifferentOrdersOrProvidersCannotBeAutoUpdated() {
        val original=payment()
        val other=payment("alipay")
        val pending=ingestRefund(refund().copy(transactionId="SYNTHETIC-OTHER")).record
        // An explicit different order is its own completed payment; post its net expense separately.
        assertEquals(RecordState.POSTED,pending.state);assertTrue(store.refundCandidates(pending.id).isEmpty())
        assertEquals(10000L,store.get(original.id)!!.bill.amountCents);assertEquals(10000L,store.get(other.id)!!.bill.amountCents)
        assertTrue(runCatching { store.confirmRefund(pending.id,other.id) }.isFailure)
        val contradictory=ingestRefund(refund(provider="alipay"),provider="wechat").record
        assertEquals(RecordState.REVIEW,contradictory.state)
        assertTrue(store.refundCandidates(contradictory.id).isEmpty())
        assertTrue(runCatching { store.confirmRefund(contradictory.id,other.id) }.isFailure)
    }

    @Test fun originalAmountConflictAndUnreliableEvidenceStayOutsidePostedTotals() {
        val original=payment()
        val conflict=refund().copy(amountCents=4654,refund=RefundInfo("wechat",11000,6346))
        assertEquals(RecordState.REVIEW,ingestRefund(conflict).record.state)
        val uncertain=ingestRefund(refund().copy(reliable=false,reason="原交易时间需核对")).record
        assertEquals(RecordState.REVIEW,uncertain.state)
        assertEquals(10000L,store.posted(instant,instant+1).sumOf { it.bill.amountCents!! })
        assertEquals(original.id,store.confirmRefund(uncertain.id,original.id).record.id)
    }

    @Test fun singleRefundIdsPersistAcrossReopenAndConflictsAndModeMixingNeedReview() {
        val original=payment()
        assertTrue(ingestRefund(refund(3000,mode="single",refundId="REFUND-A")).updated)
        store.close();store=LedgerStore(context,database,images)
        assertFalse(ingestRefund(refund(3000,mode="single",refundId="REFUND-A")).updated)
        val conflict=ingestRefund(refund(4000,mode="single",refundId="REFUND-A")).record
        assertEquals(RecordState.REVIEW,conflict.state);assertTrue(conflict.reason.contains("冲突"))
        val mixed=ingestRefund(refund(5000)).record
        assertEquals(RecordState.REVIEW,mixed.state);assertTrue(mixed.reason.contains("口径"))
        assertTrue(runCatching { store.confirmRefund(mixed.id,original.id) }.isFailure)
        assertTrue(ingestRefund(refund(3346,mode="single",refundId="REFUND-B")).updated)
        assertEquals(3654L,store.get(original.id)!!.bill.amountCents)
        assertEquals(3,store.captureHistory(original.id).size)
    }

    @Test fun ageRetentionPreservesEvidenceCapacityLeavesStablePlaceholdersAndDeleteReleasesAllImages() {
        val original=payment();ingestRefund()
        images.cleanup(instant+365*86_400_000L)
        assertTrue(store.captureHistory(original.id).all { it.hasScreenshot })
        images.cleanup(instant+365*86_400_000L,0)
        assertEquals(listOf(1,2),store.captureHistory(original.id).map { it.sequence })
        assertTrue(store.captureHistory(original.id).none { it.hasScreenshot })
        assertEquals(3654L,store.get(original.id)!!.bill.amountCents)
        store.delete(original.id);assertTrue(store.captureHistory(original.id).isEmpty());assertEquals(0,images.stats().getInt("count"))
    }

    @Test fun restoreHistoryIsIdempotentAndKeepsSingleRefundIdentity() {
        val original=payment();ingestRefund(refund(6346,mode="single",refundId="REFUND-A"))
        val record=store.get(original.id)!!
        val entries=store.captureHistory(original.id).map { RestoredCaptureHistory(it,store.historyScreenshot(original.id,it.sequence)) }
        store.delete(original.id);assertEquals(1,store.merge(listOf(record)))
        val restored=store.all().single()
        store.restoreHistory(restored.id,entries);store.restoreHistory(restored.id,entries)
        assertEquals(2,store.captureHistory(restored.id).size);assertArrayEquals(first,store.historyScreenshot(restored.id,1))
        assertFalse(ingestRefund(refund(6346,mode="single",refundId="REFUND-A")).updated)
        assertEquals(3654L,store.get(restored.id)!!.bill.amountCents)
    }

    @Test fun databaseFailureRollsBackAmountAndHistoryTogether() {
        val original=payment()
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_refund BEFORE INSERT ON capture_history
            WHEN NEW.sequence=2 BEGIN SELECT RAISE(ABORT,'synthetic history failure'); END""")
        assertTrue(runCatching { ingestRefund() }.isFailure)
        assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
        assertEquals(listOf(1),store.captureHistory(original.id).map { it.sequence })
        assertEquals(1,images.stats().getInt("count"))
    }

    @Test fun failedHistoryRestoreRollsBackRowsAndReleasesOnlyItsNewImageOwners() {
        val original=payment();ingestRefund()
        val record=store.get(original.id)!!
        val entries=store.captureHistory(original.id).map { RestoredCaptureHistory(it,store.historyScreenshot(original.id,it.sequence)) }
        store.delete(original.id);store.merge(listOf(record))
        val restored=store.all().single()
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_restore BEFORE INSERT ON capture_history
            WHEN NEW.sequence=2 BEGIN SELECT RAISE(ABORT,'synthetic restore failure'); END""")
        assertTrue(runCatching { store.restoreHistory(restored.id,entries) }.isFailure)
        assertTrue(store.captureHistory(restored.id).isEmpty());assertEquals(0,images.stats().getInt("count"))
        store.writableDatabase.execSQL("DROP TRIGGER reject_restore")
        store.attachScreenshot(restored.id,first)
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_restore_again BEFORE INSERT ON capture_history
            WHEN NEW.sequence=2 BEGIN SELECT RAISE(ABORT,'synthetic restore failure'); END""")
        assertTrue(runCatching { store.restoreHistory(restored.id,entries) }.isFailure)
        assertTrue(store.captureHistory(restored.id).isEmpty());assertEquals(1,images.stats().getInt("count"))
        assertArrayEquals(first,store.screenshot(restored.id))
    }

    @Test fun versionSixMigrationPreservesOriginalImageAndAddsFirstHistoryWithoutDuplicatingBytes() {
        val original=payment()
        val db=store.writableDatabase
        // A v6 schema has no refund columns or capture_history; construct it from the current fixture data.
        db.execSQL("CREATE TABLE legacy_records AS SELECT id,uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,transaction_id,reliable,time_explicit,state,reason,duplicate_id,note FROM records")
        db.execSQL("DROP TABLE records");db.execSQL("ALTER TABLE legacy_records RENAME TO records")
        db.execSQL("DROP TABLE capture_history")
        db.version=6;store.close();store=LedgerStore(context,database,images)
        assertEquals(14,store.readableDatabase.version);assertEquals(original,store.get(original.id))
        assertEquals(1,store.captureHistory(original.id).size);assertArrayEquals(first,store.historyScreenshot(original.id,1))
        assertEquals(1,images.stats().getInt("count"))
    }
}
