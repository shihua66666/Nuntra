package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shihua66666.nuntra.ui.components.NtTextButton
import com.shihua66666.nuntra.ui.theme.LocalAppColors

/**
 * 展开态底部操作栏：筛选开关 / 设置 / 清空 / 折叠。
 *
 * 用文字按钮而不是图标：本应用的视觉语言是「终端档案」，
 * 文字标签比图标更符合「信息清晰、不靠图形猜」的定位，也避免了图标风格不统一的观感问题。
 *
 * 「清空」在无消息时禁用（避免点了没反应）；「折叠」始终可用。
 */
@Composable
fun PanelBottomBar(
    messageCount: Int,
    filterActive: Boolean,
    onToggleFilter: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearMessages: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NtTextButton(
            text = if (filterActive) "筛选 ·开" else "筛选",
            onClick = onToggleFilter,
            color = if (filterActive) c.accent else null,
        )
        NtTextButton(text = "设置", onClick = onOpenSettings)
        Spacer(Modifier.weight(1f))
        NtTextButton(
            text = "清空",
            onClick = onClearMessages,
            enabled = messageCount > 0,
            color = if (messageCount > 0) c.warning else null,
        )
        NtTextButton(text = "折叠", onClick = onCollapse, color = c.accent)
    }
}
