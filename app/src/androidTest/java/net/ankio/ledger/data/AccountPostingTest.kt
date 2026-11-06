package net.ankio.ledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountPostingTest {
    private val at=1_793_800_000_000L
    private fun withStore(test:(LedgerStore,Context,String)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="account-posting-${UUID.randomUUID()}.db"
        val store=LedgerStore(context,name)
        try {test(store,context,name)} finally {store.close();context.deleteDatabase(name)}
    }
    private fun expense(store:LedgerStore,amount:Long=1800,id:String?="SYNTHETIC-A",time:Long=at,text:String="合成测试商户") =
        (store.ingest(Capture(Source.OCR,"com.tencent.mm",text,time),
            ParsedBill(amount,Direction.EXPENSE,"合成测试商户","餐饮",time,id,true,"",true)) as IngestResult.Saved).record

    @Test fun defaultDebitDuplicateSwitchEditDeleteAndUndoAreAtomic()=withStore { store,_,_ ->
        val a=store.saveAccount(LedgerAccount(name="合成账户A",amountCents=10000))
        val b=store.saveAccount(LedgerAccount(name="合成账户B",amountCents=20000))
        store.setDefaultAccount(b.uid)
        val record=expense(store)
        assertEquals(b.uid,record.accountUid);assertEquals(18200L,store.getAccount(b.id)!!.amountCents)
        store.ingest(record.capture,record.bill)
        assertEquals(18200L,store.getAccount(b.id)!!.amountCents)
        val moved=store.setRecordAccount(record.id,record.uid,a.uid)
        assertEquals(20000L,store.getAccount(b.id)!!.amountCents);assertEquals(8200L,store.getAccount(a.id)!!.amountCents)
        // Even an out-of-date UI copy cannot overwrite the stored previous effect.
        val edited=store.saveEdited(moved.copy(bill=moved.bill.copy(amountCents=2200),accountEffect=0))
        assertEquals(7800L,store.getAccount(a.id)!!.amountCents)
        assertTrue(store.softDelete(edited.id,at));assertEquals(10000L,store.getAccount(a.id)!!.amountCents)
        assertFalse(store.softDelete(edited.id,at));assertEquals(10000L,store.getAccount(a.id)!!.amountCents)
        assertTrue(store.undoDelete(edited.id,at+1));assertEquals(7800L,store.getAccount(a.id)!!.amountCents)
        store.softDelete(edited.id,at);store.purgeExpiredDeletes(at+10000)
        assertEquals(10000L,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun reviewHistoricalBillsAndUnlinkedRecordsDoNotDebitUntilExplicitlyLinked()=withStore { store,_,_ ->
        val historical=expense(store)
        val a=store.saveAccount(LedgerAccount(name="合成资产",amountCents=10000))
        store.saveEdited(historical.copy(note="历史备注"))
        assertEquals(10000L,store.getAccount(a.id)!!.amountCents)
        val review=(store.ingest(Capture(Source.OCR,"com.tencent.mm","合成待核对",at+1000),
            historical.bill.copy(transactionId="SYNTHETIC-REVIEW",reliable=false)) as IngestResult.Saved).record
        assertNull(review.accountUid);assertEquals(10000L,store.getAccount(a.id)!!.amountCents)
        store.saveEdited(review)
        assertEquals(8200L,store.getAccount(a.id)!!.amountCents)
        store.setRecordAccount(historical.id,historical.uid,a.uid)
        assertEquals(6400L,store.getAccount(a.id)!!.amountCents)
        val unlinked=store.setRecordAccount(historical.id,historical.uid,null)
        store.saveEdited(unlinked.copy(note="保持不关联"))
        assertEquals(8200L,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun overdraftAndLiabilityRefundHaveCorrectSigns()=withStore { store,_,_ ->
        val cash=store.saveAccount(LedgerAccount(name="合成现金",amountCents=100))
        val record=expense(store)
        assertEquals(-1700L,store.getAccount(cash.id)!!.amountCents)
        val debt=store.saveAccount(LedgerAccount(name="合成信用卡",amountCents=5000,type=AccountType.LIABILITY))
        store.setRecordAccount(record.id,record.uid,debt.uid)
        assertEquals(100L,store.getAccount(cash.id)!!.amountCents);assertEquals(6800L,store.getAccount(debt.id)!!.amountCents)
        val refund=record.bill.copy(amountCents=1200,refund=RefundInfo("wechat",1800,600,"cumulative"))
        store.ingest(record.capture.copy(text="合成退款",receivedAt=at+1000),refund)
        assertEquals(6200L,store.getAccount(debt.id)!!.amountCents)
        store.ingest(record.capture.copy(text="合成退款再识别",receivedAt=at+2000),refund)
        assertEquals(6200L,store.getAccount(debt.id)!!.amountCents)
        store.delete(record.id);assertEquals(5000L,store.getAccount(debt.id)!!.amountCents)
    }

    @Test fun shakePossibleDuplicateAddsEvidenceWithoutBillOrSecondDebit()=withStore { store,_,_ ->
        val a=store.saveAccount(LedgerAccount(name="合成账户",amountCents=10000))
        val original=expense(store)
        val image=byteArrayOf(1,2,3,4)
        val result=store.ingest(original.capture.copy(text="合成同笔详情",receivedAt=at+1000),
            original.bill.copy(transactionId=null),screenshot=image) as IngestResult.Saved
        assertEquals(original.id,result.record.id);assertTrue(result.supplemented)
        assertEquals(1,store.all().size);assertTrue(store.pending().isEmpty())
        assertEquals(8200L,store.getAccount(a.id)!!.amountCents)
        assertEquals(2,store.captureHistory(original.id).size)
        expense(store,id="SYNTHETIC-B",time=at+1000)
        assertEquals(2,store.all().size);assertEquals(6400L,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun backupRestoresLinksWithoutDebitingSnapshotTwiceAndDeletionKeepsBills()=withStore { store,context,_ ->
        val a=store.saveAccount(LedgerAccount(name="合成账户",amountCents=10000))
        val b=store.saveAccount(LedgerAccount(name="合成另一账户",amountCents=20000))
        store.setDefaultAccount(b.uid)
        expense(store)
        val backup=LedgerBackup.decode(LedgerBackup.encode(store,ChannelSettings(context)).byteInputStream())
        val name="account-restore-${UUID.randomUUID()}.db";val target=LedgerStore(context,name)
        try {
            assertEquals(1,LedgerBackup.restore(target,backup));assertEquals(0,LedgerBackup.restore(target,backup))
            assertEquals(b.uid,target.defaultAccount()!!.uid)
            assertEquals(18200L,target.accounts().single {it.uid==b.uid}.amountCents)
            val bill=target.all().single();assertEquals(b.uid,bill.accountUid)
            target.deleteAccount(target.accounts().single {it.uid==b.uid}.id)
            assertNull(target.get(bill.id)!!.accountUid);assertEquals(a.uid,target.defaultAccount()!!.uid)
            target.delete(bill.id);assertEquals(10000L,target.accounts().single().amountCents)
        } finally {target.close();context.deleteDatabase(name)}
    }

    @Test fun partialRestoreToExistingAccountAppliesOnlyNewBillsAndStaleAccountChoiceRollsBack()=withStore { store,context,_ ->
        val a=store.saveAccount(LedgerAccount(name="合成账户",amountCents=10000))
        val first=expense(store)
        val name="account-partial-${UUID.randomUUID()}.db";val target=LedgerStore(context,name)
        try {
            val settings=ChannelSettings(context)
            val initial=LedgerBackup.decode(LedgerBackup.encode(store,settings).byteInputStream())
            LedgerBackup.restore(target,initial)
            expense(store,id="SYNTHETIC-B",time=at+1000)
            val next=LedgerBackup.decode(LedgerBackup.encode(store,settings).byteInputStream())
            assertEquals(1,LedgerBackup.restore(target,next));assertEquals(6400L,target.accounts().single().amountCents)
            assertEquals(0,LedgerBackup.restore(target,next));assertEquals(6400L,target.accounts().single().amountCents)
            assertTrue(runCatching {target.setRecordAccount(target.all().first().id,UUID.randomUUID().toString(),a.uid)}.isFailure)
            assertEquals(6400L,target.accounts().single().amountCents)
            val restored=target.all().single {it.uid==first.uid}
            assertTrue(runCatching {target.setRecordAccount(restored.id,restored.uid,UUID.randomUUID().toString())}.isFailure)
            assertEquals(a.uid,target.get(restored.id)!!.accountUid);assertEquals(6400L,target.accounts().single().amountCents)
        } finally {target.close();context.deleteDatabase(name)}
    }

    @Test fun equalAmountIncomeAndExpensesRemainSeparateAndFailedOverflowWritesNothing()=withStore { store,_,_ ->
        val a=store.saveAccount(LedgerAccount(name="合成账户",amountCents=0))
        val outgoing=expense(store)
        val income=outgoing.bill.copy(direction=Direction.INCOME,transactionId="SYNTHETIC-INCOME")
        store.ingest(outgoing.capture.copy(text="合成收入"),income)
        assertEquals(0L,store.getAccount(a.id)!!.amountCents);assertEquals(2,store.all().size)
        store.saveAccount(store.getAccount(a.id)!!.copy(amountCents=Money.MAX_CENTS))
        val size=store.all().size
        assertTrue(runCatching {store.ingest(outgoing.capture.copy(text="合成越界收入"),income.copy(transactionId="SYNTHETIC-OVERFLOW"))}.isFailure)
        assertEquals(size,store.all().size);assertEquals(Money.MAX_CENTS,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun supplementedIdentityKeepsNextDistinctPurchaseAndRepeatedEvidenceSeparate()=withStore { store,_,_ ->
        val a=store.saveAccount(LedgerAccount(name="合成账户",amountCents=10000))
        val original=expense(store,id=null)
        val detail=original.bill.copy(transactionId="SYNTHETIC-DETAIL-A")
        val first=store.ingest(original.capture.copy(text="合成详情A",receivedAt=at+1000),detail,
            screenshot=byteArrayOf(1,2,3)) as IngestResult.Saved
        assertEquals(original.id,first.record.id);assertEquals(detail.transactionId,first.record.bill.transactionId)
        assertTrue(first.record.identities.isNotEmpty());assertEquals(8200L,store.getAccount(a.id)!!.amountCents)
        val next=store.ingest(original.capture.copy(text="合成详情B",receivedAt=at+2000),
            detail.copy(transactionId="SYNTHETIC-DETAIL-B"),screenshot=byteArrayOf(4,5,6)) as IngestResult.Saved
        assertNotEquals(original.id,next.record.id);assertEquals(RecordState.POSTED,next.record.state)
        store.ingest(original.capture.copy(text="合成详情A再次识别",receivedAt=at+3000),detail,screenshot=byteArrayOf(7,8,9))
        assertEquals(2,store.all().size);assertEquals(6400L,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun firstPromotionAndExplicitConfirmationEachDebitOnlyOnce()=withStore { store,_,_ ->
        val capture=Capture(Source.OCR,"com.tencent.mm","合成历史待确认",at)
        val bill=ParsedBill(1800,Direction.EXPENSE,"合成测试商户","餐饮",at,"SYNTHETIC-HISTORY",false,"需确认",true)
        val historical=(store.ingest(capture,bill) as IngestResult.Saved).record
        val a=store.saveAccount(LedgerAccount(name="合成账户",amountCents=10000))
        val promoted=(store.ingest(capture.copy(text="合成历史完整详情"),bill.copy(reliable=true,reason="")) as IngestResult.Saved).record
        assertEquals(historical.id,promoted.id);assertEquals(RecordState.POSTED,promoted.state)
        assertEquals(a.uid,promoted.accountUid);assertEquals(8200L,store.getAccount(a.id)!!.amountCents)
        store.ingest(capture.copy(text="合成历史完整详情再次识别"),bill.copy(reliable=true,reason=""))
        assertEquals(8200L,store.getAccount(a.id)!!.amountCents)
        val pending=(store.ingest(capture.copy(text="合成新的待确认",receivedAt=at+1000),
            bill.copy(transactionId="SYNTHETIC-CONFIRM")) as IngestResult.Saved).record
        val confirmed=store.saveEdited(pending)
        store.saveEdited(confirmed)
        assertEquals(6400L,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun realVersionElevenSchemaAddsAccountColumnsWithoutBackCharging()=withStore { store,context,name ->
        val historical=expense(store)
        val account=store.saveAccount(LedgerAccount(name="合成迁移账户",amountCents=10000))
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                // These owners did not exist in v11; do not replay their indexes against
                // the projected legacy columns before the migration under test can run.
                db.execSQL("DROP INDEX records_account_time")
                db.execSQL("DROP TABLE account_transfers")
                for((table,removed) in listOf("records" to setOf("account_uid","account_effect"),"accounts" to setOf("balance_adjustment","included_in_assets"))) {
                    val columns=db.rawQuery("PRAGMA table_info($table)",null).use { c->buildList {while(c.moveToNext())if(c.getString(1) !in removed)add(c.getString(1))} }
                    var schema=db.rawQuery("SELECT sql FROM sqlite_master WHERE name=?",arrayOf(table)).use {it.moveToFirst();it.getString(0)}
                    for(column in removed) {
                        val definition=if(column=="included_in_assets")"INTEGER NOT NULL DEFAULT 1 CHECK\\(included_in_assets IN \\(0,1\\)\\)" else "(?:TEXT|INTEGER NOT NULL DEFAULT 0)"
                        schema=schema.replace(Regex(",\\s*"+column+" "+definition),"")
                    }
                    val indexes=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",arrayOf(table)).use {c->buildList {while(c.moveToNext())add(c.getString(0))} }
                    db.execSQL(schema.replaceFirst("CREATE TABLE $table","CREATE TABLE fixture_$table"))
                    db.execSQL("INSERT INTO fixture_$table(${columns.joinToString()}) SELECT ${columns.joinToString()} FROM $table")
                    db.execSQL("DROP TABLE $table");db.execSQL("ALTER TABLE fixture_$table RENAME TO $table")
                    indexes.forEach {db.execSQL(it)}
                    db.rawQuery("PRAGMA table_info($table)",null).use { c ->
                        while(c.moveToNext())assertFalse("v11 fixture retained ${c.getString(1)}",c.getString(1) in removed)
                    }
                }
                db.execSQL("DROP TABLE account_preferences");db.version=11;db.setTransactionSuccessful()
            } finally {db.endTransaction()}
        }
        LedgerStore(context,name).use { upgraded ->
            assertEquals(14,upgraded.readableDatabase.version)
            assertEquals(account,upgraded.getAccount(account.id));assertEquals(historical,upgraded.get(historical.id))
            upgraded.saveEdited(historical.copy(note="迁移后备注"))
            assertEquals(10000L,upgraded.getAccount(account.id)!!.amountCents)
            expense(upgraded,id="SYNTHETIC-AFTER-UPGRADE",time=at+1000)
            assertEquals(8200L,upgraded.getAccount(account.id)!!.amountCents)
        }
    }

    @Test fun ambiguousRefundedOriginalsDoNotChooseAnArbitraryBillOrChangeBalances()=withStore {store,_,_->
        val account=store.saveAccount(LedgerAccount(name="合成账户",amountCents=10000))
        val originals=listOf(expense(store,id="SYNTHETIC-REFUNDED-A"),expense(store,id="SYNTHETIC-REFUNDED-B"))
        originals.forEachIndexed {index,original->
            store.ingest(original.capture.copy(text="合成部分退款$index",receivedAt=at+1000),
                original.bill.copy(amountCents=1200,refund=RefundInfo("wechat",1800,600,"cumulative")))
        }
        val before=store.all();val histories=before.associate {it.id to store.captureHistory(it.id)}
        val result=store.ingest(originals.first().capture.copy(text="合成无单号原付款详情",receivedAt=at+2000),
            originals.first().bill.copy(transactionId=null),screenshot=byteArrayOf(31,32,33))
        assertTrue(result is IngestResult.Skipped);assertEquals(before,store.all())
        assertEquals(histories,store.all().associate {it.id to store.captureHistory(it.id)})
        assertEquals(7600L,store.getAccount(account.id)!!.amountCents)
    }
}
