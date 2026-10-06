package com.shihua66666.nuntra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.monitor.SmsAppResolver
import com.shihua66666.nuntra.overlay.OverlayOrchestrator
import com.shihua66666.nuntra.overlay.OverlayService
import com.shihua66666.nuntra.perm.PermissionChecker
import com.shihua66666.nuntra.ui.MainScreen
import com.shihua66666.nuntra.ui.NuntraNavHost
import com.shihua66666.nuntra.ui.Screen
import com.shihua66666.nuntra.ui.theme.AppColors
import com.shihua66666.nuntra.ui.theme.NuntraTheme
import kotlinx.coroutines.launch

/**
 * 唯一 Activity。全 Compose，不引入任何 XML 布局。
 *
 * 三处为「不闪退」做的设计（都是实测踩过的坑）：
 *
 *  1. **容器访问用 [ServiceLocator.appContainerOrNull]** 而不是 [ServiceLocator.c]。
 *     后者在未初始化时会抛异常，而这个访问发生在**组合期**，
 *     结果就是界面还没画出来就闪退，用户看不到任何原因。
 *     现在改为：拿不到容器 → 渲染一个「初始化失败」页面，写明原因。
 *
 *  2. **权限状态在 onResume 刷新**（用 [resumeTick] 触发重组）。
 *     用户去系统设置里授权后切回来，必须立刻显示「已授权」；
 *     仅靠一次性的 remember 初始化做不到这一点。
 *
 *  3. **总开关走 [OverlayOrchestrator]**，它内部检查前置权限、
 *     捕获启动异常、并在失败时回滚开关状态 —— UI 不再直接碰服务启动。
 */
class MainActivity : ComponentActivity() {

    /** 每次 onResume 自增，作为组合期重新读取权限状态的触发器。 */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            setContent {
                // onRequestResumeRefresh 由 Activity 提供：
                // Composable 只调用回调，真正改动状态的是状态的持有者。
                // （此前写成在 Composable 内部 resumeTick++ 是非法的 —— 函数参数是 val，
                //   会被编译器拒绝：'val' cannot be reassigned。）
                NuntraApp(
                    resumeTick = resumeTick,
                    onRequestResumeRefresh = { resumeTick++ },
                )
            }
        }.onFailure { tr -> Logx.swallow("MainActivity", "setContent", tr) }
    }

    override fun onResume() {
        super.onResume()
        // 触发重组 → 重新计算 readiness。ColorOS 等系统会在后台回收权限，
        // 因此「每次回前台复检」是这里唯一的正确做法。
        resumeTick++
        Logx.d("MainActivity", "onResume：刷新权限状态（tick=" + resumeTick + "）")
    }
}

@Composable
private fun NuntraApp(
    resumeTick: Int,
    /** 请求重新检测权限：由 Activity 传入（它持有可变状态），Composable 只调用不修改。 */
    onRequestResumeRefresh: () -> Unit,
) {
    val container = ServiceLocator.appContainerOrNull

    // 主题单独处理：容器不可用时用默认配色兜底，保证「错误页」也能正常渲染
    val colors = if (container != null) {
        container.themeController.colors.collectAsState().value
    } else {
        AppColors.GLAID_BLUE
    }

    NuntraTheme(colors = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (container == null) {
                // ── 初始化失败：显示原因，而不是白屏或闪退 ──
                InitFailureScreen(
                    reason = ServiceLocator.initFailure,
                    reasonHint = "android:name 是否指向 NotificationTerminalApp",
                )
            } else {
                AppContent(
                    container = container,
                    resumeTick = resumeTick,
                    onRequestResumeRefresh = onRequestResumeRefresh,
                )
            }
        }
    }
}

