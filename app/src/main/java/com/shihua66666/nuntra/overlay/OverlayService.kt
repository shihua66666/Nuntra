package com.shihua66666.nuntra.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
// ★ 属性委托必需（uiState 用了 var uiState by mutableStateOf(...)）。
//   by 编译成 getValue/setValue 调用，这两个名字在源码里不出现，
//   因此不能被「未使用 import」清理误删 —— CI 上正是如此报错。
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.shihua66666.nuntra.MainActivity
import com.shihua66666.nuntra.R
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.ServiceLocator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.shihua66666.nuntra.ui.overlay.OverlayContent
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.TagPaletteimport kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮窗前台服务。
 *
 * 它同时是：
 *  1. 进程常驻的载体（前台服务 + specialUse 类型）；
 *  2. 悬浮窗窗口的持有者（WindowManager + ComposeView，见 OverlayWindowController / OverlayComposeHost）；
 *  3. 主题变更的订阅者（ThemeController 是单例，设置页切主题后这里同步重建内容）。
 *
 * 与总开关的关系（需求里的总闸）：
 *  总开关关闭 → MainActivity 调用 stop() → 本服务销毁 → removeView 移除悬浮窗；
 *  总开关开启 → MainActivity 调用 start() → 本服务 onStartCommand 里挂载悬浮窗。
 */
class OverlayService : Service(), OverlayGestureCallbacks {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager

    private lateinit var composeHost: OverlayComposeHost

    private lateinit var windowController: OverlayWindowController

    private lateinit var positionStore: OverlayPositionStore

    private lateinit var touchInterceptor: OverlayTouchInterceptor

    private var gesture: OverlayGestureDetector? = null

    /** 总开关读取到的值；用于在权限晚到、或主题变更时决定是否要挂窗口。 */
    @Volatile private var masterSwitchOn: Boolean = false

    @Volatile private var unreadCount: Int = 0

    /** 未读消息里优先级最高的标签色（折叠态未读数与状态灯用它着色；null 表示无未读）。 */
    private var unreadTagColor by mutableStateOf<Color?>(null)

    private var uiState by mutableStateOf(OverlayUiState())

    override fun onCreate() {
        super.onCreate()
        Logx.i(TAG, "onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        positionStore = OverlayPositionStore(ServiceLocator.c.appPreferences)
        composeHost = OverlayComposeHost(applicationContext)
        windowController = OverlayWindowController(
            context = applicationContext,
            windowManager = windowManager,
            positionStore = positionStore,
            serviceScope = serviceScope,
        )
        touchInterceptor = OverlayTouchInterceptor(applicationContext)
        gesture = OverlayGestureDetector(applicationContext, this).also { detector ->
            // 拦截容器在「越阈值开始抢手势」时会调用 beginGesture 同步基准点
            touchInterceptor.gesture = detector
        }
        createChannels()
        startForegroundCompat()
        observeSettings()
        observeMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logx.i(TAG, "收到停止指令")
                // 先移除窗口再停止服务：保证「停止」与「不可见」同时发生。
                detachOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Logx.i(TAG, "onStartCommand")
        }
        updateNotification(unreadCount)
        // 服务启动时做一次消息清理（需求要求触发时机包含「前台服务启动」）
        serviceScope.launch {
            runCatching { ServiceLocator.c.messageStore.sweep() }
                .onFailure { Logx.swallow(TAG, "sweep-on-start", it) }
        }
        // 总开关为开时才挂窗口；否则仅保持服务（等待总开关打开）
        syncOverlay(masterSwitchOn)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转后把窗口夹回屏幕内，否则会「跑到屏幕外 = 用户以为浮窗消失」
        runCatching { windowController.onConfigurationChanged() }
            .onFailure { Logx.swallow(TAG, "onConfigurationChanged", it) }
    }

