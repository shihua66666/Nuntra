package com.shihua66666.nuntra.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 标签色板：8 个低饱和色，**只能从中选，不允许自由调色**。
 *
 * 这是标签颜色的**唯一来源**。TagRepository 校验、设置页色板、徽章渲染都从这里取，
 * 不允许任何地方再写第二份十六进制字面量。
 *
 * 约束刻意不做取色器与十六进制输入框：
 * 这是保证「标记色始终低饱和、不刺眼」的唯一可靠手段。
 */
object TagPalette {

    val PRESETS: List<String> = listOf(
        "#C96B6B",
        "#6B9BBF",
        "#5E8C6A",
        "#A38FA8",
        "#C9A96E",
        "#6BAFA8",
        "#B08A6B",
        "#7A8CA8",
    )

    /** 解析为 Compose 颜色。非法值回退到第一个预设，不抛异常。 */
    fun resolve(hex: String): Color = AppColors.parse(hex)

    fun isValid(hex: String): Boolean = hex in PRESETS

    /**
     * 徽章底色：低饱和背景。
     *
     * 做法是把标签色以极低 alpha 叠到二级面板上，而不是直接用一个更暗的纯色 ——
     * 这样在任何主题下都不会出现「色块糊在面板上」的突兀感。
     */
    fun badgeBackground(hex: String): Color = resolve(hex).copy(alpha = 0.14f)

    /** 徽章描边：同色低 alpha，形成细边框而不是大色块。 */
    fun badgeBorder(hex: String): Color = resolve(hex).copy(alpha = 0.35f)

    /** 徽章文字：直接用标签色。 */
    fun badgeText(hex: String): Color = resolve(hex)
}
