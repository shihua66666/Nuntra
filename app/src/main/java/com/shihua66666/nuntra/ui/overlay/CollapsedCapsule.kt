package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.LocalMonoFamily
import com.shihua66666.nuntra.ui.theme.OverlayDimens

/**
 * 折叠态小胶囊。
 *
 * 组成：左侧柔和状态灯 + 中间未读数（等宽字体）。
 *
 * 未读数的颜色规则（需求）：取当前未读消息里**优先级最高的标签颜色**；
 * 没有未读时退回次要文字色；解析失败时退回强调色 —— 永不抛异常、永不空白。
 *
 * 刻意不做的事：不加阴影、不加发光、不做数字滚动动画（会显得闪烁）。
 */
@Composable
fun CollapsedCapsule(
    unreadCount: Int,
    unreadTagColor: Color?,
    modifier: Modifier = Modifier,
    animateLamp: Boolean = true,
) {
    val c = LocalAppColors.current
    val mono: FontFamily = LocalMonoFamily.current
    val hasUnread = unreadCount > 0

    val countColor = when {
        !hasUnread -> c.textSecondary
        unreadTagColor != null -> unreadTagColor
        else -> c.accent
    }
    val lampColor = if (hasUnread) countColor else c.muted

    Row(
        modifier = modifier
            .fillMaxSize()
            .widthIn(min = OverlayDimens.capsuleMinWidth, max = OverlayDimens.capsuleMaxWidth)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StatusLamp(color = lampColor, animate = animateLamp && hasUnread, size = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (hasUnread) displayCount(unreadCount) else NO_UNREAD_TEXT,
            color = countColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = mono,
        )
    }
}

/** 未读数显示：超过 99 显示 99+，避免胶囊被撑宽。 */
private fun displayCount(count: Int): String = if (count > 99) "99+" else count.toString()

/** 无未读时的占位符：用短横线而不是 0，视觉上更安静。 */
private const val NO_UNREAD_TEXT = "—"
