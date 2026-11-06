package net.ankio.ledger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ReimbursementTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(at:Long,merchant:String="报销测试")=LedgerRecord(capture=Capture(Source.MANUAL,"manual","",at),
        bill=ParsedBill(1200,Direction.EXPENSE,merchant,"交通",at,null,true,"",true),state=RecordState.POSTED)
    private fun rejected(action:()->Unit) {
        try { action();fail("invalid reimbursement operation accepted") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {}
    }
    @Test fun independentPropertyPreservesFinancialFieldsCategoryTagsAndHistory() {
        val name="reimbursement-edit-${System.nanoTime()}.db";var store=LedgerStore(context,name)
        try {
            val at=System.currentTimeMillis()
            val imported=record(at).let { it.copy(tags=listOf("项目A","报销材料"),note="出差交通",
                bill=it.bill.copy(refund=RefundInfo("wechat",2000,800,"cumulative",null))) }
            // Refunds enter through the validated import/restore seam, not the new manual bill editor.
            assertEquals(1,store.merge(listOf(imported)))
            val original=store.all().single()
            store.restoreHistory(original.id,listOf(
                RestoredCaptureHistory(RecordCaptureHistory(1,at-1000,"payment",2000,0,"原付20.00",false)),
                RestoredCaptureHistory(RecordCaptureHistory(2,at,"partial_refund",1200,800,"累计退款8.00",false))))
            val history=store.captureHistory(original.id);assertEquals(2,history.size)
            assertFalse(original.requiresReimbursement)
            val enabled=store.setRequiresReimbursement(original.id,original.uid,true)
            assertEquals(original.copy(requiresReimbursement=true),enabled)
            assertEquals(history,store.captureHistory(original.id))
            store.close();store=LedgerStore(context,name)
            assertTrue(store.get(original.id)!!.requiresReimbursement)
            rejected { store.setRequiresReimbursement(original.id,"stale-uid",false) }
            assertTrue(store.get(original.id)!!.requiresReimbursement)
            assertEquals(original,store.setRequiresReimbursement(original.id,original.uid,false))
            rejected { store.setTag(original.id,original.uid,"报销",true) }
            assertEquals(listOf("项目A","报销材料"),store.get(original.id)!!.tags)
            store.softDelete(original.id)
            rejected { store.setRequiresReimbursement(original.id,original.uid,true) }
            assertTrue(store.undoDelete(original.id));assertFalse(store.get(original.id)!!.requiresReimbursement)
            assertEquals(original.bill,store.get(original.id)!!.bill)
            assertEquals(history,store.captureHistory(original.id))
        } finally {store.close();context.deleteDatabase(name)}
    }
    @Test fun filteringCursorsTotalsPreviousCategoriesAndDaysUseTheSameProperty() {
        val name="reimbursement-query-${System.nanoTime()}.db";val store=LedgerStore(context,name)
        try {
            val view=LedgerReadView(store);val window=view.window("2026-11");val at=window.start+1000
            val required=(0..205).map { record(at+it,"商户$it").copy(tags=listOf("差旅"),requiresReimbursement=true) }
            val income=record(at+500,"收入").copy(bill=record(at+500).bill.copy(amountCents=450,direction=Direction.INCOME,category="其他"),requiresReimbursement=true)
            store.merge(required+listOf(record(at-1,"普通账单"),record(at-2,"相似标签").copy(tags=listOf("报销材料","不报销")),income,
                record(window.start-1,"上月需要").copy(bill=record(window.start-1).bill.copy(amountCents=700),requiresReimbursement=true),
                record(window.start-2,"上月不需要").copy(bill=record(window.start-2).bill.copy(amountCents=300))))
            val removed=store.saveEdited(record(at+999,"已删除").copy(requiresReimbursement=true));store.softDelete(removed.id)
            val page=view.records("2026-11",reimbursement="required")
            assertEquals(200,page.getJSONArray("records").length());assertTrue(page.getBoolean("hasMore"))
            val cursor=page.getJSONObject("nextCursor")
            val tail=view.records("2026-11",beforeAt=cursor.getLong("occurredAt"),beforeId=cursor.getLong("id"),reimbursement="required")
            assertEquals(7,tail.getJSONArray("records").length());assertFalse(tail.getBoolean("hasMore"))
            val ids=(0 until page.getJSONArray("records").length()).map { page.getJSONArray("records").getJSONObject(it).getLong("id") }+
                (0 until tail.getJSONArray("records").length()).map { tail.getJSONArray("records").getJSONObject(it).getLong("id") }
            assertEquals(207,ids.distinct().size)
            assertEquals(2,view.records("2026-11",reimbursement="notRequired").getJSONArray("records").length())
            assertEquals(0,view.records("2026-11",search="普通账单",reimbursement="required").getJSONArray("records").length())
            for((filter,expense) in listOf("" to 249600L,"required" to 247200L,"notRequired" to 2400L)) {
                val summary=view.summary("2026-11",filter);val totals=summary.getJSONObject("totals")
                assertEquals(expense,totals.getLong("expense"));assertEquals(if(filter=="notRequired")0L else 450L,totals.getLong("income"))
                assertEquals(if(filter=="")208 else if(filter=="required")206 else 2,totals.getInt("expenseCount"))
                val categories=summary.getJSONArray("categories");val days=summary.getJSONArray("days")
                assertEquals(expense,(0 until categories.length()).map { categories.getJSONObject(it) }.filter { it.getString("direction")=="EXPENSE" }.sumOf { it.getLong("amount") })
                assertEquals(expense,(0 until days.length()).map { days.getJSONObject(it) }.filter { it.getString("direction")=="EXPENSE" }.sumOf { it.getLong("amount") })
                assertEquals(if(filter=="")1000L else if(filter=="required")700L else 300L,summary.getJSONObject("previous").getLong("expense"))
            }
            val category=view.records("2026-11",category="交通",direction="EXPENSE",reimbursement="required",limit=200)
            assertEquals(200,category.getJSONArray("records").length());assertTrue(category.getBoolean("hasMore"))
            assertTrue(category.getJSONArray("records").getJSONObject(0).getBoolean("requiresReimbursement"))
            rejected { view.summary("2026-11","unknown") }
        } finally {store.close();context.deleteDatabase(name)}
    }
    @Test fun backupAndCsvRoundTripAndLegacyMarkersTransferWithoutLosingOtherTags() {
        val item=record(System.currentTimeMillis()).copy(tags=listOf("项目A","报销材料"),requiresReimbursement=true)
        val backup=LedgerBackup.encode(listOf(item),ChannelSettings(context))
        fun decode(value:JSONObject)=LedgerBackup.decode(ByteArrayInputStream(value.toString().toByteArray())).records.single()
        val current=JSONObject(backup);assertEquals(8,current.getInt("version"))
        assertEquals(item,decode(current))
        val notRequired=JSONObject(backup);notRequired.getJSONArray("records").getJSONObject(0).put("requiresReimbursement",false)
        assertFalse(decode(notRequired).requiresReimbursement)
        val old=JSONObject(backup).put("version",4);old.getJSONArray("records").getJSONObject(0).apply {
            put("tags",JSONArray(listOf("报销","项目A","报销材料")));remove("requiresReimbursement")
        }
        assertTrue(decode(old).requiresReimbursement);assertEquals(item.tags,decode(old).tags)
        val legacy=JSONObject(backup).put("version",3);legacy.getJSONArray("records").getJSONObject(0).apply { remove("tags");remove("requiresReimbursement") }
        assertFalse(decode(legacy).requiresReimbursement);assertEquals(emptyList<String>(),decode(legacy).tags)
        val invalid=JSONObject(backup);invalid.getJSONArray("records").getJSONObject(0).put("requiresReimbursement","true")
        rejected { decode(invalid) }
        fun csv(row:LedgerRecord):String { val bytes=ByteArrayOutputStream();LedgerCsvExport.write(listOf(row),LedgerCsvExport.Format.GENERAL,bytes);return bytes.toString("UTF-8") }
        val imported=LedgerCsvImport.read(ByteArrayInputStream(csv(item).toByteArray()))
        assertTrue(imported.errors.toString(),imported.errors.isEmpty());assertEquals(item.tags,imported.records.single().tags)
        assertTrue(imported.records.single().requiresReimbursement);assertEquals(item.bill.amountCents,imported.records.single().bill.amountCents)
        val oldCsv=csv(item.copy(tags=listOf("报销")+item.tags)).split("\r\n").filter { it.isNotEmpty() }.joinToString("\r\n",postfix="\r\n") { it.substringBeforeLast(',') }
        val oldImported=LedgerCsvImport.read(ByteArrayInputStream(oldCsv.toByteArray()))
        assertTrue(oldImported.errors.toString(),oldImported.errors.isEmpty());assertTrue(oldImported.records.single().requiresReimbursement)
        assertEquals(item.tags,oldImported.records.single().tags)
    }
    @Test fun databaseNineTransfersOnlyExactMarkerAndPreservesEveryBillField() {
        val name="reimbursement-v9-${System.nanoTime()}.db";val initial=LedgerStore(context,name)
        val original=initial.saveEdited(record(System.currentTimeMillis()).copy(tags=listOf("项目A","报销材料"),note="保留备注"))
        val similar=initial.saveEdited(record(System.currentTimeMillis()+1,"仅相似标签").copy(tags=listOf("报销材料","不报销")))
        val history=initial.captureHistory(original.id)
        initial.writableDatabase.execSQL("UPDATE records SET tags=? WHERE id=?",arrayOf(BillTags.toStorage(listOf("报销")+original.tags),original.id))
        initial.close();LegacyLedgerSchemaFixture.project(context.getDatabasePath(name).path,9)
        val upgraded=LedgerStore(context,name)
        try {
            assertEquals(14,upgraded.readableDatabase.version)
            assertEquals(original.copy(requiresReimbursement=true),upgraded.get(original.id))
            assertEquals(similar,upgraded.get(similar.id));assertEquals(history,upgraded.captureHistory(original.id))
            assertEquals(listOf("不报销","报销材料","项目A"),BillReports(upgraded).labels())
        } finally {upgraded.close();context.deleteDatabase(name)}
    }
    @Test fun databaseEightUpgradePreservesBillsAndIdentitiesAndDefaultsToFalse() {
        val name="reimbursement-v8-${System.nanoTime()}.db";val initial=LedgerStore(context,name)
        val item=initial.saveEdited(record(System.currentTimeMillis()));val history=initial.captureHistory(item.id);initial.close()
        LegacyLedgerSchemaFixture.project(context.getDatabasePath(name).path,8)
        val upgraded=LedgerStore(context,name)
        try {assertEquals(item,upgraded.get(item.id));assertEquals(history,upgraded.captureHistory(item.id));assertEquals(14,upgraded.readableDatabase.version)}
        finally {upgraded.close();context.deleteDatabase(name)}
    }
}
