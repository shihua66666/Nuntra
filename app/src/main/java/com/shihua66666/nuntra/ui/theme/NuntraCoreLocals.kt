package com.shihua66666.nuntra.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 主题色 CompositionLocal。
 *
 * 用 staticCompositionLocalOf：主题切换是低频操作，
 * 但读取点极多，static 版本的重组成本更低。
 *
 * 默认值给灰色预览用的占位，保证在主题外误用时也不会崩。
 */
val LocalAppColors: ProvidableCompositionLocal<AppColors> =
    staticCompositionLocalOf { AppColors.GLAID_BLUE }

/** 等宽字体家族（IBM Plex Mono，缺失时回退系统等宽）。 */
val LocalMonoFamily: ProvidableCompositionLocal<FontFamily> =
    staticCompositionLocalOf { FontFamily.Monospace }

/**
 * 字号与行高集中定义。
 *
 * 需求约束：正文不小于 14sp，行高 1.4 倍；
 * 等宽字体只用于数字/英文场景（时间、未读数、关键词），中文一律走系统字体。
 */
object NuntraType {

    const val LINE_HEIGHT_RATIO = 1.4f

    val displayCount: TextUnit = 20.sp
    val panelTitle: TextUnit = 16.sp
    val cardContact: TextUnit = 15.sp
    /** 正文下限，不允许再小。 */
    val cardBody: TextUnit = 14.sp
    val meta: TextUnit = 12.sp
    val tagBadge: TextUnit = 12.sp
    val monoId: TextUnit = 12.sp

    val bodyWeight = FontWeight.Normal
    val titleWeight = FontWeight.Medium

    fun lineHeightFor(size: TextUnit): TextUnit = (size.value * LINE_HEIGHT_RATIO).sp
}
