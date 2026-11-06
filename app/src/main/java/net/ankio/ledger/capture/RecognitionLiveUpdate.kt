package net.ankio.ledger.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import net.ankio.ledger.R
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject

/** Native summaries contain no financial actions; RecognitionBanner owns the automatic result-card handoff. */
internal class RecognitionLiveUpdate(private val context:Context) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    private var failure=""
    private var focusProtocol=0
    private var focusAllowed=false
    private var lastChannel=""
    private var lastImportance=0
    private var lastPromotable=false
    val feedbackDuration=5_000L

    fun showResult(title:String,summary:String,critical:String):Boolean {
        if(!manager.areNotificationsEnabled())return false
        focusProtocol=runCatching {Settings.System.getInt(context.contentResolver,"notification_focus_protocol",0)}.getOrDefault(0)
        focusAllowed=focusProtocol>=3 && runCatching {context.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"),"canShowFocus",null,Bundle().apply {putString("package",context.packageName)})?.getBoolean("canShowFocus",false)==true}.getOrDefault(false)
        return (focusAllowed || canPromote()) && postResult(title,summary,critical)
    }
    internal fun postResult(title:String,summary:String,critical:String):Boolean=postFeedback(title,false,summary,critical)

    fun show(message:String,loading:Boolean):Boolean {
        if(!manager.areNotificationsEnabled())return false
        focusProtocol=runCatching {Settings.System.getInt(context.contentResolver,"notification_focus_protocol",0)}.getOrDefault(0)
        focusAllowed=focusProtocol>=3 && runCatching {
            context.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"),"canShowFocus",null,
                Bundle().apply {putString("package",context.packageName)})?.getBoolean("canShowFocus",false)==true
        }.getOrDefault(false)
        if(!focusAllowed && !canPromote())return false
        return postFeedback(message,loading)
    }
    /** Publication seam for testing native content independently of OEM admission. */
    internal fun postFeedback(message:String,loading:Boolean,summary:String?=null,shortText:String?=null):Boolean=runCatching {
        val compactResult=summary!=null && !loading
        val channel=channelFor(context,loading,compactResult)
        check(channel.importance!=NotificationManager.IMPORTANCE_NONE)
        lastChannel=channel.id;lastImportance=channel.importance
        val duration=if(loading)120_000L else if(compactResult)RESULT_SUMMARY_DURATION else feedbackDuration
        val title=if(loading)"正在识别账单" else message
        val subtitle=summary ?: if(loading)"正在读取当前账单，请稍候" else "本次识别已结束"
        val critical=shortText ?: if(loading)"识别中" else "已结束"
        val intent=Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val builder=Notification.Builder(context,channel.id)
            .setSmallIcon(R.drawable.huayoupu_notification_small).setColor(context.getColor(R.color.huayoupu_coral))
            .setContentTitle(title).setContentText(subtitle)
            .setContentIntent(PendingIntent.getActivity(context,ID,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(loading || compactResult).setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setLocalOnly(true).setTimeoutAfter(duration)
            .addExtras(Bundle().apply {putBoolean("android.requestPromotedOngoing",true)})
        // Success has one compact native carrier. The legacy high-importance/big-text
        // carrier is reserved for failures, so it cannot precede the custom result card.
        if(compactResult)builder.setPriority(Notification.PRIORITY_LOW)
        else builder.setStyle(Notification.BigTextStyle().bigText(subtitle))
        if(Build.VERSION.SDK_INT>=36)builder.setShortCriticalText(critical)
        if(loading)builder.setProgress(0,0,true)
        if(focusProtocol>=3)builder.addExtras(XiaomiRecognitionIsland.extras(context,loading,title,subtitle,critical,duration,expanded=summary==null && !loading))
        val notification=builder.build()
        lastPromotable=Build.VERSION.SDK_INT>=36 && notification.hasPromotableCharacteristics()
        manager.cancel(if(loading)ID else PROGRESS_ID)
        manager.notify(if(loading)PROGRESS_ID else ID,notification)
        failure="";true
    }.getOrElse {failure=it.javaClass.simpleName;false}

    private fun canPromote()=Build.VERSION.SDK_INT>=36 && manager.canPostPromotedNotifications()
    fun promoted():Boolean=Build.VERSION.SDK_INT>=36 && manager.activeNotifications.any {
        it.id in NOTIFICATION_IDS && it.notification.flags and Notification.FLAG_PROMOTED_ONGOING!=0
    }
    fun diagnostics()=JSONObject().put("supported",Build.VERSION.SDK_INT>=36 || focusProtocol>=3)
        .put("notificationsEnabled",manager.areNotificationsEnabled()).put("canPromote",canPromote())
        .put("promoted",promoted()).put("xiaomiFocusProtocol",focusProtocol).put("xiaomiFocusPermission",focusAllowed)
        .put("channel",lastChannel).put("channelImportance",lastImportance).put("failure",failure)
        .put("hasPromotableCharacteristics",lastPromotable)
        .put("nativePath",if(focusAllowed)"xiaomi_focus" else if(Build.VERSION.SDK_INT>=36)"android_live_update" else "none")
    fun cancel() {NOTIFICATION_IDS.forEach(manager::cancel)}

    companion object {
        private const val CHANNEL="recognition_result"
        private const val COMPACT_RESULT_CHANNEL="recognition_result_compact"
        private const val PROGRESS_CHANNEL="recognition_progress"
        internal const val RESULT_SUMMARY_DURATION=1_000L
        private const val ID=4701
        private const val PROGRESS_ID=4700
        private val NOTIFICATION_IDS=setOf(PROGRESS_ID,ID)
        /** Retain existing channel preferences; success retires the old floating carrier. */
        internal fun channelFor(context:Context,loading:Boolean,compactResult:Boolean=false):NotificationChannel {
            val manager=context.getSystemService(NotificationManager::class.java)
            val id=if(loading)PROGRESS_CHANNEL else if(compactResult)COMPACT_RESULT_CHANNEL else CHANNEL
            // Never rewrite an existing channel: importance belongs to the user once created.
            manager.getNotificationChannel(id)?.let {return it}
            manager.createNotificationChannel(NotificationChannel(id,if(loading)"账单识别进度" else if(compactResult)"账单结果摘要" else "账单实时动态",
                if(loading || compactResult)NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH).apply {
                description=if(loading)"安静展示识别进度" else if(compactResult)"安静展示简短结果，再打开记账卡片" else "识别结束提示"
                setSound(null,null);enableVibration(false);setShowBadge(false)
            })
            return manager.getNotificationChannel(id)
        }
    }
}
