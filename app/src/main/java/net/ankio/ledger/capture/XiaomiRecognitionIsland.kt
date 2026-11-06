package net.ankio.ledger.capture

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import net.ankio.ledger.R
import org.json.JSONObject

/**
 * Xiaomi compact island progress/failure components; system owns presentation.
 * https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
 * Template library 2026-01-29: pp.17,47-49,70-71,76-77,95-96,104-105,110.
 * Service entitlement and user permission still apply.
 */
internal object XiaomiRecognitionIsland {
    private const val PICTURE="miui.focus.pic_ledger"
    fun extras(context:Context,loading:Boolean,title:String,text:String,critical:String,
               durationMillis:Long,expanded:Boolean=!loading):Bundle {
        // Compact island picInfo.type 1 is a static picture key (not expanded picInfo types).
        fun picture()=JSONObject().put("type",1).put("pic",PICTURE)
        val island=JSONObject().put("islandProperty",1)
            .put("islandTimeout",(durationMillis/1_000).toInt())
            .put("bigIslandArea",JSONObject()
                .put("imageTextInfoLeft",JSONObject().put("type",1).put("picInfo",picture()))
                .put("textInfo",JSONObject().put("title",critical).put("showHighlightColor",false)))
            .put("smallIslandArea",JSONObject().put("picInfo",picture()))
        val params=JSONObject().put("protocol",1).put("business","accounting")
            .put("islandFirstFloat",expanded).put("enableFloat",expanded)
            .put("updatable",true).put("reopen","reopen").put("filterWhenNoPermission",false)
            .put("ticker",critical).put("tickerPic",PICTURE).put("aodTitle",critical)
            .put("param_island",island)
            .put("iconTextInfo",JSONObject().put("title",title).put("content",text)
                .put("animIconInfo",JSONObject().put("type",0).put("src",PICTURE)))
        return Bundle().apply {
            putString("miui.focus.param",JSONObject().put("param_v2",params).toString())
            // Pass the full background app icon. No mask, circular crop or redraw.
            putBundle("miui.focus.pics",Bundle().apply {
                putParcelable(PICTURE,Icon.createWithResource(context,R.drawable.huayoupu_notification_icon))
            })
        }
    }
}
