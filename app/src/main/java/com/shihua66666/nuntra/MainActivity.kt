package com.shihua66666.nuntra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.monitor.SmsAppResolver
import com.shihua66666.nuntra.overlay.OverlayService
import com.shihua66666.nuntra.perm.PermissionChecker
import com.shihua66666.nuntra.ui.MainScreen
import com.shihua66666.nuntra.ui.NuntraNavHost
import com.shihua66666.nuntra.ui.Screen
import com.shihua66666.nuntra.ui.theme.AppColors
import com.shihua66666.nuntra.ui.theme.NuntraTheme
import kotlinx.coroutines.launch

/**
 * 唯一 Activity。
 *
 * 全 Compose，不引入任何 XML 布局。
 *
 * 两个关键接线：
 *  1. 主题来自 ServiceLocator 里的 ThemeController 单例 —— 切换后首页、设置页、悬浮窗同步生效；
 *  2. 总监控开关直接驱动前台服务与悬浮窗（开则启动、关则停止并移除窗口）。
 */
class MainActivity : ComponentActivity() {

    private val container get() = ServiceLocator.c

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors by container.themeController.colors.collectAsState()
            NuntraTheme(colors = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NuntraApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 权限可能被系统或用户在后台收回（ColorOS 上尤其常见），
        // 因此每次回前台都重新确认一次就绪状态。
        Logx.d("MainActivity", "onResume：刷新权限状态")
    }
}

@Composable
private fun NuntraApp() {
    val container = ServiceLocator.c
    val scope = ServiceLocator.appScope

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    val colors by container.themeController.colors.collectAsState()
    val themeId by container.themeController.themeId.collectAsState()
    val messages by container.messageStore.sorted.collectAsState()
    val unread by container.messageStore.unreadCount.collectAsState()

    // ── 总开关与监控范围（来自 DataStore，随设置即时回流）─────────
    val masterSwitchEnabled by container.appPreferences.masterSwitchEnabled.collectAsState(initial = false)
    val monitorWechat by container.appPreferences.monitorWechat.collectAsState(initial = true)
    val monitorQq by container.appPreferences.monitorQq.collectAsState(initial = true)
    val monitorWeCom by container.appPreferences.monitorWeCom.collectAsState(initial = false)
    val monitorTim by container.appPreferences.monitorTim.collectAsState(initial = false)
    val smsMonitoringEnabled by container.settingsRepository.smsMonitoringEnabled.collectAsState(initial = true)
    val smsKeywordSet by container.settingsRepository.smsKeywords.collectAsState(initial = emptySet())
    val resolvedSmsPackages by container.settingsRepository.resolvedSmsPackages.collectAsState(initial = emptySet())

    // 就绪状态：权限 + 服务运行状态。用总开关近似反映服务是否应当运行。
    var readiness by remember { mutableStateOf(PermissionChecker.snapshot(ServiceLocator.context)) }

    // 短信 App 识别结果：自动识别 + 用户确认，供设置页展示。
    val smsResolution = remember(resolvedSmsPackages) {
        SmsAppResolver.resolve(ServiceLocator.context, resolvedSmsPackages)
    }

    NuntraNavHost(
        current = screen,
        onNavigate = { screen = it },
        onBack = { screen = Screen.Home },
        settingsContent = {
            com.shihua66666.nuntra.ui.settings.SettingsScreen(
                onBack = { screen = Screen.Home },
                readiness = readiness,
                themeColors = AppColors.PRESETS,
                currentThemeId = themeId,
                onSelectTheme = { id -> scope.launch { container.themeController.selectTheme(id) } },
                masterSwitchEnabled = masterSwitchEnabled,
                onToggleMasterSwitch = { enabled ->
                    scope.launch {
                        container.appPreferences.setMasterSwitchEnabled(enabled)
                        applyMasterSwitch(enabled)
                    }
                },
                monitorWechat = monitorWechat,
                monitorQq = monitorQq,
                monitorWeCom = monitorWeCom,
                monitorTim = monitorTim,
                onToggleMonitorWechat = { v -> scope.launch { container.appPreferences.setMonitorWechat(v) } },
                onToggleMonitorQq = { v -> scope.launch { container.appPreferences.setMonitorQq(v) } },
                onToggleMonitorWeCom = { v -> scope.launch { container.appPreferences.setMonitorWeCom(v) } },
                onToggleMonitorTim = { v -> scope.launch { container.appPreferences.setMonitorTim(v) } },
                smsMonitoringEnabled = smsMonitoringEnabled,
                onToggleSmsMonitoring = { v -> scope.launch { container.settingsRepository.setSmsMonitoringEnabled(v) } },
                smsKeywords = smsKeywordSet,
                onAddSmsKeyword = { word -> scope.launch { container.settingsRepository.addSmsKeyword(word) } },
                onRemoveSmsKeyword = { word -> scope.launch { container.settingsRepository.removeSmsKeyword(word) } },
                onResetSmsKeywords = { scope.launch { container.settingsRepository.resetSmsKeywords() } },
                resolvedSmsPackages = smsResolution.packages,
                smsNeedsManualSelection = smsResolution.needsManualSelection,
            )
        },
        homeContent = {
            MainScreen(
                colors = colors,
                readiness = readiness,
                masterSwitchEnabled = masterSwitchEnabled,
                messageCount = messages.size,
                unreadCount = unread,
                latestMessageAt = messages.firstOrNull()?.sortTime,
                serviceRunning = readiness.serviceRunning,
                onOpenSettings = {
                    readiness = PermissionChecker.snapshot(ServiceLocator.context, masterSwitchEnabled)
                    screen = Screen.Settings
                },
                onStartService = {
                    OverlayService.start(ServiceLocator.context)
                    readiness = PermissionChecker.snapshot(ServiceLocator.context, serviceRunning = true)
                },
                onStopService = {
                    OverlayService.stop(ServiceLocator.context)
                    readiness = PermissionChecker.snapshot(ServiceLocator.context, serviceRunning = false)
                },
                onClearMessages = { container.messageStore.clear() },
                onToggleMasterSwitch = { enabled ->
                    scope.launch {
                        container.appPreferences.setMasterSwitchEnabled(enabled)
                        applyMasterSwitch(enabled)
                    }
                },
            )
        },
    )
}

/**
 * 总开关的联动行为（需求：总闸）。
 *
 * 开启：启动前台服务（服务会创建悬浮窗，第 2 步接入）。
 * 关闭：停止前台服务（第 2 步起会同时 removeView 移除悬浮窗）。
 *
 * 消息列表**不清空**：用户临时关一下总闸再打开时，之前的消息应当还在，
 * 这与「保留最近 24 小时」的策略一致；需要清空时首页有独立的「清空消息」按钮。
 */
private fun applyMasterSwitch(enabled: Boolean) {
    val context = ServiceLocator.context
    if (enabled) {
        Logx.i("MainActivity", "总开关开启：启动前台服务")
        OverlayService.start(context)
    } else {
        Logx.i("MainActivity", "总开关关闭：停止前台服务并移除悬浮窗")
        OverlayService.stop(context)
    }
}
