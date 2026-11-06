package net.ankio.ledger.ocr

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.ankio.ledger.ocr.RecognitionException
import net.ankio.ledger.ocr.MerchantIconCropper
import net.ankio.ledger.ocr.MerchantIconRegion
import net.ankio.ledger.ocr.RecognitionResult
import net.ankio.ledger.core.LocalOcrBillParser
import net.ankio.ledger.core.LocalOcrLine

/** Bundled recognition of the exact stored screenshot. This client has no network dependency. */
class LocalRecognitionClient(context: android.content.Context) {
    private val context = context.applicationContext
    suspend fun recognizePrepared(
        submitted: ByteArray,
        receivedAt: Long = System.currentTimeMillis(),
        onPrepared: (ByteArray) -> Unit = {},
        stillAllowed: () -> Boolean = { true },
        livePaymentAt: Long? = null,
        expenseOnly: Boolean = false,
        origin: String = "",
    ): RecognitionResult = withContext(Dispatchers.IO) {
        val taskContext=currentCoroutineContext()
        fun checkAllowed() {
            taskContext.ensureActive()
            if (!stillAllowed()) throw CancellationException("识别已停止")
        }
        checkAllowed()
        if (submitted.isEmpty() || submitted.size > 5 * 1024 * 1024) throw RecognitionException("图片过大或已失效，请重新截图")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(submitted, 0, submitted.size, bounds)
        if (bounds.outWidth !in 1..1800 || bounds.outHeight !in 1..1800) throw RecognitionException("图片尺寸无效，请重新截图")
        onPrepared(submitted)
        checkAllowed()
        // Waiting requests retain compressed bytes only, not decoded full-size bitmaps.
        LocalInferenceGate.shared.run {
            checkAllowed()
            val bitmap = BitmapFactory.decodeByteArray(submitted, 0, submitted.size)
                ?: throw RecognitionException("图片无法读取，请重新截图")
            try {
                // A cancelled task never reaches persistence; the backend owns resources until inference ends.
                val ocr = LocalOcr(context).use { it.recognize(bitmap) }
                checkAllowed()
                val lines = ocr.lines.map { line ->
                    val box = line.box
                    LocalOcrLine(line.text, box?.left ?: 0, box?.top ?: 0, box?.right ?: 0, box?.bottom ?: 0)
                }
                val bill = LocalOcrBillParser.parse(lines, ocr.width, ocr.height, receivedAt, livePaymentAt, expenseOnly, origin)
                net.ankio.ledger.core.TransactionRejection.reason(ocr.rawText,manualCapture=!expenseOnly && livePaymentAt==null)?.let { reason ->
                    return@run RecognitionResult(ocr.rawText.take(16_384),null,screenshot=submitted,skipReason=reason)
                }
                if (bill == null) return@run RecognitionResult(ocr.rawText.take(16_384), null, screenshot = submitted)
                checkAllowed()
                val icon = LocalIconLocator.locate(bitmap, ocr, bill.merchant)
                // A plausible image near unrelated text must not become this merchant's shared icon.
                val selected = icon.selected?.takeIf { LocalIconLocator.merchantAnchorMatches(it.anchor, bill.merchant) }
                val box = selected?.box
                val region = box?.let { MerchantIconRegion(it.left * 1000.0 / bitmap.width, it.top * 1000.0 / bitmap.height,
                    it.right * 1000.0 / bitmap.width, it.bottom * 1000.0 / bitmap.height) }
                val crop = region?.let { MerchantIconCropper.evaluate(bitmap, it,verifiedBox=true) }
                checkAllowed()
                RecognitionResult(ocr.rawText.take(16_384), bill, submitted, region, crop?.bytes,
                    if (crop?.bytes != null) "saved" else if (crop != null) "crop_rejected" else "not_located",
                    "method=local_geometry_and_ocr_v1; reason=${if (icon.selected != null && selected == null) "merchant_anchor_mismatch" else icon.reason}; ${crop?.details.orEmpty()}")
            } finally {
                bitmap.recycle()
            }
        }
    }

}
