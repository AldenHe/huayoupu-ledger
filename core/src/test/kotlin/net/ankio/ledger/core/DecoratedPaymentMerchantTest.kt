package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DecoratedPaymentMerchantTest {
    private val now=1_793_942_400_000L
    private val transportMerchant="上海公共交通卡股份有限公司"
    private fun row(text:String,l:Int,t:Int,r:Int,b:Int)=LocalOcrLine(text,l,t,r,b)
    private fun card(merchant:String=transportMerchant,bank:String="建设银行信")=listOf(
        row("一路向前",280,850,620,900),
        row(merchant,150,1120,750,1160),row("¥10.00",300,1200,600,1270),
        // Short enough to enter the old below-amount candidate band.
        row(bank,300,1350,600,1390),row("付款成功",330,1530,570,1570))
    private fun parse(rows:List<LocalOcrLine>,w:Int=900,h:Int=1800,origin:String=PaymentApps.WECHAT)=
        requireNotNull(LocalOcrBillParser.parse(rows,w,h,now,origin=origin))

    @Test fun amountAdjacentMerchantWinsOverDecorationAndBankAcrossProvidersScalesAndOffsets() {
        for(origin in listOf(PaymentApps.WECHAT,PaymentApps.ALIPAY))
            for(scale in listOf(.6,1.0,1.5))for(offset in listOf(-700,0,180)) {
                val rows=card().map { it.copy(left=(it.left*scale).toInt(),right=(it.right*scale).toInt(),
                    top=((it.top+offset)*scale).toInt(),bottom=((it.bottom+offset)*scale).toInt()) }
                for(input in listOf(rows,rows.reversed(),rows.shuffled(Random(19)))) {
                    val bill=parse(input,(900*scale).toInt(),(1800*scale).toInt(),origin)
                    assertEquals("$origin/$scale/$offset",transportMerchant,bill.merchant)
                    assertEquals(1000L,bill.amountCents);assertEquals("交通出行",bill.category)
                    assertTrue(bill.reason,bill.reliable)
                }
            }
    }

    @Test fun merchantIdentityDoesNotDependOnCompanySuffixOrTransportVocabulary() {
        for(merchant in listOf("合成早餐铺","陈小明","合成银行")) {
            val bill=parse(card(merchant,"合成银行信用卡(1234)"))
            assertEquals(merchant,bill.merchant)
            assertEquals(if(merchant.contains("早餐"))"餐饮美食" else "其他",bill.category)
        }
    }

    @Test fun closerWalletNameBelowAmountStillBeatsHeaderAndLegalFullName() {
        val rows=listOf(row("合成门店招牌",200,280,700,330),row("¥10.00",300,600,600,670),
            row("合成米粉",330,685,570,725),row("付款成功",330,820,570,860),
            row("商户全称",60,1000,220,1040),row("合成餐饮有限公司",300,1000,750,1040))
        assertEquals("合成米粉",parse(rows).merchant)
        // A bank may be the actual payee; payment-channel rejection cannot ban its name.
        assertEquals("合成银行",parse(rows.map { if(it.text=="合成米粉")it.copy(text="合成银行") else it }).merchant)
    }

    @Test fun repeatedWalletNamesUseNearestGeometryRegardlessOfOcrOrder() {
        val above=row("上方招牌",300,510,600,550)
        val amount=row("¥10.00",300,600,600,670)
        val near=row("合成米粉",330,685,570,725)
        val far=row("合成米粉",330,770,570,810)
        val status=row("付款成功",330,950,570,990)
        for(origin in listOf(PaymentApps.WECHAT,PaymentApps.ALIPAY)) {
            // The two distinct candidate names still compare by their nearest gap.
            // Removing either duplicate also verifies the ordinary unique-row path.
            for(names in listOf(listOf(near,far),listOf(near),listOf(far))) {
                val rows=listOf(above,amount,status)+names
                val expected=if(near in names)"合成米粉" else "上方招牌"
                for(input in listOf(rows,rows.reversed(),rows.shuffled(Random(1)),rows.shuffled(Random(2))))
                    assertEquals("$origin/$names",expected,parse(input,origin=origin).merchant)
            }
            // Equal gaps retain the established below-amount preference.
            val tied=near.copy(top=720,bottom=760)
            assertEquals("合成米粉",parse(listOf(above,amount,tied,status),origin=origin).merchant)
        }
    }

    @Test fun distantDecorationAloneCannotSupplyMerchantOrTransportCategory() {
        val rows=card().filterNot { it.text==transportMerchant || it.text=="建设银行信" }
            .map { if(it.text=="一路向前")it.copy(text="公共交通主题") else it }+
            listOf(row("商户全称",50,1630,230,1670),row("合成书店",320,1630,580,1670))
        val bill=parse(rows)
        assertEquals("合成书店",bill.merchant);assertEquals("其他",bill.category)
    }

    @Test fun publicTransportCategoryComesFromMerchantOrLabeledProduct() {
        val bill=parse(card("合成充值服务")+listOf(
            row("商品",40,1640,180,1680),row("公共交通卡充值",300,1640,650,1680)))
        assertEquals("合成充值服务",bill.merchant);assertEquals("交通出行",bill.category)
    }

    @Test fun namedPersonalTransferAndPendingPaymentGuardsRemainIntact() {
        val transfer=card("陈小明").map { if(it.text=="一路向前")it.copy(text="扫二维码付款-给陈小明") else it }
        assertEquals("陈小明",parse(transfer).merchant)
        assertNull(LocalOcrBillParser.parse(card()+row("输入支付密码",200,1700,700,1740),900,1800,now))
        assertNull(LocalOcrBillParser.parse(card()+row("待收款",200,1700,700,1740),900,1800,now))
    }
}
