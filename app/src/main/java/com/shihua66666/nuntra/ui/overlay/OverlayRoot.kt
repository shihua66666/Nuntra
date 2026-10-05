package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.overlay.OverlayWindowState
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.LocalMonoFamily
import com.shihua66666.nuntra.ui.theme.OverlayDimens

/**
 * 悬浮窗内容容器。
 *
 * 第 2 步只提供「正确的容器 + 三态骨架」：
 *  · 容器的圆角、底色、边框在这里统一处理，三种形态共用；
 *  · 具体内容（折叠胶囊 / 终端面板 / 小窗）在第 3、4 步填充。
 *
 * 容器本身的约束（来自需求）：低对比、柔和、不刺眼、不纯黑、无闪烁、圆角 6–8dp。
 * API 31+ 才可能有真实背景模糊，因此这里用半透明纯色 + 1dp 细边框打底，
 * 不做「看起来像毛玻璃其实是假渐变」的伪效果。
 */
@Composable
fun OverlayRoot(
    windowState: OverlayWindowState,
    unreadCount: Int,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = LocalAppColors.current
    val shape = when (windowState) {
        OverlayWindowState.CAPSULE -> OverlayDimens.capsuleShape()
        OverlayWindowState.PANEL -> OverlayDimens.panelShape()
        OverlayWindowState.MINI -> OverlayDimens.miniShape()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            // 半透明面板底：让底层内容隐约可见，形成「轻毛玻璃」的观感
            .background(c.panel.copy(alpha = 0.96f))
            .border(1.dp, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 第 2 步的占位内容。
 *
 * 存在的意义是把「窗口能不能正常显示、拖动、切换形态、旋转后是否还在屏幕内」
 * 这批结构性问题先验证掉；内容做完后这个 Composable 会被删掉。
 */
@Composable
fun OverlayPlaceholder(
    windowState: OverlayWindowState,
    unreadCount: Int,
    windowWidthPx: Int,
    windowHeightPx: Int,
    onPlaceholderTap: () -> Unit,
) {
    val c = LocalAppColors.current
    when (windowState) {
        OverlayWindowState.CAPSULE -> CapsulePlaceholder(unreadCount, c.accent)
        OverlayWindowState.PANEL -> PanelPlaceholder(windowState, windowWidthPx, windowHeightPx, onPlaceholderTap)
        OverlayWindowState.MINI -> PanelPlaceholder(windowState, windowWidthPx, windowHeightPx, onPlaceholderTap)
    }
}

@Composable
private fun CapsulePlaceholder(unreadCount: Int, accent: androidx.compose.ui.graphics.Color) {
    val c = LocalAppColors.current
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态灯：第 3 步会换成带慢呼吸的 StatusLamp；这里先用静态圆点，绝不做闪烁。
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (unreadCount > 0) accent else c.muted),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (unreadCount > 0) unreadCount.toString() else "—",
            color = c.textPrimary,
            fontSize = 16.sp,
            fontFamily = LocalMonoFamily.current,
        )
    }
}

@Composable
private fun PanelPlaceholder(
    windowState: OverlayWindowState,
    windowWidthPx: Int,
    windowHeightPx: Int,
    onPlaceholderTap: () -> Unit,
) {
    val c = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (windowState == OverlayWindowState.MINI) "小窗态" else "面板态",
                color = c.textPrimary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = windowWidthPx.toString() + "×" + windowHeightPx,
                color = c.textSecondary,
                fontSize = 12.sp,
                fontFamily = LocalMonoFamily.current,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(c.panelElevated),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "第 2 步：窗口与手势已就绪",
                    color = c.textSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "单击 / 双击 / 拖动 / 长按 均可测试",
                    color = c.textSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.accent.copy(alpha = 0.14f))
                        .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "点这里触发一次单击", color = c.accent, fontSize = 12.sp)
                }
            }
        }
    }
}
