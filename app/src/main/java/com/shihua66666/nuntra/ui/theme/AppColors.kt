package com.shihua66666.nuntra.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/**
 * 一套完整配色。
 *
 * 语义命名而非色名命名：换主题皮时只改数值，UI 代码一行不动。
 *
 * 需求只给了每套主题的三个色（背景 / 面板 / 强调），其余 token 由 [derive] 统一派生，
 * 派生比例固定，保证五套主题的对比度一致 —— 手填四套派生色必然会飘。
 */
@Immutable
data class AppColors(
    val id: String,
    val displayName: String,
    val background: Color,
    val panel: Color,
    val panelElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val infoBlue: Color,
    val warning: Color,
    val sourceWeChat: Color,
    val sourceQq: Color,
    val sourceSms: Color,
    /** 企业微信 / TIM 共用（腾讯系办公）。 */
    val sourceOffice: Color,
    val sourceUnknown: Color,
    /** 来自扩展需求（提醒音/重复提醒）的语义色，便于卡片上表达警示。 */
    val alertHighlight: Color,
    val muted: Color,
) {
    companion object {

        // ── 五套预设：需求给定的三色 + 派生 ──────────────────────

        /** 1. 灰蓝终端（默认）：背景 #1E2429，面板 #262D33，强调 #C9A96E */
        val GLAID_BLUE = derive(
            id = "glaid_blue",
            displayName = "灰蓝终端",
            backgroundHex = "#1E2429",
            panelHex = "#262D33",
            accentHex = "#C9A96E",
            sourceSmsHex = "#8A7FA8",
        )

        /** 2. 冷夜蓝：背景 #1A2028，面板 #212932，强调 #7FA8C9 */
        val COLD_NIGHT = derive(
            id = "cold_night",
            displayName = "冷夜蓝",
            backgroundHex = "#1A2028",
            panelHex = "#212932",
            accentHex = "#7FA8C9",
            sourceSmsHex = "#8FA3C9",
        )

        /** 3. 暖灰砂：背景 #262320，面板 #2E2A26，强调 #C9A96E */
        val WARM_SAND = derive(
            id = "warm_sand",
            displayName = "暖灰砂",
            backgroundHex = "#262320",
            panelHex = "#2E2A26",
            accentHex = "#C9A96E",
            sourceSmsHex = "#B08A6B",
        )

        /** 4. 深墨绿：背景 #1C2422，面板 #232C29，强调 #8FB89A */
        val DEEP_INK_GREEN = derive(
            id = "deep_ink_green",
            displayName = "深墨绿",
            backgroundHex = "#1C2422",
            panelHex = "#232C29",
            accentHex = "#8FB89A",
            sourceSmsHex = "#6BAFA8",
        )

        /** 5. 暗紫靛：背景 #211F2A，面板 #282633，强调 #A38FA8 */
        val DARK_PURPLE = derive(
            id = "dark_purple",
            displayName = "暗紫靛",
            backgroundHex = "#211F2A",
            panelHex = "#282633",
            accentHex = "#A38FA8",
            sourceSmsHex = "#7A8CA8",
        )

        val PRESETS: List<AppColors> = listOf(
            GLAID_BLUE, COLD_NIGHT, WARM_SAND, DEEP_INK_GREEN, DARK_PURPLE,
        )

        fun presetById(id: String): AppColors =
            PRESETS.firstOrNull { it.id == id } ?: GLAID_BLUE

        /**
         * 由三色派生完整 token。
         *
         * 派生规则（对五套主题统一适用）：
         *  · textPrimary   = 白色向背景混 16%
         *  · textSecondary = textPrimary 向背景退 34%
         *  · panelElevated = 面板向白色混 6%
         *  · border        = 面板向 textPrimary 混 22%
         *  · infoBlue      = 强调色向白混 22%
         *  · warning       = 固定 #C96B6B（与标签色板同值但语义独立）
         */
        fun derive(
            id: String,
            displayName: String,
            backgroundHex: String,
            panelHex: String,
            accentHex: String,
            sourceSmsHex: String,
        ): AppColors {
            val background = parse(backgroundHex)
            val panel = parse(panelHex)
            val accent = parse(accentHex)
            val textPrimary = lerp(Color.White, background, 0.16f)
            val textSecondary = lerp(textPrimary, background, 0.34f)
            return AppColors(
                id = id,
                displayName = displayName,
                background = background,
                panel = panel,
                panelElevated = lerp(panel, Color.White, 0.06f),
                border = lerp(panel, textPrimary, 0.22f),
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accent = accent,
                infoBlue = lerp(accent, Color.White, 0.22f),
                warning = Color(0xFFC96B6B),
                sourceWeChat = Color(0xFF5E8C6A),
                sourceQq = Color(0xFF5E7FA3),
                sourceSms = parse(sourceSmsHex),
                // 办公系来源色固定，不随主题的短信色变化：它要一眼与个人短信区分开
                sourceOffice = Color(0xFF5E7FA3),
                sourceUnknown = Color(0xFF6E7A85),
                alertHighlight = lerp(Color(0xFFC96B6B), Color.White, 0.12f),
                muted = lerp(textSecondary, background, 0.25f),
            )
        }

        /** 解析 #RRGGBB。非法输入回退到中性灰，绝不抛异常。 */
        fun parse(hex: String): Color {
            val cleaned = hex.removePrefix("#").trim()
            if (cleaned.length != 6) return Color(0xFF6E7A85)
            return runCatching { Color(0xFF000000L.toInt() or cleaned.toLong(16).toInt()) }
                .getOrDefault(Color(0xFF6E7A85))
        }

        /** 供 XML/系统通知等非 Compose 场景使用。 */
        fun toArgbInt(color: Color): Int = color.toArgb()
    }
}
