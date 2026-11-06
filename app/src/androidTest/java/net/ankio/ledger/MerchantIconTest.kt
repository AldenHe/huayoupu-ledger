package net.ankio.ledger

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MerchantIconTest {
    @Test fun circleLocationUsesOnePixelRadiusForBothAxesOnTallScreenshots() {
        val circle=JSONObject().put("kind","merchant_logo").put("shape","circle")
            .put("coordinate_space","pixels").put("center",JSONArray(listOf(200,120)))
            .put("radius",40).put("confidence",0.98)
        val region=MerchantIconRegion.parse(circle,400,800)
        assertEquals(MerchantIconRegion(400.0,100.0,600.0,200.0),region)
        val bitmap=fixture()
        try { assertNotNull(MerchantIconCropper.crop(jpeg(bitmap),region)) } finally { bitmap.recycle() }
        assertNull(MerchantIconRegion.parse(circle))
        assertNull(MerchantIconRegion.parse(circle.put("radius",-40),400,800))
        assertNull(MerchantIconRegion.parse(circle.put("radius",40).put("center",JSONArray(listOf(5,5))),400,800))
        assertNull(MerchantIconRegion.parse(circle.put("coordinate_space","normalized_1000"),400,800))
    }
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun metadata() = JSONObject().put("kind", "merchant_logo")
        .put("box", JSONArray(listOf(400, 100, 600, 200))).put("confidence", 0.98)
    private fun bill(merchant: String = "图标测试商家") = JSONObject().put("is_bill", true).put("amount", "12.34")
        .put("direction", "expense").put("merchant", merchant).put("category", "餐饮")
        .put("occurred_at", JSONObject.NULL).put("transaction_id", JSONObject.NULL)
        .put("raw_text", "支付成功 商户 $merchant 支付金额12.34元").put("reliable", false)
    private fun fixture(): Bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        canvas.drawRect(160f, 80f, 240f, 160f, Paint().apply { color = Color.rgb(5, 120, 88) })
        canvas.drawRect(180f, 100f, 220f, 140f, Paint().apply { color = Color.WHITE })
    }
    private fun jpeg(bitmap: Bitmap) = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it); it.toByteArray()
    }
    private fun response(json: JSONObject) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", json.toString()))))

    @Test fun slightlyTightCircleBoxRetainsTheOuterRingWithSafeMargin() {
        val bitmap=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas=Canvas(bitmap)
            canvas.drawCircle(200f,120f,40f,Paint().apply { color=Color.RED })
            canvas.drawCircle(200f,120f,37f,Paint().apply { color=Color.WHITE })
            val bytes=MerchantIconCropper.crop(jpeg(bitmap),MerchantIconRegion(410.0,105.0,590.0,195.0))!!
            val icon=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            try {
                assertTrue("The north and south tips must survive an underestimated box",(0 until 128).any { y ->
                    val c=icon.getPixel(64,y);Color.red(c)>180 && Color.green(c)<100
                })
            } finally { icon.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun colouredLogoWithEqualLuminanceStillHasVisibleDetail() {
        val bitmap=fixture()
        try {
            val canvas=Canvas(bitmap)
            canvas.drawRect(160f,80f,200f,160f,Paint().apply { color=Color.rgb(180,0,0) })
            canvas.drawRect(200f,80f,240f,160f,Paint().apply { color=Color.rgb(0,92,0) })
            assertNotNull(MerchantIconCropper.crop(jpeg(bitmap),MerchantIconRegion.parse(metadata())))
        } finally { bitmap.recycle() }
    }

    @Test fun explicitUnitCoordinatesAreConvertedWithoutGuessing() {
        val fractional=metadata().put("coordinate_space","normalized_1")
            .put("box",JSONArray(listOf(0.4,0.1,0.6,0.2)))
        assertEquals(MerchantIconRegion(400.0,100.0,600.0,200.0),MerchantIconRegion.parse(fractional))
        assertNull(MerchantIconRegion.parse(metadata().put("coordinate_space","ambiguous")))
        val pixels=metadata().put("coordinate_space","pixels").put("box",JSONArray(listOf(160,80,240,160)))
        assertEquals(MerchantIconRegion(400.0,100.0,600.0,200.0),MerchantIconRegion.parse(pixels,400,800))
        assertNull(MerchantIconRegion.parse(pixels)) // No guessed image dimensions.
    }

    @Test fun exactDuplicateCanFillMissingSameMerchantIconWithoutChangingBill() {
        val name="icon-duplicate-fill-${UUID.randomUUID()}.db"
        val store=LedgerStore(app,name)
        val bitmap=fixture()
        try {
            val parsed=SyntheticRecognition.parseRecognition(bill().toString()).bill!!.copy(transactionId="fixed-icon-transaction")
            val capture=Capture(Source.OCR,"com.tencent.mm","duplicate icon fixture")
            val saved=(store.ingest(capture,parsed) as IngestResult.Saved).record
            val icon=MerchantIconCropper.crop(jpeg(bitmap),MerchantIconRegion.parse(metadata()))!!
            assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture,parsed,merchantIcon=icon))
            assertArrayEquals(icon,store.merchantIcon(saved.id))
            assertEquals(listOf(saved),store.all())
            val alternate=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            val replacement=try { alternate.eraseColor(Color.RED);MerchantIconEncoding.encode(alternate)!! } finally { alternate.recycle() }
            assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture,parsed,merchantIcon=replacement))
            assertArrayEquals(icon,store.merchantIcon(saved.id)) // First valid asset still wins.
        } finally { store.close();app.deleteDatabase(name);bitmap.recycle() }
    }

    @Test fun duplicateWithDifferentMerchantCannotPoisonEitherSharedIcon() {
        val name="icon-duplicate-name-${UUID.randomUUID()}.db"
        val store=LedgerStore(app,name);val bitmap=fixture()
        try {
            val parsed=SyntheticRecognition.parseRecognition(bill().toString()).bill!!.copy(transactionId="fixed-other-name")
            val capture=Capture(Source.OCR,"com.tencent.mm","fixture")
            val saved=(store.ingest(capture,parsed) as IngestResult.Saved).record
            val icon=MerchantIconCropper.crop(jpeg(bitmap),MerchantIconRegion.parse(metadata()))!!
            assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture,parsed.copy(merchant="另一个商户"),merchantIcon=icon))
            assertNull(store.merchantIcon(saved.id));assertTrue(store.merchantIconIds().isEmpty())
            assertEquals(listOf(saved),store.all())
        } finally { store.close();app.deleteDatabase(name);bitmap.recycle() }
    }

    @Test fun malformedOrMissingIconNeverChangesFinancialParsing() {
        val baseline = SyntheticRecognition.parseRecognition(bill().toString(), 1_790_000_000_000L)
        val bad = listOf<Any>(JSONObject.NULL, "https://example.invalid/logo.png", JSONObject(),
            metadata().put("confidence", "0.99"), metadata().put("confidence", 0.89), metadata().put("confidence", 2),
            metadata().put("kind", "platform_logo"), metadata().put("box", JSONArray(listOf(400, 100, 600))),
            metadata().put("box", JSONArray(listOf(-1, 100, 600, 200))),
            metadata().put("box", JSONArray(listOf(400, 200, 600, 100))),
            metadata().put("box", JSONArray(listOf("400", 100, 600, 200))),
            metadata().put("box", JSONArray(listOf(400, 100, 1001, 200))))
        bad.forEach {
            val parsed = SyntheticRecognition.parseRecognition(bill().put("merchant_icon", it).toString(), 1_790_000_000_000L)
            assertEquals(baseline.bill, parsed.bill)
            assertNull(parsed.iconRegion)
        }
        assertNotNull(SyntheticRecognition.parseRecognition(bill().put("merchant_icon", metadata()).toString()).iconRegion)
        assertNull(SyntheticRecognition.parseRecognition("{\"is_bill\":false,\"merchant_icon\":${metadata()}}").iconRegion)
    }

    @Test fun cropUsesPhysicalAspectRatioAndPreservesTheSelectedPixels() {
        val bitmap = fixture()
        try {
            val bytes = MerchantIconCropper.crop(jpeg(bitmap), MerchantIconRegion.parse(metadata()))!!
            assertTrue(bytes.size <= 128 * 1024)
            val icon = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(128, icon.width); assertEquals(128, icon.height)
                assertTrue(Color.green(icon.getPixel(20, 20)) > 90)
                assertTrue(Color.red(icon.getPixel(20, 20)) < 30)
                assertTrue(Color.red(icon.getPixel(64, 64)) > 240)
            } finally { icon.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun adaptiveCompressionPreservesEveryThumbnailPixelAndChoosesNoMoreBytesThanEitherFormat() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 128) for (x in 0 until 128)
                bitmap.setPixel(x, y, Color.rgb(x * 2, y * 2, (x + y) % 256))
            fun compressed(format: Bitmap.CompressFormat) = ByteArrayOutputStream().use {
                bitmap.compress(format, 100, it); it.toByteArray()
            }
            val selected = MerchantIconEncoding.encode(bitmap)!!
            assertTrue(selected.size <= compressed(Bitmap.CompressFormat.PNG).size)
            assertTrue(selected.size <= compressed(Bitmap.CompressFormat.WEBP_LOSSLESS).size)
            val decoded = BitmapFactory.decodeByteArray(selected, 0, selected.size)
            try {
                val expected = IntArray(128 * 128); val actual = IntArray(expected.size)
                bitmap.getPixels(expected, 0, 128, 0, 0, 128, 128)
                decoded.getPixels(actual, 0, 128, 0, 0, 128, 128)
                assertArrayEquals(expected, actual)
            } finally { decoded.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun invalidGeometryAndBlankCropsAreOptionalFailures() {
        val bitmap = fixture()
        try {
            val image = jpeg(bitmap)
            for (box in listOf(listOf(0, 0, 1000, 1000), listOf(400, 100, 410, 105),
                listOf(400, 100, 600, 120), listOf(0, 0, 200, 100))) {
                assertNull(MerchantIconCropper.crop(image, MerchantIconRegion.parse(metadata().put("box", JSONArray(box)))))
            }
            assertNull(MerchantIconCropper.crop(byteArrayOf(1, 2, 3), MerchantIconRegion.parse(metadata())))
            assertNull(MerchantIconCropper.crop(image, MerchantIconRegion(-1.0, 0.0, 200.0, 100.0)))
        } finally { bitmap.recycle() }
    }

    @Test fun rejectedCropExplainsGeometrySeparatelyFromBlankImage() {
        val bitmap=fixture()
        try {
            val image=jpeg(bitmap)
            val tooLarge=MerchantIconCropper.evaluate(image,MerchantIconRegion(0.0,0.0,1000.0,500.0))
            assertNull(tooLarge.bytes);assertEquals("too_large",tooLarge.reason)
            assertTrue(tooLarge.details.contains("image=400x800"))
            val blank=MerchantIconCropper.evaluate(image,MerchantIconRegion(0.0,0.0,200.0,100.0))
            assertNull(blank.bytes);assertEquals("no_detail",blank.reason)
            assertEquals("too_small",MerchantIconCropper.evaluate(image,MerchantIconRegion(400.0,100.0,410.0,105.0)).reason)
            assertEquals("aspect_ratio",MerchantIconCropper.evaluate(image,MerchantIconRegion(400.0,100.0,600.0,120.0)).reason)
        } finally { bitmap.recycle() }
    }

    @Test fun borrowedDecodedImageMatchesBytesAndKeepsCallerOwnership() {
        val source=fixture()
        val image=try { jpeg(source) } finally { source.recycle() }
        val decoded=BitmapFactory.decodeByteArray(image,0,image.size)
        try {
            val regions=listOf(null,MerchantIconRegion(400.0,100.0,600.0,200.0),
                MerchantIconRegion(-1.0,0.0,200.0,100.0),MerchantIconRegion(0.0,0.0,1000.0,500.0),
                MerchantIconRegion(400.0,100.0,410.0,105.0),MerchantIconRegion(400.0,100.0,600.0,120.0),
                MerchantIconRegion(0.0,0.0,200.0,100.0))
            for(region in regions) {
                val encoded=MerchantIconCropper.evaluate(image,region)
                val borrowed=MerchantIconCropper.evaluate(decoded,region)
                assertEquals(encoded.reason,borrowed.reason)
                assertEquals(encoded.details,borrowed.details)
                assertArrayEquals(encoded.bytes,borrowed.bytes)
                assertFalse(decoded.isRecycled)
                assertEquals(Color.WHITE,decoded.getPixel(0,0))
            }
        } finally { decoded.recycle() }
    }

    @Test fun sameRequestCropsTheExactSubmittedResizedImage() = runBlocking {

        val original = fixture()
        val tall = Bitmap.createScaledBitmap(original, 1200, 2400, false)
        var calls = 0
        val client = OcrClient(app, SyntheticRecognitionBackend { _, _, payload, guard ->
            assertTrue(guard()); calls++
            val content = payload!!.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            val image = Base64.decode(content.getJSONObject(1).getJSONObject("image_url").getString("url").substringAfter(','), Base64.NO_WRAP)
            val sent = BitmapFactory.decodeByteArray(image, 0, image.size)
            try { assertEquals(900, sent.width); assertEquals(1800, sent.height) } finally { sent.recycle() }
            response(bill().put("merchant_icon",JSONObject().put("kind","merchant_logo").put("shape","circle")
                .put("coordinate_space","pixels").put("center",JSONArray(listOf(450,270))).put("radius",90).put("confidence",0.98)))
        })
        try {
            val result = client.recognize(tall)
            assertEquals(1, calls); assertNotNull(result.merchantIcon)
            assertTrue(result.iconDetails.contains("reason=saved"));assertTrue(result.iconDetails.contains("image=900x1800"))
            assertArrayEquals(MerchantIconCropper.crop(result.screenshot!!, result.iconRegion), result.merchantIcon)
            val decoded = BitmapFactory.decodeByteArray(result.merchantIcon, 0, result.merchantIcon!!.size)
            try { assertTrue(Color.red(decoded.getPixel(20, 20)) < 30); assertTrue(Color.red(decoded.getPixel(64, 64)) > 240) }
            finally { decoded.recycle() }
        } finally { tall.recycle(); original.recycle(); app.recognitionSession.invalidate() }
    }

    @Test fun versionThreeMigrationPreservesBillsScreenshotsAndAddsEmptyIcons() {
        val name = "merchant-migration-${UUID.randomUUID()}.db"
        var store = LedgerStore(app, name)
        val bitmap = fixture()
        try {
            val image = jpeg(bitmap)
            val parsed = SyntheticRecognition.parseRecognition(bill().toString()).bill!!
            val saved = (store.ingest(Capture(Source.IMAGE, "image", "migration"), parsed, screenshot = image) as IngestResult.Saved).record
            store.writableDatabase.execSQL("DROP TABLE merchant_icons")
            store.writableDatabase.execSQL("DROP TABLE merchant_icon_assets")
            // v3 kept screenshot bytes in capture_images, before the later reference-only layout.
            store.writableDatabase.apply {
                execSQL("DROP TABLE capture_images")
                execSQL("CREATE TABLE capture_images (record_id INTEGER PRIMARY KEY NOT NULL, image BLOB NOT NULL)")
                execSQL("INSERT INTO capture_images(record_id,image) VALUES (?,?)",arrayOf(saved.id,image))
            }
            store.close();LegacyLedgerSchemaFixture.project(app.getDatabasePath(name).path,3)
            store = LedgerStore(app, name)
            assertEquals(10, store.readableDatabase.version)
            assertEquals(saved, store.get(saved.id)); assertArrayEquals(image, store.screenshot(saved.id))
            assertTrue(store.merchantIconIds().isEmpty())
        } finally { store.close(); app.deleteDatabase(name); bitmap.recycle() }
    }

    @Test fun previewVersionFourPngsMigrateIntoOneSharedAssetWithoutChangingBills() {
        val name = "merchant-preview-migration-${UUID.randomUUID()}.db"
        var store = LedgerStore(app, name)
        val bitmap = fixture()
        try {
            val parsed = SyntheticRecognition.parseRecognition(bill().toString()).bill!!
            val first = (store.ingest(Capture(Source.IMAGE, "image", "preview1"), parsed) as IngestResult.Saved).record
            val second = (store.ingest(Capture(Source.IMAGE, "image", "preview2"), parsed) as IngestResult.Saved).record
            val small = Bitmap.createScaledBitmap(bitmap, 64, 128, true)
            val png = try { ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
                finally { small.recycle() }
            store.writableDatabase.apply {
                execSQL("DROP TABLE merchant_icons"); execSQL("DROP TABLE merchant_icon_assets")
                execSQL("CREATE TABLE merchant_icons (record_id INTEGER PRIMARY KEY NOT NULL, image BLOB NOT NULL)")
                execSQL("INSERT INTO merchant_icons VALUES (?, ?)", arrayOf(first.id, png))
                execSQL("INSERT INTO merchant_icons VALUES (?, ?)", arrayOf(second.id, png))
                // v4 still used BLOB screenshots; these two fixtures intentionally have none.
                execSQL("DROP TABLE capture_images")
                execSQL("CREATE TABLE capture_images (record_id INTEGER PRIMARY KEY NOT NULL, image BLOB NOT NULL)")
            }
            store.close();LegacyLedgerSchemaFixture.project(app.getDatabasePath(name).path,4)
            store = LedgerStore(app, name)
            assertEquals(10, store.readableDatabase.version)
            assertEquals(first, store.get(first.id)); assertEquals(second, store.get(second.id))
            assertArrayEquals(store.merchantIcon(first.id), store.merchantIcon(second.id))
            assertNotNull(MerchantIconEncoding.mime(store.merchantIcon(first.id)!!))
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM merchant_icon_assets", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally { store.close(); app.deleteDatabase(name); bitmap.recycle() }
    }

    @Test fun iconsPersistAcrossEditAndReopenButAreDeletedWithLastBillAndExcludedFromBackup() {
        val name = "merchant-store-${UUID.randomUUID()}.db"
        var store = LedgerStore(app, name)
        val bitmap = fixture()
        try {
            val image = jpeg(bitmap)
            val icon = MerchantIconCropper.crop(image, MerchantIconRegion.parse(metadata()))!!
            val parsed = SyntheticRecognition.parseRecognition(bill().toString()).bill!!
            val saved = (store.ingest(Capture(Source.IMAGE, "image", "icon"), parsed, screenshot = image, merchantIcon = icon) as IngestResult.Saved).record
            store.saveEdited(saved.copy(note = "确认后保留图标"))
            store.close(); store = LedgerStore(app, name)
            assertArrayEquals(icon, store.merchantIcon(saved.id))
            val backup = LedgerBackup.encode(store, ChannelSettings(app))
            assertFalse(backup.contains("merchantIcon")); assertFalse(backup.contains("merchant_icon"))
            val restored = LedgerBackup.decode(ByteArrayInputStream(backup.toByteArray()))
            assertEquals("确认后保留图标", restored.records.single().note)
            store.delete(saved.id)
            assertNull(store.merchantIcon(saved.id)); assertNull(store.screenshot(saved.id))
            assertTrue(store.merchantIconIds().isEmpty())
        } finally { store.close(); app.deleteDatabase(name); bitmap.recycle() }
    }

    @Test fun normalizedSameNamesShareOneCompressedAssetIncludingBillsWithoutAnIcon() {
        val name = "merchant-share-${UUID.randomUUID()}.db"
        val store = LedgerStore(app, name)
        val bitmap = fixture()
        try {
            val icon = MerchantIconCropper.crop(jpeg(bitmap), MerchantIconRegion.parse(metadata()))!!
            assertTrue(MerchantIconEncoding.mime(icon) in setOf("image/png", "image/webp"))
            val decoded = BitmapFactory.decodeByteArray(icon, 0, icon.size)
            val pngSize = try { ByteArrayOutputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it); it.size() } }
                finally { decoded.recycle() }
            assertTrue("same-pixel PNG $pngSize vs selected ${icon.size}", icon.size <= pngSize)
            assertTrue("bounded synthetic icon: ${icon.size} bytes", icon.size < 16 * 1024)
            val parsed = SyntheticRecognition.parseRecognition(bill().toString()).bill!!
            fun save(merchant: String, image: ByteArray? = null) = (store.ingest(
                Capture(Source.IMAGE, "image", "share"), parsed.copy(merchant = merchant,transactionId="ICON-SHARE-${UUID.randomUUID()}"), merchantIcon = image) as IngestResult.Saved).record
            val earlier = save(" Ｍｉｎｔ　Ｃａｆｅ（东门店） ")
            assertNull(store.merchantIcon(earlier.id))
            val captured = save("Mint Cafe(东门店)", icon)
            val later = save("mint   cafe(东门店)", ByteArrayOutputStream().use { output ->
                val alternate = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                try { alternate.eraseColor(Color.BLUE); alternate.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output); output.toByteArray() }
                finally { alternate.recycle() }
            }) // Existing asset wins, regardless of replacement bytes.
            val noCrop = save("Mint Cafe(东门店)")
            listOf(earlier, captured, later, noCrop).forEach { assertArrayEquals(icon, store.merchantIcon(it.id)) }
            store.readableDatabase.rawQuery("SELECT COUNT(*), SUM(length(image)) FROM merchant_icon_assets", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(icon.size, it.getInt(1))
            }
            store.delete(captured.id); store.delete(earlier.id); store.delete(later.id)
            assertArrayEquals(icon, store.merchantIcon(noCrop.id))
            store.delete(noCrop.id)
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM merchant_icon_assets", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { store.close(); app.deleteDatabase(name); bitmap.recycle() }
    }

    @Test fun differentBranchesAndGenericNamesDoNotShareAndEditingRelinksTheIcon() {
        assertEquals(MerchantNames.key("Ｍｉｎｔ　Ｃａｆｅ（东门店）"), MerchantNames.key("mint cafe(东门店)"))
        assertNotEquals(MerchantNames.key("咖啡(东门店)"), MerchantNames.key("咖啡(西门店)"))
        assertNotEquals(MerchantNames.key("A B"), MerchantNames.key("AB"))
        for (generic in listOf("", " 未填写商户 ", "未知商户", "微信支付", "支付宝")) assertNull(MerchantNames.key(generic))
        val name = "merchant-edit-${UUID.randomUUID()}.db"
        val store = LedgerStore(app, name)
        val bitmap = fixture()
        try {
            val icon = MerchantIconCropper.crop(jpeg(bitmap), MerchantIconRegion.parse(metadata()))!!
            val parsed = SyntheticRecognition.parseRecognition(bill().toString()).bill!!
            fun save(merchant: String, image: ByteArray? = null) = (store.ingest(Capture(Source.IMAGE, "image", "edit"),
                parsed.copy(merchant = merchant), merchantIcon = image) as IngestResult.Saved).record
            val east = save("咖啡(东门店)", icon)
            val west = save("咖啡(西门店)")
            val generic = save("未知商户", icon)
            assertNull(store.merchantIcon(west.id)); assertNull(store.merchantIcon(generic.id))
            store.saveEdited(west.copy(bill = west.bill.copy(merchant = "咖啡(东门店)")))
            assertArrayEquals(icon, store.merchantIcon(west.id))
            store.saveEdited(east.copy(bill = east.bill.copy(merchant = "新的商家")))
            assertNull(store.merchantIcon(east.id)); assertArrayEquals(icon, store.merchantIcon(west.id))
            store.saveEdited(store.get(west.id)!!.copy(bill = west.bill.copy(merchant = "新的商家")))
            assertNull(store.merchantIcon(west.id))
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM merchant_icon_assets", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { store.close(); app.deleteDatabase(name); bitmap.recycle() }
    }

    @Test fun unusableCropStillSavesBillAndDuplicateCaptureDoesNotLeaveOrphanIcons() = runBlocking {

        val transaction = "ICON-${UUID.randomUUID()}"
        val json = bill().put("transaction_id", transaction)
            .put("raw_text", "微信支付成功 商户 图标测试商家 支付金额12.34元 交易单号 $transaction")
        val bitmap = fixture()
        val ids = mutableListOf<Long>()
        val taskId=app.tasks.create(Source.OCR,"com.tencent.mm")
        try {
            val badClient = OcrClient(app, SyntheticRecognitionBackend { _, _, _, _ ->
                response(json.put("merchant_icon", metadata().put("box", JSONArray(listOf(0, 0, 1000, 1000)))))
            })
            val noIcon = (CaptureProcessor(app, badClient).process(bitmap, Source.OCR, "com.tencent.mm",taskId) { true } as IngestResult.Saved).record
            ids += noIcon.id
            assertEquals(1234L, noIcon.bill.amountCents); assertNull(app.store.merchantIcon(noIcon.id))
            val diagnostic=app.tasks.snapshot().let { tasks -> (0 until tasks.length()).map { tasks.getJSONObject(it) }.first { it.getLong("id")==taskId }.getJSONObject("diagnostic") }
            assertEquals("crop_rejected",diagnostic.getString("icon"))
            assertTrue(diagnostic.getString("icon_details").contains("reason=aspect_ratio"))
            assertEquals("finished",diagnostic.getString("stage"))
            app.store.delete(noIcon.id)
            val client = OcrClient(app, SyntheticRecognitionBackend { _, _, _, _ -> response(json.put("merchant_icon", metadata())) })
            val processor = CaptureProcessor(app, client)
            val saved = (processor.process(bitmap, Source.OCR, "com.tencent.mm") { true } as IngestResult.Saved).record
            ids += saved.id
            val before = app.store.merchantIconIds()
            assertTrue(saved.id in before)
            assertEquals(IngestResult.DuplicateSkipped, processor.process(bitmap, Source.OCR, "com.tencent.mm") { true })
            assertEquals(before, app.store.merchantIconIds())
            assertEquals(IngestResult.Ignored, app.coordinator.acceptRecognition(Capture(Source.OCR, "com.tencent.mm", "cancelled"),
                saved.bill, merchantIcon = app.store.merchantIcon(saved.id)) { false })
            assertEquals(before, app.store.merchantIconIds())
        } finally { ids.forEach { app.store.delete(it) };app.tasks.delete(taskId); bitmap.recycle(); app.recognitionSession.invalidate(); app.changed() }
    }

    @Test fun capturedIconLoadsInPendingAndConfirmedRowsAndMissingResourceFallsBack() = runBlocking {

        val merchant = "本地图标-${UUID.randomUUID()}"
        val bitmap = fixture()
        var id: Long? = null
        var sharedId: Long? = null
        try {
            val processor = CaptureProcessor(app, OcrClient(app, SyntheticRecognitionBackend { _, _, _, _ ->
                response(bill(merchant).put("merchant_icon", metadata()))
            }))
            val saved = (processor.process(bitmap, Source.OCR, "com.tencent.mm") { true } as IngestResult.Saved).record
            id = saved.id
            // Deliberately stage a review row; valid captures now post immediately.
            app.store.writableDatabase.execSQL("UPDATE records SET state='REVIEW' WHERE id=?",arrayOf(saved.id))
            assertNotNull(app.store.merchantIcon(id))
            val sameName = app.store.saveEdited(saved.copy(id = 0, uid = UUID.randomUUID().toString()))
            sharedId = sameName.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario); web.ready()
                web.run("go('ledger');refresh()")
                web.await("(()=>{const i=document.querySelector('[data-pending=\"$id\"] .merchant-icon img');return i&&i.complete&&i.naturalWidth===128})()")
                web.await("(()=>{const a=document.querySelector('[data-pending=\"$id\"] img'),b=document.querySelector('[data-record=\"$sharedId\"] img');return a&&b&&b.naturalWidth===128&&a.src===b.src})()")
                scenario.recreate(); web.ready()
                web.await("document.querySelector('[data-pending=\"$id\"] .merchant-icon img').naturalWidth===128")
                app.store.saveEdited(saved); app.changed()
                web.run("refresh()")
                web.await("(()=>{const i=document.querySelector('[data-record=\"$id\"] .merchant-icon img');return i&&i.complete&&i.naturalWidth===128})()")
                web.run("go('stats')")
                web.await("document.querySelectorAll('#ranking .merchant-icon').length===0")
                web.run("go('ledger');document.querySelector('[data-record=\"$id\"] img').src='https://appassets.androidplatform.net/merchant-icon/0000000000000000000000000000000000000000000000000000000000000000.img'")
                web.await("!document.querySelector('[data-record=\"$id\"] img') && document.querySelector('[data-record=\"$id\"] .category-icon i.fa-utensils')")
            }
        } finally { id?.let { app.store.delete(it) }; sharedId?.let { app.store.delete(it) }; bitmap.recycle(); app.recognitionSession.invalidate(); app.changed() }
    }
}
