package net.ankio.ledger.data

import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

data class ValidatedBackup(val records: List<LedgerRecord>, val channels: Map<ChannelSettings.Channel, Boolean>,
    val accounts: List<LedgerAccount> = emptyList(), val categories: List<CustomCategory> = emptyList(),
    val histories:Map<String,List<RestoredCaptureHistory>> = emptyMap(),
    val reimbursement: JSONObject? = null, val defaultAccountUid:String? = null,
    val channelDefaultAccounts:Map<String,String> = emptyMap(),
    val transfers:List<AccountTransfer> = emptyList())

object LedgerBackup {
    const val MAX_BYTES = 16 * 1024 * 1024

    /** Shared by archive preflight and database restore; no database mutation is needed. */
    internal fun validateHistory(record:LedgerRecord, entries:List<RestoredCaptureHistory>) {
        require(entries.map { it.entry.sequence } == (1..entries.size).toList()) { "识别记录序号不连续" }
        var previousRefund=0L
        val ids=mutableSetOf<String>()
        val modes=mutableSetOf<String>()
        entries.forEachIndexed { index, restored ->
            val entry=restored.entry
            require(entry.at>0 && entry.rawText.length<=32_768 && (restored.image==null || restored.image.isNotEmpty() && restored.image.size<=5*1024*1024))
            require(entry.amountCents==null || entry.amountCents in 0..Money.MAX_CENTS)
            require(entry.refundedCents>=previousRefund)
            if(index==0)require(entry.kind in setOf("payment","refund_pending","partial_refund","full_refund"))
            if(index==0 && entry.kind in setOf("payment","refund_pending")) Unit
            else if(entry.kind in setOf("payment_supplement","refund_supplement")) {
                require(entry.refundedCents==previousRefund && entry.refundId==null && entry.refundAmountCents==null) { "补充截图不能改变退款金额" }
                if(previousRefund>0) {
                    val summary=requireNotNull(record.bill.refund)
                    require(entry.amountCents==summary.originalAmountCents-previousRefund) { "补充截图净额不一致" }
                }
            } else {
                val summary=requireNotNull(record.bill.refund)
                require(entry.kind in setOf("partial_refund","full_refund") && entry.amountCents==summary.originalAmountCents-entry.refundedCents)
                require(entry.refundedCents<=summary.refundedCents && entry.refundMode in setOf("single","cumulative"))
                if(index==0)require(entry.refundMode=="cumulative") { "首次入账需要完整累计退款信息" }
                modes.add(entry.refundMode);require(modes.size==1) { "退款口径冲突" }
                if(entry.refundMode=="single") {
                    require(!entry.refundId.isNullOrBlank() && ids.add(entry.refundId))
                    require(entry.refundAmountCents==entry.refundedCents-previousRefund && entry.refundAmountCents!!>0)
                }
            }
            previousRefund=entry.refundedCents
        }
        if(entries.size>1 || entries.firstOrNull()?.entry?.kind in setOf("partial_refund","full_refund"))
            require(previousRefund==(record.bill.refund?.refundedCents ?: 0L)) { "退款历史与汇总不一致" }
    }
    fun encode(store: LedgerStore, channels: ChannelSettings): String = synchronized(store) {
        val records=store.all()
        val root=JSONObject(encode(records, channels, store.accounts(), store.customCategories(),store.defaultAccount()?.uid,store.channelDefaultAccounts(),AccountTransfers(store).list()))
        val histories=JSONObject()
        val grouped=store.captureHistories()
        var imageBytes=0L
        for(record in records) {
            val entries=JSONArray()
            for(entry in grouped[record.id].orEmpty()) {
                val image=store.historyScreenshot(record.id,entry.sequence)
                imageBytes+=(image?.size?:0)
                require(imageBytes*4/3<MAX_BYTES) { "截图超过备份容量，请先调整截图保留范围" }
                entries.put(historyJson(entry)
                    .put("image",image?.let { android.util.Base64.encodeToString(it,android.util.Base64.NO_WRAP) }?:JSONObject.NULL))
            }
            if(entries.length()>0)histories.put(record.uid,entries)
        }
        root.put("histories",histories)
        val reimbursement=ReimbursementStore(store.reimbursementContext,store).backupMetadata()
        require(reimbursement.getJSONArray("attachments").length()==0) { "报销原件请使用完整 ZIP 备份" }
        root.put("reimbursement",reimbursement)
        root.toString().also { require(it.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份及截图超过 16 MB" } }
    }

    /** Only newly imported UUIDs receive history; restoring must not overwrite later local edits. */
    fun restore(store:LedgerStore,backup:ValidatedBackup,originalFiles:Map<String,java.io.File> = emptyMap(),restoreMedia:(LedgerRecord)->Unit={ record ->
        backup.histories[record.uid]?.let { store.restoreHistory(record.id,it) }
    }):Int=synchronized(store) {
        validateChannelDefaults(backup.channelDefaultAccounts,backup.accounts)
        val old=store.activeRecordIdsByUid()
        val hadAccounts=store.accounts().isNotEmpty()
        val db=store.writableDatabase
        val createdOriginals=mutableListOf<java.io.File>()
        var successful=false
        db.beginTransaction()
        try {
            val count=store.mergeRestored(backup.records,backup.accounts,backup.categories,backup.transfers)
            if(!hadAccounts) {
                if(backup.defaultAccountUid!=null)store.setDefaultAccount(backup.defaultAccountUid)
                backup.channelDefaultAccounts.forEach { (channel,uid)->store.setChannelDefaultAccount(channel,uid) }
            }
            for((uid,id) in store.activeRecordIdsByUid())if(uid !in old)restoreMedia(requireNotNull(store.get(id)))
            backup.reimbursement?.let { ReimbursementStore(store.reimbursementContext,store).restoreMetadata(it,originalFiles,createdOriginals) }
            ReimbursementStore.validateStoredRelationships(db)
            db.setTransactionSuccessful();successful=true;count
        } catch(error:Throwable) {
            // ScreenshotStore uses a separate database. Release all imported owners while
            // the uncommitted history rows still identify them, before rolling back the batch.
            store.activeRecordIdsByUid().filterKeys { it !in old }.values.forEach(store::releaseRestoredMedia)
            throw error
        } finally {
            var ended=false
            try { db.endTransaction();ended=true } finally {
                if(!successful || !ended) createdOriginals.forEach { check(!it.exists() || it.delete()) { "无法清理本次恢复的临时原件" } }
            }
        }
    }

    internal fun historyJson(e:RecordCaptureHistory)=JSONObject().put("sequence",e.sequence).put("at",e.at)
        .put("kind",e.kind).put("amountCents",e.amountCents?:JSONObject.NULL).put("refundedCents",e.refundedCents)
        .put("rawText",e.rawText).put("refundMode",e.refundMode).put("refundId",e.refundId?:JSONObject.NULL)
        .put("refundAmountCents",e.refundAmountCents?:JSONObject.NULL).put("image",JSONObject.NULL)

    fun encode(records: List<LedgerRecord>, channels: ChannelSettings,
               accounts: List<LedgerAccount> = emptyList(), categories: List<CustomCategory> = emptyList(), defaultAccountUid:String? = null,
               channelDefaultAccounts:Map<String,String> = emptyMap(), transfers:List<AccountTransfer> = emptyList()): String {
        validateChannelDefaults(channelDefaultAccounts,accounts)
        val settings = JSONObject()
        ChannelSettings.Channel.entries.forEach { settings.put(it.key, channels.enabled(it)) }
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().apply {
            put("uid", r.uid); put("source", r.capture.source.name); put("origin", r.capture.origin)
            put("text", r.capture.text); put("receivedAt", r.capture.receivedAt)
            put("amount", r.bill.amountCents ?: JSONObject.NULL)
            put("direction", r.bill.direction?.name ?: JSONObject.NULL)
            put("merchant", r.bill.merchant); put("category", r.bill.category)
            put("occurredAt", r.bill.occurredAt); put("transactionId", r.bill.transactionId ?: JSONObject.NULL)
            put("reliable", r.bill.reliable); put("timeExplicit", r.bill.timeExplicit)
            put("state", r.state.name); put("reason", r.reason)
            put("note", r.note);put("tags",JSONArray(BillTags.encode(r.tags)));put("requiresReimbursement",r.requiresReimbursement)
            put("identities",JSONArray(TransactionIdentities.encode(r.identities)))
            put("accountUid",r.accountUid ?: JSONObject.NULL);put("accountEffect",r.accountEffect)
            r.bill.refund?.let { refund -> put("refund",JSONObject().put("provider",refund.provider)
                .put("originalAmountCents",refund.originalAmountCents).put("refundedCents",refund.refundedCents)
                .put("mode",refund.mode).put("refundId",refund.refundId?:JSONObject.NULL)) }
        }) }
        val accountArray = JSONArray()
        accounts.forEach { account ->
            account.validate()
            accountArray.put(JSONObject().put("uid", account.uid).put("name", account.name)
                .put("description", account.description).put("amountCents", account.amountCents)
                .put("kind", account.kind).put("type", account.type.name).put("includedInAssets",account.includedInAssets))
        }
        val custom = JSONObject()
        Direction.entries.forEach { direction ->
            val names = JSONArray()
            categories.filter { it.direction == direction }.forEach { it.validate(); names.put(it.name) }
            custom.put(if (direction == Direction.EXPENSE) "expense" else "income", names)
        }
        val accountsByUid=accounts.associateBy { it.uid }
        require(transfers.map { it.uid }.distinct().size==transfers.size) { "转账标识重复" }
        val transferArray=JSONArray()
        transfers.forEach { value ->
            value.validate()
            val from=requireNotNull(accountsByUid[value.fromUid]) { "转出账户不存在" }
            val to=requireNotNull(accountsByUid[value.toUid]) { "转入账户不存在" }
            transferArray.put(JSONObject().put("uid",value.uid).put("fromUid",value.fromUid).put("toUid",value.toUid)
                .put("amountCents",value.amountCents).put("occurredAt",value.occurredAt).put("note",value.note)
                .put("fromEffect",AccountTransfers.effect(from.type,value.amountCents,true))
                .put("toEffect",AccountTransfers.effect(to.type,value.amountCents,false)))
        }
        return JSONObject().put("format", "simple-ledger").put("version", 8)
            .put("channels", settings).put("records", array).put("accounts", accountArray)
            .put("customCategories", custom).put("defaultAccountUid",defaultAccountUid?:JSONObject.NULL)
            .put("channelDefaultAccounts",JSONObject(channelDefaultAccounts)).put("transfers",transferArray).toString(2)
    }

