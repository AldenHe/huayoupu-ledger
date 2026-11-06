package net.ankio.ledger.ui

import android.webkit.JavascriptInterface
import net.ankio.ledger.BuildConfig
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.data.BillTags
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.data.LedgerAccount
import net.ankio.ledger.data.AccountType
import net.ankio.ledger.data.AccountBanks
import net.ankio.ledger.data.AccountTransfers
import net.ankio.ledger.data.AccountTransfer
import net.ankio.ledger.data.AccountDetailReadView
import net.ankio.ledger.data.CustomCategory
import net.ankio.ledger.data.LedgerReadView
import net.ankio.ledger.data.paymentProvider
import net.ankio.ledger.ledger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.launch

/** Exposed only to the packaged ledger page; native storage remains the source of truth. */
class LedgerWebBridge(private val activity: MainActivity) {
    private val app get() = activity.ledger

    @JavascriptInterface
    fun snapshot(): String = buildSnapshot().toString()

    @JavascriptInterface
    fun setMonthlyBudget(amount: String): String = try {
        val cents = if (amount.isEmpty()) 0L else Money.parseCents(amount)
            ?.takeIf { it > 0 } ?: throw IllegalArgumentException("请输入大于零、最多两位小数的月限额")
        app.monthlyBudget.set(cents)
        app.changed()
        JSONObject().put("ok", true).put("monthlyBudgetCents", cents).toString()
    } catch (error: Exception) {
        failure(error.message ?: "月限额保存失败，请重试").toString()
    }

    /** Production list entry: exactly one bounded page, with a revision for continuation. */
    @JavascriptInterface
    fun snapshotFirst(month:String,search:String,direction:String,known:String):String = synchronized(app.store) {
        val revision=app.revision.value.toString()
        snapshotViewData(month,search,200,direction,known).put("dataRevision",revision).toString()
    }

    /** A mutation invalidates the cursor rather than mixing two different ledger views. */
    @JavascriptInterface
    fun recordPage(month:String,search:String,direction:String,beforeAt:Long,beforeId:Long,expectedRevision:String):String = try {
        synchronized(app.store) {
            val revision=app.revision.value.toString()
            if(expectedRevision!=revision)JSONObject().put("ok",true).put("reset",true).toString()
            else {
                val view=LedgerReadView(app.store).records(month,search,direction=direction,beforeAt=beforeAt,beforeId=beforeId)
                    .put("summary",JSONObject()).put("month",month)
                val result=buildSnapshot(view).put("dataRevision",revision)
                if(app.revision.value.toString()!=revision)JSONObject().put("ok",true).put("reset",true).toString()
                else result.toString()
            }
        }
    } catch(_:Exception) { failure("更多账单读取失败，请重试").toString() }

    @JavascriptInterface
    fun snapshotView(month:String,search:String,limit:Int,direction:String,known:String):String =
        snapshotViewData(month,search,limit,direction,known).toString()

    private fun snapshotViewData(month:String,search:String,limit:Int,direction:String,known:String):JSONObject = try {
        val read=LedgerReadView(app.store)
        val key="${app.revision.value}|$month|$search|$limit|$direction|${System.currentTimeMillis()/30_000}|${app.channels.permitted(ChannelSettings.Channel.OCR)}|${net.ankio.ledger.capture.CaptureRuntime.running}|${net.ankio.ledger.capture.ScreenCaptureService.current!=null}"
        if(known==key)JSONObject().put("ok",true).put("unchanged",true)
        else {
            val view=read.records(month,search,limit=200,direction=direction)
            // Each page is bounded, even when the caller requests more history.
            val pages=limit.coerceIn(200,1_000_000)/200
            for(page in 1 until pages) {
                if(!view.getBoolean("hasMore"))break
                val next=read.records(month,search,page*200,200,direction)
                val rows=next.getJSONArray("records");repeat(rows.length()){view.getJSONArray("records").put(rows.getJSONObject(it))}
                view.put("hasMore",next.getBoolean("hasMore"))
            }
            view.put("summary",read.summary(month)).put("month",month)
            buildSnapshot(view).put("viewKey",key)
        }
    } catch(_:Exception) { failure("账本读取失败，请重试") }

