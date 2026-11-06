package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.PaymentVisits
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentVisitsTest {
    private fun page(window:Int=7,amount:String="12.34",transaction:String?=null)=PaymentPage(
        PaymentApps.WECHAT,window,PaymentPageDetector.WECHAT_LITE_RESULT,
        listOf("支付成功","合成测试商店","¥$amount","返回商家")+
            (transaction?.let { listOf("交易单号",it) }?:emptyList()))

    private fun navigation(visits:PaymentVisits,pkg:String,activity:String) {
        assertNull(visits.detect(PaymentPage(pkg,99,activity,emptyList())))
    }

    private fun withVisits(test:(PaymentVisits,()->PaymentVisits)->Unit) {
        val context=ApplicationProvider.getApplicationContext<LedgerApp>()
        val name="payment_visits_test_${java.util.UUID.randomUUID()}"
        try { test(PaymentVisits(context,name)) { PaymentVisits(context,name) } }
        finally { context.deleteSharedPreferences(name) }
    }

    @Test fun recreatedWindowsAndRestartRetainTheOriginalPaymentIdentity()=withVisits { visits,reopen ->
        val first=visits.detect(page())!!
        assertEquals("Preserve pre-upgrade identities for the original window",PaymentPageDetector.detect(page())!!.eventIdentity,first.eventIdentity)
        val resumed=visits.detect(page(72))!!
        assertNotEquals("Frame authorization still belongs to the actual window",first.signature,resumed.signature)
        assertEquals(first.eventIdentity,resumed.eventIdentity)
        assertEquals(first.eventIdentity,reopen().detect(page(80))!!.eventIdentity)
    }

    @Test fun externalAppsAndIncompleteReadsDoNotCreateAnotherPayment()=withVisits { visits,_ ->
        val first=visits.detect(page())!!
        navigation(visits,"com.android.launcher3","com.android.launcher3.Launcher")
        navigation(visits,"net.ankio.ledger","net.ankio.ledger.ui.MainActivity")
        navigation(visits,PaymentApps.WECHAT,page().activity)
        assertNull(visits.detect(page(72).copy(texts=emptyList())))
        assertNull(visits.detect(page(72).copy(texts=listOf("支付成功","返回商家"))))
        assertEquals(first.eventIdentity,visits.detect(page(72))!!.eventIdentity)
    }

    @Test fun navigationCannotReclassifyTheSameRetainedResultAsAnotherPayment()=withVisits { visits,reopen ->
        val first=visits.detect(page())!!
        navigation(visits,PaymentApps.WECHAT,"com.tencent.mm.ui.LauncherUI")
        assertEquals(first.eventIdentity,reopen().detect(page())!!.eventIdentity)
        navigation(visits,PaymentApps.WECHAT,"com.tencent.mm.ui.LauncherUI")
        val second=reopen().detect(page(73))!!
        assertNotEquals(first.eventIdentity,second.eventIdentity)
        assertEquals(second.eventIdentity,reopen().detect(page(73))!!.eventIdentity)
    }

    @Test fun newConfirmationOrPasswordFlagAllowsASecondSameAmountPayment() {
        for(preflight in listOf(page().copy(editable=true,texts=emptyList()),page().copy(texts=listOf("请确认付款：¥12.34"))))
            withVisits { visits,_ ->
                val first=visits.detect(page())!!
                assertNull(visits.detect(preflight))
                assertNotEquals(first.eventIdentity,visits.detect(page())!!.eventIdentity)
            }
    }

    @Test fun knownTransactionIdsRemainIndependentOfVisits()=withVisits { visits,_ ->
        val first=visits.detect(page(transaction="4200003240202610171234567890"))!!
        navigation(visits,PaymentApps.WECHAT,"com.tencent.mm.ui.LauncherUI")
        assertEquals(first.eventIdentity,visits.detect(page(73,transaction=first.transactionId))!!.eventIdentity)
        assertNotEquals(first.eventIdentity,visits.detect(page(transaction="4200003240202610171234567891"))!!.eventIdentity)
    }

    @Test fun alipayHomeNavigationStartsANewPaymentVisit()=withVisits { visits,reopen ->
        val result=page().copy(packageName=PaymentApps.ALIPAY,activity="com.alipay.fixture.PaymentResultActivity")
        val first=visits.detect(result)!!
        assertEquals(first.eventIdentity,reopen().detect(result.copy(windowId=73))!!.eventIdentity)
        navigation(visits,PaymentApps.ALIPAY,"com.alipay.mobile.quinox.LauncherActivity")
        val second=reopen().detect(result.copy(windowId=74))!!
        assertNotEquals(first.eventIdentity,second.eventIdentity)
        assertEquals(second.eventIdentity,reopen().detect(result.copy(windowId=75))!!.eventIdentity)
    }

    @Test fun changedAmountsAndOtherPaymentAppsKeepSeparateIdentities()=withVisits { visits,_ ->
        val first=visits.detect(page())!!
        val other=page().copy(packageName=PaymentApps.ALIPAY,activity="com.alipay.android.msp.ui.views.MspContainerActivity")
        assertNotEquals(first.eventIdentity,visits.detect(other)!!.eventIdentity)
        assertNotEquals(first.eventIdentity,visits.detect(page(amount="23.45"))!!.eventIdentity)
        navigation(visits,PaymentApps.ALIPAY,"com.alipay.mobile.MainActivity")
        assertEquals(first.eventIdentity,visits.detect(page(75))!!.eventIdentity)
    }
}
