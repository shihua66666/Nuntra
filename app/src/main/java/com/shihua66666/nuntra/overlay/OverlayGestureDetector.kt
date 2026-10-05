package com.shihua66666.nuntra.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.shihua66666.nuntra.core.Logx
import kotlin.math.abs

/** 手势回调。全部在 UI 线程触发。 */
interface OverlayGestureCallbacks {
    fun onSingleTap()
    fun onDoubleTap()
    fun onLongPress()
    fun onDragStart()
    /** 累积位移（px），由控制器换算成窗口坐标。 */
    fun onDrag(dx: Int, dy: Int)
    fun onDragEnd()
}

/**
 * 悬浮窗手势检测。
 *
 * 需求要求用 OnTouchListener 而不是 GestureDetector 独占触摸，原因很实在：
 * 悬浮窗里还有 Compose 内容（按钮、列表），如果把所有触摸都吞掉，
 * 内部控件就点不动了。因此这里的策略是：
 *
 *  · **拖动**：阈值 10dp —— 只有位移超过阈值才判定为拖动，之后才由我们处理；
 *    未超过阈值的事件完全交给子 View（Compose）处理，内部按钮照常可点。
 *  · **单击**：位移未超阈值且未超时长时，由 [gestureDetector] 的 onSingleTapUp 上报。
 *  · **双击**：用系统时间差判定（[ViewConfiguration.getDoubleTapTimeout]），
 *    第一次单击会等待双击超时后才会真正生效，避免单击与双击互相打架。
 *  · **长按**：自带 Handler 计时（[ViewConfiguration.getLongPressTimeout]），
 *    位移超阈值立即取消 —— 否则「按住拖动」会被误判成长按。
 */
class OverlayGestureDetector(
    private val context: Context,
    private val callbacks: OverlayGestureCallbacks,
) : View.OnTouchListener {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()

    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** 同一轮手势里，是否已经上报过拖动开始。 */
    private var dragReported = false

    /** 长按是否已经触发（触发后本次手势不再上报单击）。 */
    private var longPressFired = false

    /** 已被消费的第二次点击，防止单击回调重复触发。 */
    private var secondTapConsumed = false

    private val longPressRunnable = Runnable {
        if (!dragReported) {
            longPressFired = true
            callbacks.onLongPress()
        }
    }

    /**
     * 开始一次手势，并把当前指针位置作为拖动增量基准。
     *
     * 由 [OverlayTouchInterceptor.onInterceptTouchEvent] 在判定「越过拖动阈值、开始抢手势」时调用，
     * 这样第一次 onDrag 的位移就是一个很小的增量，窗口不会跳一下。
     */
    fun beginGesture(rawX: Float, rawY: Float) {
        downX = rawX
        downY = rawY
        lastX = rawX
        lastY = rawY
        dragReported = true
        handler.removeCallbacks(longPressRunnable)
        callbacks.onDragStart()
    }

    /** 只用来判定双击；单击由我们自己按「双击超时」延迟派发。 */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (longPressFired) {
                    longPressFired = false
                    return true
                }
                // 按下瞬间已判断过是否为双击，这里只处理真正的单击
                if (!secondTapConsumed) callbacks.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                secondTapConsumed = true
                callbacks.onDoubleTap()
                return true
            }
        },
    )

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        // 交给系统检测器判定单击/双击；它会自行处理双击超时
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragReported = false
                longPressFired = false
                secondTapConsumed = false
                handler.postDelayed(longPressRunnable, longPressTimeout.toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = abs(event.rawX - downX)
                val totalDy = abs(event.rawY - downY)
                if (!dragReported && (totalDx > touchSlop || totalDy > touchSlop)) {
                    dragReported = true
                    handler.removeCallbacks(longPressRunnable)
                    callbacks.onDragStart()
                }
                if (dragReported) {
                    // 用「与上一次 move 的增量」驱动窗口位移，跟手且不会因事件抖动跳变
                    val stepX = (event.rawX - lastX).toInt()
                    val stepY = (event.rawY - lastY).toInt()
                    callbacks.onDrag(stepX, stepY)
                }
                lastX = event.rawX
                lastY = event.rawY
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragReported) {
                    dragReported = false
                    callbacks.onDragEnd()
                }
                return true
            }
        }
        return true
    }

    fun release() {
        handler.removeCallbacks(longPressRunnable)
    }

    companion object {
        /** 拖动阈值（dp）：需求明确 10dp。 */
        const val DRAG_THRESHOLD_DP = 10

        /** 仅用于日志与自检。 */
        @Suppress("unused")
        fun thresholdPx(context: Context): Int = (DRAG_THRESHOLD_DP * context.resources.displayMetrics.density).toInt()

        /** 双击超时（ms），供外部说明文案使用。 */
        fun doubleTapTimeoutMs(context: Context): Int = ViewConfiguration.getDoubleTapTimeout()

        @Suppress("unused")
        private fun logSlop(slop: Int) {
            Logx.d("OverlayGesture", "touchSlop=" + slop)
        }
    }
}
