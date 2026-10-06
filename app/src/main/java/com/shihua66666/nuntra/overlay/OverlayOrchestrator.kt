package com.shihua66666.nuntra.overlay

import android.content.Context
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.perm.PermissionChecker
import com.shihua66666.nuntra.perm.PermissionNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 总开关 → 前台服务 / 悬浮窗 的**唯一**编排入口。
 *
 * 为什么需要单独一层：
 *  总开关是用户最常点的按钮，而它触发的链路最长（写 DataStore → 启动前台服务 → 挂窗口）。
 *  任何一环在未就绪状态下都可能抛异常（未授权、服务被系统限制、初始化中）。
 *  把这些判断与兜底集中在这里，UI 就只负责「调一个方法 + 显示结果」。
 *
 * 行为约定：
 *  · 缺前置条件（未授权）→ **不启动服务**，Toast 说明 + 跳权限引导，开关回滚为关闭；
 *  · 启动过程抛异常 → 吞掉并提示，开关回滚，绝不让异常冒泡到 UI 线程；
 *  · 关闭总开关永远安全（不依赖任何前置条件）。
 */
object OverlayOrchestrator {

    private const val TAG = "OverlayOrchestrator"

    /**
     * 应用总开关。
     *
     * 调用方应在 IO/主线程皆可；内部自行处理阻塞与异常。
     *
     * @param onFinished 结果回调（在调用线程上触发），用于 UI 刷新权限状态；
     *                   null 表示不关心（例如服务内部的幂等调用）。
     */
    fun setMasterSwitch(
        context: Context,
        enabled: Boolean,
        scope: CoroutineScope,
        onFinished: (() -> Unit)? = null,
    ) {
        runCatching {
            if (!enabled) {
                // 关闭路径不做任何前置检查：关掉永远安全
                OverlayService.stop(context)
                onFinished?.invoke()
                return
            }

            // ── 前置条件检查（缺失时只提示 + 引导，不启动服务）──
            if (!PermissionChecker.hasNotificationAccess(context)) {
                NtToast.show(context, "请先授予「通知使用权」")
                PermissionNavigator.openNotificationListenerSettings(context)
                rollbackSwitch(scope)
                onFinished?.invoke()
                return
            }
            if (!PermissionChecker.canDrawOverlays(context)) {
                NtToast.show(context, "请先授予「悬浮窗权限」")
                PermissionNavigator.openOverlaySettings(context)
                rollbackSwitch(scope)
                onFinished?.invoke()
                return
            }

            // ── 启动 ──
            try {
                OverlayService.start(context)
                // 通知权限没给：前台通知可能不显示，但服务照常运行，只提示不阻断
                if (!PermissionChecker.hasPostNotifications(context)) {
                    NtToast.show(context, "未授予通知权限：常驻通知可能不显示")
                }
                Logx.i(TAG, "总开关开启：已请求启动前台服务")
                // 成功路径才给正反馈；失败路径在下面的 catch 里单独提示
                NtToast.show(context, "已开启监控")
            } catch (tr: Throwable) {
                // Android 12+ 的后台启动限制会抛 ForegroundServiceStartNotAllowedException。
                // ★ 不静默：把异常类名与消息直接弹给用户（细节仍在日志里）。
                Logx.e(TAG, "启动前台服务失败", tr)
                NtToast.show(
                    context,
                    "启动服务失败：" + tr.javaClass.simpleName + " / " + (tr.message ?: "无消息"),
                )
                rollbackSwitch(scope)
            }
            onFinished?.invoke()
        }.onFailure { tr ->
            // ★ 同样不静默：编排器自身任何异常都要让用户看到
            Logx.e(TAG, "setMasterSwitch 异常", tr)
            NtToast.show(
                context,
                "开关操作异常：" + tr.javaClass.simpleName + " / " + (tr.message ?: "无消息"),
            )
            runCatching { onFinished?.invoke() }
        }
    }

    /**
     * 开关回滚：把持久化的状态改回 false。
     *
     * 不做回滚的后果很严重 —— 开关显示「开」但服务没起来，
     * 用户会反复点、反复失败，且下次启动 App 会误以为应当自动运行。
     */
    private fun rollbackSwitch(scope: CoroutineScope, reason: String = "操作未成功") {
        scope.launch {
            runCatching { ServiceLocator.appContainerOrNull?.appPreferences?.setMasterSwitchEnabled(false) }
                .onFailure { tr ->
                    // 回滚失败会让状态卡在「开」，必须让用户知道
                    Logx.e(TAG, "回滚开关失败", tr)
                    NtToast.show(null, "回滚开关状态失败：" + tr.javaClass.simpleName)
                }
            Logx.i(TAG, "开关已回滚为关闭（原因：" + reason + "）")
        }
    }
}
