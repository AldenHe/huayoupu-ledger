package net.ankio.ledger.data

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated real SQLite/JSON tests for account and category migration, storage and recovery. */
@RunWith(AndroidJUnit4::class)
class NativeV2DataTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private lateinit var channels: ChannelSettings
    private lateinit var databaseName: String
    private lateinit var preferenceName: String
    private val instant = 1_790_000_000_000L

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        databaseName = "native-v2-$suffix.db"
        preferenceName = "native-v2-$suffix"
        context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                application.getSharedPreferences(preferenceName, mode)
        }
        store = LedgerStore(context, databaseName)
        channels = ChannelSettings(context)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun genuineVersionOneSchemaMigratesWithoutChangingBillsOrCaptureEvents() {
        val original = record().copy(id = 37, note = "v1 原有备注")
        // Build the prior schema independently of v2's onCreate, with its actual user_version.
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL, origin TEXT NOT NULL, raw_text TEXT NOT NULL,
                received_at INTEGER NOT NULL, amount INTEGER, direction TEXT,
                merchant TEXT NOT NULL, category TEXT NOT NULL, occurred_at INTEGER NOT NULL,
                transaction_id TEXT, reliable INTEGER NOT NULL, time_explicit INTEGER NOT NULL,
                state TEXT NOT NULL, reason TEXT NOT NULL, duplicate_id INTEGER, note TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX records_time ON records(occurred_at)")
            db.execSQL("CREATE INDEX records_transaction ON records(transaction_id)")
            db.execSQL("CREATE INDEX records_received ON records(received_at)")
            db.execSQL("CREATE TABLE capture_events (event_key TEXT PRIMARY KEY NOT NULL, received_at INTEGER NOT NULL)")
            db.insertOrThrow("records", null, values(original))
            db.execSQL("INSERT INTO capture_events(event_key,received_at) VALUES (?,?)", arrayOf("synthetic-v1-event", instant))
            db.version = 1
        }
        assertEquals(listOf(original), store.all())
        assertEquals(14, store.readableDatabase.version)
        assertTrue(store.accounts().isEmpty())
        assertTrue(store.customCategories().isEmpty())
        store.readableDatabase.rawQuery("SELECT received_at FROM capture_events WHERE event_key = ?",
            arrayOf("synthetic-v1-event")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(instant, cursor.getLong(0))
        }
        val account = store.saveAccount(LedgerAccount(name = "迁移后账户", amountCents = 0, kind = "cash"))
        val category = CustomCategory("迁移后分类", Direction.EXPENSE)
        assertTrue(store.addCategory(category))
        store.close()
        store = LedgerStore(context, databaseName)
        assertEquals(original, store.get(37))
        assertEquals(account, store.getAccount(account.id))
        assertEquals(listOf(category), store.customCategories())
    }

    @Test fun versionTwoUpgradeKeepsBillsAccountsCategoriesAndAddsEmptyImageStorage() {
        val bill = store.saveEdited(record())
        val account = store.saveAccount(LedgerAccount(name = "升级保留账户", amountCents = 12345, kind = "cash"))
        val category = CustomCategory("升级分类", Direction.EXPENSE)
        store.addCategory(category)
        store.writableDatabase.execSQL("DROP TABLE capture_images")
        store.writableDatabase.execSQL("DROP TABLE merchant_icons")
        store.writableDatabase.execSQL("DROP TABLE merchant_icon_assets")
        store.close()
        LegacyLedgerSchemaFixture.project(context.getDatabasePath(databaseName).path,2)
        store = LedgerStore(context, databaseName)
        assertEquals(bill, store.get(bill.id))
        assertEquals(account, store.getAccount(account.id))
        assertEquals(listOf(category), store.customCategories())
        assertEquals(14, store.readableDatabase.version)
        assertTrue(store.screenshotIds().isEmpty())
    }

    @Test fun accountsAndDirectionalCategoriesPersistValidateAndDoNotMutateBills() {
        val initial = store.saveAccount(LedgerAccount(name = "合成银行卡", description = "初始零余额",
            amountCents = 0, kind = "bank"))
        val edited = store.saveAccount(initial.copy(name = "合成负债", description = "用户手动维护",
            amountCents = 12345, type = AccountType.LIABILITY, kind = "custom"))
        assertEquals(initial.uid, edited.uid)
        assertEquals(edited, store.getAccount(initial.id))
        val expense = CustomCategory("合成自定义", Direction.EXPENSE)
        val income = expense.copy(direction = Direction.INCOME)
        assertTrue(store.addCategory(expense))
        assertFalse(store.addCategory(expense))
        assertTrue(store.addCategory(income))
        val bill = store.saveEdited(record().copy(bill = record().bill.copy(category = expense.name)))
        val postedAccount=edited.copy(amountCents=12345L+1234L)
        assertEquals(postedAccount, store.getAccount(edited.id))
        store.close()
        store = LedgerStore(context, databaseName)
        assertEquals(postedAccount, store.getAccount(edited.id))
        assertEquals(setOf(expense, income), store.customCategories().toSet())
        listOf(
            edited.copy(amountCents = -Money.MAX_CENTS - 1), edited.copy(amountCents = Money.MAX_CENTS + 1),
            edited.copy(name = ""), edited.copy(name = "名".repeat(41)),
            edited.copy(description = "说".repeat(81)), edited.copy(kind = "unsupported"),
            edited.copy(uid = "not-a-uuid"), edited.copy(id = -1),
        ).forEach { invalid -> expectFailure { store.saveAccount(invalid) } }
        listOf("", " ", "分类".repeat(21), " 需规范化 ").forEach { name ->
            expectFailure { store.addCategory(CustomCategory(name, Direction.EXPENSE)) }
        }
        assertEquals(listOf(postedAccount), store.accounts())
        assertEquals(setOf(expense, income), store.customCategories().toSet())
        store.deleteCategory(expense)
        assertEquals(listOf(income), store.customCategories())
        assertEquals(expense.name, store.get(bill.id)!!.bill.category)
        store.deleteAccount(edited.id)
        assertNull(store.getAccount(edited.id))
        assertEquals(listOf(bill.copy(accountUid=null,accountEffect=0)), store.all())
    }

    @Test fun versionTwoBackupRoundTripsEveryEntityAndRepeatedMergeKeepsLocalEdits() {
        val posted = store.saveEdited(record().copy(note = "备份备注"))
        val pending = (store.ingest(Capture(Source.IMAGE, "image", "合成不完整原文", instant + 180000),
            ParsedBill(null, null, "待确认商户", "其他", instant + 180000, null, false, "需补全金额"))
            as IngestResult.Saved).record
        val asset = store.saveAccount(LedgerAccount(name = "合成支付宝", description = "零余额资产",
            amountCents = 0, kind = "alipay"))
        val debt = store.saveAccount(LedgerAccount(name = "合成负债", amountCents = 87654,
            kind = "custom", type = AccountType.LIABILITY))
        val expense = CustomCategory("合成宠物", Direction.EXPENSE)
        val income = CustomCategory("合成兼职", Direction.INCOME)
        store.addCategory(expense); store.addCategory(income)
        channels.set(ChannelSettings.Channel.OCR, true)
        val encoded = LedgerBackup.encode(store, channels)
        assertEquals(8, JSONObject(encoded).getInt("version"))
        val backup = decode(encoded)
        assertEquals(setOf(posted.copy(id = 0), pending.copy(id = 0)), backup.records.toSet())
        assertEquals(setOf(asset.copy(id = 0), debt.copy(id = 0)), backup.accounts.toSet())
        assertEquals(setOf(expense, income), backup.categories.toSet())
        assertTrue(backup.channels.getValue(ChannelSettings.Channel.OCR))
        val targetName = "native-v2-target-${UUID.randomUUID()}.db"
        LedgerStore(context, targetName).use { target ->
            try {
                assertEquals(2, target.merge(backup.records, backup.accounts, backup.categories))
                assertEquals(setOf(posted.uid, pending.uid), target.all().map { it.uid }.toSet())
                assertEquals(setOf(asset.uid, debt.uid), target.accounts().map { it.uid }.toSet())
                val importedAsset = target.accounts().single { it.uid == asset.uid }
                val localAccountEdit = target.saveAccount(importedAsset.copy(amountCents = 999, description = "导入后本地修改"))
                val localBillEdit = target.saveEdited(target.all().single { it.uid == posted.uid }.copy(note = "保留本地账单编辑"))
                assertEquals(0, target.merge(backup.records, backup.accounts, backup.categories))
                assertEquals(localAccountEdit, target.getAccount(importedAsset.id))
                assertEquals(localBillEdit, target.get(localBillEdit.id))
                assertEquals(2, target.accounts().size)
                assertEquals(2, target.customCategories().size)
            } finally { target.close(); context.deleteDatabase(targetName) }
        }
    }

    @Test fun legacyBackupAndMalformedOrFailedV2RestoreNeverPartiallyChangeLocalData() {
        val existing = store.saveEdited(record())
        val account = store.saveAccount(LedgerAccount(name = "已有本地账户", amountCents = 500))
        val category = CustomCategory("已有本地分类", Direction.EXPENSE)
        store.addCategory(category)
        channels.set(ChannelSettings.Channel.OCR, true)
        val newRecord = record().copy(uid = UUID.randomUUID().toString(), note = "应导入的 v1 账单")
        val v1 = JSONObject(LedgerBackup.encode(listOf(newRecord), channels)).put("version", 1)
        v1.remove("accounts"); v1.remove("customCategories")
        val legacy = decode(v1.toString())
        assertTrue(legacy.accounts.isEmpty())
        assertTrue(legacy.categories.isEmpty())
        assertEquals(1, store.merge(legacy.records, legacy.accounts, legacy.categories))
        assertEquals(listOf(account), store.accounts())
        assertEquals(listOf(category), store.customCategories())
        val beforeRecords = store.all()
        val newAccount = LedgerAccount(name = "将拒绝的账户", amountCents = 100)
        val newCategory = CustomCategory("将拒绝的分类", Direction.INCOME)
        val validV2 = JSONObject(LedgerBackup.encode(listOf(record()), channels, listOf(newAccount), listOf(newCategory)))
            .apply { getJSONObject("channels").put("ocr", false) }.toString()
        val malformed = mutableListOf<String>()
        listOf<Any>(-Money.MAX_CENTS-1, 12.5, "100", Money.MAX_CENTS + 1).forEach { invalid ->
            malformed += JSONObject(validV2).apply {
                getJSONArray("accounts").getJSONObject(0).put("amountCents", invalid)
            }.toString()
        }
        malformed += JSONObject(validV2).apply {
            getJSONArray("accounts").getJSONObject(0).put("type", "UNKNOWN")
        }.toString()
        malformed += JSONObject(validV2).apply {
            getJSONObject("customCategories").put("income", JSONArray().put(""))
        }.toString()
        malformed.forEach { json -> expectFailure {
            val backup = decode(json)
            store.merge(backup.records, backup.accounts, backup.categories)
            backup.channels.forEach { (channel, enabled) -> channels.set(channel, enabled) }
        } }
        assertEquals(beforeRecords, store.all())
        assertEquals(listOf(account), store.accounts())
        assertEquals(listOf(category), store.customCategories())
        assertTrue(channels.enabled(ChannelSettings.Channel.OCR))
        assertFalse(channels.enabled(ChannelSettings.Channel.SMS))
        // A failure after the bill insert must also roll it back with all account/category changes.
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_account BEFORE INSERT ON accounts
            WHEN NEW.uid = '${newAccount.uid}' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        val backup = decode(validV2)
        expectFailure { store.merge(backup.records, backup.accounts, backup.categories) }
        assertEquals(beforeRecords, store.all())
        assertEquals(listOf(account), store.accounts())
        assertEquals(listOf(category), store.customCategories())
        assertNotNull(store.get(existing.id))
        store.writableDatabase.execSQL("DROP TRIGGER reject_account")
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_category BEFORE INSERT ON custom_categories
            WHEN NEW.name = '${newCategory.name}' BEGIN SELECT RAISE(ABORT, 'synthetic category failure'); END""")
        expectFailure { store.merge(backup.records, backup.accounts, backup.categories) }
        assertEquals(beforeRecords, store.all())
        assertEquals(listOf(account), store.accounts())
        assertEquals(listOf(category), store.customCategories())
    }

    @Test fun fiveBankAccountsSurviveRestartAndBackupAlongsideLegacyBank() {
        val kinds=listOf("bank_icbc","bank_abc","bank_boc","bank_ccb","bank_bocom","bank")
        val saved=kinds.mapIndexed { index,kind -> store.saveAccount(LedgerAccount(name="银行 $index",kind=kind,amountCents=100L+index)) }
        store.close();store=LedgerStore(context,databaseName)
        assertEquals(saved.toSet(),store.accounts().toSet())
        val backup=decode(LedgerBackup.encode(store,channels))
        assertEquals(saved.map { it.copy(id=0) }.toSet(),backup.accounts.toSet())
        val targetName="banks-${UUID.randomUUID()}.db"
        try { LedgerStore(context,targetName).use { target ->
            target.merge(backup.records,backup.accounts,backup.categories)
            assertEquals(kinds.toSet(),target.accounts().map { it.kind }.toSet())
        } } finally { context.deleteDatabase(targetName) }
    }

    @Test fun thousandBillBatchHistoryMatchesIndividualReads() {
        val db=store.writableDatabase
        val ids=mutableListOf<Long>()
        db.beginTransaction()
        try { repeat(1000) { ids.add(store.saveEdited(record()).id) };db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        fun measured(block:()->Unit):Long { val start=System.nanoTime();block();return (System.nanoTime()-start)/1000 }
        var old=emptyMap<Long,List<RecordCaptureHistory>>()
        var batch=emptyMap<Long,List<RecordCaptureHistory>>()
        val oldTimes=(0..4).map { measured { old=ids.associateWith(store::captureHistory) } }.sorted()
        val batchTimes=(0..4).map { measured { batch=store.captureHistories() } }.sorted()
        assertEquals(old,batch)
        assertEquals(1000,batch.size)
        android.util.Log.i("LedgerReadBenchmark","1000 bills: individual median=${oldTimes[2]} us; batch median=${batchTimes[2]} us")
    }

    private fun record() = LedgerRecord(capture = Capture(Source.MANUAL, "manual", "", instant),
        bill = ParsedBill(1234, Direction.EXPENSE, "合成 v2 商户", "餐饮", instant, null, true, "", true),
        state = RecordState.POSTED)

    private fun values(record: LedgerRecord) = ContentValues().apply {
        put("id", record.id); put("uid", record.uid); put("source", record.capture.source.name)
        put("origin", record.capture.origin); put("raw_text", record.capture.text); put("received_at", record.capture.receivedAt)
        put("amount", record.bill.amountCents); put("direction", record.bill.direction?.name)
        put("merchant", record.bill.merchant); put("category", record.bill.category); put("occurred_at", record.bill.occurredAt)
        put("transaction_id", record.bill.transactionId); put("reliable", 1); put("time_explicit", 1)
        put("state", record.state.name); put("reason", record.reason); put("duplicate_id", record.duplicateId); put("note", record.note)
    }

    private fun decode(json: String) = json.byteInputStream(Charsets.UTF_8).use(LedgerBackup::decode)
    private fun expectFailure(block: () -> Unit) {
        assertNotNull("Expected validation or database failure", runCatching(block).exceptionOrNull())
    }
}
