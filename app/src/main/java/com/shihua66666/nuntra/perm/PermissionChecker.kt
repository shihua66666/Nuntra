package com.shihua66666.nuntra.perm

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.model.ReadinessSnapshot
import com.shihua66666.nuntra.notify.NuntraNotificationListener

/**
 * 权限与关键开关的**只读**检测。
 *
 * 刻意只检测、不发起申请：申请要跳设置页或弹运行时对话框，那些在 PermissionNavigator 里。
 * 本类保持纯函数，可以被 onResume、首页、设置页随意调用。
 *
 * 全部检测都包了 runCatching：ColorOS / ColorOS 类定制系统在权限被回收后，
 * 个别 API 可能抛 SecurityException，而「查个权限把应用查崩」是绝对不能接受的。
 */
object PermissionChecker {

    /** 通知使用权是否已授予。 */
    fun hasNotificationAccess(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
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
     * 注意：返回 false 只影响「前台服务常驻通知能否显示」，不影响服务本身能否启动。
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

    /**
     * 通知监听服务是否**已被系统绑定**。
     *
     * 与 [hasNotificationAccess] 的区别：
     *  权限已授予 ≠ 服务还活着。系统（尤其 ColorOS）会在某些场景解绑服务，
     *  此时权限仍在、但收不到任何通知。这个检测用来把这种「假活」状态暴露给用户。
     */
    fun isListenerServiceBound(context: Context): Boolean = runCatching {
        val component = ComponentName(context, NuntraNotificationListener::class.java)
        val enabled = context.packageManager.getComponentEnabledSetting(component)
        // 只要不是显式禁用，就认为可能被绑定；真正的「活着」由权限检测兜底
        enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(true)

    /**
     * 汇总快照。
     *
     * @param serviceRunning 悬浮窗前台服务是否在运行（由调用方传入，本类不查询服务状态）
     */
    fun snapshot(
        context: Context,
        /**
         * 悬浮窗是否真的已挂载。
         *
         * ★ 默认值不再是写死的 false：改为读取服务的真实状态。
         *   之前 MainActivity 传的是 masterSwitchEnabled（开关是否打开），
         *   那只代表「用户想开」，不代表「窗口真的画出来了」—— 会误导用户。
         */
        serviceRunning: Boolean = com.shihua66666.nuntra.overlay.OverlayService.isWindowAttached,
    ): ReadinessSnapshot = ReadinessSnapshot(
        notificationAccessGranted = hasNotificationAccess(context),
        overlayGranted = canDrawOverlays(context),
        notificationsGranted = hasPostNotifications(context),
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
        serviceRunning = serviceRunning,
    )
}