    private fun buildSnapshot(view:JSONObject?=null):JSONObject = try {
        val records = JSONArray()
        val requestedIds=view?.getJSONArray("records")?.let { rows -> (0 until rows.length()).mapTo(mutableSetOf()) { rows.getJSONObject(it).getLong("id") } }
        val availableOwners = if(requestedIds==null)app.screenshots.availableOwners() else app.store.availableScreenshotOwners(requestedIds)
        val screenshotIds = app.store.screenshotIds(availableOwners,requestedIds)
        val historyRevisions = app.store.historyRevisions(availableOwners,requestedIds)
        val merchantIconKeys = if(view==null)app.store.merchantIconKeys() else emptyMap()
        val merchantIconRevisions=app.store.merchantIconRevisions(requestedIds)
        val range=view?.optString("month")?.let { LedgerReadView(app.store).window(it) }
        val tasks=app.tasks.snapshot(200,range?.start,range?.end)
        val pageRecordIds=mutableSetOf<Long>()
        val iconStatuses=mutableMapOf<Long,String>()
        val iconDetails=mutableMapOf<Long,String>()
        val taskMetadata=if(requestedIds==null)tasks else app.tasks.metadataForRecords(requestedIds)
        for(index in 0 until taskMetadata.length()) {
            val task=taskMetadata.getJSONObject(index)
            val id=task.optLong("recordId",0)
            if(id>0 && (task.optString("origin").startsWith("payment_auto:") || task.optJSONObject("diagnostic")?.optString("trigger").orEmpty().let { it.startsWith("page_auto") || it.startsWith("wechat_auto") || it.startsWith("payment_auto") }))pageRecordIds.add(id)
            val status=task.optJSONObject("diagnostic")?.optString("icon").orEmpty()
            if(id>0 && status.isNotBlank()) {
                iconStatuses.putIfAbsent(id,status)
                iconDetails.putIfAbsent(id,task.optJSONObject("diagnostic")?.optString("icon_details").orEmpty())
            }
        }
        if(view!=null) {
            val rows=view.getJSONArray("records")
            for(index in 0 until rows.length()) {
                val row=rows.getJSONObject(index);val id=row.getLong("id")
                row.put("captureMode",if(id in pageRecordIds)"page" else "")
                    .put("hasScreenshot",id in screenshotIds)
                    .put("historyRevision",historyRevisions[id]?:"")
                    .put("merchantIconRevision",merchantIconRevisions[row.optString("merchantIconKey")]?:0)
                    .put("iconDetails",iconDetails[id].orEmpty())
                if(!row.getBoolean("hasMerchantIcon"))row.put("iconStatus",iconStatuses[id].orEmpty())
                records.put(row)
            }
        } else app.store.all().forEach { record ->
            records.put(JSONObject().apply {
                put("id", record.id)
                put("uid", record.uid)
                put("merchant", record.bill.merchant)
                put("category", record.bill.category)
                put("note", record.note);put("tags",JSONArray(BillTags.encode(record.tags)));put("requiresReimbursement",record.requiresReimbursement);put("accountUid",record.accountUid?:JSONObject.NULL)
                put("amountCents", record.bill.amountCents ?: JSONObject.NULL)
                put("direction", record.bill.direction?.name ?: JSONObject.NULL)
                put("occurredAt", record.bill.occurredAt)
                put("state", record.state.name)
                put("source", record.capture.source.name)
                put("paymentProvider", paymentProvider(record.capture) ?: JSONObject.NULL)
                put("captureMode",if(record.id in pageRecordIds)"page" else "")
                put("reason", record.reason)
                put("historyRevision", historyRevisions[record.id] ?: "")
                put("hasScreenshot", record.id in screenshotIds)
                put("hasMerchantIcon", record.id in merchantIconKeys)
                put("merchantIconKey", merchantIconKeys[record.id] ?: JSONObject.NULL)
                put("merchantIconRevision",merchantIconKeys[record.id]?.let { merchantIconRevisions[it] }?:0)
                put("iconStatus",if(record.id in merchantIconKeys)"cached" else iconStatuses[record.id].orEmpty())
                put("iconDetails",iconDetails[record.id].orEmpty())
                put("duplicateId", record.duplicateId ?: JSONObject.NULL)
                put("refundCandidateCount",if(record.bill.refund!=null && record.state==RecordState.REVIEW)
                    app.store.refundCandidates(record.id).size else 0)
                put("refund", record.bill.refund?.let { refund ->
                    JSONObject().put("provider", refund.provider)
                        .put("originalAmountCents", refund.originalAmountCents)
                        .put("refundedCents", refund.refundedCents).put("mode", refund.mode)
                        .put("refundId", refund.refundId ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
            })
        }
        val channels = JSONObject()
        listOf(ChannelSettings.Channel.OCR).forEach { channel ->
            val state = JSONObject()
                .put("enabled", app.channels.enabled(channel))
                .put("permitted", app.channels.permitted(channel))
            channels.put(channel.key, state)
        }
        val accounts = JSONArray()
        val defaultAccountUid=app.store.defaultAccount()?.uid
        val channelDefaults=app.store.channelDefaultAccounts()
        app.store.accounts().forEach { account ->
            accounts.put(JSONObject().put("id", account.id).put("uid", account.uid)
                .put("name", account.name).put("description", account.description)
                .put("amountCents", account.amountCents).put("kind", account.kind).put("type", account.type.name)
                .put("includedInAssets",account.includedInAssets)
                .put("isDefault",defaultAccountUid==account.uid)
                .put("isWechatDefault",channelDefaults["wechat"]==account.uid)
                .put("isAlipayDefault",channelDefaults["alipay"]==account.uid))
        }
        val custom = JSONObject()
        val categories = app.store.customCategories()
        Direction.entries.forEach { direction ->
            val names = JSONArray()
            categories.filter { it.direction == direction }.forEach { names.put(it.name) }
            custom.put(if (direction == Direction.EXPENSE) "expense" else "income", names)
        }
        val applications=JSONArray()
        val selectedPackages=app.channels.selectedAutoPackages()
        PaymentApps.entries.forEach { item ->
            val installed=runCatching { activity.packageManager.getApplicationInfo(item.packageName,0) }.isSuccess
            applications.put(JSONObject().put("packageName",item.packageName).put("label",item.label)
                .put("selected",item.packageName in selectedPackages).put("installed",installed)
                .put("coverage",if(item.packageName=="com.taobao.taobao")"testing" else "adapted"))
        }
        JSONObject().put("ok", true).put("version", BuildConfig.VERSION_NAME)
            .put("ledgerView",view?.let { JSONObject().put("hasMore",it.getBoolean("hasMore")).put("summary",it.getJSONObject("summary"))
                .put("nextCursor",it.opt("nextCursor")?:JSONObject.NULL) }?:JSONObject.NULL)
            .put("pendingDeletion",app.store.pendingDeletion()?.let { JSONObject().put("id",it.id).put("expiresAt",it.expiresAt) }?:JSONObject.NULL)
            .put("records", records).put("recognitionTasks",tasks).put("channels", channels)
            .put("accounts", accounts).put("customCategories", custom)
            .put("bankOptions",JSONArray().apply { AccountBanks.entries.forEach {
                put(JSONObject().put("kind",it.kind).put("label",it.label).put("icon",it.icon))
            } })
            .put("captureHealth",app.captureHealth.snapshot())
            .put("automationRunning", net.ankio.ledger.capture.CaptureRuntime.running && net.ankio.ledger.capture.ScreenCaptureService.current != null)
            .put("wechatAutoConsented",app.channels.wechatAutoConsented())
            .put("automatic",JSONObject().put("enabled",app.channels.automaticEnabled()).put("applications",applications))
            .put("screenshotStorage",app.screenshots.stats())
            .put("monthlyBudgetCents",app.monthlyBudget.cents())
            .put("status", app.channels.lastStatus())
    } catch (_: Exception) {
        // An explicit error avoids making a failed read look like an empty ledger.
        failure("账本读取失败，请稍后重试").put("version", BuildConfig.VERSION_NAME)
    }

    @JavascriptInterface
    fun recordDetails(id:Long):String = try {
        val record=requireNotNull(app.store.get(id)) { "账单已不存在" }
        val ids=setOf(id);val owners=app.store.availableScreenshotOwners(ids)
        val iconKey=app.store.merchantIconKeys(ids)[id]
        val task=app.tasks.metadataForRecords(ids).optJSONObject(0)
        JSONObject().put("ok",true).put("record",JSONObject().put("id",id).put("uid",record.uid)
            .put("hasScreenshot",app.store.screenshotIds(owners,ids).contains(id)).put("historyRevision",app.store.historyRevisions(owners,ids)[id]?:"")
            .put("hasMerchantIcon",iconKey!=null).put("merchantIconKey",iconKey?:JSONObject.NULL)
            .put("merchantIconRevision",iconKey?.let { app.store.merchantIconAsset(it)?.contentHashCode() }?:0)
            .put("captureMode",if(task?.optString("origin")?.startsWith("payment_auto:")==true)"page" else "")
            .put("refundCandidateCount",if(record.bill.refund!=null&&record.state==RecordState.REVIEW)app.store.refundCandidates(id).size else 0)
            .put("merchant",record.bill.merchant).put("category",record.bill.category)
            .put("requiresReimbursement",record.requiresReimbursement).put("accountUid",record.accountUid?:JSONObject.NULL)
            .put("tags",JSONArray(BillTags.encode(record.tags))).put("note",record.note).put("amountCents",record.bill.amountCents?:JSONObject.NULL).put("direction",record.bill.direction?.name?:JSONObject.NULL)
            .put("occurredAt",record.bill.occurredAt).put("state",record.state.name).put("source",record.capture.source.name)
            .put("paymentProvider",paymentProvider(record.capture)?:JSONObject.NULL)
            .put("refund",record.bill.refund?.let { JSONObject().put("provider",it.provider).put("originalAmountCents",it.originalAmountCents).put("refundedCents",it.refundedCents).put("mode",it.mode).put("refundId",it.refundId?:JSONObject.NULL) }?:JSONObject.NULL))
            .put("rawText",record.capture.text).put("history",JSONArray().apply {
            app.store.captureHistory(id).forEach { item ->
                put(JSONObject().put("sequence",item.sequence).put("at",item.at).put("kind",item.kind)
                    .put("amountCents",item.amountCents?:JSONObject.NULL).put("refundedCents",item.refundedCents)
                    .put("rawText",item.rawText).put("hasScreenshot",item.hasScreenshot)
                    .put("screenshotUrl",if(item.hasScreenshot)"/record-history/$id/${item.sequence}.jpg" else JSONObject.NULL))
            }
        }).toString()
    } catch(_:Exception) { failure("账单详情读取失败，请重试").toString() }

    @JavascriptInterface
    fun billLabels():String = try {
        JSONObject().put("ok",true).put("tags",JSONArray(net.ankio.ledger.data.BillReports(app.store).labels())).toString()
    } catch(_:Exception) { failure("标签读取失败，请重试").toString() }

    @JavascriptInterface
    fun generateBillReport(criteria:String,requestId:String)=onMain { activity.billReports.generate(criteria,requestId) }

    @JavascriptInterface
    fun billReportPage(token:String,offset:Int):String=activity.billReports.page(token,offset)

    @JavascriptInterface
    fun exportBillReport(token:String,format:String)=onMain { activity.billReports.export(token,format) }

    @JavascriptInterface
    fun reportReduceMotion():Boolean=android.provider.Settings.Global.getFloat(activity.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f

    @JavascriptInterface
    fun exportBills(format:String) {
        if(format !in setOf("general","qianji"))return
        onMain { activity.exportBills(format) }
    }

    @JavascriptInterface
    fun importBills()=onMain { activity.importBills() }

    @JavascriptInterface
    fun categoryRecords(month:String,category:String,direction:String,offset:Int):String = try {
        checkInput(category.length<=1024,"分类无效")
        LedgerReadView(app.store).records(month,offset=offset,direction=direction,category=category).put("ok",true).toString()
    } catch(_:Exception) { failure("明细读取失败").toString() }

    @JavascriptInterface
    fun statistics(month:String,reimbursement:String):String = try {
        synchronized(app.store) {
            JSONObject().put("ok",true).put("summary",LedgerReadView(app.store).summary(month,reimbursement))
                .put("revision",app.revision.value.toString()).toString()
        }
    } catch(_:Exception) { failure("统计读取失败，请重试").toString() }

    @JavascriptInterface
    fun statisticsRecords(month:String,category:String,direction:String,offset:Int,reimbursement:String):String = try {
        checkInput(category.length<=1024,"分类无效")
        LedgerReadView(app.store).records(month,offset=offset,direction=direction,category=category,reimbursement=reimbursement,statistical=true).put("ok",true).toString()
    } catch(_:Exception) { failure("明细读取失败，请重试").toString() }

    @JavascriptInterface
    fun save(json: String): String = try {
        checkInput(json.length <= MAX_INPUT_LENGTH, "账单数据过大")
        val input = JSONObject(json)
        val id = integer(input, "id", 0, MAX_SAFE_INTEGER)
        val taskId=if(input.has("recognitionTaskId")) integer(input,"recognitionTaskId",0,MAX_SAFE_INTEGER) else 0L
        if(taskId>0) checkInput(app.tasks.status(taskId) in setOf("FAILED","NO_BILL","CANCELLED","DUPLICATE"),"请等待识别完成，或重新打开识别记录")
        val amount = string(input, "amount", 32).trim()
        checkInput(DECIMAL.matches(amount), "请输入有效金额，最多两位小数")
        val cents = Money.parseCents(amount)?.takeIf { it >= 0 }
            ?: throw InvalidInput("请输入有效金额，最多两位小数")
        val direction = when (string(input, "direction", 16)) {
            Direction.EXPENSE.name -> Direction.EXPENSE
            Direction.INCOME.name -> Direction.INCOME
            else -> throw InvalidInput("请选择收入或支出")
        }
        val merchant = string(input, "merchant", 1024).trim().ifBlank { "未填写商户" }
        val category = string(input, "category", 1024).trim().ifBlank { "其他" }
        val occurredAt = integer(input, "occurredAt", 1, MAX_TIME)
        val note = string(input, "note", 500).trim()
        val confirmed = if (input.has("confirmDuplicate")) {
            input.get("confirmDuplicate") as? Boolean ?: throw InvalidInput("重复确认参数无效")
        } else false
        val result = synchronized(app.store) {
            val original = if (id == 0L) null else (app.store.get(id)
                ?: throw InvalidInput("账单已不存在，请刷新后重试"))
            checkInput(original?.bill?.refund == null || original.state == RecordState.POSTED,
                "退款需先选择原账单，不能另记为一笔支出")
            val refund = original?.bill?.refund
            val validatedRefund = original != null && original.state == RecordState.POSTED && refund != null &&
                refund.originalAmountCents > 0 && refund.refundedCents in 0..refund.originalAmountCents &&
                original.bill.direction == Direction.EXPENSE &&
                original.bill.amountCents == refund.originalAmountCents - refund.refundedCents
            checkInput(cents > 0 || validatedRefund, "请输入大于零的有效金额")
            if (refund != null) {
                val existing = requireNotNull(original)
                checkInput(validatedRefund && cents == existing.bill.amountCents && direction == existing.bill.direction,
                    "退款后的实付金额由识别记录计算，请勿手动更改金额或收支方向")
                checkInput(merchant == existing.bill.merchant && occurredAt == existing.bill.occurredAt,
                    "退款账单保留原商户和交易时间，可修改分类和备注")
            }
            if (original?.duplicateId != null && !confirmed) {
                failure("这笔账单可能重复，请核对后确认保存").put("duplicateRequired", true)
            } else {
                val now = System.currentTimeMillis()
                val base = original ?: LedgerRecord(
                    capture = Capture(Source.MANUAL, "manual", "", now),
                    bill = ParsedBill(null, null, "", "其他", now, null, true, ""),
                    state = RecordState.POSTED)
                val saved = app.store.saveEdited(base.copy(
                    bill = base.bill.copy(amountCents = cents, direction = direction,
                        merchant = merchant, category = category, occurredAt = occurredAt,
                        reliable = true, reason = "", timeExplicit = if(original!=null && occurredAt==base.bill.occurredAt)base.bill.timeExplicit else true),
                    note = note, tags = if(input.has("tags"))BillTags.decode(input.getJSONArray("tags").toString()) else base.tags,
                    requiresReimbursement=if(input.has("requiresReimbursement")) {
                        val value=input.get("requiresReimbursement");checkInput(value is Boolean,"报销属性无效");value as Boolean
                    } else base.requiresReimbursement),
                    selectedAccountUid=if(input.has("accountUid")&&!input.isNull("accountUid"))string(input,"accountUid",36) else null,
                    accountSelectionSpecified=input.has("accountUid"))
                if(taskId>0) {
                    app.tasks.image(taskId)?.let { app.store.attachScreenshot(saved.id,it) }
                    app.tasks.update(taskId,"SUCCEEDED","已手动补录账单",saved.id)
                }
                app.changed()
                JSONObject().put("ok", true).put("id", saved.id)
            }
        }
        result.toString()
    } catch (error: InvalidInput) {
        failure(error.message ?: "账单数据无效").toString()
    } catch (_: JSONException) {
        failure("账单数据格式错误").toString()
    } catch (error: IllegalArgumentException) {
        failure(error.message ?: "账单数据无效").toString()
    } catch (_: Exception) {
        failure("保存失败，内容已保留，请重试").toString()
    }

    @JavascriptInterface
    fun refundCandidates(id: Long): String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER, "账单编号无效")
        val candidates = JSONArray()
        app.store.refundCandidates(id).forEach { record ->
            candidates.put(JSONObject().put("id", record.id).put("merchant", record.bill.merchant)
                .put("occurredAt", record.bill.occurredAt).put("amountCents", record.bill.amountCents ?: JSONObject.NULL)
                .put("originalAmountCents", record.bill.refund?.originalAmountCents ?: record.bill.amountCents ?: JSONObject.NULL))
        }
        JSONObject().put("ok", true).put("candidates", candidates).toString()
    } catch (error: Exception) {
        failure(error.message ?: "无法读取原账单，请重试").toString()
    }

    @JavascriptInterface
    fun confirmRefund(pendingId: Long, targetId: Long): String = try {
        checkInput(pendingId in 1..MAX_SAFE_INTEGER && targetId in 1..MAX_SAFE_INTEGER, "账单编号无效")
        val saved = app.store.confirmRefund(pendingId, targetId)
        app.tasks.reassignRecord(pendingId, saved.record.id)
        app.changed()
        JSONObject().put("ok", true).put("id", saved.record.id).put("updated", saved.updated).toString()
    } catch (error: Exception) {
        failure(error.message ?: "退款关联失败，请重新核对原账单").toString()
    }

    @JavascriptInterface
    fun delete(id: Long): String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER, "账单编号无效")
        synchronized(app.store) {
            checkInput(app.store.get(id) != null, "账单已不存在，请刷新后重试")
            checkInput(app.store.softDelete(id),"账单已不存在，请刷新后重试")
            app.changed()
        }
        val deletion=app.store.pendingDeletion()
        app.scope.launch {
            kotlinx.coroutines.delay(net.ankio.ledger.data.LedgerStore.UNDO_WINDOW_MS+150)
            app.store.purgeExpiredDeletes().forEach(app.tasks::deleteForRecord)
            app.changed()
        }
        JSONObject().put("ok", true).put("id", id).put("expiresAt",deletion?.expiresAt?:0).toString()
    } catch (error: InvalidInput) {
        failure(error.message ?: "账单编号无效").toString()
    } catch (_: Exception) {
        failure("删除失败，请稍后重试").toString()
    }

