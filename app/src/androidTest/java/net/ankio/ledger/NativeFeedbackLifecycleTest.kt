package net.ankio.ledger

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.RecognitionBanner
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.LedgerRecord
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real banner timers and window owner; does not emulate an OEM island skin. */
@RunWith(AndroidJUnit4::class)
class NativeFeedbackLifecycleTest {
    @Test fun oneNativeHandoffCannotDuplicateReviveOrOutliveItsResult() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","合成原生交接 ${UUID.randomUUID()}",now),
            ParsedBill(2250,Direction.EXPENSE,"合成测试商户","餐饮",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",timeExplicit=true)) as IngestResult.Saved).record
        val system=app.getSystemService(WindowManager::class.java)
        val windows=mutableListOf<View>()
        var additions=0
        val manager=object:WindowManager by system {
            override fun getMaximumWindowMetrics()=system.maximumWindowMetrics
            override fun getCurrentWindowMetrics()=system.currentWindowMetrics
            override fun addView(view:View,params:ViewGroup.LayoutParams) {windows.add(view);additions++}
            override fun removeViewImmediate(view:View) {windows.remove(view)}
        }
        val context=object:ContextWrapper(app) {
            override fun getSystemService(name:String):Any?=if(name==Context.WINDOW_SERVICE)manager else super.getSystemService(name)
        }
        val banner=RecognitionBanner(context)
        fun field(name:String)=RecognitionBanner::class.java.getDeclaredField(name).apply {isAccessible=true}
        val owner=field("handler").get(banner) as Handler
        val generation=field("generation").get(banner) as AtomicInteger
        val schedule=RecognitionBanner::class.java.getDeclaredMethod("scheduleNativeResult",LedgerRecord::class.java,Int::class.javaPrimitiveType).apply {isAccessible=true}
        fun admit():Runnable {
            field("resultIdentity").set(banner,record.id to record.uid)
            schedule.invoke(banner,record,generation.get())
            return field("hide").get(banner) as Runnable
        }
        fun complete(callback:Runnable) {owner.removeCallbacks(callback);callback.run()}
        try {
            inst.runOnMainSync {
                val superseded=admit()
                val handoff=admit()
                assertFalse("A second scheduling replaces the first timer",owner.hasCallbacks(superseded))
                assertTrue(owner.hasCallbacks(handoff))
                assertTrue(windows.isEmpty())
                complete(handoff)
                assertEquals("Only one custom window follows the native summary",1,windows.size)
                assertEquals(1,additions)
                handoff.run()
                assertEquals("A repeated callback cannot attach a second result",1,additions)
                val expiry=field("hide").get(banner) as Runnable
                assertTrue(owner.hasCallbacks(expiry))
                complete(expiry)
                assertTrue("The custom card expires through its owner",windows.isEmpty())
                handoff.run()
                assertTrue("An expired result cannot be revived",windows.isEmpty())

                val removed=admit()
                banner.removeNow()
                assertFalse(owner.hasCallbacks(removed))
                removed.run()
                assertTrue(windows.isEmpty())

                val invalid=admit()
                field("resultIdentity").set(banner,record.id to "stale-uid")
                complete(invalid)
                assertTrue("Identity mismatch must not open a result",windows.isEmpty())
                assertNull(field("hide").get(banner))
                assertFalse(field("nativeDelivery").getBoolean(banner))

                val replaced=admit()
                banner.show("请开启页面识别权限",onRepair={})
                // show() increments the generation synchronously, before dispatching its UI work.
                replaced.run()
                assertTrue("New feedback invalidates an old native handoff",windows.isEmpty())
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertEquals("Fallback feedback owns one window",1,windows.size)
                assertNull(field("cardView").get(banner))
                banner.removeNow()
                assertTrue(windows.isEmpty())
            }
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none {it.id in setOf(4700,4701)})
        } finally {
            inst.runOnMainSync {banner.removeNow()}
            app.store.delete(record.id)
        }
    }
}
