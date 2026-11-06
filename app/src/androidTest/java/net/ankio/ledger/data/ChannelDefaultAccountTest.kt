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

@RunWith(AndroidJUnit4::class)
class ChannelDefaultAccountTest {
    private val at=1_793_800_000_000L
    private fun withStore(test:(LedgerStore,Context,String)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="channel-default-${UUID.randomUUID()}.db"
        val store=LedgerStore(context,name)
        try { test(store,context,name) } finally { store.close();context.deleteDatabase(name) }
    }
    private fun bill(origin:String=PaymentApps.WECHAT,source:Source=Source.OCR,amount:Long=700,time:Long=at)=LedgerRecord(
        capture=Capture(source,origin,"微信 支付宝 银行卡 付款测试",time),
        bill=ParsedBill(amount,Direction.EXPENSE,"合成渠道商户","餐饮",time,UUID.randomUUID().toString(),true,"",true),
        state=RecordState.POSTED)
    private fun decode(json:String)=LedgerBackup.decode(json.byteInputStream())
    private fun reject(block:()->Unit) { assertTrue("Expected rejection",runCatching(block).isFailure) }

    @Test fun exactAppOriginUsesAnyChosenAccountAndUnknownManualImageUseGlobal()=withStore { store,_,_ ->
        val historical=store.saveEdited(bill())
        assertEquals(RecordState.POSTED,historical.state);assertNull(historical.accountUid);assertEquals(0L,historical.accountEffect)
        val global=store.saveAccount(LedgerAccount(name="全局钱包",kind="cash",amountCents=10000))
        val bank=store.saveAccount(LedgerAccount(name="任意银行卡",kind="bank_boc",amountCents=10000))
        val debt=store.saveAccount(LedgerAccount(name="支付宝任意负债钱包",kind="wechat",type=AccountType.LIABILITY,amountCents=10000))
        store.setDefaultAccount(global.uid)
        store.setChannelDefaultAccount("wechat",bank.uid);store.setChannelDefaultAccount("alipay",debt.uid)
        val wechat=store.saveEdited(bill())
        val alipay=store.saveEdited(bill(PaymentApps.ALIPAY,amount=800))
        assertEquals(bank.uid,wechat.accountUid);assertEquals(debt.uid,alipay.accountUid)
        listOf(bill("unknown",amount=100),bill(PaymentApps.WECHAT,Source.MANUAL,100),bill("image",Source.IMAGE,100)).forEach {
            assertEquals(global.uid,store.saveEdited(it).accountUid)
        }
        assertEquals(9700L,store.getAccount(global.id)!!.amountCents)
        assertEquals(9300L,store.getAccount(bank.id)!!.amountCents)
        assertEquals(10800L,store.getAccount(debt.id)!!.amountCents)
        assertNull(store.get(historical.id)!!.accountUid)
        store.delete(wechat.id);store.delete(alipay.id)
        assertEquals(10000L,store.getAccount(bank.id)!!.amountCents)
        assertEquals(10000L,store.getAccount(debt.id)!!.amountCents)
    }

    @Test fun newAndExplicitReviewConfirmationAssignButHistoricalUnlinkedDuplicateAndSupplementDoNot()=withStore { store,_,_ ->
        val original=bill(amount=1800)
        val historical=(store.ingest(original.capture,original.bill) as IngestResult.Saved).record
        val bank=store.saveAccount(LedgerAccount(name="渠道银行卡",kind="bank_icbc",amountCents=10000))
        val other=store.saveAccount(LedgerAccount(name="后来设置的账户",amountCents=10000))
        store.setChannelDefaultAccount("wechat",bank.uid)
        store.saveEdited(historical.copy(note="历史编辑"))
        store.ingest(original.capture,original.bill)
        val supplement=store.ingest(original.capture.copy(text="合成历史详情",receivedAt=at+1000),original.bill,
            screenshot=byteArrayOf(1,2,3)) as IngestResult.Saved
        assertTrue(supplement.supplemented);assertEquals(historical.id,supplement.record.id)
        assertNull(store.get(historical.id)!!.accountUid);assertEquals(10000L,store.getAccount(bank.id)!!.amountCents)
        val newBill=bill(amount=1900,time=at+60_000)
        val linked=(store.ingest(newBill.capture,newBill.bill) as IngestResult.Saved).record
        assertEquals(bank.uid,linked.accountUid);assertEquals(8100L,store.getAccount(bank.id)!!.amountCents)
        val unlinked=store.setRecordAccount(linked.id,linked.uid,null)
        store.setChannelDefaultAccount("wechat",other.uid)
        store.saveEdited(unlinked.copy(note="明确不关联"));store.ingest(newBill.capture,newBill.bill)
        assertNull(store.get(linked.id)!!.accountUid)
        assertEquals(10000L,store.getAccount(bank.id)!!.amountCents);assertEquals(10000L,store.getAccount(other.id)!!.amountCents)
        val pendingBill=bill(amount=2000,time=at+120_000)
        val pending=(store.ingest(pendingBill.capture,pendingBill.bill.copy(reliable=false)) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,pending.state);assertNull(pending.accountUid)
        assertEquals(other.uid,store.saveEdited(pending).accountUid)
        assertEquals(8000L,store.getAccount(other.id)!!.amountCents)
        val autoBill=bill(amount=2100,time=at+180_000)
        val autoDraft=(store.ingest(autoBill.capture,autoBill.bill.copy(reliable=false)) as IngestResult.Saved).record
        val autoPosted=(store.ingest(autoBill.capture.copy(text="合成完整详情"),autoBill.bill) as IngestResult.Saved).record
        assertEquals(autoDraft.id,autoPosted.id);assertEquals(RecordState.POSTED,autoPosted.state)
        assertEquals(other.uid,autoPosted.accountUid);assertEquals(5900L,store.getAccount(other.id)!!.amountCents)
    }

