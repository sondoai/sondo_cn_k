package com.sondo.music.video.ai.sdk.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.RelativeLayout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只当图片用的控件（头图、压在按钮上的图标）：不接收触摸，点击落到它下面的控件上，
 * 比如图标下面那个 SDK 自带的登录按钮。
 */
@SuppressLint("AppCompatCustomView", "ViewConstructor")
internal class PassThroughImageView(context: Context) : ImageView(context) {
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false
}

/**
 * 点“一键登录”后显示的 loading：一段单色圆弧匀速旋转。
 * 位置相对授权页左上角，交给 SDK 的 setLoadingView，显示和隐藏由 SDK 控制。
 */
internal object JVerifyLoadingView {

    fun create(context: Context, leftPx: Int, topPx: Int, sizePx: Int, color: Int): View {
        val density = context.resources.displayMetrics.density
        val view = ImageView(context)
        view.setImageDrawable(Arc(color, maxOf(1f, sizePx / 8f)))
        // 画在按钮和按钮图标（7dp）上面。
        view.elevation = 8f * density
        val params = RelativeLayout.LayoutParams(sizePx, sizePx)
        params.leftMargin = leftPx
        params.topMargin = topPx
        view.layoutParams = params
        return view
    }

    fun spin(): Animation {
        val animation = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        )
        animation.duration = 800
        animation.repeatCount = Animation.INFINITE
        animation.interpolator = LinearInterpolator()
        return animation
    }

    /** 四分之三圈的圆弧，圆头描边。 */
    private class Arc(color: Int, strokeWidth: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.strokeWidth = strokeWidth
            this.color = color
        }
        private val oval = RectF()

        override fun draw(canvas: Canvas) {
            val inset = paint.strokeWidth / 2f
            oval.set(bounds)
            oval.inset(inset, inset)
            canvas.drawArc(oval, 0f, 270f, false, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

/**
 * 授权页做成贴底弹窗后，SDK 自己不支持的两种关闭手势：点弹窗外的空白处，或按住弹窗顶部向下滑。
 * 这里只识别手势并通知 [onDismissRequested]，由调用方结束这次登录并关闭授权页。
 */
internal class JVerifySheetDismiss(private val onDismissRequested: () -> Unit) :
    Application.ActivityLifecycleCallbacks {

    private val registered = AtomicBoolean(false)

    @Volatile
    private var enabled = false

    /** 生命周期监听只注册一次，之后只切换开关。 */
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        if (!value) return
        val app = context.applicationContext as? Application ?: return
        if (registered.compareAndSet(false, true)) {
            app.registerActivityLifecycleCallbacks(this)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (!enabled || activity.javaClass.name !in AUTH_ACTIVITIES) return
        try {
            install(activity)
        } catch (_: Throwable) {
            // 手势只是锦上添花，装不上不影响授权页本身。
        }
    }

    /**
     * 弹窗窗口比屏幕小，落在窗口外的触摸也会交给它，坐标在根视图范围之外。
     * 根视图的触摸监听只收到子视图没处理的事件，按钮、勾选框、条款的点击不受影响。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun install(activity: Activity) {
        val decor = activity.window.decorView
        val density = activity.resources.displayMetrics.density
        val grabArea = GRAB_AREA_DP * density
        val dragDistance = DRAG_DISTANCE_DP * density

        decor.setOnTouchListener(object : View.OnTouchListener {
            private var outside = false
            private var grabbing = false
            private var fired = false
            private var downY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val x = event.x
                        val y = event.y
                        fired = false
                        outside = x < 0 || y < 0 || x > view.width || y > view.height
                        grabbing = !outside && y <= grabArea
                        downY = event.rawY
                        return outside || grabbing
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (grabbing && !fired && event.rawY - downY >= dragDistance) {
                            fired = true
                            onDismissRequested()
                        }
                        return outside || grabbing
                    }
                    MotionEvent.ACTION_UP -> {
                        val handled = outside || grabbing
                        if (outside && !fired) {
                            fired = true
                            onDismissRequested()
                        }
                        outside = false
                        grabbing = false
                        return handled
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        outside = false
                        grabbing = false
                        return false
                    }
                    else -> return false
                }
            }
        })
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        /** 运营商授权页：电信、联通用极光的页面，移动用自己的页面。 */
        private val AUTH_ACTIVITIES = setOf(
            "cn.jiguang.verifysdk.CtLoginActivity",
            "com.cmic.gen.sdk.view.GenLoginAuthActivity",
        )

        /** 弹窗顶部可以按住下滑的高度，盖住拖动条和它上下的留白。 */
        private const val GRAB_AREA_DP = 48f

        /** 下滑超过这个距离就关闭。 */
        private const val DRAG_DISTANCE_DP = 40f
    }
}
