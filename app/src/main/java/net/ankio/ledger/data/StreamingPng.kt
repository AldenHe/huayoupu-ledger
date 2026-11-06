package net.ankio.ledger.data

import android.graphics.Bitmap
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Standard RGB PNG scanlines; bounded strips make long receipts safe on smaller phones. */
internal object StreamingPng {
    fun write(width:Int,height:Int,output:OutputStream,render:(Int,Bitmap)->Unit) {
        require(width>0 && height>0)
        val data=DataOutputStream(output)
        fun chunk(name:String,bytes:ByteArray) {
            val type=name.toByteArray(Charsets.US_ASCII);val crc=CRC32().apply { update(type);update(bytes) }
            data.writeInt(bytes.size);data.write(type);data.write(bytes);data.writeInt(crc.value.toInt())
        }
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val header=java.io.ByteArrayOutputStream()
        DataOutputStream(header).apply { writeInt(width);writeInt(height);write(byteArrayOf(8,2,0,0,0)) }
        chunk("IHDR",header.toByteArray())
        val compressed=object:OutputStream() {
            private val buffer=ByteArray(65_536);private var size=0
            override fun write(value:Int) { buffer[size++]=value.toByte();if(size==buffer.size)flush() }
            override fun write(bytes:ByteArray,offset:Int,length:Int) {
                var p=offset;var remaining=length
                while(remaining>0) { val n=minOf(remaining,buffer.size-size);bytes.copyInto(buffer,size,p,p+n);size+=n;p+=n;remaining-=n;if(size==buffer.size)flush() }
            }
            override fun flush() { if(size>0){chunk("IDAT",buffer.copyOf(size));size=0} }
        }
        val pixels=IntArray(width);val scan=ByteArray(width*3+1)
        DeflaterOutputStream(compressed).use { zlib ->
            var top=0
            while(top<height) {
                val bitmap=Bitmap.createBitmap(width,minOf(512,height-top),Bitmap.Config.ARGB_8888)
                try {
                    render(top,bitmap)
                    for(y in 0 until bitmap.height) {
                        bitmap.getPixels(pixels,0,width,0,y,width,1)
                        pixels.forEachIndexed { x,argb -> val at=x*3+1;scan[at]=(argb shr 16).toByte();scan[at+1]=(argb shr 8).toByte();scan[at+2]=argb.toByte() }
                        zlib.write(scan)
                    }
                } finally { top+=bitmap.height;bitmap.recycle() }
            }
            zlib.finish();compressed.flush()
        }
        chunk("IEND",byteArrayOf());data.flush()
    }
}
