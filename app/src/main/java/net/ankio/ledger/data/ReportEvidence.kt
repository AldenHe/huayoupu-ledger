package net.ankio.ledger.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Private frozen evidence belongs to one report token, so later ledger edits do not change its exports. */
class ReportEvidence(private val directory:File) {
    private val manifest by lazy {val file=File(directory,"evidence.json");require(file.isFile){"材料快照已过期，请重新生成汇总"};JSONObject(file.readText(Charsets.UTF_8))}
    val attachments:List<JSONObject> get(){val a=manifest.getJSONArray("attachments");return (0 until a.length()).map {a.getJSONObject(it)}}
    val labels:Map<String,String> get()=attachments.associate {it.getString("uid") to it.getString("exportId")}
    private val screenshotIds:Map<String,String> get(){val a=manifest.getJSONArray("screenshots");return (0 until a.length()).associate {a.getString(it) to "S${(it+1).toString().padStart(3,'0')}"}}
    val recordAttachmentIds:Map<String,String> get(){val o=manifest.getJSONObject("recordAttachmentIds");val shots=screenshotIds;return o.keys().asSequence().associateWith {uid->listOf(o.getString(uid),shots[uid].orEmpty()).filter {it.isNotEmpty()}.joinToString("、")}}
    fun screenshot(uid:String):ByteArray? {require(Regex("[a-f0-9-]{36}").matches(uid));return File(directory,"$uid.jpg").takeIf {it.isFile}?.readBytes()}
    fun attachment(uid:String):ByteArray?=attachments.firstOrNull {it.getString("uid")==uid}?.let {File(directory,it.getString("sha256")).readBytes()}
    fun writePdfBundle(report:BillReport,output:OutputStream) {
        ZipOutputStream(object:FilterOutputStream(output){override fun close(){flush()}}).use {zip->
            zip.putNextEntry(ZipEntry("账单汇总.pdf"));ReportPaper(report,recordAttachmentIds,attachments.map {"${it.getString("exportId")} · ${AttachmentWorkbook.kind(it.getString("kind"))} · ${it.getString("name")}"}).writePdf(zip);zip.closeEntry()
            report.rows.forEach {r->screenshot(r.uid)?.let {bytes->zip.putNextEntry(ZipEntry("支付截图/${screenshotIds.getValue(r.uid)}.jpg"));zip.write(bytes);zip.closeEntry()}}
            attachments.forEach {a->val name=a.getString("name").replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"),"_");zip.putNextEntry(ZipEntry("原始附件/${a.getString("exportId")}-$name"));File(directory,a.getString("sha256")).inputStream().use {it.copyTo(zip,8192)};zip.closeEntry()}
            zip.putNextEntry(ZipEntry("附件索引.json"));zip.write(JSONObject(manifest.toString()).put("screenshotIds",JSONObject(screenshotIds)).toString(2).toByteArray(Charsets.UTF_8));zip.closeEntry()
        }
    }
    companion object {
        fun freeze(store:LedgerStore,reimbursements:ReimbursementStore,report:BillReport,directory:File):ReportEvidence=synchronized(store) {
            require(!directory.exists()&&directory.mkdirs()){ "无法创建汇总材料快照" }
            try {
                val ids=store.activeRecordIdsByUid();var total=0L
                val all=linkedMapOf<String,JSONObject>();val related=linkedMapOf<String,List<String>>()
                fun checked(size:Long){total+=size;require(total<=512L*1024*1024){"此范围的材料超过 512 MB，请缩小日期范围"}}
                report.rows.forEach {r->
                    ids[r.uid]?.let(store::screenshot)?.let {image->checked(image.size.toLong());File(directory,"${r.uid}.jpg").writeBytes(image)}
                    val files=reimbursements.attachmentsForRecord(r.uid)
                    related[r.uid]=files.map {it.getString("uid")}
                    files.forEach {a->if(all.putIfAbsent(a.getString("uid"),a)==null){val source=reimbursements.attachmentFile(a.getString("uid"));checked(source.length());source.inputStream().use {input->File(directory,a.getString("sha256")).outputStream().use {input.copyTo(it,8192)}}}}
                }
                val attachments=all.values.sortedBy {it.getString("uid")}.mapIndexed {i,a->JSONObject(a.toString()).put("exportId","A${(i+1).toString().padStart(3,'0')}")}
                val labels=attachments.associate {it.getString("uid") to it.getString("exportId")}
                val references=JSONObject();related.forEach {(uid,values)->references.put(uid,values.joinToString("、"){labels.getValue(it)})}
                val manifest=JSONObject().put("reportToken",report.token).put("attachments",JSONArray(attachments)).put("recordAttachmentIds",references)
                    .put("screenshots",JSONArray(report.rows.filter {File(directory,"${it.uid}.jpg").isFile}.map {it.uid}))
                    .put("note","支付截图是支付凭证；原始票据按编号关联，内容未转换。")
                File(directory,"evidence.json").writeText(manifest.toString(),Charsets.UTF_8)
                ReportEvidence(directory)
            }catch(error:Throwable){directory.listFiles()?.forEach {it.delete()};directory.delete();throw error}
        }
    }
}
