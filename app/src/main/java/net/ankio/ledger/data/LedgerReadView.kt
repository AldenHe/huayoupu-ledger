package net.ankio.ledger.data

import net.ankio.ledger.core.Direction
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Small read models: no OCR bodies or image blobs cross the bridge in a list request. */
class LedgerReadView(private val store:LedgerStore) {
    data class Window(val start:Long,val end:Long,val previous:Long)
    fun window(month:String):Window {
        require(Regex("(?:20[0-9]{2}|2100)-(?:0[1-9]|1[0-2])").matches(month))
        val c=Calendar.getInstance().apply { clear();set(month.take(4).toInt(),month.takeLast(2).toInt()-1,1) }
        val start=c.timeInMillis;c.add(Calendar.MONTH,1);val end=c.timeInMillis
        c.add(Calendar.MONTH,-2);return Window(start,end,c.timeInMillis)
    }
    private fun reimbursementWhere(value:String):String=when(value) {
        ""->"";"required"->" AND r.requires_reimbursement=1";"notRequired"->" AND r.requires_reimbursement=0"
        else->throw IllegalArgumentException("报销条件无效")
    }
    fun records(month:String,search:String="",offset:Int=0,limit:Int=200,direction:String="",category:String="",beforeAt:Long?=null,beforeId:Long?=null,tag:String="",reimbursement:String="",statistical:Boolean=false):JSONObject {
        val w=window(month);require(search.length<=200 && offset in 0..1_000_000 && limit in 1..200)
        require((beforeAt==null)==(beforeId==null))
        if(beforeAt!=null)require(beforeAt>0 && beforeId!!>0 && offset==0)
        require(direction.isEmpty()||direction in Direction.entries.map { it.name })
        if(tag.isNotEmpty())BillTags.validate(listOf(tag))
        val where=StringBuilder("NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)")
        val args=mutableListOf<String>()
        if(statistical)where.append(" AND (r.direction<>'INCOME' OR ${ReimbursementStore.statisticalAmountSql()}>0)")
        where.append(reimbursementWhere(reimbursement))
        if(search.isBlank()) { where.append(" AND r.occurred_at>=? AND r.occurred_at<?");args+=w.start.toString();args+=w.end.toString() }
        else { where.append(" AND (r.merchant LIKE ? ESCAPE '\\' OR r.category LIKE ? ESCAPE '\\' OR r.note LIKE ? ESCAPE '\\' OR r.transaction_id LIKE ? ESCAPE '\\' OR CASE r.source WHEN 'OCR' THEN '摇一摇' WHEN 'IMAGE' THEN '图片导入' WHEN 'MANUAL' THEN '手动' WHEN 'SMS' THEN '自动 · 短信' WHEN 'NOTIFICATION' THEN '自动 · 通知' END LIKE ? ESCAPE '\\')")
            val term="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";repeat(5){args+=term} }
        if(direction.isNotBlank()) { where.append(if(direction=="EXPENSE")" AND (r.direction=? OR r.direction IS NULL)" else " AND r.direction=?");args+=direction }
        if(tag.isNotEmpty()) { where.append(" AND r.state='POSTED' AND instr(r.tags,?)>0");args+=BillTags.toStorage(listOf(tag)) }
        if(category.isNotBlank()) { where.append(" AND r.category=?");args+=category }
        if(category.isNotBlank())where.append(" AND r.state='POSTED'")
        if(beforeAt!=null) {
            where.append(" AND (r.occurred_at<? OR (r.occurred_at=? AND r.id<?))")
            args+=beforeAt.toString();args+=beforeAt.toString();args+=beforeId.toString()
        }
        val rows=JSONArray()
        store.readableDatabase.rawQuery("""SELECT r.id,r.uid,r.merchant,r.category,r.note,r.amount,r.direction,r.occurred_at,r.state,r.source,
            r.reason,r.duplicate_id,r.refund_provider,r.refund_original,r.refund_amount,r.refund_mode,r.refund_id,
            i.merchant_key,length(a.image), (SELECT MAX(sequence) FROM capture_history h WHERE h.record_id=r.id),r.transaction_id,
            CASE WHEN lower(r.origin) LIKE '%com.tencent.mm%' OR r.origin IN ('微信','微信支付') THEN 'wechat'
                WHEN lower(r.origin) LIKE '%com.eg.android.alipaygphone%' OR r.origin='支付宝' THEN 'alipay'
                WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'微信')>0 AND instr(r.raw_text,'支付宝')=0 THEN 'wechat'
                WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'支付宝')>0 AND instr(r.raw_text,'微信')=0 THEN 'alipay' END,r.tags,r.requires_reimbursement,${ReimbursementStore.repaymentAmountSql()}
            FROM records r LEFT JOIN merchant_icons i ON i.record_id=r.id LEFT JOIN merchant_icon_assets a ON a.merchant_key=i.merchant_key
            WHERE $where ORDER BY r.occurred_at DESC,r.id DESC LIMIT ${limit+1} OFFSET $offset""",args.toTypedArray()).use { c ->
            while(c.moveToNext())rows.put(JSONObject().apply {
                put("id",c.getLong(0));put("uid",c.getString(1));put("merchant",c.getString(2));put("category",c.getString(3));put("note",c.getString(4))
                put("amountCents",if(c.isNull(5))JSONObject.NULL else c.getLong(5));put("direction",if(c.isNull(6))JSONObject.NULL else c.getString(6))
                put("occurredAt",c.getLong(7));put("state",c.getString(8));put("source",c.getString(9));put("reason",c.getString(10))
                put("duplicateId",if(c.isNull(11))JSONObject.NULL else c.getLong(11))
                put("refund",if(c.isNull(12))JSONObject.NULL else JSONObject().put("provider",c.getString(12)).put("originalAmountCents",c.getLong(13))
                    .put("refundedCents",c.getLong(14)).put("mode",c.getString(15)).put("refundId",if(c.isNull(16))JSONObject.NULL else c.getString(16)))
                put("hasMerchantIcon",!c.isNull(18));put("merchantIconKey",if(c.isNull(18))JSONObject.NULL else c.getString(17));put("merchantIconRevision",c.getInt(18))
                put("historyRevision",c.getInt(19).toString());put("hasScreenshot",false);put("captureMode","");put("iconStatus",if(!c.isNull(18))"cached" else "")
                put("transactionId",if(c.isNull(20))JSONObject.NULL else c.getString(20))
                put("paymentProvider",if(c.isNull(21))JSONObject.NULL else c.getString(21));put("tags",JSONArray(BillTags.fromStorage(c.getString(22))))
                put("requiresReimbursement",c.getInt(23)==1)
                put("reimbursementReceivedCents",c.getLong(24));put("isReimbursementRepayment",c.getLong(24)>0)
                put("statisticalAmountCents",if(c.isNull(5))JSONObject.NULL else c.getLong(5)-c.getLong(24))
            })
        }
        val more=rows.length()>limit;if(more)rows.remove(rows.length()-1)
        val last=rows.optJSONObject(rows.length()-1)
        return JSONObject().put("records",rows).put("hasMore",more).put("offset",offset).put("limit",limit)
            .put("nextCursor",if(more && last!=null)JSONObject().put("occurredAt",last.getLong("occurredAt")).put("id",last.getLong("id")) else JSONObject.NULL)
    }
    fun summary(month:String,reimbursement:String=""):JSONObject {
        val w=window(month);val result=JSONObject().put("month",month)
        val scope=reimbursementWhere(reimbursement)
        val amount=ReimbursementStore.statisticalAmountSql()
        val statisticalScope=" AND (r.direction!='INCOME' OR $amount>0)"
        fun totals(start:Long,end:Long):JSONObject {
            val total=JSONObject().put("expense",0).put("income",0).put("expenseCount",0).put("incomeCount",0)
            store.readableDatabase.rawQuery("SELECT direction,COALESCE(SUM($amount),0),COUNT(*) FROM records r WHERE state='POSTED' AND occurred_at>=? AND occurred_at<? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)$scope$statisticalScope GROUP BY direction",arrayOf(start.toString(),end.toString())).use { c ->
                while(c.moveToNext()){val key=if(c.getString(0)=="INCOME")"income" else "expense";total.put(key,c.getLong(1));total.put(key+"Count",c.getInt(2))}
            };return total
        }
        result.put("totals",totals(w.start,w.end));result.put("previous",totals(w.previous,w.start))
        val categories=JSONArray();store.readableDatabase.rawQuery("SELECT direction,category,SUM($amount),COUNT(*) FROM records r WHERE state='POSTED' AND occurred_at>=? AND occurred_at<? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)$scope$statisticalScope GROUP BY direction,category ORDER BY SUM($amount) DESC",arrayOf(w.start.toString(),w.end.toString())).use { c ->
            while(c.moveToNext())categories.put(JSONObject().put("direction",c.getString(0)).put("name",c.getString(1)).put("amount",c.getLong(2)).put("count",c.getInt(3)))
        };result.put("categories",categories)
        val days=JSONArray();store.readableDatabase.rawQuery("SELECT strftime('%d',occurred_at/1000,'unixepoch','localtime'),direction,SUM($amount) FROM records r WHERE state='POSTED' AND occurred_at>=? AND occurred_at<? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)$scope$statisticalScope GROUP BY 1,2 ORDER BY 1",arrayOf(w.start.toString(),w.end.toString())).use { c ->
            while(c.moveToNext())days.put(JSONObject().put("day",c.getString(0).toInt()).put("direction",c.getString(1)).put("amount",c.getLong(2)))
        };result.put("days",days)
        result.put("postedCount",store.readableDatabase.rawQuery("SELECT COUNT(*) FROM records r WHERE state='POSTED' AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)$scope",null).use { it.moveToFirst();it.getInt(0) })
        return result
    }
}
