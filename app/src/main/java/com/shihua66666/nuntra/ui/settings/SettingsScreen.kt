package com.shihua66666.nuntra.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
// ★ 属性委托 by 必需（draft 用了 var draft by remember { mutableStateOf("") }）
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.width
import com.shihua66666.nuntra.core.CrashRecorder
import com.shihua66666.nuntra.data.MessageStore
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.perm.PermissionNavigator
import com.shihua66666.nuntra.ui.SafeCrashReportScreen
import androidx.compose.ui.Modifier
import com.shihua66666.nuntra.model.ReadinessSnapshot
import com.shihua66666.nuntra.ui.components.NtButton
import com.shihua66666.nuntra.ui.components.NtCaption
import com.shihua66666.nuntra.ui.components.NtDivider
import com.shihua66666.nuntra.ui.components.NtPanel
import com.shihua66666.nuntra.ui.components.NtSwitchRow
import com.shihua66666.nuntra.ui.components.SectionHeader
import com.shihua66666.nuntra.ui.theme.AppColors
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import kotlin.math.roundToInt

/**
 * 设置页骨架（第 0 步版本）。
 *
 * 第 1 步已落地：总监控开关、监控范围四个分闸、短信监控（含关键词与短信 App 识别状态）。
 * 后续步骤继续填：标签管理、关注人/关键词编辑、提醒音与重复提醒、
 * 消息上限与保留策略 —— 分区顺序已按最终结构排好，填内容时不需要再动布局。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    readiness: ReadinessSnapshot = ReadinessSnapshot(),
    themeColors: List<AppColors> = AppColors.PRESETS,
    currentThemeId: String = AppColors.GLAID_BLUE.id,
    onSelectTheme: (String) -> Unit = {},
    masterSwitchEnabled: Boolean = false,
    onToggleMasterSwitch: (Boolean) -> Unit = {},
    monitorWechat: Boolean = true,
    monitorQq: Boolean = true,
    monitorWeCom: Boolean = false,
    monitorTim: Boolean = false,
    onToggleMonitorWechat: (Boolean) -> Unit = {},
    onToggleMonitorQq: (Boolean) -> Unit = {},
    onToggleMonitorWeCom: (Boolean) -> Unit = {},
    onToggleMonitorTim: (Boolean) -> Unit = {},
    smsMonitoringEnabled: Boolean = true,
    onToggleSmsMonitoring: (Boolean) -> Unit = {},
    smsKeywords: Set<String> = emptySet(),
    onAddSmsKeyword: (String) -> Unit = {},
    onRemoveSmsKeyword: (String) -> Unit = {},
    onResetSmsKeywords: () -> Unit = {},
    resolvedSmsPackages: Set<String> = emptySet(),
    smsNeedsManualSelection: Boolean = false,
    /** 消息条数上限（读自 AppPreferences.messageLimit）。 */
    messageLimit: Int = MessageStore.DEFAULT_LIMIT,
    onChangeMessageLimit: (Int) -> Unit = {},
    /** 保留时长（小时）。 */
    retentionHours: Int = MessageStore.DEFAULT_RETENTION_HOURS,
    onChangeRetentionHours: (Int) -> Unit = {},
    /** 点击消息跳转后是否隐藏已读项（设置页开关，默认开）。 */
    clearReadOnTap: Boolean = true,
    onChangeClearReadOnTap: (Boolean) -> Unit = {},
    /** 每次回前台自增：驱动权限状态重新读取（用户去设置里授权后切回来要立刻生效）。 */
    resumeTick: Int = 0,
    /** 手动重新检测权限（设置页的「重新检测」按钮）。 */
    onRefreshReadiness: () -> Unit = {},
    /** 打开「标签管理」子页面。 */
    onOpenTagManager: () -> Unit = {},
    /** 打开「关注人与关键词」子页面。 */
    onOpenContacts: () -> Unit = {},
    /** 打开「提醒音与重复提醒」子页面。 */
    onOpenReminder: () -> Unit = {},
) {
    val c = LocalAppColors.current
    Scaffold(containerColor = c.background) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                NtButton(text = "返回", onClick = onBack, accent = false)
            }

            MasterSwitchSection(
                enabled = masterSwitchEnabled,
                onToggle = onToggleMasterSwitch,
            )

            MonitorScopeSection(
                masterSwitchEnabled = masterSwitchEnabled,
                wechat = monitorWechat,
                qq = monitorQq,
                weCom = monitorWeCom,
                tim = monitorTim,
                onToggleWechat = onToggleMonitorWechat,
                onToggleQq = onToggleMonitorQq,
                onToggleWeCom = onToggleMonitorWeCom,
                onToggleTim = onToggleMonitorTim,
            )

            ThemeSection(
                themes = themeColors,
                currentId = currentThemeId,
                onSelect = onSelectTheme,
            )

            // ★ 三个真正可点击的入口 —— 之前这里是 PlaceholderSection（纯文案），
            //   点不动，导致用户根本无法配置标签与关注人，标签分类形同虚设。
            NtNavRow(
                title = "标签管理",
                subtitle = "新增 / 重命名 / 改色 / 拖拽排序 / 删除迁移，以及每个标签的特殊关注与静音开关。",
                onClick = onOpenTagManager,
            )
            NtNavRow(
                title = "关注人与关键词",
                subtitle = "关注人绑定标签；未命中任何关注人时归入兜底标签。",
                onClick = onOpenContacts,
            )
            NtNavRow(
                title = "提醒音与重复提醒",
                subtitle = "提醒音试听/更换；重复提醒间隔与次数。",
                onClick = onOpenReminder,
            )
            SmsSection(
                enabled = smsMonitoringEnabled,
                onToggle = onToggleSmsMonitoring,
                keywords = smsKeywords,
                onAddKeyword = onAddSmsKeyword,
                onRemoveKeyword = onRemoveSmsKeyword,
                onResetKeywords = onResetSmsKeywords,
                resolvedPackages = resolvedSmsPackages,
                needsManualSelection = smsNeedsManualSelection,
            )
            MessageLimitSection(
                limit = messageLimit,
                onChangeLimit = onChangeMessageLimit,
                retentionHours = retentionHours,
                onChangeRetention = onChangeRetentionHours,
            )

            // ★ 新增：点击行为。与「消息上限与保留」分开成独立分区，
            //   因为一个是存储策略、一个是交互策略，混在一起语义不清。
            ClickBehaviorSection(
                clearReadOnTap = clearReadOnTap,
                onChange = onChangeClearReadOnTap,
            )

            CrashDiagnosticsSection()

            PermissionSection(
                readiness = readiness,
                resumeTick = resumeTick,
                onRefreshReadiness = onRefreshReadiness,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 消息上限与保留策略 —— **真正可操作的控件**。
 *
 * 修复背景：这里原本只是一个 PlaceholderSection（纯文本说明），
 * 写着「条数上限默认 200，可调」却没有任何可调入口，属于「界面在骗人」。
 *
 * 交互设计：
 *  · 预设档位 50 / 100 / 200 / 500 —— 上限有明确的舒适区，预设让常用值一键可达；
 *  · 滑杆用于取预设之间的任意值，范围与 MessageStore 的合法区间一致；
 *  · 滑杆**只在松手时**写入 DataStore（onValueChangeFinished），
 *    否则拖动过程中每一帧都会写盘。
 *
 * 生效链路：写入 AppPreferences → AppContainer 中的收集器调用 MessageStore.setLimit()
 *        → 内部立即执行 trimByCountLocked()，因此超出的旧消息会被当场清理。
 */
@Composable
private fun MessageLimitSection(
    limit: Int,
    onChangeLimit: (Int) -> Unit,
    retentionHours: Int,
    onChangeRetention: (Int) -> Unit,
) {
    val c = LocalAppColors.current
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "消息上限与保留")
        Spacer(Modifier.height(8.dp))
        NtCaption(
            text = "当前：保留最近 " + retentionLabel(retentionHours) +
                "，最多 " + limit + " 条。超出部分会被立即清理。",
        )

        Spacer(Modifier.height(14.dp))
        Text(text = "条数上限", color = c.textPrimary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIMIT_PRESETS.forEach { preset ->
                NtButton(
                    text = preset.toString(),
                    // 选中档位用强调色描边，未选中用普通边框 —— 一眼看出当前值
                    accent = limit == preset,
                    onClick = { onChangeLimit(preset) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 本地状态：拖动时只改本地值，松手才写盘
        var sliderValue by remember(limit) { mutableStateOf(limit.toFloat()) }
        Slider(
            value = sliderValue,
            onValueChange = { v -> sliderValue = v },
            valueRange = MessageStore.MIN_LIMIT.toFloat()..MessageStore.MAX_LIMIT.toFloat(),
            onValueChangeFinished = { onChangeLimit(sliderValue.roundToInt()) },
            modifier = Modifier.fillMaxWidth(),
        )
        NtCaption(
            text = "可调范围 " + MessageStore.MIN_LIMIT + " ~ " + MessageStore.MAX_LIMIT + " 条，松手即生效",
        )

        Spacer(Modifier.height(14.dp))
        NtDivider()
        Spacer(Modifier.height(14.dp))
        Text(text = "保留时长", color = c.textPrimary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RETENTION_PRESETS.forEach { hours ->
                NtButton(
                    text = retentionLabel(hours),
                    accent = retentionHours == hours,
                    onClick = { onChangeRetention(hours) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        NtCaption(
            text = "只保留这段时间内的消息，更早的会被自动清理；配置数据不受影响。",
        )
    }
}

/**
 * 点击行为分区。
 *
 * 目前只有一项，但它决定了一个关键体感：点击消息跳转后，
 * 已读项是否立即从悬浮窗列表消失。关掉它则保留全部消息供回看。
 */
@Composable
private fun ClickBehaviorSection(
    clearReadOnTap: Boolean,
    onChange: (Boolean) -> Unit,
) {
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "点击行为")
        Spacer(Modifier.height(6.dp))
        NtSwitchRow(
            title = "点击后自动清理已读",
            subtitle = "点击消息跳转后，该条立即从悬浮窗列表消失，避免已读消息压住新消息",
            checked = clearReadOnTap,
            onCheckedChange = onChange,
        )
    }
}

/**
 * 可点击的导航行：设置项 → 子页面。
 *
 * 为什么必须有它：这三个区块之前用的是 PlaceholderSection（纯文本、无点击），
 * 用户点上去毫无反应 —— 这是「设置页交互死锁」的直接原因。
 */
@Composable
private fun NtNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    NtPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = c.textPrimary, fontSize = 14.sp)
                NtCaption(text = subtitle)
            }
            // 右侧箭头：让「可点击」这件事在视觉上明确
            Text(text = "›", color = c.textSecondary, fontSize = 20.sp)
        }
    }
}

