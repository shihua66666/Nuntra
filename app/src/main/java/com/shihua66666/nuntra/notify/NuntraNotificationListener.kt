package com.shihua66666.nuntra.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.shihua66666.nuntra.core.AlertPlayer
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.monitor.ExtractedNotification
import com.shihua66666.nuntra.monitor.FilterContext
import com.shihua66666.nuntra.monitor.FilterDecision
import com.shihua66666.nuntra.monitor.NotificationExtractor
import com.shihua66666.nuntra.monitor.NotificationFilter
import com.shihua66666.nuntra.monitor.PendingIntentResolver
import com.shihua66666.nuntra.monitor.SmsAppResolver
import com.shihua66666.nuntra.model.MonitorScope
import com.shihua66666.nuntra.model.SourceApp
import com.shihua66666.nuntra.model.TerminalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * 通知监听服务：整条采集链路。
 *
 * 职责（严格按顺序，任一步不通过就 return，绝不往下走）：
 *  0. 总开关         —— 关闭时立即 return，不读 extras、不解析、不入库（彻底省电）
 *  1. 包名白名单 + 分闸
 *  2. 噪音过滤（ongoing / 汇总 / 空内容）
 *  3. 语义匹配（关注人 / 关键词 / 短信关键词）
 *  4. 标签解析（TagResolver）
 *  5. 去重 + 入库（MessageStore）
 *  6. 保存 contentIntent（PendingIntentResolver）
 *  7. 提醒音与重复提醒（第 8 步接入，本步只落后两个挂载点）
 *
 * 本类**不持有 UI、不操作 WindowManager**，只做收口 ——
 * 这样监听服务被系统反复重启也不会牵连悬浮窗生命周期。
 */
class NuntraNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * contentIntent 解析器：**改为与 OverlayService 共用同一实例**。
     *
     * 之前这里是私有实例，导致悬浮窗点击消息时查不到 PendingIntent，
     * 只能标记已读而无法跳转。现在统一由 AppContainer 持有。
     */
    private val pendingIntents: PendingIntentResolver
        get() = ServiceLocator.c.pendingIntents

    /** 运行期快照：由下面的 flow 收集协程持续刷新，避免每条通知都读 DataStore。 */
    @Volatile private var masterSwitchOn: Boolean = false
    @Volatile private var scope: MonitorScope = MonitorScope()
    @Volatile private var smsPackages: Set<String> = emptySet()
    @Volatile private var smsMonitoringEnabled: Boolean = true
    @Volatile private var smsKeywords: Set<String> = com.shihua66666.nuntra.data.SettingsRepository.DEFAULT_SMS_KEYWORDS
    @Volatile private var keywords: Set<String> = emptySet()
    @Volatile private var watchedNames: List<String> = emptyList()
    @Volatile private var watchedConfiguredCount: Int = 0

    // ── 提醒音与重复提醒的运行期快照（由 observeSettings 持续刷新）──
    @Volatile private var alertEnabled: Boolean = true
    @Volatile private var alertVolume: Float = 1f
    @Volatile private var alertSound: String = AlertPlayer.SOUND_BUILTIN
    @Volatile private var repeatEnabled: Boolean = true
    @Volatile private var repeatIntervalMinutes: Int = 2
    @Volatile private var repeatMaxTimes: Int = 3

    /**
     * 每条特殊关注消息的重复提醒任务。
     *
     * 用 ConcurrentHashMap 而不是普通 map：写入发生在通知回调线程，
     * 清理发生在协程线程，必须线程安全。
     */
    private val reminderJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    override fun onCreate() {
        super.onCreate()
        Logx.i(TAG, "监听服务 onCreate")
        observeSettings()
        observeSmsPackages()
        observeMessages()
        startLowFrequencySweep()
    }

    override fun onDestroy() {
        // 重复提醒任务随服务一起结束（它们本来就是「监听服务在跑才提醒」的语义）
        runCatching {
            reminderJobs.values.forEach { it.cancel() }
            reminderJobs.clear()
        }
        AlertPlayer.stop()
        Logx.i(TAG, "监听服务 onDestroy")
        serviceScope.cancel()
        pendingIntents.clear()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Logx.i(TAG, "已连接通知栏（通知使用权正常）")
    }

    /**
     * 被系统断开时触发。
     *
     * ColorOS 等定制系统会在某些场景回收通知使用权。这里只记录，
     * 真正的自愈是「回前台复检 + 状态可见」，由 UI 层负责。
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Logx.w(TAG, "通知使用权被断开：可能被系统回收，需回前台复检")
    }

    // ── 主链路 ───────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 第 0 层：总闸。要求「不做任何解析」，因此在读取 extras 之前就返回。
        if (!masterSwitchOn) return

        try {
            // 对无关包名，连 extras 都不必读 —— 先做最便宜的包名判定。
            val packageName = sbn.packageName ?: return
            if (!SourceApp.isMonitored(packageName, smsPackages)) return

            val extracted = NotificationExtractor.extract(sbn)
            val context = FilterContext(
                masterSwitchEnabled = true,
                scope = scope,
                smsPackages = smsPackages,
                watchedContactNames = watchedNames,
                keywords = keywords,
                smsMonitoringEnabled = smsMonitoringEnabled,
                smsKeywords = smsKeywords,
                watchedConfiguredCount = watchedConfiguredCount,
            )
            when (val decision = NotificationFilter.decide(sbn, extracted, context)) {
                is FilterDecision.Reject -> {
                    Logx.d(TAG, "丢弃（" + decision.reason + "）：" + packageName)
                    return
                }
                is FilterDecision.Accept -> {
                    handleAccepted(sbn, extracted, decision.source)
                }
            }
        } catch (tr: Throwable) {
            // 监听服务里任何未捕获异常都会导致进程级风险，必须完全兜住。
            Logx.swallow(TAG, "onNotificationPosted", tr)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        runCatching { pendingIntents.forget(sbn) }
            .onFailure { Logx.swallow(TAG, "onNotificationRemoved", it) }
    }

    private fun handleAccepted(
        sbn: StatusBarNotification,
        extracted: ExtractedNotification,
        source: SourceApp,
    ) {
        val packageName = sbn.packageName ?: return
        // 短信来源再用包名二次确认一次：通知里的 category 等字段不可靠。
        val dedupKey = sha256(
            packageName + "|" +
                extracted.title.orEmpty() + "|" +
                extracted.mergedBody + "|" +
                sbn.postTime
        )

        serviceScope.launch {
            runCatching {
                val haystacks = extracted.haystacks
                val keywordsSnapshot = keywords
                val resolution = ServiceLocator.c.tagResolver.resolve(haystacks, keywordsSnapshot)
                val isPriority = ServiceLocator.c.tagResolver.isPriorityTag(resolution.tagId)

                val message = TerminalMessage(
                    id = ServiceLocator.c.messageStore.nextId(),
                    dedupKey = dedupKey,
                    packageName = packageName,
                    source = source,
                    contact = extracted.title,
                    conversationTitle = extracted.conversationTitle,
                    body = extracted.mergedBody,
                    subText = extracted.subText,
                    groupKey = extracted.groupKey,
                    matchedKeywords = resolution.matchedKeywords,
                    matchedContacts = resolution.matchedContacts,
                    tagId = resolution.tagId,
                    postedAt = sbn.postTime,
                    isPriority = isPriority,
                )

                // 标签优先级已由 MessageStore 构造时注入，这里不再传递
                val stored = ServiceLocator.c.messageStore.add(message)
                if (!stored) return@runCatching

                pendingIntents.remember(sbn, dedupKey)

                // ★ 提醒音与重复提醒：入库成功后立即生效。
                //
                //   触发条件（三者同时满足）：
                //     1. 所属标签勾选了「特殊关注」；
                //     2. 该标签未被静音（priorityAlert 与 muted 在仓库层互斥，这里再确认一次）；
                //     3. 全局提醒音开关为开。
                //
                //   之前这里只写日志、不播声音（第 1 步的占位），
                //   导致「特殊关注」这个功能实际上完全没有效果。
                val isMuted = ServiceLocator.c.tagResolver.isMutedTag(resolution.tagId)
                Logx.i(
                    TAG,
                    "入库 " + source.displayName + " | " + extracted.title.orEmpty() +
                        " | 标签=" + resolution.tagId +
                        (if (isPriority) " | 特殊关注" else "") +
                        (if (isMuted) " | 静音" else ""),
                )
                if (isPriority && !isMuted && alertEnabled) {
                    AlertPlayer.play(this@NuntraNotificationListener, alertVolume, alertSound)
                    scheduleRepeatReminder(message.id)
                }
            }.onFailure { tr -> Logx.swallow(TAG, "handleAccepted", tr) }
        }
    }

    /**
     * 安排重复提醒。
     *
     * 规则（需求）：间隔默认 2 分钟，次数默认 3 次（最高 10 次，0 表示不限）。
     *
     * 每一跳之前都检查消息是否仍未读：用户点开看过就不再打扰 ——
     * 这是任务结束的主要途径，不必依赖外部显式取消。
     *
     * 生命周期：任务挂在 serviceScope 上，监听服务被销毁时自动取消。
     */
    private fun scheduleRepeatReminder(messageId: Long) {
        if (!repeatEnabled) return
        val maxTimes = repeatMaxTimes
        val intervalMs = repeatIntervalMinutes.coerceAtLeast(1) * 60_000L

        reminderJobs.remove(messageId)?.cancel()
        reminderJobs[messageId] = serviceScope.launch {
            var sent = 0
            while (isActive && (maxTimes <= 0 || sent < maxTimes)) {
                delay(intervalMs)
                if (!isActive) break
                if (!alertEnabled) break
                val stillUnread = ServiceLocator.appContainerOrNull
                    ?.messageStore
                    ?.isUnread(messageId) == true
                if (!stillUnread) break
                AlertPlayer.play(this@NuntraNotificationListener, alertVolume, alertSound)
                ServiceLocator.appContainerOrNull?.messageStore?.bumpRepeat(messageId)
                sent += 1
            }
            reminderJobs.remove(messageId)
        }
    }

    // ── 设置同步 ─────────────────────────────────────────────────

    private fun observeSettings() {
        val container = ServiceLocator.c
        serviceScope.launch {
            container.appPreferences.masterSwitchEnabled
                .catch { emit(false) }
                .collectLatest { enabled ->
                    masterSwitchOn = enabled
                    Logx.i(TAG, "总开关 = " + enabled)
                }
        }
        serviceScope.launch {
            container.appPreferences.monitorWechat.collectLatest { wechat ->
                scope = scope.copy(wechat = wechat)
            }
        }
        serviceScope.launch {
            container.appPreferences.monitorQq.collectLatest { qq -> scope = scope.copy(qq = qq) }
        }
        serviceScope.launch {
            container.appPreferences.monitorWeCom.collectLatest { weCom -> scope = scope.copy(weCom = weCom) }
        }
        serviceScope.launch {
            container.appPreferences.monitorTim.collectLatest { tim -> scope = scope.copy(tim = tim) }
        }
        serviceScope.launch {
            container.settingsRepository.smsMonitoringEnabled.collectLatest { enabled ->
                smsMonitoringEnabled = enabled
            }
        }
        serviceScope.launch {
            container.settingsRepository.smsKeywords.collectLatest { words -> smsKeywords = words }
        }
        serviceScope.launch {
            container.settingsRepository.keywords.collectLatest { words -> keywords = words }
        }
        serviceScope.launch {
            container.tagRepository.watchedContacts.collectLatest { contacts ->
                watchedConfiguredCount = contacts.size
                watchedNames = contacts.filter { it.enabled }.map { it.name }
            }
        }
        // ── 提醒音与重复提醒的设置同步 ──────────────────────────
        serviceScope.launch {
            container.appPreferences.alertEnabled.collectLatest { v -> alertEnabled = v }
        }
        serviceScope.launch {
            container.appPreferences.alertVolume.collectLatest { v -> alertVolume = v }
        }
        serviceScope.launch {
            container.appPreferences.alertSound.collectLatest { v -> alertSound = v }
        }
        serviceScope.launch {
            container.appPreferences.repeatEnabled.collectLatest { v -> repeatEnabled = v }
        }
        serviceScope.launch {
            container.appPreferences.repeatIntervalMinutes.collectLatest { v ->
                repeatIntervalMinutes = v
            }
        }
        serviceScope.launch {
            container.appPreferences.repeatMaxTimes.collectLatest { v -> repeatMaxTimes = v }
        }
    }

    /** 短信 App 包名：首次解析 + 用户手动指定后重新解析。 */
    private fun observeSmsPackages() {
        val container = ServiceLocator.c
        serviceScope.launch {
            container.tagRepository.ensureInitialized()
        }
        serviceScope.launch {
            container.settingsRepository.resolvedSmsPackages.collectLatest { userConfirmed ->
                val resolution = SmsAppResolver.resolve(applicationContext, userConfirmed)
                smsPackages = resolution.packages
                if (resolution.needsManualSelection) {
                    Logx.w(TAG, "短信 App 未识别，短信监控需要用户手动指定（设置页有提示）")
                }
            }
        }
    }

    /** 未读数变化时刷新前台服务通知（服务未运行时这里什么都不做）。 */
    private fun observeMessages() {
        val container = ServiceLocator.c
        serviceScope.launch {
            container.messageStore.unreadCount.collectLatest { count ->
                Logx.d(TAG, "未读数更新 = " + count)
            }
        }
    }

    /**
     * 低频定时器。
     *
     * 需求要求「勿用高频唤醒」：这里用协程 delay 而不是 AlarmManager，
     * 5 分钟一次，且只在服务存活期间存在。
     * 真正的过期清理触发点是「新消息入库」（MessageStore.add 内部已做）与
     * 「前台服务启动」，定时器只是兜底，保证在长时间无新消息时也会清理。
     */
    private fun startLowFrequencySweep() {
        serviceScope.launch {
            while (true) {
                delay(SWEEP_INTERVAL_MS)
                runCatching { ServiceLocator.c.messageStore.sweep() }
                    .onFailure { Logx.swallow(TAG, "sweep", it) }
            }
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private fun sha256(input: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) sb.append(String.format("%02x", byte))
        sb.toString()
    }.getOrElse { input.hashCode().toString() }

    companion object {
        private const val TAG = "Listener"

        /** 兜底清理间隔：5 分钟。刻意低频，避免无谓唤醒。 */
        private const val SWEEP_INTERVAL_MS = 5L * 60L * 1000L

        /**
         * 通知使用权是否已授予。
         *
         * 用静态方法暴露，方便 UI 层直接询问而不用绑定服务实例。
         */
        fun isAccessGranted(context: android.content.Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

        /** 请求系统重新绑定监听服务（权限被临时回收后使用）。 */
        // 注意：这里刻意**不**封装 NotificationListenerService.requestRebind(...)。
        // 该 API 在不同版本上的可用重载不一致（CI 上直接报 Unresolved reference），
        // 而它对本项目并非必需：权限被回收后，正确做法是让用户回设置页重新授权，
        // 由系统自行重绑。少一个 API 依赖，就少一处版本风险。

    }
}
