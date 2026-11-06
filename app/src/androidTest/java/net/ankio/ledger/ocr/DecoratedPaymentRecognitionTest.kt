package net.ankio.ledger.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.core.LocalOcrBillParser
import net.ankio.ledger.core.LocalOcrLine
import net.ankio.ledger.core.PaymentApps
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DecoratedPaymentRecognitionTest {
    private val merchant="上海公共交通卡股份有限公司"
    private val rows=listOf(OcrLine("一路向前",Rect(280,850,620,900)),
        OcrLine(merchant,Rect(150,1120,750,1160)),OcrLine("¥10.00",Rect(300,1200,600,1270)),
        OcrLine("建设银行信",Rect(300,1350,600,1390)),OcrLine("付款成功",Rect(330,1530,570,1570)))

    private fun paymentImage(genuineMerchantLogo:Boolean=false):Bitmap =
        Bitmap.createBitmap(900,1800,Bitmap.Config.ARGB_8888).also { image ->
            image.eraseColor(Color.WHITE)
            val canvas=Canvas(image);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            // A full-width decorative header and a separate payment-method mark.
            paint.color=Color.rgb(190,225,215);canvas.drawRect(0f,780f,900f,940f,paint)
            paint.color=Color.rgb(30,85,160);canvas.drawCircle(250f,1370f,24f,paint)
            if(genuineMerchantLogo) {
                paint.color=Color.rgb(170,65,40);canvas.drawCircle(450f,1000f,45f,paint)
            }
        }

    @Test fun decorativeAndBankMarksCannotBecomeThePayeesIconButVerifiedStoreMarkCan() {
        for(hasLogo in listOf(false,true)) {
            val image=paymentImage(hasLogo)
            try {
                val bill=requireNotNull(LocalOcrBillParser.parse(rows.map {
                    val b=requireNotNull(it.box);LocalOcrLine(it.text,b.left,b.top,b.right,b.bottom)
                },900,1800,System.currentTimeMillis(),origin=PaymentApps.WECHAT))
                assertEquals(merchant,bill.merchant);assertEquals("交通出行",bill.category)
                val icon=LocalIconLocator.locate(image,LocalOcrResult(900,1800,0,rows),bill.merchant)
                if(hasLogo) {
                    val selected=requireNotNull(icon.selected) { icon.json().toString() }
                    assertEquals(merchant,selected.anchor)
                    assertTrue(selected.box.contains(450,1000))
                    assertFalse(selected.box.contains(250,1370))
                    val b=selected.box
                    assertNotNull(MerchantIconCropper.evaluate(image,MerchantIconRegion(
                        b.left/0.9,b.top/1.8,b.right/0.9,b.bottom/1.8),verifiedBox=true).bytes)
                } else assertNull(icon.json().toString(),icon.selected)
            } finally { image.recycle() }
        }
    }

    @Test fun bundledOcrRecognizesLowPaymentCardAcrossWechatAndAlipayWithoutUsingBankIcon()=runBlocking {
        val image=paymentImage()
        val bytes=try {
            val canvas=Canvas(image);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.BLACK;typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            }
            fun text(value:String,x:Float,y:Float,size:Float) { paint.textSize=size;canvas.drawText(value,x,y,paint) }
            text("一路向前",340f,895f,50f)
            text(merchant,130f,1160f,40f);text("¥10.00",300f,1270f,90f)
            text("建设银行信用卡",300f,1390f,36f);text("付款成功",350f,1570f,48f)
            ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray() }
        } finally { image.recycle() }
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        for(origin in listOf(PaymentApps.WECHAT,PaymentApps.ALIPAY)) {
            val result=LocalRecognitionClient(context).recognizePrepared(bytes,origin=origin)
            assertEquals(result.rawText,merchant,result.bill?.merchant)
            assertEquals(result.rawText,1000L,result.bill?.amountCents)
            assertEquals(result.rawText,"交通出行",result.bill?.category)
            assertNull("${result.rawText}; ${result.iconDetails}",result.merchantIcon)
        }
    }

    /** Optional cropped user-supplied UI photo; this is not the original stored payment bitmap. */
    @Test fun suppliedDiagnosticPaymentViewUsesActualLocalOcr()=runBlocking {
        val path=InstrumentationRegistry.getArguments().getString("decoratedPaymentFixture")
        assumeNotNull(path)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes=File(requireNotNull(path)).readBytes()
        for(origin in listOf(PaymentApps.WECHAT,PaymentApps.ALIPAY)) {
            val result=LocalRecognitionClient(context).recognizePrepared(bytes,origin=origin)
            File(context.cacheDir,"decorated-payment-${if(origin==PaymentApps.WECHAT)"wechat" else "alipay"}.txt")
                .writeText("${result.rawText}\n${result.bill}\n${result.iconDetails}")
            assertEquals(result.rawText,merchant,result.bill?.merchant)
            assertEquals(result.rawText,1000L,result.bill?.amountCents)
            assertEquals(result.rawText,"交通出行",result.bill?.category)
            assertNull("The diagnostic image has no verified merchant logo: ${result.iconDetails}",result.merchantIcon)
        }
    }
}
