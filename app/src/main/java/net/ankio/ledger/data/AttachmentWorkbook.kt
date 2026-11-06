package net.ankio.ledger.data

import org.json.JSONObject

internal object AttachmentWorkbook {
    fun kind(key:String)=mapOf("invoice" to "发票","payment" to "支付凭证","order" to "订单明细","travel" to "行程单","explanation" to "说明材料")[key]?:"说明材料"
    fun sheet(attachments:List<JSONObject>,labels:Map<String,String>,source:(String)->ByteArray?):CenteredWorkbook.Sheet {
        val t=CenteredWorkbook::text
        val rows=mutableListOf(listOf("附件编号","材料类型","原文件名","格式","大小（字节）","发票号码","开具日期","购买方","税号","材料金额（元）","信息核对","图片预览","SHA-256").map(t))
        val pictures=mutableListOf<CenteredWorkbook.Picture>()
        attachments.forEachIndexed {i,a->
            val uid=a.getString("uid")
            val picture=if(a.getString("mime").startsWith("image/"))WorkbookPictures.picture(i+1,11){source(uid)}else null
            picture?.let {pictures+=it}
            rows+=listOf(t(labels.getValue(uid)),t(kind(a.getString("kind"))),t(a.getString("name")),t(a.getString("mime")),t(a.getLong("size")),t(a.optString("invoiceNumber")),t(a.optString("issueDate")),t(a.optString("buyer")),t(a.optString("taxId")),if(a.isNull("amountCents"))t("")else CenteredWorkbook.money(a.getLong("amountCents")),t(if(a.optBoolean("reviewed"))"已人工核对"else""),t(if(picture==null)"非图片，原件请选 PDF 材料包导出"else""),t(a.getString("sha256")))
        }
        return CenteredWorkbook.Sheet("附件索引",listOf(16,18,40,26,22,28,18,30,28,20,20,28,68),rows,true,pictures)
    }
}