    @Test fun preferencesPersistClearIndependentlyPreserveGlobalAndRejectInvalidWrites()=withStore { store,context,name ->
        val oldest=store.saveAccount(LedgerAccount(name="最早账户",amountCents=10000))
        val bank=store.saveAccount(LedgerAccount(name="渠道银行",kind="bank_ccb",amountCents=10000))
        val global=store.saveAccount(LedgerAccount(name="全局账户",amountCents=10000))
        assertEquals(oldest.uid,store.defaultAccount("wechat")!!.uid)
        store.setChannelDefaultAccount("wechat",bank.uid);store.setChannelDefaultAccount("alipay",bank.uid)
        store.setDefaultAccount(global.uid)
        val before=store.channelDefaultAccounts()
        reject { store.setChannelDefaultAccount("unknown",global.uid) }
        reject { store.setChannelDefaultAccount("wechat",UUID.randomUUID().toString()) }
        reject { store.setChannelDefaultAccount("alipay","not-a-uuid") }
        assertEquals(before,store.channelDefaultAccounts());assertEquals(global.uid,store.defaultAccount()!!.uid)
        store.close()
        LedgerStore(context,name).use { reopened ->
            assertEquals(before,reopened.channelDefaultAccounts())
            reopened.setDefaultAccount(oldest.uid)
            assertEquals(before,reopened.channelDefaultAccounts())
            reopened.setChannelDefaultAccount("wechat",null)
            assertEquals(oldest.uid,reopened.defaultAccount("wechat")!!.uid)
            assertEquals(bank.uid,reopened.defaultAccount("alipay")!!.uid)
            reopened.setChannelDefaultAccount("wechat",bank.uid)
            val linked=reopened.saveEdited(bill())
            reopened.deleteAccount(bank.id)
            assertTrue(reopened.channelDefaultAccounts().isEmpty())
            assertNull(reopened.get(linked.id)!!.accountUid);assertEquals(0L,reopened.get(linked.id)!!.accountEffect)
            assertEquals(oldest.uid,reopened.defaultAccount("alipay")!!.uid)
            reopened.setDefaultAccount(null)
            assertEquals(oldest.uid,reopened.defaultAccount("unknown")!!.uid)
            assertEquals(10000L,reopened.getAccount(oldest.id)!!.amountCents)
        }
    }

    @Test fun actualVersionTwelvePreferencesUpgradeWithoutChangingBalancesLinksOrGlobalDefault()=withStore { store,context,name ->
        val oldest=store.saveAccount(LedgerAccount(name="旧账户",amountCents=10000))
        val selected=store.saveAccount(LedgerAccount(name="旧全局默认",amountCents=20000))
        store.setDefaultAccount(selected.uid)
        val record=store.saveEdited(bill())
        val beforeAccounts=store.accounts();val beforeHistory=store.captureHistory(record.id)
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE account_preferences RENAME TO fixture_preferences")
                db.execSQL("CREATE TABLE account_preferences(singleton INTEGER PRIMARY KEY CHECK(singleton=1),default_uid TEXT)")
                db.execSQL("INSERT INTO account_preferences(singleton,default_uid) SELECT singleton,default_uid FROM fixture_preferences")
                db.execSQL("DROP TABLE fixture_preferences");db.version=12;db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        LedgerStore(context,name).use { upgraded ->
            assertEquals(14,upgraded.readableDatabase.version)
            assertEquals(beforeAccounts,upgraded.accounts());assertEquals(record,upgraded.get(record.id))
            assertEquals(beforeHistory,upgraded.captureHistory(record.id))
            assertEquals(selected.uid,upgraded.defaultAccount()!!.uid);assertTrue(upgraded.channelDefaultAccounts().isEmpty())
            upgraded.setChannelDefaultAccount("wechat",oldest.uid)
            upgraded.saveEdited(record.copy(note="迁移后编辑"))
            assertEquals(beforeAccounts,upgraded.accounts());assertEquals(selected.uid,upgraded.get(record.id)!!.accountUid)
            assertEquals(oldest.uid,upgraded.saveEdited(bill()).accountUid)
        }
    }

