package net.ankio.ledger.data

import android.content.ContentValues
import net.ankio.ledger.ocr.MerchantIconEncoding
import net.ankio.ledger.ocr.IconLocalization
import android.graphics.BitmapFactory
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.ankio.ledger.core.*
import java.util.UUID

data class LedgerRecord(
    val id: Long = 0,
    val uid: String = UUID.randomUUID().toString(),
    val capture: Capture,
    val bill: ParsedBill,
    val state: RecordState,
    val reason: String = bill.reason,
    val duplicateId: Long? = null,
    val note: String = "",
    val identities: List<TransactionIdentity> = emptyList(),
    val tags: List<String> = emptyList(),
    val requiresReimbursement: Boolean = false,
    val accountUid: String? = null,
    val accountEffect: Long = 0,
) {
    fun existing() = ExistingBill(id, capture.source, capture.origin, capture.text,
        bill.amountCents, bill.direction, bill.merchant, bill.occurredAt, bill.transactionId, bill.timeExplicit)
}

sealed class IngestResult {
    data class Saved(val record: LedgerRecord, val updated: Boolean = false, val supplemented: Boolean = false) : IngestResult()
    data object DuplicateSkipped : IngestResult()
    data object Ignored : IngestResult()
    data class Skipped(val message:String):IngestResult()
}

data class DeletedRecord(val id:Long,val expiresAt:Long)