/** 条数上限预设档位。 */
private val LIMIT_PRESETS = listOf(50, 100, 200, 500)

/** 保留时长预设档位（小时）：24 小时 / 3 天 / 7 天。 */
private val RETENTION_PRESETS = listOf(24, 72, 168)

/** 时长的人话描述，例如 72 → 「3 天」。 */
private fun retentionLabel(hours: Int): String =
    if (hours >= 24 && hours % 24 == 0) (hours / 24).toString() + " 天" else hours.toString() + " 小时"

/**
 * 尚未实现的区块占位（第 6 步起逐个替换为真实内容）。
 *
 * 用统一组件而不是各写一段文案：避免「有的区块有说明、有的没有」的不一致。
 */
@Composable
private fun PlaceholderSection(title: String, note: String) {
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        Spacer(Modifier.height(6.dp))
        NtCaption(text = note)
    }
}

/**
 * 诊断分区：**只在用户主动点击时**才读取并展示崩溃记录。
 *
 * 为什么不放在启动路径（血泪教训）：
 *  上一版在 MainActivity 的组合期读取并渲染崩溃页，一旦该页自身抛异常，
 *  就变成「每次启动必崩」的死循环，用户连主界面都进不去。
 *  现在读取与渲染都由这里的一次点击触发，最坏情况也只是这一屏出问题。
 *
 * 同时给出日志文件的**绝对路径**，方便 App 完全进不去时用文件管理器/adb 取走。
 */
