package com.shihua66666.nuntra.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 悬浮窗专用尺寸与圆角。
 *
 * 单独抽出来的原因：悬浮窗的尺寸需要在「非 Compose 上下文」（WindowManager LayoutParams）里使用，
 * 而这些值又要与 Compose 内部保持一致，写两处必然漂移。
 */
@Immutable
object OverlayDimens {

    /** 折叠态胶囊：宽度自适应内容，这里给最小/最大宽度。 */
    val capsuleMinWidth: Dp = 76.dp
    val capsuleMaxWidth: Dp = 132.dp
    val capsuleHeight: Dp = 44.dp

    /** 面板态：比屏幕略窄，避免贴边。 */
    val panelWidth: Dp = 340.dp
    val panelMaxHeight: Dp = 520.dp

    /** 小窗态：比面板小一圈，类似系统小窗。 */
    val miniWidth: Dp = 240.dp
    val miniHeight: Dp = 300.dp

    // ── 缩放下限 ─────────────────────────────────────────────────
    //
    //  为什么必须有下限：没有它，捏合可以把窗口缩到极小，
    //  此时「标题栏 + 筛选栏 + 底部操作栏」的总高度超过窗口高度，
    //  固定在底部的操作栏会被挤出可视区域 —— 用户就再也点不到「折叠」「设置」了。
    //
    //  面板下限取 140dp：标题栏(~34) + 筛选栏(~32) + 内容区(≥24) + 底栏(~44) ≈ 134dp，
    //  留一点余量保证底栏永远完整可见。

    /** 面板态最小宽度：再窄筛选栏与按钮就挤成一团。 */
    val panelMinWidth: Dp = 200.dp

    /** 面板态最小高度：需求指定 140dp，保证底部操作栏不被裁掉。 */
    val panelMinHeight: Dp = 140.dp

    /** 小窗态最小宽度：需求指定 120dp。 */
    val miniMinWidth: Dp = 120.dp

    /** 小窗态最小高度：需求指定 80dp。 */
    val miniMinHeight: Dp = 80.dp

    /** 圆角：严格落在 6–8dp 区间，不用尖锐斜切。 */
    val capsuleRadius: Dp = 22.dp
    val panelRadius: Dp = 8.dp
    val miniRadius: Dp = 8.dp

    /** 拖动阈值：小于它视为点击，大于它视为拖动。 */
    val dragThreshold: Dp = 10.dp

    /** 拖动时的透明度反馈。 */
    const val draggingAlpha: Float = 0.82f

    fun capsuleShape() = RoundedCornerShape(capsuleRadius)

    fun panelShape() = RoundedCornerShape(panelRadius)

    fun miniShape() = RoundedCornerShape(miniRadius)
}
