package com.shihua66666.nuntra.perm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.NtToast

/**
 * 权限申请与系统设置跳转。
 *
 * 设计原则：**每个入口都必须有兜底**。三方的 Custom ROM（ColorOS / MIUI / EMUI…）
 * 常把系统设置页改名或移除，直接 startActivity 会抛 ActivityNotFoundException，
 * 那是最典型的「点一下就闪退」。因此：
 *
 *   主入口 → 失败则降级到「应用详情页」→ 再失败则降级到「系统设置首页」→ 都不行就 Toast
 *
 * 并且返回 Boolean 告诉调用方「是否真的跳成功了」，便于 UI 给出下一步指引。
 */
object PermissionNavigator {

    private const val TAG = "PermissionNavigator"

    // ── 标准权限 ─────────────────────────────────────────────────

    /**
     * 通知使用权设置页。
     *
     * Android 8.0 以下没有 ACTION_NOTIFICATION_LISTENER_SETTINGS，需要兜底到详情页。
     * 打开后请让用户在列表里勾选本应用（这是系统级授权，无法用运行时弹窗申请）。
     */
    fun openNotificationListenerSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (startSafely(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return true
        }
        return openAppDetails(context)
    }

    /**
     * 悬浮窗权限设置页。
     *
     * 一加 / ColorOS 的「后台弹出界面」不是一个可查询的独立权限；
     * 对悬浮窗而言，系统实际用 SYSTEM_ALERT_WINDOW 判定。因此这里就是正确的入口。
     */
    fun openOverlaySettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName),
        )
        if (startSafely(context, intent)) return true
        // 个别 ROM 不接受带 package 的 URI，退化成不带参数的入口再试一次
        if (startSafely(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) return true
        return openAppDetails(context)
    }

    /** 电池优化白名单申请页。 */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + context.packageName))
        if (startSafely(context, intent)) return true
        if (startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
        return openAppDetails(context)
    }

    /** 打开通知设置（用于「通知权限被拒 / 渠道被关」）。 */
    fun openNotificationSettings(context: Context): Boolean {
        if (startSafely(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))) return true
        return openAppDetails(context)
    }

    /** 应用详情页：几乎所有 ROM 都有的最后兜底。 */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName))
        if (startSafely(context, intent)) return true
        if (startSafely(context, Intent(Settings.ACTION_SETTINGS))) return true
        NtToast.show(context, "无法打开系统设置，请手动进入设置页")
        return false
    }

    // ── 厂商专属（尽力而为，全部允许失败） ────────────────────────

    /**
     * 一加 / ColorOS 的「自启动」「后台活动」等厂商页面。
     *
     * 这些页面**没有公开 API**，ComponentName 各版本不一致，因此在部分机型上必然失败。
     * 这里逐个尝试候选组件，只要有一个成功就返回；全部失败则降级到应用详情页，
     * 并提示用户按文字路径自己找 —— 不给用户「点了没反应」的体验，也不假装能做到。
     */
    fun openColorOsAutoStart(context: Context): Boolean {
        val candidates = listOf(
            // 一加 / ColorOS 常见组件（按新→旧排列）
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        )
        for (component in candidates) {
            val intent = Intent().setComponent(component)
            if (startSafely(context, intent)) {
                Logx.i(TAG, "已跳转 ColorOS 自启动页：" + component.flattenToShortString())
                return true
            }
        }
        Logx.w(TAG, "ColorOS 自启动页全部候选失败，降级到应用详情页")
        openAppDetails(context)
        NtToast.show(context, "请手动：设置 → 应用 → 自启动 / 后台活动")
        return false
    }

    // ── 内部 ─────────────────────────────────────────────────────

    /** 统一的跳转包装：任何异常都吞掉并返回 false，绝不因为跳设置页而崩。 */
    private fun startSafely(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse { tr ->
        Logx.swallow(TAG, "startSafely:" + intent.action, tr)
        false
    }
}
