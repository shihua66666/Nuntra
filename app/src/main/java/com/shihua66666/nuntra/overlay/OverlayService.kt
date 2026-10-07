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
import android.os.SystemClock
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
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.ui.overlay.StaticOverlayCapsule
import com.shihua66666.nuntra.ui.theme.AppColors
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
import com.shihua66666.nuntra.ui.overlay.MessageList
import com.shihua66666.nuntra.ui.overlay.OverlayContent
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.TagPalette
import kotlinx.coroutines.CoroutineScope
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

    /**
     * 启动。**整个初始化过程都在 try-catch 内** —— 这是「不闪退」的第二道闸门。
     *
     * 为什么必须这么做：前台服务由系统在任意时刻拉起（START_STICKY 被回收后会重建），
     * 此时应用可能还没完成初始化（容器未就绪），或系统服务拿不到。
     * 任何一处未捕获异常都会导致进程被杀 —— 用户看到的就是「启动服务闪退」。
     */
    /** 组件是否已初始化完成。未完成时所有窗口操作都要跳过，否则会 NPE。 */
    private var componentsReady = false

    override fun onCreate() {
        super.onCreate()
        Logx.i(TAG, "onCreate")

        // ★ 第一步必须是 startForeground，而且必须在任何可能失败的操作之前。
        //
        // 原因：通过 startForegroundService 启动的服务，系统要求**秒级内**调用
        // startForeground()。如果在它之前先做了一堆初始化并中途失败返回，
        // 系统会直接杀掉进程（ForegroundServiceDidNotStartInTimeException）——
        // 表现就是用户说的「点总开关闪退」。
        //
        // 这里的前台通知只依赖 strings / drawable，与容器、权限都无关，因此可以先决执行。
        runCatching { createChannels() }
            .onFailure { Logx.swallow(TAG, "createChannels", it) }
        runCatching { startForegroundCompat() }
            .onFailure { Logx.swallow(TAG, "startForegroundCompat", it) }

        // 第二步才做组件初始化；失败不影响「已进入前台」这一事实。
        runCatching { initInternal() }.onFailure { tr ->
            Logx.e(TAG, "组件初始化失败，服务保持前台但不出窗口", tr)
        }
    }

    /**
     * 组件初始化：窗口控制器、手势、设置订阅。
     *
     * 全部依赖注入容器，因此容器未就绪时**整体跳过**并保持 componentsReady = false；
     * 之后 onStartCommand 里的所有窗口操作都会因此被安全跳过，不会 NPE。
     */
    private fun initInternal() {
        if (!ServiceLocator.isInitialized) {
            Logx.w(TAG, "容器未就绪，跳过组件初始化（服务仍在前台运行）")
            componentsReady = false
            return
        }
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
        observeSettings()
        observeMessages()
        componentsReady = true
        Logx.i(TAG, "组件初始化完成")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 组件未就绪（容器尚未初始化）：只保证服务活着，不做任何窗口/数据操作。
        // 这样即使系统在应用未启动时把服务拉起来，也不会 NPE。
        if (!componentsReady) {
            Logx.w(TAG, "组件未就绪，忽略本次 onStartCommand")
            if (intent?.action == ACTION_STOP) stopSelf()
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            Logx.i(TAG, "收到停止指令")
            // 先移除窗口再停止服务：保证「停止」与「不可见」同时发生。
            detachOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        Logx.i(TAG, "onStartCommand")

        runCatching { updateNotification(unreadCount) }
            .onFailure { Logx.swallow(TAG, "updateNotification", it) }

        // 服务启动时做一次消息清理（需求要求触发时机包含「前台服务启动」）
        serviceScope.launch {
            runCatching { ServiceLocator.c.messageStore.sweep() }
                .onFailure { Logx.swallow(TAG, "sweep-on-start", it) }
        }

        // 总开关为开时才挂窗口；否则仅保持服务（等待总开关打开）
        runCatching { syncOverlay(masterSwitchOn) }
            .onFailure { Logx.swallow(TAG, "syncOverlay-onStart", it) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!componentsReady) return
        // 屏幕旋转后把窗口夹回屏幕内，否则会「跑到屏幕外 = 用户以为浮窗消失」
        runCatching { windowController.onConfigurationChanged() }
            .onFailure { Logx.swallow(TAG, "onConfigurationChanged", it) }
    }

    override fun onDestroy() {
        Logx.i(TAG, "onDestroy")
        setWindowAttached(false)
        runCatching { gesture?.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── 悬浮窗挂载 / 卸载 ────────────────────────────────────────

    private fun syncOverlay(shouldShow: Boolean) {
        // ★ 四道闸门全部改成「有反馈」：任何一处拦住都要让用户知道是哪一个，
        //   否则表现就是「服务在运行但屏幕上什么都没有」，完全无从排查。
        if (!componentsReady) {
            Logx.w(TAG, "组件未就绪，跳过挂载")
            NtToast.show(this, "悬浮窗未挂载：组件未就绪")
            return
        }
        if (!shouldShow) {
            detachOverlay()
            return
        }
        if (windowController.isAttached) {
            Logx.d(TAG, "窗口已挂载，无需重复")
            return
        }
        if (!canDrawOverlays()) {
            Logx.w(TAG, "缺少悬浮窗权限，暂不挂载")
            NtToast.show(this, "悬浮窗未挂载：缺少悬浮窗权限")
            return
        }
        serviceScope.launch {
            // ★★ 延迟挂载 + 就绪等待（防「空壳崩溃」）★★
            //
            //   为什么需要：OverlayService 由 startForegroundService 启动，
            //   它的 onCreate 有可能赶在 Application.onCreate 中的 ServiceLocator.init 完成之前执行。
            //   此时 ServiceLocator.c 会抛 IllegalStateException —— 而它一旦发生在**组合期**，
            //   就无法被任何 try-catch 拦住（会直接杀掉进程）。
            //
            //   这里先等一个固定窗口让主进程把单例装配完，再轮询确认（有上限，绝不无限等）。
            //   即使最终仍未就绪也照常挂载 —— 内容会走「静态空壳」分支，不读任何数据源。
            kotlinx.coroutines.delay(MOUNT_DELAY_MS)
            if (!awaitContainerReady()) {
                Logx.w(TAG, "容器未就绪，将以静态空壳挂载（内容不依赖任何数据源）")
            }

            // 主题色读取也要兜底：容器可能仍未就绪
            val colors = runCatching {
                ServiceLocator.appContainerOrNull?.themeController?.current()
            }.getOrNull() ?: AppColors.GLAID_BLUE

            // 1) 建 ComposeView（此时尚未 attach，生命周期钩子由控制器在正确时机回调）
            val composeView = runCatching {
                composeHost.createView(colors) {
                    OverlayServiceContent()
                }
            }.getOrElse { tr ->
                Logx.e(TAG, "创建悬浮窗内容失败", tr)
                NtToast.show(
                    this@OverlayService,
                    "悬浮窗内容创建失败：" + tr.javaClass.simpleName,
                )
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
            setWindowAttached(attached)
            // 挂载成功 → 清除崩溃自愈标记；失败 → 置位，下次启动会自动关闭总开关
            runCatching {
                ServiceLocator.c.appPreferences.setOverlayCrashFlag(!attached)
            }
            if (!attached) {
                // 具体异常已由 OverlayWindowController 弹出，这里只补一条上下文
                Logx.w(TAG, "悬浮窗挂载失败（权限被撤销或 token 失效）")
                NtToast.show(this@OverlayService, "悬浮窗挂载失败，详见上一条提示")
            } else {
                gesture?.let { touchInterceptor.resetGesture() }
            }
        }
    }
    private fun detachOverlay() {
        setWindowAttached(false)
        if (!componentsReady) return
        runCatching { windowController.release() }
            .onFailure { Logx.swallow(TAG, "detachOverlay", it) }
    }

    private fun canDrawOverlays(): Boolean = runCatching {
        Settings.canDrawOverlays(this)
    }.getOrDefault(false)

    /**
     * 等待依赖容器就绪。
     *
     * **必须有上限**：否则一旦容器永远不会就绪（例如 Application 初始化失败），
     * 这里就会一直空转。超时后返回 false，由调用方走「静态空壳」路径 ——
     * 显示一个安静的小点，也好过崩溃或永远不出现。
     */
    private suspend fun awaitContainerReady(timeoutMs: Long = CONTAINER_WAIT_MS): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!ServiceLocator.isInitialized && SystemClock.elapsedRealtime() < deadline) {
            kotlinx.coroutines.delay(50L)
        }
        return ServiceLocator.isInitialized
    }

    // ── 手势回调 ─────────────────────────────────────────────────

    override fun onSingleTap() {
        if (!componentsReady || !windowController.isAttached) return
        val next = uiState.windowState.toggleExpanded()
        uiState = uiState.withState(next)
        windowController.applyState(next)
        if (next != OverlayWindowState.CAPSULE) {
            // 展开面板即视为「已查看」：清未读并刷新前台通知
            ServiceLocator.c.messageStore.markAllRead()
        }
    }

    override fun onDoubleTap() {
        if (!componentsReady || !windowController.isAttached) return
        val next = uiState.windowState.toggleMini()
        uiState = uiState.withState(next)
        windowController.applyState(next)
    }

    override fun onLongPress() {
        uiState = uiState.withQuickMenu(!uiState.quickMenuVisible)
        Logx.d(TAG, "长按快捷菜单：" + uiState.quickMenuVisible)
    }

    override fun onDragStart() {
        if (!componentsReady) return
        uiState = uiState.withDragging(true)
        windowController.setDraggingAlpha(true)
    }

    override fun onDrag(dx: Int, dy: Int) {
        if (!componentsReady) return
        windowController.moveBy(dx, dy)
    }

    override fun onDragEnd() {
        if (!componentsReady) return
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
        if (!componentsReady || !windowController.isAttached) return
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

    /**
     * 悬浮窗内容：把服务持有的数据与回调接到 UI 容器上。
     *
     * 为什么内容装配放在 Service 内的私有 Composable，而不是 UI 层：
     *  数据源（MessageStore / TagRepository）与行为（折叠、清空、打开设置）都属于服务职责；
     *  UI 层保持纯粹（只接收参数），这样第 4 步替换消息列表时不需要动服务以外的代码。
     */
    @Composable
    private fun OverlayServiceContent() {
        // ★★ 组合期绝不能调用会抛异常的访问器 ★★
        //
        //   ServiceLocator.c 的定义是 error(...) 非空断言，注释里也明确写着
        //   「UI 层不要直接用这个，以免闪退」。组合期抛出的异常无法被 try-catch 拦截，
        //   会直接杀掉进程 —— 这就是「空壳崩溃」最典型的成因。
        //
        //   因此这里统一走 appContainerOrNull；为 null 时渲染**不读任何数据源**的静态空壳。
        val container = ServiceLocator.appContainerOrNull
        if (container == null) {
            Logx.w(TAG, "容器未就绪：悬浮窗降级为静态空壳")
            StaticOverlayCapsule()
            return
        }

        val colors = container.themeController.colors.collectAsState().value
        val tags by container.tagRepository.tags.collectAsState()
        val messages by container.messageStore.sorted.collectAsState()
        val state = uiState
        // tagViews 构造失败时退回空列表：空列表会被 UI 当成「无标签」，绝不崩
        val tagViews = remember(tags, messages) {
            runCatching { container.messageStore.tagViews(tags) }.getOrDefault(emptyList())
        }

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
            onClearMessages = { runCatching { container.messageStore.clear() } },
                onCollapse = { collapseToCapsule() },
            messageList = {
                MessageList(
                    tags = tags,
                    selectedTagIds = state.selectedTagIds,
                    messages = messages,
                    // 展开面板时已整体标记过已读；这里覆盖「展开后新到」的消息。
                    // 第 5 步会在此基础上加 contentIntent.send() 跳转原 App。
                    onMessageClick = { message ->
                        runCatching { container.messageStore.markRead(message.id) }
                    },
                )
            },
        )
    }

    companion object {
        private const val TAG = "OverlayService"

        /** 挂载前的固定延迟（ms）：让主进程把单例装配完再挂窗口。 */
        private const val MOUNT_DELAY_MS = 500L

        /** 等待容器就绪的上限（ms）：超时则走静态空壳，绝不无限等待。 */
        private const val CONTAINER_WAIT_MS = 3000L

        /**
         * 悬浮窗是否真的已挂载到 WindowManager。
         *
         * 为什么需要这个静态标志：UI 层无法直接询问服务实例，
         * 而「服务在运行」并不等于「窗口已挂载」—— 之前界面文案就是靠猜，会骗用户。
         * 这里由服务在挂载/卸载时维护，UI 读它得到真实状态。
         */
        @Volatile
        var isWindowAttached: Boolean = false
            private set

        /** 供服务内部更新挂载状态。 */
        fun setWindowAttached(attached: Boolean) {
            isWindowAttached = attached
        }
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