@Composable
private fun CrashDiagnosticsSection() {
    val context = LocalContext.current
    val c = LocalAppColors.current
    var report by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }
    var paths by remember { mutableStateOf<Pair<String, String?>?>(null) }

    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "诊断")
        Spacer(Modifier.height(6.dp))
        NtCaption(text = "崩溃详情只在你点击时读取，不参与启动流程。")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NtButton(
                text = "查看上次崩溃",
                onClick = {
                    // 读取包在 runCatching 里：失败也只是「没有记录」
                    report = runCatching { CrashRecorder.readPersisted(context) }.getOrNull()
                    showReport = report != null
                    if (report == null) NtToast.show(context, "没有崩溃记录")
                },
            )
            NtButton(
                text = "显示日志路径",
                accent = false,
                onClick = {
                    paths = runCatching {
                        CrashRecorder.internalLogPath(context) to CrashRecorder.externalLogPath(context)
                    }.getOrNull()
                },
            )
        }
        if (paths != null) {
            Spacer(Modifier.height(8.dp))
            NtCaption(text = "内部：" + paths!!.first)
            NtCaption(text = "外部：" + (paths!!.second ?: "(外部存储不可用)"))
        }
        if (showReport && report != null) {
            Spacer(Modifier.height(10.dp))
            NtDivider()
            Spacer(Modifier.height(10.dp))
            // 用安全包装渲染：即使堆栈内容异常导致渲染失败，也只降级为纯文字
            SafeCrashReportScreen(
                report = report!!,
                onDismiss = {
                    runCatching { CrashRecorder.clear(context) }
                    showReport = false
                    report = null
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        NtCaption(text = "日志标签（adb logcat -s Nuntra/CrashRecorder）")
    }
}

/**
 * 权限引导（可操作）。
 *
 * 设计要点：
 *  · **每一项都有可点的跳转按钮**，而不是只显示「未授权」让用户自己找；
 *  · 状态在**每次回前台（resumeTick 变化）**重新读取，用户在系统设置里授权后
 *    切回来立刻显示「已授权」，不需要重启应用；
 *  · 全部跳转都经过 PermissionNavigator —— 那里有「主入口失败 → 应用详情页 →
 *    系统设置首页」三级兜底，且任何异常都不外抛，不会因为点一下跳转而闪退。
 */
@Composable
private fun PermissionSection(
    readiness: ReadinessSnapshot,
    resumeTick: Int,
    onRefreshReadiness: () -> Unit,
) {
    val context = LocalContext.current
    val c = LocalAppColors.current

    // resumeTick 变化时重算一次「快照」，用于高亮「刚刚变成已授权」的项。
    // 真正读取权限的是 MainActivity 传入的 readiness；这里只做呈现。
    val current = remember(resumeTick, readiness) { readiness }

    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "权限引导")
        Spacer(Modifier.height(4.dp))
        NtCaption(text = "带「必开」的两项缺失时应用无法工作；其余为建议项。")
        Spacer(Modifier.height(10.dp))

        PermissionRowItem(
            title = "通知使用权",
            required = true,
            granted = current.notificationAccessGranted,
            description = "读取微信 / QQ / 短信的通知内容。系统级授权，没有运行时弹窗，必须手动开启。",
            onOpen = { PermissionNavigator.openNotificationListenerSettings(context) },
        )
        PermissionRowItem(
            title = "悬浮窗权限",
            required = true,
            granted = current.overlayGranted,
            description = "显示可拖动的悬浮终端。缺少它时服务能运行但窗口画不出来。",
            onOpen = { PermissionNavigator.openOverlaySettings(context) },
        )
        PermissionRowItem(
            title = "通知权限",
            required = false,
            granted = current.notificationsGranted,
            description = "决定前台服务那条常驻通知能否显示（Android 13+）。",
            onOpen = { PermissionNavigator.openNotificationSettings(context) },
        )
        PermissionRowItem(
            title = "电池优化白名单",
            required = false,
            granted = current.batteryOptimizationIgnored,
            description = "降低被系统在后台清理的概率。一加 / ColorOS 上尤其重要。",
            onOpen = { PermissionNavigator.requestIgnoreBatteryOptimizations(context) },
        )

        Spacer(Modifier.height(10.dp))
        NtDivider()
        Spacer(Modifier.height(10.dp))

        // ── 一加 / ColorOS 专属：厂商页面无法保证跳转成功，因此给文字路径 ──
        SectionHeader(title = "一加 / ColorOS 保活")
        Spacer(Modifier.height(6.dp))
        NtCaption(
            text = "ColorOS 的「自启动」「后台活动」没有公开 API，跳转可能失败。" +
                "若下面的按钮打不开，请手动按路径设置：" +
                "设置 → 应用 → 应用管理 → 找到 Nuntra → 允许自启动 / 允许后台活动。",
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NtButton(
                text = "尝试打开自启动设置",
                accent = false,
                onClick = { PermissionNavigator.openColorOsAutoStart(context) },
            )
        }
        Spacer(Modifier.height(6.dp))
        NtCaption(
            text = "提示：本应用不申请也未使用「后台弹出界面」这类权限；" +
                "悬浮窗是否可显示由系统按 SYSTEM_ALERT_WINDOW（即上面的悬浮窗权限）判定。",
            color = c.textSecondary,
        )

        Spacer(Modifier.height(10.dp))
        NtDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtCaption(
                text = if (current.allCriticalGranted) "核心权限已就绪" else "缺少核心权限，应用无法工作",
                color = if (current.allCriticalGranted) c.sourceWeChat else c.warning,
            )
            Spacer(Modifier.weight(1f))
            NtButton(text = "重新检测", onClick = onRefreshReadiness)
        }
    }
}

