package net.ankio.ledger.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ScrollView

/** Account scrolling UI owns its decoration and clips it inside the menu surface. */
internal class MintScrollFrame(context:Context):FrameLayout(context) {
    val viewport=ScrollView(context).apply {
        isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false
        overScrollMode=OVER_SCROLL_NEVER;isFillViewport=false
        setPadding(0,0,dp(10),0);clipToPadding=true
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    init {
        tag="mint-scroll-frame"
        background=GradientDrawable().apply {setColor(if(dark)0xff202522.toInt() else android.graphics.Color.WHITE);cornerRadius=dp(18).toFloat()}
        clipToOutline=true;clipChildren=true
        addView(viewport,LayoutParams(-1,-1))
        viewport.setOnScrollChangeListener {_,_,_,_,_->postInvalidateOnAnimation()}
        viewport.viewTreeObserver.addOnGlobalLayoutListener {postInvalidateOnAnimation()}
    }
    override fun dispatchDraw(canvas:Canvas) {
        super.dispatchDraw(canvas)
        val child=viewport.getChildAt(0)?:return
        val range=child.height-viewport.height
        if(range<=0)return
        val top=dp(5).toFloat();val trackHeight=(height-dp(10)).coerceAtLeast(1).toFloat()
        val thumb=(trackHeight*viewport.height/child.height).coerceAtLeast(dp(24).toFloat()).coerceAtMost(trackHeight)
        val y=top+(trackHeight-thumb)*viewport.scrollY.coerceIn(0,range)/range
        val right=width-dp(3).toFloat();val left=right-dp(4)
        paint.color=if(dark)0xff38524b.toInt() else 0xffe5f0eb.toInt()
        canvas.drawRoundRect(left,top,right,top+trackHeight,dp(4).toFloat(),dp(4).toFloat(),paint)
        paint.color=if(dark)0xff57d7b0.toInt() else 0xff64b69c.toInt()
        canvas.drawRoundRect(left,y,right,y+thumb,dp(4).toFloat(),dp(4).toFloat(),paint)
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
