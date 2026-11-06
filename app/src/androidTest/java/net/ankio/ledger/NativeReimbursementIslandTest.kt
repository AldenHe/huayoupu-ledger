package net.ankio.ledger

import android.app.Notification
import android.app.NotificationManager
import android.graphics.drawable.Icon
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.RecognitionLiveUpdate
import net.ankio.ledger.capture.XiaomiRecognitionIsland
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native templates carry a summary; the automatic one-second handoff is tested in ResultAccountPopupTest. */
@RunWith(AndroidJUnit4::class)
class NativeReimbursementIslandTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private fun published(manager:NotificationManager,id:Int,title:String):Notification {
        val until=android.os.SystemClock.elapsedRealtime()+5_000
        var notification:Notification?=null
        do {
            notification=manager.activeNotifications.firstOrNull {it.id==id && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()==title}?.notification
            if(notification!=null)return notification
            android.os.SystemClock.sleep(20)
        } while(android.os.SystemClock.elapsedRealtime()<until)
        error("Notification $id did not publish expected title: $title")
    }
    @Test fun nativeSuccessPublishesPrivateSummaryWithGenericCompactTextAndNoFinancialActions() {
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        val live=RecognitionLiveUpdate(context);val manager=context.getSystemService(NotificationManager::class.java)
        try {
            instrument.runOnMainSync {assertTrue(live.postResult("已记账 ¥128.00","星巴克臻选 · 餐饮美食","已记账"))}
            val n=published(manager,4701,"已记账 ¥128.00")
            assertEquals("已记账 ¥128.00",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("星巴克臻选 · 餐饮美食",n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE,n.visibility);assertTrue(n.actions.isNullOrEmpty())
            assertEquals("recognition_result_compact",n.channelId)
            assertEquals(NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel(n.channelId).importance)
            assertEquals(1_000L,n.timeoutAfter)
            assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE!=0)
            assertNull("Successful summaries must never use the old system big-text carrier",n.extras.getString(Notification.EXTRA_TEMPLATE))
            assertFalse(n.extras.containsKey(Notification.EXTRA_BIG_TEXT))
            assertNull(n.bigContentView);assertNull(n.headsUpContentView)
            val payload=JSONObject(XiaomiRecognitionIsland.extras(context,false,"已记账 ¥128.00","星巴克臻选 · 餐饮美食","已记账",1_000,expanded=false).getString("miui.focus.param")!!).getJSONObject("param_v2")
            assertEquals("已记账",payload.getString("ticker"));assertEquals("已记账",payload.getString("aodTitle"));assertFalse(payload.getBoolean("enableFloat"));assertFalse(payload.getBoolean("islandFirstFloat"));assertFalse(payload.has("actions"))
            assertEquals(1,payload.getJSONObject("param_island").getInt("islandTimeout"))
        } finally {instrument.runOnMainSync {live.cancel()}}
    }
    @Test fun standardLiveUpdateIsEligibleForColorOs16WithoutPrivateOemApis() {
        assumeTrue(context.packageName=="net.ankio.ledger.qa" && android.os.Build.VERSION.SDK_INT>=36)
        val live=RecognitionLiveUpdate(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        try {
            instrument.runOnMainSync {assertTrue(live.postFeedback("正在识别账单",true))}
            val progress=published(manager,4700,"正在识别账单")
            assertTrue("The standard notification must satisfy Android's live-update contract",progress.hasPromotableCharacteristics())
            assertEquals(Notification.CATEGORY_STATUS,progress.category)
            assertTrue(progress.extras.getBoolean("android.requestPromotedOngoing"))
            assertEquals("android_live_update",live.diagnostics().getString("nativePath"))
            assertTrue(live.diagnostics().getBoolean("hasPromotableCharacteristics"))
            android.os.SystemClock.sleep(600)
            assertTrue("Posting may await OEM promotion; it must not expire at 250 ms",manager.activeNotifications.any {it.id==4700})
            instrument.runOnMainSync {assertTrue(live.postResult("已记账 ¥22.50","合成测试商户 · 餐饮","已记账"))}
            val result=published(manager,4701,"已记账 ¥22.50")
            assertTrue(result.hasPromotableCharacteristics())
            assertTrue(manager.activeNotifications.none {it.id==4700})
        } finally {instrument.runOnMainSync {live.cancel()}}
    }
    @Test fun nativeProgressAndFailureHaveNoFinancialActionsAndCancelWithoutExpansion() {
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        val manager=context.getSystemService(NotificationManager::class.java)
        val live=RecognitionLiveUpdate(context)
        try {
            instrument.runOnMainSync {assertTrue(live.postFeedback("正在识别账单",true))}
            var current=published(manager,4700,"正在识别账单")
            assertTrue(current.actions.isNullOrEmpty())
            assertTrue(current.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertEquals(R.drawable.huayoupu_notification_small,current.smallIcon.resId)
            assertEquals(120_000L,current.timeoutAfter)
            instrument.runOnMainSync {assertTrue(live.postFeedback("未识别到金额，请重试",false))}
            current=published(manager,4701,"未识别到金额，请重试")
            assertTrue(current.actions.isNullOrEmpty());assertEquals(5_000L,current.timeoutAfter)
            assertEquals("未识别到金额，请重试",current.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertFalse(current.extras.containsKey("ledger.feedbackToken"))
            instrument.runOnMainSync {live.cancel()}
            android.os.SystemClock.sleep(1_250)
            assertTrue(manager.activeNotifications.none {it.id in setOf(4700,4701)})
            assertTrue(RecognitionLiveUpdate::class.java.declaredMethods.none {it.name=="handleAction"})
            assertTrue(context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_RECEIVERS)
                .receivers.orEmpty().none {it.name.endsWith("BillTagActionReceiver")})
        } finally {instrument.runOnMainSync {live.cancel()}}
    }
    @Test fun xiaomiProgressPayloadUsesStaticFullAppPictureWithoutActions() {
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        val loading=XiaomiRecognitionIsland.extras(context,true,"正在识别账单","正在读取当前账单，请稍候","识别中",120_000)
        val compact=JSONObject(loading.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertFalse(compact.getBoolean("islandFirstFloat"));assertFalse(compact.getBoolean("enableFloat"));assertFalse(compact.has("actions"))
        assertEquals(0,compact.getJSONObject("iconTextInfo").getJSONObject("animIconInfo").getInt("type"))
        val picture=loading.getBundle("miui.focus.pics")!!.getParcelable<Icon>("miui.focus.pic_ledger")!!
        assertEquals(Icon.TYPE_RESOURCE,picture.type);assertEquals(R.drawable.huayoupu_notification_icon,picture.resId)
        assertEquals(1,compact.getJSONObject("param_island").getJSONObject("smallIslandArea").getJSONObject("picInfo").getInt("type"))
        assertEquals(1,compact.getJSONObject("param_island").getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft").getJSONObject("picInfo").getInt("type"))
        assertEquals("识别中",compact.getJSONObject("param_island").getJSONObject("bigIslandArea").getJSONObject("textInfo").getString("title"))
    }
    @Test fun nativeChannelsRetainQuietProgressAndFullBrandArtwork() {
        assumeTrue(context.packageName=="net.ankio.ledger.qa")
        val progress=RecognitionLiveUpdate.channelFor(context,true)
        val failure=RecognitionLiveUpdate.channelFor(context,false)
        val legacyBefore=context.getSystemService(NotificationManager::class.java).getNotificationChannel(failure.id)
        val compact=RecognitionLiveUpdate.channelFor(context,false,compactResult=true)
        assertNotEquals(progress.id,failure.id)
        assertNotEquals("A previously installed high result channel cannot carry successes",failure.id,compact.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW,progress.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,failure.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW,compact.importance)
        assertNull(compact.sound);assertFalse(compact.shouldVibrate())
        val legacyAfter=context.getSystemService(NotificationManager::class.java).getNotificationChannel(failure.id)
        assertEquals("Do not rewrite the user's legacy channel preferences",legacyBefore,legacyAfter)
        assertNull(progress.sound);assertFalse(progress.shouldVibrate())
        val drawable=context.getDrawable(R.drawable.huayoupu_notification_icon)!!
        val bitmap=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0,0,128,128);drawable.draw(android.graphics.Canvas(bitmap))
        assertEquals(context.getColor(R.color.huayoupu_coral),bitmap.getPixel(0,0))
        assertEquals(context.getColor(R.color.huayoupu_coral),bitmap.getPixel(127,127))
        bitmap.recycle()
    }
}
