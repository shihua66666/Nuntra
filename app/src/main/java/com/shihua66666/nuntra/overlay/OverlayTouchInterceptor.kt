package com.shihua66666.nuntra.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.shihua66666.nuntra.core.NtToast

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

    /**
     * 「禁止拦截拖动」区域（**窗口本地坐标**）。
     *
     * ★★ 这是修「标签栏只能点、不能横向滑」的关键 ★★
     *
     *   顶部标签栏用 horizontalScroll 实现横向滚动，但本拦截器原来只要
     *   |dx| 超过 touchSlop 就抢手势 —— 横向滑动必然超过阈值，
     *   于是事件被抢走变成「拖动窗口」，标签栏永远滚不动。
     *
     *   现在由 Compose 侧（TagFilterBar 通过 onGloballyPositioned）把自己的
     *   矩形登记到这里；手势**起点**落在该区域内时，本拦截器一律不抢，
     *   事件完整交给 horizontalScroll。两个手势因此互斥：
     *     · 起点在标签栏内 → 只滚动标签；
     *     · 起点在别处     → 只拖动窗口。
     *
     *   坐标口径：用 ev.x / ev.y（相对本根 View，即窗口左上角），
     *   与 Compose 的 boundsInRoot() 完全同一口径，不需要再换算屏幕坐标。
     */
    @Volatile
    var noDragBounds: android.graphics.Rect? = null

    /** 本轮手势的起点是否落在禁止拦截区（ACTION_DOWN 时确定，整轮沿用）。 */
    private var downInNoDragRegion = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                // 用「按下点」判定，而不是每一帧的当前位置 ——
                // 手势一旦从标签栏内开始，整轮都不该被抢走。
                // 注意用 ev.x/ev.y（窗口本地坐标）与 noDragBounds 对齐。
                downInNoDragRegion = noDragBounds?.contains(
                    ev.x.toInt(),
                    ev.y.toInt(),
                ) == true
                // ★ 调试：这条 Toast 证明「触摸事件真的到达了浮窗」。
                //   若手指按下却看不到它，说明窗口本身不可触摸（flags/权限问题）；
                //   看到了它却点不动，说明是手势层的问题。验证完成后置 DEBUG_TOUCH=false 即可。
                if (DEBUG_TOUCH) NtToast.show(context, "[浮窗] 触摸到达")
                // ★ 返回 false：把 DOWN 交给 Compose，点击/双击/长按才能生效。
                //   返回 true 会把子 View 的事件全部吃掉，浮窗内部就「点不动」了。
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                // ★ 多指（>=2）时不抢手势：那是双指捏合缩放，
                //   必须完整交给 Compose 的 detectTransformGestures 处理。
                //   若这里抢走，捏合就会变成「窗口跟着第一根手指乱跑」。
                if (ev.pointerCount > 1) {
                    dragging = false
                    return false
                }
                // ★ 手势起点在标签栏（或其他登记为禁区的横向滚动区）内：
                //   绝不抢 —— 让 horizontalScroll 完整消费这次滑动。
                //   这就是「滑动标签」与「拖动窗口」互斥的实现点。
                if (downInNoDragRegion) {
                    dragging = false
                    return false
                }
                if (!dragging) {
                    val dx = kotlin.math.abs(ev.rawX - downX)
                    val dy = kotlin.math.abs(ev.rawY - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        dragging = true
                        gesture?.beginGesture(ev.rawX, ev.rawY)
                        if (DEBUG_TOUCH) NtToast.show(context, "[浮窗] 开始拖动")
                        return true // 越阈值才抢过手势用于拖动
                    }
                }
                // ★★ 未越阈值一律返回 false ★★
                //    原实现这里写的是 `return dragging`：虽然此刻 dragging 为 false，
                //    语义上却把「已开始拖动」的状态也带进了返回值，容易误改；
                //    显式 false 更明确 —— 阈值内绝不吃掉点击。
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 未抢过手势时把 UP 交还子 View（Compose 需要它来完成点击）
                val was = dragging
                dragging = false
                downInNoDragRegion = false
                return was
            }
        }
        return false
    }

    /**
     * ★★ 拖动能否「跟手」的关键 ★★
     *
     * 一旦 [onInterceptTouchEvent] 返回 true 抢过手势，本 View 就成为触摸目标，
     * 后续 MOVE/UP **不再经过 onInterceptTouchEvent**，而是走这里。
     *
     * 之前没有这个方法，导致拖动只在「越阈值那一帧」响应一次，之后窗口纹丝不动 ——
     * 现象就是「浮窗拖不动」。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 拖动过程中若第二根手指落下，说明用户要做捏合：立刻结束拖动并放行，
        // 避免「一边拖一边被当成缩放」。
        if (event.pointerCount > 1) {
            if (dragging) {
                dragging = false
                gesture?.endGesture()
            }
            return false
        }
        val detector = gesture
        if (detector != null) return detector.onTouch(this, event)
        return super.onTouchEvent(event)
    }

    /** 手指离开时复位，保证下一次手势从干净状态开始。 */
    fun resetGesture() {
        dragging = false
    }
    private companion object {
        /**
         * 调试开关：为 true 时每次「按下 / 开始拖动」都弹 Toast。
         *
         * 存在的意义是区分两类「没反应」：
         *  · 按下无 Toast → 触摸根本没到浮窗（窗口 flags / 权限 / 窗口不可触摸）；
         *  · 有 Toast 但不响应 → 触摸到了，问题在手势层。
         * 触摸验证通过后改为 false 即可，避免刷屏。
         */
        const val DEBUG_TOUCH = true
    }

    // ── OverlayComposeHostAccessor ───────────────────────────────
    //
    // WindowManager 挂载的是本容器（它才是触摸的最外层），而控制器回调的
    // onBeforeAdd / onAfterAdd / onAfterRemove 也落在本容器上；
    // 但真正持有 owner（OverlayLifecycleProvider）的是 OverlayComposeHost。
    //
    // ★★ 这里曾经写成 `(getChildAt(0) as? OverlayComposeHostAccessor)`，是个致命错误：
    //    子 View 是 ComposeView，而 ComposeView **并不实现该接口**，
    //    转型恒为 null，安全调用直接跳过 —— 生命周期回调被**静默丢弃**。
    //    后果：owner.onCreate() 从未执行 → SavedStateRegistry.performAttach 从未调用 →
    //    owner 永远停在 INITIALIZED → Compose 拿不到可用生命周期，挂载后异步崩溃。
    //
    // 现在改为显式持有的 relay，由 OverlayService 在创建后立即赋值。
    // 保留 getChildAt 兜底：万一将来子 View 也实现了该接口，行为不变。
    // ─────────────────────────────────────────────────────────────

    /** 生命周期透传目标（由 OverlayService 在创建后赋值为 OverlayComposeHost）。 */
    var lifecycleRelay: OverlayComposeHostAccessor? = null

    private fun relay(): OverlayComposeHostAccessor? =
        lifecycleRelay ?: (getChildAt(0) as? OverlayComposeHostAccessor)

    override fun onBeforeAdd() {
        val target = relay()
        if (target == null) {
            android.util.Log.e("OverlayTouchInterceptor", "lifecycleRelay 为空，onBeforeAdd 未转发")
        }
        target?.onBeforeAdd()
    }

    override fun onAfterAdd() {
        relay()?.onAfterAdd()
    }

    override fun onAfterRemove() {
        relay()?.onAfterRemove()
    }
}
