package net.ankio.ledger.capture

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.content.res.ColorStateList
import net.ankio.ledger.core.Money
import net.ankio.ledger.data.LedgerAccount
import net.ankio.ledger.data.AccountType
import net.ankio.ledger.ui.MintScrollFrame

/** Account choices expand inline in the successful result's one window. */
internal class ResultAccountPanel(private val context:Context) {
    private var root:View?=null
    private var menu:View?=null
    private var onClose:(()->Unit)?=null
    val showing get()=menu!=null
    fun contains(view:View)=root===view

    fun show(accounts:List<LedgerAccount>,selected:String?,bar:LinearLayout,choose:(String?)->String?,closed:()->Unit):Boolean {
        close(false)
        val colors=ResultColors(context)
        val target=bar.findViewWithTag<LinearLayout>("account-menu-host") ?: return false
        if(android.animation.ValueAnimator.areAnimatorsEnabled())android.transition.TransitionManager.beginDelayedTransition(bar.parent as android.view.ViewGroup,android.transition.AutoTransition().apply {duration=200})
        // Inline list remains inside the same card and owns focus while open.
        val options=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;background=colors.background(colors.surface,18)}
        fun option(uid:String?,name:String,detail:String) {
            val chosen=uid==selected
            val button=LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;isSelected=chosen
                minimumHeight=colors.dp(40)
                isFocusable=true;isClickable=true
                background=colors.ripple(if(chosen)android.graphics.drawable.InsetDrawable(colors.background(if(colors.dark)0xff36594f.toInt() else 0xffe4f6ef.toInt(),12),0,colors.dp(2),0,colors.dp(2)) else null)
                setPadding(colors.dp(6),0,colors.dp(6),0)
                contentDescription="$name，$detail"+if(chosen)"，当前账户" else ""
                tag="account-option:$name"
                setOnClickListener {
                    if(root!==bar)return@setOnClickListener
                    val problem=choose(uid)
                    if(problem==null)close() else {
                        announceForAccessibility(problem)
                    }
                }
            }
            button.addView(colors.accountGlyph(accounts.firstOrNull {it.uid==uid}?.kind?:"custom"),LinearLayout.LayoutParams(colors.dp(18),colors.dp(18)).apply {rightMargin=colors.dp(6)})
            val labels=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
            labels.addView(colors.text(name,13,chosen).apply {includeFontPadding=false;setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.MIDDLE;tag="account-name:$name"})
            button.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            button.addView(View(context).apply {background=if(chosen)colors.symbol("check") else null;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},
                LinearLayout.LayoutParams(colors.dp(16),colors.dp(16)).apply {leftMargin=colors.dp(8)})
            options.addView(button,LinearLayout.LayoutParams(-1,-2))
        }
        accounts.forEach { account ->
            val balanceLabel=if(account.type==AccountType.LIABILITY)"待还" else "余额"
            option(account.uid,account.name,"$balanceLabel ¥${Money.display(account.amountCents)}")
        }
        option(null,"不关联账户","不调整账户余额")
        if(accounts.isEmpty())options.addView(colors.text("还没有账户，可在花有谱的资产页添加",14).apply {setTextColor(colors.secondary)})
        val list=MintScrollFrame(context).apply {viewport.addView(options);viewport.tag="account-menu-scroll"}
        val maxHeight=(context.resources.displayMetrics.heightPixels-colors.dp(360)).coerceAtLeast(colors.dp(80)).coerceAtMost(colors.dp(124))
        options.measure(View.MeasureSpec.makeMeasureSpec((bar.width-bar.paddingLeft-bar.paddingRight).coerceAtLeast(colors.dp(180)),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        target.addView(list,LinearLayout.LayoutParams(-1,options.measuredHeight.coerceAtMost(maxHeight)))
        root=bar;menu=list;onClose=closed
        return true
    }
    fun close(notify:Boolean=true) {
        val callback=onClose;onClose=null
        if(notify && android.animation.ValueAnimator.areAnimatorsEnabled())(root?.parent as? android.view.ViewGroup)?.let {android.transition.TransitionManager.beginDelayedTransition(it,android.transition.AutoTransition().apply {duration=200})}
        menu?.let {(it.parent as? android.view.ViewGroup)?.removeView(it)};menu=null;root=null
        if(notify)callback?.invoke()
    }
}