    @Test fun jsonAndZipRoundTripsRestoreExplicitDefaultsAndSnapshotsOnlyOnceWhileLocalPreferencesWin()=withStore { source,context,_ ->
        val global=source.saveAccount(LedgerAccount(name="备份全局",amountCents=20000))
        val bank=source.saveAccount(LedgerAccount(name="备份渠道银行",kind="bank_abc",amountCents=10000))
        source.setDefaultAccount(global.uid);source.setChannelDefaultAccount("wechat",bank.uid);source.setChannelDefaultAccount("alipay",global.uid)
        source.saveEdited(bill())
        val encoded=LedgerBackup.encode(source,ChannelSettings(context));val backup=decode(encoded)
        assertEquals(8,JSONObject(encoded).getInt("version"));assertEquals(source.channelDefaultAccounts(),backup.channelDefaultAccounts)
        withStore { target,_,_ ->
            assertEquals(1,LedgerBackup.restore(target,backup));assertEquals(0,LedgerBackup.restore(target,backup))
            assertEquals(source.channelDefaultAccounts(),target.channelDefaultAccounts());assertEquals(global.uid,target.defaultAccount()!!.uid)
            assertEquals(9300L,target.accounts().single { it.uid==bank.uid }.amountCents)
            val local=target.saveAccount(LedgerAccount(name="本地默认",amountCents=5000))
            target.setDefaultAccount(local.uid);target.setChannelDefaultAccount("wechat",local.uid);target.setChannelDefaultAccount("alipay",null)
            assertEquals(0,LedgerBackup.restore(target,backup));assertEquals(mapOf("wechat" to local.uid),target.channelDefaultAccounts())
            assertEquals(local.uid,target.defaultAccount()!!.uid)
        }
        withStore { target,_,_ ->
            val local=target.saveAccount(LedgerAccount(name="非空目标本地账户",amountCents=5000))
            target.setChannelDefaultAccount("alipay",local.uid)
            assertEquals(1,LedgerBackup.restore(target,backup));assertEquals(mapOf("alipay" to local.uid),target.channelDefaultAccounts())
            assertEquals(local.uid,target.defaultAccount()!!.uid);assertEquals(9300L,target.accounts().single { it.uid==bank.uid }.amountCents)
        }
        val zip=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
        withStore { target,_,_ ->
            LedgerArchive.read(zip.inputStream(),context.cacheDir).use { prepared ->
                assertEquals(1,LedgerArchive.restore(target,prepared));assertEquals(0,LedgerArchive.restore(target,prepared))
            }
            assertEquals(source.channelDefaultAccounts(),target.channelDefaultAccounts());assertEquals(global.uid,target.defaultAccount()!!.uid)
            assertEquals(9300L,target.accounts().single { it.uid==bank.uid }.amountCents)
        }
        val legacy=JSONObject(encoded).put("version",6).apply { remove("channelDefaultAccounts") }.toString()
        assertTrue(decode(legacy).channelDefaultAccounts.isEmpty())
        withStore { target,_,_ ->
            LedgerArchive.read(legacy.byteInputStream(),context.cacheDir).use { prepared ->
                assertTrue(prepared.legacy);assertEquals(1,LedgerArchive.restore(target,prepared));assertEquals(0,LedgerArchive.restore(target,prepared))
            }
            assertTrue(target.channelDefaultAccounts().isEmpty());assertEquals(global.uid,target.defaultAccount()!!.uid)
            assertEquals(9300L,target.accounts().single { it.uid==bank.uid }.amountCents)
        }
    }

    @Test fun malformedChannelMappingsRejectBeforeAnyRestoreMutation()=withStore { store,context,_ ->
        val account=store.saveAccount(LedgerAccount(name="保留账户",amountCents=10000))
        store.setChannelDefaultAccount("wechat",account.uid)
        val record=store.saveEdited(bill())
        val json=LedgerBackup.encode(store,ChannelSettings(context))
        val accounts=store.accounts();val records=store.all();val defaults=store.channelDefaultAccounts()
        val badMaps=listOf(JSONObject().put("unknown",account.uid),JSONObject().put("wechat","not-a-uuid"),
            JSONObject().put("alipay",UUID.randomUUID().toString()),JSONObject().put("wechat",1),JSONObject().put("wechat",JSONObject.NULL))
        badMaps.forEach { invalid ->
            reject { LedgerBackup.restore(store,decode(JSONObject(json).put("channelDefaultAccounts",invalid).toString())) }
            assertEquals(accounts,store.accounts());assertEquals(records,store.all());assertEquals(defaults,store.channelDefaultAccounts())
        }
        reject { LedgerBackup.restore(store,decode(JSONObject(json).put("channelDefaultAccounts","invalid").toString())) }
        assertEquals(record,store.get(record.id))
    }
}
