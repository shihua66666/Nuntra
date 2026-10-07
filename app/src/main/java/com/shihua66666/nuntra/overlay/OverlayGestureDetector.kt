package com.shihua66666.nuntra.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** 手势回调。全部在 UI 线程触发。 */
interface OverlayGestureCallbacks {
    /** 单击：由 Compose 的 detectTapGestures 上报（见 ui/overlay/OverlayRoot.kt）。 */
    fun onSingleTap()

    /** 双击：同上。 */
    fun onDoubleTap()

    /** 长按：同上。 */
    fun onLongPress()

    fun onDragStart()

    /** 累积位移（px），由控制器换算成窗口坐标。 */
    fun onDrag(dx: Int, dy: Int)

    fun onDragEnd()
}

/**
 * 悬浮窗的**拖动**手势检测。
 *
 * ★ 职责边界（这是修「浮窗完全没反应」时重新划定的）：
 *
 *   · **拖动 → 本类负责**。窗口是被 addView 的 View，只有它能移动自己；
 *     而且拖动必须能在 Compose 子视图之上「抢」到手势（靠拦截器的越阈值拦截）。
 *
 *   · **单击 / 双击 / 长按 → 由 Compose 负责**（OverlayRoot 根容器的 detectTapGestures）。
 *
 * 为什么点击不在这里做：
 *   1. Compose 子视图会消费点击事件，父容器的 OnTouchListener 根本收不到；
 *   2. 即便收到，也会与 Compose 的手势**重复触发**（单击一次却切换两次状态）。
 *   因此点击只保留 Compose 一条路径，这里只处理拖动 —— 单一职责，不会有歧义。
 *
 * 拖动阈值 10dp 由 [OverlayTouchInterceptor] 判定；越阈值时调用 [beginGesture]
 * 同步基准点，所以第一次 [dragBy] 的位移很小，窗口不会「跳一下」。
 */
class OverlayGestureDetector(
    context: Context,
    private val callbacks: OverlayGestureCallbacks,
) : View.OnTouchListener {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var lastX = 0f
    private var lastY = 0f

    /** 本轮手势是否已进入拖动。 */
    private var dragging = false

    /**
     * 开始一次拖动，并把当前指针位置作为增量基准。
     *
     * 由 [OverlayTouchInterceptor.onInterceptTouchEvent] 在越过阈值时调用。
     */
    fun beginGesture(rawX: Float, rawY: Float) {
        lastX = rawX
        lastY = rawY
        if (!dragging) {
            dragging = true
            callbacks.onDragStart()
        }
    }

    /** 拖动增量。由拦截器在抢到手势后的每一个 MOVE 调用。 */
    fun dragBy(dx: Int, dy: Int) {
        if (dragging) callbacks.onDrag(dx, dy)
    }

    /** 结束拖动。 */
    fun endGesture() {
        if (dragging) {
            dragging = false
            callbacks.onDragEnd()
        }
    }

    /** 当前是否处于拖动中（供调试与自检）。 */
    fun isDragging(): Boolean = dragging

    /** 触摸阈值（px）：拦截器用它判断是否该进入拖动。 */
    fun touchSlopPx(): Int = touchSlop

    /**
     * 作为 [View.OnTouchListener] 的入口。
     *
     * 只有拦截器把事件抢过来之后才会收到 MOVE/UP，
     * 因此这里无需重复做阈值判断，直接按增量移动窗口即可。
     */
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    dragBy((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
                    lastX = event.rawX
                    lastY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endGesture()
                return true
            }
        }
        return true
    }

    /** 释放：服务销毁时调用。 */
    fun release() {
        dragging = false
    }

    companion object {
        /** 拖动阈值（dp）：需求明确 10dp。 */
        const val DRAG_THRESHOLD_DP = 10

        /** 阈值换算成 px（供自检与说明文案使用）。 */
        fun dragThresholdPx(context: Context): Int =
            (DRAG_THRESHOLD_DP * context.resources.displayMetrics.density).toInt()
    }
}
