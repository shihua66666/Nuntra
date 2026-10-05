package com.shihua66666.nuntra.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Material3 排版。
 *
 * 字体策略（需求：中文系统字体，英文 IBM Plex Mono）：
 *  · IBM Plex Mono 只有拉丁字形，**没有中文字形**。
 *    若把中英混排的字符串整体设为该字体，中文会走系统兜底，但字形与字重会跳变，观感很差。
 *  · 因此这里只把「纯数字/英文场景」的样式（labelSmall / labelMedium）设为等宽，
 *    正文、标题一律用默认字体家族（系统中文）。
 *  · 需要精确控制的地方（时间戳、未读数、关键词）在组件里单独用 LocalMonoFamily 覆盖。
 */
fun nuntraTypography(mono: FontFamily): Typography {
    // bodyMedium 是全局兜底；显式给 14sp / 1.4 行高，保证正文下限
    return Typography(
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = NuntraType.cardBody,
            lineHeight = NuntraType.lineHeightFor(NuntraType.cardBody),
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = NuntraType.meta,
            lineHeight = NuntraType.lineHeightFor(NuntraType.meta),
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = NuntraType.panelTitle,
            lineHeight = NuntraType.lineHeightFor(NuntraType.panelTitle),
        ),
        // 纯数字/英文场景才用等宽
        labelSmall = TextStyle(
            fontFamily = mono,
            fontSize = NuntraType.monoId,
            lineHeight = NuntraType.lineHeightFor(NuntraType.monoId),
        ),
        labelMedium = TextStyle(
            fontFamily = mono,
            fontSize = NuntraType.meta,
            lineHeight = NuntraType.lineHeightFor(NuntraType.meta),
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
            lineHeight = NuntraType.lineHeightFor(14.sp),
        ),
    )
}
