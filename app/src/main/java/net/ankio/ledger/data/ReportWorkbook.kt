package net.ankio.ledger.data

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Preview and all export formats consume the same report snapshot. */
object ReportWorkbook {
    fun write(report:BillReport,output:OutputStream,image:(String)->ByteArray?={null},attachmentIds:Map<String,String> = emptyMap(),evidence:ReportEvidence?=null) {
        val c=report.criteria
        val summary=mutableListOf<List<CenteredWorkbook.Cell>>()
        fun row(vararg values:Any?){summary+=values.map(CenteredWorkbook::text)}
        row("花有谱 · 账单汇总")
        row("时间范围","${c.from} 至 ${c.through}")
        row("标签条件",c.tags.joinToString("、").ifEmpty {"全部标签"},if(c.matchAll)"全部满足"else"包含任一")
        row("收支方向",when(c.direction){"EXPENSE"->"支出";"INCOME"->"收入";else->"全部收支"})
        row("分类条件",c.category.ifEmpty {"全部分类"})
        row("报销条件",when(c.reimbursement){"required"->"需要报销";"notRequired"->"不需要报销";else->"全部"})
        summary+=listOf(CenteredWorkbook.text("账单笔数"),CenteredWorkbook.number(report.rows.size))
        listOf("支出合计" to report.expense,"收入合计" to report.income,"结余" to report.income-report.expense).forEach {(label,value)->summary+=listOf(CenteredWorkbook.text(label),CenteredWorkbook.money(value))}
        row("口径","已入账、退款后金额；报销到账分开列示，不混入一般收入。")
        row("材料说明","截图仅为支付凭证；原始票据按附件索引保存。")
        row("分类","收支","金额（元）","笔数")
        report.categories.forEach {summary+=listOf(CenteredWorkbook.text(it.name),CenteredWorkbook.text(if(it.direction=="INCOME")"收入"else"支出"),CenteredWorkbook.money(it.cents),CenteredWorkbook.number(it.count))}
        val rows=mutableListOf(listOf("时间","商户","分类","收支","金额（元）","标签","备注","需要报销","附件编号","截图预览").map(CenteredWorkbook::text))
        val pictures=mutableListOf<CenteredWorkbook.Picture>()
        val formatter=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of(report.zoneId))
        report.rows.forEachIndexed {index,r->
            val picture=WorkbookPictures.picture(index+1,9){image(r.uid)}
            if(picture!=null)pictures+=picture
            rows+=listOf(CenteredWorkbook.text(formatter.format(Instant.ofEpochMilli(r.occurredAt))),CenteredWorkbook.text(r.merchant),CenteredWorkbook.text(r.category),
                CenteredWorkbook.text(if(r.direction=="INCOME")"收入"else"支出"),CenteredWorkbook.money(r.amountCents),CenteredWorkbook.text(r.tags.joinToString("、")),CenteredWorkbook.text(r.note),
                CenteredWorkbook.text(if(r.requiresReimbursement)"是"else"否"),CenteredWorkbook.text(attachmentIds[r.uid].orEmpty()),CenteredWorkbook.text(if(picture==null)"未保存截图"else""))
        }
        val sheets=mutableListOf(CenteredWorkbook.Sheet("账单汇总",listOf(26,48,24,12),summary),CenteredWorkbook.Sheet("账单明细",listOf(22,24,24,14,18,36,36,16,24,28),rows,true,pictures,listOf("")+report.rows.map {it.uid}))
        if(evidence!=null)sheets+=AttachmentWorkbook.sheet(evidence.attachments,evidence.labels,evidence::attachment)
        CenteredWorkbook.write(sheets,output)
    }
}
