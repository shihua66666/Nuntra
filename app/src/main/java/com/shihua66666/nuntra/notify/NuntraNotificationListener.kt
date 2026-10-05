package com.shihua66666.nuntra.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.data.MessageStore
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

    private val pendingIntents = PendingIntentResolver()

    /** 运行期快照：由下面的 flow 收集协程持续刷新，避免每条通知都读 DataStore。 */
    @Volatile private var masterSwitchOn: Boolean = false
    @Volatile private var scope: MonitorScope = MonitorScope()
    @Volatile private var smsPackages: Set<String> = emptySet()
    @Volatile private var smsMonitoringEnabled: Boolean = true
    @Volatile private var smsKeywords: Set<String> = com.shihua66666.nuntra.data.SettingsRepository.DEFAULT_SMS_KEYWORDS
    @Volatile private var keywords: Set<String> = emptySet()
    @Volatile private var watchedNames: List<String> = emptyList()
    @Volatile private var watchedConfiguredCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        Logx.i(TAG, "监听服务 onCreate")
        observeSettings()
        observeSmsPackages()
        observeMessages()
        startLowFrequencySweep()
    }

    override fun onDestroy() {
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

                val stored = ServiceLocator.c.messageStore.add(message) { tagId ->
                    ServiceLocator.c.tagRepository.priorityOf(tagId)
                }
                if (!stored) return@runCatching

                pendingIntents.remember(sbn, dedupKey)

                // ★ 第 8 步挂载点：提醒音与重复提醒在这里触发。
                // 现在只记录，不播声音 —— 避免在第 1 步引入音频焦点与定时器复杂度。
                Logx.i(
                    TAG,
                    "入库 " + source.displayName + " | " + extracted.title.orEmpty() +
                        " | 标签=" + resolution.tagId +
                        (if (isPriority) " | 特殊关注" else ""),
                )
            }.onFailure { tr -> Logx.swallow(TAG, "handleAccepted", tr) }
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
        fun requestRebind(service: NotificationListenerService) {
            // 用 (ComponentName) 重载：它在 API 24 就已存在，无需 @RequiresApi；
            // 无参重载是 API 26 才加入的，用它会引入不必要的最低版本约束。
            runCatching {
                service.requestRebind(
                    android.content.ComponentName(service, NuntraNotificationListener::class.java),
                )
            }.onFailure { Logx.swallow(TAG, "requestRebind", it) }
        }
    }
}
