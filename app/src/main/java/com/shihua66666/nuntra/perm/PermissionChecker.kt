package com.shihua66666.nuntra.perm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.model.ReadinessSnapshot

/**
 * 权限与关键开关的**只读**检测。
 *
 * 刻意只是检测，不发起申请：申请动作有的要跳设置页、有的要运行时弹窗，
 * 那些放在 PermissionNavigator（第 6 步）里，本类保持纯函数、可随意调用。
 *
 * 全部检测都包了 runCatching：
 * ColorOS 等定制系统在权限被回收后，个别 API 可能抛 SecurityException，
 * 而我们绝不允许「查个权限把应用查崩」。
 */
object PermissionChecker {

    /** 通知使用权是否已授予。 */
    fun hasNotificationAccess(context: Context): Boolean = runCatching {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        enabled.contains(context.packageName)
    }.getOrElse { tr ->
        Logx.swallow("PermissionChecker", "hasNotificationAccess", tr)
        false
    }

    /** 悬浮窗权限是否已授予（Android 6+）。 */
    fun canDrawOverlays(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrElse { tr ->
        Logx.swallow("PermissionChecker", "canDrawOverlays", tr)
        false
    }

    /**
     * 通知权限是否已授予。
     *
     * Android 13 以下没有这个运行时权限，直接返回 true。
     * 注意：这里返回 false 只影响「前台服务常驻通知能否显示」，
     * 不影响服务本身能否启动。
     */
    fun hasPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrElse { tr ->
            Logx.swallow("PermissionChecker", "hasPostNotifications", tr)
            false
        }
    }

    /** 是否已被加入电池优化白名单。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrElse { tr ->
        Logx.swallow("PermissionChecker", "isIgnoringBatteryOptimizations", tr)
        false
    }

    /** 通知渠道是否被用户关闭（前台通知不显示的另一条路径）。 */
    fun isNotificationsEnabled(context: Context): Boolean = runCatching {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.areNotificationsEnabled() ?: true
    }.getOrElse { true }

    /** 汇总快照。第 2 步接入服务运行状态后 serviceRunning 会有真实值。 */
    fun snapshot(context: Context, serviceRunning: Boolean = false): ReadinessSnapshot = ReadinessSnapshot(
        notificationAccessGranted = hasNotificationAccess(context),
        overlayGranted = canDrawOverlays(context),
        notificationsGranted = hasPostNotifications(context),
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
        serviceRunning = serviceRunning,
    )
}
