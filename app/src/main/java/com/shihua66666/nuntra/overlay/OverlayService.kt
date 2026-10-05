package com.shihua66666.nuntra.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.shihua66666.nuntra.MainActivity
import com.shihua66666.nuntra.R
import com.shihua66666.nuntra.core.Logx

/**
 * 悬浮窗前台服务。
 *
 * 第 0 步状态：**只提供可运行的前台服务与通知渠道，尚未挂载 ComposeView 浮窗**。
 * 目的：让 Manifest 里声明的 specialUse 前台服务真实可用，
 * 使「服务可启动 / 可停止」这条链路先跑通，再在第 2 步接入 WindowManager。
 *
 * 第 2 步会在这里接入：
 *  · OverlayComposeHost（自实现 LifecycleOwner / SavedStateRegistryOwner / ViewTree* 挂载）
 *  · OverlayWindowController（LayoutParams、拖动、三态尺寸切换、位置持久化）
 *  · 前台通知文案随未读数更新
 */
class OverlayService : Service() {

    override fun onCreate() {
        super.onCreate()
        Logx.i(TAG, "OverlayService onCreate")
        createChannels()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logx.i(TAG, "收到停止指令")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Logx.i(TAG, "onStartCommand")
        }
        updateNotification(unread = 0)
        // START_STICKY：被系统杀掉后尽量自动重建。
        // 注意：在 ColorOS 上这条不完全可靠，仍需用户手动加入自启动 / 后台白名单。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logx.i(TAG, "OverlayService onDestroy")
        // 第 2 步在这里：先 removeView()，再 owner.onDestroy()，并保存浮窗位置。
        super.onDestroy()
    }

    // ── 前台通知 ─────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val overlay = NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_overlay_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_overlay_desc)
                setShowBadge(false)
            }
            val priority = NotificationChannel(
                CHANNEL_PRIORITY,
                getString(R.string.channel_priority_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(R.string.channel_priority_desc) }
            nm.createNotificationChannel(overlay)
            nm.createNotificationChannel(priority)
        }.onFailure { Logx.swallow(TAG, "createChannels", it) }
    }

    /**
     * 启动前台。
     *
     * targetSdk 34 起必须声明类型；这里用 specialUse（Manifest 已配 subtype property）。
     * 若类型不匹配会抛 MissingForegroundServiceTypeException，因此包一层兜底：
     * 最坏情况退化为不带类型的 startForeground，至少不崩溃。
     */
    private fun startForegroundCompat() {
        val notification = buildNotification(unread = 0)
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }.onFailure { tr ->
            Logx.w(TAG, "startForeground 失败，尝试降级：" + tr.message, tr)
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { Logx.swallow(TAG, "startForeground-fallback", it) }
        }
    }

    private fun updateNotification(unread: Int) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(unread))
        }.onFailure { Logx.swallow(TAG, "updateNotification", it) }
    }

    private fun buildNotification(unread: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (unread <= 0) {
            getString(R.string.fgs_text_idle)
        } else {
            getString(R.string.fgs_text_unread, unread)
        }
        return NotificationCompat.Builder(this, CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_OVERLAY = "nuntra_overlay"
        private const val CHANNEL_PRIORITY = "nuntra_priority"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.shihua66666.nuntra.action.START_OVERLAY"
        const val ACTION_STOP = "com.shihua66666.nuntra.action.STOP_OVERLAY"

        /** 启动服务。未授权悬浮窗也允许调用：服务本身能跑，只是画不出窗口。 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { tr ->
                // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
                // 这里必须吞掉：用户从 UI 点启动时正常，系统后台限制时不应崩溃。
                Logx.swallow(TAG, "start", tr)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure { tr -> Logx.swallow(TAG, "stop", tr) }
        }
    }
}
