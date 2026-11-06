package net.ankio.ledger.data

import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Portable financial export; screenshots and private recognition text belong in a full backup. */
object LedgerCsvExport {
    enum class Format(val key: String) {
        GENERAL("general"), QIANJI("qianji");

        companion object {
            fun fromKey(value: String): Format = entries.firstOrNull { it.key == value }
                ?: throw IllegalArgumentException("不支持的账单导出格式")
        }
    }

    private val generalHeader = listOf("时间", "分类", "类型", "金额", "币种", "商户", "备注", "账单UID",
        "交易订单号", "识别来源", "来源应用", "录入时间", "时间来源", "原付款金额", "已退款金额",
        "退款渠道", "退款模式", "退款单号", "账单状态", "标签", "需要报销")

    // Match the downloaded official V10 template, whose image column is last.
    private val qianjiHeader = listOf("时间", "分类", "二级分类", "类型", "金额", "账户1", "账户2", "备注",
        "账单标记", "手续费", "优惠券", "标签", "账单图片")

    /** Writes every posted income/expense across all months and returns its row count. Does not close output. */
    fun write(records: List<LedgerRecord>, format: Format, output: OutputStream): Int {
        val posted = records.filter { it.state == RecordState.POSTED }
        // Never silently omit a corrupt posted record or leave a successful-looking partial export.
        posted.forEach { record ->
            require(record.bill.amountCents != null && record.bill.amountCents in 0..Money.MAX_CENTS &&
                record.bill.direction != null) { "已入账记录缺少有效金额或收支类型" }
        }
        val date = SimpleDateFormat(if (format == Format.QIANJI) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        fun row(cells: List<String>, identifiers:Set<Int> = emptySet()) {
            writer.write(cells.mapIndexed { index,value ->
                // Keep identifier precision without applying the literal-apostrophe escape twice.
                val escaped=if(index in identifiers && value.length>=15 && value.all(Char::isDigit)) "'$value" else safeText(value)
                "\"${escaped.replace("\"", "\"\"")}\""
            }.joinToString(","))
            writer.write("\r\n")
        }
        writer.write("\uFEFF")
        row(if (format == Format.GENERAL) generalHeader else qianjiHeader)
        for (record in posted) {
            val bill = record.bill
            val at = date.format(Date(bill.occurredAt))
            val direction = if (bill.direction == Direction.INCOME) "收入" else "支出"
            val amount = Money.format(bill.amountCents!!)
            val refund = bill.refund
            val cells = when (format) {
                Format.GENERAL -> listOf(at, bill.category, direction, amount, "CNY", bill.merchant,
                    record.note, record.uid, bill.transactionId.orEmpty(), source(record.capture.source),
                    record.capture.origin, date.format(Date(record.capture.receivedAt)),
                    if (bill.timeExplicit) "交易时间" else "识别时间", refund?.let { Money.format(it.originalAmountCents) }.orEmpty(),
                    refund?.let { Money.format(it.refundedCents) }.orEmpty(), refund?.provider.orEmpty(),
                    refund?.mode.orEmpty(), refund?.refundId.orEmpty(), "已入账",BillTags.encode(record.tags),if(record.requiresReimbursement)"是" else "否")
                Format.QIANJI -> {
                    val note = buildList {
                        if (bill.merchant.isNotBlank()) add("商户：${bill.merchant}")
                        if (record.note.isNotBlank()) add(record.note)
                        bill.transactionId?.takeIf { it.isNotBlank() }?.let { add("交易订单号：$it") }
                        if (refund != null) add("原付款：${Money.format(refund.originalAmountCents)}；已退款：${Money.format(refund.refundedCents)}")
                        if(record.requiresReimbursement)add("需要报销：是")
                    }.joinToString("；")
                    listOf(at, bill.category, "", direction, amount, "", "", note,
                        if (bill.amountCents == 0L) "不计收支" else "", "", "", record.tags.joinToString(";"), "")
                }
            }
            row(cells,if(format==Format.GENERAL)setOf(7,8,17) else emptySet())
        }
        writer.flush()
        return posted.size
    }

    /** CSV quoting alone does not stop spreadsheet formula evaluation. */
    private fun safeText(value: String): String =
        if (value.startsWith("'") || value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') ||
            value.startsWith('\r') || value.startsWith('\n')) "'$value" else value

    private fun source(value: Source): String = when (value) {
        Source.OCR -> "屏幕识别"
        Source.IMAGE -> "图片导入"
        Source.SMS -> "短信识别"
        Source.NOTIFICATION -> "通知识别"
        Source.MANUAL -> "手动记账"
    }
}
