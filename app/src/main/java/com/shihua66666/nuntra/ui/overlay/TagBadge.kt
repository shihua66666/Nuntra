package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.model.Tag
import com.shihua66666.nuntra.ui.theme.TagPalette

/**
 * 标签徽章。
 *
 * 视觉规格（需求逐条落实）：
 *  · 小圆角：6dp
 *  · 低饱和背景：标签色 alpha 0.14（不是色块，只是淡淡一层）
 *  · 标签色文字：直接用标签色
 *  · 细边框：1dp、标签色 alpha 0.35
 *  · 不加动画：徽章是静态元素，**不做**闪烁/脉冲/呼吸
 *  · 不大面积色块：内边距刻意收窄（横向 6dp、纵向 2dp），高度不超过 16dp
 *
 * 颜色来源是 [TagPalette]（标签色板唯一来源），不硬编码任何色值。
 */
@Composable
fun TagBadge(
    tag: Tag,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(TagPalette.badgeBackground(tag.color))
            .border(1.dp, TagPalette.badgeBorder(tag.color), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = tag.name,
            color = TagPalette.badgeText(tag.color),
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}
