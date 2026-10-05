package com.shihua66666.nuntra.model

/**
 * 就绪状态快照：首页与悬浮窗都据此决定显示什么。
 *
 * 全部为纯数据，可直接交给 Compose 渲染；
 * 任何字段为 false 都意味着某条链路不可用，但**都不应导致崩溃**。
 */
data class ReadinessSnapshot(
    /** 通知使用权（BIND_NOTIFICATION_LISTENER_SERVICE）：核心前提。 */
    val notificationAccessGranted: Boolean = false,
    /** 悬浮窗权限（SYSTEM_ALERT_WINDOW）。 */
    val overlayGranted: Boolean = false,
    /** 通知权限（Android 13+ 运行时）：决定前台服务常驻通知能否显示。 */
    val notificationsGranted: Boolean = false,
    /** 是否已加入电池优化白名单。 */
    val batteryOptimizationIgnored: Boolean = false,
    /** 悬浮窗前台服务是否在运行。 */
    val serviceRunning: Boolean = false,
) {
    /** 两条硬前提：缺任一条，整个应用都不会工作。 */
    val allCriticalGranted: Boolean get() = notificationAccessGranted && overlayGranted
}
