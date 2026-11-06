package net.ankio.ledger

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.ocr.RecognitionSession
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.capture.ImageImporter
import net.ankio.ledger.data.*

class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        recognitionSession
        getSystemService(android.app.NotificationManager::class.java).apply {
            // Remove the previous version's runtime/result channels during upgrade.
            listOf("shake_ledger","capture_repair","capture_feedback").forEach(::deleteNotificationChannel)
        }
        scope.launch {
            try {
                tasks.recoverInterrupted(preserveCurrentSession=true)
                taskRecovery.complete(Unit)
            } catch (error: Throwable) {
                taskRecovery.completeExceptionally(error)
                throw error
            }
            store.readableDatabase
            store.purgeExpiredDeletes().forEach(tasks::deleteForRecord)
            store.pendingDeletion()?.let { deletion ->
                scope.launch {
                    delay((deletion.expiresAt-System.currentTimeMillis()).coerceAtLeast(0)+150)
                    store.purgeExpiredDeletes().forEach(tasks::deleteForRecord);changed()
                }
            }
            for(record in store.merchantLabelRepairCandidates()) {
                val image=store.screenshot(record.id)?:continue
                runCatching {
                    val result=net.ankio.ledger.ocr.LocalRecognitionClient(this@LedgerApp).recognizePrepared(image,
                        receivedAt=record.capture.receivedAt,origin=record.capture.origin)
                    result.bill?.let { store.repairMerchantLabel(record.id,it,result.merchantIcon) }
                }
            }
            store.discardRejectedDrafts().forEach { (id,message) -> tasks.skipRefundReceipt(id,message) }
            store.discardStandaloneRefundReceipts().forEach { tasks.skipRefundReceipt(it) }
            store.reconcilePendingRefunds().forEach { (from,to) -> tasks.reassignRecord(from,to) }
            store.reconcilePendingDuplicates().forEach { (from,to) ->
                val cents=store.get(to)?.bill?.amountCents
                tasks.reassignRecord(from,to,if(cents==null)"已记账" else "已记账 ¥${net.ankio.ledger.core.Money.display(cents)}")
            }
            screenshots.cleanup();changed();captureQueue.resume()
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val taskRecovery = CompletableDeferred<Unit>()
    val revision = MutableStateFlow(0L)
    val store by lazy { LedgerStore(this) }
    val tasks by lazy { RecognitionTasks(this) }
    val screenshots by lazy { ScreenshotStore.get(this) }
    val monthlyBudget by lazy { MonthlyBudget(this) }
    val captureQueue by lazy { net.ankio.ledger.capture.PaymentRecognitionQueue(this) }
    val executionLog by lazy { ExecutionLog(this) }
    val channels by lazy { ChannelSettings(this) }
    val shakeRuntime by lazy { net.ankio.ledger.capture.ShakeRuntime(this) }
    val captureHealth by lazy { net.ankio.ledger.capture.CaptureHealth(this) }
    val recognitionSession by lazy { RecognitionSession(this) }
    val recognizer by lazy { OcrClient(this) }
    val importer by lazy { ImageImporter(this) }
    val coordinator by lazy { CaptureCoordinator(this) }
    fun changed() { revision.value = System.nanoTime() }
}

val Context.ledger: LedgerApp get() = applicationContext as LedgerApp
