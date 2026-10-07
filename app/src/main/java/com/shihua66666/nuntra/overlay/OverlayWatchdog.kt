package com.shihua66666.nuntra.overlay

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.shihua66666.nuntra.MainActivity
import com.shihua66666.nuntra.R
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.ServiceLocator

/**
 * 悬浮窗守护：让浮窗在被「一键清理 / 垃圾清理」杀掉之后自动复活。
 *
 * 四层复活机制（由快到慢，互相补位）：
 *
 *  1. **状态持久化**：总开关存在 DataStore 里。App 重启后（用户打开、或收到通知）
 *     由 [tryRestore] 读到「本来是开着的」并自动拉起服务 —— 无需用户手动点击。
 *  2. **定时守护**：[schedule] 向 AlarmManager 注册 5 分钟一次的重复闹钟，
 *     每次检查窗口是否还在，不在就重新拉起。
 *  3. **屏幕亮起**：[registerScreenOn] 监听 ACTION_SCREEN_ON ——
 *     亮屏时立刻检查一次，用户几乎察觉不到被清理过。
 *  4. **保底通知**：[postRestoreNotification] 自动恢复被系统拒绝时弹一条通知，
 *     用户点一下即可恢复（Android 12+ 对后台启动前台服务有硬限制，必须有这条兜底）。
 *
 * ★ 为什么 ACTION_SCREEN_ON 必须动态注册：
 *   它属于「只能在运行时注册才能收到」的广播，写进 AndroidManifest 的接收器
 *   永远不会被触发。这里注册在 applicationContext 上且不注销 ——
 *   通知监听服务会让进程长期存活，进程在，这条监听就有效。
 *
 * ★ 为什么用 AlarmManager 而不是 WorkManager：
 *   WorkManager 的最小周期是 15 分钟，达不到需求要求的 5 分钟；
 *   也不用「无声音频循环」那种黑产手段 —— 它会常驻音频焦点、明显耗电。
 */
object OverlayWatchdog {

    private const val TAG = "OverlayWatchdog"

    /** 定时守护触发。 */
    const val ACTION_WATCHDOG = "com.shihua66666.nuntra.action.WATCHDOG_TICK"

    /** 用户点击保底通知触发的恢复。 */
    const val ACTION_RESTORE = "com.shihua66666.nuntra.action.RESTORE_OVERLAY"

    private const val RC_WATCHDOG = 3001
    private const val RC_RESTORE = 3002
    private const val RC_OPEN = 3003

    /** 保底通知的 id：与前台服务通知（1001）区分开。 */
    private const val NOTIFICATION_ID_RESTORE = 1002

    private const val CHANNEL_WATCHDOG = "nuntra_watchdog"

    /** 守护间隔：5 分钟（需求指定）。 */
    const val INTERVAL_MS = 5L * 60L * 1000L

    @Volatile
    private var screenReceiver: BroadcastReceiver? = null

    // ── 屏幕亮起监听 ─────────────────────────────────────────────

    /** 注册屏幕亮起监听。重复调用安全（内部去重）。 */
    fun registerScreenOn(context: Context) {
        if (screenReceiver != null) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_ON) return
                runCatching {
                    val ok = tryRestore(app, "屏幕亮起")
                    if (!ok) postRestoreNotification(app)
                }.onFailure { Logx.swallow(TAG, "screenOn", it) }
            }
        }
        runCatching {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
            Logx.i(TAG, "已注册屏幕亮起监听")
        }.onFailure { Logx.swallow(TAG, "registerScreenOn", it) }
    }

    // ── 定时守护 ─────────────────────────────────────────────────

    /** 启动 5 分钟一次的守护闹钟。重复调用安全（同 id 的 PendingIntent 会覆盖）。 */
    fun schedule(context: Context) {
        val app = context.applicationContext
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // ELAPSED_REALTIME（不是 WAKEUP）：息屏时不必唤醒设备 ——
            // 悬浮窗只在屏幕亮着时才有意义，而亮屏本身有 SCREEN_ON 监听兜底。
            // setInexactRepeating：不需要精确唤醒，省电且不触发精确闹钟权限限制。
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                watchdogPendingIntent(app),
            )
            Logx.i(TAG, "守护已启动：每 " + (INTERVAL_MS / 60000L) + " 分钟检查一次")
        }.onFailure { Logx.swallow(TAG, "schedule", it) }
    }

    /** 停止守护（用户关闭总开关时调用）。 */
    fun cancel(context: Context) {
        runCatching {
            val app = context.applicationContext
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(watchdogPendingIntent(app))
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID_RESTORE)
            Logx.i(TAG, "守护已停止")
        }.onFailure { Logx.swallow(TAG, "cancel", it) }
    }

    private fun watchdogPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RC_WATCHDOG,
            Intent(context, OverlayWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ── 恢复入口 ─────────────────────────────────────────────────

    /**
     * 统一的恢复入口：判断「该不该恢复」并尝试拉起服务。
     *
     * @return true 表示**无需处理**或**已成功发起**；
     *         false 表示确实尝试了但被系统拒绝（调用方应发保底通知）。
     *
     * 这个返回值的语义很关键：绝不能把「用户本来就没开总开关」
     * 当成「恢复失败」去骚扰用户。
     */
    fun tryRestore(context: Context, reason: String): Boolean {
        val prefs = ServiceLocator.appContainerOrNull?.appPreferences
            ?: return true // 容器未就绪：留给下一次时机，不算失败
        if (!prefs.masterSwitchEnabledBlocking()) return true // 用户没开启过，无需恢复
        if (OverlayService.isWindowAttached) return true // 窗口还在，什么都没发生
        if (!canDrawOverlays(context)) return true // 没权限，启动了也画不出来

        Logx.i(TAG, "检测到悬浮窗缺失，尝试恢复（触发原因：" + reason + "）")
        return OverlayService.startAndReport(context)
    }

    private fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    // ── 保底通知 ─────────────────────────────────────────────────

    /**
     * 保底通知：自动恢复被系统拒绝时提示用户手动点击。
     *
     * 点击通知体 = 再试一次恢复（不需要打开 App）；
     * 「打开应用」按钮 = 兜底，用户可在界面里手动开总开关。
     */
    fun postRestoreNotification(context: Context) {
        runCatching {
            val app = context.applicationContext
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm, app)

            val restore = PendingIntent.getBroadcast(
                app,
                RC_RESTORE,
                Intent(app, OverlayWatchdogReceiver::class.java).setAction(ACTION_RESTORE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                app,
                RC_OPEN,
                Intent(app, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_WATCHDOG)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(app.getString(R.string.watchdog_title))
                .setContentText(app.getString(R.string.watchdog_text))
                .setContentIntent(restore)
                .addAction(0, app.getString(R.string.watchdog_action_open), open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(NOTIFICATION_ID_RESTORE, notification)
            Logx.w(TAG, "自动恢复失败，已发出保底通知")
        }.onFailure { Logx.swallow(TAG, "postRestoreNotification", it) }
    }

    private fun ensureChannel(nm: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCHDOG,
                    context.getString(R.string.watchdog_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }.onFailure { Logx.swallow(TAG, "ensureChannel", it) }
    }
}
