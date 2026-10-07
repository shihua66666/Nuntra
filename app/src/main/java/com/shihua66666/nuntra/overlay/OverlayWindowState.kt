package com.shihua66666.nuntra.overlay

import android.content.res.Configuration
import com.shihua66666.nuntra.ui.theme.OverlayDimens

/** 悬浮窗尺寸（px）。用 px 而不是 dp：WindowManager.LayoutParams 用的是 px。 */
data class OverlaySizePx(val width: Int, val height: Int)

/**
 * 悬浮窗三种形态。
 *
 *  · [CAPSULE] 折叠态小胶囊（默认）
 *  · [PANEL]   展开态终端面板
 *  · [MINI]    小窗态：尺寸比面板小，与面板共用同一套组件
 *
 * 为什么不用 ColorOS 自由浮窗：没有公开 API。
 * 为什么不用 Android PiP：尺寸与交互受限，且会占用系统 PiP 槽位。
 * 三者都是同一个 WindowManager 窗口的不同尺寸与布局，切换即改 LayoutParams。
 */
enum class OverlayWindowState {
    CAPSULE,
    PANEL,
    MINI;

    val isExpanded: Boolean get() = this != CAPSULE

    /** 单击：胶囊 ↔ 面板。小窗态单击回到胶囊，避免「单击直接跳大面板」的突兀。 */
    fun toggleExpanded(): OverlayWindowState = when (this) {
        CAPSULE -> PANEL
        PANEL -> CAPSULE
        MINI -> CAPSULE
    }

    /** 双击：在面板与小窗之间切换；胶囊态双击直接进小窗。 */
    fun toggleMini(): OverlayWindowState = when (this) {
        MINI -> PANEL
        CAPSULE -> MINI
        PANEL -> MINI
    }

    companion object {

        /**
         * 计算该形态的窗口尺寸（px）。
         *
         * 三种形态都受屏幕尺寸约束：小屏手机上 340dp 面板可能超过可用宽度，
         * 因此统一用屏幕宽度与高度的比例封顶，避免窗口跑出屏幕。
         */
        fun sizeFor(
            state: OverlayWindowState,
            configuration: Configuration,
            density: Float,
            miniOverride: OverlaySizePx? = null,
        ): OverlaySizePx {
            val screenW = configuration.screenWidthDp
            val screenH = configuration.screenHeightDp
            return when (state) {
                CAPSULE -> OverlaySizePx(
                    width = dp(OverlayDimens.capsuleMinWidth.value, density),
                    height = dp(OverlayDimens.capsuleHeight.value, density),
                )

                PANEL -> {
                    // ★ 默认宽度 = 屏幕宽度的 75%（需求指定）。
                    //   原实现用固定 340dp，在小屏上可能贴边、在大屏上又显得小。
                    //   用户捏合缩放后的尺寸由 miniOverride 之外的 panelOverride 覆盖（见控制器）。
                    val widthDp = (screenW * PANEL_WIDTH_RATIO).coerceAtMost(screenW * 0.92f)
                    val heightDp = OverlayDimens.panelMaxHeight.value.coerceAtMost(screenH * 0.72f)
                    OverlaySizePx(dp(widthDp, density), dp(heightDp, density))
                }

                MINI -> {
                    // 小窗优先用用户上次拖拽后的尺寸，其次用默认值
                    val fallbackW = OverlayDimens.miniWidth.value.coerceAtMost(screenW * 0.8f)
                    val fallbackH = OverlayDimens.miniHeight.value.coerceAtMost(screenH * 0.6f)
                    val w = miniOverride?.width ?: dp(fallbackW, density)
                    val h = miniOverride?.height ?: dp(fallbackH, density)
                    OverlaySizePx(
                        width = w.coerceAtMost(dp(screenW * 0.96f, density)),
                        height = h.coerceAtMost(dp(screenH * 0.85f, density)),
                    )
                }
            }
        }

        /** 面板默认宽度占屏幕宽度的比例（需求：75%）。 */
        const val PANEL_WIDTH_RATIO = 0.75f

        private fun dp(value: Float, density: Float): Int = (value * density).toInt().coerceAtLeast(1)
    }
}
