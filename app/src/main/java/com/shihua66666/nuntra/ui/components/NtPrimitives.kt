package com.shihua66666.nuntra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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

/**
 * UI 基元集合。
 *
 * 全部从 CompositionLocal 取色，**任何组件都不许硬编码 Color(0xFF...)**，
 * 这是「切换主题后全局立即生效」的前提。
 */

/** 面板容器：一级面板底色 + 1dp 细边框，无阴影。 */
@Composable
fun NtPanel(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (elevated) colors.panelElevated else colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(contentPadding),
        content = content,
    )
}


/** 分区标题：小号、克制、前置一条强调色短竖线。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 次要说明文字，统一 12sp 与次要文字色。 */
@Composable
fun NtCaption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        modifier = modifier,
        color = color ?: colors.textSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

/** 正文文字：14sp / 1.4 行高，需求下限。 */
@Composable
fun NtBody(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        modifier = modifier,
        color = color ?: colors.textPrimary,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

/** 等宽数字文本：只用于时间、计数、ID 等纯数字/英文场景。 */
@Composable
fun NtMono(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    family: FontFamily = LocalMonoFamily.current,
) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        modifier = modifier,
        color = color ?: colors.textSecondary,
        fontSize = fontSize,
        fontFamily = family,
    )
}

/** 分隔线：1dp 边框色。 */
@Composable
fun NtDivider(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.border),
    )
}

/** 状态点（如权限是否就绪）。无动画，避免闪烁。 */
@Composable
fun NtStatusDot(ok: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (ok) colors.sourceWeChat else colors.muted),
    )
}
