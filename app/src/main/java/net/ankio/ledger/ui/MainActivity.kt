package net.ankio.ledger.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import android.view.View
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.core.text.htmlEncode
import kotlinx.coroutines.*
import net.ankio.ledger.ocr.MerchantIconEncoding
import net.ankio.ledger.BuildConfig
import net.ankio.ledger.R
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.ImageImportState
import net.ankio.ledger.capture.CaptureRuntime
import net.ankio.ledger.capture.ScreenCaptureService
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.data.*
import net.ankio.ledger.ledger
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/** Bundled HTML is the presentation layer; all ledger operations stay in the native local store. */
class MainActivity : Activity() {
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var webView: WebView
        private set
    lateinit var pageHost: FrameLayout
        private set
    internal var modelPage: EmbeddedModelPage? = null
    private var csvPreviewSheet: MintDialog? = null
    private var ready = false
    private var pendingCaptureRepair=false
    private var pendingRecordId:Long?=null
    private var pendingChannel: ChannelSettings.Channel? = null
    internal val permissionSetup by lazy { PermissionSetupController(this) }
    internal val billReports by lazy { BillReportController(this) }
    @Volatile private var savedPageState: String? = null
    @Volatile private var previewBytes: ByteArray? = null
    private val localOrigin = "https://appassets.androidplatform.net"