    private fun validateChannelDefaults(defaults:Map<String,String>,accounts:List<LedgerAccount>) {
        defaults.forEach { (channel,uid)->
            require(channel in setOf("wechat","alipay")) { "不支持的默认账户支付渠道" }
            require(UUID.fromString(uid).toString()==uid && accounts.any { it.uid==uid }) { "支付渠道默认账户不存在或标识无效" }
        }
    }

    fun decode(stream: InputStream): ValidatedBackup {
        val buffer = ByteArray(8192)
        val collected = ByteArrayOutputStream()
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            require(collected.size() + count <= MAX_BYTES) { "备份文件超过 16 MB" }
            collected.write(buffer, 0, count)
        }
        val bytes = collected.toByteArray()
        return decodeMetadata(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    /** Reuse the legacy schema validation for ZIP metadata, whose image bytes live separately. */
    internal fun decodeMetadata(root: JSONObject): ValidatedBackup {
        val version = root.get("version")
        require(root.get("format") == "simple-ledger" && version is Int && version in 1..8) { "备份格式或版本不支持" }
        val array = root.getJSONArray("records")
        require(array.length() <= 100_000) { "备份记录过多" }
        val uids = mutableSetOf<String>()
        val records = (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            fun text(key: String, max: Int = 1024): String = ((obj.get(key) as? String)
                ?: error("字段类型错误：$key")).also { require(it.length <= max) { "字段过长：$key" } }
            fun integer(key: String): Long {
                val value = obj.get(key)
                require(value is Int || value is Long) { "字段必须是整数：$key" }
                return (value as Number).toLong()
            }
            fun positiveTime(key: String) = integer(key).also { require(it in 1..4_102_444_800_000L) { "时间格式错误" } }
            fun bool(key: String) = (obj.get(key) as? Boolean) ?: error("开关格式错误：$key")
            val uid = text("uid", 36)
            require(UUID.fromString(uid).toString() == uid && uids.add(uid)) { "账单标识错误或重复" }
            val amount = if (obj.isNull("amount")) null else integer("amount")
            val refund=obj.optJSONObject("refund")?.let { r ->
                fun cents(key:String):Long { val v=r.get(key);require(v is Long || v is Int);return (v as Number).toLong() }
                RefundInfo(r.getString("provider"),cents("originalAmountCents"),cents("refundedCents"),
                    r.optString("mode","cumulative"),if(r.isNull("refundId"))null else r.getString("refundId")).also {
                    require(it.provider in setOf("wechat","alipay") && it.originalAmountCents in 1..Money.MAX_CENTS &&
                        it.refundedCents in 1..it.originalAmountCents && it.mode in setOf("cumulative","single")) { "退款信息无效" }
                    require(it.mode!="single" || !it.refundId.isNullOrBlank()) { "退款单号缺失" }
                    require((it.refundId?.length?:0)<=160) { "退款单号过长" }
                }
            }
            require(amount == null || amount in 1..Money.MAX_CENTS || amount==0L && refund!=null && refund.refundedCents==refund.originalAmountCents) { "账单金额无效" }
            val direction = if (obj.isNull("direction")) null else Direction.valueOf(text("direction"))
            val state = RecordState.valueOf(text("state"))
            if(refund!=null)require(direction==Direction.EXPENSE && (state!=RecordState.POSTED || amount==refund.originalAmountCents-refund.refundedCents)) { "退款净额不一致" }
            require(state != RecordState.POSTED || amount != null && direction != null) { "已入账记录缺少金额或收支类型" }
            val tags=if(version>=4)BillTags.decode(obj.getJSONArray("tags").toString()) else emptyList()
            val reimbursement=if(version>=5)bool("requiresReimbursement") else BillTags.LEGACY_REIMBURSEMENT in tags
            LedgerRecord(uid = uid,
                capture = Capture(Source.valueOf(text("source")), text("origin"), text("text", 32_768), positiveTime("receivedAt")),
                bill = ParsedBill(amount, direction, text("merchant"), text("category"), positiveTime("occurredAt"),
                    if (obj.isNull("transactionId")) null else text("transactionId"), bool("reliable"), text("reason"), bool("timeExplicit"),refund),
                state = state, reason = text("reason"), note = text("note", 500), tags=tags-BillTags.LEGACY_REIMBURSEMENT,requiresReimbursement=reimbursement,
                identities=obj.optJSONArray("identities")?.let { TransactionIdentities.decode(it.toString()) } ?: emptyList(),
                accountUid=if(version>=6 && !obj.isNull("accountUid"))text("accountUid",36) else null,
                accountEffect=if(version>=6)integer("accountEffect").also { require(it in -Money.MAX_CENTS..Money.MAX_CENTS) } else 0)
        }
        val settings = root.getJSONObject("channels")
        val channels = ChannelSettings.Channel.entries.associateWith { channel ->
            if(channel!=ChannelSettings.Channel.OCR) false
            else (settings.get(channel.key) as? Boolean) ?: error("渠道设置格式错误")
        }
        val accounts = if (version == 1) emptyList() else {
            val arrayOfAccounts = root.getJSONArray("accounts")
            require(arrayOfAccounts.length() <= 10_000) { "备份账户过多" }
            val accountUids = mutableSetOf<String>()
            (0 until arrayOfAccounts.length()).map { index ->
                val obj = arrayOfAccounts.getJSONObject(index)
                fun text(key: String, max: Int): String = ((obj.get(key) as? String)
                    ?: error("账户字段类型错误：$key")).also { require(it.length <= max) { "账户字段过长：$key" } }
                val amount = obj.get("amountCents")
                require(amount is Int || amount is Long) { "账户余额必须为整数分" }
                val account = LedgerAccount(uid = text("uid", 36), name = text("name", 40),
                    description = text("description", 80), amountCents = (amount as Number).toLong(),
                    kind = text("kind", 16), type = AccountType.valueOf(text("type", 16)),
                    includedInAssets=if(version>=8 && obj.has("includedInAssets"))(obj.get("includedInAssets") as? Boolean ?: error("计入资产开关格式错误")) else true)
                account.validate()
                require(accountUids.add(account.uid)) { "账户标识重复" }
                account
            }
        }
        val defaultAccountUid=if(version>=6 && !root.isNull("defaultAccountUid"))root.getString("defaultAccountUid") else null
        require(defaultAccountUid==null || accounts.any { it.uid==defaultAccountUid }) { "默认账户不存在" }
        val channelDefaultAccounts=if(version>=7 && root.has("channelDefaultAccounts")) {
            val defaults=root.getJSONObject("channelDefaultAccounts")
            defaults.keys().asSequence().associateWith { defaults.get(it) as? String ?: error("支付渠道默认账户格式错误") }
        }else emptyMap()
        validateChannelDefaults(channelDefaultAccounts,accounts)
        val accountsByUid=accounts.associateBy { it.uid }
        val transfers=if(version>=8) {
            val values=root.getJSONArray("transfers")
            require(values.length()<=100_000) { "备份转账记录过多" }
            val transferUids=mutableSetOf<String>()
            (0 until values.length()).map { index ->
                val obj=values.getJSONObject(index)
                fun integer(key:String):Long {
                    val value=obj.get(key);require(value is Int || value is Long) { "转账字段必须为整数：$key" }
                    return (value as Number).toLong()
                }
                fun text(key:String):String=obj.get(key) as? String ?: error("转账字段格式错误：$key")
                val transfer=AccountTransfer(text("uid"),text("fromUid"),text("toUid"),integer("amountCents"),integer("occurredAt"),text("note"))
                transfer.validate()
                require(transferUids.add(transfer.uid)) { "转账标识重复" }
                val from=requireNotNull(accountsByUid[transfer.fromUid]) { "转出账户不存在" }
                val to=requireNotNull(accountsByUid[transfer.toUid]) { "转入账户不存在" }
                require(integer("fromEffect")==AccountTransfers.effect(from.type,transfer.amountCents,true) &&
                    integer("toEffect")==AccountTransfers.effect(to.type,transfer.amountCents,false)) { "转账余额关联不一致" }
                transfer
            }
        } else emptyList()
        for(record in records) {
            val account=record.accountUid?.let { uid->accountsByUid[uid] ?: error("关联账户不存在") }
            val signed=if(record.bill.direction==Direction.EXPENSE)-(record.bill.amountCents?:0) else record.bill.amountCents?:0
            val expected=if(record.state!=RecordState.POSTED || account==null)0L else if(account.type==AccountType.LIABILITY)-signed else signed
            require(record.accountEffect==expected) { "账户余额关联不一致" }
        }
        val categories = if (version == 1) emptyList() else {
            val custom = root.getJSONObject("customCategories")
            Direction.entries.flatMap { direction ->
                val key = if (direction == Direction.EXPENSE) "expense" else "income"
                val names = custom.getJSONArray(key)
                require(names.length() <= 1_000) { "备份分类过多" }
                val unique = mutableSetOf<String>()
                (0 until names.length()).map { index ->
                    val name = names.get(index) as? String ?: error("分类名称格式错误")
                    CustomCategory(name, direction).also {
                        it.validate()
                        require(unique.add(name)) { "分类名称重复" }
                    }
                }
            }
        }
        val histories=mutableMapOf<String,List<RestoredCaptureHistory>>()
        root.optJSONObject("histories")?.let { all ->
            require(all.length()<=records.size)
            for(uid in all.keys()) {
                require(uid in uids) { "截图没有关联账单" }
                val entries=all.getJSONArray(uid);require(entries.length()<=1000)
                var previous=0
                histories[uid]=(0 until entries.length()).map { index ->
                    val e=entries.getJSONObject(index)
                    fun num(key:String):Long { val v=e.get(key);require(v is Int || v is Long);return (v as Number).toLong() }
                    val sequence=num("sequence");require(sequence>previous && sequence<=Int.MAX_VALUE);previous=sequence.toInt()
                    val at=num("at");require(at in 1..4_102_444_800_000L)
                    val kind=e.getString("kind");require(kind.length<=40)
                    val amount=if(e.isNull("amountCents"))null else num("amountCents");require(amount==null || amount in 0..Money.MAX_CENTS)
                    val refunded=num("refundedCents");require(refunded in 0..Money.MAX_CENTS)
                    val raw=e.getString("rawText");require(raw.length<=32_768)
                    val mode=e.optString("refundMode","cumulative");require(mode in setOf("cumulative","single"))
                    val refundId=if(e.isNull("refundId"))null else e.getString("refundId");require(refundId==null || refundId.length in 1..160)
                    val refundAmount=if(e.isNull("refundAmountCents"))null else num("refundAmountCents");require(refundAmount==null || refundAmount in 1..Money.MAX_CENTS)
                    val image=if(e.isNull("image"))null else {
                        val encoded=e.getString("image");require(encoded.length<=7_000_000)
                        android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP).also { require(it.isNotEmpty() && it.size<=5*1024*1024) }
                    }
                    RestoredCaptureHistory(RecordCaptureHistory(sequence.toInt(),at,kind,amount,refunded,raw,image!=null,mode,refundId,refundAmount),image)
                }
            }
        }
        val reimbursement=if(root.has("reimbursement"))ReimbursementStore.validateBackup(root.getJSONObject("reimbursement"),records) else null
        return ValidatedBackup(records, channels, accounts, categories,histories,reimbursement,defaultAccountUid,channelDefaultAccounts,transfers)
    }
}