    override fun onDestroy() {
        Logx.i(TAG, "onDestroy")
        gesture?.release()
        detachOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── 悬浮窗挂载 / 卸载 ────────────────────────────────────────

    private fun syncOverlay(shouldShow: Boolean) {
        if (!shouldShow) {
            detachOverlay()
            return
        }
        if (windowController.isAttached) return
        if (!canDrawOverlays()) {
            Logx.w(TAG, "缺少悬浮窗权限，暂不挂载（用户授权后重启服务即可）")
            return
        }
        serviceScope.launch {
            val colors = ServiceLocator.c.themeController.current()

            // 1) 建 ComposeView（此时尚未 attach，生命周期钩子由控制器在正确时机回调）
            val composeView = runCatching {
                composeHost.createView(colors) {
                    OverlayServiceContent()
                }
            }.getOrElse { tr ->
                Logx.swallow(TAG, "createView", tr)
                null
            } ?: return@launch

            // 2) 套进触摸拦截容器：拖动与内部点击共存的关键。
            //    注意每轮只能 addView 一次 —— 拦截容器里只放这一个子 View。
            touchInterceptor.removeAllViews()
            touchInterceptor.addView(
                composeView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )

            // 3) 交给控制器挂载。它内部按「onCreate → addView → onStart」推进生命周期。
            val attached = windowController.attach(
                state = uiState.windowState,
                viewFactory = { _, _ -> touchInterceptor },
            )
            if (!attached) {
                Logx.w(TAG, "悬浮窗挂载失败（权限被撤销或 token 失效）")
            } else {
                gesture?.let { touchInterceptor.resetGesture() }
            }
        }
    }
    private fun detachOverlay() {
        runCatching { windowController.release() }
            .onFailure { Logx.swallow(TAG, "detachOverlay", it) }
    }

    private fun canDrawOverlays(): Boolean = runCatching {
        Settings.canDrawOverlays(this)
    }.getOrDefault(false)

    // ── 手势回调 ─────────────────────────────────────────────────

    override fun onSingleTap() {
        if (!windowController.isAttached) return
        val next = uiState.windowState.toggleExpanded()
        uiState = uiState.withState(next)
        windowController.applyState(next)
        if (next != OverlayWindowState.CAPSULE) {
            // 展开面板即视为「已查看」：清未读并刷新前台通知
            ServiceLocator.c.messageStore.markAllRead()
        }
    }

    override fun onDoubleTap() {
        if (!windowController.isAttached) return
        val next = uiState.windowState.toggleMini()
        uiState = uiState.withState(next)
        windowController.applyState(next)
    }

    override fun onLongPress() {
        uiState = uiState.withQuickMenu(!uiState.quickMenuVisible)
        Logx.d(TAG, "长按快捷菜单：" + uiState.quickMenuVisible)
    }

    override fun onDragStart() {
        uiState = uiState.withDragging(true)
        windowController.setDraggingAlpha(true)
    }

    override fun onDrag(dx: Int, dy: Int) {
        windowController.moveBy(dx, dy)
    }

    override fun onDragEnd() {
        uiState = uiState.withDragging(false)
        windowController.setDraggingAlpha(false)
        windowController.onDragFinished()
    }

    // ── 设置与状态订阅 ───────────────────────────────────────────

    private fun observeSettings() {
        val container = ServiceLocator.c
        serviceScope.launch {
            container.appPreferences.masterSwitchEnabled
                .catch { emit(false) }
                .collectLatest { enabled ->
                    masterSwitchOn = enabled
                    Logx.i(TAG, "总开关 = " + enabled)
                    syncOverlay(enabled)
                }
        }
        // 主题变更：设置页切换后悬浮窗立即生效（同一个 ThemeController 单例）
        serviceScope.launch {
            container.themeController.colors.collectLatest { colors ->
                composeHost.updateTheme(colors)
            }
        }
    }

    private fun observeMessages() {
        serviceScope.launch {
            ServiceLocator.c.messageStore.unreadCount.collectLatest { count ->
                unreadCount = count
                updateNotification(count)
                refreshUnreadTagColor()
            }
        }
    }

    /**
     * 刷新「未读里优先级最高的标签色」。
     *
     * 规则来自需求：折叠态未读数的颜色 = 当前未读消息里优先级最高的标签颜色。
     * 无未读时置 null（UI 会退回次要文字色）；标签被删除等异常情况下 TagPalette 会兜底，
     * 不会抛异常。
     */
    private fun refreshUnreadTagColor() {
        serviceScope.launch {
            unreadTagColor = runCatching {
                val tagRepository = ServiceLocator.c.tagRepository
                val tagId = ServiceLocator.c.messageStore
                    .highestPriorityUnreadTagId { id -> tagRepository.priorityOf(id) }
                val hex = tagId?.let { tagRepository.tagById(it)?.color }
                hex?.let { TagPalette.resolve(it) }
            }.getOrElse { tr ->
                Logx.swallow(TAG, "refreshUnreadTagColor", tr)
                null
            }
        }
    }

    /** 折叠回胶囊态（底栏「折叠」按钮）。 */
    private fun collapseToCapsule() {
        if (!windowController.isAttached) return
        uiState = uiState.withState(OverlayWindowState.CAPSULE)
        windowController.applyState(OverlayWindowState.CAPSULE)
    }

    /** 打开设置页（底栏「设置」按钮）：从悬浮窗直接跳到主界面。 */
    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }.onFailure { Logx.swallow(TAG, "openApp", it) }
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
     * targetSdk 34 起启动前台服务必须声明类型；这里用 specialUse
     * （Manifest 已配 FOREGROUND_SERVICE_SPECIAL_USE 与 subtype property）。
     * 任何异常都不能让服务崩：最坏情况退化为不带类型的 startForeground。
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
                // 用户从 UI 点击时正常，系统后台限制时不应崩。
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

/**
 * 悬浮窗内容：把服务持有的数据与回调接到 UI 容器上。
 *
 * 为什么内容装配放在 Service 内的私有 Composable，而不是 UI 层：
 *  数据源（MessageStore / TagRepository）与行为（折叠、清空、打开设置）都属于服务职责；
 *  UI 层保持纯粹（只接收参数），这样第 4 步替换消息列表时不需要动服务以外的代码。
 */
@Composable
private fun OverlayServiceContent() {
    val colors = ServiceLocator.c.themeController.colors.collectAsState().value
    val tags by ServiceLocator.c.tagRepository.tags.collectAsState()
    val messages by ServiceLocator.c.messageStore.sorted.collectAsState()
    val state = uiState
    val tagViews = remember(tags, messages) { ServiceLocator.c.messageStore.tagViews(tags) }

    OverlayContent(
        colors = colors,
        windowState = state.windowState,
        unreadCount = unreadCount,
        unreadTagColor = unreadTagColor,
        dragging = state.dragging,
        tags = tagViews,
        selectedTagIds = state.selectedTagIds,
        messageCount = messages.size,
        filterVisible = state.filterVisible,
        animateLamp = state.animateLamp,
        onToggleTag = { tagId ->
            val current = uiState.selectedTagIds
            val next = if (tagId in current) current - tagId else current + tagId
            uiState = uiState.copy(selectedTagIds = next)
        },
        onSelectAll = { uiState = uiState.copy(selectedTagIds = emptySet()) },
        onToggleFilter = { uiState = uiState.withFilterVisible(!uiState.filterVisible) },
        onOpenSettings = { openApp() },
        onClearMessages = { ServiceLocator.c.messageStore.clear() },
        onCollapse = { collapseToCapsule() },
        messageList = { MessageListSlot(messages.isEmpty()) },
    )
}

/**
 * 消息列表插槽。
 *
 * 第 4 步会替换为真正的 MessageList（LazyColumn + 稳定 key + 新消息淡入 + 关键词高亮）。
 * 现在保留这一层，是为了让「窗口 → 面板 → 内容」整条链路先可在真机上目视验证。
 */
@Composable
private fun MessageListSlot(empty: Boolean) {
    val c = LocalAppColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (empty) {
            Text(text = "暂无消息", color = c.textSecondary, fontSize = 13.sp)
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.panelElevated)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "消息列表 · 第 4 步接入",
                    color = c.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
