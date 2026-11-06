package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountNameTest {
    private fun withStore(test:(LedgerStore)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="account-name-${UUID.randomUUID()}.db"
        LedgerStore(context,name).use {store ->try {test(store)}finally {store.close();context.deleteDatabase(name)}}
    }
    private fun rejected(save:()->Unit) {
        try {save();fail("Duplicate account names must be rejected")}
        catch(error:IllegalArgumentException) {assertEquals("账户名称已存在，请使用其他名称",error.message)}
    }
    @Test fun creationRenameAndSelfEditPreserveAtomicFinancialState()=withStore {store ->
        val a=store.saveAccount(LedgerAccount(name="Wallet A",kind="wechat",amountCents=12345))
        val b=store.saveAccount(LedgerAccount(name="合成备用账户",kind="alipay",amountCents=67890))
        rejected {store.saveAccount(LedgerAccount(name="Ｗａｌｌｅｔ Ａ",kind="cash",amountCents=90000))}
        rejected {store.saveAccount(b.copy(name="wallet a",amountCents=1))}
        assertEquals(2,store.accounts().size)
        assertEquals(b,store.getAccount(b.id))
        val saved=store.saveAccount(a.copy(description="备注更新",amountCents=12346))
        assertEquals(a.uid,saved.uid);assertEquals(12346L,store.getAccount(a.id)!!.amountCents)
    }
    @Test fun invisiblePrefixCannotBypassCanonicalDuplicateValidation()=withStore {store ->
        val a=store.saveAccount(LedgerAccount(name="Wallet",amountCents=100))
        rejected {store.saveAccount(LedgerAccount(name="\uFEFFWallet",amountCents=200))}
        assertEquals(1,store.accounts().size)
        assertEquals(a,store.getAccount(a.id))
        assertEquals(101L,store.saveAccount(a.copy(amountCents=101)).amountCents)
        rejected {store.saveAccount(LedgerAccount(name="\uFEFF\uFEFFWallet",amountCents=300))}
        val prefixFirst=store.saveAccount(LedgerAccount(name="\uFEFF独立账户",amountCents=400))
        assertEquals("SQLite UTF-16 consumes the leading BOM","独立账户",store.getAccount(prefixFirst.id)!!.name)
        rejected {store.saveAccount(LedgerAccount(name="独立账户",amountCents=500))}
        try {store.saveAccount(LedgerAccount(name="\uFEFF",amountCents=1));fail("Invisible-only names cannot become empty stored names")}
        catch(error:IllegalArgumentException){assertEquals("账户名称需为 1 至 40 个字符",error.message)}
    }
    @Test fun historicalBackupDuplicatesRemainUntilTheUserRenamesThem()=withStore {store ->
        val a=LedgerAccount(name="历史重名",amountCents=10000)
        val b=LedgerAccount(name="历史重名",amountCents=20000)
        store.merge(emptyList(),listOf(a,b))
        val rows=store.accounts();assertEquals(2,rows.size)
        rejected {store.saveAccount(rows[0].copy(description="不能绕过重名校验"))}
        assertEquals(listOf(10000L,20000L),store.accounts().map {it.amountCents})
        val corrected=store.saveAccount(rows[0].copy(name="历史重名（已区分）"))
        assertEquals(a.uid,corrected.uid);assertEquals(2,store.accounts().size)
        store.saveAccount(rows[1].copy(description="现在可保存"))
    }
    @Test fun editableNamesUseTwentyCodePointsAndPreserveHistoricalNames()=withStore {store ->
        val twenty=store.saveAccount(LedgerAccount(name="新".repeat(20),amountCents=100))
        val emoji=store.saveAccount(LedgerAccount(name="😀".repeat(20),amountCents=200))
        fun tooLong(save:()->Unit) {
            try {save();fail("New or renamed accounts must respect the name limit")}
            catch(error:IllegalArgumentException){assertEquals("账户名称最多 20 个字符",error.message)}
        }
        tooLong {store.saveAccount(LedgerAccount(name="长".repeat(21),amountCents=300))}
        tooLong {store.saveAccount(twenty.copy(name="😀".repeat(20)+"多",amountCents=1))}
        assertEquals(100L,store.getAccount(twenty.id)!!.amountCents)
        assertEquals(emoji.name,store.getAccount(emoji.id)!!.name)
        val legacy=LedgerAccount(name="旧".repeat(25),amountCents=400)
        store.merge(emptyList(),listOf(legacy))
        val saved=store.accounts().single {it.uid==legacy.uid}
        val updated=store.saveAccount(saved.copy(amountCents=401,description="保留历史名称"))
        assertEquals(saved.name,updated.name);assertEquals(saved.uid,updated.uid)
        tooLong {store.saveAccount(saved.copy(name="改".repeat(21),amountCents=1))}
        assertEquals(401L,store.getAccount(saved.id)!!.amountCents)
        assertEquals("改".repeat(20),store.saveAccount(updated.copy(name="改".repeat(20))).name)
    }

}
