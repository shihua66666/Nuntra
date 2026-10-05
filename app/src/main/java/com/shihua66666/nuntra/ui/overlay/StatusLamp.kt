package com.shihua66666.nuntra.ui.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 柔和状态灯。
 *
 * 需求约束（逐条落实）：
 *  · 呼吸频率放慢：单程 2800ms，一个完整周期约 5.6 秒 —— 远慢于常见的 1~1.5 秒，
 *    这样才是「呼吸」而不是「闪烁」。
 *  · 不要闪烁动画：alpha 只在 0.45~1.0 之间往返，**永远不低于 0.45**。
 *    若让 alpha 走到 0（灭掉再亮），观感就是闪烁，这是需求明确禁止的。
 *  · 低对比、不刺眼：灯本身是纯色小圆点，不加发光/阴影/扫描线。
 *
 * 关闭动画的场景：系统「移除动画」开启时 [animate] 传 false，直接显示静态点。
 */
@Composable
fun StatusLamp(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    animate: Boolean = true,
) {
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "statusLamp")
        val animated by transition.animateFloat(
            initialValue = MIN_ALPHA,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = BREATH_DURATION_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusLampAlpha",
        )
        animated
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .alpha(alpha)
            .background(color),
    )
}

/** 呼吸最暗值：0.45 是刻意选的 —— 低于它就会被感知为「闪烁」而不是「呼吸」。 */
private const val MIN_ALPHA = 0.45f

/** 单程时长（ms）：2800 使一个完整往返约 5.6 秒，符合「放慢」要求。 */
private const val BREATH_DURATION_MS = 2800
