package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScreenshotStorageTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val namespace="test-images-${UUID.randomUUID()}"
    private val images=ScreenshotStore(context,namespace)
    private val ledgerName="$namespace-ledger.db"
    private val taskName="$namespace-task.db"
    private var ledger=LedgerStore(context,ledgerName,images)
    private var tasks=RecognitionTasks(context,taskName,images)
    private val now=System.currentTimeMillis()

    @After fun close() {
        ledger.close();tasks.close();images.close()
        context.deleteDatabase(ledgerName);context.deleteDatabase(taskName);context.deleteDatabase("$namespace.db")
        File(context.filesDir,namespace).deleteRecursively()
        context.getSharedPreferences("${namespace}_policy",Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun bill(image:ByteArray,icon:ByteArray?=null):LedgerRecord = (ledger.ingest(
        Capture(Source.OCR,"shake:com.tencent.mm","synthetic payment",now),
        ParsedBill(123,Direction.EXPENSE,"测试商户","购物",now,"synthetic-${UUID.randomUUID()}",true,"",true),
        screenshot=image,merchantIcon=icon) as IngestResult.Saved).record

    @Test fun taskAndBillShareOneFileAndDeletingOneReferencePreservesTheOther() {
        val bytes=byteArrayOf(1,2,3,4)
        val id=tasks.create(Source.OCR,"shake:com.tencent.mm")
        assertTrue(tasks.queued(id,bytes))
        val record=bill(bytes)
        tasks.update(id,"SUCCEEDED","已入账",record.id)
        assertEquals(1,images.stats().getInt("count"));assertEquals(4L,images.stats().getLong("bytes"))
        assertEquals(1,File(context.filesDir,namespace).listFiles()!!.count { it.extension=="jpg" })
        tasks.delete(id);assertArrayEquals(bytes,ledger.screenshot(record.id))
        ledger.delete(record.id);assertEquals(0,images.stats().getInt("count"))
    }

    @Test fun queuedImagesSurviveBudgetAndEvictedHistoryKeepsBillsAndIcons() {
        val bytes=byteArrayOf(1,2,3)
        val icon=byteArrayOf(9,8,7)
        val record=bill(bytes,icon)
        images.put("failed",byteArrayOf(4),now-8*DAY,"FAILED")
        images.put("queued",bytes,now-50*DAY,"QUEUED")
        images.cleanup(now+31*DAY,0)
        assertNotNull(ledger.get(record.id));assertEquals(RecordState.POSTED,ledger.get(record.id)!!.state)
        assertArrayEquals(icon,ledger.merchantIcon(record.id));assertNull(images.image("failed"))
        assertArrayEquals(bytes,images.image("queued"))
        assertArrayEquals(bytes,ledger.screenshot(record.id)) // Linked recognition history remains available after retention cleanup.
        images.state("queued","FAILED");images.cleanup(now+31*DAY,0)
        assertNull(ledger.historyScreenshot(record.id,1))
        assertEquals(1,ledger.captureHistory(record.id).size)
        assertFalse(ledger.captureHistory(record.id).single().hasScreenshot)
        assertEquals(0,images.stats().getInt("count"))
        assertEquals(123L,ledger.get(record.id)!!.bill.amountCents)
        assertArrayEquals(icon,ledger.merchantIcon(record.id))
    }

    @Test fun noBillAndDuplicateDiscardOnlyTheirOwnReference() {
        val bytes=byteArrayOf(7,6,5)
        val record=bill(bytes)
        val id=tasks.create(Source.OCR,"shake:com.tencent.mm");tasks.prepared(id,bytes)
        tasks.update(id,"DUPLICATE","已有记录")
        assertNull(tasks.image(id));assertArrayEquals(bytes,ledger.screenshot(record.id))
        assertFalse(tasks.snapshot().getJSONObject(0).getBoolean("hasScreenshot"))
        val second=tasks.create(Source.OCR,"shake:com.tencent.mm");tasks.prepared(second,byteArrayOf(3))
        tasks.update(second,"NO_BILL","不是账单");assertNull(tasks.image(second))
        assertEquals(1,images.stats().getInt("count"))
    }

    @Test fun replacingReferenceReclaimsOnlyAnUnsharedPreviousImage() {
        val first=byteArrayOf(11,12);val second=byteArrayOf(21,22)
        val originalHash=images.put("first",first,now,"HISTORY")
        images.put("shared",first,now,"QUEUED")
        images.put("first",second,now,"HISTORY")
        assertEquals(2,images.stats().getInt("count"));assertArrayEquals(first,images.image("shared"))
        images.put("shared",second,now,"QUEUED")
        assertFalse(File(context.filesDir,"$namespace/$originalHash.jpg").exists())
        assertEquals(1,images.stats().getInt("count"));assertArrayEquals(second,images.image("first"))
        images.put("first",second,now,"HISTORY")
        images.release("absent");images.release("first")
        assertArrayEquals(second,images.image("shared"))
        images.release("shared");assertEquals(0,images.stats().getInt("count"))
    }

    @Test fun legacyBlobsMigrateIdempotentlyAndDeduplicateAcrossBothDatabases() {
        // Larger than CursorWindow: migration must use bounded chunk reads.
        val bytes=ByteArray(3*1024*1024) { (it%251).toByte() }
        val record=bill(byteArrayOf(1))
        ledger.writableDatabase.execSQL("UPDATE capture_images SET image=? WHERE record_id=?",arrayOf(bytes,record.id))
        ledger.close();LegacyLedgerSchemaFixture.project(context.getDatabasePath(ledgerName).path,5)
        val id=tasks.create(Source.OCR,"shake:com.tencent.mm")
        tasks.writableDatabase.execSQL("UPDATE tasks SET image=?,status='FAILED' WHERE id=?",arrayOf(bytes,id))
        tasks.writableDatabase.version=2;tasks.close()
        ledger=LedgerStore(context,ledgerName,images);tasks=RecognitionTasks(context,taskName,images)
        assertArrayEquals(bytes,ledger.screenshot(record.id));assertArrayEquals(bytes,tasks.image(id))
        assertEquals(14,ledger.readableDatabase.version);assertEquals(3,tasks.readableDatabase.version)
        assertEquals(0,ledger.readableDatabase.rawQuery("SELECT COUNT(*) FROM capture_images WHERE image IS NOT NULL",null).use { it.moveToFirst();it.getInt(0) })
        assertEquals(0,tasks.readableDatabase.rawQuery("SELECT COUNT(*) FROM tasks WHERE image IS NOT NULL",null).use { it.moveToFirst();it.getInt(0) })
        images.cleanup(now);assertEquals(1,images.stats().getInt("count"))
        ledger.close();ledger=LedgerStore(context,ledgerName,images)
        assertArrayEquals(bytes,ledger.screenshot(record.id));assertNotNull(ledger.get(record.id))
    }

    @Test fun interruptedPaymentResumesOnlyWithAuthorizableOriginAndImage() {
        val queued=tasks.create(Source.OCR,"payment_auto:com.tencent.mm");tasks.queued(queued,byteArrayOf(1))
        val running=tasks.create(Source.OCR,"shake:com.tencent.mm");tasks.prepared(running,byteArrayOf(2))
        val imported=tasks.create(Source.IMAGE,"导入截图");tasks.prepared(imported,byteArrayOf(3))
        val capturing=tasks.create(Source.OCR,"payment_auto:com.tencent.mm")
        tasks.recoverInterrupted()
        assertEquals("QUEUED",tasks.status(queued));assertEquals("QUEUED",tasks.status(running))
        assertEquals("FAILED",tasks.status(imported));assertEquals("FAILED",tasks.status(capturing))
        assertEquals(listOf(queued,running),tasks.pendingQueued().map { it.first })
        images.cleanup(now+365*DAY,0)
        assertNotNull(tasks.image(queued));assertNotNull(tasks.image(running))
    }

    @Test fun restartingTaskDatabaseRetainsQueuedImageAndCancelledEntryCannotBeRequeued() {
        val bytes=byteArrayOf(4,2,8)
        val id=tasks.create(Source.OCR,"payment_auto:com.eg.android.AlipayGphone")
        assertTrue(tasks.queued(id,bytes));tasks.close()
        tasks=RecognitionTasks(context,taskName,images);tasks.recoverInterrupted()
        assertEquals(listOf(id to "payment_auto:com.eg.android.AlipayGphone"),tasks.pendingQueued())
        assertArrayEquals(bytes,tasks.image(id))
        assertTrue(tasks.update(id,"CANCELLED","应用授权已撤销"))
        assertFalse(tasks.queued(id,bytes));assertNull(tasks.image(id))
        assertTrue(tasks.pendingQueued().isEmpty())
        assertEquals(0,images.stats().getInt("count"))
    }

    @Test fun retentionChoiceAndCapacityApplyToOldestCompletedFilesOnly() {
        images.setRetentionDays(0)
        images.put("old",byteArrayOf(1),now-100*DAY,"SUCCEEDED")
        images.put("new",byteArrayOf(2,3),now-50*DAY,"SUCCEEDED")
        images.cleanup(now,2)
        assertNull(images.image("old"));assertNotNull(images.image("new"))
        assertEquals(0,images.stats().getInt("retentionDays"))
        images.setRetentionDays(7);assertNull(images.image("new"))
    }
    companion object { private const val DAY=86_400_000L }
}
