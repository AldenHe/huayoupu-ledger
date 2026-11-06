package net.ankio.ledger.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** A completed local file becomes visible only after the final byte is saved. */
internal object LocalExportFile {
    fun publish(context:Context,file:File,name:String,mime:String,picture:Boolean=false) {
        val resolver=context.contentResolver
        val collection=if(picture)MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri=resolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,(if(picture)Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS)+"/花有谱")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })?:error("无法创建导出文件")
        var published=false
        try {
            (resolver.openOutputStream(uri,"w")?:error("无法写入导出文件")).use { out -> file.inputStream().use { it.copyTo(out) } }
            check(resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1) {"文件保存未完成"}
            published=true
        } finally {if(!published)resolver.delete(uri,null,null)}
    }
}
