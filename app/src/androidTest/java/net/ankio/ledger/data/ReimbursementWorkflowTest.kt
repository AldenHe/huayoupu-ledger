package net.ankio.ledger.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReimbursementWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val stores=mutableListOf<Pair<String,LedgerStore>>()
    private val caches=mutableListOf<File>()
    private val at=java.time.LocalDate.parse("2026-11-01").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun store():LedgerStore { val name="reimbursement-loop-${UUID.randomUUID()}.db";return LedgerStore(context,name).also { stores+=name to it } }
    private fun expense(store:LedgerStore,amount:Long=1200,merchant:String="差旅支出"):LedgerRecord = store.saveEdited(LedgerRecord(
        capture=Capture(Source.MANUAL,"manual","",at),bill=ParsedBill(amount,Direction.EXPENSE,merchant,"交通",at,null,true,"",true),state=RecordState.POSTED,requiresReimbursement=true))
    private fun income(store:LedgerStore,amount:Long=1500):LedgerRecord = store.saveEdited(LedgerRecord(
        capture=Capture(Source.MANUAL,"manual","",at+1),bill=ParsedBill(amount,Direction.INCOME,"单位回款","其他",at+1,null,true,"",true),state=RecordState.POSTED))
    private fun command(store:ReimbursementStore,action:String,vararg fields:Pair<String,Any?>):JSONObject = store.command(JSONObject().put("action",action).apply { fields.forEach { put(it.first,it.second?:JSONObject.NULL) } })
    /** An archived legacy relationship fixture, not an application workflow creation API. */
    private fun legacy(ledger:LedgerStore,store:ReimbursementStore,expense:LedgerRecord,income:LedgerRecord,amount:Long=300):JSONObject {
        val oldExpense=JSONObject().put("requestedCents",expense.bill.amountCents).put("purpose","旧费用用途").put("differenceReason","").put("unitRequirements","旧单位要求").put("materialsReviewed",true)
        // Seed historical payload directly in this isolated database; application commands cannot write it.
        ledger.writableDatabase.execSQL("INSERT OR REPLACE INTO reimbursement_expenses(record_uid,payload) VALUES(?,?)",arrayOf(expense.uid,oldExpense.toString()))
        val data=store.backupMetadata()
        val uid=UUID.randomUUID().toString()
        data.getJSONArray("claims").put(JSONObject().put("uid",uid).put("title","历史报销单").put("claimant","历史报销人").put("company","历史单位").put("reason","历史事由")
            .put("department","").put("project","").put("costCenter","").put("createdAt",at).put("submittedAt",at+1).put("workflowStatus","submitted"))
        data.getJSONArray("items").put(JSONObject().put("recordUid",expense.uid).put("claimUid",uid))
        data.getJSONArray("repayments").put(JSONObject().put("uid",UUID.randomUUID().toString()).put("claimUid",uid).put("sourceRecordUid",income.uid).put("amountCents",amount).put("receivedAt",at+2).put("note","历史到账备注"))
        store.restoreMetadata(data,data.getJSONArray("attachments").let {a->(0 until a.length()).map {a.getJSONObject(it)}.associate {it.getString("sha256") to store.attachmentFile(it.getString("uid"))}},mutableListOf())
        return store.backupMetadata()
    }
    private fun rejected(action:()->Unit) { try { action();fail("invalid reimbursement data accepted") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} catch(_:java.util.zip.ZipException) {} }
    private fun bytes(store:LedgerStore)=ByteArrayOutputStream().also { LedgerArchive.write(store,ChannelSettings(context),it) }.toByteArray()
    private fun cache():File = File(context.cacheDir,"reimbursement-test-${UUID.randomUUID()}").apply { mkdirs();caches+=this }
    private fun entries(bytes:ByteArray):LinkedHashMap<String,ByteArray> = linkedMapOf<String,ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while(true) { val entry=zip.nextEntry?:break;put(entry.name,zip.readBytes()) } }
    }
    private fun zip(files:Map<String,ByteArray>)=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> files.forEach { (name,content) -> zip.putNextEntry(ZipEntry(name));zip.write(content);zip.closeEntry() } } }.toByteArray()
    @After fun close() {
        stores.forEach { (name,store) -> store.reimbursementDirectory.listFiles()?.forEach { it.delete() };store.reimbursementDirectory.delete();store.close();context.deleteDatabase(name) }
        caches.forEach { directory -> directory.listFiles()?.forEach { child -> child.listFiles()?.forEach { it.delete() };child.delete() };directory.delete() }
    }

    @Test fun workflowActionsAreRetiredAndCurrentSelectionRequiresOnlyValidBills() {
        val ledger=store();val r=ReimbursementStore(context,ledger);val first=expense(ledger);val incoming=income(ledger)
        val before=r.backupMetadata().toString();val rowsBefore=ledger.all()
        for(action in listOf("saveExpense","createClaim","saveClaim","updateClaim","setStatus","addRepayment","removeRepayment","updateAttachment")) {
            rejected { command(r,action) }
        }
        assertEquals(setOf("expenses","attachments"),r.snapshot().keys().asSequence().toSet())
        val selection=r.exportSnapshot(listOf(first.uid));assertEquals(1200L,selection.getLong("paidCents"))
        val item=selection.getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("recordUid","id","merchant","category","occurredAt","paidCents","note","attachments"),item.keys().asSequence().toSet())
        assertEquals(0,item.getJSONArray("attachments").length())
        rejected { r.exportSnapshot(emptyList()) };rejected { r.exportSnapshot(List(1001){first.uid}) }
        rejected { r.exportSnapshot(listOf(first.uid,first.uid)) };rejected { r.exportSnapshot(listOf("invalid")) }
        rejected { r.exportSnapshot(listOf(UUID.randomUUID().toString())) };rejected { r.exportSnapshot(listOf(incoming.uid)) }
        assertEquals(before,r.backupMetadata().toString());assertEquals(rowsBefore,ledger.all())
        ledger.setRequiresReimbursement(first.id,first.uid,false);rejected { r.exportSnapshot(listOf(first.uid)) }
        ledger.setRequiresReimbursement(first.id,first.uid,true)
        val review=first.copy(id=0,uid=UUID.randomUUID().toString(),state=RecordState.REVIEW)
        ledger.merge(listOf(review));rejected { r.exportSnapshot(listOf(review.uid)) }
        ledger.softDelete(first.id);rejected { r.exportSnapshot(listOf(first.uid)) }
    }

    @Test fun originalsRemainDeduplicatedManyToManyAndMissingMetadataDoesNotBlockExport() {
        val ledger=store();val r=ReimbursementStore(context,ledger);val a=expense(ledger);val b=expense(ledger,800)
        val original="%PDF-1.7\nuntouched synthetic invoice bytes\u0000\u0001".toByteArray()
        val invoice=r.importAttachment(listOf(a.uid,b.uid),"原始发票.pdf","application/pdf","invoice",ByteArrayInputStream(original))
        val same=r.importAttachment(listOf(a.uid),"另一个文件名.pdf","application/pdf","invoice",ByteArrayInputStream(original))
        assertEquals(invoice.getString("uid"),same.getString("uid"));assertTrue(same.getBoolean("reused"))
        assertArrayEquals(original,r.attachmentFile(invoice.getString("uid")).readBytes())
        assertEquals(1,r.exportSnapshot(listOf(a.uid,b.uid)).getJSONArray("attachments").length())
        command(r,"unlinkAttachment","attachmentUid" to invoice.getString("uid"),"recordUid" to a.uid)
        assertEquals(0,r.attachmentsForRecord(a.uid).size);assertEquals(1,r.attachmentsForRecord(b.uid).size)
        command(r,"unlinkAttachment","attachmentUid" to invoice.getString("uid"),"recordUid" to b.uid)
        assertEquals(0,r.attachmentsForRecord(b.uid).size)
        assertEquals(invoice.getString("uid"),r.snapshot().getJSONArray("attachments").getJSONObject(0).getString("uid"))
        assertArrayEquals(original,r.attachmentFile(invoice.getString("uid")).readBytes())
        command(r,"linkAttachment","attachmentUid" to invoice.getString("uid"),"recordUids" to JSONArray(listOf(a.uid,b.uid)))
        assertEquals(1,r.attachmentsForRecord(a.uid).size);assertEquals(1,r.attachmentsForRecord(b.uid).size)
        val before=r.backupMetadata().toString()
        val oversized=object:InputStream() {
            var remaining=ReimbursementStore.MAX_ATTACHMENT_BYTES+1
            override fun read():Int {if(remaining--<=0)return -1;return 1}
            override fun read(b:ByteArray,off:Int,len:Int):Int {if(remaining<=0)return -1;val count=minOf(remaining,len.toLong()).toInt();java.util.Arrays.fill(b,off,off+count,1.toByte());remaining-=count;return count}
        }
        rejected { r.importAttachment(listOf(a.uid),"大文件.pdf","application/pdf","invoice",oversized) }
        rejected { r.importAttachment(listOf(a.uid),"../escape.pdf","application/pdf","invoice",ByteArrayInputStream(original)) }
        assertEquals(before,r.backupMetadata().toString())
        assertArrayEquals(original,r.attachmentFile(invoice.getString("uid")).readBytes())
        assertEquals(before,r.backupMetadata().toString())
        File(ledger.reimbursementDirectory,invoice.getString("sha256")).writeText("corrupted original")
        rejected { r.attachmentFile(invoice.getString("uid")) }
    }

    @Test fun failedImportRemovesOnlyItsNewOriginalAndPreservesPreexistingFiles() {
        val ledger=store();val r=ReimbursementStore(context,ledger);val expense=expense(ledger)
        val existingBytes="existing registered original".toByteArray()
        val existing=r.importAttachment(listOf(expense.uid),"已有原件.pdf","application/pdf","invoice",ByteArrayInputStream(existingBytes))
        val preservedBytes="preexisting unregistered content-addressed file".toByteArray()
        val preservedHash=java.security.MessageDigest.getInstance("SHA-256").digest(preservedBytes).joinToString(""){"%02x".format(it)}
        val preservedFile=File(ledger.reimbursementDirectory,preservedHash).apply {writeBytes(preservedBytes)}
        val before=r.backupMetadata().toString();val filesBefore=ledger.reimbursementDirectory.listFiles().orEmpty().map {it.name}.toSet()
        ledger.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_attachment_import BEFORE INSERT ON reimbursement_attachments BEGIN SELECT RAISE(ABORT,'fixture rejected attachment insert'); END")
        try {
            fun rejectedImport(content:ByteArray) {
                try {
                    r.importAttachment(listOf(expense.uid),"拒绝保存.pdf","application/pdf","invoice",ByteArrayInputStream(content))
                    fail("fixture must reject the attachment insert")
                } catch(error:android.database.sqlite.SQLiteException) {
                    assertTrue(error.message.orEmpty().contains("fixture rejected attachment insert"))
                }
                assertEquals(before,r.backupMetadata().toString())
                assertEquals(filesBefore,ledger.reimbursementDirectory.listFiles().orEmpty().map {it.name}.toSet())
                assertArrayEquals(existingBytes,r.attachmentFile(existing.getString("uid")).readBytes())
                assertArrayEquals(preservedBytes,preservedFile.readBytes())
            }
            rejectedImport("new original that must roll back".toByteArray())
            rejectedImport(preservedBytes)
        } finally {ledger.writableDatabase.execSQL("DROP TRIGGER reject_attachment_import")}
        // A later normal import can still commit its bytes and expose them in the inventory.
        val retried=r.importAttachment(listOf(expense.uid),"恢复导入.pdf","application/pdf","invoice",ByteArrayInputStream("new original that must roll back".toByteArray()))
        assertTrue(r.attachmentFile(retried.getString("uid")).isFile)
        assertEquals(2,r.snapshot().getJSONArray("attachments").length())
    }

    @Test fun archivePreservesLegacyRelationshipsOriginalsAndLocalAttachmentUnlinkWithoutExposingWorkflow() {
        val source=store();val r=ReimbursementStore(context,source);val expense=expense(source);val income=income(source)
        val expected=linkedMapOf<String,ByteArray>()
        for((name,mime) in listOf("票.pdf" to "application/pdf","票.ofd" to "application/ofd","支付.xml" to "application/xml","证明.jpeg" to "image/jpeg")) {
            val content=("original $name\u0000\u0001\u00ff").toByteArray()
            val attachment=r.importAttachment(listOf(expense.uid),name,mime,"invoice",ByteArrayInputStream(content));expected[attachment.getString("uid")]=content
        }
        val archived=legacy(source,r,expense,income)
        val original=bytes(source);assertEquals(4,entries(original).keys.count {it.startsWith("originals/")})
        val target=store();val restored=ReimbursementStore(context,target)
        LedgerArchive.read(ByteArrayInputStream(original),cache()).use {prepared->
            assertEquals(2,LedgerArchive.restore(target,prepared))
            assertEquals(archived.toString(),restored.backupMetadata().toString())
            expected.forEach {(uid,content)->assertArrayEquals(content,restored.attachmentFile(uid).readBytes())}
            assertEquals(1200L,LedgerReadView(target).summary("2026-11").getJSONObject("totals").getLong("income"))
            assertFalse(restored.snapshot().has("claims"))
            val selection=restored.exportSnapshot(listOf(expense.uid));assertEquals(1200L,selection.getLong("paidCents"))
            assertFalse(selection.toString().contains("旧费用用途"));assertFalse(selection.toString().contains("历史报销人"))
            val current=target.all().single {it.uid==expense.uid}
            target.saveEdited(current.copy(bill=current.bill.copy(amountCents=1000,refund=RefundInfo("wechat",2000,1000,"cumulative",null))))
            assertEquals(1000L,restored.exportSnapshot(listOf(expense.uid)).getLong("paidCents"))
            assertEquals(1200L,restored.backupMetadata().getJSONArray("expenses").getJSONObject(0).getLong("requestedCents"))
            // Old submitted claims no longer block current attachment maintenance.
            command(restored,"unlinkAttachment","attachmentUid" to expected.keys.first(),"recordUid" to expense.uid)
            val before=restored.backupMetadata().toString()
            assertEquals(0,LedgerArchive.restore(target,prepared));assertEquals(before,restored.backupMetadata().toString())
            assertEquals(3,restored.attachmentsForRecord(expense.uid).size)
            expected.forEach {(uid,content)->assertArrayEquals(content,restored.attachmentFile(uid).readBytes())}
            assertEquals(before,restored.backupMetadata().toString())
            assertEquals(1,restored.backupMetadata().getJSONArray("claims").length());assertEquals(1,restored.backupMetadata().getJSONArray("repayments").length())
        }
        rejected { LedgerBackup.encode(source,ChannelSettings(context)) }
    }

    @Test fun unsafeCorruptOrInvalidArchivedDataRejectsBeforeRestore() {
        val source=store();val r=ReimbursementStore(context,source);val expense=expense(source);val incoming=income(source)
        r.importAttachment(listOf(expense.uid),"票.pdf","application/pdf","invoice",ByteArrayInputStream("synthetic PDF original".toByteArray()))
        legacy(source,r,expense,incoming)
        val good=entries(bytes(source));val original=good.keys.single {it.startsWith("originals/")};val cache=cache()
        fun reject(files:Map<String,ByteArray>) {rejected {LedgerArchive.read(ByteArrayInputStream(zip(files)),cache).use {}};assertTrue(cache.listFiles().orEmpty().isEmpty())}
        reject(LinkedHashMap(good).apply {this[original]=byteArrayOf(1,2,3)})
        reject(LinkedHashMap(good).apply {remove(original)})
        reject(LinkedHashMap(good).apply {this["originals/../escape.pdf"]=byteArrayOf(1)})
        reject(LinkedHashMap(good).apply {this["originals/"+"a".repeat(64)+".bin"]=byteArrayOf(1)})
        val invalid=LinkedHashMap(good);val manifest=JSONObject(invalid.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.getJSONObject("ledger").getJSONObject("reimbursement").getJSONArray("expenses").getJSONObject(0).put("requestedCents",-1)
        invalid["manifest.json"]=manifest.toString().toByteArray();reject(invalid)
        val badSource=LinkedHashMap(good);val sourceManifest=JSONObject(badSource.getValue("manifest.json").toString(Charsets.UTF_8))
        val records=sourceManifest.getJSONObject("ledger").getJSONArray("records")
        (0 until records.length()).map {records.getJSONObject(it)}.single {it.getString("uid")==incoming.uid}.put("state","REVIEW")
        badSource["manifest.json"]=sourceManifest.toString().toByteArray();reject(badSource)
        assertEquals(2,source.all().size)
    }

    @Test fun failedLegacyRestoreRollsBackNewOriginalsAndPreservesStoredRelationships() {
        val source=store();val reimbursements=ReimbursementStore(context,source)
        val sourceExpense=expense(source);val sourceIncome=income(source,1000)
        val imported=reimbursements.importAttachment(listOf(sourceExpense.uid),"恢复原件.pdf","application/pdf","invoice",ByteArrayInputStream("new original for restore".toByteArray()))
        legacy(source,reimbursements,sourceExpense,sourceIncome,700)
        val target=store();val local=ReimbursementStore(context,target);target.merge(listOf(sourceIncome))
        val localExpense=expense(target)
        val existing=local.importAttachment(listOf(localExpense.uid),"保留原件.ofd","application/ofd","invoice",ByteArrayInputStream("already local original".toByteArray()))
        legacy(target,local,localExpense,sourceIncome,500)
        val beforeFiles=target.reimbursementDirectory.listFiles().orEmpty().map {it.name}.toSet();val before=local.backupMetadata().toString()
        LedgerArchive.read(ByteArrayInputStream(bytes(source)),cache()).use {prepared->rejected {LedgerArchive.restore(target,prepared)}}
        assertEquals(beforeFiles,target.reimbursementDirectory.listFiles().orEmpty().map {it.name}.toSet());assertEquals(before,local.backupMetadata().toString())
        assertNull(target.all().singleOrNull {it.uid==sourceExpense.uid})
        assertArrayEquals("already local original".toByteArray(),local.attachmentFile(existing.getString("uid")).readBytes())
        assertFalse(File(target.reimbursementDirectory,imported.getString("sha256")).exists())
        rejected {target.saveEdited(sourceIncome.copy(bill=sourceIncome.bill.copy(amountCents=400)))}
    }

    @Test fun versionTenUpgradePreservesFinancialHistoryAndDefaultsToNoWorkflow() {
        val ledger=store();val original=expense(ledger);val history=ledger.captureHistory(original.id);val name=stores.last().first
        ledger.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            for(table in listOf("reimbursement_expenses","reimbursement_claims","reimbursement_items","reimbursement_attachments","reimbursement_links","reimbursement_repayments"))db.execSQL("DROP TABLE $table")
            db.version=10
        }
        val upgraded=LedgerStore(context,name);stores[stores.lastIndex]=name to upgraded
        assertEquals(14,upgraded.readableDatabase.version);assertEquals(original,upgraded.get(original.id));assertEquals(history,upgraded.captureHistory(original.id))
        val r=ReimbursementStore(context,upgraded)
        assertEquals(1200L,r.snapshot().getJSONArray("expenses").getJSONObject(0).getLong("paidCents"))
        assertEquals(0,r.backupMetadata().getJSONArray("claims").length());assertEquals(0,r.backupMetadata().getJSONArray("repayments").length())
    }
}
