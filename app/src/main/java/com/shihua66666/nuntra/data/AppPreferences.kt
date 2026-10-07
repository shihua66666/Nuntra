package com.shihua66666.nuntra.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shihua66666.nuntra.core.Logx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 悬浮窗位置（px，相对屏幕左上角）。 */
data class OverlayPosition(val x: Int, val y: Int)

/** 悬浮窗尺寸（px）。 */
data class OverlaySize(val width: Int, val height: Int)

/**
 * 非标签类偏好设置。
 *
 * 全部集中在这里的原因：这些都是「用户设置」，与消息数据生命周期完全无关，
 * 永远不能被清理逻辑碰到。
 */
class AppPreferences(private val context: Context) {

    private val store = context.nuntraDataStore

    // ── 主题 ─────────────────────────────────────────────────────
    val themeId: Flow<String> = store.data.map { it[KEY_THEME] ?: DEFAULT_THEME_ID }

    suspend fun setThemeId(id: String) = store.edit { it[KEY_THEME] = id }

    // ── 消息上限与保留 ───────────────────────────────────────────
    val messageLimit: Flow<Int> = store.data.map {
        (it[KEY_MESSAGE_LIMIT] ?: MessageStore.DEFAULT_LIMIT)
            .coerceIn(MessageStore.MIN_LIMIT, MessageStore.MAX_LIMIT)
    }

    suspend fun setMessageLimit(value: Int) = store.edit {
        it[KEY_MESSAGE_LIMIT] = value.coerceIn(MessageStore.MIN_LIMIT, MessageStore.MAX_LIMIT)
    }

    val retentionHours: Flow<Int> = store.data.map {
        (it[KEY_RETENTION_HOURS] ?: MessageStore.DEFAULT_RETENTION_HOURS)
            .coerceIn(MessageStore.MIN_RETENTION_HOURS, MessageStore.MAX_RETENTION_HOURS)
    }

    suspend fun setRetentionHours(value: Int) = store.edit {
        it[KEY_RETENTION_HOURS] = value.coerceIn(MessageStore.MIN_RETENTION_HOURS, MessageStore.MAX_RETENTION_HOURS)
    }

    // ── 面板尺寸（捏合缩放后持久化，px）──────────────────────────
    /**
     * 展开面板宽度（px）；未设置过返回 null，由调用方用默认值（屏幕宽 75%）。
     *
     * 与小窗尺寸（[overlaySize]）分开存：两者是不同形态的独立偏好，
     * 共用一个键会出现「缩放面板把小窗也改了」的串扰。
     */
    val panelWidth: Flow<Int?> = store.data.map { prefs ->
        val w = prefs[KEY_PANEL_W] ?: return@map null
        if (w <= 0) null else w
    }

    val panelHeight: Flow<Int?> = store.data.map { prefs ->
        val h = prefs[KEY_PANEL_H] ?: return@map null
        if (h <= 0) null else h
    }

    suspend fun setPanelSize(width: Int, height: Int) = store.edit {
        it[KEY_PANEL_W] = width
        it[KEY_PANEL_H] = height
    }

    // ── 点击行为 ─────────────────────────────────────────────────
    /**
     * 点击消息跳转后是否自动清理（隐藏）已读消息。**默认开启。**
     *
     * 为什么默认开：点击跳转会把该条标记已读，但它仍留在列表里，
     * 已读项会把新到的未读消息压在下面，用户得滚动才能看到新消息 ——
     * 这是「点了跳转却感觉消息没变化」的直接原因。
     * 关掉它则保留全部消息（含已读），供需要回看的用户使用。
     */
    val clearReadOnTap: Flow<Boolean> = store.data.map { it[KEY_CLEAR_READ_ON_TAP] ?: true }

    suspend fun setClearReadOnTap(value: Boolean) = store.edit { it[KEY_CLEAR_READ_ON_TAP] = value }

    // ── 提醒音 ───────────────────────────────────────────────────
    val alertEnabled: Flow<Boolean> = store.data.map { it[KEY_ALERT_ENABLED] ?: true }

    suspend fun setAlertEnabled(value: Boolean) = store.edit { it[KEY_ALERT_ENABLED] = value }

