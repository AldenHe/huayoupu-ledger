package net.ankio.ledger.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import net.ankio.ledger.core.Money
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Print layout is data-driven; PNG is streamed in strips instead of allocating a giant bitmap. */
class ReportPaper(private val report:BillReport,private val attachmentIds:Map<String,String> = emptyMap(),private val attachmentIndex:List<String> = emptyList()) {
    private data class Line(val text:String,val right:String="",val style:Int=0)
    private data class BillBlock(val first:Int,val end:Int,val continuation:List<Line>)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val lines=mutableListOf<Line>()
    private val bills=mutableListOf<BillBlock>()
    init {
        fun add(text:String,right:String="",style:Int=0) {
            paint.textSize=if(style==1)54f else if(style==2)40f else 32f
            val width=936f
            (if(right.isEmpty())text else "$text   $right").split('\n').forEach { paragraph ->
                var rest=paragraph
                if(rest.isEmpty())lines+=Line("",style=style)
                while(rest.isNotEmpty()) {
                    var n=paint.breakText(rest,true,width,null).coerceAtLeast(1)
                    if(n<rest.length && Character.isHighSurrogate(rest[n-1]))n--
                    n=n.coerceAtLeast(1)
                    lines+=Line(rest.take(n),style=style);rest=rest.drop(n)
                }
            }
        }
        fun rule() { lines+=Line("",style=4) }
        add("花有谱 · 账单汇总",style=1)
        add("${report.criteria.from} 至 ${report.criteria.through}",style=3)
        add("共 ${report.rows.size} 笔 · 人民币",style=3)
        val criteria=report.criteria
        if(criteria.tags.isNotEmpty())add("标签 · ${if(criteria.matchAll)"全部满足" else "包含任一"}：${criteria.tags.joinToString("、")}",style=3)
        if(criteria.category.isNotEmpty())add("分类 · ${criteria.category}",style=3)
        if(criteria.direction.isNotEmpty())add("收支 · ${if(criteria.direction=="INCOME")"收入" else "支出"}",style=3)
        if(criteria.reimbursement.isNotEmpty())add("报销 · ${if(criteria.reimbursement=="required")"需要报销" else "不需要报销"}",style=3)
        rule();add("支出合计","¥ ${Money.display(report.expense)}",2)
        add("收入合计","¥ ${Money.display(report.income)}",2)
        add("结余","¥ ${Money.display(report.income-report.expense)}",2)
        rule();add("分类汇总",style=2)
        report.categories.forEach { add("${it.name} · ${if(it.direction=="INCOME")"收入" else "支出"} · ${it.count} 笔","¥ ${Money.display(it.cents)}") }
        rule();add("账单明细",style=2)
        val formatter=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of(report.zoneId))
        report.rows.forEach { r ->
            val first=lines.size
            val date="${formatter.format(Instant.ofEpochMilli(r.occurredAt))} · ${if(r.direction=="INCOME")"收入" else "支出"}"
            val amount="¥ ${Money.display(r.amountCents)}"
            val merchant="${r.merchant.ifEmpty { "未填写商户" }} · ${r.category}"
            add(date,amount)
            add(merchant)
            add("报销：${if(r.requiresReimbursement)"需要" else "不需要"}",style=3)
            if(r.tags.isNotEmpty())add("标签：${r.tags.joinToString("、")}",style=3)
            if(r.note.isNotEmpty())add("备注：${r.note}",style=3)
            attachmentIds[r.uid]?.takeIf {it.isNotEmpty()}?.let {add("附件编号：$it",style=3)}
            lines+=Line("")
            // Continuation identity is bounded even when the original merchant spans a page.
            paint.textSize=32f
            val continuationMerchant=android.text.TextUtils.ellipsize(merchant.replace('\n',' '),
                android.text.TextPaint(paint),936f,android.text.TextUtils.TruncateAt.END).toString()
            bills+=BillBlock(first,lines.size,listOf(Line("账单明细（续）",style=3),Line(date,amount),Line(continuationMerchant)))
        }
        if(report.rows.isEmpty())add("此范围没有已入账账单。",style=3)
        if(attachmentIndex.isNotEmpty()){rule();add("原始附件索引",style=2);attachmentIndex.forEach {add(it,style=3)}}
        rule();add("仅已入账账单，金额为退款后的最终入账金额。",style=3)
        add("本份汇总生成后，账本修改不会改变导出内容。",style=3)
        add("生成于 ${formatter.format(Instant.ofEpochMilli(report.createdAt))} · ${report.token.take(8)}",style=3)
    }
    private fun draw(canvas:Canvas,first:Int,count:Int,top:Float=72f) {
        drawLines(canvas,lines.subList(first,(first+count).coerceAtMost(lines.size)),top)
    }
    private fun drawLines(canvas:Canvas,pageLines:List<Line>,top:Float) {
        for((index,line) in pageLines.withIndex()) {
            val y=top+index*STEP
            if(line.style==4) {
                paint.color=Color.rgb(155,151,139);paint.strokeWidth=2f
                paint.pathEffect=DashPathEffect(floatArrayOf(12f,9f),0f)
                canvas.drawLine(72f,y-14,1008f,y-14,paint);paint.pathEffect=null;continue
            }
            paint.color=if(line.style==3)Color.rgb(94,95,84) else Color.rgb(48,53,45)
            paint.textSize=if(line.style==1)54f else if(line.style==2)40f else 32f
            paint.typeface=if(line.style in 1..2)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign=Paint.Align.CENTER
            canvas.drawText(if(line.right.isEmpty())line.text else "${line.text}   ${line.right}",540f,y,paint)
        }
    }
    private fun paper(canvas:Canvas,top:Int,height:Int,totalHeight:Int,cut:Boolean) {
        canvas.drawColor(Color.rgb(232,235,230))
        paint.shader=LinearGradient(0f,0f,WIDTH.toFloat(),0f,
            intArrayOf(Color.rgb(237,233,221),Color.rgb(255,253,245),Color.rgb(252,250,243),Color.rgb(244,241,231)),
            floatArrayOf(0f,.06f,.8f,1f),Shader.TileMode.CLAMP)
        canvas.save()
        if(cut && top+height>=totalHeight-14) {
            val edge=Path().apply {
                moveTo(0f,-top.toFloat());lineTo(WIDTH.toFloat(),-top.toFloat())
                lineTo(WIDTH.toFloat(),(totalHeight-top-14).toFloat())
                var x=WIDTH
                while(x>0) { lineTo((x-9).toFloat(),(totalHeight-top).toFloat());lineTo((x-18).toFloat(),(totalHeight-top-14).toFloat());x-=18 }
                close()
            }
            canvas.clipPath(edge)
        }
        canvas.drawRect(0f,0f,WIDTH.toFloat(),height.toFloat(),paint);paint.shader=null
        // Very faint thermal-head lines stay aligned across streamed PNG strips.
        paint.color=Color.argb(6,113,94,57);paint.strokeWidth=1f
        for(y in ((top+7)/8)*8 until top+height step 8)canvas.drawLine(0f,(y-top).toFloat(),WIDTH.toFloat(),(y-top).toFloat(),paint)
        canvas.restore()
    }
    fun writePdf(output:OutputStream) {
        val pages=pdfPages()
        val pdf=PdfDocument()
        try {
            pages.forEachIndexed { index,pageLines ->
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,index+1).create())
                page.canvas.save();page.canvas.scale(595f/WIDTH,595f/WIDTH)
                paper(page.canvas,0,1529,1529,false)
                drawLines(page.canvas,pageLines,if(index==0)90f else 130f)
                if(index>0) { paint.color=Color.rgb(77,99,91);paint.textSize=28f;paint.typeface=Typeface.DEFAULT
                    paint.textAlign=Paint.Align.CENTER;page.canvas.drawText("花有谱 · 账单汇总 · ${report.criteria.from} 至 ${report.criteria.through}",540f,66f,paint) }
                paint.color=Color.rgb(77,99,91);paint.textSize=26f;paint.typeface=Typeface.DEFAULT
                paint.textAlign=Paint.Align.CENTER;page.canvas.drawText("${index+1} / ${pages.size}",540f,1480f,paint)
                page.canvas.restore();pdf.finishPage(page)
            }
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
    private fun pdfPages():List<List<Line>> {
        val capacity=23
        val pages=mutableListOf<MutableList<Line>>(mutableListOf())
        fun freshPage() { if(pages.last().isNotEmpty())pages.add(mutableListOf()) }
        val byFirst=bills.associateBy { it.first }
        var index=0
        while(index<lines.size) {
            val bill=byFirst[index]
            if(bill==null) {
                if(pages.last().size==capacity)freshPage()
                pages.last()+=lines[index++]
                continue
            }
            // Ordinary bills stay whole; an oversized bill starts with its own identity.
            if(pages.last().size+bill.end-bill.first>capacity)freshPage()
            while(index<bill.end) {
                if(index==bill.end-1 && pages.last().size==capacity) { index++;break }
                if(pages.last().size==capacity) {
                    freshPage()
                    pages.last().addAll(bill.continuation)
                }
                pages.last()+=lines[index++]
            }
        }
        return pages
    }
    fun writePng(output:OutputStream) {
        val height=lines.size*STEP+110
        StreamingPng.write(WIDTH,height,output) { top,bitmap ->
            val canvas=Canvas(bitmap);paper(canvas,top,bitmap.height,height,true)
            // Draw only intersecting lines; the strip holds at most a few rows in memory.
            val first=((top-100)/STEP).coerceAtLeast(0)
            canvas.save();canvas.translate(0f,(72+first*STEP-top)-72f)
            draw(canvas,first,bitmap.height/STEP+5);canvas.restore()
        }
    }
    companion object { private const val WIDTH=1080;private const val STEP=58 }
}
