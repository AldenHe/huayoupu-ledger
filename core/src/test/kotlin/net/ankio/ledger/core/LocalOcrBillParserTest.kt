package net.ankio.ledger.core

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalOcrBillParserTest {
    @Test fun explicitPendingReceiptIsSkippedButImmediateOutgoingSuccessStillPosts() {
        val lines=listOf(LocalOcrLine("转账成功"),LocalOcrLine("待测试收款人确认收款"),LocalOcrLine("转账金额：¥0.01"),LocalOcrLine("转账时间：2026-11-05 15:06:00"))
        assertNull(LocalOcrBillParser.parse(lines,0,0,now,origin=PaymentApps.WECHAT))
        val automatic=LocalOcrBillParser.parse(lines,0,0,now,expenseOnly=true,origin=PaymentApps.WECHAT)
        assertNotNull(automatic);assertEquals(Direction.EXPENSE,automatic!!.direction);assertEquals(1L,automatic.amountCents)
        assertNotNull(LocalOcrBillParser.parse(lines.filterNot {it.text.startsWith("待")},0,0,now,origin=PaymentApps.WECHAT))
    }
    @Test fun closedShoppingOrderNeverCreatesARefundReviewDraft() {
        for(status in listOf("交易关闭", "订单已关闭", "订单已取消")) {
            assertNull(status,parse(status,"店铺名称：合成旗舰店","实付价¥14.90",
                "退款成功 支付宝¥14.90","实付款¥14.9","订单信息共6项","5127805189031056102|复制",origin=PaymentApps.TAOBAO))
        }
    }
    @Test fun pendingRefundNeverCreatesAnAmountReviewDraft() {
        for(status in listOf("退款处理中", "退款申请中", "退款审核中", "退款中", "待退款")) {
            assertNull(status,parse("交易成功",status,"实付款¥14.90","退款金额¥14.90",origin=PaymentApps.TAOBAO))
        }
        assertNotNull(parse("交易成功","实付款¥14.90","申请退款","极速退款",origin=PaymentApps.TAOBAO))
    }
    @Test fun orderInfoDisclosureGlyphCannotLoseAnExplicitOrderNumber() {
        for(arrow in listOf("√", "∨", "⌄", "v")) {
            val lines=listOf(LocalOcrLine("实付款¥39.48"),LocalOcrLine("订单信息共8项$arrow"),
                LocalOcrLine("5127298851523000911|复制"))
            assertEquals(arrow,"5127298851523000911",LocalOcrBillParser.transactionId(lines))
            assertEquals(arrow,"5127298851523000911",LocalOcrBillParser.parse(lines,0,0,now,origin=PaymentApps.TAOBAO)!!.transactionId)
        }
    }

    @Test fun orderStoreRowWinsOverShareButtonsAboveTheActualTotal() {
        val bill=LocalOcrBillParser.parse(listOf(
            line("双新 合成厨具官方旗舰店 官方旗舰 11.11",36,380,560),
            line("分享商品",260,795,130),line("先用后付 实付: ¥6.89 (免运费)",378,892,430),
            line("订单编号: 260925-183731556732484",28,962,500)
        ),827,1800,now,origin="com.xunmeng.pinduoduo")!!
        assertEquals(689L,bill.amountCents);assertEquals("双新 合成厨具官方旗舰店",bill.merchant)
    }
    @Test fun displayMerchantAboveAmountWinsOverLegalMerchantFullName() {
        val bill=LocalOcrBillParser.parse(listOf(line("合成餐厅",200,330,420),line("-15.58",300,440,240,90),
            line("支付成功",420,700),line("商户全称",60,900,180),line("商户_张某",400,900)),900,1800,now)!!
        assertEquals("合成餐厅",bill.merchant)
    }
    @Test fun verifiedLiveScanResultKeepsProminentAmountWithoutRepeatingSuccessWords() {
        val lines=listOf(line("12:11",79,43,144,26),line("64.4",493,40,33,12),
            line("合成自助餐厅",223,332,382,33),line("15.58",322,413,235,81),
            line("摇一摇,有优惠",247,675,339,55),line("完成",381,1492,66,33))
        for(origin in listOf(PaymentApps.WECHAT,PaymentApps.ALIPAY)) {
            val bill=LocalOcrBillParser.parse(lines,827,1800,now,now,expenseOnly=true,origin=origin)
            assertNotNull("Native-verified live result must not depend on a second success OCR",bill)
            assertEquals(1558L,bill!!.amountCents);assertEquals("合成自助餐厅",bill.merchant)
            assertTrue(bill.reason,bill.reliable);assertEquals(Direction.EXPENSE,bill.direction)
            assertTrue(bill.reason.startsWith("已识别即时支付成功页"))
            assertNull(LocalOcrBillParser.parse(lines,827,1800,now,expenseOnly=true,origin=origin))
            assertNull(LocalOcrBillParser.parse(lines,827,1800,now,now+1,expenseOnly=true,origin=origin))
            for(blocked in listOf("请输入支付密码","待付款","收款成功","退款成功")) {
                assertNull(blocked,LocalOcrBillParser.parse(lines+line(blocked,100,200),827,1800,now,now,true,origin))
            }
            val conflict=LocalOcrBillParser.parse(lines+line("付款金额:18.00",100,900),827,1800,now,now,true,origin)
            assertTrue(conflict==null || !conflict.reliable)
        }
        assertNull(LocalOcrBillParser.parse(lines,827,1800,now,now,true,"com.example.shopping"))
    }
    @Test fun actualShoppingOcrDecorationsKeepFinalAmountAndOrderIdentity() {
        val pdd=LocalOcrBillParser.parse(listOf(LocalOcrLine("先用后付 实付: ¥6.890(免运费)"),
            LocalOcrLine("订单编号:260925-183731556732484")),0,0,System.currentTimeMillis())
        assertNotNull(pdd);assertEquals(689L,pdd!!.amountCents);assertTrue(pdd.reliable)
        val idle=LocalOcrBillParser.parse(listOf(LocalOcrLine("成交价(已到达卖家账戶)",10,500,300,530),
            LocalOcrLine("3799.00 v",600,500,800,530),LocalOcrLine("3909.00",600,400,800,430),
            LocalOcrLine("订单編号",10,600,160,630),LocalOcrLine("5127339747176000911 |复制",400,600,800,630)),900,1800,System.currentTimeMillis())
        assertEquals(379900L,idle!!.amountCents);assertTrue(idle.reliable);assertEquals("5127339747176000911",idle.transactionId)
        val taobao=LocalOcrBillParser.parse(listOf(LocalOcrLine("实付款¥22.5"),
            LocalOcrLine("订单信息共5页v",30,1100,270,1130),LocalOcrLine("5127861889007037738 复制",400,1100,800,1130)),900,1800,System.currentTimeMillis())
        assertEquals("5127861889007037738",taobao!!.transactionId)
    }
    @Test fun commonManualTotalsDoNotRequirePageTemplatesAndPreferTotalOverProductPrice() {
        for((label,price) in listOf("实付:¥6.89（免运费）" to 689L,"合计¥35.8" to 3580L,
            "成交价（已到达卖家账户）¥3799.00" to 379900L,"实付款¥21.90" to 2190L)) {
            val result=parse("订单详情","商品 ¥3989.00",label,origin="com.example.shopping")
            assertNotNull(label,result);assertEquals(label,price,result!!.amountCents);assertTrue(label,result.reliable)
        }
    }
    @Test fun orderInfoRowExtractsTheOrderIdApartFromItsItemCountAndCopyControl() {
        val result=LocalOcrBillParser.parse(listOf(
            line("订单信息 共5项",30,1100,210),line("512786188900737738 复制",400,1100,400),
            line("实付款¥22.5",610,1000,230)),900,1900,now,origin=PaymentApps.TAOBAO)!!
        assertEquals("512786188900737738",result.transactionId)
        assertTrue(result.reliable)
        assertEquals("260925-183731556732484",parse("实付¥6.89","订单编号：260925-183731556732484",origin="com.xunmeng.pinduoduo")!!.transactionId)
    }
    @Test fun shippedPayLaterOrderUsesActualItemPriceWhenPaymentTotalIsZero() {
        for(price in listOf("12.56","5.63")) {
            val result=parse("订单详情","待收货","店铺名称：合成旗舰店","商品总价：16.20元",
                "实付价：${price}元","实付款：0元","先用后付","确认收货",origin=PaymentApps.TAOBAO)
            assertNotNull("A delivered pay-later order has an explicit actual price",result)
            assertEquals(Money.parseCents(price),result!!.amountCents)
            assertTrue(result.reason,result.reliable)
            assertEquals(now,result.occurredAt)
            assertNull(parse("待付款","实付价：${price}元","实付款：0元",origin=PaymentApps.TAOBAO))
            assertNull(parse("待收货","实付价：${price}元","实付款：0元",origin=PaymentApps.TAOBAO,expenseOnly=true))
        }
    }
    @Test fun fullyRefundedOriginalPageHasAConciseSkipReason() {
        val raw="账单详情 120.00 已全额退款 查看关联记录 支付时间 2026-10-18 22:23:45"
        assertTrue(StandaloneRefundReceipt.matches(raw))
        assertNull(parse(raw))
    }
    @Test fun actualPayLaterOcrKeepsPriceDetailSuffixAndCurrencyZeroConfusionOutOfTheAmount() {
        for((label,expected) in listOf("实付价¥12.56 价格明细>" to 1256L,"实付价¥5.63 价格明细〉" to 563L)) {
            val result=parse("订单详情","确认收货",label,"实付款¥O",origin=PaymentApps.TAOBAO)
            assertNotNull(result);assertEquals(expected,result!!.amountCents);assertTrue(result.reliable)
        }
        assertTrue(StandaloneRefundReceipt.matches("120.00 已全颌退款> 查看关联记录"))
    }
    @Test fun recipientFullNameLabelNeverBecomesTheMerchantName() {
        val bill=LocalOcrBillParser.parse(listOf(LocalOcrLine("交易成功"),LocalOcrLine("-20.00"),
            LocalOcrLine("收款方全称"),LocalOcrLine("合成云计算有限公司")),0,0,System.currentTimeMillis())!!
        assertEquals("合成云计算有限公司",bill.merchant)
    }
    @Test fun standaloneRefundReceiptIsNotAnExpenseOrIncome() {
        assertNull(LocalOcrBillParser.parse(listOf(LocalOcrLine("退款成功"),LocalOcrLine("120.00"),
            LocalOcrLine("关联记录"),LocalOcrLine("查看原账单"),LocalOcrLine("退款方式"),LocalOcrLine("余额")),0,0,System.currentTimeMillis()))
    }
    @Test fun explicitManualPaidAmountPostsWithoutMerchantDateOrSuccessWords() {
        val result=parse("订单详情","实付款：26.90元",origin=PaymentApps.TAOBAO)!!
        assertEquals(2690L,result.amountCents)
        assertEquals(now,result.occurredAt)
        assertFalse(result.timeExplicit)
        assertTrue(result.reason,result.reliable)
        assertTrue(result.merchant.isNotBlank())
        assertNull(parse("订单详情","实付款：26.90元",expenseOnly=true,origin=PaymentApps.TAOBAO))
        assertNull(parse("订单详情","待付款","实付款：26.90元",origin=PaymentApps.TAOBAO))
        val otherTimes=parse("订单详情","实付款：26.90元","创建时间：2026年10月17日 12:00:00","发货时间：2026年10月18日 10:00:00",origin=PaymentApps.TAOBAO)!!
        assertEquals(now,otherTimes.occurredAt)
        assertFalse(otherTimes.timeExplicit)
    }
    @Test fun orderTotalDisclosureArrowAndStoreRowDoNotHideThePaidOrder() {
        val result=LocalOcrBillParser.parse(listOf(
            line("已发货",400,150,200,60),line("合成母婴店",180,680,200,45),line("进店逛逛〉",800,680,200,45),
            line("实付价 ¥26.9",300,1000),line("实付款¥26.9~",700,1300),
            line("付款时间",50,1800,180),line("2026-10-17 13:11:55",650,1800,400),
            line("支付宝交易号",50,1700,250),line("202610171234567890123456",500,1700,550),
        ),1200,2600,now,origin=PaymentApps.TAOBAO)!!
        assertTrue(result.reason,result.reliable)
        assertEquals(2690L,result.amountCents)
        assertEquals("合成母婴店",result.merchant)
    }
    @Test fun shippedShoppingOrderUsesPaidTotalPaymentTimeAndStoreInsteadOfItemPrice() {
        val result = parse("已发货", "订单详情", "店铺名称：合成母婴店", "商品价格：99.00元", "实付价：26.90元",
            "实付款：26.90元", "创建时间：2026年10月17日 13:11:50", "付款时间：2026年10月17日 13:11:55",
            "发货时间：2026年10月18日 10:00:00", "支付宝交易号：202610171234567890123456", "极速退款", origin=PaymentApps.TAOBAO)!!
        assertEquals(2690L,result.amountCents)
        assertEquals("合成母婴店",result.merchant)
        assertEquals("202610171234567890123456",result.transactionId)
        assertTrue(result.reason,result.reliable)
        assertEquals(LocalDateTime.of(2026,10,17,13,11,55).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),result.occurredAt)
    }

    @Test fun shoppingStatusesAloneNeverProveMoneyWasDeducted() {
        for (status in listOf("待付款","待发货","已发货","待收货","交易关闭")) {
            assertNull(status,parse(status,"商品价格：99元", "店铺名称：合成商店",origin=PaymentApps.TAOBAO))
        }
        assertNull(parse("已发货","先用后付","实付款：0元","确认收货后再付款：99元",origin=PaymentApps.TAOBAO))
        assertNull(parse("待付款","实付款：99元","付款时间：2026年10月17日 12:00:00",origin=PaymentApps.TAOBAO))
    }
    private val now = LocalDateTime.of(2026, 10, 18, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun line(text: String, x: Int, y: Int, w: Int = 360, h: Int = 36) = LocalOcrLine(text, x, y, x + w, y + h)
    private fun parse(vararg text: String, live: Boolean = false, expenseOnly: Boolean = false, origin: String = "com.tencent.mm") =
        LocalOcrBillParser.parse(text.map(::LocalOcrLine), 0, 0, now, if (live) now else null, expenseOnly, origin)

    @Test fun shuffledWechatOcrBlocksAreJoinedByRowsAndLargeHeaderWinsOverBalance() {
        val result = LocalOcrBillParser.parse(listOf(
            line("交易单号", 60, 1110, 150), line("2026年10月17日 02:32:51", 260, 930),
            line("零钱余额", 60, 1000, 150), line("10001073012026101701183202026521", 260, 1110),
            line("-26.35", 340, 510, 220, 70), line("北京速通科技有限公司", 180, 410, 540, 42),
            line("支付时间", 60, 930, 150), line("支付成功", 260, 750), line("当前状态", 60, 750, 150),
            line("¥2,354.00", 260, 1000), line("高速代扣", 260, 870), line("商品", 60, 870, 100),
        ), 900, 1900, now)!!
        assertEquals(2635L, result.amountCents)
        assertEquals("北京速通科技有限公司", result.merchant)
        assertEquals("交通出行", result.category)
        assertEquals("10001073012026101701183202026521", result.transactionId)
        assertTrue(result.reason, result.reliable)
        assertTrue(result.timeExplicit)
    }

    @Test fun scanPaymentNamedRecipientIsNotMistakenForIncome() {
        val result = parse("扫二维码付款-给示例收款人", "支付成功", "收款方备注", "二维码收款", "-0.01", "转账时间", "2026年10月17日 02:32:51")!!
        assertEquals("示例收款人", result.merchant)
        assertEquals(Direction.EXPENSE, result.direction)
        assertEquals(1L, result.amountCents)
        assertTrue(result.reason, result.reliable)
    }

    @Test fun observedBundledOcrLabelVariantAndSplitMonthRetainEvidenceWithoutGuessing() {
        val result = LocalOcrBillParser.parse(listOf(
            line("支付成功", 390, 112, 260, 60), line("- ¥ 28.50", 306, 544, 436, 82),
            line("商戶各称", 88, 878, 172, 47), line("知余咖啡店", 423, 879, 217, 44),
            line("支付时间", 86, 997, 174, 48), line("2026-1 0-18 11:48:30", 439, 1006, 369, 38),
        ), 1080, 1800, now)!!
        assertEquals("知余咖啡店", result.merchant)
        assertEquals(2850L, result.amountCents)
        assertTrue(result.reason, result.reliable)
        assertTrue(result.timeExplicit)
    }

    @Test fun livePaymentUsesObservedClockButImportWithoutDateRemainsUncertain() {
        val live = parse("支付成功", "付款金额：28.50元", "商户名称：合成商户", live = true)!!
        assertTrue(live.reason, live.reliable)
        assertFalse(live.timeExplicit)
        assertEquals(now, live.occurredAt)
        assertTrue("Live capture provenance is consumed by cross-source supplementation", live.reason.startsWith("已识别即时支付成功页"))
        val imported = parse("支付成功", "付款金额：28.50元", "商户名称：合成商户")!!
        assertTrue(imported.reason,imported.reliable)
        assertFalse(imported.timeExplicit)
        assertEquals(now,imported.occurredAt)
    }

    @Test fun credentialsPendingAndFailedPaymentsCannotBecomeBills() {
        for (status in listOf("请输入支付密码", "指纹验证", "待付款", "支付失败", "交易关闭")) {
            assertNull(status, parse("支付成功", "付款金额：88.00元", "商户名称：合成商户", status, live = true))
        }
        assertNull(parse("商品详情", "订单金额：88.00元", "去付款"))
        assertNull(parse("待对方收款", "转账金额：88.00元"))
    }

    @Test fun successfulOutgoingTransferDoesNotWaitForRecipientConfirmation() {
        val result = parse("转账成功", "等待对方收款", "转账金额：88.00元", "商户名称：合成收款人", live = true)!!
        assertTrue(result.reason, result.reliable)
        assertEquals(Direction.EXPENSE, result.direction)
        assertEquals(8800L, result.amountCents)
    }

    @Test fun conflictingHeaderAndLabeledAmountCannotPostAndExplicitIncomeWinsOverIncidentalPaymentText() {
        val conflict = parse("支付成功", "¥88.00", "付款金额：68.00元", "商户名称：合成商户", live = true)!!
        assertFalse(conflict.reliable)
        assertNull(conflict.amountCents)
        val incoming = parse("收入", "收款成功", "对方支付成功", "+88.00", "收款金额：88.00元", "付款方：合成付款人", "交易时间：2026年10月17日 12:00:00")!!
        assertEquals(Direction.INCOME, incoming.direction)
    }

    @Test fun ambiguousHeaderAndPromotionalAmountsNeverChooseArbitraryValue() {
        val ambiguous = parse("支付成功", "¥18.00", "¥28.00", "商户名称：合成商户", live = true)!!
        assertNull(ambiguous.amountCents)
        assertFalse(ambiguous.reliable)
        val promotional = parse("支付成功", "最高88元优惠券", "零钱余额：1000.00元", "商户名称：合成商户", live = true)!!
        assertNull(promotional.amountCents)
        val valid = parse("支付成功", "¥18.00", "立减优惠2.00元", "商户名称：合成商户", live = true)!!
        assertEquals(1800L, valid.amountCents)
    }

    @Test fun amountCannotBeAnOrderNumberOrFractionalCent() {
        assertNull(parse("支付成功", "交易单号：4200003240202610169770990343", "商户名称：合成商户", live = true)!!.amountCents)
        assertNull(parse("支付成功", "付款金额：2.345元", "商户名称：合成商户", live = true)!!.amountCents)
        assertNull(parse("支付成功", "付款金额：-99999999999999999999999元", "商户名称：合成商户", live = true)!!.amountCents)
    }

    @Test fun separatelyRecognizedBalanceOrCouponCannotReplaceUnreadablePaymentAmount() {
        val result = LocalOcrBillParser.parse(listOf(
            line("支付成功", 390, 112, 260, 60), line("商户名称", 88, 878, 172, 47),
            line("合成商户", 423, 879, 217, 44), line("账户余额", 86, 997, 174, 48),
            line("999.00元", 439, 1006, 369, 38),
        ), 1080, 1800, now, now)!!
        assertNull(result.amountCents)
        assertFalse(result.reliable)
        assertNull(parse("支付成功", "商户名称：合成商户", "账户余额", "999.00元", live = true)!!.amountCents)
    }

    @Test fun receivedMoneyIsAvailableOnlyToManualImportOrShake() {
        val incoming = parse("收款成功", "收款金额：99.00元", "付款方：合成付款人", "交易时间：2026年10月17日 12:00:00")!!
        assertEquals(Direction.INCOME, incoming.direction)
        assertEquals(9900L, incoming.amountCents)
        assertTrue(incoming.reason, incoming.reliable)
        assertNull(parse("收款成功", "收款金额：99.00元", "付款方：合成付款人", expenseOnly = true))
    }

    private fun refund(vararg extra: String) = arrayOf("微信支付", "已部分退款", "原付款金额：100.00元", "累计退款：63.46元",
        "商户名称：合成商户", "原支付时间：2026年10月17日 12:00:00", "原交易单号：420000000012345678901234", *extra)

    @Test fun completedCumulativeRefundComputesFinalExpenseLocallyAndPreservesOriginalIdentity() {
        val result = parse(*refund())!!
        assertEquals(3654L, result.amountCents)
        assertEquals(10000L, result.refund!!.originalAmountCents)
        assertEquals(6346L, result.refund!!.refundedCents)
        assertEquals("cumulative", result.refund!!.mode)
        assertEquals("wechat", result.refund!!.provider)
        assertTrue(result.reason, result.reliable)
        assertEquals("420000000012345678901234", result.transactionId)
        assertNull(parse(*refund(), expenseOnly = true))
    }

    @Test fun fullRefundAllowsZeroButPendingAndExcessRefundCannotReduceOriginal() {
        val full = parse(*refund().map { if (it == "累计退款：63.46元") "累计退款：100.00元" else it }.toTypedArray())!!
        assertEquals(0L, full.amountCents)
        assertTrue(full.reliable)
        for (extra in listOf("退款处理中", "退款申请中", "退款失败")) {
            assertNull(extra,parse(*refund(extra)))
        }
        val excessive = parse(*refund().map { if (it == "累计退款：63.46元") "累计退款：100.01元" else it }.toTypedArray())!!
        assertNull(excessive.refund)
        assertFalse(excessive.reliable)
    }

    @Test fun singleRefundNeedsSeparateIdAndCannotReuseRefundTimeAsOriginalPaymentTime() {
        val fields = arrayOf("支付宝", "退款成功", "原付款金额：100.00元", "本次退款：10.00元", "商户名称：合成商户",
            "退款时间：2026年10月18日 11:00:00", "原交易单号：202610171234567890123456")
        val missing = parse(*fields, origin = "com.eg.android.AlipayGphone")!!
        assertNull(missing.refund)
        assertFalse(missing.timeExplicit)
        val complete = parse(*fields, "退款单号：202610181234567890123456", "原支付时间：2026年10月17日 12:00:00", origin = "支付宝")!!
        assertEquals(9000L, complete.amountCents)
        assertEquals("single", complete.refund!!.mode)
        assertTrue(complete.reason, complete.reliable)
    }

    @Test fun refundHelpButtonDoesNotTurnNormalPaymentIntoRefundEvent() {
        val result = parse("支付成功", "付款金额：10.00元", "商户名称：合成商户", "申请退款", live = true)!!
        assertNull(result.refund)
        assertEquals(1000L, result.amountCents)
        assertTrue(result.reason, result.reliable)
    }

    @Test fun unreadableOrFutureDatesUseRecognitionTimeWithoutBlockingManualEntry() {
        for (date in listOf("2026年02月30日 11:00:00", "2027年10月17日 12:00:00")) {
            val result = parse("支付成功", "付款金额：10.00元", "商户名称：合成商户", "支付时间：$date")!!
            assertTrue(result.reason,result.reliable)
            assertFalse(result.timeExplicit)
            assertEquals(now,result.occurredAt)
        }
    }
}
