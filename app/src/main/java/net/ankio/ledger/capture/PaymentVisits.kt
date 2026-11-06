package net.ankio.ledger.capture

import android.content.Context
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.core.PaymentMatch
import net.ankio.ledger.core.PaymentPage
import net.ankio.ledger.core.PaymentPageDetector

/** A retained result can receive a new native window ID after backgrounding. */
internal class PaymentVisits(context:Context,name:String="payment_visits") {
    private val preferences=context.getSharedPreferences(name,Context.MODE_PRIVATE)

    companion object {
        fun isNavigation(packageName:String,activity:String)=PaymentApps.supported(packageName) &&
            (activity in setOf("com.alipay.mobile.quinox.LauncherActivity","com.eg.android.AlipayGphone.AlipayLogin") ||
                (activity.endsWith("UI") || activity.endsWith("Activity")) &&
                !PaymentPageDetector.eligibleActivity(packageName,activity))
    }

    @Synchronized fun detect(page:PaymentPage):PaymentMatch? {
        // Only the current, owner-verified page may end a visit. Raw activity
        // events from other windows or an app-resume burst are not navigation.
        if(isNavigation(page.packageName,page.activity)) {
            // Home may briefly become active beneath the retained result window.
            // Navigation alone cannot make that same result a second purchase.
            check(preferences.edit().putBoolean("${page.packageName}.navigation",true).commit())
            return null
        }
        val match=PaymentPageDetector.detect(page)
        if(match==null) {
            // Inputs are observed only as a flag; their values never enter this gate.
            if(page.editable || page.texts.any { text -> listOf("确认付款","确认支付","请输入支付密码","请输入付款密码").any(text::contains) })begin(page.packageName)
            return null
        }
        if(match.transactionId!=null)return match
        val window="${page.windowId}|${page.activity}"
        val previous=preferences.getString("${page.packageName}.result",null)
        if(preferences.getBoolean("${page.packageName}.navigation",false) && previous!=null && previous!=window)begin(page.packageName)
        val visit=preferences.getString(page.packageName,null) ?: window.also {
            check(preferences.edit().putString(page.packageName,it).commit())
        }
        check(preferences.edit().putString("${page.packageName}.result",window).putBoolean("${page.packageName}.navigation",false).commit())
        return PaymentPageDetector.detect(page,visit)
    }

    private fun begin(packageName:String) {
        if(preferences.contains(packageName))check(preferences.edit().putString(packageName,java.util.UUID.randomUUID().toString())
            .remove("$packageName.result").putBoolean("$packageName.navigation",false).commit())
    }
}
