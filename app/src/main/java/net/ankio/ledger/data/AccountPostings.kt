package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source

/** Runs inside LedgerStore's transaction. Stored effects make every reversal idempotent. */
internal object AccountPostings {
    fun create(db:SQLiteDatabase) {
        fun add(table:String,column:String,definition:String) {
            val exists=db.rawQuery("PRAGMA table_info($table)",null).use { c->
                var found=false;while(c.moveToNext())if(c.getString(1)==column)found=true;found
            }
            if(!exists)db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
        add("records","account_uid","TEXT")
        add("records","account_effect","INTEGER NOT NULL DEFAULT 0")
        // Keep the legacy nonnegative base, allowing an overdraft in its signed adjustment.
        add("accounts","balance_adjustment","INTEGER NOT NULL DEFAULT 0")
        add("accounts","included_in_assets","INTEGER NOT NULL DEFAULT 1 CHECK(included_in_assets IN (0,1))")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_account_time ON records(account_uid,occurred_at DESC,id DESC)")
        db.execSQL("CREATE TABLE IF NOT EXISTS account_preferences(singleton INTEGER PRIMARY KEY CHECK(singleton=1),default_uid TEXT)")
        add("account_preferences","wechat_uid","TEXT")
        add("account_preferences","alipay_uid","TEXT")
    }

    private val channelColumns=mapOf("wechat" to "wechat_uid","alipay" to "alipay_uid")

    fun channelDefaults(db:SQLiteDatabase):Map<String,String> = channelColumns.mapNotNull { (channel,column) ->
        db.rawQuery("SELECT a.uid FROM account_preferences p JOIN accounts a ON a.uid=p.$column WHERE p.singleton=1",null)
            .use { if(it.moveToFirst())channel to it.getString(0) else null }
    }.toMap()

    fun defaultUid(db:SQLiteDatabase,channel:String?=null):String? = channelDefaults(db)[channel] ?: db.rawQuery(
        "SELECT uid FROM accounts ORDER BY CASE WHEN uid=(SELECT default_uid FROM account_preferences WHERE singleton=1) THEN 0 ELSE 1 END,id LIMIT 1",null
    ).use { if(it.moveToFirst())it.getString(0) else null }

    fun setDefault(db:SQLiteDatabase,uid:String?) {
        require(uid==null || account(db,uid)!=null) { "账户已不存在" }
        updatePreference(db,"default_uid",uid)
    }

    fun setChannelDefault(db:SQLiteDatabase,channel:String,uid:String?) {
        val column=requireNotNull(channelColumns[channel]) { "不支持的支付渠道" }
        require(uid==null || account(db,uid)!=null) { "账户已不存在" }
        updatePreference(db,column,uid)
    }

    private fun updatePreference(db:SQLiteDatabase,column:String,uid:String?) {
        db.execSQL("INSERT OR IGNORE INTO account_preferences(singleton) VALUES (1)")
        db.update("account_preferences",ContentValues().apply { put(column,uid) },"singleton=1",null)
    }

    fun clearDeletedAccount(db:SQLiteDatabase,uid:String) {
        for(column in listOf("default_uid")+channelColumns.values)
            db.update("account_preferences",ContentValues().apply { putNull(column) },"$column=?",arrayOf(uid))
    }

    internal fun account(db:SQLiteDatabase,uid:String):Pair<Long,AccountType>? = db.rawQuery(
        "SELECT amount+balance_adjustment,type FROM accounts WHERE uid=?",arrayOf(uid)
    ).use { if(it.moveToFirst())it.getLong(0) to AccountType.valueOf(it.getString(1)) else null }

    internal fun adjust(db:SQLiteDatabase,uid:String,delta:Long) {
        if(delta==0L)return
        val current=account(db,uid)?:return
        val next=Math.addExact(current.first,delta)
        require(next in -Money.MAX_CENTS..Money.MAX_CENTS) { "账户余额超出范围" }
        db.execSQL("UPDATE accounts SET balance_adjustment=balance_adjustment+? WHERE uid=?",arrayOf(delta,uid))
    }

    internal fun effect(type:AccountType,amount:Long,direction:Direction):Long {
        val signed=if(direction==Direction.EXPENSE)-amount else amount
        return if(type==AccountType.LIABILITY)-signed else signed
    }

    fun synchronize(db:SQLiteDatabase,id:Long,assignDefault:Boolean=false) {
        val stored=db.rawQuery("SELECT account_uid,account_effect,state,amount,direction,source,origin FROM records WHERE id=?",arrayOf(id.toString())).use {
            if(!it.moveToFirst())return
            Entry(if(it.isNull(0))null else it.getString(0),it.getLong(1),it.getString(2),if(it.isNull(3))null else it.getLong(3),if(it.isNull(4))null else it.getString(4),it.getString(5),it.getString(6))
        }
        var uid=stored.uid
        if(uid==null && assignDefault && stored.state==RecordState.POSTED.name) {
            val channel=if(stored.source==Source.MANUAL.name)null else when(stored.origin) {
                PaymentApps.WECHAT -> "wechat"
                PaymentApps.ALIPAY -> "alipay"
                else -> null
            }
            uid=defaultUid(db,channel)
        }
        val target=uid?.let { account(db,it) }
        val deleted=db.rawQuery("SELECT 1 FROM record_deletions WHERE record_id=?",arrayOf(id.toString())).use { it.moveToFirst() }
        val effect=if(target!=null && stored.state==RecordState.POSTED.name && !deleted && stored.amount!=null && stored.direction!=null) {
            effect(target.second,stored.amount,Direction.valueOf(stored.direction))
        }else 0L
        if(uid!=null)adjust(db,uid,effect-stored.effect)
        db.update("records",ContentValues().apply { put("account_uid",uid);put("account_effect",effect) },"id=?",arrayOf(id.toString()))
    }

    fun select(db:SQLiteDatabase,id:Long,uid:String?) {
        require(uid==null || account(db,uid)!=null) { "账户已不存在，请重新选择" }
        val unchanged=db.rawQuery("SELECT account_uid FROM records WHERE id=?",arrayOf(id.toString())).use {
            it.moveToFirst() && (if(it.isNull(0))null else it.getString(0))==uid
        }
        if(unchanged) { synchronize(db,id);return }
        reverse(db,id)
        db.update("records",ContentValues().apply { put("account_uid",uid) },"id=?",arrayOf(id.toString()))
        synchronize(db,id)
    }

    fun reverse(db:SQLiteDatabase,id:Long) {
        db.rawQuery("SELECT account_uid,account_effect FROM records WHERE id=?",arrayOf(id.toString())).use {
            if(it.moveToFirst() && !it.isNull(0))adjust(db,it.getString(0),-it.getLong(1))
        }
        db.update("records",ContentValues().apply { put("account_effect",0) },"id=?",arrayOf(id.toString()))
    }

    private data class Entry(val uid:String?,val effect:Long,val state:String,val amount:Long?,val direction:String?,val source:String,val origin:String)
}
