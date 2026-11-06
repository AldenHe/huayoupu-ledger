package net.ankio.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingReceiptRecognitionTest {
    @Test fun manualPendingReceiptIsFeedbackOnlyWhileAutomaticPaidTransferRemainsRecorded() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        val app=context.applicationContext as LedgerApp
        val before=app.store.all().map {it.uid}.toSet()
        val created=mutableListOf<Long>();val saved=mutableListOf<Long>()
        val now=System.currentTimeMillis()
        val bill=ParsedBill(1,Direction.EXPENSE,"合成收款人","转账",now,null,true,"",true)
        var text="转账成功\n待合成收款人确认收款\n转账金额：¥0.01"
        // Even a recognizer returning a plausible bill must not bypass the acceptance policy.
        val client=OcrClient(app,RecognitionBackend {_,_,_,_,_,_->RecognitionResult(text,bill)})
        val processor=CaptureProcessor(app,client)
        try {
            for(source in listOf(Source.OCR,Source.IMAGE)) {
                val task=app.tasks.create(source,PaymentApps.WECHAT).also(created::add)
                val result=processor.processPrepared(byteArrayOf(1),source,PaymentApps.WECHAT,task){true}
                assertTrue(result is IngestResult.Skipped);assertEquals("SKIPPED",app.tasks.status(task))
                assertEquals(before,app.store.all().map {it.uid}.toSet())
            }
            val auto=app.tasks.create(Source.OCR,"payment_auto:qa-pending-receipt").also(created::add)
            val paid=processor.processPrepared(byteArrayOf(1),Source.OCR,PaymentApps.WECHAT,auto){true}
            assertTrue(paid is IngestResult.Saved);saved+=(paid as IngestResult.Saved).record.id
            assertEquals("SUCCEEDED",app.tasks.status(auto))
            text="转账详情\n对方已收款\n转账金额：¥0.01"
            val manual=app.tasks.create(Source.OCR,PaymentApps.WECHAT).also(created::add)
            val completed=processor.processPrepared(byteArrayOf(1),Source.OCR,PaymentApps.WECHAT,manual){true}
            assertFalse(completed is IngestResult.Skipped)
            if(completed is IngestResult.Saved)saved+=completed.record.id
        } finally {created.forEach(app.tasks::delete);saved.forEach(app.store::delete);app.changed()}
    }
}
