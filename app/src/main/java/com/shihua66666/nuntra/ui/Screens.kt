package com.shihua66666.nuntra.ui

import androidx.compose.runtime.Composable

/**
 * 两个页面之间的极简导航。
 *
 * 为什么不用 Navigation Compose：
 *  · 全应用只有 2 个目的地（首页 / 设置），引入导航库带来的版本风险大于收益；
 *  · 悬浮窗内部的「面板/小窗/折叠」是窗口形态切换，本来就不是导航；
 *  · 需求要求「依赖面尽量小、不能在无法本机验证时引入不确定版本」。
 *
 * 若将来页面变多，替换成本只是把 Screen 换成 NavHost 的一个 sealed class 路由表。
 */
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
}

@Composable
fun NuntraNavHost(
    current: Screen,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    settingsContent: @Composable () -> Unit,
    homeContent: @Composable () -> Unit,
) {
    when (current) {
        Screen.Home -> homeContent()
        Screen.Settings -> settingsContent()
    }
}