    @JavascriptInterface
    fun undoDelete(id:Long):String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER,"账单编号无效")
        checkInput(app.store.undoDelete(id),"撤销时间已过")
        app.changed();JSONObject().put("ok",true).toString()
    } catch(error:Exception) { failure(error.message?:"撤销失败，请重试").toString() }

    @JavascriptInterface
    fun deleteTask(id:Long):String=try {
        checkInput(id in 1..MAX_SAFE_INTEGER,"识别记录编号无效")
        checkInput(app.tasks.exists(id),"识别记录已不存在")
        checkInput(app.tasks.status(id) !in setOf("CAPTURING","RECOGNIZING"),"正在识别，请稍后再删除")
        app.tasks.delete(id);app.changed();JSONObject().put("ok",true).toString()
    } catch(error:Exception) { failure((error as? InvalidInput)?.message?:"删除失败，请重试").toString() }

    @JavascriptInterface
    fun saveAccount(json: String): String = try {
        checkInput(json.length <= MAX_INPUT_LENGTH, "账户数据过大")
        val input = JSONObject(json)
        listOf("isDefault","isWechatDefault","isAlipayDefault","includedInAssets").forEach { key ->
            if(input.has(key))checkInput(input.get(key) is Boolean,"默认账户设置无效")
        }
        val id = integer(input, "id", 0, MAX_SAFE_INTEGER)
        val enteredName = string(input, "name", 40).trim()
        val description = if (input.has("description")) string(input, "description", 80).trim() else ""
        val amount = string(input, "amount", 32).trim()
        checkInput(amount.isNotEmpty(), "请输入余额，可填 0")
        checkInput(Regex("-?\\d+(?:\\.\\d{1,2})?").matches(amount), "余额最多两位小数")
        val magnitude=Money.parseCents(amount.removePrefix("-"))
        val cents = magnitude?.let { if(amount.startsWith("-"))-it else it } ?: throw InvalidInput("账户余额无效或过大")
        val kind = string(input, "kind", 16)
        checkInput(kind in LedgerAccount.KINDS, "账户图标类型无效")
        val name=enteredName.ifBlank { AccountBanks.entries.firstOrNull { it.kind==kind }?.label ?: when(kind) {
            "bank"->"银行卡";"alipay"->"支付宝";"wechat"->"微信";"cash"->"现金";else->"其他账户"
        } }
        val type = when (string(input, "type", 16)) {
            "ASSET" -> AccountType.ASSET
            "LIABILITY" -> AccountType.LIABILITY
            else -> throw InvalidInput("请选择资产或负债")
        }
        val saved = synchronized(app.store) {
            val original = if (id == 0L) null else (app.store.getAccount(id)
                ?: throw InvalidInput("账户已不存在，请刷新后重试"))
            val db=app.store.writableDatabase;db.beginTransaction()
            try {
                val account=app.store.saveAccount((original ?: LedgerAccount(name = name, amountCents = cents)).copy(
                    name = name, description = description, amountCents = cents, kind = kind, type = type,
                    includedInAssets=if(input.has("includedInAssets"))input.getBoolean("includedInAssets") else original?.includedInAssets?:true))
                if(input.has("isDefault")) {
                    if(input.getBoolean("isDefault"))app.store.setDefaultAccount(account.uid)
                }
                val channelDefaults=app.store.channelDefaultAccounts()
                listOf("wechat" to "isWechatDefault","alipay" to "isAlipayDefault").forEach { (channel,key) ->
                    if(input.has(key)) {
                        if(input.getBoolean(key))app.store.setChannelDefaultAccount(channel,account.uid)
                        else if(channelDefaults[channel]==account.uid)app.store.setChannelDefaultAccount(channel,null)
                    }
                }
                db.setTransactionSuccessful();account
            } finally { db.endTransaction() }
        }
        app.changed()
        JSONObject().put("ok", true).put("id", saved.id).toString()
    } catch (error: InvalidInput) {
        failure(error.message ?: "账户数据无效").toString()
    } catch (_: JSONException) {
        failure("账户数据格式错误").toString()
    } catch (error: IllegalArgumentException) {
        failure(error.message?:"账户数据无效").toString()
    } catch (_: Exception) {
        failure("账户保存失败，内容已保留，请重试").toString()
    }

    @JavascriptInterface
    fun setDefaultAccount(uid:String):String = try {
        checkInput(uid.length==36,"账户标识无效")
        app.store.setDefaultAccount(uid);app.changed();JSONObject().put("ok",true).toString()
    } catch(error:Exception) { failure(error.message?:"默认账户设置失败").toString() }

    @JavascriptInterface
    fun setAccountPreference(channel:String,uid:String):String=try {
        checkInput(uid.isEmpty()||uid.length==36,"账户标识无效")
        synchronized(app.store) {
            when(channel) {
                "common"->{checkInput(uid.isNotEmpty(),"请选择通用默认账户");app.store.setDefaultAccount(uid)}
                "wechat","alipay"->app.store.setChannelDefaultAccount(channel,uid.takeIf { it.isNotEmpty() })
                else->throw InvalidInput("支付渠道无效")
            }
        }
        app.changed();JSONObject().put("ok",true).toString()
    }catch(error:Exception){failure(error.message?:"默认账户设置失败").toString()}

    @JavascriptInterface
    fun accountDetails(uid:String,month:String,filter:String,offset:Int):String=try {
        checkInput(uid.length==36,"账户标识无效")
        AccountDetailReadView(app.store).query(uid,month,filter,offset).put("ok",true).toString()
    }catch(error:Exception){failure(error.message?:"账户明细读取失败，请重试").toString()}

    private fun transferJson(value:AccountTransfer)=JSONObject().put("uid",value.uid).put("fromUid",value.fromUid)
        .put("toUid",value.toUid).put("amountCents",value.amountCents).put("occurredAt",value.occurredAt).put("note",value.note)

    @JavascriptInterface
    fun transferDetails(uid:String):String=try {
        checkInput(uid.length==36,"转账标识无效")
        JSONObject().put("ok",true).put("transfer",transferJson(requireNotNull(AccountTransfers(app.store).get(uid)){"转账记录已不存在"})).toString()
    }catch(error:Exception){failure(error.message?:"转账读取失败").toString()}

    @JavascriptInterface
    fun saveTransfer(json:String):String=try {
        checkInput(json.length<=MAX_INPUT_LENGTH,"转账数据过大")
        val input=JSONObject(json)
        val cents=Money.parseCents(string(input,"amount",32).trim())?:throw InvalidInput("请输入有效转账金额，最多两位小数")
        val value=AccountTransfer(uid=if(input.isNull("uid"))java.util.UUID.randomUUID().toString() else string(input,"uid",36),
            fromUid=string(input,"fromUid",36),toUid=string(input,"toUid",36),amountCents=cents,
            occurredAt=integer(input,"occurredAt",1,MAX_TIME),note=string(input,"note",500).trim())
        val saved=AccountTransfers(app.store).save(value);app.changed()
        JSONObject().put("ok",true).put("transfer",transferJson(saved)).toString()
    }catch(error:Exception){failure(error.message?:"转账保存失败，内容已保留").toString()}

    @JavascriptInterface
    fun deleteTransfer(uid:String):String=try {
        checkInput(uid.length==36,"转账标识无效")
        AccountTransfers(app.store).delete(uid);app.changed();JSONObject().put("ok",true).toString()
    }catch(error:Exception){failure(error.message?:"转账删除失败").toString()}

    @JavascriptInterface
    fun deleteAccount(id: Long): String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER, "账户编号无效")
        synchronized(app.store) {
            checkInput(app.store.getAccount(id) != null, "账户已不存在，请刷新后重试")
            app.store.deleteAccount(id)
        }
        app.changed()
        JSONObject().put("ok", true).put("id", id).toString()
    } catch (error: InvalidInput) {
        failure(error.message ?: "账户编号无效").toString()
    } catch (error: IllegalArgumentException) {
        failure(error.message ?: "账户删除失败").toString()
    } catch (_: Exception) {
        failure("账户删除失败，请稍后重试").toString()
    }

    @JavascriptInterface
    fun addCategory(name: String, direction: String): String = try {
        val category = categoryInput(name, direction)
        val added = app.store.addCategory(category)
        app.changed()
        JSONObject().put("ok", true).put("added", added).toString()
    } catch (error: InvalidInput) {
        failure(error.message ?: "分类数据无效").toString()
    } catch (_: Exception) {
        failure("分类保存失败，请重试").toString()
    }

    @JavascriptInterface
    fun deleteCategory(name: String, direction: String): String = try {
        app.store.deleteCategory(categoryInput(name, direction))
        app.changed()
        JSONObject().put("ok", true).toString()
    } catch (error: InvalidInput) {
        failure(error.message ?: "分类数据无效").toString()
    } catch (_: Exception) {
        failure("分类删除失败，请重试").toString()
    }

    private fun categoryInput(name: String, direction: String): CustomCategory {
        checkInput(name.length <= 40 && name.isNotBlank(), "分类名称需为 1 至 40 个字符")
        val parsed = when (direction) {
            "expense" -> Direction.EXPENSE
            "income" -> Direction.INCOME
            else -> throw InvalidInput("分类收支类型无效")
        }
        return CustomCategory(name.trim(), parsed)
    }

    @JavascriptInterface
    fun importImage() = onMain { activity.importImage() }

    @JavascriptInterface
    fun setChannel(key: String, enabled: Boolean) {
        if (ChannelSettings.Channel.entries.none { it.key == key }) return
        onMain { activity.changeChannel(key, enabled) }
    }
    @JavascriptInterface
    fun configureWechatAuto()=onMain { activity.configureWechatAuto() }

    @JavascriptInterface
    fun setAutoApplication(packageName:String,enabled:Boolean):String = try {
        checkInput(PaymentApps.entries.any { it.packageName==packageName },"暂不支持此应用")
        checkInput(!enabled || runCatching { activity.packageManager.getApplicationInfo(packageName,0) }.isSuccess,"请先安装此应用")
        app.channels.setAutoPackage(packageName,enabled)
        if(!enabled)app.captureQueue.cancelAutomatic(packageName)
        onMain {
            if(app.channels.selectedAutoPackages().isEmpty()) {
                app.channels.setAutomaticEnabled(false)
                net.ankio.ledger.capture.ScreenCaptureService.current?.cancelAutomaticRecognition()
                if(!app.channels.captureEnabled())net.ankio.ledger.capture.CaptureRuntime.stopSafely()
            }
            net.ankio.ledger.capture.ScreenCaptureService.current?.checkPaymentPages()
            app.changed()
        }
        JSONObject().put("ok",true).toString()
    } catch(e:Exception) { failure(e.message?:"无法修改应用选择").toString() }

    /** An individual switch only commits its application after the shared setup completes. */
    @JavascriptInterface
    fun setPaymentApplication(packageName:String,enabled:Boolean)=onMain {
        if(packageName !in setOf("com.tencent.mm","com.eg.android.AlipayGphone"))return@onMain
        if(enabled) {
            if(runCatching { activity.packageManager.getApplicationInfo(packageName,0) }.isFailure) { activity.message("请先安装对应的支付应用");return@onMain }
            activity.permissionSetup.startAutomatic(packageName)
        } else {
            val name=if(packageName=="com.tencent.mm")"微信" else "支付宝"
            MintDialog.confirm(activity,"关闭${name}自动识别？","将停止${name}支付成功页自动记账，其他应用与摇一摇的设置保留。","关闭自动识别") {
                app.channels.setAutoPackage(packageName,false);app.captureQueue.cancelAutomatic(packageName)
                if(app.channels.selectedAutoPackages().isEmpty())app.channels.setAutomaticEnabled(false)
                net.ankio.ledger.capture.ScreenCaptureService.current?.checkPaymentPages()
                if(!app.channels.captureEnabled())net.ankio.ledger.capture.CaptureRuntime.stopSafely()
                app.changed()
            }
        }
    }

    @JavascriptInterface
    fun repairCapture()=onMain { activity.repairCapture() }

    @JavascriptInterface
    fun setAutomaticEnabled(enabled:Boolean)=onMain {
        if(enabled) {
            if(app.channels.selectedAutoPackages().isEmpty()) { activity.message("请先选择至少一个允许自动识别的应用");return@onMain }
            activity.permissionSetup.startAutomatic()
        } else MintDialog.confirm(activity,"暂停自动识别？","将停止所选应用的支付页面自动识别。摇一摇补录及已有账单会保留。","暂停自动识别") {
            app.channels.setAutomaticEnabled(false)
            net.ankio.ledger.capture.ScreenCaptureService.current?.cancelAutomaticRecognition()
            if(app.channels.captureEnabled())runCatching { net.ankio.ledger.capture.CaptureRuntime.refreshHealth() }
            else net.ankio.ledger.capture.CaptureRuntime.stopSafely()
            app.changed()
        }
    }

    @JavascriptInterface
    fun setScreenshotRetention(days:Int):String = try {
        checkInput(days in setOf(7,30,90,0),"截图保留期限无效")
        app.screenshots.setRetentionDays(days)
        app.screenshots.cleanup()
        JSONObject().put("ok",true).put("storage",app.screenshots.stats()).toString()
    } catch(_:Exception) { failure("无法更新截图保留设置").toString() }

    @JavascriptInterface
    fun backup(action: String) {
        when (action) {
            "export" -> onMain { activity.exportBackup() }
            "restore" -> onMain { activity.restoreBackup() }
        }
    }

    @JavascriptInterface
    fun about() = onMain { activity.showAbout() }

    @JavascriptInterface
    fun aboutFrom(json:String) = withPageOrigin(json) { bounds,radius -> activity.showAbout(bounds,radius) }

    /** Only finite geometry inside the visible WebView may influence a native surface. */
    private fun withPageOrigin(json:String,open:(android.graphics.RectF,Float?)->Unit) {
        if(json.length>1024)return
        val bounds=runCatching { JSONObject(json) }.getOrNull()?:return
        onMain {
            val viewport=bounds.optDouble("viewportWidth",0.0).toFloat()
            if(!viewport.isFinite() || viewport<=0)return@onMain
            if(activity.webView.width<=0 || activity.webView.height<=0)return@onMain
            val scale=activity.webView.width/viewport
            val location=IntArray(2);activity.webView.getLocationOnScreen(location)
            val x=bounds.optDouble("left",Double.NaN).toFloat()*scale
            val y=bounds.optDouble("top",Double.NaN).toFloat()*scale
            val w=bounds.optDouble("width",Double.NaN).toFloat()*scale
            val h=bounds.optDouble("height",Double.NaN).toFloat()*scale
            if(!listOf(x,y,w,h).all { it.isFinite() } || x<0 || y<0 || w<=0 || h<=0 || x+w>activity.webView.width+1 || y+h>activity.webView.height+1)return@onMain
            val radius=if(bounds.has("radius"))bounds.optDouble("radius",Double.NaN).toFloat()*scale else null
            if(radius!=null && (!radius.isFinite() || radius<0))return@onMain
            open(android.graphics.RectF(x+location[0],y+location[1],x+w+location[0],y+h+location[1]),radius?.coerceAtMost(minOf(w,h)/2f))
        }
    }

    @JavascriptInterface
    fun privacy() = onMain { activity.privacy() }

    @JavascriptInterface
    fun privacyFrom(json:String) = withPageOrigin(json) { bounds,radius -> activity.privacy(bounds,radius) }

    @JavascriptInterface
    fun shakeStatus()=onMain { activity.showShakeStatus() }

    /** Technical metadata only; no bill text, images or model credentials. */
    @JavascriptInterface
    fun captureDiagnostics():String=net.ankio.ledger.data.RecognitionDiagnostics.permissions(app).toString()


    @JavascriptInterface
    fun setupAction(action:String) { if(action in setOf("open","check","close","restricted","back","skip","view:battery","view:autostart","view:task_lock","reset:autostart","reset:task_lock","open:accessibility","open:battery_oem","open:battery","open:task_lock","open:autostart"))onMain { activity.permissionSetup.action(action) } }

    @JavascriptInterface
    fun revokeChannel(key: String) = onMain { activity.revokeChannel(key) }

    @JavascriptInterface
    fun remember(json: String) {
        if (json.length > 65_536) return
        try {
            val validated = JSONObject(json).toString()
            onMain { activity.rememberPage(validated) }
        } catch (_: JSONException) {
            // A malformed transient page state must not replace the last valid draft.
        }
    }

    private fun onMain(action: () -> Unit) {
        activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) action() }
    }

    private fun string(input: JSONObject, key: String, maxLength: Int): String {
        val value = input.get(key) as? String ?: throw InvalidInput("字段格式错误：$key")
        checkInput(value.length <= maxLength, "字段过长：$key")
        return value
    }

    private fun integer(input: JSONObject, key: String, minimum: Long, maximum: Long): Long {
        val value = input.get(key)
        checkInput(value is Int || value is Long, "字段必须是整数：$key")
        return (value as Number).toLong().also {
            checkInput(it in minimum..maximum, "字段超出范围：$key")
        }
    }

    private fun checkInput(valid: Boolean, message: String) {
        if (!valid) throw InvalidInput(message)
    }

    private fun failure(message: String) = JSONObject().put("ok", false).put("error", message)
    private class InvalidInput(message: String) : IllegalArgumentException(message)

    private companion object {
        const val MAX_INPUT_LENGTH = 16_384
        const val MAX_TIME = 4_102_444_800_000L
        // WebView's JavaScript numbers must represent record IDs without rounding.
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        val DECIMAL = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    }
}