/** 单条权限：状态点 + 名称 + 必开标记 + 说明 + 跳转按钮。 */
@Composable
private fun PermissionRowItem(
    title: String,
    required: Boolean,
    granted: Boolean,
    description: String,
    onOpen: () -> Unit,
) {
    val c = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (granted) c.sourceWeChat else c.muted),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = title, color = c.textPrimary, fontSize = 14.sp)
            if (required) {
                Spacer(Modifier.width(6.dp))
                Text(text = "必开", color = c.warning, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (granted) "已授权" else "未授权",
                color = if (granted) c.sourceWeChat else c.warning,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        NtCaption(text = description)
        Spacer(Modifier.height(6.dp))
        NtButton(
            text = if (granted) "打开系统设置" else "去授权",
            onClick = onOpen,
            accent = !granted,
        )
    }
}

/** 总监控开关（总闸）。放在设置页最顶部，与首页的状态一致。 */
@Composable
private fun MasterSwitchSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "总监控开关")
        Spacer(Modifier.height(6.dp))
        NtSwitchRow(
            title = if (enabled) "正在运行中" else "已暂停监控（省电模式）",
            subtitle = "总闸。关闭后监听服务收到任何通知都立即丢弃，前台服务与悬浮窗同时停止。",
            checked = enabled,
            onCheckedChange = onToggle,
            // 与首页同一个开关：两处都出 Toast 才能确认是哪一层的问题
            debugToast = true,
        )
        if (!enabled) {
            Spacer(Modifier.height(6.dp))
            NtCaption(
                text = "当前处于省电模式：即使下方的监控范围全部打开，也不会有任何监听行为。",
                color = c.warning,
            )
        }
    }
}

