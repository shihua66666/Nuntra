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
