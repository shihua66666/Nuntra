package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.model.TagView
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.LocalMonoFamily
import com.shihua66666.nuntra.ui.theme.TagPalette

/**
 * 展开态顶部的标签筛选栏。
 *
 * 关键约束（需求）：**条目完全来自用户配置的标签，不写死任何标签名**。
 * 传入的 [tags] 由 TagRepository 的 StateFlow 渲染而来，用户增删改标签后自动重建。
 *
 * 交互：单击切换选中；[selectedTagIds] 为空集时表示「全部」。
 * 视觉：小圆角 + 低饱和底 + 标签色文字（与消息卡片徽章同一套视觉语言）。
 */
@Composable
fun TagFilterBar(
    tags: List<TagView>,
    selectedTagIds: Set<String>,
    onToggleTag: (String) -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val c = LocalAppColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            label = ALL_LABEL,
            unreadCount = tags.sumOf { it.unreadCount },
            // 「全部」选中态：没有任何具体标签被选中
            selected = selectedTagIds.isEmpty(),
            accentColor = c.accent,
            onClick = onSelectAll,
        )
        tags.forEach { view ->
            val tag = view.tag
            val tagColor = TagPalette.resolve(tag.color)
            FilterChip(
                label = tag.name,
                unreadCount = view.unreadCount,
                selected = tag.id in selectedTagIds,
                accentColor = tagColor,
                onClick = { onToggleTag(tag.id) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    unreadCount: Int,
    selected: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    val mono: FontFamily = LocalMonoFamily.current
    val borderColor = if (selected) accentColor.copy(alpha = 0.55f) else c.border
    val backgroundColor = if (selected) accentColor.copy(alpha = 0.16f) else c.panelElevated
    val labelColor = if (selected) accentColor else c.textSecondary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = labelColor, fontSize = 12.sp)
        if (unreadCount > 0) {
            Spacer(Modifier.width(4.dp))
            // 未读计数用等宽字体，避免数字变化时宽度抖动
            Text(
                text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                color = if (selected) accentColor else c.muted,
                fontSize = 11.sp,
                fontFamily = mono,
            )
        }
    }
}

private const val ALL_LABEL = "全部"