    /** 音量缩放：0.0..1.0，作用在 STREAM_NOTIFICATION 上。 */
    val alertVolume: Flow<Float> = store.data.map { (it[KEY_ALERT_VOLUME] ?: 1.0f).coerceIn(0f, 1f) }

    suspend fun setAlertVolume(value: Float) = store.edit { it[KEY_ALERT_VOLUME] = value.coerceIn(0f, 1f) }

    /**
     * 提醒音音源：builtin（内置 alert_priority / alert_fallback）或 system（系统默认通知音）。
     *
     * 存字符串而不是资源 id：资源 id 会随构建变化，存下来必然失效。
     */
    val alertSound: Flow<String> = store.data.map {
        it[KEY_ALERT_SOUND] ?: com.shihua66666.nuntra.core.AlertPlayer.SOUND_BUILTIN
    }

    suspend fun setAlertSound(value: String) = store.edit { it[KEY_ALERT_SOUND] = value }

    // ── 重复提醒 ─────────────────────────────────────────────────
    val repeatEnabled: Flow<Boolean> = store.data.map { it[KEY_REPEAT_ENABLED] ?: true }

    suspend fun setRepeatEnabled(value: Boolean) = store.edit { it[KEY_REPEAT_ENABLED] = value }

    /** 重复提醒间隔（分钟），默认 2 分钟。 */
    val repeatIntervalMinutes: Flow<Int> = store.data.map { (it[KEY_REPEAT_INTERVAL] ?: 2).coerceIn(1, 60) }

    suspend fun setRepeatIntervalMinutes(value: Int) = store.edit { it[KEY_REPEAT_INTERVAL] = value.coerceIn(1, 60) }

    /** 重复提醒次数上限，默认 3 次。 */
    val repeatMaxTimes: Flow<Int> = store.data.map { (it[KEY_REPEAT_MAX] ?: 3).coerceIn(0, 10) }

    suspend fun setRepeatMaxTimes(value: Int) = store.edit { it[KEY_REPEAT_MAX] = value.coerceIn(0, 10) }

    // ── 悬浮窗 ───────────────────────────────────────────────────
    val overlayPosition: Flow<OverlayPosition?> = store.data.map { prefs ->
        val x = prefs[KEY_OVERLAY_X] ?: return@map null
        val y = prefs[KEY_OVERLAY_Y] ?: return@map null
        OverlayPosition(x, y)
    }

    suspend fun setOverlayPosition(x: Int, y: Int) = store.edit {
        it[KEY_OVERLAY_X] = x
        it[KEY_OVERLAY_Y] = y
    }

    val overlaySize: Flow<OverlaySize?> = store.data.map { prefs ->
        val w = prefs[KEY_OVERLAY_W] ?: return@map null
        val h = prefs[KEY_OVERLAY_H] ?: return@map null
        if (w <= 0 || h <= 0) null else OverlaySize(w, h)
    }

    suspend fun setOverlaySize(width: Int, height: Int) = store.edit {
        it[KEY_OVERLAY_W] = width
        it[KEY_OVERLAY_H] = height
    }

    // ── 总监控开关（总闸）────────────────────────────────────────
    /**
     * 总监控开关，唯一的总闸，默认 false。
     *
     * 关闭时：监听服务在收到通知的第一时间直接丢弃（不做任何解析/过滤/入库），
     * 同时停止前台服务并移除悬浮窗 —— 达到彻底省电的目的。
     *
     * 与监控范围开关（monitorXxx）是「总闸 vs 分闸」的关系：
     * 总闸关闭时，即使各分闸全开也不做任何监听。
     */
    val masterSwitchEnabled: Flow<Boolean> = store.data.map { it[KEY_MASTER_SWITCH] ?: false }

    /**
     * 写入总开关。
     *
     * ★ 与其它 setter 不同，这里**失败时向上抛**而不是吞掉：
     *   总开关是用户唯一的关键操作，必须能给出明确反馈（调用方会转成 Toast）；
     *   静默失败会让用户看到「点了没反应」而完全无从排查。
     */
    suspend fun setMasterSwitchEnabled(value: Boolean) {
        Logx.i("AppPreferences", "写入总开关 = " + value + " …")
        store.edit { it[KEY_MASTER_SWITCH] = value }
        // 立即回读确认落盘（同一次 edit 返回后读到的就是新值）
        val readBack = store.data.first()[KEY_MASTER_SWITCH]
        Logx.i("AppPreferences", "写入完成，回读 = " + readBack)
        if (readBack != value) {
            throw IllegalStateException("写入未生效：期望 " + value + " 实际 " + readBack)
        }
    }

