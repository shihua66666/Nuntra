package com.shihua66666.nuntra

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.core.AlertPlayer
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.data.MessageStore
import com.shihua66666.nuntra.data.TagOpError
import com.shihua66666.nuntra.data.TagOpResult
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.model.Tag
import com.shihua66666.nuntra.monitor.SmsAppResolver
import com.shihua66666.nuntra.overlay.OverlayOrchestrator
import com.shihua66666.nuntra.overlay.OverlayService
import com.shihua66666.nuntra.overlay.OverlayWatchdog
import com.shihua66666.nuntra.perm.PermissionChecker
import com.shihua66666.nuntra.ui.MainScreen
import com.shihua66666.nuntra.ui.NuntraNavHost
import com.shihua66666.nuntra.ui.Screen
import com.shihua66666.nuntra.ui.settings.ContactsScreen
import com.shihua66666.nuntra.ui.settings.ReminderScreen
import com.shihua66666.nuntra.ui.settings.TagManagerScreen
import com.shihua66666.nuntra.ui.theme.AppColors
import com.shihua66666.nuntra.ui.theme.NuntraTheme
import kotlinx.coroutines.launch

/**
 * 唯一 Activity。全 Compose，不引入任何 XML 布局。
 *
 * 三处为「不闪退」做的设计（都是实测踩过的坑）：
 *
 *  1. **容器访问用 [ServiceLocator.appContainerOrNull]** 而不是 [ServiceLocator.c]。
 *     后者在未初始化时会抛异常，而这个访问发生在**组合期**，
 *     结果就是界面还没画出来就闪退，用户看不到任何原因。
 *     现在改为：拿不到容器 → 渲染一个「初始化失败」页面，写明原因。
 *
 *  2. **权限状态在 onResume 刷新**（用 [resumeTick] 触发重组）。
 *     用户去系统设置里授权后切回来，必须立刻显示「已授权」；
 *     仅靠一次性的 remember 初始化做不到这一点。
 *
 *  3. **总开关走 [OverlayOrchestrator]**，它内部检查前置权限、
 *     捕获启动异常、并在失败时回滚开关状态 —— UI 不再直接碰服务启动。
 */
class MainActivity : ComponentActivity() {

    /** 每次 onResume 自增，作为组合期重新读取权限状态的触发器。 */
    private var resumeTick by mutableIntStateOf(0)

    /**
     * 外部入口请求「直达设置页」的计数器。
     *
     * 为什么用递增计数而不是布尔值：
     *   主界面已在栈中时，CLEAR_TOP 不会重建 Activity，而是回调 onNewIntent。
     *   若用布尔值，第二次请求会因为「已经是 true」而被丢掉，用户点了没反应。
     *   计数每次自增，配合 LaunchedEffect(key) 保证每次请求都触发一次切换。
     */
    private var settingsRequestTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动时可能带着「直达设置页」的请求（悬浮窗「设置」按钮）
        consumeScreenExtra(intent)
        runCatching {
            setContent {
                // onRequestResumeRefresh 由 Activity 提供：
                // Composable 只调用回调，真正改动状态的是状态的持有者。
                // （此前写成在 Composable 内部 resumeTick++ 是非法的 —— 函数参数是 val，
                //   会被编译器拒绝：'val' cannot be reassigned。）
                NuntraApp(
                    resumeTick = resumeTick,
                    settingsRequestTick = settingsRequestTick,
                    onRequestResumeRefresh = { resumeTick++ },
                )
            }
        }.onFailure { tr -> Logx.swallow("MainActivity", "setContent", tr) }
    }

    /**
     * 是否已在本进程内尝试过自动复活。
     *
     * 只在第一次 onResume 尝试：之后由 AlarmManager 守护与屏幕亮起监听接管，
     * 避免每次切回前台都去启动一次服务。
     */
    private var autoRestoreAttempted = false

    override fun onResume() {
        super.onResume()
        // ★ 自动复活：总开关本来是开着的，但悬浮窗不在（被一键清理杀掉了）→
        //   立刻恢复，不需要用户手动再点一次总开关。
        if (!autoRestoreAttempted) {
            autoRestoreAttempted = true
            runCatching { OverlayWatchdog.tryRestore(this, "应用回到前台") }
                .onFailure { Logx.swallow("MainActivity", "autoRestore", it) }
        }
        // 触发重组 → 重新计算 readiness。ColorOS 等系统会在后台回收权限，
        // 因此「每次回前台复检」是这里唯一的正确做法。
        resumeTick++
        Logx.d("MainActivity", "onResume：刷新权限状态（tick=" + resumeTick + "）")
    }

    /**
     * 已存在实例时的新 Intent（CLEAR_TOP + NEW_TASK 走的正是这条路径）。
     *
     * 必须实现：否则「App 已在后台 → 点悬浮窗设置按钮」不会跳转，
     * 这与用户看到的「点设置没反应」完全一致。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeScreenExtra(intent)
    }

    /** 解析 [EXTRA_SCREEN]，命中设置页时把计数器加一。 */
    private fun consumeScreenExtra(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_SCREEN) ?: return
        if (target == SCREEN_SETTINGS) {
            settingsRequestTick += 1
            Logx.d("MainActivity", "外部请求：直达设置页（tick=" + settingsRequestTick + "）")
        }
    }

    companion object {
        /** Intent extra：目标页面。 */
        const val EXTRA_SCREEN = "extra_screen"

        /** [EXTRA_SCREEN] 的取值：设置页。 */
        const val SCREEN_SETTINGS = "settings"
    }
}

