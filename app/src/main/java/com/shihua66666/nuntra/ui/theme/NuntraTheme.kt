package com.shihua66666.nuntra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * 应用主题。
 *
 * 只提供深色方案：本设计风格（低对比、护眼、终端感）在浅色下不成立，
 * 因此不给浅色分支，也不启用 dynamicColor —— 后者会拿壁纸颜色覆盖 token，
 * 直接破坏五套预设的可预期性。
 */
@Composable
fun NuntraTheme(
    colors: AppColors = AppColors.GLAID_BLUE,
    monoFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 未显式传入时自行探测字体资源；缺文件会回退系统等宽，不崩。
    val mono = monoFamily ?: remember(context) { MonoFont.load(context) }

    val colorScheme = remember(colors) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            secondary = colors.infoBlue,
            onSecondary = colors.background,
            tertiary = colors.sourceWeChat,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.panel,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.panelElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.warning,
            onError = colors.textPrimary,
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalMonoFamily provides mono,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = nuntraTypography(mono),
            shapes = NuntraShapes,
            content = content,
        )
    }
}

/** 便捷取色：等价于 LocalAppColors.current，但语义更直白。 */
@Composable
fun appColors(): AppColors = LocalAppColors.current
