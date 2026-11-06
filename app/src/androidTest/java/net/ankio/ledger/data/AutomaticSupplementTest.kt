package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomaticSupplementTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val namespace="auto-supplement-${UUID.randomUUID()}"
    private val images=ScreenshotStore(context,namespace)
    private val store=LedgerStore(context,"$namespace-ledger.db",images)
    private val at=1_790_000_000_000L
    private val first=byteArrayOf(21,22,23)
    private val second=byteArrayOf(24,25,26)
    private fun capture(time:Long=at,text:String="账单详情",origin:String=PaymentApps.WECHAT)=Capture(Source.OCR,origin,text,time)
    private fun bill(id:String?="ORIGINAL-ORDER")=ParsedBill(10000,Direction.EXPENSE,"合成石化商户","交通",at,id,true,"",true)
    private fun refund()=bill().copy(amountCents=3654,refund=RefundInfo("wechat",10000,6346))
    private fun automatic(offset:Long=15_000)= (store.ingest(capture(at+offset,"支付成功"),bill(null).copy(occurredAt=at+offset,
        timeExplicit=false,reason="已识别即时支付成功页；页面未显示交易时间，使用本机捕获时间"),screenshot=first) as IngestResult.Saved).record

    @After fun close() {
        store.all().forEach { store.delete(it.id) };store.close();images.close()
        context.deleteDatabase("$namespace-ledger.db");context.deleteDatabase("$namespace.db")
        File(context.filesDir,namespace).deleteRecursively();context.deleteSharedPreferences("${namespace}_policy")
    }

    @Test fun localLiveCaptureCanBeSupplementedByLocalExplicitRefundWithoutDuplicate() {
        val liveBill=LocalOcrBillParser.parse(listOf("支付成功","付款金额：100.00元","商户名称：合成石化商户").map(::LocalOcrLine),
            0,0,at+15_000,at+15_000,true,PaymentApps.WECHAT)!!
        assertTrue(liveBill.reliable)
        val original=(store.ingest(capture(at+15_000,"支付成功"),liveBill,screenshot=first) as IngestResult.Saved).record
        assertEquals(RecordState.POSTED,original.state)
        val timestamp=java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"))
        val detail=LocalOcrBillParser.parse(listOf("已部分退款","原付款金额：100.00元","累计退款金额：63.46元","商户名称：合成石化商户",
            "支付时间：$timestamp","交易单号：LOCAL-ORIGINAL-ORDER").map(::LocalOcrLine),0,0,at+8*3_600_000,origin=PaymentApps.WECHAT)!!
        assertTrue(detail.reason,detail.reliable)
        val update=store.ingest(capture(at+8*3_600_000),detail,screenshot=second) as IngestResult.Saved
        assertEquals(original.id,update.record.id);assertTrue(update.updated)
        assertEquals(3654L,update.record.bill.amountCents);assertEquals(1,store.all().size)
        assertEquals(2,store.captureHistory(original.id).size)
    }

    @Test fun explicitDetailCompletesUniqueAutomaticRecordAndRepeatedShakeDoesNotAddExpense() {
        val original=automatic()
        store.saveEdited(original.copy(note="保留备注"))
        val result=store.ingest(capture(at+8*3_600_000),bill(),screenshot=second) as IngestResult.Saved
        assertEquals(original.id,result.record.id);assertTrue(result.supplemented)
        assertEquals("ORIGINAL-ORDER",result.record.bill.transactionId)
        assertEquals(at,result.record.bill.occurredAt);assertTrue(result.record.bill.timeExplicit)
        assertEquals("保留备注",result.record.note);assertEquals(1,store.all().size)
        assertEquals(2,store.captureHistory(original.id).size)
        assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture(at+9*3_600_000),bill(),screenshot=second))
    }

    @Test fun refundDetailUpdatesUniqueAutomaticPaymentNetAndKeepsBothImages() {
        val original=automatic()
        val result=store.ingest(capture(at+8*3_600_000),refund(),screenshot=second) as IngestResult.Saved
        assertEquals(original.id,result.record.id);assertTrue(result.updated)
        assertEquals(3654L,result.record.bill.amountCents);assertEquals(RecordState.POSTED,result.record.state)
        assertEquals("ORIGINAL-ORDER",result.record.bill.transactionId)
        assertEquals(1,store.all().size);assertEquals(2,store.captureHistory(original.id).size)
        assertArrayEquals(first,store.historyScreenshot(original.id,1));assertArrayEquals(second,store.historyScreenshot(original.id,2))
    }

    @Test fun completeUnmatchedCumulativeRefundCreatesPostedNetExpenseAndBackupRestoresIt() {
        val result=store.ingest(capture(at+8*3_600_000),refund(),screenshot=second) as IngestResult.Saved
        assertEquals(RecordState.POSTED,result.record.state);assertEquals(3654L,result.record.bill.amountCents)
        assertFalse(result.record.reason.contains("关联"));assertEquals(1,store.all().size)
        val backup=LedgerBackup.decode(ByteArrayInputStream(LedgerBackup.encode(store,ChannelSettings(context)).toByteArray()))
        store.delete(result.record.id);assertEquals(1,LedgerBackup.restore(store,backup))
        val restored=store.all().single()
        assertEquals(3654L,restored.bill.amountCents);assertArrayEquals(second,store.historyScreenshot(restored.id,1))
        val repeat=store.ingest(capture(at+9*3_600_000),refund(),screenshot=second) as IngestResult.Saved
        assertFalse(repeat.updated);assertEquals(restored.id,repeat.record.id);assertEquals(1,store.all().size)
        val more=store.ingest(capture(at+10*3_600_000),refund().copy(amountCents=3000,refund=RefundInfo("wechat",10000,7000)),screenshot=first) as IngestResult.Saved
        assertEquals(3000L,more.record.bill.amountCents);assertEquals(restored.id,more.record.id)
    }

    @Test fun twoNearbyAutomaticPaymentsRemainAmbiguousInsteadOfSilentlyMerging() {
        automatic(10_000);automatic(30_000)
        val result=store.ingest(capture(at+3_600_000),refund(),screenshot=second) as IngestResult.Saved
        assertEquals(RecordState.REVIEW,result.record.state);assertEquals(2,store.refundCandidates(result.record.id).size)
        assertEquals(20000L,store.posted(at-1,at+120_000).sumOf { it.bill.amountCents!! })
    }

    @Test fun ordinaryDetailWithTwoNearbyAutomaticPaymentsDoesNotPostAThirdExpense() {
        automatic(10_000);automatic(30_000)
        val result=store.ingest(capture(at+3_600_000),bill(),screenshot=second)
        assertTrue(result is IngestResult.Skipped);assertEquals(2,store.all().size);assertTrue(store.pending().isEmpty())
        assertTrue(store.all().all {it.bill.transactionId==null && store.captureHistory(it.id).size==1})
        assertEquals(20000L,store.posted(at-1,at+120_000).sumOf { it.bill.amountCents!! })
    }

    @Test fun detailOfLaterRealPaymentCannotAbsorbAnEarlierAutomaticPayment() {
        val original=automatic(0)
        val later=(store.ingest(capture(at+3_600_000),bill().copy(occurredAt=at+60_000),screenshot=second) as IngestResult.Saved).record
        assertNotEquals(original.id,later.id);assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
        assertNull(store.get(original.id)!!.bill.transactionId)
    }

    @Test fun missingIdentityOrUnknownMerchantCannotAutoMerge() {
        val original=automatic()
        val uncertain=store.ingest(capture(at+3_600_000),refund().copy(transactionId=null,reliable=false,reason="原交易单号缺失"),screenshot=second) as IngestResult.Saved
        assertEquals(RecordState.REVIEW,uncertain.record.state);assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
    }

    @Test fun previousStagedRefundIsReconciledOnceWithoutLosingOriginalUidAndScreenshots() {
        val original=automatic()
        // Reproduce the previous release's staged record without changing the new ingestion policy.
        val pending=(store.ingest(capture(at+8*3_600_000),refund(),screenshot=second,requireReview=true) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,pending.state)
        assertEquals(mapOf(pending.id to original.id),store.reconcilePendingRefunds())
        assertNull(store.get(pending.id));assertEquals(original.uid,store.get(original.id)!!.uid)
        assertEquals(3654L,store.all().single().bill.amountCents)
        assertArrayEquals(second,store.historyScreenshot(original.id,2))
        assertTrue(store.reconcilePendingRefunds().isEmpty());assertEquals(2,images.stats().getInt("count"))
    }

    @Test fun unmatchedStagedFullReceiptPostsInPlaceAndReopensAndBacksUp() {
        val pending=(store.ingest(capture(at+8*3_600_000),refund(),screenshot=second,requireReview=true) as IngestResult.Saved).record
        assertEquals(mapOf(pending.id to pending.id),store.reconcilePendingRefunds())
        assertEquals(pending.uid,store.all().single().uid);assertEquals(RecordState.POSTED,store.get(pending.id)!!.state)
        val backup=LedgerBackup.decode(ByteArrayInputStream(LedgerBackup.encode(store,ChannelSettings(context)).toByteArray()))
        store.delete(pending.id);assertEquals(1,LedgerBackup.restore(store,backup))
        assertEquals(3654L,store.all().single().bill.amountCents)
    }

    @Test fun oldDateAndOtherProviderDoNotAbsorbAnUnrelatedReceipt() {
        val original=automatic()
        val old=(store.ingest(capture(at+3_600_000),refund().copy(occurredAt=at-86_400_000),screenshot=second) as IngestResult.Saved).record
        assertEquals(RecordState.POSTED,old.state);assertNotEquals(original.id,old.id)
        assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
        val alipay=(store.ingest(capture(at+3_600_000,origin=PaymentApps.ALIPAY),refund().copy(
            refund=RefundInfo("alipay",10000,6346)),screenshot=second) as IngestResult.Saved).record
        assertEquals(RecordState.POSTED,alipay.state);assertNotEquals(original.id,alipay.id)
        assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
    }

    @Test fun singleRefundWithoutOriginalCannotClaimToKnowFinalNetAndHasNoAssociationCandidate() {
        val result=(store.ingest(capture(at+3_600_000),refund().copy(refund=RefundInfo("wechat",10000,6346,"single","REFUND-ONE")),screenshot=second) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,result.state);assertTrue(store.refundCandidates(result.id).isEmpty())
        assertFalse(result.reason.contains("关联"));assertTrue(store.reconcilePendingRefunds().isEmpty())
    }

    @Test fun completeUnmatchedFullRefundIsZeroExpenseAndNeverAddsIncome() {
        val result=(store.ingest(capture(at+3_600_000),refund().copy(amountCents=0,refund=RefundInfo("wechat",10000,10000)),screenshot=second) as IngestResult.Saved).record
        assertEquals(RecordState.POSTED,result.state);assertEquals(Direction.EXPENSE,result.bill.direction)
        assertEquals(0L,result.bill.amountCents);assertEquals("full_refund",store.captureHistory(result.id).single().kind)
    }

    @Test fun failedIdentitySupplementRollsBackIdentityAndAmountAsWellAsImage() {
        val original=automatic()
        store.writableDatabase.execSQL("CREATE TRIGGER reject_auto_supplement BEFORE INSERT ON capture_history WHEN NEW.sequence=2 BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        assertTrue(runCatching { store.ingest(capture(at+3_600_000),refund(),screenshot=second) }.isFailure)
        assertEquals(original,store.get(original.id));assertEquals(1,store.captureHistory(original.id).size)
        assertEquals(1,images.stats().getInt("count"))
    }
}
