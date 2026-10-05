package com.shihua66666.nuntra.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.shihua66666.nuntra.core.Logx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 关键词与开关类设置。
 *
 * 关注人存在 TagRepository（它要跟着标签一起迁移），这里只放关键词与开关。
 * 所有配置数据的共同点：**永远不被消息清理逻辑触碰**。
 */
class SettingsRepository(
    private val context: Context,
    private val preferences: com.shihua66666.nuntra.data.AppPreferences,
) {

    private val store = context.nuntraDataStore

    // ── 监控总开关 ───────────────────────────────────────────────
    /** 总开关。关闭后不再入库任何消息（但服务仍在运行）。 */
    val monitoringEnabled: Flow<Boolean> = store.data.map { it[KEY_MONITORING] ?: true }

    suspend fun setMonitoringEnabled(value: Boolean) = runCatching {
        store.edit { it[KEY_MONITORING] = value }
    }.onFailure { Logx.swallow("SettingsRepository", "setMonitoringEnabled", it) }

    // ── 微信/QQ 关键词 ───────────────────────────────────────────
    /**
     * 关键词集合。
     *
     * 注意与关注人的区别：
     *  · 命中关注人 → 继承该关注人的标签；
     *  · 只命中关键词 → 使用兜底标签（isDefault）。
     * 这条规则由 TagResolver 实现。
     */
    val keywords: Flow<Set<String>> = store.data.map { it[KEY_KEYWORDS] ?: emptySet() }

    suspend fun addKeyword(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        runCatching { store.edit { prefs ->
            prefs[KEY_KEYWORDS] = (prefs[KEY_KEYWORDS] ?: emptySet()) + keyword
        } }.onFailure { Logx.swallow("SettingsRepository", "addKeyword", it) }
    }

    suspend fun removeKeyword(keyword: String) {
        runCatching { store.edit { prefs ->
            prefs[KEY_KEYWORDS] = (prefs[KEY_KEYWORDS] ?: emptySet()) - keyword
        } }.onFailure { Logx.swallow("SettingsRepository", "removeKeyword", it) }
    }

    // ── 短信监控 ─────────────────────────────────────────────────
    /** 短信监控开关（默认开启；只用通知监听，不申请短信权限）。 */
    val smsMonitoringEnabled: Flow<Boolean> = store.data.map { it[KEY_SMS_ENABLED] ?: true }

    suspend fun setSmsMonitoringEnabled(value: Boolean) = runCatching {
        store.edit { it[KEY_SMS_ENABLED] = value }
    }.onFailure { Logx.swallow("SettingsRepository", "setSmsMonitoringEnabled", it) }

    /**
     * 短信关键词：只有命中这些词才会入库。
     *
     * 默认预设来自需求：快递、取件、派送、驿站、丰巢、菜鸟。
     * 用户可增删；若用户删空，则退化为「全部短信都入库」而不是「一条都不入」——
     * 后者会让用户以为功能坏了。
     */
    val smsKeywords: Flow<Set<String>> = store.data.map {
        it[KEY_SMS_KEYWORDS] ?: DEFAULT_SMS_KEYWORDS
    }

    suspend fun addSmsKeyword(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        runCatching { store.edit { prefs ->
            prefs[KEY_SMS_KEYWORDS] = (prefs[KEY_SMS_KEYWORDS] ?: DEFAULT_SMS_KEYWORDS) + keyword
        } }.onFailure { Logx.swallow("SettingsRepository", "addSmsKeyword", it) }
    }

    suspend fun removeSmsKeyword(keyword: String) {
        runCatching { store.edit { prefs ->
            prefs[KEY_SMS_KEYWORDS] = (prefs[KEY_SMS_KEYWORDS] ?: DEFAULT_SMS_KEYWORDS) - keyword
        } }.onFailure { Logx.swallow("SettingsRepository", "removeSmsKeyword", it) }
    }

    suspend fun resetSmsKeywords() = runCatching {
        store.edit { it[KEY_SMS_KEYWORDS] = DEFAULT_SMS_KEYWORDS }
    }.onFailure { Logx.swallow("SettingsRepository", "resetSmsKeywords", it) }

    // ── 短信 App 包名（运行时解析结果的缓存）─────────────────────
    /**
     * 用户确认过的短信 App 包名。
     *
     * 自动识别可能失败或识别成多个候选项，此时设置页会提示用户手动指定，
     * 结果存在这里，避免每次启动都重新探测。
     */
    val resolvedSmsPackages: Flow<Set<String>> = store.data.map { it[KEY_SMS_PACKAGES] ?: emptySet() }

    suspend fun setResolvedSmsPackages(packages: Set<String>) = runCatching {
        store.edit { it[KEY_SMS_PACKAGES] = packages }
    }.onFailure { Logx.swallow("SettingsRepository", "setResolvedSmsPackages", it) }

    // ── 总闸与分闸的便捷转发 ─────────────────────────────────────
    // 监听服务在每条通知上都要判定，集中在这里可以少持有两个仓库引用。

    /** 总监控开关（总闸）。 */
    val masterSwitch: Flow<Boolean> get() = preferences.masterSwitchEnabled

    suspend fun setMasterSwitch(value: Boolean) = preferences.setMasterSwitchEnabled(value)

    /**
     * 是否应当继续处理通知（总闸开 且 至少一个来源分闸开）。
     *
     * 这里只做「值不值得继续往下走」的粗判；具体某个包名该不该处理，
     * 由 NotificationFilter 按包名逐项判定。
     */
    suspend fun isMonitoringEnabledNow(): Boolean {
        val snapshot = preferences.snapshot()
        if (!snapshot.masterSwitchEnabled) return false
        return snapshot.monitorWechat || snapshot.monitorQq || snapshot.monitorWeCom || snapshot.monitorTim
    }

    companion object {

        val DEFAULT_SMS_KEYWORDS: Set<String> = setOf("快递", "取件", "派送", "驿站", "丰巢", "菜鸟")

        private val KEY_MONITORING = booleanPreferencesKey("monitoring_enabled")
        private val KEY_KEYWORDS = stringSetPreferencesKey("keywords")
        private val KEY_SMS_ENABLED = booleanPreferencesKey("sms_monitoring_enabled")
        private val KEY_SMS_KEYWORDS = stringSetPreferencesKey("sms_keywords")
        private val KEY_SMS_PACKAGES = stringSetPreferencesKey("sms_packages")
    }
}
