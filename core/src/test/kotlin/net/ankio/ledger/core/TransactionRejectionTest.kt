package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class TransactionRejectionTest {
    @Test fun manualAwaitingReceiptCannotCreateAnotherExpenseOrIncome() {
        for(status in listOf("待收款","待对方收款","待测试收款人确认收款","等待对方确认收款","对方尚未收款","当前状态：待收款")) {
            val raw="转账详情\n$status\n转账金额：¥0.01\n转账时间：2026-11-05 15:06:00"
            assertEquals(status,"待收款记录，不予重复记账",TransactionRejection.reason(raw,manualCapture=true))
            assertNull("Automatic outgoing success and existing stored records retain their policy",TransactionRejection.reason(raw))
        }
        assertNull(TransactionRejection.reason("商户名称：待收款服务店\n支付成功\n付款金额：¥8.00",manualCapture=true))
        assertNull(TransactionRejection.reason("转账详情\n对方已收款\n转账金额：¥0.01",manualCapture=true))
    }
    @Test fun matchingSuccessfulOrderRefundAndPaidTotalAreNotAnotherExpense() {
        assertEquals("已全额退款，不予记账",TransactionRejection.reason("订单详情\n实付款¥14.9\n退款成功 支付宝¥14.90"))
        assertNull(TransactionRejection.reason("订单详情\n实付款¥14.90\n退款成功 支付宝¥4.90"))
    }

    @Test fun feedbackIsSpecificAndDoesNotMistakeServiceButtonsForRefunds() {
        assertEquals("交易已关闭，不予记账",TransactionRejection.reason("交易关闭\n退款成功 支付宝¥14.90\n实付款¥14.9"))
        assertEquals("退款处理中，暂不记账",TransactionRejection.reason("退款处理中\n退款金额¥14.90\n实付款¥14.90"))
        assertEquals("退款记录，不予记账",TransactionRejection.reason("退款成功\n120.00\n查看原账单\n退款方式 余额"))
        assertNull(TransactionRejection.reason("交易成功\n申请退款\n极速退款\n实付款¥14.90"))
    }
}
