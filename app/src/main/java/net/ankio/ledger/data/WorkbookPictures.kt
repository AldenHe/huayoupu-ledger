package net.ankio.ledger.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal object WorkbookPictures {
    fun picture(row:Int,column:Int,source:()->ByteArray?):CenteredWorkbook.Picture? {
        val bytes=source()?:return null
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null
        val ratio=minOf(160.0/bounds.outWidth,180.0/bounds.outHeight,1.0)
        val width=(bounds.outWidth*ratio).toInt().coerceAtLeast(1);val height=(bounds.outHeight*ratio).toInt().coerceAtLeast(1)
        return CenteredWorkbook.Picture(row,column,width,height) {
            val original=requireNotNull(source());var sample=1
            while(bounds.outWidth/sample>width*2&&bounds.outHeight/sample>height*2)sample*=2
            val decoded=requireNotNull(BitmapFactory.decodeByteArray(original,0,original.size,BitmapFactory.Options().apply {inSampleSize=sample}))
            try {val resized=Bitmap.createScaledBitmap(decoded,width,height,true)
                try {ByteArrayOutputStream().use {out->check(resized.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}}
                finally {if(resized!==decoded)resized.recycle()}
            }finally {decoded.recycle()}
        }
    }
}