/** 悬浮窗挂载状态的轮询间隔（ms）：挂载/卸载都是低频事件，1.5 秒足够且不耗电。 */
private const val WINDOW_STATE_POLL_MS = 1500L

/** 把标签操作错误转成用户能看懂的一句话。 */
private fun tagOpErrorText(error: TagOpError): String = when (error) {
    TagOpError.NAME_EMPTY -> "标签名不能为空"
    TagOpError.NAME_TOO_LONG -> "标签名最长 " + Tag.NAME_MAX_LENGTH + " 个字"
    TagOpError.NAME_DUPLICATE -> "已有同名标签"
    TagOpError.NOT_FOUND -> "目标不存在，可能已被删除"
    TagOpError.IS_DEFAULT -> "兜底标签不可删除"
    TagOpError.LAST_TAG -> "至少要保留一个标签"
    TagOpError.STORAGE_ERROR -> "保存失败，请重试"
}

@Composable
private fun NuntraApp(
    resumeTick: Int,
    /** 外部入口请求打开设置页的次数（0 表示无请求）。 */
    settingsRequestTick: Int,
    /** 请求重新检测权限：由 Activity 传入（它持有可变状态），Composable 只调用不修改。 */
    onRequestResumeRefresh: () -> Unit,
) {
    val container = ServiceLocator.appContainerOrNull

    // 主题单独处理：容器不可用时用默认配色兜底，保证「错误页」也能正常渲染
    val colors = if (container != null) {
        container.themeController.colors.collectAsState().value
    } else {
        AppColors.GLAID_BLUE
    }

    NuntraTheme(colors = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // ★ 启动路径保持绝对简单：这里**不做任何**崩溃记录读取与渲染。
            //   上一版在组合期读取并渲染崩溃页，一旦该页自身抛异常就会「每次启动必崩」，
            //   用户连主界面都进不去。现在崩溃详情只在设置页由用户主动查看。
            if (container == null) {
                // ── 初始化失败：显示原因，而不是白屏或闪退 ──
                InitFailureScreen(
                    reason = ServiceLocator.initFailure,
                    reasonHint = "android:name 是否指向 NotificationTerminalApp",
                )
            } else {
                AppContent(
                    container = container,
                    resumeTick = resumeTick,
                    settingsRequestTick = settingsRequestTick,
                    onRequestResumeRefresh = onRequestResumeRefresh,
                    // ★ 主题自上而下传递：NuntraApp 已收集过一次，避免重复 collectAsState
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun AppContent(
    container: com.shihua66666.nuntra.core.AppContainer,
    resumeTick: Int,
    /** 外部入口请求打开设置页的次数。 */
    settingsRequestTick: Int,
    onRequestResumeRefresh: () -> Unit,
    colors: AppColors,
) {
    val scope = ServiceLocator.appScope
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // ★ 直达设置页：悬浮窗「设置」按钮通过 Intent extra 请求。
    //   以计数为 key，每次请求都触发一次；为 0 时（正常启动）不动作。
    LaunchedEffect(settingsRequestTick) {
        if (settingsRequestTick > 0) screen = Screen.Settings
    }

    // 消息上限 / 保留时长：设置页需要读写这两个值
    val messageLimit by container.appPreferences.messageLimit
        .collectAsState(initial = MessageStore.DEFAULT_LIMIT)
    val retentionHoursPref by container.appPreferences.retentionHours
        .collectAsState(initial = MessageStore.DEFAULT_RETENTION_HOURS)

    val themeId by container.themeController.themeId.collectAsState()
    val messages by container.messageStore.sorted.collectAsState()
    val unread by container.messageStore.unreadCount.collectAsState()

    val masterSwitchStored by container.appPreferences.masterSwitchEnabled.collectAsState(initial = false)

    // ★ 乐观更新：点击立刻改变开关外观，不等 DataStore 写盘回流。
    //   原因：用户已经确认「点击进来了但开关不亮」，说明问题在写入/回流环节；
    //   把「点亮」与「落盘」解耦后：
    //     · 写入成功 → 无感（本地覆盖值随后被存储值覆盖，两者相同）；
    //     · 写入失败 → 本地覆盖值被清空 + Toast 报错，开关回到真实状态。
    //   这样「开关能不能亮」不再取决于写盘是否成功，便于把问题彻底分离。
    var pendingMaster by remember { mutableStateOf<Boolean?>(null) }
    val masterSwitchEnabled: Boolean = pendingMaster ?: masterSwitchStored
    val monitorWechat by container.appPreferences.monitorWechat.collectAsState(initial = true)
    val monitorQq by container.appPreferences.monitorQq.collectAsState(initial = true)
    val monitorWeCom by container.appPreferences.monitorWeCom.collectAsState(initial = false)
    val monitorTim by container.appPreferences.monitorTim.collectAsState(initial = false)
    val smsMonitoringEnabled by container.settingsRepository.smsMonitoringEnabled.collectAsState(initial = true)
    val smsKeywordSet by container.settingsRepository.smsKeywords.collectAsState(initial = emptySet())
    val resolvedSmsPackages by container.settingsRepository.resolvedSmsPackages.collectAsState(initial = emptySet())

    // readiness 由 resumeTick 驱动：onResume 时重新计算。
    // snapshot 内部已经全部 runCatching，这里再包一层是为了「任何意外都不致崩」。
    // ★ 悬浮窗是否**真的**挂上了：读服务维护的静态标志，低频轮询刷新。
    //   为什么需要轮询：挂载发生在服务协程里（建 ComposeView、读位置、addView），
    //   只在 onResume 读一次会漏掉「挂载完成后」的状态变化，界面就会一直显示旧判断。
    var windowAttached by remember {
        mutableStateOf(com.shihua66666.nuntra.overlay.OverlayService.isWindowAttached)
    }
    LaunchedEffect(masterSwitchEnabled) {
        while (true) {
            windowAttached = com.shihua66666.nuntra.overlay.OverlayService.isWindowAttached
            kotlinx.coroutines.delay(WINDOW_STATE_POLL_MS)
        }
    }

    val readiness = remember(resumeTick, masterSwitchEnabled) {
        runCatching { PermissionChecker.snapshot(ServiceLocator.context) }
            .getOrElse { tr ->
                Logx.swallow("MainActivity", "readiness", tr)
                com.shihua66666.nuntra.model.ReadinessSnapshot()
            }
    }

    // 短信 App 识别：只在包名集合变化时重算，避免每帧扫包管理器
    val smsResolution = remember(resolvedSmsPackages) {
        runCatching { SmsAppResolver.resolve(ServiceLocator.context, resolvedSmsPackages) }
            .getOrElse { com.shihua66666.nuntra.monitor.SmsAppResolver.Resolution(emptySet(), false) }
    }

    // 总开关统一走编排器：它内部做权限检查、异常兜底与状态回滚。
    // 这里额外做「乐观更新 + 逐步可见的错误反馈」。
    val applyMaster: (Boolean) -> Unit = { enabled ->
        // 全链路打点：即使后续任何一步异常，也能从 Toast 序号看出死在哪一环
        NtToast.show(ServiceLocator.context, "#11 进入 applyMaster，目标=" + enabled)
        val ctx = ServiceLocator.context
        // ① 立刻更新外观（乐观更新）—— 用户先看到开关亮了，再等落盘结果
        pendingMaster = enabled
        NtToast.show(ctx, "#11b 乐观状态已写入，pending=" + pendingMaster + "，当前显示=" + (pendingMaster ?: masterSwitchStored))
        scope.launch {
            try {
                // ② 落盘（内部会回读校验，失败即抛）
                NtToast.show(ctx, "#12 正在写入 DataStore")
                container.appPreferences.setMasterSwitchEnabled(enabled)
                NtToast.show(ctx, "#12b DataStore 写入并回读成功")
                // ③ ★ 先清空覆盖值：让 UI 立刻以「已落盘的真实值」为准，
                //    避免「服务启动较慢时开关一直停留在乐观值」。
                //    由于落盘已完成，此时读到的存储值必然等于期望值，不会闪回。
                pendingMaster = null
                // ④ 再交给编排器启动/停止服务（它内部会 Toast 告知失败原因）
                NtToast.show(ctx, "#13 准备启动前台服务")
                OverlayOrchestrator.setMasterSwitch(
                    context = ctx,
                    enabled = enabled,
                    scope = scope,
                )
            } catch (tr: Throwable) {
                // ★ 绝不静默：把异常类名与消息直接弹出来
                Logx.e("MainActivity", "总开关操作失败", tr)
                NtToast.show(
                    ctx,
                    "写入失败：" + tr.javaClass.simpleName + " / " + (tr.message ?: "无消息"),
                )
                pendingMaster = null
            }
        }
    }

    // ── 三个配置子页面所需的数据 ──────────────────────────────────
    val tags by container.tagRepository.tags.collectAsState()
    val watchedContacts by container.tagRepository.watchedContacts.collectAsState()
    val keywordSet by container.settingsRepository.keywords.collectAsState(initial = emptySet())
    val alertEnabled by container.appPreferences.alertEnabled.collectAsState(initial = true)
    val alertVolume by container.appPreferences.alertVolume.collectAsState(initial = 1f)
    val alertSound by container.appPreferences.alertSound
        .collectAsState(initial = AlertPlayer.SOUND_BUILTIN)
    val repeatEnabled by container.appPreferences.repeatEnabled.collectAsState(initial = true)
    val repeatIntervalPref by container.appPreferences.repeatIntervalMinutes.collectAsState(initial = 2)
    val repeatMaxPref by container.appPreferences.repeatMaxTimes.collectAsState(initial = 3)
    // 点击消息后是否隐藏已读项（默认开）
    val clearReadOnTapPref by container.appPreferences.clearReadOnTap.collectAsState(initial = true)

    /**
     * 标签类操作的统一入口。
     *
     * TagRepository 的方法都返回 TagOpResult；失败必须让用户看到原因
     * （重名 / 超长 / 兜底标签不可删 / 存储失败…），否则又变成「点了没反应」。
     * 这里统一把失败转成中文 Toast，成功则静默（列表会自行刷新）。
     */
    val runTagOp: (suspend () -> TagOpResult) -> Unit = { op ->
        scope.launch {
            val result = runCatching { op() }.getOrElse { tr ->
                Logx.swallow("MainActivity", "tagOp", tr)
                TagOpResult.Failure(TagOpError.STORAGE_ERROR)
            }
            if (result is TagOpResult.Failure) {
                NtToast.show(ServiceLocator.context, tagOpErrorText(result.error))
            }
        }
    }

    NuntraNavHost(
        current = screen,
        onNavigate = { screen = it },
        onBack = { screen = Screen.Home },
        settingsContent = {
            com.shihua66666.nuntra.ui.settings.SettingsScreen(
                onBack = { screen = Screen.Home },
                readiness = readiness,
                themeColors = AppColors.PRESETS,
                currentThemeId = themeId,
                onSelectTheme = { id -> scope.launch { container.themeController.selectTheme(id) } },
                masterSwitchEnabled = masterSwitchEnabled,
                onToggleMasterSwitch = applyMaster,
                monitorWechat = monitorWechat,
                monitorQq = monitorQq,
                monitorWeCom = monitorWeCom,
                monitorTim = monitorTim,
                onToggleMonitorWechat = { v -> scope.launch { container.appPreferences.setMonitorWechat(v) } },
                onToggleMonitorQq = { v -> scope.launch { container.appPreferences.setMonitorQq(v) } },
                onToggleMonitorWeCom = { v -> scope.launch { container.appPreferences.setMonitorWeCom(v) } },
                onToggleMonitorTim = { v -> scope.launch { container.appPreferences.setMonitorTim(v) } },
                smsMonitoringEnabled = smsMonitoringEnabled,
                onToggleSmsMonitoring = { v -> scope.launch { container.settingsRepository.setSmsMonitoringEnabled(v) } },
                smsKeywords = smsKeywordSet,
                onAddSmsKeyword = { word -> scope.launch { container.settingsRepository.addSmsKeyword(word) } },
                onRemoveSmsKeyword = { word -> scope.launch { container.settingsRepository.removeSmsKeyword(word) } },
                onResetSmsKeywords = { scope.launch { container.settingsRepository.resetSmsKeywords() } },
                resolvedSmsPackages = smsResolution.packages,
                smsNeedsManualSelection = smsResolution.needsManualSelection,
                messageLimit = messageLimit,
                onChangeMessageLimit = { v -> scope.launch { container.appPreferences.setMessageLimit(v) } },
                retentionHours = retentionHoursPref,
                onChangeRetentionHours = { v -> scope.launch { container.appPreferences.setRetentionHours(v) } },
                resumeTick = resumeTick,
                // 「重新检测」按钮：直接把 resumeTick 加一即可触发 readiness 重算
                onRefreshReadiness = { onRequestResumeRefresh() },
                // ★ 点击行为开关：默认开启「点击后自动清理已读」
                clearReadOnTap = clearReadOnTapPref,
                onChangeClearReadOnTap = { v ->
                    scope.launch { container.appPreferences.setClearReadOnTap(v) }
                },
                onOpenTagManager = { screen = Screen.TagManager },
                onOpenContacts = { screen = Screen.Contacts },
                onOpenReminder = { screen = Screen.Reminder },
            )
        },
        tagManagerContent = {
            TagManagerScreen(
                tags = tags,
                onAdd = { name, color -> runTagOp { container.tagRepository.addTag(name, color) } },
                onRename = { id, name -> runTagOp { container.tagRepository.renameTag(id, name) } },
                onRecolor = { id, hex -> runTagOp { container.tagRepository.recolorTag(id, hex) } },
                onToggleFlags = { id, priority, muted ->
                    runTagOp { container.tagRepository.updateTagAlertFlags(id, priority, muted) }
                },
                onDelete = { id ->
                    runTagOp { container.tagRepository.deleteTag(id, moveToDefault = true) }
                },
                onMove = { from, to ->
                    // 本地重排后整体提交：TagRepository.reorderTags 要求完整有序 id 列表
                    val ids = tags.map { it.id }.toMutableList()
                    if (from in ids.indices && to in ids.indices) {
                        val moved = ids.removeAt(from)
                        ids.add(to, moved)
                        scope.launch { container.tagRepository.reorderTags(ids) }
                    }
                },
                onBack = { screen = Screen.Settings },
            )
        },
        contactsContent = {
            ContactsScreen(
                contacts = watchedContacts,
                tags = tags,
                keywords = keywordSet,
                onUpsertContact = { name, tagId ->
                    runTagOp { container.tagRepository.upsertContact(name, tagId) }
                },
                onRemoveContact = { name ->
                    scope.launch { container.tagRepository.removeContact(name) }
                },
                onToggleContact = { name, enabled ->
                    scope.launch { container.tagRepository.setContactEnabled(name, enabled) }
                },
                onAddKeyword = { word ->
                    scope.launch { container.settingsRepository.addKeyword(word) }
                },
                onRemoveKeyword = { word ->
                    scope.launch { container.settingsRepository.removeKeyword(word) }
                },
                onBack = { screen = Screen.Settings },
            )
        },
        reminderContent = {
            ReminderScreen(
                alertEnabled = alertEnabled,
                onToggleAlert = { v -> scope.launch { container.appPreferences.setAlertEnabled(v) } },
                alertVolume = alertVolume,
                onChangeVolume = { v -> scope.launch { container.appPreferences.setAlertVolume(v) } },
                alertSound = alertSound,
                onChangeSound = { v -> scope.launch { container.appPreferences.setAlertSound(v) } },
                repeatEnabled = repeatEnabled,
                onToggleRepeat = { v -> scope.launch { container.appPreferences.setRepeatEnabled(v) } },
                repeatIntervalMinutes = repeatIntervalPref,
                onChangeInterval = { v ->
                    scope.launch { container.appPreferences.setRepeatIntervalMinutes(v) }
                },
                repeatMaxTimes = repeatMaxPref,
                onChangeMaxTimes = { v ->
                    scope.launch { container.appPreferences.setRepeatMaxTimes(v) }
                },
                onBack = { screen = Screen.Settings },
            )
        },
        homeContent = {
            MainScreen(
                colors = colors,
                readiness = readiness,
                masterSwitchEnabled = masterSwitchEnabled,
                messageCount = messages.size,
                unreadCount = unread,
                latestMessageAt = messages.firstOrNull()?.sortTime,
                windowAttached = windowAttached,
                onOpenSettings = { screen = Screen.Settings },
                onStartService = {
                    // 手动启动也走编排逻辑：缺权限时提示并引导，而不是静默失败
                    OverlayOrchestrator.setMasterSwitch(
                        context = ServiceLocator.context,
                        enabled = true,
                        scope = scope,
                    )
                },
                onStopService = {
                    runCatching { OverlayService.stop(ServiceLocator.context) }
                        .onFailure { Logx.swallow("MainActivity", "stopService", it) }
                },
                onClearMessages = { container.messageStore.clear() },
                onToggleMasterSwitch = applyMaster,
            )
        },
    )
}


/**
 * 初始化失败页。
 *
 * 存在的唯一目的：让「启动失败」变成一句人话，而不是闪退或白屏。
 * 这页本身不依赖任何容器数据，因此它一定画得出来。
 */
@Composable
private fun InitFailureScreen(reason: String?, reasonHint: String) {
    val c = com.shihua66666.nuntra.ui.theme.LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "Nuntra", color = c.textPrimary, fontSize = 20.sp)
        Text(text = "初始化未完成", color = c.warning, fontSize = 15.sp)
        Text(
            text = reason ?: "容器尚未初始化（未知原因）",
            color = c.textSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(text = "可以尝试：", color = c.textPrimary, fontSize = 13.sp)
        Text(text = "· 完全退出应用后重新打开", color = c.textSecondary, fontSize = 13.sp)
        Text(text = "· 检查应用是否被系统限制后台、存储或自启动", color = c.textSecondary, fontSize = 13.sp)
        Text(text = "· 检查 Manifest 的 application 配置：" + reasonHint, color = c.textSecondary, fontSize = 13.sp)
    }
}
