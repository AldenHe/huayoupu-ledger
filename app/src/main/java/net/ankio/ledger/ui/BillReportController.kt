package net.ankio.ledger.ui

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.ledger.data.BillReport
import net.ankio.ledger.data.BillReports
import net.ankio.ledger.data.ReportPaper
import net.ankio.ledger.data.ReportWorkbook
import net.ankio.ledger.data.ReportEvidence
import net.ankio.ledger.ledger
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Owns immutable report snapshots and direct, atomically published local exports. */
internal class BillReportController(private val activity:MainActivity) {
    private val directory=File(activity.cacheDir,"bill-reports").apply { mkdirs() }
    @Volatile private var current:BillReport?=null
    private var generating:Job?=null
    private var request=""
    private var exporting=false
    fun restore() {
        directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach { file ->
            if(file.isDirectory && Regex("[a-f0-9-]{36}-evidence").matches(file.name))file.listFiles()?.forEach {it.delete()}
            file.delete()
        }
    }
    fun generate(json:String,id:String) {
        if(exporting) {
            emit("billReport",failure("正在保存文件，请稍后重试").put("requestId",id));return
        }
        generating?.cancel();request=id
        generating=activity.uiScope.launch {
            try {
                val report=withContext(Dispatchers.IO) {
                    val criteria=BillReport.Criteria.read(JSONObject(json))
                    synchronized(activity.ledger.store) {BillReports(activity.ledger.store).generate(criteria).also { result ->
                        val file=file(result.token);val temporary=File(directory,"${result.token}.tmp")
                        val evidence=evidenceDirectory(result.token)
                        ReportEvidence.freeze(activity.ledger.store,net.ankio.ledger.data.ReimbursementStore(activity,activity.ledger.store),result,evidence)
                        try {
                            GZIPOutputStream(temporary.outputStream()).bufferedWriter(Charsets.UTF_8).use { it.write(result.encode()) }
                            check(temporary.renameTo(file)) { "汇总保存失败，请重试" }
                        } catch(error:Throwable) {
                            evidence.listFiles()?.forEach { it.delete() };evidence.delete()
                            throw error
                        } finally { temporary.delete() }
                    }}
                }
                if(request!=id)return@launch
                current=report
                emit("billReport",report.preview().put("requestId",id))
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) { emit("billReport",failure(error.message?:"汇总生成失败，请重试").put("requestId",id)) }
        }
    }
    @Synchronized fun page(token:String,offset:Int):String = try { load(token).preview(offset).toString() }
        catch(_:Exception) { failure("这份汇总已过期，请重新生成").toString() }
    private fun file(token:String):File {
        require(Regex("[a-f0-9-]{36}").matches(token)) { "汇总标识无效" }
        return File(directory,"$token.json.gz")
    }
    @Synchronized private fun load(token:String):BillReport {
        current?.takeIf { it.token==token }?.let { return it }
        val file=file(token);require(file.isFile && System.currentTimeMillis()-file.lastModified()<=86_400_000) { "汇总已过期" }
        return GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { BillReport.decode(it.readText()) }
            .also { require(it.token==token);current=it }
    }
    private fun evidenceDirectory(token:String):File {file(token);return File(directory,"$token-evidence")}
    fun export(token:String,format:String) {
        if(exporting)return
        if(format !in FORMATS || !runCatching { file(token).isFile }.getOrDefault(false)) {
            emit("reportExport",failure("这份汇总已过期，请重新生成"));return
        }
        exporting=true
        activity.uiScope.launch {
            emit("reportExport",JSONObject().put("ok",true).put("busy",true).put("message","正在保存 ${format.uppercase()}…"))
            try {
                withContext(Dispatchers.IO) {
                    val report=load(token)
                    val extension=if(format=="pdf")"zip"else format
                    val evidence=ReportEvidence(evidenceDirectory(token))
                    val temp=File.createTempFile("bill-report-export-",".$extension",activity.cacheDir)
                    try {
                        temp.outputStream().buffered().use { out -> when(format) {
                            "png"->ReportPaper(report).writePng(out)
                            "pdf"->evidence.writePdfBundle(report,out)
                            "xlsx"->ReportWorkbook.write(report,out,evidence::screenshot,evidence.recordAttachmentIds,evidence)
                            else->error("导出格式无效")
                        } }
                        LocalExportFile.publish(activity,temp,"花有谱-账单汇总-${report.criteria.from}-${report.criteria.through}-${report.token.take(8)}.$extension",FORMATS.getValue(format),format=="png")
                    } finally { temp.delete() }
                }
                val destination=if(format=="png")"相册/花有谱" else "下载/花有谱"
                emit("reportExport",JSONObject().put("ok",true).put("busy",false).put("message","${if(format=="pdf")"PDF 与图片材料包 ZIP"else format.uppercase()} 已保存到$destination"))
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) { emit("reportExport",failure(error.message?:"保存失败，请检查可用空间后重试").put("busy",false)) }
            finally { exporting=false }
        }
    }
    private fun failure(message:String)=JSONObject().put("ok",false).put("error",message)
    private fun emit(type:String,json:JSONObject)=activity.dispatch(type,json.toString())
    companion object {
        private val FORMATS=mapOf("png" to "image/png","pdf" to "application/zip","xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    }
}
