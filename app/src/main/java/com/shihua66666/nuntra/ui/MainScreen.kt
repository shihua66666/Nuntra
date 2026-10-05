package com.shihua66666.nuntra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.core.TimeFmt
import com.shihua66666.nuntra.model.ReadinessSnapshot
import com.shihua66666.nuntra.ui.components.NtBody
import com.shihua66666.nuntra.ui.components.NtButton
import com.shihua66666.nuntra.ui.components.NtCaption
import com.shihua66666.nuntra.ui.components.NtDivider
import com.shihua66666.nuntra.ui.components.NtMono
import com.shihua66666.nuntra.ui.components.NtPanel
import com.shihua66666.nuntra.ui.components.NtStatusDot
import com.shihua66666.nuntra.ui.components.NtSwitchRow
import com.shihua66666.nuntra.ui.components.SectionHeader
import com.shihua66666.nuntra.ui.theme.AppColors
import com.shihua66666.nuntra.ui.theme.LocalAppColors

/**
 * 首页：只做三件事 —— 显示就绪状态、引导开权限、进入设置。
 *
 * 刻意不做「消息列表」：消息只在悬浮窗里看。
 * 原因是本应用的定位是悬浮终端，首页是「启动台」，不是主界面。
 *
 * 总开关放在首页最显眼的位置：它是总闸，用户日常最常动的就是它。
 */
@Composable
fun MainScreen(
    colors: AppColors,
    readiness: ReadinessSnapshot,
    masterSwitchEnabled: Boolean,
    messageCount: Int,
    unreadCount: Int,
    latestMessageAt: Long?,
    serviceRunning: Boolean,
    onOpenSettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onClearMessages: () -> Unit,
    onToggleMasterSwitch: (Boolean) -> Unit,
) {
    val c = LocalAppColors.current
    Scaffold(
        containerColor = c.background,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppHeader(colors)
            MasterSwitchPanel(
                enabled = masterSwitchEnabled,
                serviceRunning = serviceRunning,
                onToggle = onToggleMasterSwitch,
            )
            ReadinessPanel(readiness = readiness, onOpenSettings = onOpenSettings)
            ServicePanel(
                serviceRunning = serviceRunning,
                messageCount = messageCount,
                unreadCount = unreadCount,
                latestMessageAt = latestMessageAt,
                masterSwitchEnabled = masterSwitchEnabled,
                onStartService = onStartService,
                onStopService = onStopService,
                onClearMessages = onClearMessages,
            )
            NtCaption(
                text = "本应用只读取通知栏内容，不读取微信/QQ 数据库、不自动回复、不破解聊天记录。",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppHeader(colors: AppColors) {
    val c = LocalAppColors.current
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Nuntra",
                color = c.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "通知中继终端",
                color = c.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        NtMono(text = "v" + "0.1.0", color = c.muted)
    }
}

@Composable
private fun MasterSwitchPanel(
    enabled: Boolean,
    serviceRunning: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val c = LocalAppColors.current
    // 先在局部变量里算好文案：把 if/else 直接写进命名参数在部分 Kotlin 版本上
    // 会触发解析歧义（CI 上曾报 Syntax error: Unexpected tokens）。
    val title = if (enabled) "正在运行中" else "已暂停监控（省电模式）"
    val subtitle = if (enabled) {
        "监听已开启：符合监控范围的通知会被收纳到悬浮窗。"
    } else {
        "总闸关闭：监听服务收到通知会立即丢弃，不做任何解析；前台服务与悬浮窗一并停止。"
    }
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "总监控开关")
        Spacer(Modifier.height(6.dp))
        NtSwitchRow(
            title = title,
            subtitle = subtitle,
            checked = enabled,
            onCheckedChange = onToggle,
        )
        Spacer(Modifier.height(8.dp))
        NtDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtStatusDot(ok = enabled && serviceRunning)
            Spacer(Modifier.width(8.dp))
            NtCaption(
                text = when {
                    !enabled -> "省电状态：不会监听任何通知"
                    serviceRunning -> "前台服务运行中，悬浮窗应可见"
                    else -> "已开启但服务未运行：点下方「启动服务」"
                },
                color = when {
                    !enabled -> c.textSecondary
                    serviceRunning -> c.sourceWeChat
                    else -> c.warning
                },
            )
        }
    }
}

@Composable
private fun ReadinessPanel(readiness: ReadinessSnapshot, onOpenSettings: () -> Unit) {
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "就绪状态")
        Spacer(Modifier.height(10.dp))
        PermissionRow("通知使用权", readiness.notificationAccessGranted, critical = true)
        PermissionRow("悬浮窗权限", readiness.overlayGranted, critical = true)
        PermissionRow("通知权限", readiness.notificationsGranted, critical = false)
        PermissionRow("电池优化白名单", readiness.batteryOptimizationIgnored, critical = false)
        Spacer(Modifier.height(12.dp))
        NtDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtCaption(
                text = if (readiness.allCriticalGranted) "核心权限已就绪" else "缺少核心权限时无法工作",
                color = if (readiness.allCriticalGranted) LocalAppColors.current.sourceWeChat else LocalAppColors.current.warning,
            )
            Spacer(Modifier.weight(1f))
            NtButton(text = "权限与设置", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, critical: Boolean) {
    val c = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtStatusDot(ok = granted)
        Spacer(Modifier.width(10.dp))
        NtBody(text = label)
        Spacer(Modifier.weight(1f))
        NtMono(
            text = if (granted) "已授权" else "未授权",
            color = when {
                granted -> c.sourceWeChat
                critical -> c.warning
                else -> c.textSecondary
            },
        )
    }
}

@Composable
private fun ServicePanel(
    serviceRunning: Boolean,
    messageCount: Int,
    unreadCount: Int,
    latestMessageAt: Long?,
    masterSwitchEnabled: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onClearMessages: () -> Unit,
) {
    val c = LocalAppColors.current
    NtPanel(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "悬浮终端")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtStatusDot(ok = serviceRunning)
            Spacer(Modifier.width(10.dp))
            NtBody(text = if (serviceRunning) "服务运行中" else "服务未运行")
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtCaption(text = "总开关", modifier = Modifier.width(70.dp))
            NtMono(
                text = if (masterSwitchEnabled) "开启" else "关闭",
                color = if (masterSwitchEnabled) c.sourceWeChat else c.textSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtCaption(text = "已接收", modifier = Modifier.width(56.dp))
            NtMono(text = messageCount.toString(), color = c.textPrimary, fontSize = 14.sp)
            Spacer(Modifier.width(18.dp))
            NtCaption(text = "未读", modifier = Modifier.width(48.dp))
            NtMono(text = unreadCount.toString(), color = c.accent, fontSize = 14.sp)
        }
        if (latestMessageAt != null) {
            Spacer(Modifier.height(6.dp))
            Row {
                NtCaption(text = "最近一条", modifier = Modifier.width(70.dp))
                NtMono(text = TimeFmt.relative(latestMessageAt))
            }
        }
        Spacer(Modifier.height(12.dp))
        NtDivider()
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (serviceRunning) {
                NtButton(text = "停止服务", onClick = onStopService, accent = false)
            } else {
                NtButton(
                    text = "启动服务",
                    onClick = onStartService,
                    enabled = masterSwitchEnabled,
                )
            }
            NtButton(text = "清空消息", onClick = onClearMessages, accent = false, enabled = messageCount > 0)
        }
        if (!masterSwitchEnabled) {
            Spacer(Modifier.height(8.dp))
            NtCaption(
                text = "总开关关闭时无法启动服务：请先打开上方的总监控开关。",
                color = c.warning,
            )
        }
    }
}