/** Mint result palette, with a quiet dark scheme for night use. */
internal class ResultColors(private val context:Context) {
    val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    val surface=if(dark)0xff202522.toInt() else Color.WHITE
    val ink=if(dark)0xfff5f7f5.toInt() else 0xff18222b.toInt()
    val secondary=if(dark)0xffb5c0bb.toInt() else 0xff63717a.toInt()
    val primary=if(dark)0xff57d7b0.toInt() else 0xff36cda3.toInt()
    val mint=primary
    val control=if(dark)0xff38524b.toInt() else 0xffdcefea.toInt()
    val error=if(dark)0xffffb4ab.toInt() else 0xffa82424.toInt()
    fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun background(color:Int,radius:Int)=GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat()}
    fun text(value:String,size:Int,bold:Boolean=false)=TextView(context).apply {
        text=value;textSize=size.toFloat();setTextColor(ink)
        if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    fun ripple(content:Drawable?=null)=android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(if(dark)0x33ffffff else 0x16000000),content,background(Color.WHITE,10))
    fun action(label:String)=Button(context).apply {
        text=label;textSize=15f;isAllCaps=false;minHeight=dp(48);minimumHeight=dp(48);minWidth=dp(48);minimumWidth=dp(48)
        includeFontPadding=false;setPadding(dp(8),dp(6),dp(8),dp(6));setTextColor(primary);stateListAnimator=null
        background=ripple()
    }
    fun reimbursementSwitch(checked:Boolean)=Switch(context).apply {
        text="";textSize=13f;setTextColor(ink);contentDescription="标记为待报销";tag="result-reimbursement"
        minimumHeight=dp(48);minHeight=dp(48);minimumWidth=dp(48);minWidth=dp(48);switchMinWidth=dp(40);switchPadding=dp(8)
        gravity=Gravity.BOTTOM;setPadding(0,0,0,dp(8));showText=false;splitTrack=false
        // The native switch owns hit testing and checked semantics; draw the whole track/knob
        // together so the off thumb never bleeds outside the track on OEM Switch variants.
        thumbDrawable=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        trackDrawable=object:Drawable() {
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(c:Canvas) {val b=android.graphics.RectF(bounds);p.color=if(isChecked)primary else control;c.drawRoundRect(b,b.height()/2,b.height()/2,p);p.color=Color.WHITE;val r=(b.height()-dp(4))/2;val x=if(isChecked)b.right-r-dp(2) else b.left+r+dp(2);c.drawCircle(x,b.centerY(),r,p)}
            override fun getIntrinsicWidth()=dp(40);override fun getIntrinsicHeight()=dp(24)
            override fun setAlpha(a:Int){p.alpha=a};override fun setColorFilter(f:android.graphics.ColorFilter?){p.colorFilter=f};@Deprecated("Deprecated")override fun getOpacity()=PixelFormat.TRANSLUCENT
        };trackTintList=null;thumbTintList=null;isChecked=checked
    }
    fun accountGlyph(kind:String):View = View(context).apply {background=pictogram(kind);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun pictogram(kind:String):Drawable {
        // The same unmodified official source images are shared with the assets page.
        if(kind=="wechat" || kind=="alipay") {
            val asset=if(kind=="alipay")"alipay-square.png" else "$kind.png"
            context.assets.open("web/payment-brands/$asset").use {android.graphics.drawable.Drawable.createFromStream(it,kind)}?.let {return it}
        }
        net.ankio.ledger.data.AccountBanks.entries.firstOrNull {it.kind==kind}?.let {bank ->
            runCatching {context.assets.open("web/${bank.icon}").use {android.graphics.drawable.Drawable.createFromStream(it,bank.icon)}}.getOrNull()?.let {return it}
        }
        return object:Drawable() {
        val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=primary;style=Paint.Style.STROKE;strokeWidth=2f;strokeJoin=Paint.Join.ROUND}
        override fun draw(c:Canvas) {c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat())
            if(kind=="receipt") {val scale=minOf(bounds.width(),bounds.height())/32f;c.translate((bounds.width()-32*scale)/2,(bounds.height()-32*scale)/2);c.scale(scale,scale)}
            else c.scale(bounds.width()/32f,bounds.height()/32f)
            if(kind=="receipt") {
                pen.strokeWidth=2.1f;pen.strokeCap=Paint.Cap.ROUND
                pen.shader=android.graphics.LinearGradient(0f,3f,0f,28f,
                    if(dark)intArrayOf(0xffd3e0d9.toInt(),0xff829b90.toInt()) else intArrayOf(0xff34434c.toInt(),0xffdce2e4.toInt()),null,android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(3f,2.5f,23.5f,28f,2.7f,2.7f,pen)
                val wave=android.graphics.Path().apply {moveTo(8.3f,9.2f);cubicTo(10f,11.2f,11.2f,11.2f,12.8f,9.6f);cubicTo(14.2f,11.1f,15.5f,11.1f,17.2f,9.2f)}
                pen.shader=null;pen.color=if(dark)0xffc0d0c7.toInt() else 0xff495b62.toInt();c.drawPath(wave,pen)
                pen.color=if(dark)0xffa6bbb0.toInt() else 0xff9aaeb0.toInt();c.drawLine(8.3f,16.2f,16.5f,16.2f,pen)
                val seal=android.graphics.Path().apply {
                    moveTo(23.5f,17f);cubicTo(25.9f,16.8f,25.8f,18.7f,27.6f,19.5f)
                    cubicTo(29.9f,20.5f,31f,21.1f,30.1f,23.5f);cubicTo(31f,26f,30.6f,26.9f,28.5f,28.3f)
                    cubicTo(27.3f,30.8f,26.7f,31.4f,24.2f,30.7f);cubicTo(21.7f,31.4f,20.7f,30.9f,19.5f,28.5f)
                    cubicTo(17.1f,27.3f,16.8f,26.3f,17.5f,23.9f);cubicTo(16.9f,21.5f,17.6f,20.5f,19.9f,19.4f)
                    cubicTo(20.8f,17.2f,21.3f,16.6f,23.5f,17f);close()
                }
                pen.style=Paint.Style.FILL;pen.shader=android.graphics.LinearGradient(18f,17f,30f,31f,intArrayOf(0xff55d6b6.toInt(),0xff32c99f.toInt()),null,android.graphics.Shader.TileMode.CLAMP);c.drawPath(seal,pen)
                val star=android.graphics.Path().apply {for(i in 0 until 10) {val angle=-Math.PI/2+i*Math.PI/5;val radius=if(i%2==0)4.8 else 2.05;val x=(23.8+Math.cos(angle)*radius).toFloat();val y=(24.1+Math.sin(angle)*radius).toFloat();if(i==0)moveTo(x,y) else lineTo(x,y)};close()}
                pen.shader=null;pen.color=Color.WHITE;c.drawPath(star,pen);pen.style=Paint.Style.STROKE
            }
            else if(kind=="merchant") {pen.color=secondary;c.drawRoundRect(5f,12f,27f,28f,2f,2f,pen);c.drawLine(4f,12f,8f,5f,pen);c.drawLine(8f,5f,24f,5f,pen);c.drawLine(24f,5f,28f,12f,pen);c.drawRect(13f,19f,19f,28f,pen)}
            else {c.drawRoundRect(3f,6f,29f,26f,5f,5f,pen);c.drawLine(3f,13f,29f,13f,pen);c.drawLine(8f,21f,16f,21f,pen)};c.restore()}
        override fun setAlpha(a:Int){pen.alpha=a};override fun setColorFilter(f:android.graphics.ColorFilter?){pen.colorFilter=f};@Deprecated("Deprecated")override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    }
    fun symbol(kind:String):Drawable=object:Drawable() {
        private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(kind=="check")primary else secondary;style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        override fun draw(canvas:Canvas) {
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/20f,bounds.height()/20f)
            when(kind) {
                "check"->{canvas.drawLine(4f,10f,8f,14f,pen);canvas.drawLine(8f,14f,16f,6f,pen)}
                else->{canvas.drawLine(5f,8f,10f,13f,pen);canvas.drawLine(10f,13f,15f,8f,pen)}
            };canvas.restore()
        }
        override fun setAlpha(alpha:Int){pen.alpha=alpha}
        override fun setColorFilter(filter:android.graphics.ColorFilter?){pen.colorFilter=filter}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth()=dp(16)
        override fun getIntrinsicHeight()=dp(16)
    }
}