    // ── 启动自愈标记 ─────────────────────────────────────────────
    /**
     * 上一次启动是否在「挂载悬浮窗之后」崩溃。
     *
     * 用途：万一悬浮窗渲染持续崩溃，会让用户每次打开 App 都崩、进不去界面。
     * 启动时读这个标记并自动关闭总开关，保证用户**一定能进 App** 去看崩溃日志。
     * 读取失败一律按 false 处理，绝不影响启动。
     */
    val overlayCrashFlag: Flow<Boolean> = store.data.map { it[KEY_OVERLAY_CRASH] ?: false }

    suspend fun setOverlayCrashFlag(value: Boolean) = runCatching {
        store.edit { it[KEY_OVERLAY_CRASH] = value }
    }.onFailure { Logx.swallow("AppPreferences", "setOverlayCrashFlag", it) }

    /**
     * 同步读取崩溃标记（仅供启动自愈使用）。
     *
     * 这里用 runBlocking 是刻意的：它在 Application.onCreate 里执行，
     * 只读一个布尔值、耗时极小，且必须在本帧之前拿到结果。
     * 整个调用被 runCatching 包住，任何异常都退化为 false。
     */
    fun overlayCrashFlagBlocking(): Boolean = runCatching {
        kotlinx.coroutines.runBlocking { store.data.first()[KEY_OVERLAY_CRASH] ?: false }
    }.getOrDefault(false)

    // ── 监控范围（分闸）：四个来源各自独立 ──────────────────────
    /** 微信：默认开。 */
    val monitorWechat: Flow<Boolean> = store.data.map { it[KEY_MONITOR_WECHAT] ?: true }

    suspend fun setMonitorWechat(value: Boolean) = runCatching {
        store.edit { it[KEY_MONITOR_WECHAT] = value }
    }.onFailure { Logx.swallow("AppPreferences", "setMonitorWechat", it) }

    /** QQ（含 com.tencent.mobileqq / mobileqqi）：默认开。 */
    val monitorQq: Flow<Boolean> = store.data.map { it[KEY_MONITOR_QQ] ?: true }

    suspend fun setMonitorQq(value: Boolean) = runCatching {
        store.edit { it[KEY_MONITOR_QQ] = value }
    }.onFailure { Logx.swallow("AppPreferences", "setMonitorQq", it) }

    /** 企业微信：默认关（通知量大，避免过载）。 */
    val monitorWeCom: Flow<Boolean> = store.data.map { it[KEY_MONITOR_WECOM] ?: false }

    suspend fun setMonitorWeCom(value: Boolean) = runCatching {
        store.edit { it[KEY_MONITOR_WECOM] = value }
    }.onFailure { Logx.swallow("AppPreferences", "setMonitorWeCom", it) }

    /** TIM：默认关。 */
    val monitorTim: Flow<Boolean> = store.data.map { it[KEY_MONITOR_TIM] ?: false }

    suspend fun setMonitorTim(value: Boolean) = runCatching {
        store.edit { it[KEY_MONITOR_TIM] = value }
    }.onFailure { Logx.swallow("AppPreferences", "setMonitorTim", it) }

