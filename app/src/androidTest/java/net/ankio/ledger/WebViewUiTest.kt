package net.ankio.ledger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ImageImportState
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebViewUiTest {
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun legacyShakeRecordShowsItsFieldsAndDoesNotPretendToHaveAScreenshot() {
        val now = System.currentTimeMillis()
        val merchant = "历史摇动-${UUID.randomUUID()}"
        val saved = (app.store.ingest(Capture(Source.OCR, "com.tencent.mm", "支付成功 $merchant 9.60元", now),
            ParsedBill(960L, Direction.EXPENSE, merchant, "餐饮", now, null, false, "交易时间需确认")) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario); web.ready()
                web.run("openRecord(${saved.id})")
                web.await("document.querySelector('#merchant').value===${quote(merchant)} && document.querySelector('#amount').value==='9.6'")
                web.await("document.querySelector('.scanning').textContent.includes('未保存原始截图') && document.querySelector('.receipt')===null")
            }
        } finally { app.store.delete(saved.id); app.changed() }
    }

    @Test fun realDomEventsSaveDirectionalFilterChangeMonthEditAndDelete() {
        val prefix = "UI-${UUID.randomUUID()}"
        val expenseName = "$prefix-支出"
        val incomeName = "$prefix-收入"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                changeMonth(web, "2098-08")
                val from = LocalDateTime.of(2098, 8, 1, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val to = LocalDateTime.of(2098, 9, 1, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val previous = app.store.posted(from, to)
                val incomeBefore = previous.filter { it.bill.direction?.name == "INCOME" }.sumOf { it.bill.amountCents ?: 0 }
                val expenseBefore = previous.filter { it.bill.direction?.name == "EXPENSE" }.sumOf { it.bill.amountCents ?: 0 }
                fillAndSave(web, expenseName, "12.34", income = false)
                fillAndSave(web, incomeName, "45.67", income = true)
                val expense = app.store.all().single { it.bill.merchant == expenseName }
                val income = app.store.all().single { it.bill.merchant == incomeName }
                assertEquals(1234L, expense.bill.amountCents)
                assertEquals(4567L, income.bill.amountCents)
                web.await("document.querySelector('.totals').textContent.includes(${quote(money(incomeBefore + 4567))}) && document.querySelector('.totals').textContent.includes(${quote(money(expenseBefore + 1234))})")
                web.await("document.querySelector('.balance-number').textContent.replace(/\\s/g,'').includes(${quote(money(incomeBefore + 4567))})")

                web.run("document.querySelector('[data-filter=\"expense\"]').click()")
                web.await("document.querySelector('[data-record=\"${expense.id}\"]') && !document.querySelector('[data-record=\"${income.id}\"]')")
                web.run("document.querySelector('[data-filter=\"income\"]').click()")
                web.await("document.querySelector('[data-record=\"${income.id}\"]') && !document.querySelector('[data-record=\"${expense.id}\"]')")
                web.run("document.querySelector('[data-filter=\"expense\"]').click()")
                changeMonth(web,"2098-09")
                web.await("document.querySelector('#month h1').textContent==='2098年9月' && document.querySelectorAll('#entries [data-record]').length===0")
                changeMonth(web,"2098-08")

                web.run("document.querySelector('[data-filter=expense]').click();document.querySelector('[data-record=\"${expense.id}\"]').click()")
                web.await("document.querySelector('#record-panel').open && document.querySelector('#merchant').value===${quote(expenseName)}")
                web.run("document.querySelector('#amount').value='2.50';document.querySelector('#note').value='UI修改验证';document.querySelector('#import-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("document.querySelector('#ledger').classList.contains('mobile-active') && document.querySelector('[data-record=\"${expense.id}\"]').textContent.includes('2.50')")
                assertEquals(expense.uid, app.store.get(expense.id)!!.uid)
                assertEquals(250L, app.store.get(expense.id)!!.bill.amountCents)
                assertEquals("UI修改验证", app.store.get(expense.id)!!.note)
                web.run("document.querySelector('[data-record=\"${expense.id}\"]').click();document.querySelector('#delete-record').click()")
                web.await("document.querySelector('#sheet').open && document.querySelector('#confirm-delete')")
                web.run("document.querySelector('#confirm-delete').click()")
                web.await("document.querySelector('#ledger').classList.contains('mobile-active') && !document.querySelector('[data-record=\"${expense.id}\"]')")
                assertNull(app.store.get(expense.id))
                scenario.recreate()
                web.ready()
                web.run("document.querySelector('[data-filter=income]').click()")
                web.await("document.querySelector('[data-record=\"${income.id}\"]')")
            }
        } finally { cleanup(prefix) }
    }

    @Test fun formDraftAndNavigationSurviveRotationAndSettingsShowRealPermissionChannels() {
        val prefix = "UI-${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web = WebUiProbe(scenario)
            web.ready()
            web.run("newDraft();go('import');document.querySelector('#merchant').value=${quote(prefix)};document.querySelector('#amount').value='8.08';document.querySelector('#note').value='旋转草稿';document.querySelector('#direction').click();document.querySelector('#note').dispatchEvent(new Event('input',{bubbles:true}))")
            web.await("window.LedgerUI.getState().draft.note==='旋转草稿' && window.LedgerUI.getState().draft.direction==='INCOME'")
            scenario.recreate()
            web.ready()
            web.await("document.querySelector('#import').classList.contains('mobile-active') && document.querySelector('#merchant').value===${quote(prefix)} && document.querySelector('#amount').value==='8.08' && document.querySelector('#note').value==='旋转草稿' && document.querySelector('#direction').dataset.value==='INCOME'")
            assertFalse(app.store.all().any { it.bill.merchant == prefix })
            web.run("document.querySelector('#import .back').click();document.querySelector('#ledger .bottom-nav button:last-child').click()")
            web.await("document.querySelector('#settings').classList.contains('mobile-active') && !document.querySelector('#model-info') && !document.querySelector('#api-key')")
            for (channel in listOf("ocr")) {
                web.await("(()=>{const state=JSON.parse(LedgerNative.snapshot()).channels[${quote(channel)}],toggle=document.querySelector('[data-channel=\"$channel\"]');return typeof state.permitted==='boolean' && toggle.getAttribute('aria-checked')===String(state.enabled)})()")
            }
            web.await("!document.querySelector('[data-channel=sms]') && !document.querySelector('[data-channel=notification]') && document.querySelectorAll('[data-payment-app]').length===2")
            web.run("document.querySelector('.privacy-banner').click()")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            web.run("document.querySelector('#backup').click()")
            web.await("document.querySelector('#export-backup') && document.querySelector('#restore-backup')")
            web.await("Array.from(document.querySelectorAll('script[src],link[href]')).every(e=>new URL(e.src||e.href).origin===location.origin) && typeof LedgerNative.save==='function' && document.fonts.check('900 16px \"Font Awesome 6 Free\"')")
        }
    }

    @Test fun pendingRecordOpensRealReviewEditorAndRawSourceText() {
        val prefix = "UI-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val text = "$prefix 合成截图原文"
        val record = (app.store.ingest(Capture(Source.IMAGE, "image", text, now),
            ParsedBill(null, null, prefix, "其他", now, null, false, "缺少金额，请核对")) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                web.await("document.querySelector('[data-pending=\"${record.id}\"]')")
                web.run("document.querySelector('[data-pending=\"${record.id}\"]').click()")
                web.await("document.querySelector('#record-panel').open && document.querySelector('#merchant').value===${quote(prefix)} && document.querySelector('#review-reason').textContent==='缺少金额，请核对' && document.querySelector('#amount').value==='' && document.querySelector('#source-label').textContent==='截图识别'")
                web.run("document.querySelector('#raw-text-button').click()")
                web.await("document.querySelector('#sheet').open && document.querySelector('.raw-text').textContent===${quote(text)}")
                assertEquals(record, app.store.get(record.id))
            }
        } finally { app.store.delete(record.id) }
    }

    @Test fun remoteResultDeliveredToExistingWebViewKeepsLocalPreviewAfterRotation() {
        val fixture = createPaymentImage()
        var imported: LedgerRecord? = null
        try {
            app.importer.cancel()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                scenario.onActivity { app.importer.state.value = ImageImportState.Working }
                web.await("document.querySelector('.scanning').textContent==='AI 正在识别账单…' && document.querySelector('#import-form .primary').disabled")
                imported = publishSyntheticRemoteResult(fixture)
                assertImportedPayment(imported!!)
                awaitImportedEditor(web, imported!!)
                assertEquals(ImageImportState.Idle, app.importer.state.value)
                scenario.recreate()
                web.ready()
                awaitImportedEditor(web, imported!!)
            }
        } finally {
            imported?.let { app.store.delete(it.id) }
            app.importer.cancel()
            fixture.delete()
            app.changed()
        }
    }

    @Test fun remoteResultCompletedBeforeWebViewExistsIsConsumedOnceWhenPageBecomesReady() {
        val fixture = createPaymentImage()
        var imported: LedgerRecord? = null
        try {
            app.importer.cancel()
            imported = publishSyntheticRemoteResult(fixture)
            assertImportedPayment(imported!!)
            // This tests lifecycle delivery independently of the remote transport and credentials.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                awaitImportedEditor(web, imported!!)
                assertEquals(ImageImportState.Idle, app.importer.state.value)
                assertEquals(1, app.store.all().count { it.uid == imported!!.uid })
                scenario.recreate()
                web.ready()
                awaitImportedEditor(web, imported!!)
                assertEquals(1, app.store.all().count { it.uid == imported!!.uid })
            }
        } finally {
            imported?.let { app.store.delete(it.id) }
            app.importer.cancel()
            fixture.delete()
            app.changed()
        }
    }

    @Test fun remoteFailureStopsSpinnerAndRemainsVisibleWithoutCreatingBill() {
        app.importer.cancel()
        val before = app.store.all().map { it.uid }.toSet()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                scenario.onActivity { app.importer.state.value = ImageImportState.Working }
                web.await("document.querySelector('.scanner').classList.contains('working')")
                scenario.onActivity { app.importer.state.value = ImageImportState.Failed("识别服务暂时不可用，请重试") }
                web.await("document.querySelector('.scanning').textContent==='识别服务暂时不可用，请重试' && document.querySelector('.scanning').classList.contains('recognition-error') && !document.querySelector('.scanner').classList.contains('working') && !document.querySelector('#import-form .primary').disabled")
                assertEquals(before, app.store.all().map { it.uid }.toSet())
                assertEquals(ImageImportState.Idle, app.importer.state.value)
            }
        } finally { app.importer.cancel() }
    }

    private fun publishSyntheticRemoteResult(fixture: File): LedgerRecord {
        val now = System.currentTimeMillis()
        val order = "WEB-REMOTE-${UUID.randomUUID()}"
        val raw = "支付成功 支付金额 28.50元 交易单号 $order"
        val result = app.store.ingest(Capture(Source.IMAGE, "image", raw, now),
            ParsedBill(2850L, Direction.EXPENSE, "合成远端咖啡-$order", "餐饮", now, order, true, "", true),
            screenshot=fixture.readBytes(),requireReview=true)
        assertTrue("Synthetic payment must be saved: $result", result is IngestResult.Saved)
        // Only UI lifecycle tests use this private-field setup; URI/transport are covered separately.
        app.importer.javaClass.getDeclaredField("previewBytes").apply { isAccessible = true }
            .set(app.importer, fixture.readBytes())
        app.importer.state.value = ImageImportState.Finished(result)
        app.changed()
        return (result as IngestResult.Saved).record
    }
    @Test fun quotedHtmlMerchantStaysTextInLedgerEditorStatisticsAndPendingList() {
        val prefix = "UI-${UUID.randomUUID()}"
        val merchant = "$prefix \"'><img id=\"merchant-injection\" src=x onerror=\"window.merchantInjected=true\"> & 咖啡"
        var pending: LedgerRecord? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario)
                web.ready()
                changeMonth(web, "2098-08")
                fillAndSave(web, merchant, "4.56", income = false)
                val record = app.store.all().single { it.bill.merchant == merchant }
                web.await("document.querySelector('[data-record=\"${record.id}\"] .entry-title').textContent===${quote(merchant)} && document.querySelector('[data-record=\"${record.id}\"]').getAttribute('aria-label')===${quote("编辑$merchant，支出4.56元，${record.bill.category}，手动")} && !document.querySelector('#merchant-injection') && window.merchantInjected!==true")
                assertEquals(456L, app.store.get(record.id)!!.bill.amountCents)
                web.run("document.querySelector('[data-record=\"${record.id}\"]').click()")
                web.await("document.querySelector('#merchant').value===${quote(merchant)} && !document.querySelector('#merchant-injection') && window.merchantInjected!==true")
                web.run("document.querySelector('#import .back').click();document.querySelector('#ledger .bottom-nav button:nth-child(2)').click()")
                web.await("document.querySelector('#stats').classList.contains('mobile-active') && !document.querySelector('#merchant-injection') && window.merchantInjected!==true")
                web.run("go('ledger')")
                val now = LocalDateTime.of(2098, 8, 16, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                pending = (app.store.ingest(Capture(Source.IMAGE, "image", merchant, now),
                    ParsedBill(null, null, merchant, "其他", now, null, false, merchant)) as IngestResult.Saved).record
                app.changed()
                web.await("document.querySelector('[data-pending=\"${pending!!.id}\"]').textContent.includes(${quote(merchant)}) && !document.querySelector('#merchant-injection') && window.merchantInjected!==true")
            }
        } finally {
            pending?.let { app.store.delete(it.id) }
            cleanup(prefix)
        }
    }

    private fun createPaymentImage(): File {
        val bitmap = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f }
        val time = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(3)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val order = "9988${System.currentTimeMillis()}${UUID.randomUUID().toString().take(6).replace("-", "") }"
        val file = File(app.cacheDir, "web-import-${UUID.randomUUID()}.png")
        try {
            listOf("支付宝", "支付成功", "支付金额 28.50元", "商户 示例咖啡店", "交易时间 $time", "交易单号 $order")
                .forEachIndexed { index, line -> canvas.drawText(line, 80f, 150f + index * 170f, paint) }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun assertImportedPayment(record: LedgerRecord) {
        assertEquals(Source.IMAGE, record.capture.source)
        assertEquals(2850L, record.bill.amountCents)
        assertEquals(Direction.EXPENSE, record.bill.direction)
        assertEquals(RecordState.REVIEW, record.state)
        assertTrue(record.capture.text, record.capture.text.contains("支付"))
        assertTrue(record.capture.text, record.capture.text.contains("28.50"))
        assertTrue(app.importer.previewBytes?.isNotEmpty() == true)
    }

    private fun awaitImportedEditor(web: WebUiProbe, record: LedgerRecord) {
        web.await("document.querySelector('#record-panel').open && window.LedgerUI.getState().draft.id===${record.id} && document.querySelector('#amount').value==='28.5' && document.querySelector('#source-label').textContent==='截图识别'")
        web.await("(()=>{const image=document.querySelector('#capture-gallery .history-image img');return !document.querySelector('#capture-gallery').hidden && image && image.complete && image.naturalWidth>0})()")
        web.await("!document.querySelector('#import-form .primary').disabled && !document.querySelector('.scanner').classList.contains('working')")
    }

    private fun fillAndSave(web: WebUiProbe, merchant: String, amount: String, income: Boolean) {
        web.run("newDraft();go('import');document.querySelector('#merchant').value=${quote(merchant)};document.querySelector('#amount').value=${quote(amount)};document.querySelector('#occurred-at').value='2098-08-15T12:34';" +
            (if (income) "document.querySelector('#direction').click();" else "") +
            "document.querySelector('#import-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
        web.await("document.querySelector('#ledger').classList.contains('mobile-active') && Array.from(document.querySelectorAll('.entry-title')).some(e=>e.textContent===${quote(merchant)})")
    }

    private fun changeMonth(web: WebUiProbe, month: String) {
        web.run("document.querySelector('#month').click();document.querySelector('#month-picker').value='$month';document.querySelector('#month-apply').click()")
        web.await("window.LedgerUI.getState().month===${quote(month)}")
    }

    private fun cleanup(prefix: String) {
        app.store.all().filter { it.bill.merchant.startsWith(prefix) }.forEach { app.store.delete(it.id) }
        app.changed()
    }

    private fun quote(text: String) = JSONObject.quote(text)
    private fun money(cents: Long) = String.format(java.util.Locale.US, "%,.2f", cents / 100.0)
}
