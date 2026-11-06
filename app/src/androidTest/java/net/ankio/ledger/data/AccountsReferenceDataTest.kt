package net.ankio.ledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Financial invariants for selected accounts, first posting, transfers and portable snapshots. */
@RunWith(AndroidJUnit4::class)
class AccountsReferenceDataTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val month="2026-11"
    private val at=java.time.LocalDate.parse("2026-11-01").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()+1000

    private fun withStore(test:(LedgerStore,String)->Unit) {
        val name="accounts-reference-${UUID.randomUUID()}.db"
        LedgerStore(context,name).use { store -> try { test(store,name) } finally { store.close();context.deleteDatabase(name) } }
    }
    private fun account(store:LedgerStore,name:String,amount:Long=10000,type:AccountType=AccountType.ASSET,included:Boolean=true)=
        store.saveAccount(LedgerAccount(name=name,amountCents=amount,type=type,includedInAssets=included))
    private fun bill(amount:Long=100,direction:Direction=Direction.EXPENSE,time:Long=at,
                     source:Source=Source.MANUAL,origin:String="manual",merchant:String="合成账户测试")=LedgerRecord(
        capture=Capture(source,origin,"微信 支付宝 现金：只用于确认文本不参与账户路由",time),
        bill=ParsedBill(amount,direction,merchant,if(direction==Direction.EXPENSE)"交通" else "其他",time,UUID.randomUUID().toString(),true,"",true),
        state=RecordState.POSTED)
    private fun reject(action:()->Unit) { assertTrue("Invalid financial operation accepted",runCatching(action).isFailure) }
    private fun balance(store:LedgerStore,account:LedgerAccount)=store.getAccount(account.id)!!.amountCents
    private fun backup(store:LedgerStore)=LedgerBackup.decode(LedgerBackup.encode(store,ChannelSettings(context)).byteInputStream())

    @Test fun initialIncomeAndExpenseUseExactSourceThenCommonFallbackAndNeverRelinkHistory()=withStore { store,_ ->
        val oldBill=bill(source=Source.OCR,origin=PaymentApps.WECHAT)
        val old=(store.ingest(oldBill.capture,oldBill.bill) as IngestResult.Saved).record
        assertNull(old.accountUid)
        val common=account(store,"通用账户")
        val wechat=account(store,"任意银行卡")
        val alipay=account(store,"任意负债",type=AccountType.LIABILITY)
        store.setDefaultAccount(common.uid)
        store.setChannelDefaultAccount("wechat",wechat.uid);store.setChannelDefaultAccount("alipay",alipay.uid)
        var index=0
        for(direction in Direction.entries) {
            for((source,origin,expected) in listOf(
                Triple(Source.OCR,PaymentApps.WECHAT,wechat.uid),Triple(Source.OCR,PaymentApps.ALIPAY,alipay.uid),
                Triple(Source.OCR,"com.tencent.mm.fake",common.uid),Triple(Source.IMAGE,"image",common.uid),
                Triple(Source.MANUAL,PaymentApps.WECHAT,common.uid))) {
                val value=bill(amount=200L+index,direction=direction,time=at+(++index)*300_000L,source=source,origin=origin,merchant="渠道商户$index")
                val saved=(store.ingest(value.capture,value.bill) as IngestResult.Saved).record
                assertEquals(expected,saved.accountUid)
                val previousBalance=store.accounts()
                store.ingest(value.capture,value.bill)
                assertEquals(previousBalance,store.accounts())
            }
        }
        store.setChannelDefaultAccount("wechat",null)
        val fallback=bill(time=at+(++index)*300_000L,source=Source.OCR,origin=PaymentApps.WECHAT)
        assertEquals(common.uid,(store.ingest(fallback.capture,fallback.bill) as IngestResult.Saved).record.accountUid)
        val before=store.accounts()
        store.saveEdited(old.copy(note="历史不追溯关联"));store.ingest(oldBill.capture,oldBill.bill)
        assertNull(store.get(old.id)!!.accountUid);assertEquals(before,store.accounts())
    }

    @Test fun firstReliablePromotionAssignsOnceForBothDirectionsAndExplicitNoneSurvivesEnrichment()=withStore { store,_ ->
        val selected=account(store,"渠道账户")
        store.setChannelDefaultAccount("wechat",selected.uid)
        var expected=10000L
        Direction.entries.forEachIndexed { index,direction ->
            val value=bill(amount=500L+index,direction=direction,time=at+index*300_000L,source=Source.OCR,origin=PaymentApps.WECHAT)
            val review=(store.ingest(value.capture,value.bill.copy(reliable=false)) as IngestResult.Saved).record
            assertEquals(RecordState.REVIEW,review.state);assertNull(review.accountUid)
            assertEquals(expected,balance(store,selected))
            val posted=(store.ingest(value.capture,value.bill) as IngestResult.Saved).record
            expected+=if(direction==Direction.EXPENSE)-value.bill.amountCents!! else value.bill.amountCents!!
            assertEquals(review.id,posted.id);assertEquals(selected.uid,posted.accountUid);assertEquals(expected,balance(store,selected))
            store.ingest(value.capture,value.bill)
            assertEquals(expected,balance(store,selected))
            val unlinked=store.saveEdited(posted,selectedAccountUid=null,accountSelectionSpecified=true)
            expected-=posted.accountEffect
            store.saveEdited(unlinked.copy(note="显式不关联"));store.ingest(value.capture,value.bill)
            assertNull(store.get(posted.id)!!.accountUid);assertEquals(expected,balance(store,selected))
        }
    }

    @Test fun selectedManualAccountCreatesEditsSwitchesAndUnlinksInOneTransaction()=withStore { store,_ ->
        val a=account(store,"默认")
        val b=account(store,"选择账户")
        store.setDefaultAccount(a.uid)
        val created=store.saveEdited(bill(),b.uid,true)
        assertEquals(b.uid,created.accountUid);assertEquals(10000L,balance(store,a));assertEquals(9900L,balance(store,b))
        val edited=store.saveEdited(created.copy(bill=created.bill.copy(amountCents=250),accountEffect=0),a.uid,true)
        assertEquals(a.uid,edited.accountUid);assertEquals(9750L,balance(store,a));assertEquals(10000L,balance(store,b))
        store.saveEdited(edited,a.uid,true)
        assertEquals(9750L,balance(store,a))
        val unlinked=store.saveEdited(edited,selectedAccountUid=null,accountSelectionSpecified=true)
        assertNull(unlinked.accountUid);assertEquals(10000L,balance(store,a))
        assertNull(store.saveEdited(bill(time=at+1),selectedAccountUid=null,accountSelectionSpecified=true).accountUid)
        assertEquals(10000L,balance(store,a))
        assertEquals(a.uid,store.saveEdited(bill(time=at+2)).accountUid)
    }

    @Test fun invalidOrOverflowingSelectionRollsBackBothNewAndEditedBills()=withStore { store,_ ->
        val a=account(store,"原账户")
        val minimum=account(store,"余额下限",-Money.MAX_CENTS)
        val saved=store.saveEdited(bill(),a.uid,true)
        val records=store.all();val accounts=store.accounts();val history=store.captureHistory(saved.id)
        val missing=UUID.randomUUID().toString()
        reject { store.saveEdited(saved.copy(note="不能留下的修改",bill=saved.bill.copy(amountCents=300)),missing,true) }
        reject { store.saveEdited(saved.copy(note="不能留下的修改",bill=saved.bill.copy(amountCents=300)),minimum.uid,true) }
        reject { store.saveEdited(bill(time=at+1),missing,true) }
        reject { store.saveEdited(bill(time=at+2),minimum.uid,true) }
        assertEquals(records,store.all());assertEquals(accounts,store.accounts());assertEquals(history,store.captureHistory(saved.id))
        store.saveAccount(store.getAccount(a.id)!!.copy(amountCents=Money.MAX_CENTS))
        val noteOnly=store.saveEdited(saved.copy(note="余额修正后仅改备注"),a.uid,true)
        assertEquals("余额修正后仅改备注",noteOnly.note);assertEquals(Money.MAX_CENTS,balance(store,a))
    }

    @Test fun selectedRefundRetainsNetEffectAndReversalSemantics()=withStore { store,_ ->
        val a=account(store,"原支付账户")
        val b=account(store,"退款后的关联负债",type=AccountType.LIABILITY)
        val original=bill(amount=1800,source=Source.OCR,origin=PaymentApps.WECHAT)
        val posted=(store.ingest(original.capture,original.bill) as IngestResult.Saved).record
        val refundedBill=posted.bill.copy(amountCents=1200,refund=RefundInfo("wechat",1800,600,"cumulative"))
        store.ingest(posted.capture.copy(text="合成退款",receivedAt=at+1000),refundedBill)
        val refunded=store.get(posted.id)!!
        val moved=store.saveEdited(refunded.copy(note="仅换关联"),b.uid,true)
        assertEquals(10000L,balance(store,a));assertEquals(11200L,balance(store,b));assertEquals(refunded.bill.refund,moved.bill.refund)
        store.softDelete(moved.id);assertEquals(10000L,balance(store,b))
        store.undoDelete(moved.id);assertEquals(11200L,balance(store,b))
    }

    @Test fun transfersHaveCorrectAssetAndLiabilityLegsAndRepeatedSavesEditsDeletesAreIdempotent()=withStore { store,_ ->
        val a=account(store,"资产A")
        val b=account(store,"资产B",20000)
        val debt=account(store,"负债",5000,AccountType.LIABILITY)
        val transfers=AccountTransfers(store)
        val value=AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=300,occurredAt=at,note="内部调拨")
        assertEquals(value,transfers.save(value));transfers.save(value)
        assertEquals(9700L,balance(store,a));assertEquals(20300L,balance(store,b));assertEquals(1,transfers.list().size)
        val edited=transfers.save(value.copy(toUid=debt.uid,amountCents=600,note="信用卡还款"))
        assertEquals(9400L,balance(store,a));assertEquals(20000L,balance(store,b));assertEquals(4400L,balance(store,debt))
        assertEquals(edited,transfers.get(value.uid));assertTrue(transfers.list(b.uid).isEmpty())
        transfers.delete(value.uid);transfers.delete(value.uid)
        assertEquals(10000L,balance(store,a));assertEquals(5000L,balance(store,debt));assertNull(transfers.get(value.uid))
        val borrowed=transfers.save(value.copy(fromUid=debt.uid,toUid=a.uid,amountCents=700))
        assertEquals(10700L,balance(store,a));assertEquals(5700L,balance(store,debt))
        val debt2=account(store,"负债二",3000,AccountType.LIABILITY)
        val debtMovement=transfers.save(AccountTransfer(fromUid=debt.uid,toUid=debt2.uid,amountCents=100,occurredAt=at+1))
        assertEquals(5800L,balance(store,debt));assertEquals(2900L,balance(store,debt2))
        transfers.delete(debtMovement.uid);transfers.delete(borrowed.uid)
        assertEquals(10000L,balance(store,a));assertEquals(5000L,balance(store,debt));assertEquals(3000L,balance(store,debt2))
        assertTrue(store.all().isEmpty());assertEquals(0L,LedgerReadView(store).summary(month).getJSONObject("totals").getLong("expense"))
    }

    @Test fun transferErrorsAndOverflowRollBackBothLegsIncludingFailedEditsAndDeletes()=withStore { store,_ ->
        val a=account(store,"转出")
        val b=account(store,"转入")
        val maximum=account(store,"余额上限",Money.MAX_CENTS)
        val transfers=AccountTransfers(store)
        val saved=transfers.save(AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=200,occurredAt=at))
        val accounts=store.accounts()
        reject { transfers.save(saved.copy(toUid=a.uid)) }
        reject { transfers.save(saved.copy(toUid=UUID.randomUUID().toString())) }
        reject { transfers.save(saved.copy(amountCents=0)) }
        reject { transfers.save(saved.copy(amountCents=Money.MAX_CENTS+1)) }
        reject { transfers.save(saved.copy(toUid=maximum.uid)) }
        reject { transfers.save(saved.copy(uid=UUID.randomUUID().toString(),toUid=maximum.uid)) }
        assertEquals(accounts,store.accounts());assertEquals(listOf(saved),transfers.list())
        // Explicit balance corrections can make a reversal overflow; neither leg may be left reversed.
        store.saveAccount(store.getAccount(a.id)!!.copy(amountCents=Money.MAX_CENTS))
        val noteOnly=transfers.save(saved.copy(note="仅改备注不临时回冲"))
        val corrected=store.accounts()
        reject { transfers.delete(saved.uid) }
        assertEquals(corrected,store.accounts());assertEquals(noteOnly,transfers.get(saved.uid))
    }

    @Test fun accountsWithTransferHistoryBlockDeletionAndTypeChangeUntilExplicitReversal()=withStore { store,_ ->
        val a=account(store,"受保护转出")
        val b=account(store,"受保护转入",included=false)
        val transfers=AccountTransfers(store)
        val saved=transfers.save(AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=200,occurredAt=at))
        val before=store.accounts()
        for(value in listOf(a,b)) {
            val deleted=runCatching { store.deleteAccount(value.id) }.exceptionOrNull()
            assertTrue(deleted?.message.orEmpty().contains("转账"))
            reject { store.saveAccount(store.getAccount(value.id)!!.copy(type=AccountType.LIABILITY)) }
        }
        assertEquals(before,store.accounts());assertEquals(saved,transfers.get(saved.uid))
        store.saveAccount(store.getAccount(b.id)!!.copy(name="可改名称",includedInAssets=true))
        transfers.delete(saved.uid)
        store.saveAccount(store.getAccount(a.id)!!.copy(type=AccountType.LIABILITY))
        store.deleteAccount(b.id);assertNull(store.getAccount(b.id))
    }

    @Test fun versionThirteenUpgradeAddsOnlySchemaAndPreservesUnlinkedBillsBalancesAndPreferences()=withStore { store,name ->
        val historical=store.saveEdited(bill())
        val a=account(store,"v13 账户",-500)
        store.setChannelDefaultAccount("wechat",a.uid)
        val linked=store.saveEdited(bill(time=at+1),a.uid,true)
        val records=store.all();val accounts=store.accounts();val history=store.captureHistory(linked.id)
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE accounts RENAME TO fixture_accounts")
                db.execSQL("""CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,description TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>=0),
                    kind TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('ASSET','LIABILITY')),balance_adjustment INTEGER NOT NULL DEFAULT 0)""")
                db.execSQL("INSERT INTO accounts(id,uid,name,description,amount,kind,type,balance_adjustment) SELECT id,uid,name,description,amount,kind,type,balance_adjustment FROM fixture_accounts")
                db.execSQL("DROP TABLE fixture_accounts");db.execSQL("DROP TABLE account_transfers")
                db.version=13;db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        LedgerStore(context,name).use { upgraded ->
            assertEquals(14,upgraded.readableDatabase.version);assertEquals(records,upgraded.all());assertEquals(accounts,upgraded.accounts())
            assertEquals(history,upgraded.captureHistory(linked.id));assertNull(upgraded.get(historical.id)!!.accountUid)
            assertEquals(mapOf("wechat" to a.uid),upgraded.channelDefaultAccounts());assertTrue(AccountTransfers(upgraded).list().isEmpty())
            assertTrue(upgraded.accounts().all { it.includedInAssets })
        }
    }

    @Test fun jsonAndZipPreserveTransfersExclusionSnapshotsAndLocalEditsAcrossRepeatedRestores()=withStore { source,_ ->
        val a=account(source,"备份转出")
        val b=account(source,"备份转入",included=false)
        source.setDefaultAccount(b.uid);source.setChannelDefaultAccount("wechat",a.uid)
        source.saveEdited(bill(amount=200),a.uid,true)
        val saved=AccountTransfers(source).save(AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=1000,occurredAt=at+1,note="保留 \\\"\n备注"))
        val json=LedgerBackup.encode(source,ChannelSettings(context))
        assertEquals(8,JSONObject(json).getInt("version"))
        val portable=LedgerBackup.decode(json.byteInputStream())
        assertEquals(listOf(saved),portable.transfers);assertFalse(portable.accounts.single { it.uid==b.uid }.includedInAssets)
        val zip=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
        for(useZip in listOf(false,true))withStore { target,_ ->
            fun restore():Int=if(useZip)LedgerArchive.read(zip.inputStream(),context.cacheDir).use { LedgerArchive.restore(target,it) } else LedgerBackup.restore(target,portable)
            assertEquals(1,restore());assertEquals(0,restore())
            assertEquals(8800L,target.accounts().single { it.uid==a.uid }.amountCents)
            assertEquals(11000L,target.accounts().single { it.uid==b.uid }.amountCents)
            assertEquals(listOf(saved),AccountTransfers(target).list());assertEquals(source.channelDefaultAccounts(),target.channelDefaultAccounts())
            val local=target.accounts().single { it.uid==b.uid }
            target.saveAccount(local.copy(name="本地新名称",amountCents=-123,includedInAssets=true))
            target.setDefaultAccount(a.uid);target.setChannelDefaultAccount("wechat",null)
            val locallyEdited=AccountTransfers(target).save(saved.copy(note="本地转账备注"))
            val before=target.accounts()
            assertEquals(0,restore());assertEquals(before,target.accounts())
            assertEquals(locallyEdited,AccountTransfers(target).get(saved.uid));assertEquals(a.uid,target.defaultAccount()!!.uid)
            assertTrue(target.channelDefaultAccounts().isEmpty())
        }
    }

    @Test fun mixedAccountRestoreAppliesOnlyMissingExistingLegAndNeverRepeatsItForJsonOrZip()=withStore { source,_ ->
        val a=account(source,"备份甲")
        val b=account(source,"备份乙",5000,included=false)
        val saved=AccountTransfers(source).save(AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=1000,occurredAt=at))
        val portable=backup(source)
        val zip=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
        for(existingUid in listOf(a.uid,b.uid))for(useZip in listOf(false,true))withStore { target,_ ->
            val existing=if(existingUid==a.uid)a else b
            target.saveAccount(existing.copy(id=0,name="本地账户",amountCents=20000,includedInAssets=true))
            target.setChannelDefaultAccount("alipay",existingUid)
            fun restore() { if(useZip)LedgerArchive.read(zip.inputStream(),context.cacheDir).use { LedgerArchive.restore(target,it) } else LedgerBackup.restore(target,portable) }
            restore();restore()
            assertEquals(if(existingUid==a.uid)19000L else 9000L,target.accounts().single { it.uid==a.uid }.amountCents)
            assertEquals(if(existingUid==b.uid)21000L else 6000L,target.accounts().single { it.uid==b.uid }.amountCents)
            val kept=target.accounts().single { it.uid==existingUid }
            assertEquals("本地账户",kept.name);assertTrue(kept.includedInAssets)
            assertEquals(mapOf("alipay" to existingUid),target.channelDefaultAccounts());assertEquals(listOf(saved),AccountTransfers(target).list())
            AccountTransfers(target).delete(saved.uid)
            assertEquals(if(existingUid==a.uid)20000L else 10000L,target.accounts().single { it.uid==a.uid }.amountCents)
            assertEquals(if(existingUid==b.uid)20000L else 5000L,target.accounts().single { it.uid==b.uid }.amountCents)
        }
    }

    @Test fun mixedSnapshotRestoreAppliesNetMissingTransferEffectsAtBothBalanceLimitsRegardlessOfHistoryOrder() {
        for(edge in listOf(Money.MAX_CENTS,-Money.MAX_CENTS))withStore { source,_ ->
            val a=account(source,"边界账户",edge)
            val b=account(source,"新快照账户",0,included=false)
            val transfers=AccountTransfers(source)
            val first=transfers.save(AccountTransfer(fromUid=if(edge>0)a.uid else b.uid,
                toUid=if(edge>0)b.uid else a.uid,amountCents=100,occurredAt=at))
            transfers.save(AccountTransfer(fromUid=first.toUid,toUid=first.fromUid,amountCents=100,occurredAt=at+1))
            assertEquals(edge,balance(source,a));assertEquals(0L,balance(source,b))
            val portable=backup(source)
            val zip=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
            for(mode in listOf("json","jsonReverse","zip"))withStore { target,_ ->
                val existing=target.saveAccount(a.copy(id=0,name="保留本地边界账户"))
                fun restore() {
                    when(mode) {
                        "zip" -> LedgerArchive.read(zip.inputStream(),context.cacheDir).use { LedgerArchive.restore(target,it) }
                        "jsonReverse" -> LedgerBackup.restore(target,portable.copy(transfers=portable.transfers.reversed()))
                        else -> LedgerBackup.restore(target,portable)
                    }
                }
                restore();restore()
                assertEquals(edge,balance(target,existing))
                val restored=target.accounts().single { it.uid==b.uid }
                assertEquals(0L,restored.amountCents);assertFalse(restored.includedInAssets)
                assertEquals("保留本地边界账户",target.getAccount(existing.id)!!.name)
                assertEquals(transfers.list(),AccountTransfers(target).list())
            }
        }
    }

    @Test fun transferBatchRestoreUsesLocalAccountTypeAndPersistsIndividualReversibleLegs()=withStore { source,_ ->
        val a=account(source,"源资产账户",0)
        val b=account(source,"新资产账户",0)
        val first=AccountTransfers(source).save(AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=100,occurredAt=at))
        val second=AccountTransfers(source).save(AccountTransfer(fromUid=b.uid,toUid=a.uid,amountCents=100,occurredAt=at+1))
        val portable=backup(source)
        withStore { target,_ ->
            val local=target.saveAccount(a.copy(id=0,type=AccountType.LIABILITY,amountCents=Money.MAX_CENTS,name="本地负债"))
            LedgerBackup.restore(target,portable);LedgerBackup.restore(target,portable)
            assertEquals(Money.MAX_CENTS,balance(target,local));assertEquals(AccountType.LIABILITY,target.getAccount(local.id)!!.type)
            val restoredB=target.accounts().single { it.uid==b.uid }
            // The first outgoing liability leg was +100, even though the backup's source was an asset.
            AccountTransfers(target).delete(first.uid)
            assertEquals(Money.MAX_CENTS-100,balance(target,local));assertEquals(-100L,balance(target,restoredB))
            AccountTransfers(target).delete(second.uid)
            assertEquals(Money.MAX_CENTS,balance(target,local));assertEquals(0L,balance(target,restoredB))
        }
    }

    @Test fun batchRestoreSkipsExistingTransferUuidsAndRollsBackGenuineFinalOverflowAtBothLimits() {
        for(edge in listOf(Money.MAX_CENTS,-Money.MAX_CENTS))withStore { source,_ ->
            val a=account(source,"源账户",0)
            val b=account(source,"对端账户",0)
            account(source,"应回滚的新快照",77)
            source.saveEdited(bill(),selectedAccountUid=null,accountSelectionSpecified=true)
            val first=AccountTransfers(source).save(AccountTransfer(fromUid=if(edge>0)a.uid else b.uid,
                toUid=if(edge>0)b.uid else a.uid,amountCents=100,occurredAt=at))
            val second=AccountTransfers(source).save(AccountTransfer(fromUid=first.toUid,toUid=first.fromUid,amountCents=100,occurredAt=at+1))
            val portable=backup(source)
            withStore { target,_ ->
                val local=target.saveAccount(a.copy(id=0))
                target.saveAccount(b.copy(id=0))
                val existing=AccountTransfers(target).save(first.copy(note="保留本地已存在 UUID"))
                target.saveAccount(target.getAccount(local.id)!!.copy(amountCents=edge))
                val before=target.accounts()
                // Only the second UUID is missing. Counting the existing first again
                // would incorrectly cancel its genuinely overflowing new counterpart.
                reject { LedgerBackup.restore(target,portable) }
                assertEquals(before,target.accounts());assertTrue(target.all().isEmpty())
                assertEquals(listOf(existing),AccountTransfers(target).list());assertNull(AccountTransfers(target).get(second.uid))
            }
        }
    }

    @Test fun combinedBillAndTransferRestoreAppliesOnlyFinalExistingBalanceForJsonAndZipAndRepeats() {
        for(edge in listOf(10000L,Money.MAX_CENTS,-Money.MAX_CENTS))withStore { source,_ ->
            val outgoing=edge>=0
            val a=account(source,"源已有账户",edge+if(outgoing)-100 else 100)
            val b=account(source,"新导入快照账户",0,included=false)
            val record=source.saveEdited(bill(amount=100,direction=if(outgoing)Direction.INCOME else Direction.EXPENSE),a.uid,true)
            val transfer=AccountTransfers(source).save(AccountTransfer(fromUid=if(outgoing)a.uid else b.uid,
                toUid=if(outgoing)b.uid else a.uid,amountCents=100,occurredAt=at+1,note="与账单抵消"))
            val portable=backup(source)
            val zip=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
            for(useZip in listOf(false,true))withStore { target,_ ->
                val local=target.saveAccount(a.copy(id=0,name="保留本地余额",amountCents=edge))
                target.setChannelDefaultAccount("wechat",local.uid)
                fun restore():Int=if(useZip)LedgerArchive.read(zip.inputStream(),context.cacheDir).use { LedgerArchive.restore(target,it) }
                    else LedgerBackup.restore(target,portable)
                assertEquals(1,restore());assertEquals(0,restore())
                assertEquals(edge,balance(target,local));assertEquals("保留本地余额",target.getAccount(local.id)!!.name)
                val restoredB=target.accounts().single { it.uid==b.uid }
                assertEquals(if(outgoing)100L else -100L,restoredB.amountCents);assertFalse(restoredB.includedInAssets)
                assertEquals(mapOf("wechat" to local.uid),target.channelDefaultAccounts())
                val restoredRecord=target.all().single()
                assertEquals(record.uid,restoredRecord.uid);assertEquals(if(outgoing)100L else -100L,restoredRecord.accountEffect)
                assertEquals(listOf(transfer),AccountTransfers(target).list())
                val changed=target.saveEdited(restoredRecord.copy(note="保留本地账单修改"))
                val changedTransfer=AccountTransfers(target).save(transfer.copy(note="保留本地转账修改"))
                assertEquals(0,restore());assertEquals(changed,target.get(changed.id));assertEquals(changedTransfer,AccountTransfers(target).get(transfer.uid))
                // Each persisted effect remains independently reversible after batch application.
                target.delete(changed.id)
                assertEquals(edge+if(outgoing)-100 else 100,balance(target,local))
                AccountTransfers(target).delete(transfer.uid)
                assertEquals(edge,balance(target,local));assertEquals(0L,balance(target,restoredB))
            }
        }
    }

    @Test fun combinedBillAndTransferRestoreRejectsTrueFinalOverflowAndRollsBackJsonAndZip() {
        for(edge in listOf(Money.MAX_CENTS,-Money.MAX_CENTS))withStore { source,_ ->
            val outgoing=edge>0
            val a=account(source,"已有边界账户",0)
            val b=account(source,"应回滚的对端快照",0)
            account(source,"应回滚的其他快照",77)
            source.addCategory(CustomCategory("应回滚分类",Direction.EXPENSE))
            source.saveEdited(bill(amount=200,direction=if(outgoing)Direction.INCOME else Direction.EXPENSE),a.uid,true)
            AccountTransfers(source).save(AccountTransfer(fromUid=if(outgoing)a.uid else b.uid,
                toUid=if(outgoing)b.uid else a.uid,amountCents=100,occurredAt=at+1))
            val portable=backup(source)
            val zip=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
            for(useZip in listOf(false,true))withStore { target,_ ->
                val local=target.saveAccount(a.copy(id=0,name="原本地余额",amountCents=edge))
                val before=target.accounts()
                reject {
                    if(useZip)LedgerArchive.read(zip.inputStream(),context.cacheDir).use { LedgerArchive.restore(target,it) }
                    else LedgerBackup.restore(target,portable)
                }
                assertEquals(before,target.accounts());assertEquals(edge,balance(target,local))
                assertTrue(target.all().isEmpty());assertTrue(AccountTransfers(target).list().isEmpty());assertTrue(target.customCategories().isEmpty())
            }
        }
    }

    @Test fun combinedRestoreFailureAfterMediaDoesNotReversePendingFinancialRowsDuringCleanup()=withStore { source,_ ->
        val a=account(source,"原有边界账户",-Money.MAX_CENTS+100)
        val b=account(source,"新快照对端",0)
        source.saveEdited(bill(amount=100),a.uid,true)
        AccountTransfers(source).save(AccountTransfer(fromUid=b.uid,toUid=a.uid,amountCents=100,occurredAt=at+1))
        val portable=backup(source)
        withStore { target,_ ->
            val local=target.saveAccount(a.copy(id=0,amountCents=Money.MAX_CENTS))
            val before=target.accounts()
            val failure=runCatching {
                LedgerBackup.restore(target,portable,restoreMedia={ record ->
                    target.attachScreenshot(record.id,byteArrayOf(1,2,3))
                    throw IllegalStateException("合成媒体恢复失败")
                })
            }.exceptionOrNull()
            assertEquals("合成媒体恢复失败",failure?.message)
            assertEquals(before,target.accounts());assertEquals(Money.MAX_CENTS,balance(target,local))
            assertTrue(target.all().isEmpty());assertTrue(AccountTransfers(target).list().isEmpty())
            val next=target.saveEdited(bill(time=at+2),selectedAccountUid=null,accountSelectionSpecified=true)
            assertNull(target.screenshot(next.id))
        }
    }

    @Test fun backupVersionsOneThroughSevenDefaultToIncludedAndNoTransfersAndOptionalFlagDefaultsTrue()=withStore { source,_ ->
        val a=account(source,"旧备份账户",included=false)
        val encoded=LedgerBackup.encode(source,ChannelSettings(context))
        for(version in 1..7) {
            val old=JSONObject(encoded).put("version",version).apply { remove("transfers") }
            old.getJSONArray("accounts").getJSONObject(0).remove("includedInAssets")
            val decoded=LedgerBackup.decode(old.toString().byteInputStream())
            assertTrue(decoded.transfers.isEmpty());assertTrue(decoded.accounts.all { it.includedInAssets })
            withStore { target,_ -> LedgerBackup.restore(target,decoded);assertTrue(target.accounts().all { it.includedInAssets }) }
        }
        val missing=JSONObject(encoded);missing.getJSONArray("accounts").getJSONObject(0).remove("includedInAssets")
        assertTrue(LedgerBackup.decode(missing.toString().byteInputStream()).accounts.single().includedInAssets)
        assertFalse(source.getAccount(a.id)!!.includedInAssets)
    }

    @Test fun malformedOrOverflowingFullRestoreRejectsWithoutLeavingAccountsRecordsOrTransferLegs()=withStore { source,_ ->
        val a=account(source,"导出甲")
        val b=account(source,"导出乙")
        source.saveEdited(bill(),a.uid,true)
        AccountTransfers(source).save(AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=100,occurredAt=at))
        val encoded=LedgerBackup.encode(source,ChannelSettings(context))
        withStore { target,_ ->
            val retained=target.saveAccount(b.copy(id=0,name="保留本地",amountCents=Money.MAX_CENTS))
            val before=target.accounts()
            val invalidFlag=JSONObject(encoded).apply { getJSONArray("accounts").getJSONObject(0).put("includedInAssets","false") }
            val invalidLeg=JSONObject(encoded).apply { getJSONArray("transfers").getJSONObject(0).put("fromEffect",0) }
            val invalidAccount=JSONObject(encoded).apply { getJSONArray("transfers").getJSONObject(0).put("toUid",UUID.randomUUID().toString()) }
            for(value in listOf(invalidFlag,invalidLeg,invalidAccount))reject { LedgerBackup.restore(target,LedgerBackup.decode(value.toString().byteInputStream())) }
            // The bill and new source account are inserted before this existing destination overflows.
            reject { LedgerBackup.restore(target,LedgerBackup.decode(encoded.byteInputStream())) }
            assertEquals(before,target.accounts());assertEquals(retained,target.getAccount(retained.id))
            assertTrue(target.all().isEmpty());assertTrue(AccountTransfers(target).list().isEmpty())
        }
    }

    @Test fun detailFiltersMonthPaginationAndTotalsReadFullNativeHistoryBeyondTwoHundred()=withStore { store,_ ->
        val a=account(store,"大量明细账户",100000)
        val b=account(store,"另一账户",included=false)
        val records=(0 until 230).map { index -> bill(time=at+index,merchant="商户\"$index").copy(accountUid=a.uid,requiresReimbursement=index%2==0) }
        val older=bill(amount=700,time=LedgerReadView(store).window(month).start-1).copy(accountUid=a.uid)
        val review=bill(amount=800,time=at+500).copy(accountUid=a.uid,state=RecordState.REVIEW)
        val income=bill(amount=500,direction=Direction.INCOME,time=at+600).copy(accountUid=a.uid)
        store.merge(records+listOf(older,review,income))
        val deleted=store.all().first { it.uid==records.first().uid };store.softDelete(deleted.id)
        val transfer=AccountTransfers(store).save(AccountTransfer(fromUid=a.uid,toUid=b.uid,amountCents=50,occurredAt=at+600,note="跨表排序"))
        val view=AccountDetailReadView(store)
        val seen=mutableSetOf<String>();var offset=0;var total=0
        do {
            val page=view.query(a.uid,month,offset=offset,limit=37)
            assertEquals(22900L,page.getLong("expenseCents"));assertEquals(500L,page.getLong("incomeCents"))
            val rows=page.getJSONArray("rows")
            for(index in 0 until rows.length()) {
                val row=rows.getJSONObject(index);assertTrue(seen.add(row.getString("kind")+row.getString("uid")));total++
                assertTrue(row.has("hasMerchantIcon"));assertTrue(row.has("requiresReimbursement"))
            }
            if(!page.getBoolean("hasMore")) { assertTrue(page.isNull("nextOffset"));break }
            offset=page.getInt("nextOffset")
        } while(true)
        assertEquals(231,total)
        assertEquals(29,view.query(a.uid,month,"expense",offset=200).getJSONArray("rows").length())
        val incoming=view.query(a.uid,month,"income")
        assertEquals(1,incoming.getJSONArray("rows").length());assertEquals(22900L,incoming.getLong("expenseCents"))
        val outgoing=view.query(a.uid,month,"transfer").getJSONArray("rows").getJSONObject(0)
        assertEquals(transfer.uid,outgoing.getString("uid"));assertEquals("OUT",outgoing.getString("direction"));assertEquals(a.name,outgoing.getString("fromName"))
        val received=view.query(b.uid,month,"transfer")
        assertEquals("IN",received.getJSONArray("rows").getJSONObject(0).getString("direction"));assertEquals(0L,received.getLong("incomeCents"))
        assertEquals(700L,view.query(a.uid,"2026-10").getLong("expenseCents"))
        assertEquals(22900L,LedgerReadView(store).summary(month).getJSONObject("totals").getLong("expense"))
        reject { view.query(a.uid,month,"invalid") };reject { view.query(a.uid,month,limit=201) };reject { view.query(a.uid,month,offset=-1) }
        reject { view.query(UUID.randomUUID().toString(),month) };reject { view.query(a.uid,"2026-13") }
    }

    @Test fun accountDetailReimbursementTotalsMatchExistingLedgerStatisticalSemantics()=withStore { store,_ ->
        val a=account(store,"历史报销账户")
        val expense=store.saveEdited(bill(amount=1000).copy(requiresReimbursement=true),a.uid,true)
        val income=store.saveEdited(bill(amount=1000,direction=Direction.INCOME,time=at+1),a.uid,true)
        val reimbursements=ReimbursementStore(context,store)
        val metadata=reimbursements.backupMetadata()
        metadata.getJSONArray("expenses").put(JSONObject().put("recordUid",expense.uid).put("requestedCents",1000)
            .put("purpose","历史差旅").put("differenceReason","").put("unitRequirements","").put("materialsReviewed",true))
        val claimUid=UUID.randomUUID().toString()
        metadata.getJSONArray("claims").put(JSONObject().put("uid",claimUid).put("title","历史单据").put("claimant","测试人").put("company","测试单位").put("reason","差旅")
            .put("department","").put("project","").put("costCenter","").put("createdAt",at).put("submittedAt",at+1).put("workflowStatus","submitted"))
        metadata.getJSONArray("items").put(JSONObject().put("recordUid",expense.uid).put("claimUid",claimUid))
        metadata.getJSONArray("repayments").put(JSONObject().put("uid",UUID.randomUUID().toString()).put("claimUid",claimUid).put("sourceRecordUid",income.uid)
            .put("amountCents",400).put("receivedAt",at+2).put("note","历史分摊"))
        reimbursements.restoreMetadata(metadata,emptyMap(),mutableListOf())
        val detail=AccountDetailReadView(store).query(a.uid,month)
        val summary=LedgerReadView(store).summary(month).getJSONObject("totals")
        assertEquals(1000L,detail.getLong("expenseCents"));assertEquals(600L,detail.getLong("incomeCents"))
        assertEquals(summary.getLong("income"),detail.getLong("incomeCents"));assertEquals(summary.getLong("expense"),detail.getLong("expenseCents"))
        val incomeRow=AccountDetailReadView(store).query(a.uid,month,"income").getJSONArray("rows").getJSONObject(0)
        assertEquals(1000L,incomeRow.getLong("amountCents"));assertEquals(600L,incomeRow.getLong("statisticalAmountCents"))
    }
}
