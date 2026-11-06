package net.ankio.ledger.data

import org.json.JSONArray

/** Labels are independent of category, income/expense and accounting totals. */
object BillTags {
    // Read only at the old backup/CSV/database boundary; reimbursement is now a separate field.
    const val LEGACY_REIMBURSEMENT="报销"
    fun validate(tags:List<String>) {
        require(tags.size<=10 && tags.distinct().size==tags.size && tags.all {
            it.isNotBlank() && it==it.trim() && it.length<=24 && it.none(Char::isISOControl)
        }) { "账单标签无效" }
    }
    // SQLite on Android 11 has no JSON1. Delimited storage gives exact membership
    // with instr(); control characters are excluded from labels by validate().
    fun toStorage(tags:List<String>):String {
        validate(tags);return if(tags.isEmpty())"" else tags.joinToString("\u001f",prefix="\u001f",postfix="\u001f")
    }
    fun fromStorage(value:String):List<String> {
        if(value.isEmpty())return emptyList()
        require(value.startsWith('\u001f') && value.endsWith('\u001f')) { "账单标签格式错误" }
        return value.drop(1).dropLast(1).split('\u001f').also(::validate)
    }
    fun encode(tags:List<String>):String {
        validate(tags)
        return tags.joinToString(",",prefix="[",postfix="]") {
            "\"" + it.replace("\\","\\\\").replace("\"","\\\"") + "\""
        }
    }
    fun decode(value:String):List<String> {
        val array=JSONArray(value)
        return (0 until array.length()).map { requireNotNull(array.get(it) as? String) { "账单标签无效" } }.also(::validate)
    }
}