/**
 * 监控范围（四个分闸）。
 *
 * 总闸关闭时不禁用这些开关 —— 用户可以先配置好范围，再打开总闸；
 * 禁用会让用户以为设置坏了。但会明确提示当前总闸关闭、分闸暂不生效。
 */
@Composable
private fun MonitorScopeSection(
    masterSwitchEnabled: Boolean,
    wechat: Boolean,
    qq: Boolean,
    weCom: Boolean,
    tim: Boolean,
    onToggleWechat: (Boolean) -> Unit,
    onToggleQq: (Boolean) -> Unit,
    onToggleWeCom: (Boolean) -> Unit,
    onToggleTim: (Boolean) -> Unit,
) {
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "监控范围")
        Spacer(Modifier.height(4.dp))
        NtSwitchRow(
            title = "微信",
            subtitle = "监听普通微信消息",
            checked = wechat,
            onCheckedChange = onToggleWechat,
        )
        NtSwitchRow(
            title = "QQ",
            subtitle = "监听普通 QQ 消息（含 QQ 轻聊版）",
            checked = qq,
            onCheckedChange = onToggleQq,
        )
        NtSwitchRow(
            title = "企业微信",
            subtitle = "监听企业微信消息（默认关闭，建议需要时开启）",
            checked = weCom,
            onCheckedChange = onToggleWeCom,
        )
        NtSwitchRow(
            title = "TIM",
            subtitle = "监听 TIM 消息（默认关闭，建议需要时开启）",
            checked = tim,
            onCheckedChange = onToggleTim,
        )
        Spacer(Modifier.height(8.dp))
        NtDivider()
        Spacer(Modifier.height(8.dp))
        NtCaption(
            text = "关闭后，对应 App 的通知将完全不会被收纳到悬浮窗，可省电并减少干扰。" +
                "总开关是总闸、这里是分闸：总闸关闭时，分闸全部不生效。",
        )
        Spacer(Modifier.height(8.dp))
        NtPanel(elevated = true, contentPadding = PaddingValues(10.dp)) {
            NtCaption(
                text = "腾讯系办公 App 过滤提示：企业微信与 TIM 的通知量通常很大，" +
                    "建议配合「关注人」使用 —— 只关注需要的人，其余消息不会进入悬浮窗，避免过载。",
            )
        }
        if (!masterSwitchEnabled) {
            Spacer(Modifier.height(8.dp))
            NtCaption(
                text = "提示：总监控开关当前为关闭状态，以上范围暂不生效。",
                color = LocalAppColors.current.warning,
            )
        }
    }
}

