package net.ankio.ledger

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImageImportTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.ledger
    private fun response(): JSONObject {
        val json = JSONObject().put("is_bill",true).put("amount","28.50").put("direction","expense")
            .put("merchant","合成咖啡店").put("category","餐饮").put("transaction_id",UUID.randomUUID().toString())
            .put("occurred_at", LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .put("raw_text","支付成功 支付金额 28.50元 商户 合成咖啡店").put("reliable",true)
        return JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",json.toString()))))
    }
    private fun fixture(): File {
        val file = File(app.cacheDir,"synthetic-import-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        return file
    }
    @Test fun imageUriDecodesSendsOnlyOneImageAndPersistsParsedBillLocally() = runBlocking {
        val file = fixture(); val before = app.store.all().map { it.uid }.toSet()
        var calls = 0
        var submitted: ByteArray? = null
        val importer = ImageImporter(app,OcrClient(app,SyntheticRecognitionBackend { _,path,payload,guard ->
            assertTrue(guard()); assertEquals("chat/completions",path); calls++
            assertEquals(1,payload!!.getJSONArray("messages").length())
            val content = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            assertEquals(2,content.length()); assertEquals("image_url",content.getJSONObject(1).getString("type"))
            assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
            submitted = android.util.Base64.decode(content.getJSONObject(1).getJSONObject("image_url").getString("url").substringAfter(','), android.util.Base64.DEFAULT)
            assertFalse(payload.has("records")); response()
        }))
        try {
            importer.start(Uri.fromFile(file))
            val state = withTimeout(10_000) { importer.state.first { it is ImageImportState.Finished || it is ImageImportState.Failed } }
            assertTrue(state.toString(),state is ImageImportState.Finished)
            val result = (state as ImageImportState.Finished).result as IngestResult.Saved
            assertEquals(2850L,app.store.get(result.record.id)!!.bill.amountCents)
            assertTrue(importer.previewBytes!!.isNotEmpty()); assertEquals(1,calls)
            assertArrayEquals(submitted, importer.previewBytes)
            assertArrayEquals(submitted, app.store.screenshot(result.record.id))
        } finally {
            importer.cancel(); app.store.all().filter { it.uid !in before }.forEach { app.store.delete(it.id) }
            app.recognitionSession.invalidate(); file.delete()
        }
    }
    @Test fun cancellingImportDuringRecognitionNeverCreatesBill() = runBlocking {
        val file = fixture(); val before = app.store.all().map { it.uid }.toSet()
        val entered = CompletableDeferred<Unit>()
        val client = OcrClient(app,SyntheticRecognitionBackend { _,_,_,_ -> entered.complete(Unit); delay(500); response() })
        val importer = ImageImporter(app,client)
        try {
            importer.start(Uri.fromFile(file)); withTimeout(10_000) { entered.await() }; importer.cancel(); delay(700)
            assertEquals(ImageImportState.Idle,importer.state.value)
            assertEquals(before,app.store.all().map { it.uid }.toSet())
            assertNull(importer.previewBytes)
        } finally { importer.cancel(); app.recognitionSession.invalidate(); file.delete() }
    }
    @Test fun unreadableImageFailsWithoutStartingRecognition() = runBlocking {
        app.recognitionSession.invalidate(); var calls = 0
        val importer = ImageImporter(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,_ -> calls++; response() }))
        importer.start(Uri.parse("content://unavailable.invalid/image"))
        withTimeout(10_000) { importer.state.first { it is ImageImportState.Failed } }
        assertEquals(0,calls); importer.cancel()
    }
}
