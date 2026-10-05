package com.shihua66666.nuntra.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * 悬浮窗的触摸拦截容器。
 *
 * 为什么需要它（而不是直接在 ComposeView 上挂 OnTouchListener）：
 *  · 如果 OnTouchListener 对 ACTION_DOWN 返回 true，事件就被**吞掉**，
 *    子 View（Compose 里的按钮、列表）再也收不到触摸 —— 内部控件会点不动。
 *  · 正确做法是 ViewGroup.onInterceptTouchEvent 的「越阈值才抢」：
 *      - ACTION_DOWN 返回 false → 先让 Compose 正常处理（按钮可点、列表可滚）；
 *      - ACTION_MOVE 且位移超过 10dp → 返回 true，从此刻起抢过手势用于拖动窗口。
 *    这是 Android 里滚动容器与可点击子项共存的成熟机制。
 *
 * 一旦开始抢手势，[OverlayGestureDetector.beginGesture] 会同步基准点，
 * 且整个手势期间持续返回 true，直到 UP/CANCEL 才复位。
 */
class OverlayTouchInterceptor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), OverlayComposeHostAccessor {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f

    /** 本轮手势是否已进入拖动。 */
    private var dragging = false

    var gesture: OverlayGestureDetector? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                return false // 让 Compose 先拿到事件
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = kotlin.math.abs(ev.rawX - downX)
                    val dy = kotlin.math.abs(ev.rawY - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        dragging = true
                        gesture?.beginGesture(ev.rawX, ev.rawY)
                        return true // 从这里开始抢过手势
                    }
                }
                return dragging
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                dragging = false
                return was
            }
        }
        return dragging
    }

    /** 手指离开时复位，保证下一次手势从干净状态开始。 */
    fun resetGesture() {
        dragging = false
    }

    // ── OverlayComposeHostAccessor ───────────────────────────────
    // WindowManager 挂载的是本容器（它才是 touch 的最外层），
    // 但生命周期归内部的 ComposeView，因此这里做一层透传。

    override fun onBeforeAdd() {
        (getChildAt(0) as? OverlayComposeHostAccessor)?.onBeforeAdd()
    }

    override fun onAfterAdd() {
        (getChildAt(0) as? OverlayComposeHostAccessor)?.onAfterAdd()
    }

    override fun onAfterRemove() {
        (getChildAt(0) as? OverlayComposeHostAccessor)?.onAfterRemove()
    }
}