/** 短信监控：只用通知监听，不申请短信权限。 */
@Composable
private fun SmsSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    keywords: Set<String>,
    onAddKeyword: (String) -> Unit,
    onRemoveKeyword: (String) -> Unit,
    onResetKeywords: () -> Unit,
    resolvedPackages: Set<String>,
    needsManualSelection: Boolean,
) {
    var draft by remember { mutableStateOf("") }
    val c = LocalAppColors.current
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "短信监控")
        Spacer(Modifier.height(4.dp))
        NtSwitchRow(
            title = "启用短信监控",
            subtitle = "只用通知监听，不申请 RECEIVE_SMS / READ_SMS 权限",
            checked = enabled,
            onCheckedChange = onToggle,
        )
        Spacer(Modifier.height(8.dp))
        NtCaption(text = "只有正文或标题命中下列任一关键词的短信才会入库；其余直接丢弃。")
        Spacer(Modifier.height(8.dp))
        if (keywords.isEmpty()) {
            NtCaption(
                text = "关键词为空：当前会收纳全部短信。建议至少保留一个关键词。",
                color = c.warning,
            )
        } else {
            KeywordChips(keywords = keywords.sorted(), onRemove = onRemoveKeyword)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("新增关键词") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.border,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary,
                    focusedLabelColor = c.accent,
                    unfocusedLabelColor = c.textSecondary,
                    cursorColor = c.accent,
                ),
            )
            NtButton(
                text = "添加",
                enabled = draft.isNotBlank(),
                onClick = {
                    onAddKeyword(draft)
                    draft = ""
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NtButton(text = "恢复默认关键词", onClick = onResetKeywords, accent = false)
        }
        Spacer(Modifier.height(10.dp))
        NtDivider()
        Spacer(Modifier.height(8.dp))
        NtCaption(text = "识别的短信 App：" + if (resolvedPackages.isEmpty()) "尚未识别" else resolvedPackages.joinToString(", "))
        if (needsManualSelection) {
            NtCaption(
                text = "未能自动识别本机短信 App。部分定制系统的短信包名不在已知名单内，" +
                    "请在下方「权限引导」确认通知使用权已开启，或稍后由你在设置中手动指定。",
                color = c.warning,
            )
        }
    }
}

/** 关键词胶囊列表：小圆角、细边框、点击删除。 */
@Composable
private fun KeywordChips(keywords: List<String>, onRemove: (String) -> Unit) {
    val c = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keywords.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { keyword ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, c.border, RoundedCornerShape(6.dp))
                            .background(c.panelElevated)
                            .clickable { onRemove(keyword) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(text = keyword + "  ×", color = c.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSection(
    themes: List<AppColors>,
    currentId: String,
    onSelect: (String) -> Unit,
) {
    val c = LocalAppColors.current
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "主题")
        Spacer(Modifier.height(10.dp))
        themes.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { theme ->
                    ThemeSwatch(
                        theme = theme,
                        selected = theme.id == currentId,
                        onClick = { onSelect(theme.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        NtCaption(text = "切换后设置页与悬浮窗同时生效，无需重启服务。")
    }
}

@Composable
private fun ThemeSwatch(
    theme: AppColors,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NtPanel(
        modifier = modifier,
        elevated = true,
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwatchDot(theme.background)
            SwatchDot(theme.panel)
            SwatchDot(theme.accent)
            Spacer(Modifier.weight(1f))
            NtCaption(
                text = theme.id,
                color = if (selected) theme.accent else theme.textSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        NtButton(
            text = theme.displayName + if (selected) " · 当前" else "",
            onClick = onClick,
            accent = selected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 单个色块：18dp 圆点 + 细边框。 */
@Composable
private fun SwatchDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(1.dp, LocalAppColors.current.border, RoundedCornerShape(4.dp)),
    )
}


