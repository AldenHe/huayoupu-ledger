package net.ankio.ledger.capture

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult

/** Shared local persistence pipeline; the bundled recognizer reads the exact stored image. */
class CaptureProcessor(private val app: LedgerApp, private val client: OcrClient = app.recognizer) {
    suspend fun process(bitmap: Bitmap, source: Source, origin: String, taskId:Long?=null, beforeAccept:suspend ()->Unit={}, allowed: () -> Boolean): IngestResult {
        return processRecognition(source,origin,taskId,beforeAccept,allowed) { guard ->
            recognize(client.prepareScreenshot(bitmap), taskId, origin, guard)
        }
    }
    suspend fun processPrepared(image:ByteArray,source:Source,origin:String,taskId:Long,allowed:()->Boolean):IngestResult =
        processRecognition(source,origin,taskId,{},allowed) { guard -> recognize(image, taskId, origin, guard) }

    private suspend fun recognize(image:ByteArray,taskId:Long?,origin:String,guard:()->Boolean):net.ankio.ledger.ocr.RecognitionResult {
        val prepared:(ByteArray)->Unit={ bytes -> taskId?.let { app.tasks.prepared(it,bytes);app.changed() } }
        val liveAt=taskId?.let(app.tasks::livePaymentAt)
        val expenseOnly=taskId?.let(app.tasks::automatic)?:false
        return client.recognizePrepared(image,onPrepared=prepared,stillAllowed=guard,livePaymentAt=liveAt,expenseOnly=expenseOnly,origin=origin)
    }

    private fun recordedMessage(cents:Long?)=if(cents==null)"已记账" else "已记账 ¥${net.ankio.ledger.core.Money.display(cents)}"

    private suspend fun processRecognition(source:Source,origin:String,taskId:Long?,beforeAccept:suspend ()->Unit,allowed:()->Boolean,
        recognize:suspend (()->Boolean)->net.ankio.ledger.ocr.RecognitionResult):IngestResult {
        require(source == Source.OCR || source == Source.IMAGE)
        val guard={ allowed() && (taskId==null || app.tasks.exists(taskId)) }
        try {
            val identity=taskId?.let(app.tasks::paymentIdentity)
            if(identity!=null && (app.store.hasCaptureEvent(identity) || app.tasks.legacyFrameRecords(taskId).any { app.store.get(it)!=null })) {
                if(!guard())throw CancellationException("识别已停止")
                app.store.rememberCaptureEvent(identity)
                val record=(app.tasks.recordForPaymentIdentity(identity)?.let(app.store::get)
                    ?:app.tasks.legacyFrameRecords(taskId).firstNotNullOfOrNull(app.store::get))
                app.tasks.update(taskId,"DUPLICATE",recordedMessage(record?.bill?.amountCents),record?.id)
                app.tasks.diagnose(taskId,"stage","duplicate_before_model")
                app.changed();return IngestResult.DuplicateSkipped
            }
            taskId?.let { app.tasks.diagnose(it,"stage","local_recognition");app.tasks.diagnose(it,"engine","local") }
            val recognized = recognize(guard)
            currentCoroutineContext().ensureActive()
            beforeAccept()
            if(!guard())throw CancellationException("识别已停止")
            val skip=recognized.skipReason ?: net.ankio.ledger.core.TransactionRejection.reason(recognized.rawText,
                manualCapture=taskId?.let(app.tasks::automatic)!=true) ?:
                recognized.bill?.takeIf { it.refund!=null && it.amountCents==0L }?.let { "已全额退款，不予记账" }
            skip?.let { message ->
                taskId?.let { app.tasks.update(it,"SKIPPED",message);app.tasks.diagnose(it,"stage","skipped_transaction") }
                app.changed();return IngestResult.Skipped(message)
            }
            taskId?.let {
                app.tasks.diagnose(it,"icon",recognized.iconStatus)
                if(recognized.iconDetails.isNotBlank())app.tasks.diagnose(it,"icon_details",recognized.iconDetails)
                app.tasks.diagnose(it,"stage","save_result")
            }
            val result=app.coordinator.acceptRecognition(Capture(source, origin, recognized.rawText), recognized.bill, recognized.screenshot, recognized.merchantIcon, eventKey=identity, allowed=guard)
            if(result==IngestResult.Ignored&&!guard())throw CancellationException("识别已停止")
            taskId?.let { id ->
                // Explain REVIEW without exporting the model response or financial fields.
                app.tasks.diagnose(id,"decision",org.json.JSONObject().apply {
                    put("validated",recognized.bill?.reliable==true)
                    put("hasAmount",recognized.bill?.amountCents!=null);put("hasDirection",recognized.bill?.direction!=null)
                    put("timeExplicit",recognized.bill?.timeExplicit==true)
                    if(result is IngestResult.Saved) {
                        put("state",result.record.state.name);put("possibleDuplicate",result.record.duplicateId!=null)
                        if(result.record.state==net.ankio.ledger.core.RecordState.REVIEW)put("reviewReason",result.record.reason.take(220))
                    } else put("outcome",if(result==IngestResult.DuplicateSkipped)"duplicate_skipped" else "no_bill")
                }.toString())
                when(result) {
                    is IngestResult.Saved -> app.tasks.update(id,if(result.record.state==net.ankio.ledger.core.RecordState.REVIEW) "REVIEW" else "SUCCEEDED",if(result.record.state==net.ankio.ledger.core.RecordState.REVIEW) result.record.reason else recordedMessage(result.record.bill.amountCents),result.record.id)
                    IngestResult.DuplicateSkipped -> {
                        val capture=Capture(source,origin,recognized.rawText)
                        // The primary legacy field may still be a Taobao order ID;
                        // resolve an Alipay/WeChat alias through the scoped index first.
                        val original=recognized.bill?.let { app.store.matchingIdentity(capture,it) } ?: run {
                            val exact=recognized.bill?.let { bill -> net.ankio.ledger.core.DuplicateDetector.find(capture,bill,app.store.all().map { it.existing() }) } as? net.ankio.ledger.core.Duplicate.Exact
                            exact?.id?.let(app.store::get)
                        }
                        app.tasks.update(id,"DUPLICATE",recordedMessage(original?.bill?.amountCents ?: recognized.bill?.amountCents),original?.id)
                    }
                    IngestResult.Ignored -> app.tasks.update(id,"NO_BILL","未识别到单笔账单，可换到账单详情页重试")
                    is IngestResult.Skipped -> app.tasks.update(id,"SKIPPED",result.message)
                }
                app.tasks.diagnose(id,"stage","finished")
                app.changed()
            }
            return result
        } catch(error:CancellationException) {
            taskId?.let { app.tasks.update(it,"CANCELLED","识别已停止，未新增账单");app.changed() };throw error
        } catch(error:Exception) {
            app.executionLog.event("model","local","failed",org.json.JSONObject().put("errorType",error.javaClass.simpleName)
                .put("message",(error as? net.ankio.ledger.ocr.RecognitionException)?.userMessage?:"识别失败"))
            taskId?.let { app.tasks.update(it,"FAILED",(error as? net.ankio.ledger.ocr.RecognitionException)?.userMessage?:"识别失败，请换一张清晰截图重试");app.changed() };throw error
        }
    }
}
