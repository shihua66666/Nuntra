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

            PlaceholderSection(
                title = "标签管理",
                note = "新增 / 重命名 / 改色 / 拖拽排序 / 删除迁移，以及每个标签的特殊关注与静音开关。",
            )
            PlaceholderSection(
                title = "关注人与关键词",
                note = "关注人绑定标签；关键词命中无对应关注人时归入兜底标签。",
            )
            PlaceholderSection(
                title = "提醒音与重复提醒",
                note = "特殊关注标签提醒音试听/更换；重复提醒间隔与次数。",
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
            PlaceholderSection(
                title = "消息上限与保留",
                note = "默认保留最近 24 小时，条数上限默认 200，可调。",
            )

            NtPanel(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = "权限引导")
                Spacer(Modifier.height(8.dp))
                NtCaption(text = "通知使用权：" + grantedText(readiness.notificationAccessGranted))
                NtCaption(text = "悬浮窗权限：" + grantedText(readiness.overlayGranted))
                NtCaption(text = "通知权限：" + grantedText(readiness.notificationsGranted))
                NtCaption(text = "电池优化白名单：" + grantedText(readiness.batteryOptimizationIgnored))
                Spacer(Modifier.height(8.dp))
                NtDivider()
                Spacer(Modifier.height(8.dp))
                NtCaption(text = "各权限的跳转按钮将在权限引导模块（第 6/7 步）接入。")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

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


private fun grantedText(granted: Boolean): String = if (granted) "已授权" else "未授权"
