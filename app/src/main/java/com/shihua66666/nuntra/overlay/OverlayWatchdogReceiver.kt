package com.shihua66666.nuntra.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shihua66666.nuntra.core.Logx

/**
 * 悬浮窗守护的广播入口。
 *
 * 两类触发：
 *  · [OverlayWatchdog.ACTION_WATCHDOG]：AlarmManager 每 5 分钟一次；
 *  · [OverlayWatchdog.ACTION_RESTORE]：用户点击保底通知。
 *
 * 必须在 AndroidManifest 里静态注册：定时闹钟在**进程已死**时也要能唤醒它，
 * 这正是「被杀后自动复活」的关键路径。
 */
class OverlayWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: return
        if (action != OverlayWatchdog.ACTION_WATCHDOG && action != OverlayWatchdog.ACTION_RESTORE) {
            return
        }

        val app = context.applicationContext
        val reason = if (action == OverlayWatchdog.ACTION_WATCHDOG) {
            "定时守护（5 分钟）"
        } else {
            "用户点击恢复通知"
        }

        // ★ 这里必须用 goAsync()：
        //   恢复流程要「启动服务 → 等它真的把窗口挂上 → 校验」，最多几秒，
        //   直接在 onReceive 主线程里 sleep 会阻塞广播分发。
        //   goAsync 允许我们把工作挪到后台线程，完成后再 finish()。
        val pending = goAsync()
        Thread {
            try {
                val ok = OverlayWatchdog.restoreAndVerify(app, reason)
                if (!ok) OverlayWatchdog.postRestoreNotification(app)
            } catch (tr: Throwable) {
                Logx.swallow("OverlayWatchdogReceiver", "restoreAndVerify", tr)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}
