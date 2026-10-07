package com.shihua66666.nuntra.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        when (intent?.action) {
            OverlayWatchdog.ACTION_WATCHDOG -> {
                val ok = OverlayWatchdog.tryRestore(context, "定时守护（5 分钟）")
                if (!ok) OverlayWatchdog.postRestoreNotification(context)
            }

            OverlayWatchdog.ACTION_RESTORE -> {
                val ok = OverlayWatchdog.tryRestore(context, "用户点击恢复通知")
                if (!ok) OverlayWatchdog.postRestoreNotification(context)
            }
        }
    }
}
