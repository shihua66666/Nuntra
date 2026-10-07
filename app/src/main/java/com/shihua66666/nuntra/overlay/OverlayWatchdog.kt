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

    /** 请求「卸载并完整重建」服务实例（守护发现只挂上了空壳时使用）。 */
    const val ACTION_REBUILD = "com.shihua66666.nuntra.action.REBUILD_OVERLAY"

    private const val RC_WATCHDOG = 3001
    private const val RC_RESTORE = 3002
    private const val RC_OPEN = 3003
    private const val RC_RETRY = 3004

    /** 保底通知的 id：与前台服务通知（1001）区分开。 */
    private const val NOTIFICATION_ID_RESTORE = 1002

    private const val CHANNEL_WATCHDOG = "nuntra_watchdog"

    /** 守护间隔：5 分钟（需求指定）。 */
    const val INTERVAL_MS = 5L * 60L * 1000L

    /** 服务销毁后立即重试的延时（需求：2 秒）。 */
    const val IMMEDIATE_RETRY_MS = 2_000L

    /** 一轮恢复里允许的最大重试次数 —— 防止无限重启循环。 */
    private const val MAX_RETRIES = 3

    /** 健康检查：超时与轮询间隔。总耗时必须远小于 BroadcastReceiver 的 10 秒上限。 */
    private const val HEALTH_TIMEOUT_MS = 2_500L
    private const val HEALTH_POLL_MS = 100L

    /** 发出重建指令后等待旧实例退出的时间。 */
    private const val REBUILD_SETTLE_MS = 500L

    /** 已重试次数：窗口挂载成功即清零。 */
    private val retries = java.util.concurrent.atomic.AtomicInteger(0)

    /** 窗口挂载成功时调用：重置重试计数，让守护回到「随时可救」的状态。 */
    fun resetRetries() {
        retries.set(0)
    }

    /** 当前已重试次数（供日志与自检）。 */
    fun retryCount(): Int = retries.get()

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

    /**
     * 安排一次**立即重试**（默认 2 秒后）。
     *
     * 场景：服务刚被系统杀掉时通常马上就能拉回来，不必等 5 分钟的守护闹钟。
     * 若 5 分钟重复闹钟被国产 ROM 冻结，这一层就是唯一还活着的恢复路径。
     *
     * 重试次数超过上限后不再重试，改为发保底通知交给用户 —— 避免无限重启。
     */
    fun scheduleImmediateRetry(context: Context, delayMs: Long = IMMEDIATE_RETRY_MS) {
        if (retries.get() >= MAX_RETRIES) {
            Logx.w(TAG, "重试已达上限（" + MAX_RETRIES + "），停止自动恢复，改发保底通知")
            postRestoreNotification(context)
            return
        }
        retries.incrementAndGet()
        runCatching {
            val app = context.applicationContext
            val trigger = SystemClock.elapsedRealtime() + delayMs
            scheduleAt(app, trigger, retryPendingIntent(app))
            Logx.i(TAG, "已安排 " + delayMs + "ms 后的恢复重试（第 " + retries.get() + " 次）")
        }.onFailure { Logx.swallow(TAG, "scheduleImmediateRetry", it) }
    }

    /**
     * 逐级降级的闹钟设置。
     *
     * 精确闹钟（setExactAndAllowWhileIdle）在 Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，
     * 拿不到会抛 SecurityException。本项目**不申请**该权限（它会打断用户去系统设置授权），
     * 因此这里逐级降级：
     *   精确 + 打盹可唤醒 → 非精确 + 打盹可唤醒 → 普通闹钟。
     * 只要有一级成功，恢复链路就成立。
     */
    private fun scheduleAt(context: Context, triggerElapsed: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactOk = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsed,
                    pi,
                )
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
            }
            true
        }.getOrElse { tr ->
            Logx.w(TAG, "精确闹钟不可用（" + tr.javaClass.simpleName + "），降级为非精确", tr)
            false
        }
        if (exactOk) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
            }
        }.onFailure { Logx.swallow(TAG, "scheduleAt-fallback", it) }
    }

    private fun retryPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RC_RETRY,
            Intent(context, OverlayWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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

    /**
     * 守护入口：恢复**并校验结果**，不合格就完整重建。
     *
     * ★ 为什么不能只 startService：
     *   startForegroundService 成功 ≠ 悬浮窗恢复正常。
     *   服务可能起来了却只挂上「空壳」（容器未就绪时的降级路径），
     *   用户看到的就是一条没有内容的**白条**。
     *   因此这里必须校验「容器已就绪 + 窗口已挂载」，不合格就重建整个服务实例。
     *
     * 只应在**后台线程**调用（内部有 Thread.sleep 轮询）。
     *
     * @return true 表示最终处于「已恢复」或「无需恢复」
     */
    fun restoreAndVerify(context: Context, reason: String): Boolean {
        val prefs = ServiceLocator.appContainerOrNull?.appPreferences ?: return false
        if (!prefs.masterSwitchEnabledBlocking()) return true // 用户没开启，无需恢复
        if (OverlayService.isWindowAttached) {
            resetRetries()
            return true // 已经正常
        }
        if (!canDrawOverlays(context)) return false // 没权限，恢复了也画不出来

        Logx.i(TAG, "守护触发恢复（原因：" + reason + "）")

        // 第一次：常规启动
        if (OverlayService.startAndReport(context) && awaitHealthy()) {
            resetRetries()
            return true
        }

        // 第二次：完整重建 —— 专门解决「服务起来了但只挂上壳」的状态
        Logx.w(TAG, "首次恢复未达到健康状态，执行完整重建")
        runCatching { OverlayService.rebuild(context) }
            .onFailure { Logx.swallow(TAG, "rebuild", it) }
        try {
            Thread.sleep(REBUILD_SETTLE_MS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (OverlayService.startAndReport(context) && awaitHealthy()) {
            resetRetries()
            return true
        }

        Logx.w(TAG, "完整重建后仍未达到健康状态")
        return false
    }

    /** 轮询等待「容器已就绪 + 窗口已挂载」。只应在线程外调用。 */
    private fun awaitHealthy(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + HEALTH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ServiceLocator.isInitialized && OverlayService.isWindowAttached) {
                Logx.i(TAG, "健康检查通过：容器已就绪且窗口已挂载")
                return true
            }
            try {
                Thread.sleep(HEALTH_POLL_MS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Logx.w(
            TAG,
            "健康检查超时：容器=" + ServiceLocator.isInitialized +
                " 窗口=" + OverlayService.isWindowAttached,
        )
        return false
    }

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