@Composable
private fun AppContent(
    container: com.shihua66666.nuntra.core.AppContainer,
    resumeTick: Int,
    onRequestResumeRefresh: () -> Unit,
    colors: AppColors,
) {
    val scope = ServiceLocator.appScope
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    val themeId by container.themeController.themeId.collectAsState()
    val messages by container.messageStore.sorted.collectAsState()
    val unread by container.messageStore.unreadCount.collectAsState()

    val masterSwitchStored by container.appPreferences.masterSwitchEnabled.collectAsState(initial = false)

    // ★ 乐观更新：点击立刻改变开关外观，不等 DataStore 写盘回流。
    //   原因：用户已经确认「点击进来了但开关不亮」，说明问题在写入/回流环节；
    //   把「点亮」与「落盘」解耦后：
    //     · 写入成功 → 无感（本地覆盖值随后被存储值覆盖，两者相同）；
    //     · 写入失败 → 本地覆盖值被清空 + Toast 报错，开关回到真实状态。
    //   这样「开关能不能亮」不再取决于写盘是否成功，便于把问题彻底分离。
    var pendingMaster by remember { mutableStateOf<Boolean?>(null) }
    val masterSwitchEnabled: Boolean = pendingMaster ?: masterSwitchStored
    val monitorWechat by container.appPreferences.monitorWechat.collectAsState(initial = true)
    val monitorQq by container.appPreferences.monitorQq.collectAsState(initial = true)
    val monitorWeCom by container.appPreferences.monitorWeCom.collectAsState(initial = false)
    val monitorTim by container.appPreferences.monitorTim.collectAsState(initial = false)
    val smsMonitoringEnabled by container.settingsRepository.smsMonitoringEnabled.collectAsState(initial = true)
    val smsKeywordSet by container.settingsRepository.smsKeywords.collectAsState(initial = emptySet())
    val resolvedSmsPackages by container.settingsRepository.resolvedSmsPackages.collectAsState(initial = emptySet())

    // readiness 由 resumeTick 驱动：onResume 时重新计算。
    // snapshot 内部已经全部 runCatching，这里再包一层是为了「任何意外都不致崩」。
    val readiness = remember(resumeTick, masterSwitchEnabled) {
        runCatching { PermissionChecker.snapshot(ServiceLocator.context, masterSwitchEnabled) }
            .getOrElse { tr ->
                Logx.swallow("MainActivity", "readiness", tr)
                com.shihua66666.nuntra.model.ReadinessSnapshot()
            }
    }

    // 短信 App 识别：只在包名集合变化时重算，避免每帧扫包管理器
    val smsResolution = remember(resolvedSmsPackages) {
        runCatching { SmsAppResolver.resolve(ServiceLocator.context, resolvedSmsPackages) }
            .getOrElse { com.shihua66666.nuntra.monitor.SmsAppResolver.Resolution(emptySet(), false) }
    }

    // 总开关统一走编排器：它内部做权限检查、异常兜底与状态回滚。
    // 这里额外做「乐观更新 + 逐步可见的错误反馈」。
    val applyMaster: (Boolean) -> Unit = { enabled ->
        // 全链路打点：即使后续任何一步异常，也能从 Toast 序号看出死在哪一环
        NtToast.show(ServiceLocator.context, "#11 进入 applyMaster，目标=" + enabled)
        val ctx = ServiceLocator.context
        // ① 立刻更新外观（乐观更新）—— 用户先看到开关亮了，再等落盘结果
        pendingMaster = enabled
        NtToast.show(ctx, "#11b 乐观状态已写入，pending=" + pendingMaster + "，当前显示=" + (pendingMaster ?: masterSwitchStored))
        scope.launch {
            try {
                // ② 落盘（内部会回读校验，失败即抛）
                NtToast.show(ctx, "#12 正在写入 DataStore")
                container.appPreferences.setMasterSwitchEnabled(enabled)
                NtToast.show(ctx, "#12b DataStore 写入并回读成功")
                // ③ ★ 先清空覆盖值：让 UI 立刻以「已落盘的真实值」为准，
                //    避免「服务启动较慢时开关一直停留在乐观值」。
                //    由于落盘已完成，此时读到的存储值必然等于期望值，不会闪回。
                pendingMaster = null
                // ④ 再交给编排器启动/停止服务（它内部会 Toast 告知失败原因）
                NtToast.show(ctx, "#13 准备启动前台服务")
                OverlayOrchestrator.setMasterSwitch(
                    context = ctx,
                    enabled = enabled,
                    scope = scope,
                )
            } catch (tr: Throwable) {
                // ★ 绝不静默：把异常类名与消息直接弹出来
                Logx.e("MainActivity", "总开关操作失败", tr)
                NtToast.show(
                    ctx,
                    "写入失败：" + tr.javaClass.simpleName + " / " + (tr.message ?: "无消息"),
                )
                pendingMaster = null
            }
        }
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
                onToggleMasterSwitch = applyMaster,
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
                resumeTick = resumeTick,
                // 「重新检测」按钮：直接把 resumeTick 加一即可触发 readiness 重算
                onRefreshReadiness = { onRequestResumeRefresh() },
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
                onOpenSettings = { screen = Screen.Settings },
                onStartService = {
                    // 手动启动也走编排逻辑：缺权限时提示并引导，而不是静默失败
                    OverlayOrchestrator.setMasterSwitch(
                        context = ServiceLocator.context,
                        enabled = true,
                        scope = scope,
                    )
                },
                onStopService = {
                    runCatching { OverlayService.stop(ServiceLocator.context) }
                        .onFailure { Logx.swallow("MainActivity", "stopService", it) }
                },
                onClearMessages = { container.messageStore.clear() },
                onToggleMasterSwitch = applyMaster,
            )
        },
    )
}


/**
 * 初始化失败页。
 *
 * 存在的唯一目的：让「启动失败」变成一句人话，而不是闪退或白屏。
 * 这页本身不依赖任何容器数据，因此它一定画得出来。
 */
@Composable
private fun InitFailureScreen(reason: String?, reasonHint: String) {
    val c = com.shihua66666.nuntra.ui.theme.LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "Nuntra", color = c.textPrimary, fontSize = 20.sp)
        Text(text = "初始化未完成", color = c.warning, fontSize = 15.sp)
        Text(
            text = reason ?: "容器尚未初始化（未知原因）",
            color = c.textSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(text = "可以尝试：", color = c.textPrimary, fontSize = 13.sp)
        Text(text = "· 完全退出应用后重新打开", color = c.textSecondary, fontSize = 13.sp)
        Text(text = "· 检查应用是否被系统限制后台、存储或自启动", color = c.textSecondary, fontSize = 13.sp)
        Text(text = "· 检查 Manifest 的 application 配置：" + reasonHint, color = c.textSecondary, fontSize = 13.sp)
    }
}
