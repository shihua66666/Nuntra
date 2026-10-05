package com.shihua66666.nuntra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.ui.theme.LocalAppColors

/**
 * 交互控件集合。
 *
 * 统一原则：低对比、细边框、圆角 6–8dp、无涟漪以外的动效。
 */

/** 主按钮：强调色描边 + 透明底，避免大面积色块刺眼。 */
@Composable
fun NtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = true,
) {
    val colors = LocalAppColors.current
    val borderColor = when {
        !enabled -> colors.border
        accent -> colors.accent
        else -> colors.border
    }
    val textColor = when {
        !enabled -> colors.muted
        accent -> colors.accent
        else -> colors.textPrimary
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = textColor, fontSize = 14.sp)
    }
}

/** 次级按钮：次要文字色，用于「清空」「重试」这类非主路径操作。 */
@Composable
fun NtTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = when {
            !enabled -> colors.muted
            color != null -> color
            else -> colors.infoBlue
        },
        fontSize = 13.sp,
    )
}

/** 开关行：左侧标题 + 可选说明，右侧开关。 */
@Composable
fun NtSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) colors.textPrimary else colors.muted,
                fontSize = 14.sp,
            )
            if (subtitle != null) NtCaption(subtitle)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.panelElevated,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}