    override fun attachBaseContext(newBase: Context) {
        // Native WebView date/time dialogs belong to this Chinese interface,
        // independently of the device's chosen system language.
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.NativeControlsTheme)
        super.onCreate(savedInstanceState)
        billReports.restore()
        pendingCaptureRepair=intent.action==net.ankio.ledger.capture.CaptureHealth.REPAIR
        pendingRecordId=savedInstanceState?.getLong("pendingRecordId",0)?.takeIf { it>0 }
            ?:intent.takeIf { it.action==OPEN_RECORD }?.getLongExtra("recordId",0)?.takeIf { it>0 }
        window.setDecorFitsSystemWindows(false)
        savedPageState = savedInstanceState?.getString("pageState")
        pendingChannel = savedInstanceState?.getString("pendingChannel")?.let { key -> ChannelSettings.Channel.entries.firstOrNull { it.key == key } }
        savedInstanceState?.getString("permissionSetup")?.let { json -> runCatching { permissionSetup.restoreState(JSONObject(json)) } }
        previewBytes = ledger.importer.previewBytes
        val outer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(248,250,252)) }
        pageHost = outer
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(248,250,252))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.blockNetworkLoads = true
            settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceAtLeast(100)
            addJavascriptInterface(LedgerWebBridge(this@MainActivity), "LedgerNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    val uri = request?.url
                    if (uri?.scheme != "https" || uri.host != "appassets.androidplatform.net") return missing()
                    return try {
                        if (uri.path == "/capture.png") {
                            val bytes = previewBytes ?: return missing()
                            WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
                        } else if (uri.path?.startsWith("/task-capture/") == true) {
                            val id = Regex("^/task-capture/([1-9][0-9]*)\\.jpg$").matchEntire(uri.path!!)
                                ?.groupValues?.get(1)?.toLongOrNull() ?: return missing()
                            val bytes = ledger.tasks.image(id) ?: return missing()
                            WebResourceResponse("image/jpeg",null,ByteArrayInputStream(bytes))
                        } else if (uri.path?.startsWith("/merchant-icon/") == true) {
                            val key = Regex("^/merchant-icon/([a-f0-9]{64})\\.img$").matchEntire(uri.path!!)
                                ?.groupValues?.get(1) ?: return missing()
                            val bytes = ledger.store.merchantIconAsset(key) ?: return missing()
                            val mime = MerchantIconEncoding.mime(bytes) ?: return missing()
                            WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
                        } else if (uri.path?.startsWith("/record-history/") == true) {
                            val match = Regex("^/record-history/([1-9][0-9]*)/([1-9][0-9]*)\\.jpg$").matchEntire(uri.path!!)
                                ?: return missing()
                            val id = match.groupValues[1].toLongOrNull() ?: return missing()
                            val sequence = match.groupValues[2].toIntOrNull() ?: return missing()
                            val bytes = ledger.store.historyScreenshot(id, sequence) ?: return missing()
                            WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
                        } else if (uri.path?.startsWith("/record-capture/") == true) {
                            val id = Regex("^/record-capture/([1-9][0-9]*)\\.jpg$").matchEntire(uri.path!!)
                                ?.groupValues?.get(1)?.toLongOrNull() ?: return missing()
                            val bytes = ledger.store.screenshot(id) ?: return missing()
                            WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
                        } else {
                            val path = uri.path?.removePrefix("/") ?: return missing()
                            if (path.contains("..") || !path.startsWith("web/")) return missing()
                            val mime = when {
                                path.endsWith(".html") -> "text/html"
                                path.endsWith(".css") -> "text/css"
                                path.endsWith(".js") -> "application/javascript"
                                path.endsWith(".woff2") -> "font/woff2"
                                path.endsWith(".png") -> "image/png"
                                else -> "application/octet-stream"
                            }
                            WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript")) "UTF-8" else null, assets.open(path))
                        }
                    } catch (_: Exception) { missing() }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready = true
                    dispatch("fontScale", resources.configuration.fontScale.toString())
                    savedPageState?.let { state -> dispatch("restoreState", state) }
                    savedPageState = null
                    refresh()
                    renderImportState(ledger.importer.state.value)
                    permissionSetup.resume()
                    resumeCaptureRepair()
                    openPendingRecord()
                }
            }
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        outer.addView(webView, FrameLayout.LayoutParams(-1, -1))
        outer.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            val keyboard=if(modelPage==null)(insets.getInsets(WindowInsets.Type.ime()).bottom-safe.bottom).coerceAtLeast(0) else 0
            val webParams=webView.layoutParams as FrameLayout.LayoutParams
            if(webParams.bottomMargin!=keyboard) { webParams.bottomMargin=keyboard;webView.layoutParams=webParams }
            insets
        }
        setContentView(outer)
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        outer.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        requestFeedbackPermission()
        webView.loadUrl("$localOrigin/web/index.html")
        uiScope.launch { ledger.revision.collect { refresh() } }
        uiScope.launch {
            ledger.importer.state.collect { state -> renderImportState(state) }
        }
    }

    private fun requestFeedbackPermission() {
        if(Build.VERSION.SDK_INT>=36 && ledger.channels.captureEnabled() &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val prompts=getSharedPreferences("notification_feedback",MODE_PRIVATE)
            if(!prompts.getBoolean("requested",false)) {
                prompts.edit().putBoolean("requested",true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),17)
            }
        }
    }

    private fun renderImportState(state: ImageImportState) {
        if (!ready) return
        when (state) {
            ImageImportState.Working -> {
                dispatch("importStatus", JSONObject().put("working",true).put("taskId",ledger.importer.activeTask).toString())
                previewBytes = ledger.importer.previewBytes
                if (previewBytes != null) dispatch("preview", JSONObject().put("url", "$localOrigin/capture.png?v=${System.nanoTime()}").toString())
            }
            is ImageImportState.Finished -> {
                ledger.importer.state.value = ImageImportState.Idle
                refresh()
                when (val result = state.result) {
                    is IngestResult.Saved -> {
                        val persisted=ledger.store.get(result.record.id)
                        if(persisted==null) {
                            dispatch("importStatus", "{\"working\":false}")
                            message("账单已移除")
                            return
                        }
                        previewBytes = ledger.importer.previewBytes
                        if (previewBytes != null) dispatch("preview", JSONObject().put("url", "$localOrigin/capture.png?v=${System.nanoTime()}").toString())
                        dispatch("openRecord", persisted.id.toString())
                        val amount=persisted.bill.amountCents
                        if(persisted.state==RecordState.POSTED && amount!=null) {
                            ledger.captureHealth.successful(persisted.id,"image")
                            message("已记账 ¥${net.ankio.ledger.core.Money.display(amount)}")
                        } else message("金额待核对")
                    }
                    IngestResult.DuplicateSkipped -> { dispatch("importStatus", "{\"working\":false}"); message("识别完成，请查看账本") }
                    IngestResult.Ignored -> { dispatch("importStatus", "{\"working\":false}"); message("没有识别到账单文字，可换图或手动记账") }
                    is IngestResult.Skipped -> { dispatch("importStatus", "{\"working\":false}");message(result.message) }
                }
            }
            is ImageImportState.Failed -> {
                ledger.importer.state.value = ImageImportState.Idle
                dispatch("importError", JSONObject.quote(state.message))
                message(state.message)
            }
            ImageImportState.Idle -> Unit
        }
    }

    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    fun refresh() {
        // Capture continues in the background; rebuild the WebView only when it is visible.
        // onResume always requests a fresh snapshot, so no ledger updates are lost.
        if (!captureRestoreResumed) return
        if(ready && !permissionSetup.active)requestFeedbackPermission()
        if (previewBytes !== ledger.importer.previewBytes) {
            previewBytes = ledger.importer.previewBytes
            if (previewBytes != null && ledger.importer.state.value == ImageImportState.Working) dispatch("preview", JSONObject().put("url", "$localOrigin/capture.png?v=${System.nanoTime()}").toString())
        }
        if (ready && !isDestroyed) dispatch("refresh", "null")
    }
    fun rememberPage(json: String) { savedPageState = json }
    internal fun dispatch(event: String, json: String) {
        if (ready && !isDestroyed) webView.evaluateJavascript("window.LedgerUI&&window.LedgerUI.event(${JSONObject.quote(event)},$json)", null)
    }
    internal fun showSetup(state:JSONObject) { if(ready)dispatch("permissionSetup",state.toString()) }
    internal fun setupRequestPermission(channel:ChannelSettings.Channel) { requestChannelPermission(channel);pendingChannel=null }
    internal fun setupEnableChannel(channel:ChannelSettings.Channel) { enableChannel(channel) }
    fun message(value: String) { if (ready) dispatch("message", JSONObject.quote(value)) else Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun handleBack() {
        modelPage?.let { it.back(); return }
        if (!ready) { finish(); return }
        webView.evaluateJavascript("window.LedgerUI.back()") { handled -> if (handled != "true") finish() }
    }
    @Deprecated("Native activity back navigation")
    override fun onBackPressed() = handleBack()
    private var captureRestoreEpoch=0
    private var captureRestoreResumed=false
    private fun restoreEnabledCapture(epoch:Int) {
        ledger.captureHealth.suspendForMissingPermission()
        if(captureRestoreResumed && epoch==captureRestoreEpoch && ledger.captureHealth.runtimeRequested())CaptureRuntime.refreshHealth()
        if(!captureRestoreResumed || epoch!=captureRestoreEpoch || !ledger.captureHealth.runtimeRequested() || CaptureRuntime.running)return
        if(ledger.captureHealth.runtimeRequested()) {
            runCatching { CaptureRuntime.refreshHealth() }
                .onFailure { ledger.channels.status("识别服务尚未启动，请检查后台设置后重试") }
        }
    }
    override fun onResume() {
        super.onResume()
        // Restore a previously enabled listener after an update/process stop. No new consent.
        captureRestoreResumed=true;restoreEnabledCapture(++captureRestoreEpoch)
        if(ledger.executionLog.active())ledger.executionLog.event("system","permissions","activity_resumed",net.ankio.ledger.data.RecognitionDiagnostics.permissions(ledger))
        pendingChannel?.let { channel ->
            pendingChannel = null
            if (channel == ChannelSettings.Channel.OCR) showBackgroundSetup(true)
            else if (ledger.channels.permitted(channel)) enableChannel(channel)
            else { ledger.channels.set(channel, false); ledger.changed(); message("尚未获得授权，功能未开启") }
        }
        if (::webView.isInitialized) { webView.onResume(); refresh();permissionSetup.resume();resumeCaptureRepair() }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(intent.action==net.ankio.ledger.capture.CaptureHealth.REPAIR) { pendingCaptureRepair=true;resumeCaptureRepair() }
        if(intent.action==OPEN_RECORD) { pendingRecordId=intent.getLongExtra("recordId",0).takeIf { it>0 };openPendingRecord() }
    }
    private fun openPendingRecord() {
        if(!ready)return
        val id=pendingRecordId?:return
        pendingRecordId=null
        // A one-shot result link must not reopen after a rotation or process recreation.
        intent.removeExtra("recordId")
        refresh();dispatch("openRecord",id.toString())
    }
    private fun resumeCaptureRepair() {
        if(!ready || !pendingCaptureRepair)return
        pendingCaptureRepair=false;repairCapture()
    }
    internal fun repairCapture() {
        if(!ledger.channels.captureEnabled()) {
            if(ledger.captureHealth.repairShake())permissionSetup.start(ChannelSettings.Channel.OCR)
            else dispatch("navigate",JSONObject.quote("settings"))
            return
        }
        restoreEnabledCapture(captureRestoreEpoch)
        if(ledger.channels.enabled(ChannelSettings.Channel.OCR))permissionSetup.start(ChannelSettings.Channel.OCR)
        else permissionSetup.startAutomatic()
    }
    override fun onPause() {
        captureRestoreResumed=false;++captureRestoreEpoch
        if (::webView.isInitialized) {
            webView.evaluateJavascript("JSON.stringify(window.LedgerUI&&window.LedgerUI.getState())") { value ->
                if (value != "null") savedPageState = runCatching { org.json.JSONArray("[$value]").getString(0) }.getOrNull()
            }
            webView.onPause()
        }
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingRecordId?.let { outState.putLong("pendingRecordId",it) }
        savedPageState?.let { outState.putString("pageState", it) }
        pendingChannel?.let { outState.putString("pendingChannel", it.key) }
        permissionSetup.saveState()?.let { outState.putString("permissionSetup",it.toString()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { csvPreviewSheet?.dismiss();csvPreviewSheet=null;modelPage?.dismiss(false); uiScope.cancel(); webView.removeJavascriptInterface("LedgerNative"); webView.destroy(); super.onDestroy() }

    fun importImage() {
        if (ledger.importer.state.value == ImageImportState.Working) { message("正在识别，请稍候"); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, IMAGE_REQUEST)
    }
    fun exportBackup() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        type="application/zip"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE,"花有谱完整备份-${java.time.LocalDate.now()}.zip")
    }, EXPORT_REQUEST) }
    fun exportBills(format:String) {
        val selected=LedgerCsvExport.Format.fromKey(format)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type="text/csv";addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE,"花有谱-${if(selected==LedgerCsvExport.Format.QIANJI)"钱迹模板" else "全部账单"}-${java.time.LocalDate.now()}.csv")
        },if(selected==LedgerCsvExport.Format.QIANJI)CSV_QIANJI_REQUEST else CSV_GENERAL_REQUEST)
    }
    fun importBills() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type="*/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/csv","text/comma-separated-values","application/csv","application/octet-stream"))
        addCategory(Intent.CATEGORY_OPENABLE)
    }, CSV_IMPORT_REQUEST) }

    private fun previewBills(document:LedgerCsvImport.Document,preview:LedgerCsvImport.Preview) {
        if(isDestroyed || isFinishing)return
        csvPreviewSheet?.dismiss()
        val sheet=MintDialog(this,"导入账单预览",preview.format)
        csvPreviewSheet=sheet
        sheet.dialog.setOnDismissListener { if(csvPreviewSheet===sheet)csvPreviewSheet=null }
        sheet.content.addView(sheet.text("共 ${preview.total} 笔 · 可导入 ${preview.ready} 笔",20,MintDialog.INK,true))
        sheet.content.addView(sheet.text("重复 ${preview.duplicates} 笔 · 无效 ${preview.errors.size} 笔",14,MintDialog.MUTED).apply { setPadding(0,sheet.dp(8),0,sheet.dp(8)) })
        for(record in preview.samples)sheet.content.addView(sheet.text("${java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(java.util.Date(record.bill.occurredAt))}  ${record.bill.merchant.take(20)}  ${if(record.bill.direction==net.ankio.ledger.core.Direction.INCOME)"收入" else "支出"} ¥${net.ankio.ledger.core.Money.display(record.bill.amountCents!!)}",13,MintDialog.INK))
        if(preview.errors.isNotEmpty()) {
            sheet.label("以下无效行将跳过")
            preview.errors.take(4).forEach { sheet.content.addView(sheet.text("第 ${it.row} 行：${it.message}",13,MintDialog.MUTED)) }
            if(preview.errors.size>4)sheet.content.addView(sheet.text("另有 ${preview.errors.size-4} 行无效",13,MintDialog.MUTED))
        }
        sheet.content.addView(sheet.text("按表头自动匹配字段；重复账单跳过，已有账单不覆盖。账户和图片不从 CSV 导入，识别开关保持当前设置。钱迹模板需为最终人民币金额；零金额退款请用花有谱通用 CSV。",12,MintDialog.MUTED).apply { setPadding(0,sheet.dp(16),0,0) })
        if(preview.ready>0)sheet.action("确认导入 ${preview.ready} 笔",true) {
            sheet.dismiss()
            uiScope.launch {
                dispatch("fileOperation",JSONObject.quote("正在导入账单"))
                try {
                    val result=withContext(Dispatchers.IO) { ensureActive();LedgerCsvImport.apply(ledger.store,document) }
                    ledger.changed();message("已导入 ${result.imported} 笔，跳过 ${result.duplicates+result.invalid} 笔")
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { message("导入失败，账单未修改") }
                finally { dispatch("fileOperation","null") }
            }
        }
        sheet.action(if(preview.ready>0)"取消" else "关闭") { sheet.dismiss() }
        sheet.show()
    }
    private fun exportDiagnostics() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        type="application/json";addCategory(Intent.CATEGORY_OPENABLE)
        putExtra(Intent.EXTRA_TITLE,"花有谱识别诊断-${java.time.LocalDate.now()}.json")
    }, DIAGNOSTICS_REQUEST) }
    fun restoreBackup() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type="*/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/zip","application/json","application/octet-stream","application/x-zip-compressed"));addCategory(Intent.CATEGORY_OPENABLE)
    }, RESTORE_REQUEST) }
    @Deprecated("Native document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        when(requestCode) {
            CSV_IMPORT_REQUEST -> uiScope.launch {
                dispatch("fileOperation",JSONObject.quote("正在检查 CSV"))
                try {
                    val result=withContext(Dispatchers.IO) {
                        val document=(contentResolver.openInputStream(uri) ?: error("无法读取文件")).use { LedgerCsvImport.read(it) }
                        document to LedgerCsvImport.preview(ledger.store,document)
                    }
                    previewBills(result.first,result.second)
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { message(error.message?.take(70) ?: "CSV 无法读取，请检查格式") }
                finally { dispatch("fileOperation","null") }
            }
            DIAGNOSTICS_REQUEST -> uiScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json=net.ankio.ledger.data.RecognitionDiagnostics.encode(ledger)
                        (contentResolver.openOutputStream(uri,"wt")?:error("无法写入")).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    };message("诊断已导出，含限时调试采集的脱敏交易消息，不含截图和密钥")
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { message("诊断导出失败，请检查文件位置") }
            }
            IMAGE_REQUEST -> {
                dispatch("navigate", JSONObject.quote("import"))
                ledger.importer.start(uri)
            }
            EXPORT_REQUEST,CSV_GENERAL_REQUEST,CSV_QIANJI_REQUEST -> uiScope.launch {
                dispatch("fileOperation",JSONObject.quote(if(requestCode==EXPORT_REQUEST)"正在生成完整备份" else "正在导出全部账单"))
                try {
                    val result=withContext(Dispatchers.IO) {
                        val temporary=java.io.File.createTempFile("ledger-export-",".tmp",cacheDir)
                        try {
                            val count=temporary.outputStream().use { output ->
                                if(requestCode==EXPORT_REQUEST) { LedgerArchive.write(ledger.store,ledger.channels,output);null }
                                else LedgerCsvExport.write(ledger.store.all(),if(requestCode==CSV_QIANJI_REQUEST)LedgerCsvExport.Format.QIANJI else LedgerCsvExport.Format.GENERAL,output)
                            }
                            ensureActive()
                            (contentResolver.openOutputStream(uri,"wt") ?: error("无法写入")).use { output -> temporary.inputStream().use { it.copyTo(output) } }
                            count
                        } finally { temporary.delete() }
                    }; message(if(result==null)"完整备份已导出" else "已导出 $result 笔账单")
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { message("导出失败，请检查文件位置或可用空间") }
                finally { dispatch("fileOperation", "null") }
            }
            RESTORE_REQUEST -> MintDialog.confirm(this,"恢复本地备份？","将合并备份中的账单、账户、分类及识别截图。已有账单不会覆盖；采集保持暂停，系统授权需要重新确认。旧版备份可能没有截图。","恢复") { uiScope.launch {
                    dispatch("fileOperation",JSONObject.quote("正在校验并恢复备份"))
                    try {
                        val count=withContext(Dispatchers.IO) {
                            val imported=(contentResolver.openInputStream(uri) ?: error("无法读取")).use { input ->
                                LedgerArchive.read(input,cacheDir).use { prepared -> LedgerArchive.restore(ledger.store,prepared) }
                            }
                            // Restoring data must never silently start collection or restore cloud consent.
                            ChannelSettings.Channel.entries.forEach { ledger.channels.set(it,false) }
                            imported
                        }; CaptureRuntime.stopSafely(); ledger.changed(); message("已恢复 $count 笔账单，采集保持暂停，可按需开启")
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:Exception) { message("恢复失败，备份无效或已损坏，现有账单未修改") }
                    finally { dispatch("fileOperation","null") }
                }}
        }
    }
    fun changeChannel(key: String, enabled: Boolean) {
        val channel = ChannelSettings.Channel.entries.firstOrNull { it.key == key && it == ChannelSettings.Channel.OCR } ?: return
        if (!enabled) { revokeChannel(key); return }
        permissionSetup.start(channel)
    }
    private fun requestChannelPermission(channel: ChannelSettings.Channel) {
        pendingChannel = channel
        try {
            when (channel) {
                ChannelSettings.Channel.PAGE, ChannelSettings.Channel.SMS, ChannelSettings.Channel.NOTIFICATION -> return
                ChannelSettings.Channel.OCR -> {
                    val component = net.ankio.ledger.capture.captureServiceComponent(this)
                    val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
                    try { startActivity(detail) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }

            }
        } catch (_: Exception) { pendingChannel = null; message("无法打开授权页面，请在系统设置中授权") }
    }
    private fun enableChannel(channel: ChannelSettings.Channel) {
        if (!ledger.channels.permitted(channel)) return
        if (channel.capture && permissionSetup.backgroundNeedsReview()) {
            permissionSetup.start(channel); return
        }
        ledger.channels.set(channel, true)
        if (channel.capture) {
            try { CaptureRuntime.refreshHealth() }
            catch (_: Exception) { ledger.channels.set(channel, false); message("无法启动识别服务，请重新打开应用后开启") }
        }
        ledger.changed()
    }
    private fun pauseChannel(channel: ChannelSettings.Channel) {
        if (pendingChannel == channel) pendingChannel = null
        ledger.channels.set(channel, false)
        if (channel.capture) {
            ScreenCaptureService.current?.cancelChannel(channel)
            if(ledger.channels.captureEnabled())CaptureRuntime.refreshHealth() else CaptureRuntime.stopSafely()
        }
        ledger.changed()
    }
    fun revokeChannel(key: String) {
        val channel = ChannelSettings.Channel.entries.firstOrNull { it.key == key && it == ChannelSettings.Channel.OCR } ?: return
        val name=when(channel) {
            ChannelSettings.Channel.OCR -> "截图识别授权"
            else -> return
        }
        val permitted=ledger.channels.permitted(channel)
        val direct=channel.capture && ScreenCaptureService.current!=null
        val detail=if(!permitted) "将停止此渠道的记账。已有本地账单会保留。"
            else if(direct) "将关闭摇一摇和支付自动识别，并撤销共用的无障碍授权。已有本地账单会保留。\n\n部分银行会限制具有截图能力的无障碍服务。关闭后可重新打开银行；若仍提示，请检查系统录屏和其他应用。再次识别需由你重新开启系统授权。"
            else "将先停止此渠道，再打开系统设置，请关闭对应授权。已有本地账单会保留。"
        MintDialog.confirm(this,"关闭${name}？",detail,
            if(!permitted) "确认关闭" else if(direct) "关闭并撤销授权" else "关闭并前往撤销") {
            if(channel==ChannelSettings.Channel.OCR) {
                ledger.channels.setAutomaticEnabled(false)
                ledger.captureQueue.cancelAll()
            }
            pauseChannel(channel)
            if(!permitted) { message("已关闭$name"); return@confirm }
            if(direct) {
                ScreenCaptureService.current?.revoke()
                message("已关闭摇一摇，正在撤销无障碍授权")
            } else try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                message("请在系统页面关闭对应授权")
            } catch (_: Exception) { message("请在系统设置中关闭对应权限；花有谱已停止此渠道采集") }
        }
    }
    fun showShakeStatus() {
        CaptureRuntime.refreshHealth()
        val state=ledger.shakeRuntime.snapshot()
        val running=state.optBoolean("running")&&state.optBoolean("accessibilityConnected")
        val sheet=MintDialog(this,"摇一摇运行状态")
        sheet.content.addView(sheet.text(ledger.shakeRuntime.statusMessage(),20,if(running)MintDialog.GREEN else MintDialog.INK,true))
        sheet.content.addView(sheet.text("打开单笔账单详情，摇一摇即可补录。",14,MintDialog.MUTED).apply { setPadding(0,sheet.dp(10),0,sheet.dp(16)) })
        val recent=ledger.captureHealth.snapshot()
        val successfulAt=recent.optLong("lastSuccessfulAt",0)
        val recentRecord=recent.optLong("lastSuccessfulRecordId",0).takeIf { it>0 }?.let { ledger.store.get(it) }
        if(successfulAt>0 && recentRecord?.state==RecordState.POSTED)sheet.content.addView(sheet.text(
            "最近成功识别  ·  "+java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT).format(java.util.Date(successfulAt)),13,MintDialog.MUTED).apply { setPadding(0,sheet.dp(10),0,0) })
        sheet.action(if(running)"检查后台运行设置" else "检查识别与运行设置",true) { sheet.dismiss();showBackgroundSetup(false) }
        if(ledger.channels.permitted(ChannelSettings.Channel.OCR))sheet.action("关闭并撤销识别授权") { sheet.dismiss();revokeChannel("ocr") }
        sheet.show()
    }
    fun configureWechatAuto() {
        val enabled=ledger.channels.wechatAutoConsented()
        val sheet=MintDialog(this,"微信支付自动识别",if(enabled)"已开启 · 与摇一摇共用无障碍授权" else "微信没发通知，也能识别前台支付结果")
        sheet.content.addView(sheet.text("开启后，在微信付款或转账成功页、已完成交易详情页停留片刻，应用会检查交易页面；可读取文字时先核对状态和单笔金额，微信转账结果页没有提供文字时改用截图识别。匹配后弹出识别进度，全部在本机识别，截图不上传。有效结果直接记账，可再编辑或删除。\n\n不读取聊天内容，不点击、不支付；输入密码、待支付或交易失败的页面不采集。截图和账本保存在本机。\n\n此功能需要同时开启摇一摇及无障碍。后台代扣如果既没有通知、也没有显示支付结果页面，仍无法实时感知；可之后打开单笔详情识别。暂未适配的结果页请摇一摇补录。",14,MintDialog.MUTED))
        sheet.action(if(enabled)"关闭微信自动识别" else "同意并开启微信自动识别",true) {
            ledger.channels.setWechatAuto(!enabled)
            ScreenCaptureService.current?.cancelRecognition()
            ledger.changed();sheet.dismiss()
            if(!enabled && !ledger.channels.captureEnabled())changeChannel("ocr",true)
            else { ScreenCaptureService.current?.checkPaymentPages();message(if(enabled)"已关闭微信自动识别" else "已开启，请停留在微信支付成功页片刻") }
        }
        sheet.action("取消") { sheet.dismiss() };sheet.show()
    }
    internal fun showBackgroundSetup(enableAfter:Boolean) { permissionSetup.openShake(enableAfter) }
    fun privacy(origin:android.graphics.RectF?=null,originRadius:Float?=null) {
        if(modelPage?.isClosing==true)modelPage?.dismiss(false)
        if(modelPage!=null)return
        val sheet=MintDialog(this,"数据与隐私","截图与账本保存在本机")
        sheet.content.addView(sheet.text("识别在本机完成，截图不上传。\n\n只检查已授权的交易页面，不读取短信或其他应用通知，不代你操作付款。\n\n自动识别与摇一摇可分别暂停。\n\n卸载或清除数据会删除账本，请先导出备份。",14,MintDialog.INK).apply { setPadding(0,0,0,sheet.dp(14)) })
        val privacyDetails=sheet.text("账单、账户、分类、备注及新增、修改、删除操作均在本机完成和保存。应用不自动同步完整账本。卸载或清除应用数据会删除本机账本，请主动导出备份。\n\n自动记账、摇一摇和截图导入均在本机识别，无需配置，截图不上传。主动摇动手机或选择图片时取图识别；另外同意并开启支付自动识别后，只会检查你主动选择的应用中的前台付款成功页，先从可读取的节点确认付款成功和单笔金额，再核对当前页面后截图识别。出现输入框、密码或验证提示，或无法确认页面时直接跳过，不用截图来猜测是否成功。摇一摇也不能绕过这层检查。仅自动记录支出，不等待对方确认收款；收入请手动记账、导入截图或主动摇一摇记录。识别成功后直接记账，可在详情中编辑或删除。不会代你点击、收款或支付。识别开始即保存本地任务；识别中、成功、失败均可查看。识别截图会压缩后保存在应用私有目录，用于详情核对。账单与任务共享同一张图片。已关联账单的付款及退款截图随识别历史保留，按第 1 次、第 2 次顺序核对。未关联的成功任务截图默认保留30天，失败截图7天，并按${ScreenshotStore.MAX_BYTES / (1024 * 1024)}MiB容量预算清理；仍被账单历史或待识别任务引用的图片继续保留。清理截图不删除账单、识别历史和商户图标，缺失图片会明确提示。删除最后一个引用后释放图片。完整 ZIP 备份包含账单、识别历史、截图、商户图标、账户及分类，相同图片只保存一次；支持恢复旧版 JSON。账单 CSV 可导出全部月份的已入账收支，通用格式保留商户与订单号，也提供钱迹官方模板格式。失败识别任务不作为账单导出，也不进入完整备份。\n\n此版本不再读取短信和其他应用通知。没有显示前台结果页的后台代扣可能漏记，可打开交易详情后摇一摇补录。暂停自动识别与关闭摇一摇分别生效；撤销无障碍授权会同时停止两种截图功能。",14,MintDialog.MUTED).apply { visibility=View.GONE }
        sheet.content.addView(sheet.button("查看完整说明").apply {
            setOnClickListener {
                val expanded=privacyDetails.visibility!=View.VISIBLE
                privacyDetails.visibility=if(expanded)View.VISIBLE else View.GONE
                text=if(expanded)"收起完整说明" else "查看完整说明"
            }
        })
        sheet.content.addView(privacyDetails)
        var afterDismiss:(()->Unit)?=null
        sheet.fixedActions(
            sheet.button("管理截图授权").apply { setOnClickListener { afterDismiss={ changeChannel("ocr",false) };sheet.dismiss() } },
            sheet.button("调试与诊断").apply { setOnClickListener { afterDismiss={ showDiagnostics() };sheet.dismiss() } }
        )
        sheet.onPageDismiss { if(!isFinishing&&!isDestroyed)afterDismiss?.invoke() }
        sheet.showEmbedded(this,origin,originRadius,"privacy-page")
    }
    internal fun showDiagnostics() {
        val snapshot=ledger.executionLog.snapshot()
        val active=snapshot.optBoolean("active")
        val sheet=MintDialog(this,"调试与诊断",if(active)"限时调试正在记录 · 最多 15 分钟" else "调试记录未开启")
        sheet.content.addView(sheet.text("记录应用选择、功能开关、服务连接、支付页面候选匹配或跳过原因、截图和识别任务的执行状态。小米自启动、后台锁为手动确认项，无法由应用可靠检测。\n\n仅保存技术状态，不采集普通聊天、验证码或截图。日志只在本机保存，最多 300 条，15 分钟后停止采集，超过一天的内容在下次打开或导出时清理。",14,MintDialog.MUTED))
        sheet.label("使用步骤")
        sheet.content.addView(sheet.text("1 · 开启 15 分钟调试\n2 · 在微信 / 支付宝完成一笔交易，或复现摇一摇问题\n3 · 回到这里停止记录，导出诊断文件",14,MintDialog.INK))
        sheet.label("当前已记录 ${snapshot.getJSONArray("events").length()} 条执行事件")
        if(active)sheet.action("停止调试记录",true) { ledger.executionLog.stop();sheet.dismiss();showDiagnostics() }
        else sheet.action("开启 15 分钟调试",true) {
            ledger.executionLog.start()
            ledger.executionLog.event("system","permissions","session_start",net.ankio.ledger.data.RecognitionDiagnostics.permissions(ledger))
            sheet.dismiss();message("调试已开启，请复现问题后导出诊断")
        }
        sheet.action("导出完整诊断文件") { sheet.dismiss();exportDiagnostics() }
        sheet.action("清空调试日志") { ledger.executionLog.clear();sheet.dismiss();message("调试已停止，日志已清空") }
        sheet.action("关闭") { sheet.dismiss() };sheet.show()
    }
    fun showAbout(origin:android.graphics.RectF?=null,originRadius:Float?=null) {
        if(modelPage?.isClosing==true)modelPage?.dismiss(false)
        if(modelPage!=null)return
        val sheet=MintDialog(this,"花有谱","少点手动，收支有谱 · ${BuildConfig.VERSION_NAME}")
        sheet.label("为什么做这个 App")
        sheet.content.addView(sheet.text("平时想把账记清楚，却总会遇到这些小麻烦：付款时忘了记，月底想补又记不清；账单散在不同应用里，手动一笔笔填也很费时间。\n\n所以做了花有谱，希望把日常记账变得简单一点。能识别的收支少填几步，需要补充的账单随时记录，让每个月的钱花在哪里更清楚。\n\n无需配置，识别在本机完成，截图和账本保存在本机。",15,MintDialog.INK))
        var afterDismiss:(()->Unit)?=null
        sheet.action("许可与第三方声明") { afterDismiss={ showLicenses() };sheet.dismiss() }
        sheet.onPageDismiss { if(!isFinishing&&!isDestroyed)afterDismiss?.invoke() }
        sheet.showEmbedded(this,origin,originRadius,"about-page")
    }
    private fun showLicenses() {
        val sheet=MintDialog(this,"许可与第三方声明")
        sheet.content.addView(sheet.text("花有谱项目代码以 GPL-3.0 开源，允许使用、修改与再分发。分发衍生程序时，须遵守 GPL 的对应源码与许可要求。\n\n内置识别使用 ${net.ankio.ledger.BuildConfig.LOCAL_OCR_LABEL}，另使用 AndroidX、Kotlin 等基础库；这些第三方内容保留各自许可与署名。\n\nFont Awesome：代码 MIT、字体 SIL OFL 1.1、图标 CC BY 4.0。银行标识权利属于各银行。来源与授权范围见 LICENSE、NOTICE.md。",14,MintDialog.MUTED))
        var afterDismiss:(()->Unit)?=null
        sheet.action("查看项目开源协议") { afterDismiss={ showOfflineNotices(false,true) };sheet.dismiss() }
        sheet.action("查看第三方依赖声明") { afterDismiss={ showOfflineNotices(false) };sheet.dismiss() }
        sheet.action("字体与图标声明") { afterDismiss={ showOfflineNotices(true) };sheet.dismiss() }
        sheet.action("返回关于花有谱",true) { afterDismiss={ showAbout() };sheet.dismiss() }
        sheet.onPageDismiss { if(!isFinishing&&!isDestroyed)afterDismiss?.invoke() }
        sheet.showEmbedded(this,null,pageTag="licenses-page")
    }
    private fun showOfflineNotices(fonts:Boolean,projectLicense:Boolean=false) {
        val sheet=MintDialog(this,if(projectLicense)"项目开源协议 · GPL-3.0" else if(fonts)"字体与图标声明" else "第三方依赖声明")
        val viewer=WebView(this).apply {
            settings.javaScriptEnabled=false
            settings.allowContentAccess=false
            settings.blockNetworkLoads=true
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean =
                    !Regex("file:///android_asset/licenses/[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:html|txt)").matches(request.url.toString())
            }
            if(fonts)loadDataWithBaseURL(null,"<meta name='viewport' content='width=device-width,initial-scale=1'><pre style='white-space:pre-wrap;overflow-wrap:anywhere'>"+
                assets.open("web/vendor/LICENSE.txt").bufferedReader().use { it.readText() }.htmlEncode()+"</pre>","text/html","utf-8",null)
            else loadUrl("file:///android_asset/licenses/${if(projectLicense)"project-GPL-3.0.txt" else "index.html"}")
        }
        sheet.content.addView(viewer,android.widget.LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.55f).toInt()))
        var returning=false
        sheet.action("返回许可说明",true) { returning=true;sheet.dismiss() }
        sheet.onPageDismiss { viewer.destroy();if(returning&&!isFinishing&&!isDestroyed)showLicenses() }
        sheet.showEmbedded(this,null,pageTag="third-party-notices-page")
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults); refresh()
        if(permissionSetup.active)permissionSetup.resume()
    }
    companion object { const val OPEN_RECORD="net.ankio.ledger.OPEN_RECORD";const val IMAGE_REQUEST=10; const val EXPORT_REQUEST=11; const val RESTORE_REQUEST=12; const val DIAGNOSTICS_REQUEST=13;const val CSV_GENERAL_REQUEST=14;const val CSV_QIANJI_REQUEST=15;const val CSV_IMPORT_REQUEST=16 }
}
