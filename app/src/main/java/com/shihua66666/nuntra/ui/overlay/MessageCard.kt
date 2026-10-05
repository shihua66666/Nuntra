package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.core.TimeFmt
import com.shihua66666.nuntra.model.Tag
import com.shihua66666.nuntra.model.TerminalMessage
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.LocalMonoFamily
import com.shihua66666.nuntra.ui.theme.SourceColors
import com.shihua66666.nuntra.ui.theme.TagPalette

/**
 * 消息卡片。
 *
 * 结构（严格按需求）：
 *   左侧来源色条（微信 / QQ / 企业微信+办公 / 短信 / 其他）
 *   右上：联系人 + 时间（等宽）
 *   右中：标签徽章 + 命中摘要（特殊关注 / 关键词 / 摘要消息）
 *   右下：正文，命中的关键词高亮
 *
 * 无状态设计：不持有任何状态、不引用 ServiceLocator，所有数据由参数传入，
 * 回调由上层提供。这样列表可以安全地做 key 复用与动画，也便于第 5 步接点击跳转。
 */
@Composable
fun MessageCard(
    message: TerminalMessage,
    tag: Tag?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val mono: FontFamily = LocalMonoFamily.current
    val sourceColor = SourceColors.of(c, message.source)
    val tagColor = tag?.let { TagPalette.resolve(it.color) }
    val highlightColor = tagColor ?: c.accent

    // 未读 / 已读的视觉区分：**只靠色条与描边的强弱**，不改底色明暗。
    // 原因：改底色会与面板、二级面板的层次打架，在低对比的护眼配色下显得脏。
    val stripColor = if (message.read) sourceColor.copy(alpha = 0.40f) else sourceColor
    val cardBackground = c.panelElevated
    val borderColor = if (message.read) c.border.copy(alpha = 0.55f) else c.border

    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBackground)
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable(onClick = onClick)
            .heightIn(min = CARD_MIN_HEIGHT),
    ) {
        // ── 左侧来源色条 ──
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(stripColor),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── 标题行：联系人 + 时间 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contactLabel(message),
                    color = if (message.read) c.textSecondary else c.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (message.read) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = normalizeTime(message.postedAt),
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = mono,
                )
            }

            // ── 徽章行：标签 + 提示 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tag != null) {
                    TagBadge(tag = tag)
                } else {
                    // 标签被删除等异常情况：显示一个中性徽章，绝不空白
                    Text(
                        text = "未分类",
                        color = c.muted,
                        fontSize = 11.sp,
                    )
                }
                if (message.isPriority) {
                    Text(
                        text = "特殊关注",
                        color = c.alertHighlight,
                        fontSize = 11.sp,
                    )
                }
                if (message.isSummary) {
                    Text(text = "汇总", color = c.textSecondary, fontSize = 11.sp)
                }
                if (message.matchedKeywords.isNotEmpty()) {
                    Text(
                        text = "命中 " + message.matchedKeywords.size.toString(),
                        color = c.textSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            // ── 正文（关键词高亮）──
            if (message.body.isNotEmpty()) {
                val annotated = remember(message.id, message.body, message.matchedKeywords, highlightColor) {
                    highlightKeywords(
                        text = message.body,
                        keywords = message.matchedKeywords,
                        highlightColor = highlightColor,
                    )
                }
                Text(
                    text = annotated,
                    color = c.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = MAX_BODY_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 联系人显示：三层兜底，保证永远有内容。 */
private fun contactLabel(message: TerminalMessage): String {
    val candidates = listOf(message.contact, message.conversationTitle, message.subText)
    return candidates.firstOrNull { !it.isNullOrBlank() } ?: message.source.displayName
}

/** 时间：postTime 缺失时退回入库时间，绝不显示 1970。 */
private fun normalizeTime(postedAt: Long): String {
    val ts = if (postedAt > 0L) postedAt else System.currentTimeMillis()
    return TimeFmt.relative(ts)
}

/** 正文最多 3 行：再多会让单条卡片过高，滚动时信息密度下降。 */
private const val MAX_BODY_LINES = 3

/** 卡片最小高度：只有一行正文时也不显得局促。 */
private val CARD_MIN_HEIGHT = 56.dp
