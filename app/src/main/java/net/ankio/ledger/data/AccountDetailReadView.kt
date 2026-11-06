package net.ankio.ledger.data

import org.json.JSONArray
import org.json.JSONObject

/** Account history comes directly from indexed native tables, independent of the bridge bill cache. */
class AccountDetailReadView(private val store:LedgerStore) {
    fun query(accountUid:String,month:String,filter:String="all",offset:Int=0,limit:Int=100):JSONObject=synchronized(store) {
        require(filter in setOf("all","expense","income","transfer")) { "账户明细筛选无效" }
        require(offset in 0..1_000_000 && limit in 1..200) { "账户明细分页无效" }
        val db=store.readableDatabase
        require(AccountPostings.account(db,accountUid)!=null) { "账户已不存在" }
        val window=LedgerReadView(store).window(month)
        val scope="r.account_uid=? AND r.occurred_at>=? AND r.occurred_at<? AND r.state='POSTED' AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)"
        val scopeArgs=arrayOf(accountUid,window.start.toString(),window.end.toString())
        // Same repayment allocation semantics as the ordinary ledger summary.
        val statistical=ReimbursementStore.statisticalAmountSql()
        var expense=0L;var income=0L
        db.rawQuery("SELECT r.direction,COALESCE(SUM($statistical),0) FROM records r WHERE $scope GROUP BY r.direction",scopeArgs).use { c ->
            while(c.moveToNext())if(c.getString(0)=="EXPENSE")expense=c.getLong(1) else income=c.getLong(1)
        }
        val parts=mutableListOf<String>();val args=mutableListOf<String>()
        if(filter!="transfer") {
            val direction=when(filter) { "expense"->" AND r.direction='EXPENSE'";"income"->" AND r.direction='INCOME'";else->"" }
            parts+="""SELECT 'record' AS kind,r.id AS id,r.uid AS uid,r.occurred_at AS occurred_at,r.direction AS direction,
                r.amount AS amount,r.merchant AS merchant,r.category AS category,r.requires_reimbursement AS reimbursement,
                CASE WHEN a.image IS NULL THEN 0 ELSE 1 END AS has_icon,r.note AS note,
                NULL AS from_uid,NULL AS to_uid,NULL AS from_name,NULL AS to_name,$statistical AS statistical_amount
                FROM records r LEFT JOIN merchant_icons i ON i.record_id=r.id
                LEFT JOIN merchant_icon_assets a ON a.merchant_key=i.merchant_key WHERE $scope$direction"""
            args.addAll(scopeArgs)
        }
        if(filter in setOf("all","transfer")) {
            parts+="""SELECT 'transfer' AS kind,NULL AS id,t.uid AS uid,t.occurred_at AS occurred_at,
                CASE WHEN t.from_uid=? THEN 'OUT' ELSE 'IN' END AS direction,t.amount AS amount,
                '' AS merchant,'转账' AS category,0 AS reimbursement,0 AS has_icon,t.note AS note,
                t.from_uid,t.to_uid,f.name AS from_name,a.name AS to_name,0 AS statistical_amount
                FROM account_transfers t JOIN accounts f ON f.uid=t.from_uid JOIN accounts a ON a.uid=t.to_uid
                WHERE (t.from_uid=? OR t.to_uid=?) AND t.occurred_at>=? AND t.occurred_at<?"""
            args.addAll(listOf(accountUid,accountUid,accountUid,window.start.toString(),window.end.toString()))
        }
        val rows=JSONArray()
        db.rawQuery("SELECT * FROM (${parts.joinToString(" UNION ALL ")}) ORDER BY occurred_at DESC,kind ASC,uid ASC LIMIT ${limit+1} OFFSET $offset",args.toTypedArray()).use { c ->
            while(c.moveToNext())rows.put(JSONObject().apply {
                put("kind",c.getString(0));put("id",if(c.isNull(1))JSONObject.NULL else c.getLong(1));put("uid",c.getString(2))
                put("occurredAt",c.getLong(3));put("direction",c.getString(4));put("amountCents",c.getLong(5))
                put("merchant",c.getString(6));put("category",c.getString(7));put("requiresReimbursement",c.getInt(8)==1)
                put("hasMerchantIcon",c.getInt(9)==1);put("note",c.getString(10));put("statisticalAmountCents",c.getLong(15))
                if(c.getString(0)=="transfer") {
                    put("fromUid",c.getString(11));put("toUid",c.getString(12));put("fromName",c.getString(13));put("toName",c.getString(14))
                }
            })
        }
        val hasMore=rows.length()>limit
        if(hasMore)rows.remove(rows.length()-1)
        JSONObject().put("accountUid",accountUid).put("month",month).put("expenseCents",expense).put("incomeCents",income)
            .put("rows",rows).put("hasMore",hasMore).put("nextOffset",if(hasMore)offset+rows.length() else JSONObject.NULL)
    }
}
