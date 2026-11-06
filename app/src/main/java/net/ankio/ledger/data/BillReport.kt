package net.ankio.ledger.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** A generated report is a read-only snapshot; preview and every export share these rows. */
data class BillReport(val token:String,val createdAt:Long,val criteria:Criteria,val rows:List<Row>,val zoneId:String=ZoneId.systemDefault().id) {
    data class Criteria(val from:String,val through:String,val tags:List<String> = emptyList(),
        val matchAll:Boolean=false,val direction:String="",val category:String="",val reimbursement:String="") {
        init {
            val first=LocalDate.parse(from);val last=LocalDate.parse(through)
            require(first.year in 2000..2100 && last.year in 2000..2100 && !last.isBefore(first)) { "请选择有效的起止日期，结束日期不能早于开始日期" }
            BillTags.validate(tags)
            require(direction in listOf("","EXPENSE","INCOME") && category.length<=80) { "汇总条件无效" }
            require(reimbursement in listOf("","required","notRequired")) { "报销条件无效" }
        }
        fun json()=JSONObject().put("from",from).put("through",through).put("tags",JSONArray(tags))
            .put("matchAll",matchAll).put("direction",direction).put("category",category).put("reimbursement",reimbursement)
        companion object {
            fun read(json:JSONObject)=Criteria(json.getString("from"),json.getString("through"),
                BillTags.decode(json.optJSONArray("tags")?.toString()?:"[]"),json.optBoolean("matchAll"),
                json.optString("direction"),json.optString("category"),json.optString("reimbursement"))
        }
    }
    data class Row(val uid:String,val occurredAt:Long,val merchant:String,val category:String,
        val direction:String,val amountCents:Long,val tags:List<String>,val note:String,val requiresReimbursement:Boolean=false) {
        fun json()=JSONObject().put("uid",uid).put("occurredAt",occurredAt).put("merchant",merchant)
            .put("category",category).put("direction",direction).put("amountCents",amountCents)
            .put("tags",JSONArray(tags)).put("note",note).put("requiresReimbursement",requiresReimbursement)
    }
    data class Category(val name:String,val direction:String,val cents:Long,val count:Int)
    val expense:Long get()=rows.filter { it.direction=="EXPENSE" }.fold(0L) { sum,r -> Math.addExact(sum,r.amountCents) }
    val income:Long get()=rows.filter { it.direction=="INCOME" }.fold(0L) { sum,r -> Math.addExact(sum,r.amountCents) }
    val categories:List<Category> get()=rows.groupBy { it.direction to it.category }.map { (key,items) ->
        Category(key.second,key.first,items.fold(0L) { sum,r -> Math.addExact(sum,r.amountCents) },items.size)
    }.sortedWith(compareBy<Category> { it.direction }.thenByDescending { it.cents }.thenBy { it.name })
    fun preview(offset:Int=0,limit:Int=40):JSONObject {
        require(offset in 0..rows.size && limit in 1..200)
        val end=(offset+limit).coerceAtMost(rows.size)
        return JSONObject().put("ok",true).put("token",token).put("createdAt",createdAt).put("zoneId",zoneId).put("criteria",criteria.json())
            .put("count",rows.size).put("expense",expense).put("income",income).put("offset",offset)
            .put("rows",JSONArray(rows.subList(offset,end).map { it.json() })).put("hasMore",end<rows.size)
            .put("categories",JSONArray(categories.map { JSONObject().put("name",it.name).put("direction",it.direction).put("cents",it.cents).put("count",it.count) }))
    }
    fun encode()=JSONObject().put("token",token).put("createdAt",createdAt).put("zoneId",zoneId).put("criteria",criteria.json())
        .put("rows",JSONArray(rows.map { it.json() })).toString()
    companion object {
        fun decode(value:String):BillReport {
            val json=JSONObject(value);val array=json.getJSONArray("rows")
            return BillReport(json.getString("token"),json.getLong("createdAt"),Criteria.read(json.getJSONObject("criteria")),
                (0 until array.length()).map { index -> val row=array.getJSONObject(index)
                    Row(row.getString("uid"),row.getLong("occurredAt"),row.getString("merchant"),row.getString("category"),
                        row.getString("direction"),row.getLong("amountCents"),BillTags.decode(row.getJSONArray("tags").toString()),row.getString("note"),row.optBoolean("requiresReimbursement"))
                },json.getString("zoneId"))
        }
    }
}

/** Read in one SQLite transaction, independent of the home's month/page/search scope. */
class BillReports(private val store:LedgerStore) {
    fun labels():List<String> = synchronized(store) {
        val labels=linkedSetOf<String>()
        store.readableDatabase.rawQuery("SELECT DISTINCT tags FROM records r WHERE state='POSTED' AND tags<>'' AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)",null).use { c ->
            while(c.moveToNext())labels.addAll(BillTags.fromStorage(c.getString(0)))
        }
        labels.sorted()
    }
    fun generate(criteria:BillReport.Criteria,token:String=java.util.UUID.randomUUID().toString()):BillReport = synchronized(store) {
        val zone=ZoneId.systemDefault()
        val from=LocalDate.parse(criteria.from).atStartOfDay(zone).toInstant().toEpochMilli()
        val end=LocalDate.parse(criteria.through).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val where=StringBuilder("r.state='POSTED' AND r.occurred_at>=? AND r.occurred_at<? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)")
        val args=mutableListOf(from.toString(),end.toString())
        if(criteria.direction.isNotEmpty()) { where.append(" AND r.direction=?");args+=criteria.direction }
        if(criteria.category.isNotEmpty()) { where.append(" AND r.category=?");args+=criteria.category }
        if(criteria.reimbursement.isNotEmpty()) { where.append(" AND r.requires_reimbursement=?");args+=if(criteria.reimbursement=="required")"1" else "0" }
        if(criteria.tags.isNotEmpty()) {
            where.append(criteria.tags.joinToString(if(criteria.matchAll)" AND " else " OR ",prefix=" AND (",postfix=")") { "instr(r.tags,?)>0" })
            args+=criteria.tags.map { BillTags.toStorage(listOf(it)) }
        }
        val db=store.readableDatabase;db.beginTransactionNonExclusive()
        try {
            val rows=mutableListOf<BillReport.Row>()
            db.rawQuery("SELECT r.uid,r.occurred_at,r.merchant,r.category,r.direction,${ReimbursementStore.statisticalAmountSql()},r.tags,r.note,r.requires_reimbursement FROM records r WHERE $where AND (r.direction<>'INCOME' OR ${ReimbursementStore.statisticalAmountSql()}>0) ORDER BY r.occurred_at,r.id",args.toTypedArray()).use { c ->
                while(c.moveToNext()) {
                    require(rows.size<50_000) { "符合条件的账单超过 50000 笔，请缩小时间范围" }
                    rows+=BillReport.Row(c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),
                        BillTags.fromStorage(c.getString(6)),c.getString(7),c.getInt(8)==1)
                }
            }
            db.setTransactionSuccessful()
            BillReport(token,System.currentTimeMillis(),criteria,rows.toList())
        } finally { db.endTransaction() }
    }
}