    // ── 一次性读取辅助（服务启动时用，避免多次 first()）───────────
    suspend fun snapshot(): PreferencesSnapshot = store.data.first().let { prefs ->
        PreferencesSnapshot(
            themeId = prefs[KEY_THEME] ?: DEFAULT_THEME_ID,
            messageLimit = (prefs[KEY_MESSAGE_LIMIT] ?: MessageStore.DEFAULT_LIMIT)
                .coerceIn(MessageStore.MIN_LIMIT, MessageStore.MAX_LIMIT),
            retentionHours = (prefs[KEY_RETENTION_HOURS] ?: MessageStore.DEFAULT_RETENTION_HOURS)
                .coerceIn(MessageStore.MIN_RETENTION_HOURS, MessageStore.MAX_RETENTION_HOURS),
            alertEnabled = prefs[KEY_ALERT_ENABLED] ?: true,
            alertVolume = (prefs[KEY_ALERT_VOLUME] ?: 1.0f).coerceIn(0f, 1f),
            repeatEnabled = prefs[KEY_REPEAT_ENABLED] ?: true,
            repeatIntervalMinutes = (prefs[KEY_REPEAT_INTERVAL] ?: 2).coerceIn(1, 60),
            repeatMaxTimes = (prefs[KEY_REPEAT_MAX] ?: 3).coerceIn(0, 10),
            masterSwitchEnabled = prefs[KEY_MASTER_SWITCH] ?: false,
            monitorWechat = prefs[KEY_MONITOR_WECHAT] ?: true,
            monitorQq = prefs[KEY_MONITOR_QQ] ?: true,
            monitorWeCom = prefs[KEY_MONITOR_WECOM] ?: false,
            monitorTim = prefs[KEY_MONITOR_TIM] ?: false,
        )
    }

    /** 读取迁移 flag。 */
    suspend fun migrationFlag(key: String): Boolean = store.data.first()[booleanPreferencesKey(key)] ?: false

    suspend fun setMigrationFlag(key: String, value: Boolean) {
        runCatching { store.edit { it[booleanPreferencesKey(key)] = value } }
            .onFailure { Logx.swallow("AppPreferences", "setMigrationFlag", it) }
    }

    companion object {
        const val DEFAULT_THEME_ID = "glaid_blue"

        private val KEY_THEME = stringPreferencesKey("theme_id")
        private val KEY_MESSAGE_LIMIT = intPreferencesKey("message_limit")
        private val KEY_RETENTION_HOURS = intPreferencesKey("retention_hours")
        private val KEY_ALERT_ENABLED = booleanPreferencesKey("alert_enabled")
        private val KEY_ALERT_VOLUME = floatPreferencesKey("alert_volume")
        private val KEY_ALERT_SOUND = stringPreferencesKey("alert_sound")
        private val KEY_CLEAR_READ_ON_TAP = booleanPreferencesKey("clear_read_on_tap")
        private val KEY_REPEAT_ENABLED = booleanPreferencesKey("repeat_enabled")
        private val KEY_REPEAT_INTERVAL = intPreferencesKey("repeat_interval_minutes")
        private val KEY_REPEAT_MAX = intPreferencesKey("repeat_max_times")
        private val KEY_OVERLAY_X = intPreferencesKey("overlay_x")
        private val KEY_OVERLAY_Y = intPreferencesKey("overlay_y")
        private val KEY_OVERLAY_W = intPreferencesKey("overlay_width")
        private val KEY_PANEL_W = intPreferencesKey("panel_width")
        private val KEY_PANEL_H = intPreferencesKey("panel_height")
        private val KEY_OVERLAY_H = intPreferencesKey("overlay_height")
        private val KEY_MASTER_SWITCH = booleanPreferencesKey("master_switch_enabled")
        private val KEY_OVERLAY_CRASH = booleanPreferencesKey("overlay_crash_flag")
        private val KEY_MONITOR_WECHAT = booleanPreferencesKey("monitor_wechat")
        private val KEY_MONITOR_QQ = booleanPreferencesKey("monitor_qq")
        private val KEY_MONITOR_WECOM = booleanPreferencesKey("monitor_wecom")
        private val KEY_MONITOR_TIM = booleanPreferencesKey("monitor_tim")
    }
}

/** 一次性读取的快照，避免服务启动阶段多次 await DataStore。 */
data class PreferencesSnapshot(
    val themeId: String,
    val messageLimit: Int,
    val retentionHours: Int,
    val alertEnabled: Boolean,
    val alertVolume: Float,
    val repeatEnabled: Boolean,
    val repeatIntervalMinutes: Int,
    val repeatMaxTimes: Int,
    /** 总监控开关（总闸）。 */
    val masterSwitchEnabled: Boolean,
    /** 监控范围（分闸）。 */
    val monitorWechat: Boolean,
    val monitorQq: Boolean,
    val monitorWeCom: Boolean,
    val monitorTim: Boolean,
)
