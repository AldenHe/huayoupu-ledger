package net.ankio.ledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.RecordState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Bill attachment owner. Legacy relationship tables remain archive-only; amounts stay owned by records. */
class ReimbursementStore(context: Context, private val ledger: LedgerStore) {
    private val directory = ledger.reimbursementDirectory
    private val db get() = ledger.writableDatabase

    companion object {
        const val MAX_ATTACHMENT_BYTES = 32L * 1024 * 1024
        const val MAX_STORED_BYTES = 512L * 1024 * 1024
        private val kinds = setOf("invoice", "payment", "order", "travel", "explanation")
        internal val hashPattern = Regex("[0-9a-f]{64}")
        internal fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS reimbursement_expenses(record_uid TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS reimbursement_claims(uid TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS reimbursement_items(record_uid TEXT PRIMARY KEY NOT NULL,claim_uid TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS reimbursement_items_claim ON reimbursement_items(claim_uid)")
            db.execSQL("CREATE TABLE IF NOT EXISTS reimbursement_attachments(uid TEXT PRIMARY KEY NOT NULL,sha256 TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS reimbursement_links(attachment_uid TEXT NOT NULL,record_uid TEXT NOT NULL,PRIMARY KEY(attachment_uid,record_uid))")
            db.execSQL("CREATE TABLE IF NOT EXISTS reimbursement_repayments(uid TEXT PRIMARY KEY NOT NULL,claim_uid TEXT NOT NULL,source_uid TEXT,amount INTEGER NOT NULL CHECK(amount>0),received_at INTEGER NOT NULL,note TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS reimbursement_repayments_source ON reimbursement_repayments(source_uid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS reimbursement_repayments_claim ON reimbursement_repayments(claim_uid)")
        }
        /** A split receipt removes only its allocated portion from normal income. */
        fun repaymentAmountSql(alias: String = "r"): String {
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(alias))
            return "CASE WHEN $alias.direction='INCOME' THEN COALESCE((SELECT SUM(rr.amount) FROM reimbursement_repayments rr WHERE rr.source_uid=$alias.uid),0) ELSE 0 END"
        }
        fun statisticalAmountSql(alias: String = "r") = "(COALESCE($alias.amount,0)-(${repaymentAmountSql(alias)}))"
        internal fun sha256(file: File): String = file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun uuid(value: String): String = value.also { require(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "标识无效" } }
        private fun text(obj: JSONObject, key: String, max: Int = 500): String =
            (if (obj.has(key)) obj.get(key) as? String ?: error("字段必须是文字：$key") else "").also { require(it.length <= max) { "字段过长：$key" } }
        private fun cents(obj: JSONObject, key: String, zero: Boolean = true): Long {
            val n = obj.get(key); require(n is Int || n is Long) { "金额必须为整数分" }
            return (n as Number).toLong().also { require(it in (if (zero) 0L else 1L)..Money.MAX_CENTS) { "金额超出范围" } }
        }
        private fun flag(obj: JSONObject, key: String): Boolean = if (!obj.has(key)) false else (obj.get(key) as? Boolean ?: error("开关格式错误"))
        private fun time(obj: JSONObject, key: String): Long {
            val n = obj.get(key); require(n is Int || n is Long)
            return (n as Number).toLong().also { require(it in 1..4_102_444_800_000L) { "日期无效" } }
        }
        private fun ids(obj: JSONObject, key: String): List<String> {
            val array = obj.getJSONArray(key); require(array.length() in 1..1000) { "请选择 1 至 1000 笔支出" }
            return (0 until array.length()).map { uuid(array.getString(it)) }.also { require(it.distinct().size == it.size) { "支出重复" } }
        }

        /** Checks all money, identities and joins before archive restore touches the ledger. */
        internal fun validateBackup(value: JSONObject, records: List<LedgerRecord>): JSONObject {
            require(value.get("version") == 1) { "报销备份版本不支持" }
            fun fields(obj:JSONObject,allowed:Set<String>) { require(obj.keys().asSequence().toSet()==allowed) { "报销备份字段缺失或不支持" } }
            fields(value,setOf("version","expenses","claims","items","attachments","links","repayments"))
            fun objects(key: String): List<JSONObject> {
                val a=value.getJSONArray(key);require(a.length()<=100_000) { "报销备份条目过多" }
                return (0 until a.length()).map { a.getJSONObject(it) }
            }
            fun unique(list: List<JSONObject>, key: String): Set<String> = list.map { uuid(it.getString(key)) }.also { require(it.distinct().size==it.size) { "报销备份标识重复" } }.toSet()
            val expenses=objects("expenses");val claims=objects("claims");val items=objects("items")
            val attachments=objects("attachments");val links=objects("links");val receipts=objects("repayments")
            val eids=unique(expenses,"recordUid");val cids=unique(claims,"uid");val aids=unique(attachments,"uid")
            unique(items,"recordUid");unique(receipts,"uid")
            expenses.forEach { e ->
                fields(e,setOf("recordUid","requestedCents","purpose","differenceReason","unitRequirements","materialsReviewed"))
                cents(e,"requestedCents");text(e,"purpose");text(e,"differenceReason");text(e,"unitRequirements",2000);flag(e,"materialsReviewed")
            }
            claims.forEach { c ->
                fields(c,setOf("uid","title","claimant","company","reason","department","project","costCenter","createdAt","submittedAt","workflowStatus"))
                for(key in listOf("title","claimant","company","reason","department","project","costCenter")) text(c,key,if(key=="reason")2000 else 200)
                for(key in listOf("title","claimant","company","reason")) require(c.getString(key).isNotBlank())
                time(c,"createdAt");if(!c.isNull("submittedAt"))time(c,"submittedAt")
                require(c.getString("workflowStatus") in setOf("draft","submitted"))
            }
            items.forEach { fields(it,setOf("recordUid","claimUid"));require(it.getString("recordUid") in eids && uuid(it.getString("claimUid")) in cids) { "报销单明细引用缺失" } }
            require(claims.all { c -> items.any { it.getString("claimUid")==c.getString("uid") } }) { "报销单缺少明细" }
            val hashes=mutableSetOf<String>();var total=0L
            attachments.forEach { a ->
                fields(a,setOf("uid","name","mime","kind","sha256","size","invoiceNumber","issueDate","buyer","taxId","amountCents","reviewed"))
                require(hashPattern.matches(a.getString("sha256")) && hashes.add(a.getString("sha256"))) { "附件摘要重复或无效" }
                val name=text(a,"name",240);require(name.isNotBlank() && name.none { it=='/' || it=='\\' || it.code<32 }) { "附件文件名无效" }
                text(a,"mime",160);require(text(a,"kind",20) in kinds)
                val size=a.get("size");require(size is Int || size is Long);val length=(size as Number).toLong()
                require(length in 1..MAX_ATTACHMENT_BYTES);total+=length;require(total<=MAX_STORED_BYTES)
                for(key in listOf("invoiceNumber","issueDate","buyer","taxId"))text(a,key,200)
                val date=a.getString("issueDate");require(date.isBlank() || Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && runCatching { java.time.LocalDate.parse(date) }.isSuccess)
                if(!a.isNull("amountCents"))cents(a,"amountCents");flag(a,"reviewed")
            }
            val pairs=mutableSetOf<Pair<String,String>>()
            links.forEach { l ->
                fields(l,setOf("attachmentUid","recordUid"))
                val attachment=uuid(l.getString("attachmentUid"));val record=uuid(l.getString("recordUid"))
                require(attachment in aids && record in eids && pairs.add(attachment to record)) { "附件引用重复或缺失" }
            }
            val requested=expenses.associate { it.getString("recordUid") to it.getLong("requestedCents") }
            val receiptTotals=mutableMapOf<String,Long>();val sourceTotals=mutableMapOf<String,Long>()
            receipts.forEach { r ->
                fields(r,setOf("uid","claimUid","sourceRecordUid","amountCents","receivedAt","note"))
                val claim=uuid(r.getString("claimUid"));require(claim in cids)
                val amount=cents(r,"amountCents",false);time(r,"receivedAt");text(r,"note")
                receiptTotals[claim]=(receiptTotals[claim]?:0)+amount
                if(!r.isNull("sourceRecordUid")) { val source=uuid(r.getString("sourceRecordUid"));sourceTotals[source]=(sourceTotals[source]?:0)+amount }
            }
            receiptTotals.forEach { (uid,amount) -> require(amount<=items.filter { it.getString("claimUid")==uid }.sumOf { requested.getValue(it.getString("recordUid")) }) { "报销回款超过申请金额" } }
            val recordMap=records.associateBy { it.uid }
            sourceTotals.forEach { (uid,amount) -> recordMap[uid]?.let { require(it.state==RecordState.POSTED && it.bill.direction==Direction.INCOME && amount<=(it.bill.amountCents?:0)) { "关联回款必须来自已入账收入且不能超过原收入" } } }
            return JSONObject(value.toString())
        }

        /** Validate the relationships that actually survive a merge, rather than discarded backup rows. */
        internal fun validateStoredRelationships(db: SQLiteDatabase) {
            db.rawQuery("""SELECT r.state,r.direction,r.amount,SUM(p.amount) FROM reimbursement_repayments p
                JOIN records r ON r.uid=p.source_uid GROUP BY r.uid""",null).use { c ->
                while(c.moveToNext()) require(c.getString(0)=="POSTED" && c.getString(1)=="INCOME" && !c.isNull(2) && c.getLong(3)<=c.getLong(2)) {
                    "已关联报销回款的账单必须为已入账收入，且金额不能小于累计回款"
                }
            }
            db.rawQuery("SELECT claim_uid,SUM(amount) FROM reimbursement_repayments GROUP BY claim_uid",null).use { c ->
                while(c.moveToNext()) {
                    var requested=0L
                    db.rawQuery("SELECT e.payload FROM reimbursement_items i JOIN reimbursement_expenses e ON e.record_uid=i.record_uid WHERE i.claim_uid=?",arrayOf(c.getString(0))).use { items ->
                        while(items.moveToNext()) requested=Math.addExact(requested,JSONObject(items.getString(0)).getLong("requestedCents"))
                    }
                    require(c.getLong(1)<=requested) { "报销回款超过当前申请金额" }
                }
            }
        }
    }

    private fun rows(sql: String, args: Array<String>? = null): List<JSONObject> = db.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(JSONObject().apply {
            for (i in 0 until c.columnCount) put(c.getColumnName(i), if (c.isNull(i)) JSONObject.NULL else when(c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                else -> c.getString(i)
            })
        }) }
    }
    private fun payload(table: String, column: String, uid: String): JSONObject? = rows("SELECT payload FROM $table WHERE $column=?", arrayOf(uid)).firstOrNull()?.let { JSONObject(it.getString("payload")) }
    private fun putPayload(table: String, column: String, uid: String, value: JSONObject) {
        db.insertWithOnConflict(table, null, ContentValues().apply { put(column, uid); put("payload", value.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun record(uid: String): LedgerRecord? = rows("SELECT id FROM records r WHERE uid=? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)", arrayOf(uid)).firstOrNull()?.let { ledger.get(it.getLong("id")) }
    private fun expenseRecord(uid: String): LedgerRecord = requireNotNull(record(uuid(uid))) { "支出已删除或不存在" }.also {
        require(it.state == RecordState.POSTED && it.bill.direction == Direction.EXPENSE && it.requiresReimbursement) { "只能选择已入账且需要报销的支出" }
    }
    private fun claimFor(uid: String): String? = rows("SELECT claim_uid FROM reimbursement_items WHERE record_uid=?", arrayOf(uid)).firstOrNull()?.getString("claim_uid")
    private fun attachment(uid: String): JSONObject = requireNotNull(payload("reimbursement_attachments", "uid", uuid(uid))) { "附件不存在" }
    private fun attachmentsFor(uid: String): List<JSONObject> = rows("SELECT a.payload FROM reimbursement_attachments a JOIN reimbursement_links l ON l.attachment_uid=a.uid WHERE l.record_uid=? ORDER BY a.uid", arrayOf(uid)).map { JSONObject(it.getString("payload")) }

    private fun expense(uid: String): JSONObject {
        val record = expenseRecord(uid)
        return JSONObject().put("recordUid", uid).put("id", record.id)
            .put("merchant", record.bill.merchant).put("category", record.bill.category)
            .put("occurredAt", record.bill.occurredAt).put("paidCents", requireNotNull(record.bill.amountCents))
            .put("note", record.note).put("attachments", JSONArray(attachmentsFor(uid)))
    }

    fun snapshot(): JSONObject = synchronized(ledger) {
        val expenses = rows("SELECT uid FROM records r WHERE r.state='POSTED' AND r.direction='EXPENSE' AND r.requires_reimbursement=1 AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id) ORDER BY r.occurred_at DESC,r.uid").map { expense(it.getString("uid")) }
        JSONObject().put("expenses", JSONArray(expenses)).put("attachments", JSONArray(allAttachmentMetadata()))
    }

    /** Read-only projection: selection neither creates a claim nor changes any ledger relationship. */
    internal fun exportSnapshot(recordUids: List<String>): JSONObject = synchronized(ledger) {
        val selected = ids(JSONObject().put("recordUids", JSONArray(recordUids)), "recordUids")
        val items = selected.map(::expense)
        JSONObject().put("generatedAt", System.currentTimeMillis()).put("items", JSONArray(items))
            .put("paidCents", items.sumOf { it.getLong("paidCents") })
            .put("attachments", JSONArray(items.flatMap { item ->
                val a = item.getJSONArray("attachments");(0 until a.length()).map { a.getJSONObject(it) }
            }.distinctBy { it.getString("uid") }))
    }

    fun command(input: JSONObject): JSONObject = synchronized(ledger) {
        require(input.getString("action") in setOf("linkAttachment", "unlinkAttachment")) { "材料操作无效" }
        db.beginTransaction()
        try {
            when (input.getString("action")) {
                "linkAttachment" -> link(input.getString("attachmentUid"), ids(input,"recordUids"))
                "unlinkAttachment" -> {
                    val uid = input.getString("recordUid");expenseRecord(uid)
                    attachment(input.getString("attachmentUid"))
                    db.delete("reimbursement_links", "record_uid=? AND attachment_uid=?", arrayOf(uid,input.getString("attachmentUid")))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        snapshot()
    }

    private fun link(attachmentUid: String, uids: List<String>) {
        attachment(attachmentUid)
        uids.forEach(::expenseRecord)
        uids.forEach {
            // The existing archive format anchors links in expenses. This blank carrier is not
            // a workflow projection and preserves the original archive contract without a second owner.
            if(payload("reimbursement_expenses","record_uid",it)==null) putPayload("reimbursement_expenses","record_uid",it,JSONObject()
                .put("requestedCents",0L).put("purpose","").put("differenceReason","").put("unitRequirements","").put("materialsReviewed",false))
            db.insertWithOnConflict("reimbursement_links",null,ContentValues().apply { put("attachment_uid",attachmentUid);put("record_uid",it) },SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** Copies untouched originals into app-private content-addressed storage, never into JSON. */
    fun importAttachment(recordUids: List<String>, name: String, mime: String, kind: String, input: InputStream): JSONObject = synchronized(ledger) {
        require(recordUids.isNotEmpty() && recordUids.size<=1000 && recordUids.distinct().size==recordUids.size)
        recordUids.forEach(::expenseRecord)
        require(name.isNotBlank() && name.length<=240 && name.none { it=='/' || it=='\\' || it.code<32 }) { "附件文件名无效" }
        require(mime.length<=160 && kind in kinds)
        val extension=name.substringAfterLast('.',"").lowercase()
        require(extension in setOf("pdf","ofd","xml","png","jpg","jpeg","webp","heic","heif","txt")) { "支持 PDF、OFD、XML、图片及说明文本原件" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建附件目录" }
        val staged=File.createTempFile("import-",".tmp",directory)
        try {
            var size=0L;val digest=MessageDigest.getInstance("SHA-256")
            staged.outputStream().use { out -> val buffer=ByteArray(8192);while(true) {
                val n=input.read(buffer);if(n==-1)break;size+=n
                require(size<=MAX_ATTACHMENT_BYTES) { "单个附件不能超过 32 MB" };digest.update(buffer,0,n);out.write(buffer,0,n)
            } }
            require(size>0) { "附件内容为空" }
            val hash=digest.digest().joinToString("") { "%02x".format(it) }
            val old=rows("SELECT uid FROM reimbursement_attachments WHERE sha256=?",arrayOf(hash)).firstOrNull()
            var createdOriginal:File?=null
            var committed=false
            try {
                db.beginTransaction()
                val imported=try {
                    val uid=old?.getString("uid") ?: UUID.randomUUID().toString()
                    if(old==null) {
                        val used=directory.listFiles().orEmpty().filter { hashPattern.matches(it.name) }.sumOf { it.length() }
                        require(used+(if(File(directory,hash).exists())0 else size)<=MAX_STORED_BYTES) { "报销原件总量不能超过 512 MB" }
                        val data=JSONObject().put("uid",uid).put("name",name).put("mime",mime).put("kind",kind).put("sha256",hash).put("size",size)
                            .put("invoiceNumber","").put("issueDate","").put("buyer","").put("taxId","").put("amountCents",JSONObject.NULL).put("reviewed",false)
                        val target=File(directory,hash)
                        if(target.exists()) require(target.length()==size && sha256(target)==hash) { "本地附件原件已损坏" }
                        else {
                            check(staged.renameTo(target)) { "无法保存附件原件" }
                            createdOriginal=target
                        }
                        db.insertOrThrow("reimbursement_attachments",null,ContentValues().apply { put("uid",uid);put("sha256",hash);put("payload",data.toString()) })
                    } else attachmentFile(uid) // Verify an existing original before reusing its metadata.
                    link(uid,recordUids)
                    val result=attachment(uid).put("reused",old!=null)
                    db.setTransactionSuccessful()
                    result
                } finally { db.endTransaction() }
                committed=true
                imported
            } finally {
                if(!committed)createdOriginal?.let {file->
                    // A commit error can be ambiguous. Remove only a file this import created,
                    // after confirming rollback left no metadata referencing its hash.
                    if(runCatching {rows("SELECT uid FROM reimbursement_attachments WHERE sha256=?",arrayOf(hash)).isEmpty()}.getOrDefault(false))file.delete()
                }
            }
        } finally { staged.delete() }
    }

    fun attachmentFile(uid: String): File = synchronized(ledger) {
        val data=attachment(uid);val hash=data.getString("sha256");require(hashPattern.matches(hash))
        File(directory,hash).also { require(it.isFile && it.length()==data.getLong("size") && sha256(it)==hash) { "附件原件缺失或已损坏" } }
    }
    fun attachmentMetadata(uid: String): JSONObject = synchronized(ledger) { JSONObject(attachment(uuid(uid)).toString()) }
    fun attachmentsForRecord(recordUid: String): List<JSONObject> = synchronized(ledger) {
        attachmentsFor(uuid(recordUid)).map { JSONObject(it.toString()) }
    }
    private fun allAttachmentMetadata(): List<JSONObject> = rows("SELECT payload FROM reimbursement_attachments ORDER BY uid").map { JSONObject(it.getString("payload")) }

    internal fun backupMetadata(): JSONObject = synchronized(ledger) {
        JSONObject().put("version",1)
            .put("expenses",JSONArray(rows("SELECT record_uid,payload FROM reimbursement_expenses ORDER BY record_uid").map { JSONObject(it.getString("payload")).put("recordUid",it.getString("record_uid")) }))
            .put("claims",JSONArray(rows("SELECT payload FROM reimbursement_claims ORDER BY uid").map { JSONObject(it.getString("payload")) }))
            .put("items",JSONArray(rows("SELECT record_uid,claim_uid FROM reimbursement_items ORDER BY record_uid").map { JSONObject().put("recordUid",it.getString("record_uid")).put("claimUid",it.getString("claim_uid")) }))
            .put("attachments",JSONArray(allAttachmentMetadata()))
            .put("links",JSONArray(rows("SELECT attachment_uid,record_uid FROM reimbursement_links ORDER BY attachment_uid,record_uid").map { JSONObject().put("attachmentUid",it.getString("attachment_uid")).put("recordUid",it.getString("record_uid")) }))
            .put("repayments",JSONArray(rows("SELECT * FROM reimbursement_repayments ORDER BY uid").map { JSONObject().put("uid",it.getString("uid")).put("claimUid",it.getString("claim_uid"))
                .put("sourceRecordUid",it.opt("source_uid")).put("amountCents",it.getLong("amount")).put("receivedAt",it.getLong("received_at")).put("note",it.getString("note")) }))
    }

    /** New entities are merged; existing local claim/material edits and receipt removals win. */
    internal fun restoreMetadata(metadata: JSONObject, files: Map<String,File>, createdOriginals: MutableList<File>) = synchronized(ledger) {
        // Archived records were checked during preflight. Local edits may deliberately make
        // discarded old receipt rows incompatible; validate only the merged relationships below.
        val data=validateBackup(metadata,emptyList())
        fun entries(key:String):List<JSONObject> { val a=data.getJSONArray(key);return (0 until a.length()).map { a.getJSONObject(it) } }
        check(directory.isDirectory || directory.mkdirs())
        val incomingAttachments=entries("attachments")
        incomingAttachments.forEach { a ->
            val hash=a.getString("sha256");val source=requireNotNull(files[hash]) { "备份缺少附件原件" }
            require(source.length()==a.getLong("size") && sha256(source)==hash) { "附件原件已损坏" }
        }
        db.beginTransaction()
        try {
            val mapped=mutableMapOf<String,String>()
            incomingAttachments.forEach { a ->
                val uid=a.getString("uid");val hash=a.getString("sha256")
                val existingUid=rows("SELECT uid FROM reimbursement_attachments WHERE sha256=?",arrayOf(hash)).firstOrNull()?.getString("uid")
                val local=payload("reimbursement_attachments","uid",uid)
                require(local==null || local.getString("sha256")==hash) { "附件标识与本地原件冲突" }
                mapped[uid]=existingUid?:uid
                val target=File(directory,hash)
                if(target.exists()) require(target.length()==a.getLong("size") && sha256(target)==hash) { "本地附件原件已损坏" }
                else {
                    val used=directory.listFiles().orEmpty().filter { hashPattern.matches(it.name) }.sumOf { it.length() }
                    require(used+a.getLong("size")<=MAX_STORED_BYTES) { "本地报销原件总量不能超过 512 MB" }
                    val staged=File.createTempFile("restore-",".tmp",directory)
                    try {
                        files.getValue(hash).inputStream().use { input -> staged.outputStream().use { input.copyTo(it,8192) } }
                        check(staged.renameTo(target));createdOriginals+=target
                    } finally { staged.delete() }
                }
                if(local==null && existingUid==null) db.insertOrThrow("reimbursement_attachments",null,ContentValues().apply { put("uid",uid);put("sha256",hash);put("payload",a.toString()) })
            }
            val oldExpenseUids=rows("SELECT record_uid FROM reimbursement_expenses").map { it.getString("record_uid") }.toSet()
            entries("expenses").forEach { e ->
                val uid=e.getString("recordUid")
                if(uid !in oldExpenseUids) { val payload=JSONObject(e.toString());payload.remove("recordUid");putPayload("reimbursement_expenses","record_uid",uid,payload) }
            }
            val restoredClaims=mutableSetOf<String>()
            val incomingItems=entries("items")
            entries("claims").forEach { c ->
                val uid=c.getString("uid");val items=incomingItems.filter { it.getString("claimUid")==uid }
                if(payload("reimbursement_claims","uid",uid)==null && items.none { claimFor(it.getString("recordUid"))!=null }) {
                    putPayload("reimbursement_claims","uid",uid,c);restoredClaims+=uid
                    items.forEach { db.insertOrThrow("reimbursement_items",null,ContentValues().apply { put("record_uid",it.getString("recordUid"));put("claim_uid",uid) }) }
                }
            }
            entries("links").forEach { l ->
                val record=l.getString("recordUid")
                // Repeated restore must not undo locally removed links on edited existing expenses.
                if(record !in oldExpenseUids) db.insertWithOnConflict("reimbursement_links",null,ContentValues().apply { put("record_uid",record);put("attachment_uid",mapped.getValue(l.getString("attachmentUid"))) },SQLiteDatabase.CONFLICT_IGNORE)
            }
            entries("repayments").filter { it.getString("claimUid") in restoredClaims }.forEach { r ->
                val source=if(r.isNull("sourceRecordUid"))null else r.getString("sourceRecordUid")
                db.insertWithOnConflict("reimbursement_repayments",null,ContentValues().apply {
                    put("uid",r.getString("uid"));put("claim_uid",r.getString("claimUid"));put("source_uid",source)
                    put("amount",r.getLong("amountCents"));put("received_at",r.getLong("receivedAt"));put("note",r.getString("note"))
                },SQLiteDatabase.CONFLICT_IGNORE)
            }
            validateStoredRelationships(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
