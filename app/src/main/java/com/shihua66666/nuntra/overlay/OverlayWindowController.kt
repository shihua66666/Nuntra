package com.shihua66666.nuntra.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 拖动坐标的可移动范围（px）。 */
data class MoveBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 悬浮窗窗口控制器。
 *
 * 职责边界：只做「窗口」相关的事 —— LayoutParams 组装、add/remove、形态切换、
 * 拖动位移、边缘吸附、屏幕旋转后夹回屏幕内。**不渲染任何内容**（那是 Compose 层）。
 *
 * 为什么尺寸与内容分开：WindowManager 用的是 px 且要求精确，
 * Compose 用的是 dp 与自适应；把两者的换算集中在这里，避免散落各处。
 */
class OverlayWindowController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val positionStore: OverlayPositionStore,
    private val serviceScope: CoroutineScope,
) {

    private var hostView: View? = null

    private var params: WindowManager.LayoutParams? = null

    private var currentState: OverlayWindowState = OverlayWindowState.CAPSULE

    private var miniSize: OverlaySizePx? = null

    /** 当前窗口尺寸（px）。 */
    private var windowWidth: Int = 0
    private var windowHeight: Int = 0

    private var persistJob: Job? = null

    private var snapJob: Job? = null

    val isAttached: Boolean get() = hostView != null

    val state: OverlayWindowState get() = currentState

    fun sizePx(): OverlaySizePx = OverlaySizePx(windowWidth, windowHeight)

    // ── 挂载 / 卸载 ─────────────────────────────────────────────

    /**
     * 把宿主 View 挂到窗口。
     *
     * 顺序严格要求：建 View → onCreate → addView → onStart。
     * 反过来会在 Compose 首次组合时拿不到 lifecycle，进而崩溃或泄漏。
     */
    suspend fun attach(
        state: OverlayWindowState,
        viewFactory: (Int, Int) -> View,
    ): Boolean {
        if (hostView != null) {
            Logx.w(TAG, "attach 被重复调用，忽略")
            return true
        }
        currentState = state
        val size = resolveSize(state)
        windowWidth = size.width
        windowHeight = size.height

        val view = viewFactory(size.width, size.height)

        val (savedX, savedY) = positionStore.loadOrDefault(
            screenWidthPx = screenWidth(),
            screenHeightPx = screenHeight(),
            windowWidth = size.width,
            windowHeight = size.height,
        )

        val layoutParams = buildParams(state, size.width, size.height).apply {
            x = savedX
            y = savedY
        }

        // 宿主 View 必须实现 OverlayComposeHostAccessor，否则生命周期无法推进；
        // 这里做一次显式校验，失败就明确报错而不是静默泄漏。
        val host = view as? OverlayComposeHostAccessor
        if (host == null) {
            Logx.e(TAG, "viewFactory 返回的 View 未实现 OverlayComposeHostAccessor，拒绝挂载")
            return false
        }
        host.onBeforeAdd()

        return runCatching {
            windowManager.addView(view, layoutParams)
        }.map {
            hostView = view
            params = layoutParams
            host.onAfterAdd()
            Logx.i(TAG, "窗口已挂载：" + state.name + " " + size.width + "x" + size.height)
            true
        }.getOrElse { tr ->
            // BadTokenException / 权限被撤销等都会走到这里；不允许崩。
            Logx.swallow(TAG, "addView", tr)
            host.onAfterRemove()
            false
        }
    }

    /**
     * 从窗口移除。
     *
     * 顺序严格要求：removeView → onDestroy。
     * 先 destroy 的话，正在 detach 的组合会看到 DESTROYED 状态。
     */
    fun detach() {
        val view = hostView ?: return
        runCatching {
            persistNow()
            windowManager.removeView(view)
        }.onFailure { Logx.swallow(TAG, "removeView", it) }

        (view as? OverlayComposeHostAccessor)?.onAfterRemove()
        hostView = null
        params = null
        Logx.i(TAG, "窗口已移除")
    }

    // ── 形态切换 ─────────────────────────────────────────────────

    /**
     * 切换形态：更新尺寸并保持「锚点边」不变。
     *
     * 锚点边逻辑：从右侧拖到右边的胶囊展开成面板时，用户期望面板仍然贴着右边，
     * 而不是突然跑到左边。因此横向位置按「原本离哪边近就贴哪边」重算。
     */
    fun applyState(newState: OverlayWindowState) {
        val view = hostView ?: return
        val layoutParams = params ?: return
        val size = resolveSize(newState)
        val screenW = screenWidth()

        val oldW = windowWidth
        val oldX = layoutParams.x
        val anchorRight = oldX + oldW / 2 > screenW / 2

        val newX = if (anchorRight) {
            (screenW - size.width - MARGIN_PX).coerceAtLeast(MARGIN_PX)
        } else {
            MARGIN_PX
        }

        windowWidth = size.width
        windowHeight = size.height
        currentState = newState
        layoutParams.width = size.width
        layoutParams.height = size.height
        layoutParams.flags = flagsFor(newState)
        layoutParams.x = newX
        val bounds = moveBounds()
        layoutParams.y = layoutParams.y.coerceIn(bounds.top, bounds.bottom)

        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Logx.swallow(TAG, "updateViewLayout", it) }

        schedulePersist()
    }

    /** 小窗尺寸变化（用户拖拽小窗边缘时调用；本步先由控制器内部记录）。 */
    fun resizeMini(size: OverlaySizePx) {
        if (currentState != OverlayWindowState.MINI) {
            miniSize = size
            return
        }
        miniSize = size
        val view = hostView ?: return
        val layoutParams = params ?: return
        windowWidth = size.width
        windowHeight = size.height
        layoutParams.width = size.width
        layoutParams.height = size.height
        val bounds = moveBounds()
        layoutParams.x = layoutParams.x.coerceIn(bounds.left, bounds.right)
        layoutParams.y = layoutParams.y.coerceIn(bounds.top, bounds.bottom)
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Logx.swallow(TAG, "updateViewLayout-resize", it) }
        serviceScope.launch { positionStore.saveMiniSize(size) }
    }

    // ── 拖动 ─────────────────────────────────────────────────────

    /**
     * 位移窗口。
     *
     * 拖动过程中**不做边缘吸附**（粘手），只在抬手后吸附 —— 这样手感才跟手。
     */
    fun moveBy(dx: Int, dy: Int) {
        val view = hostView ?: return
        val layoutParams = params ?: return
        val bounds = moveBounds()
        layoutParams.x = (layoutParams.x + dx).coerceIn(bounds.left, bounds.right)
        layoutParams.y = (layoutParams.y + dy).coerceIn(bounds.top, bounds.bottom)
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Logx.swallow(TAG, "updateViewLayout-drag", it) }
    }

    /**
     * 拖动时的透明度反馈（需求：拖动时降低透明度给反馈）。
     *
     * 只改 window alpha，不动 Compose 的 alpha —— 后者会连同内容一起变淡，
     * 反而看不清楚。
     */
    fun setDraggingAlpha(dragging: Boolean) {
        val view = hostView ?: return
        val layoutParams = params ?: return
        layoutParams.alpha = if (dragging) DRAGGING_ALPHA else 1f
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Logx.swallow(TAG, "setDraggingAlpha", it) }
    }

    /** 抬手：吸附到最近的左/右边缘，并持久化位置。 */
    fun onDragFinished() {
        snapToEdge()
        schedulePersist(delayMs = 0L)
    }

    private fun snapToEdge() {
        val view = hostView ?: return
        val layoutParams = params ?: return
        val screenW = screenWidth()
        val targetX = if (layoutParams.x + windowWidth / 2 > screenW / 2) {
            (screenW - windowWidth - MARGIN_PX).coerceAtLeast(MARGIN_PX)
        } else {
            MARGIN_PX
        }
        val startX = layoutParams.x
        if (startX == targetX) return

        // 用协程做一次短促的线性吸附（约 160ms），避免突兀跳变
        snapJob?.cancel()
        snapJob = serviceScope.launch {
            val steps = SNAP_STEPS
            for (i in 1..steps) {
                val x = startX + ((targetX - startX) * i / steps)
                val lp = params ?: break
                lp.x = x
                runCatching { windowManager.updateViewLayout(view, lp) }
                    .onFailure { Logx.swallow(TAG, "snap", it); break }
                delay(SNAP_STEP_MS)
            }
            persistNow()
        }
    }

    // ── 配置变化 ─────────────────────────────────────────────────

    /**
     * 屏幕旋转 / 尺寸变化后重新夹回屏幕内。
     *
     * 不做这一步的典型症状：旋转后悬浮窗跑到屏幕外，用户以为「浮窗消失了」。
     */
    fun onConfigurationChanged() {
        val view = hostView ?: return
        val layoutParams = params ?: return
        val size = resolveSize(currentState)
        windowWidth = size.width
        windowHeight = size.height
        layoutParams.width = size.width
        layoutParams.height = size.height
        val bounds = moveBounds()
        layoutParams.x = layoutParams.x.coerceIn(bounds.left, bounds.right)
        layoutParams.y = layoutParams.y.coerceIn(bounds.top, bounds.bottom)
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Logx.swallow(TAG, "onConfigurationChanged", it) }
        schedulePersist()
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun resolveSize(state: OverlayWindowState): OverlaySizePx = OverlayWindowState.sizeFor(
        state = state,
        configuration = context.resources.configuration,
        density = context.resources.displayMetrics.density,
        miniOverride = miniSize,
    )

    private fun buildParams(
        state: OverlayWindowState,
        width: Int,
        height: Int,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flagsFor(state),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 轻微毛玻璃观感由 Compose 层的半透明底色实现（API 31+ 才可叠真实 blur）
    }

    /**
     * 窗口 flags。
     *
     * 折叠态要求 NOT_FOCUSABLE：否则胶囊会抢输入法焦点，
     * 而且某些系统下会导致状态栏/输入异常。
     * 展开态与拖动态都保持 NOT_FOCUSABLE —— 悬浮窗内目前没有文本输入需求，
     * 一旦将来需要输入，只需在展开态去掉这一个 flag。
     */
    private fun flagsFor(state: OverlayWindowState): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (state == OverlayWindowState.CAPSULE) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        return flags
    }

    private fun moveBounds(): MoveBounds {
        val screenW = screenWidth()
        val screenH = screenHeight()
        return MoveBounds(
            left = MARGIN_PX,
            top = MARGIN_PX + statusBarHeight(),
            right = (screenW - windowWidth - MARGIN_PX).coerceAtLeast(MARGIN_PX),
            bottom = (screenH - windowHeight - MARGIN_PX).coerceAtLeast(MARGIN_PX),
        )
    }

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    /** 状态栏高度：让悬浮窗不要压在状态栏下面。取不到时返回 0，不崩。 */
    private fun statusBarHeight(): Int = runCatching {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    private fun schedulePersist(delayMs: Long = PERSIST_DEBOUNCE_MS) {
        persistJob?.cancel()
        persistJob = serviceScope.launch {
            if (delayMs > 0) delay(delayMs)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        val layoutParams = params ?: return
        positionStore.save(layoutParams.x, layoutParams.y)
    }

    /** 释放全部资源。服务销毁时调用。 */
    fun release() {
        snapJob?.cancel()
        persistJob?.cancel()
        detach()
    }

    companion object {
        private const val TAG = "OverlayWindow"

        /** 屏幕边缘留白（px）。 */
        const val MARGIN_PX = 12

        /** 吸附动画步数与步长：约 160ms 完成，短促不拖沓。 */
        private const val SNAP_STEPS = 8
        private const val SNAP_STEP_MS = 20L

        /** 位置持久化防抖：拖动过程中不要每个 move 都写磁盘。 */
        private const val PERSIST_DEBOUNCE_MS = 600L

        /** 拖动时窗口透明度：给用户「正在被拖动」的反馈，但不要过于透明。 */
        private const val DRAGGING_ALPHA = 0.82f
    }
}

/**
 * 宿主 View 需要实现的生命周期钩子。
 *
 * 用接口而不是直接引用 OverlayComposeHost：控制器只关心「挂载前 / 挂载后 / 移除后」三个时机，
 * 具体 View 怎么实现（Compose 宿主 / 将来的其他实现）不应该被控制器知道。
 */
interface OverlayComposeHostAccessor {
    fun onBeforeAdd()
    fun onAfterAdd()
    fun onAfterRemove()
}