class LedgerStore(context: Context, private val name: String = "ledger.db", private val screenshots: ScreenshotStore = ScreenshotStore.get(context)) : SQLiteOpenHelper(context, name, null, 14) {
    internal val reimbursementContext: Context = context.applicationContext
    internal val reimbursementDirectory = java.io.File(context.filesDir, "reimbursement-originals/" + UUID.nameUUIDFromBytes(name.toByteArray()).toString())
    private fun imageOwner(id: Long) = "ledger:$name:$id"
    private fun historyOwner(id: Long, sequence: Int) = if (sequence == 1) imageOwner(id) else "ledger:$name:$id:history:$sequence"
    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        if(db.isReadOnly)return
        // Migrate one durable reference before clearing each legacy BLOB; safe to resume after a crash.
        var migratedBytes=0L
        db.rawQuery("SELECT i.record_id,length(i.image),r.received_at FROM capture_images i JOIN records r ON i.record_id=r.id WHERE i.image IS NOT NULL",null).use { c ->
            while(c.moveToNext()) {
                val image=LegacyScreenshots.read(db,"capture_images","record_id",c.getLong(0),c.getInt(1))
                screenshots.put(imageOwner(c.getLong(0)),image,c.getLong(2),"HISTORY")
                db.execSQL("UPDATE capture_images SET image=NULL WHERE record_id=?",arrayOf(c.getLong(0)))
                migratedBytes+=image.size
            }
        }
        db.rawQuery("SELECT h.record_id,h.sequence,d.record_id FROM capture_history h LEFT JOIN record_deletions d ON d.record_id=h.record_id", null).use { c ->
            while(c.moveToNext()) screenshots.state(historyOwner(c.getLong(0),c.getInt(1)),if(c.isNull(2))"HISTORY" else "UNDO")
        }
        LegacyScreenshots.compact(db,migratedBytes)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE records (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE,
            source TEXT NOT NULL, origin TEXT NOT NULL, raw_text TEXT NOT NULL,
            received_at INTEGER NOT NULL, amount INTEGER, direction TEXT,
            merchant TEXT NOT NULL, category TEXT NOT NULL, occurred_at INTEGER NOT NULL,
            transaction_id TEXT, reliable INTEGER NOT NULL, time_explicit INTEGER NOT NULL,
            state TEXT NOT NULL, reason TEXT NOT NULL, duplicate_id INTEGER, note TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '',
            requires_reimbursement INTEGER NOT NULL DEFAULT 0 CHECK(requires_reimbursement IN (0,1)))""")
        db.execSQL("CREATE INDEX records_time ON records(occurred_at)")
        db.execSQL("CREATE INDEX records_transaction ON records(transaction_id)")
        db.execSQL("CREATE INDEX records_received ON records(received_at)")
        db.execSQL("CREATE TABLE capture_events (event_key TEXT PRIMARY KEY NOT NULL, received_at INTEGER NOT NULL)")
        createAccountsAndCategories(db)
        createCaptureImages(db)
        createMerchantIcons(db)
        createRefunds(db)
        createIdentitiesAndUndo(db)
        ReimbursementStore.createSchema(db)
        AccountPostings.create(db)
        AccountTransfers.createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<13)upgradeLegacy(db,oldVersion,13)
        check(oldVersion in 1..13 && newVersion==14) { "Unsupported database migration $oldVersion → $newVersion" }
        AccountPostings.create(db)
        AccountTransfers.createSchema(db)
    }

    private fun upgradeLegacy(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion in 1..12 && newVersion == 13) { "Unsupported database migration $oldVersion → $newVersion" }
        ReimbursementStore.createSchema(db)
        if (oldVersion >= 10) return
        db.execSQL("ALTER TABLE records ADD COLUMN requires_reimbursement INTEGER NOT NULL DEFAULT 0 CHECK(requires_reimbursement IN (0,1))")
        if(oldVersion==9) {
            // Exact label migration in SQLiteOpenHelper's transaction; financial fields never change.
            val marked=BillTags.toStorage(listOf("报销"))
            db.execSQL("UPDATE records SET requires_reimbursement=1 WHERE instr(tags,?)>0",arrayOf(marked))
            db.rawQuery("SELECT id,tags FROM records WHERE requires_reimbursement=1",null).use { c ->
                while(c.moveToNext())db.update("records",ContentValues().apply {
                    put("tags",BillTags.toStorage(BillTags.fromStorage(c.getString(1))-"报销"))
                },"id=?",arrayOf(c.getLong(0).toString()))
            }
            return
        }
        // Add metadata before legacy readers run; v8 requires no identity/history rebuild.
        db.execSQL("ALTER TABLE records ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        if(oldVersion==8)return
        // SQLiteOpenHelper wraps this migration in a transaction; existing bills remain untouched.
        if (oldVersion == 1) createAccountsAndCategories(db)
        if (oldVersion < 3) createCaptureImages(db)
        if (oldVersion < 5) {
            if (oldVersion == 4) db.execSQL("ALTER TABLE merchant_icons RENAME TO legacy_merchant_icons")
            createMerchantIcons(db)
            db.query("records", arrayOf("id", "merchant"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) linkMerchantIcon(db, cursor.getLong(0), cursor.getString(1), null)
            }
            if (oldVersion == 4) migratePreviewIcons(db)
        }
        if (oldVersion in 3..5) {
            db.execSQL("ALTER TABLE capture_images RENAME TO legacy_capture_images")
            createCaptureImages(db)
            db.execSQL("INSERT INTO capture_images(record_id,image) SELECT record_id,image FROM legacy_capture_images")
            db.execSQL("DROP TABLE legacy_capture_images")
        }
        createRefunds(db)
        db.execSQL("""INSERT OR IGNORE INTO capture_history(record_id,sequence,at,kind,amount,refunded,raw_text,refund_mode)
            SELECT id,1,received_at,'payment',amount,0,raw_text,'cumulative' FROM records""")
        createIdentitiesAndUndo(db)
        db.query("records",null,null,null,null,null,null).use { c -> while(c.moveToNext()) {
            val record=read(c)
            val captured=db.rawQuery("SELECT raw_text FROM capture_history WHERE record_id=?",arrayOf(record.id.toString())).use { h->
                buildList { while(h.moveToNext())addAll(TransactionIdentities.from(record.capture.copy(text=h.getString(0)),record.bill.copy(transactionId=null))) }
            }
            val recovered=(TransactionIdentities.from(record.capture,record.bill)+captured).distinct().groupBy { it.scope }.values.filter { it.size==1 }.flatten()
            persistIdentities(db,record.id,recovered)
        } }
    }

    private fun createIdentitiesAndUndo(db:SQLiteDatabase) {
        val columns=db.rawQuery("PRAGMA table_info(records)",null).use { c->buildSet { while(c.moveToNext())add(c.getString(1)) } }
        if("transaction_identities" !in columns)db.execSQL("ALTER TABLE records ADD COLUMN transaction_identities TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("CREATE TABLE IF NOT EXISTS record_identities(record_id INTEGER NOT NULL,scope TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(record_id,scope))")
        db.execSQL("CREATE INDEX IF NOT EXISTS identities_lookup ON record_identities(scope,value)")
        db.execSQL("CREATE TABLE IF NOT EXISTS record_deletions(record_id INTEGER PRIMARY KEY NOT NULL,expires_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_state_time ON records(state,occurred_at DESC,id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_time_id ON records(occurred_at DESC,id DESC)")
    }

    private fun identities(record:LedgerRecord)=(record.identities+TransactionIdentities.from(record.capture,record.bill)).distinct()
    private fun identitiesConflict(old:LedgerRecord,capture:Capture,bill:ParsedBill):Boolean {
        val incoming=TransactionIdentities.from(capture,bill)
        return old.identities.any { previous->incoming.any { it.scope==previous.scope && it.value!=previous.value } }
    }
    private fun primaryIssuerConflictsWithCapture(capture:Capture,bill:ParsedBill):Boolean {
        val id=bill.transactionId ?: return false
        if(TransactionIdentities.reservedPrimary(capture,id))return true
        val claimed=TransactionIdentities.from(capture,bill).filter { it.value==id }
        return claimed.isNotEmpty() && claimed.none { it.scope==DuplicateDetector.identityScope(capture) }
    }
    private fun persistIdentities(db:SQLiteDatabase,id:Long,ids:List<TransactionIdentity>) {
        val valid=ids.distinct();require(valid.groupBy { it.scope }.all { (_,items)->items.size==1 }) { "交易标识冲突" }
        db.delete("record_identities","record_id=?",arrayOf(id.toString()))
        valid.forEach { identity-> require(TransactionIdentities.valid(identity))
            db.insertOrThrow("record_identities",null,ContentValues().apply {
                put("record_id",id);put("scope",identity.scope);put("value",identity.value)
            })
        }
        db.update("records",ContentValues().apply { put("transaction_identities",TransactionIdentities.encode(valid)) },"id=?",arrayOf(id.toString()))
    }

    private fun identityMatch(db:SQLiteDatabase,capture:Capture,bill:ParsedBill):LedgerRecord? {
        val incoming=TransactionIdentities.from(capture,bill)
        if(incoming.isEmpty())return null
        val ids=incoming.flatMap { identity->db.rawQuery("SELECT record_id FROM record_identities WHERE scope=? AND value=?",arrayOf(identity.scope,identity.value))
            .use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } } }.distinct()
        if(ids.size!=1)return null
        val old=get(ids.single())?:return null
        if(old.state!=RecordState.POSTED || old.bill.direction!=bill.direction || (old.bill.refund?.originalAmountCents ?: old.bill.amountCents)!=bill.amountCents)return null
        val combined=(old.identities+incoming).distinct()
        if(combined.groupBy { it.scope }.any { (_,items)->items.size>1 })return null
        return old.copy(identities=combined)
    }

    /** Read-only resolution for feedback after an already-known capture. */
    fun matchingIdentity(capture:Capture,bill:ParsedBill):LedgerRecord? = identityMatch(readableDatabase,capture,bill)?.let { get(it.id) }

    private fun createRefunds(db: SQLiteDatabase) {
        val columns=db.rawQuery("PRAGMA table_info(records)",null).use { c -> buildSet { while(c.moveToNext())add(c.getString(1)) } }
        mapOf("refund_provider" to "TEXT","refund_original" to "INTEGER","refund_amount" to "INTEGER",
            "refund_mode" to "TEXT","refund_id" to "TEXT").forEach { (column,type) ->
            if(column !in columns)db.execSQL("ALTER TABLE records ADD COLUMN $column $type")
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS capture_history(record_id INTEGER NOT NULL,sequence INTEGER NOT NULL,
            at INTEGER NOT NULL,kind TEXT NOT NULL,amount INTEGER,refunded INTEGER NOT NULL,
            raw_text TEXT NOT NULL,refund_mode TEXT NOT NULL,refund_id TEXT,refund_amount INTEGER,
            PRIMARY KEY(record_id,sequence))""")
    }

    private fun createMerchantIcons(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE merchant_icons (record_id INTEGER PRIMARY KEY NOT NULL, merchant_key TEXT NOT NULL)")
        db.execSQL("CREATE INDEX merchant_icons_key ON merchant_icons(merchant_key)")
        db.execSQL("CREATE TABLE merchant_icon_assets (merchant_key TEXT PRIMARY KEY NOT NULL, image BLOB NOT NULL)")
    }

    private fun linkMerchantIcon(db: SQLiteDatabase, id: Long, merchant: String, image: ByteArray?) {
        val key = MerchantNames.key(merchant) ?: return
        // References also exist for bills without a crop, so a later icon serves earlier same-name bills.
        db.insertWithOnConflict("merchant_icons", null, ContentValues().apply {
            put("record_id", id); put("merchant_key", key)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (image != null) {
            val existing=db.query("merchant_icon_assets",arrayOf("image"),"merchant_key=?",arrayOf(key),null,null,null).use { if(it.moveToFirst())it.getBlob(0) else null }
            if(existing==null || !IconLocalization.usableThumbnail(existing) && IconLocalization.usableThumbnail(image))
                db.insertWithOnConflict("merchant_icon_assets", null, ContentValues().apply {
                    put("merchant_key", key); put("image", image)
                }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun iconKey(db: SQLiteDatabase, id: Long): String? = db.query("merchant_icons", arrayOf("merchant_key"),
        "record_id = ?", arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun pruneIcon(db: SQLiteDatabase, key: String?) {
        if (key != null) db.execSQL("DELETE FROM merchant_icon_assets WHERE merchant_key = ? AND NOT EXISTS " +
            "(SELECT 1 FROM merchant_icons WHERE merchant_key = ?)", arrayOf(key, key))
    }

    /** Enrich a proven payment without replacing financial fields or user-authored names.
     * A short OCR truncation is repairable only when its original raw line survives and
     * the new screenshot independently corroborates the longer display name. */
    private fun enrichPaymentPresentation(db:SQLiteDatabase,original:LedgerRecord,capture:Capture,bill:ParsedBill,image:ByteArray?):LedgerRecord {
        fun compact(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
            .filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT)
        val before=compact(original.bill.merchant);val after=compact(bill.merchant)
        val sameName=MerchantNames.key(bill.merchant)?.let { it==MerchantNames.key(original.bill.merchant) }==true
        val incomingLines=capture.text.lineSequence().map(::compact).filter(String::isNotBlank).toList()
        val sharedIdentity=original.identities.any { it in TransactionIdentities.from(capture,bill) }
        val originalEvidence=original.capture.text.lineSequence().map(::compact).any { it==before }
        val corroborated=incomingLines.count { it==after }>=2 ||
            incomingLines.any { it==after } && incomingLines.any { it.contains(after) && it.endsWith("公司") }
        val repair=!sameName && bill.reliable && sharedIdentity &&
            original.capture.source in setOf(Source.OCR,Source.IMAGE) && capture.source in setOf(Source.OCR,Source.IMAGE) &&
            (LocalOcrBillParser.isMerchantInterfaceText(before) ||
                before.length>=3 && after.length-before.length in 1..2 && after.startsWith(before)) &&
            originalEvidence && corroborated && MerchantNames.key(bill.merchant)!=null
        val result=if(repair) {
            check(db.update("records",ContentValues().apply { put("merchant",bill.merchant) },"id=?",arrayOf(original.id.toString()))==1)
            val previousKey=iconKey(db,original.id)
            linkMerchantIcon(db,original.id,bill.merchant,image?:merchantIcon(original.id))
            pruneIcon(db,previousKey)
            original.copy(bill=original.bill.copy(merchant=bill.merchant))
        }else original
        if(sameName)linkMerchantIcon(db,original.id,original.bill.merchant,image)
        return result
    }

    /** Internal pre-release v4 used one PNG per record; preserve these when upgrading a preview. */
    private fun migratePreviewIcons(db: SQLiteDatabase) {
        db.rawQuery("SELECT l.merchant_key, a.image FROM legacy_merchant_icons a JOIN merchant_icons l ON a.record_id = l.record_id ORDER BY a.record_id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val input = cursor.getBlob(1)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
                if (bounds.outWidth !in 1..128 || bounds.outHeight !in 1..128) continue
                val bitmap = BitmapFactory.decodeByteArray(input, 0, input.size) ?: continue
                try {
                    val image = MerchantIconEncoding.encode(bitmap) ?: continue
                    db.insertWithOnConflict("merchant_icon_assets", null, ContentValues().apply {
                        put("merchant_key", cursor.getString(0)); put("image", image)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                } finally { bitmap.recycle() }
            }
        }
        db.execSQL("DROP TABLE legacy_merchant_icons")
    }

    private fun createCaptureImages(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE capture_images (record_id INTEGER PRIMARY KEY NOT NULL, image BLOB)")
    }

    private fun createAccountsAndCategories(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL, description TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount >= 0),
            kind TEXT NOT NULL, type TEXT NOT NULL CHECK(type IN ('ASSET', 'LIABILITY')))""")
        db.execSQL("""CREATE TABLE custom_categories (
            name TEXT NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('EXPENSE', 'INCOME')),
            PRIMARY KEY(direction, name))""")
    }

    fun hasCaptureEvent(key:String):Boolean=readableDatabase.rawQuery("SELECT 1 FROM capture_events WHERE event_key=?",arrayOf(key)).use { it.moveToFirst() }

    @Synchronized fun rememberCaptureEvent(key:String) {
        writableDatabase.insertWithOnConflict("capture_events",null,ContentValues().apply {
            put("event_key",key);put("received_at",System.currentTimeMillis())
        },SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun ingest(capture: Capture, bill: ParsedBill, eventKey: String? = null, screenshot: ByteArray? = null, merchantIcon: ByteArray? = null, requireReview: Boolean = false): IngestResult {
        require(screenshot == null || screenshot.isNotEmpty() && screenshot.size <= 5 * 1024 * 1024) { "Invalid screenshot size" }
        require(merchantIcon == null || merchantIcon.isNotEmpty() && merchantIcon.size <= 128 * 1024) { "Invalid icon size" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val incomingIdentities=TransactionIdentities.from(capture,bill)
            val primaryIssuerConflict=primaryIssuerConflictsWithCapture(capture,bill)
            val recentlyDeleted=db.rawQuery("SELECT r.* FROM records r JOIN record_deletions d ON d.record_id=r.id",null).use { c->
                buildList { while(c.moveToNext())add(read(c)) }
            }.any { old ->
                old.bill.amountCents==bill.amountCents && old.bill.direction==bill.direction &&
                    (old.identities.any { it in incomingIdentities } ||
                        old.capture.origin==capture.origin && old.capture.text.filterNot(Char::isWhitespace)==capture.text.filterNot(Char::isWhitespace))
            }
            if(recentlyDeleted) { db.setTransactionSuccessful();return IngestResult.Skipped("账单已删除") }
            if (eventKey != null) {
                val replay = db.rawQuery("SELECT 1 FROM capture_events WHERE event_key = ?", arrayOf(eventKey)).use { it.moveToFirst() }
                if (replay) { db.setTransactionSuccessful(); return IngestResult.DuplicateSkipped }
                db.insertOrThrow("capture_events", null, ContentValues().apply {
                    put("event_key", eventKey); put("received_at", capture.receivedAt)
                })
                db.delete("capture_events", "received_at < ?", arrayOf((capture.receivedAt - 7 * 86_400_000L).toString()))
            }
            if (bill.refund != null) {
                val result = ingestRefund(db, capture, bill, screenshot, requireReview, merchantIcon)
                db.setTransactionSuccessful()
                return result
            }
            val identified=if(!requireReview && bill.reliable)identityMatch(db,capture,bill) else null
            if(identified!=null) {
                val enriched=identified.identities!=get(identified.id)?.identities
                persistIdentities(db,identified.id,identified.identities)
                val supplemented=appendPaymentScreenshot(db,identified,capture,screenshot)
                val presented=enrichPaymentPresentation(db,identified,capture,bill,merchantIcon)
                db.setTransactionSuccessful()
                return if(supplemented || enriched || presented!=identified) IngestResult.Saved(presented,supplemented=supplemented)
                    else IngestResult.DuplicateSkipped
            }
            val automatic = if(!requireReview) uniqueAutomaticPayment(capture,bill) else null
            if(automatic!=null) {
                val completed=completePaymentIdentity(db,automatic,bill)
                val supplemented=appendPaymentScreenshot(db,completed,capture,screenshot)
                linkMerchantIcon(db,completed.id,completed.bill.merchant,merchantIcon)
                db.setTransactionSuccessful()
                return IngestResult.Saved(completed,supplemented=supplemented)
            }
            val repeated=if(!requireReview && eventKey==null)uniqueRepeatedPayment(capture,bill) else null
            if(repeated!=null) {
                val supplemented=appendPaymentScreenshot(db,repeated,capture,screenshot)
                linkMerchantIcon(db,repeated.id,repeated.bill.merchant,merchantIcon)
                db.setTransactionSuccessful()
                return IngestResult.Saved(repeated,supplemented=supplemented)
            }
            // Reopening the original payment must never undo a later refund or add another expense.
            val refundedOriginals = if(bill.direction==Direction.EXPENSE && !primaryIssuerConflict)query(
                "state='POSTED' AND refund_provider IS NOT NULL AND refund_original=?",
                arrayOf(bill.amountCents?.toString() ?: "")).filter {
                    it.state == RecordState.POSTED && it.bill.refund != null &&
                        paymentProvider(it.capture) == paymentProvider(capture) &&
                        paymentProvider(capture) != null && it.bill.refund!!.originalAmountCents == bill.amountCents
                } else emptyList()
            val refundedOriginal = refundedOriginals.singleOrNull { old ->
                    (!bill.transactionId.isNullOrBlank() && old.bill.transactionId==bill.transactionId) ||
                        (screenshot!=null && screenshots.matches(imageOwner(old.id),screenshot) &&
                            (bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank() || old.bill.transactionId==bill.transactionId))
                }
            if (refundedOriginal != null) {
                val supplemented = bill.reliable && appendPaymentScreenshot(db, refundedOriginal, capture, screenshot)
                db.setTransactionSuccessful()
                return if (supplemented) IngestResult.Saved(refundedOriginal, supplemented=true) else IngestResult.DuplicateSkipped
            }
            // Without a transaction ID an equal amount/name/time is only a candidate: a new purchase
            // can look the same. Keep it outside totals until the user decides which payment it is.
            val refundedPaymentCandidates=refundedOriginals.filter { old ->
                (bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank()) &&
                    bill.timeExplicit && old.bill.timeExplicit && kotlin.math.abs(bill.occurredAt-old.bill.occurredAt)<=120_000 &&
                    MerchantNames.key(bill.merchant)?.let { it==MerchantNames.key(old.bill.merchant) }==true
            }
            val refundedPaymentCandidate=refundedPaymentCandidates.firstOrNull()
            val near = db.query("records", null,
                "NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id) AND amount=? AND COALESCE(direction,'')=? AND (transaction_id = ? OR occurred_at BETWEEN ? AND ? OR received_at BETWEEN ? AND ?)",
                arrayOf(bill.amountCents?.toString() ?: "",bill.direction?.name ?: "",bill.transactionId ?: "", (bill.occurredAt - 120_000).toString(),
                    (bill.occurredAt + 120_000).toString(), (capture.receivedAt - 30_000).toString(),
                    (capture.receivedAt + 30_000).toString()), null, null, "id DESC")
                .use { cursor -> buildList { while (cursor.moveToNext()) {
                    val old=read(cursor)
                    if(!(primaryIssuerConflict && old.bill.transactionId==bill.transactionId) &&
                        old.identities.none { oldId->incomingIdentities.any { it.scope==oldId.scope && it.value!=oldId.value } })add(old.existing())
                } } }
            val detected = DuplicateDetector.find(capture, bill, near)
            val identityOwners=incomingIdentities.flatMap { identity->db.rawQuery("SELECT record_id FROM record_identities WHERE scope=? AND value=?",arrayOf(identity.scope,identity.value))
                .use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } } }.distinct()
            // A shared alias with conflicting other issuer IDs is ambiguous, even when
            // the old single-ID detector could otherwise choose the first row.
            val duplicate=if(detected is Duplicate.Exact && identityOwners.size>1)Duplicate.Possible(detected.id) else detected
            if (duplicate is Duplicate.Exact) {
                val previous = requireNotNull(get(duplicate.id))
                val promoted=previous.state==RecordState.REVIEW && previous.duplicateId==null && previous.bill.refund==null &&
                    previous.capture.source in setOf(Source.OCR,Source.IMAGE) && !requireReview && bill.reliable
                val original=if(promoted)previous.copy(bill=bill,state=RecordState.POSTED,reason=bill.reason,duplicateId=null)
                    else enrichPaymentPresentation(db,previous,capture,bill,merchantIcon)
                if(promoted)check(db.update("records",values(original),"id=?",arrayOf(original.id.toString()))==1)
                if(promoted)AccountPostings.synchronize(db,original.id,assignDefault=true)
                if(promoted)linkMerchantIcon(db,original.id,original.bill.merchant,merchantIcon)
                persistIdentities(db,original.id,(original.identities+incomingIdentities).distinct())
                val supplemented = bill.reliable && appendPaymentScreenshot(db, original, capture, screenshot)
                db.setTransactionSuccessful()
                return if (supplemented || promoted || original!=previous) IngestResult.Saved(requireNotNull(get(original.id)), supplemented=supplemented) else IngestResult.DuplicateSkipped
            }
            val automaticCandidates=automaticPaymentCandidates(capture,bill)
            val automaticCandidate=automaticCandidates.firstOrNull { isAutomaticPayment(it) }
            val possible = duplicate as? Duplicate.Possible ?: refundedPaymentCandidate?.let { Duplicate.Possible(it.id) }
                ?: automaticCandidate?.let { Duplicate.Possible(it.id) }
            // An explicit shake is enrichment once duplicate evidence is found. It
            // must not turn the same payment into another review draft or debit.
            if(possible!=null && capture.source==Source.OCR && eventKey==null && screenshot!=null && !requireReview && bill.reliable) {
                // Approximate live-capture times may only be completed by uniqueAutomaticPayment.
                val candidates=(near.filter { DuplicateDetector.find(capture,bill,listOf(it))!=Duplicate.None }.mapNotNull { get(it.id) }+refundedPaymentCandidates)
                    .distinctBy { it.id }.filter { it.state==RecordState.POSTED && !identitiesConflict(it,capture,bill) &&
                        paymentProvider(it.capture)==paymentProvider(capture) && paymentProvider(capture)!=null &&
                        (bill.transactionId.isNullOrBlank() || it.bill.transactionId.isNullOrBlank() || bill.transactionId==it.bill.transactionId) }
                    .sortedBy { kotlin.math.abs(it.bill.occurredAt-bill.occurredAt) }
                if(candidates.isEmpty() && automaticCandidates.size>1) {
                    db.setTransactionSuccessful();return IngestResult.Skipped("已有多笔相近账单，未新增，请打开原账单补图")
                }
                if(candidates.isNotEmpty()) {
                val first=candidates.firstOrNull()
                val target=first?.takeIf { candidates.size==1 ||
                    kotlin.math.abs(it.bill.occurredAt-bill.occurredAt)<kotlin.math.abs(candidates[1].bill.occurredAt-bill.occurredAt) }
                if(target==null) { db.setTransactionSuccessful();return IngestResult.Skipped("已有多笔相近账单，未新增，请打开原账单补图") }
                // A later distinct payment must be distinguishable from this supplemented one.
                if(target.bill.transactionId.isNullOrBlank() && !bill.transactionId.isNullOrBlank())
                    check(db.update("records",ContentValues().apply { put("transaction_id",bill.transactionId) },"id=?",arrayOf(target.id.toString()))==1)
                persistIdentities(db,target.id,(target.identities+incomingIdentities).distinct())
                val supplemented=appendPaymentScreenshot(db,target,capture,screenshot)
                if(MerchantNames.key(target.bill.merchant)==MerchantNames.key(bill.merchant))linkMerchantIcon(db,target.id,target.bill.merchant,merchantIcon)
                db.setTransactionSuccessful()
                return IngestResult.Saved(requireNotNull(get(target.id)),supplemented=supplemented)
                }
            }
            val reason = if(refundedPaymentCandidate!=null) "疑似为已退款账单的原付款页面，请核对后保存"
                else if (possible != null) "疑似与已有账单重复，请核对后保存" else bill.reason
            val record = LedgerRecord(capture = capture, bill = bill.copy(reason = reason),
                state = if (!requireReview && bill.reliable && bill.amountCents != null && bill.direction != null && possible == null)
                    RecordState.POSTED else RecordState.REVIEW,
                reason = reason,
                duplicateId = possible?.id)
            val id = db.insertOrThrow("records", null, values(record))
            AccountPostings.synchronize(db,id,assignDefault=true)
            persistIdentities(db,id,identities(record))
            if (screenshot != null) {
                screenshots.put(imageOwner(id), screenshot, capture.receivedAt, "HISTORY")
                db.insertOrThrow("capture_images", null, ContentValues().apply { put("record_id", id) })
            }
            insertHistory(db, id, RecordCaptureHistory(1,capture.receivedAt,"payment",bill.amountCents,0,capture.text,screenshot != null))
            linkMerchantIcon(db, id, bill.merchant, merchantIcon)
            db.setTransactionSuccessful()
            return IngestResult.Saved(requireNotNull(get(id)))
        } finally { db.endTransaction() }
    }

    /** Without an identifier only the identical captured payload can supplement a payment.
     * Merchant/amount/minute alone can describe two genuine purchases. */
    private fun uniqueRepeatedPayment(capture:Capture,bill:ParsedBill):LedgerRecord? {
        if(!bill.reliable || bill.refund!=null || bill.reason.startsWith("已识别即时支付成功页") || capture.source !in setOf(Source.OCR,Source.IMAGE) ||
            bill.amountCents==null || bill.direction==null)return null
        val merchant=MerchantNames.key(bill.merchant)?:return null
        val provider=paymentProvider(capture)
        return query("state='POSTED' AND refund_provider IS NULL AND source IN ('OCR','IMAGE') AND amount=? AND direction=? AND received_at BETWEEN ? AND ?",
            arrayOf(bill.amountCents.toString(),bill.direction!!.name,(capture.receivedAt-120_000).toString(),(capture.receivedAt+120_000).toString())).filter { old ->
            val sameChannel=if(provider!=null)paymentProvider(old.capture)==provider else old.capture.origin==capture.origin
            val noConflictingId=bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank() || bill.transactionId==old.bill.transactionId
            val sameTime=old.capture.text.filterNot(Char::isWhitespace)==capture.text.filterNot(Char::isWhitespace) &&
                kotlin.math.abs(capture.receivedAt-old.capture.receivedAt)<=120_000
            old.state==RecordState.POSTED && old.bill.refund==null && old.capture.source in setOf(Source.OCR,Source.IMAGE) && sameChannel && noConflictingId && !identitiesConflict(old,capture,bill) &&
                (bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank()) &&
                old.bill.amountCents==bill.amountCents && old.bill.direction==bill.direction &&
                MerchantNames.key(old.bill.merchant)==merchant && sameTime
        }.singleOrNull()
    }

    /** Upgrade only earlier linked duplicate drafts, retaining all evidence on the posted payment. */
    @Synchronized fun reconcilePendingDuplicates():Map<Long,Long> {
        val merged=mutableMapOf<Long,Long>()
        // Older shopping captures did not populate order IDs. Recover only explicit
        // labelled identity from stored OCR; never infer identity from amount/name.
        val db=writableDatabase
        // A narrow projection also handles Unicode-blank legacy IDs exactly as Kotlin
        // does, without decoding OCR text for the already-identified history.
        val unidentified=db.rawQuery("SELECT id,transaction_id FROM records WHERE source IN ('OCR','IMAGE') AND refund_provider IS NULL "+
            "AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)",null).use { c ->
            buildList { while(c.moveToNext())if(c.isNull(1) || c.getString(1).isBlank())add(c.getLong(0)) }
        }
        for(recordId in unidentified) {
            val record=get(recordId)?:continue
            val texts=(listOf(record.capture.text)+captureHistory(record.id).map { it.rawText }).distinct()
            val id=texts.mapNotNull { raw -> LocalOcrBillParser.transactionId(raw.lines().map { LocalOcrLine(it) }) }.distinct().singleOrNull()?:continue
            db.update("records",ContentValues().apply { put("transaction_id",id) },"id=?",arrayOf(record.id.toString()))
            persistIdentities(db,record.id,(record.identities+TransactionIdentities.from(record.capture,record.bill.copy(transactionId=id))).distinct())
        }
        // Only IDs repeated with the same financial fields can belong to a duplicate group.
        // Scope, conflicting identities and user-note checks remain in Kotlin below.
        val groups=query("state='POSTED' AND refund_provider IS NULL AND source IN ('OCR','IMAGE') AND transaction_id IN ("+
            "SELECT transaction_id FROM records WHERE state='POSTED' AND refund_provider IS NULL AND source IN ('OCR','IMAGE') "+
            "AND amount IS NOT NULL AND direction IS NOT NULL AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id) "+
            "GROUP BY transaction_id,amount,direction HAVING COUNT(*)>1)",null).filter { it.state==RecordState.POSTED && it.bill.refund==null &&
            !primaryIssuerConflictsWithCapture(it.capture,it.bill) &&
            it.capture.source in setOf(Source.OCR,Source.IMAGE) && !it.bill.transactionId.isNullOrBlank() &&
            it.bill.amountCents!=null && it.bill.direction!=null && DuplicateDetector.identityScope(it.capture)!=null }
            .groupBy { listOf(DuplicateDetector.identityScope(it.capture),it.bill.transactionId,it.bill.amountCents,it.bill.direction) }
        for(copies in groups.values.filter { it.size>1 }) {
            val ordered=copies.sortedWith(compareBy<LedgerRecord> { it.capture.receivedAt }.thenBy { it.id })
            val original=ordered.first()
            for(copy in ordered.drop(1)) {
                // Conflicting user notes require an explicit edit; don't discard one.
                val current=get(original.id)?:continue
                if(current.note.isNotBlank() && copy.note.isNotBlank() && current.note!=copy.note)continue
                db.beginTransaction()
                try {
                    val entries=captureHistory(copy.id)
                    if(entries.isEmpty())appendPaymentScreenshot(db,current,copy.capture,screenshot(copy.id))
                    else entries.forEach { entry -> appendPaymentScreenshot(db,current,copy.capture.copy(text=entry.rawText,receivedAt=entry.at),historyScreenshot(copy.id,entry.sequence)) }
                    if(current.note.isBlank() && copy.note.isNotBlank())db.update("records",ContentValues().apply { put("note",copy.note) },"id=?",arrayOf(current.id.toString()))
                    if(MerchantNames.key(current.bill.merchant)==MerchantNames.key(copy.bill.merchant))linkMerchantIcon(db,current.id,current.bill.merchant,merchantIcon(copy.id))
                    delete(copy.id);merged[copy.id]=current.id
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
        for(pending in query("state='REVIEW' AND duplicate_id IS NOT NULL AND reason LIKE '疑似与已有账单重复%'",null)) {
            val original=uniqueRepeatedPayment(pending.capture,pending.bill)?:continue
            val db=writableDatabase;db.beginTransaction()
            try {
                val entries=captureHistory(pending.id)
                if(entries.isEmpty())appendPaymentScreenshot(db,original,pending.capture,screenshot(pending.id))
                else entries.forEach { entry -> appendPaymentScreenshot(db,original,pending.capture.copy(text=entry.rawText,receivedAt=entry.at),historyScreenshot(pending.id,entry.sequence)) }
                linkMerchantIcon(db,original.id,original.bill.merchant,merchantIcon(pending.id))
                delete(pending.id);merged[pending.id]=original.id
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return merged
    }

    @Synchronized fun repairMerchantLabel(id:Long,recognized:ParsedBill,icon:ByteArray?):Boolean {
        val old=get(id)?:return false
        if(old.capture.source !in setOf(Source.OCR,Source.IMAGE) || !LocalOcrBillParser.isMerchantInterfaceText(old.bill.merchant) ||
            !recognized.reliable || recognized.amountCents!=old.bill.amountCents || recognized.direction!=old.bill.direction ||
            LocalOcrBillParser.isMerchantInterfaceText(recognized.merchant) || MerchantNames.key(recognized.merchant)==null)return false
        if(old.bill.merchant !in setOf("全称","名称") && old.capture.text.lineSequence().none {
            it.filterNot(Char::isWhitespace)==old.bill.merchant.filterNot(Char::isWhitespace)
        })return false
        val db=writableDatabase;db.beginTransaction()
        try {
            db.update("records",ContentValues().apply { put("merchant",recognized.merchant) },"id=?",arrayOf(id.toString()))
            val previousKey=iconKey(db,id)
            linkMerchantIcon(db,id,recognized.merchant,icon);pruneIcon(db,previousKey);db.setTransactionSuccessful();return true
        } finally { db.endTransaction() }
    }

    fun merchantLabelRepairCandidates():List<LedgerRecord> = query("source IN ('OCR','IMAGE') AND " +
        "(merchant IN ('全称','名称') OR merchant LIKE '%门店%' OR merchant LIKE '%人喜欢%' OR merchant LIKE '%人收藏%' OR merchant LIKE '%人关注%')",null)
        .filter { LocalOcrBillParser.isMerchantInterfaceText(it.bill.merchant) }

    @Synchronized fun discardStandaloneRefundReceipts():List<Long> = readableDatabase.rawQuery(
        "SELECT id,raw_text FROM records WHERE source IN ('OCR','IMAGE') AND refund_provider IS NULL "+
            "AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id) ORDER BY occurred_at DESC,id DESC",null).use { c ->
        buildList { while(c.moveToNext())if(StandaloneRefundReceipt.matches(c.getString(1)))add(c.getLong(0)) }
    }.also { ids -> ids.forEach(::delete) }

    /** Withdraw only untouched machine drafts that the recognition gate now rejects. */
    @Synchronized fun discardRejectedDrafts():Map<Long,String> = query("state='REVIEW' AND source IN ('OCR','IMAGE')",null).filter {
        it.state==RecordState.REVIEW && it.note.isBlank() &&
            it.capture.source in setOf(Source.OCR,Source.IMAGE)
    }.mapNotNull { record -> TransactionRejection.reason(record.capture.text)?.let { record.id to it } }
        .toMap().also { drafts -> drafts.keys.forEach(::delete) }

    @Synchronized
    fun saveEdited(record: LedgerRecord, selectedAccountUid:String?=null, accountSelectionSpecified:Boolean=false): LedgerRecord {
        BillTags.validate(record.tags)
        require(BillTags.LEGACY_REIMBURSEMENT !in record.tags) { "请使用是否报销属性，不要创建报销标签" }
        val amount = record.bill.amountCents
        val previous=record.id.takeIf { it!=0L }?.let(::get)
        require(previous == null || previous.uid == record.uid) { "账单标识不能修改" }
        require(previous==null && record.accountUid==null || previous!=null && record.accountUid==previous.accountUid) { "请通过账户选择更改关联" }
        require(record.bill.refund == null || record.id != 0L && previous?.state == RecordState.POSTED) { "请通过关联原账单确认退款" }
        require(amount != null && (amount > 0 || amount == 0L && validRefundSummary(record.bill))) { "请输入有效金额" }
        require(record.bill.refund == null || validRefundSummary(record.bill)) { "退款账单金额必须等于原付金额减累计退款" }
        if (record.id != 0L) {
            require(previous?.bill?.refund == null || record.bill.refund == previous.bill.refund && amount == previous.bill.amountCents) { "退款账单请保留实付金额" }
        }
        require(amount <= Money.MAX_CENTS) { "金额过大" }
        require(record.bill.direction != null) { "请选择收入或支出" }
        // Category/note edits must not erase the native success-page provenance used by later details.
        val provenance=previous?.takeIf { isAutomaticPayment(it) && it.bill.amountCents==record.bill.amountCents &&
            it.bill.direction==record.bill.direction && it.bill.merchant==record.bill.merchant &&
            it.bill.occurredAt==record.bill.occurredAt && it.bill.timeExplicit==record.bill.timeExplicit }?.reason.orEmpty()
        val posted = record.copy(accountEffect=previous?.accountEffect ?: 0,identities=previous?.identities ?: record.identities,bill = record.bill.copy(reliable = true, reason = provenance),
            state = RecordState.POSTED, reason = provenance, duplicateId = null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previousKey = if (record.id == 0L) null else iconKey(db, record.id)
            val id = if (record.id == 0L) db.insertOrThrow("records", null, values(posted)) else {
                check(previous!=null && db.update("records", values(posted),
                    "id = ? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)", arrayOf(record.id.toString())) == 1) { "账单已被删除" }
                record.id
            }
            if(accountSelectionSpecified)AccountPostings.select(db,id,selectedAccountUid)
            else AccountPostings.synchronize(db,id,assignDefault=previous==null || previous.state!=RecordState.POSTED)
            persistIdentities(db,id,identities(posted))
            if (record.id == 0L) insertHistory(db,id,RecordCaptureHistory(1,record.capture.receivedAt,"payment",amount,0,record.capture.text,false))
            db.delete("merchant_icons", "record_id = ?", arrayOf(id.toString()))
            linkMerchantIcon(db, id, posted.bill.merchant, null)
            pruneIcon(db, previousKey)
            ReimbursementStore.validateStoredRelationships(db)
            db.setTransactionSuccessful()
            return requireNotNull(get(id))
        } finally { db.endTransaction() }
    }

    /** Tag changes preserve category, financial fields and any other bill tags. */
    @Synchronized fun setTag(id:Long,uid:String,tag:String,enabled:Boolean):LedgerRecord {
        BillTags.validate(listOf(tag))
        require(tag!=BillTags.LEGACY_REIMBURSEMENT) { "请使用是否报销属性" }
        val record=requireNotNull(get(id)) { "账单已不存在" }
        require(record.uid==uid && record.state==RecordState.POSTED) { "账单已变更，请重新打开" }
        val tags=if(enabled)(record.tags+tag).distinct() else record.tags-tag
        check(writableDatabase.update("records",ContentValues().apply { put("tags",BillTags.toStorage(tags)) },
            "id=? AND uid=?",arrayOf(id.toString(),uid))==1)
        return requireNotNull(get(id))
    }

    /** Independent metadata: category, amounts, refund history and custom labels stay unchanged. */
    @Synchronized fun setRequiresReimbursement(id:Long,uid:String,enabled:Boolean):LedgerRecord {
        val record=requireNotNull(get(id)) { "账单已不存在" }
        require(record.uid==uid && record.state==RecordState.POSTED) { "账单已变更，请重新打开" }
        check(writableDatabase.update("records",ContentValues().apply { put("requires_reimbursement",if(enabled)1 else 0) },
            "id=? AND uid=? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)",arrayOf(id.toString(),uid))==1)
        return requireNotNull(get(id))
    }

    fun get(id: Long): LedgerRecord? = readableDatabase.query("records", null, "id = ? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)",
        arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) read(it) else null }

    fun captureHistory(recordId: Long): List<RecordCaptureHistory> = readableDatabase.query("capture_history", null,
        "record_id=?",arrayOf(recordId.toString()),null,null,"sequence ASC").use { c -> buildList {
        while(c.moveToNext()) {
            fun text(key:String)=c.getString(c.getColumnIndexOrThrow(key))
            fun number(key:String)=c.getLong(c.getColumnIndexOrThrow(key))
            fun optional(key:String):Long?=c.getColumnIndexOrThrow(key).let { if(c.isNull(it))null else c.getLong(it) }
            val sequence=number("sequence").toInt()
            add(RecordCaptureHistory(sequence,number("at"),text("kind"),optional("amount"),number("refunded"),text("raw_text"),
                screenshots.contains(historyOwner(recordId,sequence)),text("refund_mode"),text("refund_id"),optional("refund_amount")))
        }
    } }

    fun historyScreenshot(recordId: Long, sequence: Int): ByteArray? {
        val exists=readableDatabase.rawQuery("SELECT 1 FROM capture_history WHERE record_id=? AND sequence=?",
            arrayOf(recordId.toString(),sequence.toString())).use { it.moveToFirst() }
        if(!exists)return null
        return screenshots.image(historyOwner(recordId,sequence))
    }

    /** Lightweight change tokens; the home screen never needs every OCR transcript. */
    fun historyRevisions(availableOwners: Set<String>,recordIds:Set<Long>?=null): Map<Long,String> = readableDatabase.rawQuery(
        "SELECT record_id,sequence,at FROM capture_history"+idFilter(recordIds)+" ORDER BY record_id,sequence",null).use { cursor ->
        val values=linkedMapOf<Long,LongArray>()
        while(cursor.moveToNext()) {
            val id=cursor.getLong(0);val sequence=cursor.getInt(1)
            val summary=values.getOrPut(id) { longArrayOf(0,0,0,0) }
            summary[0]++;summary[1]=sequence.toLong();summary[2]=maxOf(summary[2],cursor.getLong(2))
            if(historyOwner(id,sequence) in availableOwners)summary[3]++
        }
        values.mapValues { it.value.joinToString(":") }
    }

    private fun idFilter(ids:Set<Long>?,column:String="record_id"):String {
        if(ids==null)return ""
        require(ids.all { it>0 })
        return if(ids.isEmpty())" WHERE 0" else " WHERE $column IN (${ids.joinToString(",")})"
    }

    fun availableScreenshotOwners(recordIds:Set<Long>):Set<String> {
        if(recordIds.isEmpty())return emptySet()
        val owners=recordIds.mapTo(mutableSetOf(),::imageOwner)
        readableDatabase.rawQuery("SELECT record_id,sequence FROM capture_history"+idFilter(recordIds),null).use { c->
            while(c.moveToNext())owners.add(historyOwner(c.getLong(0),c.getInt(1)))
        }
        return screenshots.availableOwners(owners)
    }

    /** Full history for rendering: avoid a separate history and screenshot query for each bill. */
    fun captureHistories(availableOwners: Set<String> = screenshots.availableOwners()): Map<Long,List<RecordCaptureHistory>> =
        readableDatabase.query("capture_history",null,null,null,null,null,"record_id ASC, sequence ASC").use { c ->
            val grouped=linkedMapOf<Long,MutableList<RecordCaptureHistory>>()
            fun text(key:String)=c.getString(c.getColumnIndexOrThrow(key))
            fun number(key:String)=c.getLong(c.getColumnIndexOrThrow(key))
            fun optional(key:String):Long?=c.getColumnIndexOrThrow(key).let { if(c.isNull(it))null else c.getLong(it) }
            while(c.moveToNext()) {
                val id=number("record_id");val sequence=number("sequence").toInt()
                grouped.getOrPut(id) { mutableListOf() }.add(RecordCaptureHistory(sequence,number("at"),text("kind"),
                    optional("amount"),number("refunded"),text("raw_text"),historyOwner(id,sequence) in availableOwners,
                    text("refund_mode"),text("refund_id"),optional("refund_amount")))
            }
            grouped
        }

    private fun insertHistory(db: SQLiteDatabase, id: Long, entry: RecordCaptureHistory) {
        db.insertOrThrow("capture_history",null,ContentValues().apply {
            put("record_id",id);put("sequence",entry.sequence);put("at",entry.at);put("kind",entry.kind)
            put("amount",entry.amountCents);put("refunded",entry.refundedCents);put("raw_text",entry.rawText)
            put("refund_mode",entry.refundMode);put("refund_id",entry.refundId);put("refund_amount",entry.refundAmountCents)
        })
    }

    /** Identity has already been checked by the caller. Evidence cannot change financial fields. */
    private fun appendPaymentScreenshot(db: SQLiteDatabase, original: LedgerRecord, capture: Capture, image: ByteArray?, kind: String = "payment_supplement"): Boolean {
        if (image == null || capture.source !in setOf(Source.OCR, Source.IMAGE)) return false
        val history = captureHistory(original.id)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        if (screenshots.fingerprint(imageOwner(original.id)) == fingerprint || history.any {
                screenshots.fingerprint(historyOwner(original.id,it.sequence)) == fingerprint
            }) return false
        if (history.isEmpty()) insertHistory(db,original.id,RecordCaptureHistory(1,original.capture.receivedAt,
            "payment",original.bill.amountCents,0,original.capture.text,screenshots.contains(imageOwner(original.id))))
        val sequence = (history.maxOfOrNull { it.sequence } ?: 1) + 1
        val owner = historyOwner(original.id,sequence)
        screenshots.put(owner,image,capture.receivedAt,"HISTORY")
        try {
            insertHistory(db,original.id,RecordCaptureHistory(sequence,capture.receivedAt,kind,
                original.bill.amountCents,original.bill.refund?.refundedCents ?: 0,capture.text,true))
        } catch (error: Throwable) {
            screenshots.release(owner)
            throw error
        }
        return true
    }

    /** Backup restore supplies only newly imported UIDs. Existing evidence is immutable. */
    @Synchronized fun restoreHistory(recordId:Long, entries:List<RestoredCaptureHistory>) =
        restoreHistory(recordId,entries) { it.image }

    /** ZIP restore supplies metadata first and loads at most one attachment for each insertion. */
    @Synchronized fun restoreHistory(recordId:Long, entries:List<RestoredCaptureHistory>,
                                    imageProvider:(RestoredCaptureHistory)->ByteArray?) {
        val record=requireNotNull(get(recordId)) { "账单已不存在" }
        LedgerBackup.validateHistory(record,entries)
        val db=writableDatabase
        val newImageOwners=mutableListOf<String>()
        db.beginTransaction()
        try {
            val existing=captureHistory(recordId).map { it.sequence }.toSet()
            entries.filter { it.entry.sequence !in existing }.forEach { restored ->
                val image=imageProvider(restored)
                require(image==null || image.isNotEmpty() && image.size<=5*1024*1024)
                image?.let {
                    val owner=historyOwner(recordId,restored.entry.sequence)
                    if(!screenshots.contains(owner)) {
                        screenshots.put(owner,image,restored.entry.at,"HISTORY")
                        newImageOwners.add(owner)
                    }
                }
                insertHistory(db,recordId,restored.entry)
                if(restored.entry.sequence==1 && image!=null)db.insertWithOnConflict("capture_images",null,
                    ContentValues().apply { put("record_id",recordId) },SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } catch(error:Throwable) {
            newImageOwners.forEach(screenshots::release)
            throw error
        } finally { db.endTransaction() }
    }

    private fun refundIdentityMatches(original:LedgerRecord,incoming:ParsedBill):Boolean {
        val provider=incoming.refund?.provider ?: return false
        val id=incoming.transactionId?.takeIf(String::isNotBlank) ?: return false
        return original.identities.any { it.scope==provider && it.value==id } ||
            paymentProvider(original.capture)==provider && original.bill.transactionId==id
    }

    /** SQL only narrows candidates; provider and conflict checks remain authoritative. */
    private fun postedRefundIdentityCandidates(bill:ParsedBill):List<LedgerRecord> {
        val id=bill.transactionId?.takeIf(String::isNotBlank) ?: return emptyList()
        val provider=bill.refund?.provider ?: return emptyList()
        return query("state='POSTED' AND (transaction_id=? OR EXISTS(SELECT 1 FROM record_identities i "+
            "WHERE i.record_id=records.id AND i.scope=? AND i.value=?))",arrayOf(id,provider,id))
    }

    private fun refundAmountCandidates(bill:ParsedBill):List<LedgerRecord> {
        val refund=bill.refund ?: return emptyList()
        // selectionArgs are strings; CASE has no column affinity, so compare an integer parameter.
        return query("state='POSTED' AND direction='EXPENSE' AND "+
            "(CASE WHEN refund_provider IS NULL THEN amount ELSE refund_original END)=CAST(? AS INTEGER) AND "+
            "(occurred_at BETWEEN ? AND ? OR transaction_id=? OR EXISTS(SELECT 1 FROM record_identities i "+
            "WHERE i.record_id=records.id AND i.scope=? AND i.value=?))",
            arrayOf(refund.originalAmountCents.toString(),(bill.occurredAt-120_000).toString(),(bill.occurredAt+120_000).toString(),
                bill.transactionId ?: "",refund.provider,bill.transactionId ?: ""))
    }

    private fun compatibleRefund(original:LedgerRecord, incoming:ParsedBill):Boolean {
        val refund=incoming.refund ?: return false
        return original.state==RecordState.POSTED && original.bill.direction==Direction.EXPENSE &&
            (paymentProvider(original.capture)==refund.provider || refundIdentityMatches(original,incoming)) &&
            (original.bill.refund?.originalAmountCents ?: original.bill.amountCents)==refund.originalAmountCents &&
            (original.bill.refund==null || validRefundSummary(original.bill)) &&
            (original.bill.transactionId.isNullOrBlank() || incoming.transactionId.isNullOrBlank() || refundIdentityMatches(original,incoming))
    }

    private fun isAutomaticPayment(record:LedgerRecord):Boolean = record.state==RecordState.POSTED &&
        record.capture.source==Source.OCR && !record.bill.timeExplicit && record.bill.transactionId.isNullOrBlank() &&
        record.reason.startsWith("已识别即时支付成功页") &&
        kotlin.math.abs(record.capture.receivedAt-record.bill.occurredAt)<=15_000

    /** Only a unique native live capture can gain identity from an explicitly timed order detail. */
    private fun uniqueAutomaticPayment(capture:Capture,bill:ParsedBill):LedgerRecord? {
        val provider=paymentProvider(capture) ?: return null
        if(!bill.transactionId.isNullOrBlank() && query("state='POSTED' AND transaction_id=?",arrayOf(bill.transactionId!!)).any { it.state==RecordState.POSTED && paymentProvider(it.capture)==provider &&
                it.bill.transactionId==bill.transactionId })return null
        // Count even candidates with conflicting IDs; ambiguity must never pick an arbitrary payment.
        return automaticPaymentCandidates(capture,bill).singleOrNull()?.takeIf {
            isAutomaticPayment(it) && it.bill.refund==null && it.bill.occurredAt-bill.occurredAt in -5_000L..120_000L
        }
    }

    private fun automaticPaymentCandidates(capture:Capture,bill:ParsedBill):List<LedgerRecord> {
        if(!bill.reliable || !bill.timeExplicit || bill.transactionId.isNullOrBlank() ||
            capture.source !in setOf(Source.OCR,Source.IMAGE) || bill.direction!=Direction.EXPENSE || primaryIssuerConflictsWithCapture(capture,bill))return emptyList()
        val provider=paymentProvider(capture) ?: return emptyList()
        if(bill.refund?.provider?.let { it!=provider }==true)return emptyList()
        val merchant=MerchantNames.key(bill.merchant) ?: return emptyList()
        val gross=bill.refund?.originalAmountCents ?: bill.amountCents ?: return emptyList()
        return query("state='POSTED' AND direction='EXPENSE' AND "+
            "(CASE WHEN refund_provider IS NULL THEN amount ELSE refund_original END)=CAST(? AS INTEGER) AND occurred_at BETWEEN ? AND ?",
            arrayOf(gross.toString(),(bill.occurredAt-120_000).toString(),(bill.occurredAt+120_000).toString())).filter { old -> old.state==RecordState.POSTED && old.bill.direction==Direction.EXPENSE &&
            paymentProvider(old.capture)==provider && !identitiesConflict(old,capture,bill) && (old.bill.refund?.originalAmountCents ?: old.bill.amountCents)==gross &&
            MerchantNames.key(old.bill.merchant)==merchant && kotlin.math.abs(old.bill.occurredAt-bill.occurredAt)<=120_000 }
    }

    private fun completePaymentIdentity(db:SQLiteDatabase,original:LedgerRecord,bill:ParsedBill):LedgerRecord {
        val completed=original.copy(bill=original.bill.copy(transactionId=bill.transactionId,
            occurredAt=bill.occurredAt,timeExplicit=true,reason=""),reason="")
        check(db.update("records",values(completed),"id=?",arrayOf(original.id.toString()))==1)
        persistIdentities(db,original.id,(original.identities+TransactionIdentities.from(original.capture,bill)).distinct())
        return requireNotNull(get(completed.id))
    }

    private fun matchingRefundCandidates(capture:Capture,bill:ParsedBill):List<LedgerRecord> {
        if(!validRefundEvidence(bill) || paymentProvider(capture)?.let { it!=bill.refund?.provider }==true)return emptyList()
        val merchant=MerchantNames.key(bill.merchant)
        return refundAmountCandidates(bill).filter { candidate -> compatibleRefund(candidate,bill) &&
            (refundIdentityMatches(candidate,bill) ||
                merchant!=null && merchant==MerchantNames.key(candidate.bill.merchant) &&
                    kotlin.math.abs(candidate.bill.occurredAt-bill.occurredAt)<=120_000) }
    }

    fun refundCandidates(pendingId:Long):List<LedgerRecord> {
        val pending=get(pendingId) ?: return emptyList()
        if(pending.state!=RecordState.REVIEW || !validRefundEvidence(pending.bill))return emptyList()
        return matchingRefundCandidates(pending.capture,pending.bill).filter { it.id!=pendingId }
    }

    @Synchronized fun confirmRefund(pendingId:Long,targetId:Long):IngestResult.Saved {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val pending=requireNotNull(get(pendingId)) { "待确认退款已不存在" }
            require(pending.state==RecordState.REVIEW && validRefundEvidence(pending.bill)) { "退款证据不完整，请重新识别" }
            require(paymentProvider(pending.capture)?.let { it==pending.bill.refund?.provider }!=false) { "退款平台与截图来源冲突，请重新识别" }
            require(refundCandidates(pendingId).any { it.id==targetId }) { "原账单已变更或与退款不匹配，请重新选择" }
            val original=requireNotNull(get(targetId))
            val reason=refundConflict(original,pending.bill)
            require(reason==null) { reason ?: "退款状态冲突" }
            val result=applyRefund(db,original,pending.capture,pending.bill,screenshot(pendingId))
            delete(pendingId)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun ingestRefund(db:SQLiteDatabase,capture:Capture,bill:ParsedBill,image:ByteArray?,requireReview:Boolean,merchantIcon:ByteArray?=null):IngestResult {
        val refund=requireNotNull(bill.refund)
        val exact=if(!bill.transactionId.isNullOrBlank())postedRefundIdentityCandidates(bill).filter {
            compatibleRefund(it,bill) && refundIdentityMatches(it,bill)
        } else emptyList()
        val target=exact.singleOrNull() ?: if(exact.isEmpty())uniqueAutomaticPayment(capture,bill) else null
        val conflict=target?.let { refundConflict(it,bill) }
        val providerConflict=paymentProvider(capture)?.let { it!=refund.provider }==true
        if(!requireReview && bill.reliable && validRefundEvidence(bill) && !providerConflict && target!=null && conflict==null) {
            val original=if(isAutomaticPayment(target))completePaymentIdentity(db,target,bill) else target
            val result=applyRefund(db,original,capture,bill,image)
            if(MerchantNames.key(original.bill.merchant)==MerchantNames.key(bill.merchant))
                linkMerchantIcon(db,original.id,original.bill.merchant,merchantIcon)
            return result
        }
        val candidates=matchingRefundCandidates(capture,bill)
        val identityConflict=!bill.transactionId.isNullOrBlank() && postedRefundIdentityCandidates(bill).any {
            it.state==RecordState.POSTED && refundIdentityMatches(it,bill)
        }
        if(!requireReview && canCreateNetRefund(capture,bill))
            return insertNetRefund(db,capture,bill,image,merchantIcon)
        val reason=when {
            !validRefundEvidence(bill) -> "退款金额或口径不完整，请重新识别成功退款详情"
            providerConflict -> "退款平台与截图来源冲突，请重新识别"
            conflict!=null -> conflict
            !bill.reliable && bill.reason.isNotBlank() -> bill.reason
            candidates.isNotEmpty() -> "发现可能对应的原支出，请核对后更新"
            identityConflict -> "原交易单号对应的金额或状态冲突，请重新核对"
            refund.mode=="single" -> "只有单次退款信息，暂无法确定最终支出，请打开完整付款详情重新识别"
            else -> "付款与退款信息不完整，请打开完整付款详情重新识别"
        }
        val pending=LedgerRecord(capture=capture,bill=bill.copy(reason=reason),state=RecordState.REVIEW,reason=reason)
        val id=db.insertOrThrow("records",null,values(pending))
        persistIdentities(db,id,identities(pending))
        image?.let {
            screenshots.put(imageOwner(id),it,capture.receivedAt,"HISTORY")
            db.insertOrThrow("capture_images",null,ContentValues().apply { put("record_id",id) })
        }
        insertHistory(db,id,RecordCaptureHistory(1,capture.receivedAt,"refund_pending",bill.amountCents,refund.refundedCents,capture.text,image!=null,
            refund.mode,refund.refundId,refund.refundedCents))
        return IngestResult.Saved(requireNotNull(get(id)))
    }

    private fun canCreateNetRefund(capture:Capture,bill:ParsedBill):Boolean {
        val refund=bill.refund ?: return false
        return bill.reliable && bill.timeExplicit && !bill.transactionId.isNullOrBlank() && refund.refundedCents>0 && validRefundSummary(bill) &&
            paymentProvider(capture)?.let { it==refund.provider }!=false && matchingRefundCandidates(capture,bill).isEmpty() &&
            postedRefundIdentityCandidates(bill).none { it.state==RecordState.POSTED && refundIdentityMatches(it,bill) }
    }

    private fun insertNetRefund(db:SQLiteDatabase,capture:Capture,bill:ParsedBill,image:ByteArray?,merchantIcon:ByteArray?):IngestResult.Saved {
        val refund=requireNotNull(bill.refund)
        require(refund.mode=="cumulative" && validRefundSummary(bill))
        val record=LedgerRecord(capture=capture,bill=bill.copy(reason=""),state=RecordState.POSTED,reason="")
        val id=db.insertOrThrow("records",null,values(record))
        AccountPostings.synchronize(db,id,assignDefault=true)
        persistIdentities(db,id,identities(record))
        try {
            image?.let {
                screenshots.put(imageOwner(id),it,capture.receivedAt,"HISTORY")
                db.insertOrThrow("capture_images",null,ContentValues().apply { put("record_id",id) })
            }
            insertHistory(db,id,RecordCaptureHistory(1,capture.receivedAt,
                if(refund.refundedCents==refund.originalAmountCents)"full_refund" else "partial_refund",
                bill.amountCents,refund.refundedCents,capture.text,image!=null,"cumulative",null,refund.refundedCents))
            linkMerchantIcon(db,id,bill.merchant,merchantIcon)
            return IngestResult.Saved(requireNotNull(get(id)))
        } catch(error:Throwable) { screenshots.release(imageOwner(id));throw error }
    }

    /** Repair old staged refunds only where the same current ingestion checks can settle them. */
    @Synchronized fun reconcilePendingRefunds():Map<Long,Long> {
        val resolved=mutableMapOf<Long,Long>()
        for(pending in query("state='REVIEW' AND refund_provider IS NOT NULL AND reliable=1 AND time_explicit=1",null).filter { it.bill.refund!=null && it.bill.reliable && it.bill.timeExplicit &&
            !it.bill.transactionId.isNullOrBlank() && validRefundEvidence(it.bill) }) {
            val db=writableDatabase
            db.beginTransaction()
            try {
                val target=postedRefundIdentityCandidates(pending.bill).filter { compatibleRefund(it,pending.bill) && refundIdentityMatches(it,pending.bill) }.singleOrNull()
                    ?: uniqueAutomaticPayment(pending.capture,pending.bill)
                if(target!=null && refundConflict(target,pending.bill)==null && paymentProvider(pending.capture)==pending.bill.refund!!.provider) {
                    val original=if(isAutomaticPayment(target))completePaymentIdentity(db,target,pending.bill) else target
                    applyRefund(db,original,pending.capture,pending.bill,screenshot(pending.id))
                    delete(pending.id);resolved[pending.id]=original.id
                } else if(canCreateNetRefund(pending.capture,pending.bill)) {
                    val settled=pending.copy(state=RecordState.POSTED,reason="",bill=pending.bill.copy(reason=""))
                    check(db.update("records",values(settled),"id=?",arrayOf(pending.id.toString()))==1)
                    AccountPostings.synchronize(db,pending.id)
                    db.update("capture_history",ContentValues().apply {
                        put("kind",if(pending.bill.amountCents==0L)"full_refund" else "partial_refund")
                    },"record_id=? AND sequence=1 AND kind='refund_pending'",arrayOf(pending.id.toString()))
                    resolved[pending.id]=pending.id
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return resolved
    }

    private fun refundConflict(original:LedgerRecord,bill:ParsedBill):String? {
        val refund=requireNotNull(bill.refund)
        if(!validRefundEvidence(bill))return "退款证据不完整，请重新识别"
        val history=captureHistory(original.id).filter { it.kind in setOf("partial_refund","full_refund") }
        if(history.any { it.refundMode!=refund.mode })return "累计退款与单次退款口径不同，请核对原账单"
        if(original.bill.refund!=null && history.isEmpty())return "原账单缺少退款明细，暂不能叠加退款"
        if(refund.mode=="single") {
            val prior=history.find { it.refundId==refund.refundId }
            if(prior!=null && prior.refundAmountCents!=refund.refundedCents)return "相同退款单号的金额冲突，请重新核对"
            val current=original.bill.refund?.refundedCents ?: 0
            if(prior==null && refund.refundedCents>refund.originalAmountCents-current)return "累计退款超过原付款金额，请核对退款明细"
        }
        return null
    }

    private fun applyRefund(db:SQLiteDatabase,original:LedgerRecord,capture:Capture,bill:ParsedBill,image:ByteArray?):IngestResult.Saved {
        val incoming=requireNotNull(bill.refund)
        val current=original.bill.refund?.refundedCents ?: 0L
        val history=if(incoming.mode=="single")captureHistory(original.id) else null
        val prior=history?.any { it.refundId==incoming.refundId && it.kind!="payment" }==true
        val total=if(incoming.mode=="single")if(prior)current else current+incoming.refundedCents else maxOf(current,incoming.refundedCents)
        if(total==current && original.bill.refund!=null) {
            // Another view of the same successful refund may add evidence, but an older
            // cumulative page must not replace the more recent financial state.
            val supplemented=(incoming.mode=="single" || incoming.refundedCents==current) &&
                appendPaymentScreenshot(db,original,capture,image,"refund_supplement")
            return IngestResult.Saved(original,supplemented=supplemented)
        }
        val entries=history ?: captureHistory(original.id)
        if(entries.isEmpty())insertHistory(db,original.id,RecordCaptureHistory(1,original.capture.receivedAt,
            "payment",original.bill.amountCents,0,original.capture.text,screenshots.contains(imageOwner(original.id))))
        val sequence=(entries.maxOfOrNull { it.sequence } ?: 1)+1
        // Save bytes before changing financial data; failure leaves the original amount intact.
        image?.let { screenshots.put(historyOwner(original.id,sequence),it,capture.receivedAt,"HISTORY") }
        try {
            val summary=incoming.copy(refundedCents=total,mode="cumulative",refundId=null)
            val updated=original.copy(bill=original.bill.copy(amountCents=incoming.originalAmountCents-total,refund=summary,
                transactionId=original.bill.transactionId ?: bill.transactionId))
            check(db.update("records",values(updated),"id=?",arrayOf(original.id.toString()))==1)
            AccountPostings.synchronize(db,original.id)
            persistIdentities(db,original.id,identities(updated))
            insertHistory(db,original.id,RecordCaptureHistory(sequence,capture.receivedAt,if(total==incoming.originalAmountCents)"full_refund" else "partial_refund",
                updated.bill.amountCents,total,capture.text,image!=null,incoming.mode,incoming.refundId,incoming.refundedCents))
            return IngestResult.Saved(requireNotNull(get(original.id)),updated=true)
        } catch(error:Throwable) {
            // The ledger transaction will roll back; release its uncommitted external image reference too.
            if(image!=null)screenshots.release(historyOwner(original.id,sequence))
            throw error
        }
    }

    fun posted(start: Long, end: Long): List<LedgerRecord> = query(
        "state = ? AND occurred_at >= ? AND occurred_at < ?",
        arrayOf(RecordState.POSTED.name, start.toString(), end.toString()))

    fun pending(): List<LedgerRecord> = query("state = ?", arrayOf(RecordState.REVIEW.name))
    fun all(): List<LedgerRecord> = query(null, null)
    internal fun activeRecordIdsByUid():Map<String,Long> = readableDatabase.rawQuery(
        "SELECT uid,id FROM records r WHERE NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)",null
    ).use { c->buildMap { while(c.moveToNext())put(c.getString(0),c.getLong(1)) } }
    fun screenshotIds(availableOwners: Set<String> = screenshots.availableOwners(),recordIds:Set<Long>?=null): Set<Long> =
        readableDatabase.rawQuery("SELECT record_id FROM capture_images"+idFilter(recordIds),null)
            .use { cursor -> buildSet { while (cursor.moveToNext()) { val id=cursor.getLong(0); if(imageOwner(id) in availableOwners)add(id) } } }

    fun merchantIconKeys(recordIds:Set<Long>?=null): Map<Long, String> {
        if(recordIds?.isEmpty()==true)return emptyMap()
        return readableDatabase.rawQuery("SELECT l.record_id, l.merchant_key FROM merchant_icons l " +
            "JOIN merchant_icon_assets a ON l.merchant_key = a.merchant_key"+idFilter(recordIds,"l.record_id"), null)
            .use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) } }
    }

    fun merchantIconIds(): Set<Long> = merchantIconKeys().keys

    fun merchantIconRevisions(recordIds:Set<Long>?=null):Map<String,Int> = readableDatabase.rawQuery(
        if(recordIds==null)"SELECT merchant_key,image FROM merchant_icon_assets"
        else "SELECT DISTINCT a.merchant_key,a.image FROM merchant_icon_assets a JOIN merchant_icons l ON l.merchant_key=a.merchant_key"+idFilter(recordIds,"l.record_id"),null).use { cursor ->
        buildMap { while(cursor.moveToNext())put(cursor.getString(0),cursor.getBlob(1).contentHashCode()) }
    }

    fun merchantIconAsset(key: String): ByteArray? = readableDatabase.query("merchant_icon_assets", arrayOf("image"),
        "merchant_key = ?", arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun merchantIcon(id: Long): ByteArray? = readableDatabase.rawQuery("SELECT a.image FROM merchant_icons l " +
        "JOIN merchant_icon_assets a ON l.merchant_key = a.merchant_key WHERE l.record_id = ?",
        arrayOf(id.toString())).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }

    /** Restored artwork must not replace an icon already learned from a local bill. */
    @Synchronized fun restoreMerchantIcon(id:Long,image:ByteArray) {
        val record=requireNotNull(get(id)) { "账单已不存在" }
        require(image.isNotEmpty() && image.size<=5*1024*1024)
        val key=MerchantNames.key(record.bill.merchant) ?: return
        writableDatabase.insertWithOnConflict("merchant_icon_assets",null,ContentValues().apply {
            put("merchant_key",key);put("image",image)
        },SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized fun attachScreenshot(id:Long,image:ByteArray) {
        require(image.isNotEmpty()&&image.size<=5*1024*1024)
        require(get(id)!=null)
        screenshots.put(imageOwner(id),image,System.currentTimeMillis(),"HISTORY")
        writableDatabase.insertWithOnConflict("capture_images",null,ContentValues().apply { put("record_id",id) },SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun screenshot(id: Long): ByteArray? {
        readableDatabase // Finish a legacy migration before consulting the shared store.
        return screenshots.image(imageOwner(id))
    }

    /** The surrounding restore transaction rolls financial rows back; media lives in another database. */
    @Synchronized internal fun releaseRestoredMedia(id:Long) {
        captureHistory(id).forEach { screenshots.release(historyOwner(id,it.sequence)) }
        screenshots.release(imageOwner(id))
    }

    @Synchronized fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previousKey = iconKey(db, id)
            AccountPostings.reverse(db,id)
            db.delete("record_deletions","record_id=?",arrayOf(id.toString()))
            db.delete("record_identities","record_id=?",arrayOf(id.toString()))
            db.delete("merchant_icons", "record_id = ?", arrayOf(id.toString()))
            db.delete("capture_images", "record_id = ?", arrayOf(id.toString()))
            captureHistory(id).forEach { screenshots.release(historyOwner(id,it.sequence)) }
            db.delete("capture_history", "record_id = ?", arrayOf(id.toString()))
            db.delete("records", "id = ?", arrayOf(id.toString()))
            screenshots.release(imageOwner(id))
            pruneIcon(db, previousKey)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** The deletion deadline is durable: process death cannot resurrect a removed bill. */
    @Synchronized fun softDelete(id:Long,now:Long=System.currentTimeMillis()):Boolean {
        if(get(id)==null)return false
        val db=writableDatabase;db.beginTransaction()
        try {
        return synchronized(screenshots) {
            val deleted=writableDatabase.insertWithOnConflict("record_deletions",null,ContentValues().apply {
                put("record_id",id);put("expires_at",now+UNDO_WINDOW_MS)
            },SQLiteDatabase.CONFLICT_IGNORE)!=-1L
            if(deleted) { AccountPostings.synchronize(db,id);markDeletedMedia(id,"UNDO") }
            db.setTransactionSuccessful();deleted
        }
        } finally { db.endTransaction() }
    }

    private fun markDeletedMedia(id:Long,state:String) {
        screenshots.state(imageOwner(id),state)
        captureHistory(id).forEach { screenshots.state(historyOwner(id,it.sequence),state) }
    }

    @Synchronized fun undoDelete(id:Long,now:Long=System.currentTimeMillis()):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
        val restored=db.delete("record_deletions","record_id=? AND expires_at>?",arrayOf(id.toString(),now.toString()))==1
        if(restored) { AccountPostings.synchronize(db,id);markDeletedMedia(id,"HISTORY") }
        db.setTransactionSuccessful();return restored
        } finally { db.endTransaction() }
    }

    fun pendingDeletion(now:Long=System.currentTimeMillis()):DeletedRecord? = readableDatabase.rawQuery(
        "SELECT record_id,expires_at FROM record_deletions WHERE expires_at>? ORDER BY expires_at DESC,record_id DESC LIMIT 1",arrayOf(now.toString())
    ).use { c-> if(c.moveToFirst())DeletedRecord(c.getLong(0),c.getLong(1)) else null }

    @Synchronized fun purgeExpiredDeletes(now:Long=System.currentTimeMillis()):List<Long> {
        val ids=readableDatabase.rawQuery("SELECT record_id FROM record_deletions WHERE expires_at<=?",arrayOf(now.toString()))
            .use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } }
        ids.forEach(::delete)
        return ids
    }

    companion object { const val UNDO_WINDOW_MS=6_000L }

    fun accounts(): List<LedgerAccount> = readableDatabase.query("accounts", null, null, null,
        null, null, "id ASC").use { cursor -> buildList { while (cursor.moveToNext()) add(readAccount(cursor)) } }

    fun getAccount(id: Long): LedgerAccount? = readableDatabase.query("accounts", null, "id = ?",
        arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) readAccount(it) else null }

    @Synchronized
    fun saveAccount(account: LedgerAccount): LedgerAccount {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val original=if(account.id==0L)null else getAccount(account.id)
            require(account.name.codePointCount(0,account.name.length)<=LedgerAccount.MAX_EDITABLE_NAME_LENGTH ||
                original?.uid==account.uid && original.name==account.name) { "账户名称最多 20 个字符" }
            account.validate()
            val nameKey=LedgerAccount.nameKey(account.name)
            require(nameKey.isNotEmpty()) { "账户名称需为 1 至 40 个字符" }
            require(accounts().none { it.id!=account.id && LedgerAccount.nameKey(it.name)==nameKey }) { "账户名称已存在，请使用其他名称" }
            if(account.id!=0L && getAccount(account.id)?.type!=account.type)AccountTransfers.requireNoHistory(db,account.uid)
            if(account.id!=0L && getAccount(account.id)?.type!=account.type)require(!db.rawQuery("SELECT 1 FROM records WHERE account_uid=? LIMIT 1",arrayOf(account.uid)).use { it.moveToFirst() }) { "账户已关联账单，不能更改资产或负债类型" }
            val saved=if (account.id == 0L) account.copy(id = db.insertOrThrow("accounts", null, accountValues(account))) else {
                check(db.update("accounts", accountValues(account), "id = ? AND uid = ?",
                    arrayOf(account.id.toString(), account.uid)) == 1) { "账户已不存在" }
                account
            }
            db.setTransactionSuccessful()
            return saved
        } finally { db.endTransaction() }
    }

    @Synchronized fun deleteAccount(id: Long) {
        val db=writableDatabase;db.beginTransaction()
        try {
            getAccount(id)?.let { account ->
                AccountTransfers.requireNoHistory(db,account.uid)
                db.update("records",ContentValues().apply { putNull("account_uid");put("account_effect",0) },"account_uid=?",arrayOf(account.uid))
                AccountPostings.clearDeletedAccount(db,account.uid)
                db.delete("accounts","id=?",arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun defaultAccount(channel:String?=null):LedgerAccount?=AccountPostings.defaultUid(readableDatabase,channel)?.let { uid->
        readableDatabase.query("accounts",null,"uid=?",arrayOf(uid),null,null,null).use { if(it.moveToFirst())readAccount(it) else null }
    }
    @Synchronized fun setDefaultAccount(uid:String?)=AccountPostings.setDefault(writableDatabase,uid)
    fun channelDefaultAccounts():Map<String,String> = AccountPostings.channelDefaults(readableDatabase)
    @Synchronized fun setChannelDefaultAccount(channel:String,uid:String?)=AccountPostings.setChannelDefault(writableDatabase,channel,uid)
    @Synchronized fun setRecordAccount(id:Long,uid:String,accountUid:String?):LedgerRecord {
        val db=writableDatabase;db.beginTransaction()
        try {
            val current=requireNotNull(get(id)) { "账单已不存在" }
            require(current.uid==uid && current.state==RecordState.POSTED) { "账单已变更，请重新打开" }
            AccountPostings.select(db,id,accountUid)
            db.setTransactionSuccessful();return requireNotNull(get(id))
        } finally { db.endTransaction() }
    }

    fun customCategories(): List<CustomCategory> = readableDatabase.query("custom_categories", null,
        null, null, null, null, "direction ASC, rowid ASC").use { cursor -> buildList {
        while (cursor.moveToNext()) add(CustomCategory(cursor.getString(cursor.getColumnIndexOrThrow("name")),
            Direction.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("direction")))))
    } }

    @Synchronized
    fun addCategory(category: CustomCategory): Boolean {
        category.validate()
        return writableDatabase.insertWithOnConflict("custom_categories", null, categoryValues(category),
            SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized fun deleteCategory(category: CustomCategory) {
        category.validate()
        // Past bills keep their original category text after removal from the picker.
        writableDatabase.delete("custom_categories", "direction = ? AND name = ?",
            arrayOf(category.direction.name, category.name))
    }

    @Synchronized
    fun merge(records: List<LedgerRecord>, accounts: List<LedgerAccount> = emptyList(),
              categories: List<CustomCategory> = emptyList()): Int = mergeRestored(records,accounts,categories,emptyList())

    /** Snapshot restore applies the net of all missing bill/transfer UUIDs once per existing account. */
    @Synchronized internal fun mergeRestored(records:List<LedgerRecord>,accounts:List<LedgerAccount>,
              categories:List<CustomCategory>,transfers:List<AccountTransfer>):Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var imported = 0
            val deltas=mutableMapOf<String,Long>()
            val existingAccounts=accounts().mapTo(mutableSetOf()) { it.uid }
            accounts.forEach { account ->
                account.validate()
                if(account.uid !in existingAccounts)db.insertOrThrow("accounts",null,accountValues(account.copy(id=0)))
            }
            records.forEach { record ->
                // UUIDs identify records in repeated backups. Existing local edits win.
                val exists = db.rawQuery("SELECT 1 FROM records WHERE uid = ?", arrayOf(record.uid)).use { it.moveToFirst() }
                if (!exists) {
                    val appliesToExisting=record.accountUid in existingAccounts
                    val amount=record.bill.amountCents
                    val direction=record.bill.direction
                    val effect=if(appliesToExisting && record.state==RecordState.POSTED && amount!=null && direction!=null)
                        AccountPostings.effect(requireNotNull(AccountPostings.account(db,requireNotNull(record.accountUid))).second,
                            amount,direction) else 0L
                    val restored=record.copy(id=0,duplicateId=null,accountEffect=if(appliesToExisting)effect else record.accountEffect)
                    val id = db.insertOrThrow("records", null, values(restored))
                    // New accounts carry snapshot balances. Existing accounts receive only
                    // newly imported bills; re-importing a UUID can never debit twice.
                    if(appliesToExisting) {
                        val uid=requireNotNull(record.accountUid)
                        deltas[uid]=Math.addExact(deltas[uid]?:0L,effect)
                    }
                    persistIdentities(db,id,identities(record))
                    linkMerchantIcon(db, id, record.bill.merchant, null)
                    imported++
                }
            }
            require(transfers.map { it.uid }.distinct().size==transfers.size) { "转账标识重复" }
            AccountTransfers(this).restore(transfers,existingAccounts).forEach { (uid,delta)->
                deltas[uid]=Math.addExact(deltas[uid]?:0L,delta)
            }
            // A bill may offset a transfer or another bill in the same snapshot. Only
            // final balances are meaningful; replay order must not cause temporary overflow.
            deltas.forEach { (uid,delta)->AccountPostings.adjust(db,uid,delta) }
            categories.forEach { category ->
                category.validate()
                db.insertWithOnConflict("custom_categories", null, categoryValues(category), SQLiteDatabase.CONFLICT_IGNORE)
            }
            ReimbursementStore.validateStoredRelationships(db)
            db.setTransactionSuccessful()
            return imported
        } finally { db.endTransaction() }
    }

    private fun accountValues(account: LedgerAccount) = ContentValues().apply {
        put("uid", account.uid); put("name", account.name); put("description", account.description)
        put("amount", maxOf(0,account.amountCents));put("balance_adjustment",minOf(0,account.amountCents)); put("kind", account.kind); put("type", account.type.name)
        put("included_in_assets",if(account.includedInAssets)1 else 0)
    }

    private fun categoryValues(category: CustomCategory) = ContentValues().apply {
        put("name", category.name); put("direction", category.direction.name)
    }

    private fun readAccount(cursor: Cursor) = LedgerAccount(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        uid = cursor.getString(cursor.getColumnIndexOrThrow("uid")),
        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
        amountCents = cursor.getLong(cursor.getColumnIndexOrThrow("amount"))+cursor.getLong(cursor.getColumnIndexOrThrow("balance_adjustment")),
        kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
        type = AccountType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))),
        includedInAssets = cursor.getInt(cursor.getColumnIndexOrThrow("included_in_assets"))==1)

    private fun query(where: String?, args: Array<String>?): List<LedgerRecord> = readableDatabase.query(
        "records", null, "NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)"+(where?.let { " AND ($it)" }?:""), args, null, null, "occurred_at DESC, id DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(read(cursor)) }
    }

    private fun values(r: LedgerRecord) = ContentValues().apply {
        put("uid", r.uid); put("source", r.capture.source.name); put("origin", r.capture.origin)
        put("raw_text", r.capture.text); put("received_at", r.capture.receivedAt)
        put("amount", r.bill.amountCents); put("direction", r.bill.direction?.name)
        put("merchant", r.bill.merchant); put("category", r.bill.category)
        put("occurred_at", r.bill.occurredAt); put("transaction_id", r.bill.transactionId)
        put("reliable", if (r.bill.reliable) 1 else 0); put("time_explicit", if (r.bill.timeExplicit) 1 else 0)
        put("state", r.state.name); put("reason", r.reason); put("duplicate_id", r.duplicateId)
        put("note", r.note);put("tags",BillTags.toStorage(r.tags))
        put("requires_reimbursement",if(r.requiresReimbursement)1 else 0)
        put("account_uid",r.accountUid);put("account_effect",r.accountEffect)
        put("transaction_identities",TransactionIdentities.encode(identities(r)))
        put("refund_provider", r.bill.refund?.provider);put("refund_original", r.bill.refund?.originalAmountCents)
        put("refund_amount", r.bill.refund?.refundedCents);put("refund_mode",r.bill.refund?.mode);put("refund_id",r.bill.refund?.refundId)
    }

    private fun read(c: Cursor): LedgerRecord {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun number(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        fun optionalNumber(key: String): Long? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getLong(it) }
        fun optionalText(key: String): String? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getString(it) }
        return LedgerRecord(number("id"), text("uid"), Capture(Source.valueOf(text("source")),
            text("origin"), text("raw_text"), number("received_at")),
            ParsedBill(optionalNumber("amount"), optionalText("direction")?.let(Direction::valueOf),
                text("merchant"), text("category"), number("occurred_at"), optionalText("transaction_id"),
                number("reliable") == 1L, text("reason"), number("time_explicit") == 1L,
                optionalText("refund_provider")?.let { RefundInfo(it,number("refund_original"),number("refund_amount"),text("refund_mode"),optionalText("refund_id")) }),
            RecordState.valueOf(text("state")), text("reason"), optionalNumber("duplicate_id"), text("note"),
            TransactionIdentities.decode(text("transaction_identities")),BillTags.fromStorage(text("tags")),number("requires_reimbursement")==1L,if(c.getColumnIndex("account_uid")>=0)optionalText("account_uid") else null,if(c.getColumnIndex("account_effect")>=0)number("account_effect") else 0)
    }
}
