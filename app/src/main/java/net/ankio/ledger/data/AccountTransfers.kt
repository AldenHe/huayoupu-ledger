package net.ankio.ledger.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.Money
import java.util.UUID

/** A movement between two accounts, deliberately separate from income and expense bills. */
data class AccountTransfer(
    val uid:String=UUID.randomUUID().toString(),
    val fromUid:String,
    val toUid:String,
    val amountCents:Long,
    val occurredAt:Long,
    val note:String="",
) {
    fun validate() {
        require(UUID.fromString(uid).toString()==uid) { "转账标识无效" }
        require(UUID.fromString(fromUid).toString()==fromUid && UUID.fromString(toUid).toString()==toUid) { "转账账户标识无效" }
        require(fromUid!=toUid) { "转出和转入账户不能相同" }
        require(amountCents in 1..Money.MAX_CENTS) { "请输入有效转账金额" }
        require(occurredAt in 1..4_102_444_800_000L) { "转账时间无效" }
        require(note.length<=500) { "转账备注最多 500 个字符" }
    }
}

/** Persisted leg effects are the sole reversal authority. Every operation holds the store lock. */
class AccountTransfers(private val store:LedgerStore) {
    companion object {
        internal fun createSchema(db:SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS account_transfers(
                uid TEXT PRIMARY KEY NOT NULL,from_uid TEXT NOT NULL,to_uid TEXT NOT NULL,
                amount INTEGER NOT NULL CHECK(amount>0),occurred_at INTEGER NOT NULL,note TEXT NOT NULL,
                from_effect INTEGER NOT NULL,to_effect INTEGER NOT NULL,CHECK(from_uid<>to_uid))""")
            db.execSQL("CREATE INDEX IF NOT EXISTS transfers_from_time ON account_transfers(from_uid,occurred_at DESC,uid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS transfers_to_time ON account_transfers(to_uid,occurred_at DESC,uid)")
        }

        internal fun requireNoHistory(db:SQLiteDatabase,uid:String) {
            require(!db.rawQuery("SELECT 1 FROM account_transfers WHERE from_uid=? OR to_uid=? LIMIT 1",arrayOf(uid,uid)).use { it.moveToFirst() }) {
                "账户存在转账记录，请先删除相关转账后再删除账户或更改资产负债类型"
            }
        }

        internal fun effect(type:AccountType,amount:Long,outgoing:Boolean):Long {
            val signed=if(outgoing)-amount else amount
            return if(type==AccountType.LIABILITY)-signed else signed
        }
    }

    fun get(uid:String):AccountTransfer?=synchronized(store) {
        store.readableDatabase.query("account_transfers",null,"uid=?",arrayOf(uid),null,null,null)
            .use { if(it.moveToFirst())read(it) else null }
    }

    /** Full history for backup. Account screens use AccountDetailReadView's bounded SQL. */
    fun list(accountUid:String?=null):List<AccountTransfer> = synchronized(store) {
        store.readableDatabase.query("account_transfers",null,
            if(accountUid==null)null else "from_uid=? OR to_uid=?",
            accountUid?.let { arrayOf(it,it) },null,null,"occurred_at DESC,uid ASC")
            .use { c->buildList { while(c.moveToNext())add(read(c)) } }
    }

    fun save(value:AccountTransfer):AccountTransfer=synchronized(store) {
        value.validate()
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            val from=requireNotNull(AccountPostings.account(db,value.fromUid)) { "转出账户已不存在，请重新选择" }
            val to=requireNotNull(AccountPostings.account(db,value.toUid)) { "转入账户已不存在，请重新选择" }
            // Repeating the same UUID/value is a no-op, including when balances were corrected later.
            if(get(value.uid)!=value) {
                val fromEffect=effect(from.second,value.amountCents,true)
                val toEffect=effect(to.second,value.amountCents,false)
                val deltas=mutableMapOf<String,Long>()
                effects(db,value.uid).forEach { (uid,effect)->deltas[uid]=-effect }
                for((uid,effect) in mapOf(value.fromUid to fromEffect,value.toUid to toEffect))
                    deltas[uid]=Math.addExact(deltas[uid]?:0L,effect)
                // Apply final per-account differences, so a note edit or same-account leg
                // correction cannot fail because of a temporary reverse/reapply overflow.
                deltas.forEach { (uid,delta)->AccountPostings.adjust(db,uid,delta) }
                db.insertWithOnConflict("account_transfers",null,values(value,fromEffect,toEffect),SQLiteDatabase.CONFLICT_REPLACE)
                    .also { check(it!=-1L) { "转账保存失败" } }
            }
            db.setTransactionSuccessful()
            value
        } finally { db.endTransaction() }
    }

    fun delete(uid:String)=synchronized(store) {
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            reverse(db,uid)
            db.delete("account_transfers","uid=?",arrayOf(uid))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        Unit
    }

    /** Called inside the encompassing backup transaction after account snapshots are inserted. */
    internal fun restore(transfers:List<AccountTransfer>,existingAccounts:Set<String>):Map<String,Long> =synchronized(store) {
        val db=store.writableDatabase
        check(db.inTransaction()) { "恢复转账需要与账户快照处于同一事务" }
        require(transfers.size<=100_000) { "备份转账记录过多" }
        val pending=mutableListOf<Triple<AccountTransfer,Long,Long>>()
        val deltas=mutableMapOf<String,Long>()
        transfers.forEach { value ->
            value.validate()
            if(get(value.uid)==null) {
                val from=requireNotNull(AccountPostings.account(db,value.fromUid)) { "转出账户不存在" }
                val to=requireNotNull(AccountPostings.account(db,value.toUid)) { "转入账户不存在" }
                val fromEffect=effect(from.second,value.amountCents,true)
                val toEffect=effect(to.second,value.amountCents,false)
                // A new account's snapshot already contains its leg; only existing sides need movement.
                for((uid,leg) in mapOf(value.fromUid to fromEffect,value.toUid to toEffect))
                    if(uid in existingAccounts)deltas[uid]=Math.addExact(deltas[uid]?:0L,leg)
                pending+=Triple(value,fromEffect,toEffect)
            }
        }
        // The restore owner combines these missing leg effects with missing bill effects
        // before applying final existing-account balances. Individual effects remain durable.
        pending.forEach { (value,fromEffect,toEffect)->
            db.insertOrThrow("account_transfers",null,values(value,fromEffect,toEffect))
        }
        deltas
    }

    private fun reverse(db:SQLiteDatabase,uid:String) {
        effects(db,uid).forEach { (accountUid,effect)->AccountPostings.adjust(db,accountUid,-effect) }
    }

    private fun effects(db:SQLiteDatabase,uid:String):Map<String,Long> =
        db.rawQuery("SELECT from_uid,to_uid,from_effect,to_effect FROM account_transfers WHERE uid=?",arrayOf(uid)).use { c ->
            if(!c.moveToFirst())emptyMap() else {
                check(AccountPostings.account(db,c.getString(0))!=null && AccountPostings.account(db,c.getString(1))!=null) { "转账关联账户缺失，无法撤销" }
                mapOf(c.getString(0) to c.getLong(2),c.getString(1) to c.getLong(3))
            }
        }

    private fun values(value:AccountTransfer,fromEffect:Long,toEffect:Long)=ContentValues().apply {
        put("uid",value.uid);put("from_uid",value.fromUid);put("to_uid",value.toUid)
        put("amount",value.amountCents);put("occurred_at",value.occurredAt);put("note",value.note)
        put("from_effect",fromEffect);put("to_effect",toEffect)
    }

    private fun read(c:Cursor)=AccountTransfer(
        uid=c.getString(c.getColumnIndexOrThrow("uid")),fromUid=c.getString(c.getColumnIndexOrThrow("from_uid")),
        toUid=c.getString(c.getColumnIndexOrThrow("to_uid")),amountCents=c.getLong(c.getColumnIndexOrThrow("amount")),
        occurredAt=c.getLong(c.getColumnIndexOrThrow("occurred_at")),note=c.getString(c.getColumnIndexOrThrow("note")))
}
