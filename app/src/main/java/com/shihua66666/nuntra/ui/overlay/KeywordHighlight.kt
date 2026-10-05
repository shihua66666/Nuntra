package com.shihua66666.nuntra.ui.overlay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.shihua66666.nuntra.core.Keywords

/** 关键词高亮。
 *
 * 实现要点：
 *  · 复用 [Keywords.compile] 的匹配语义（忽略大小写、纯 ASCII 词要求边界、中文不要求）——
 *    这样「高亮的词」与「命中入库的词」永远一致，不会出现「入了库但没高亮」的困惑。
 *  · 先算出所有命中区间并**合并重叠**，再按区间一次性构建文本：
 *    若逐个关键词各自高亮，重叠词会出现 SpanStyle 叠加导致颜色异常。
 */

/** 命中区间（闭开区间）。 */
private data class HitRange(val start: Int, val end: Int)

/**
 * 把 [text] 中命中的 [keywords] 加上强调色与中等字重。
 *
 * @param highlightColor 高亮色（调用方传 tagColor 或强调色）
 */
fun highlightKeywords(
    text: String,
    keywords: List<String>,
    highlightColor: Color,
): AnnotatedString {
    if (text.isEmpty() || keywords.isEmpty()) return AnnotatedString(text)

    val ranges = mutableListOf<HitRange>()
    for (keyword in keywords) {
        val regex = Keywords.compile(keyword) ?: continue
        for (match in regex.findAll(text)) {
            if (match.value.isEmpty()) continue
            ranges.add(HitRange(match.range.first, match.range.last + 1))
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)

    // 合并重叠区间：保证任意位置只被一个 SpanStyle 覆盖
    val merged = ranges
        .sortedWith(compareBy<HitRange> { it.start }.thenBy { it.end })
        .fold(mutableListOf<HitRange>()) { acc, range ->
            val last = acc.lastOrNull()
            if (last != null && range.start <= last.end) {
                acc[acc.size - 1] = HitRange(last.start, maxOf(last.end, range.end))
            } else {
                acc.add(range)
            }
            acc
        }

    return buildAnnotatedString {
        var cursor = 0
        for (range in merged) {
            if (range.start > cursor) append(text.substring(cursor, range.start))
            withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Medium)) {
                append(text.substring(range.start, range.end))
            }
            cursor = range.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
