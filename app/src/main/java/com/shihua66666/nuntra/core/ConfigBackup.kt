package com.shihua66666.nuntra.core

import android.content.Context
import com.shihua66666.nuntra.BuildConfig
import com.shihua66666.nuntra.data.TagOpResult
import com.shihua66666.nuntra.model.Tag
import com.shihua66666.nuntra.model.WatchedContact
import com.shihua66666.nuntra.overlay.OverlayOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备份格式版本。
 *
 * 导入时若文件里的 schema **高于**本常量，说明文件来自更新的版本，
 * 宁可拒绝也不要按旧规则瞎猜 —— 猜错会静默写坏用户配置。
 */
const val BACKUP_SCHEMA = 1

/**
 * 可导出的配置快照。
 *
 * 覆盖范围：标签、关注人、主题、消息上限与保留、提醒音与重复提醒、
 * 点击行为、监控范围、关键词（含短信关键词）、总开关。
 *
 * 全部字段都可空（除 schema）：导入时**只覆盖文件里存在的字段**，
 * 这样旧版本的备份文件导入到新版本时，新版本新增的开关会保持原值而不是被重置。
 *
 * 刻意不包含：消息内容、悬浮窗位置/尺寸、崩溃记录。
 *   · 消息内容属于隐私数据，导出会把它带进普通文件；
 *   · 位置/尺寸与当前屏幕绑定，跨设备导入没有意义。
 */
@Serializable
data class ConfigBackup(
    val schema: Int = BACKUP_SCHEMA,
    val exportedAt: Long = 0L,
    val appVersion: String = "",

    // ── 标签与关注人（分类体系的核心）──
    val tags: List<Tag> = emptyList(),
    val contacts: List<WatchedContact> = emptyList(),
    val keywords: List<String> = emptyList(),
    val smsKeywords: List<String> = emptyList(),

    // ── 外观与容量 ──
    val themeId: String? = null,
    val messageLimit: Int? = null,
    val retentionHours: Int? = null,

    // ── 提醒 ──
    val alertEnabled: Boolean? = null,
    val alertVolume: Float? = null,
    val alertSound: String? = null,
    val repeatEnabled: Boolean? = null,
    val repeatIntervalMinutes: Int? = null,
    val repeatMaxTimes: Int? = null,
    val clearReadOnTap: Boolean? = null,

    // ── 监控范围 ──
    val monitorWechat: Boolean? = null,
    val monitorQq: Boolean? = null,
    val monitorWeCom: Boolean? = null,
    val monitorTim: Boolean? = null,
    val smsMonitoringEnabled: Boolean? = null,
    val masterSwitchEnabled: Boolean? = null,
)

/**
 * 配置的导出与导入。
 *
 * 这是「覆盖安装失败必须先卸载，导致数据全丢」的紧急备选方案：
 * 即使签名没固定好，用户也能先导出配置、卸载、装新版、再导入回来。
 *
 * 注意命名：本对象叫 BackupManager 而不是 ConfigBackup ——
 * Kotlin 不允许同一包里存在同名的 class 与 object，
 * 而上面的数据类已经占用了 ConfigBackup 这个名字。
 */
object BackupManager {

    private const val TAG = "ConfigBackup"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // 容错：将来新增字段时，旧版本导入新文件不会因为未知字段直接失败
        ignoreUnknownKeys = true
    }

    /** 采集当前配置。全部读取都走 firstOrNull，任何一项读失败都不影响其余。 */
    suspend fun snapshot(container: AppContainer): ConfigBackup {
        val prefs = container.appPreferences
        val settings = container.settingsRepository
        return ConfigBackup(
            exportedAt = System.currentTimeMillis(),
            appVersion = runCatching { BuildConfig.VERSION_NAME }.getOrDefault(""),
            tags = container.tagRepository.tags.value,
            contacts = container.tagRepository.watchedContacts.value,
            keywords = settings.keywords.firstOrNull()?.sorted().orEmpty(),
            smsKeywords = settings.smsKeywords.firstOrNull()?.sorted().orEmpty(),
            themeId = prefs.themeId.firstOrNull(),
            messageLimit = prefs.messageLimit.firstOrNull(),
            retentionHours = prefs.retentionHours.firstOrNull(),
            alertEnabled = prefs.alertEnabled.firstOrNull(),
            alertVolume = prefs.alertVolume.firstOrNull(),
            alertSound = prefs.alertSound.firstOrNull(),
            repeatEnabled = prefs.repeatEnabled.firstOrNull(),
            repeatIntervalMinutes = prefs.repeatIntervalMinutes.firstOrNull(),
            repeatMaxTimes = prefs.repeatMaxTimes.firstOrNull(),
            clearReadOnTap = prefs.clearReadOnTap.firstOrNull(),
            monitorWechat = prefs.monitorWechat.firstOrNull(),
            monitorQq = prefs.monitorQq.firstOrNull(),
            monitorWeCom = prefs.monitorWeCom.firstOrNull(),
            monitorTim = prefs.monitorTim.firstOrNull(),
            smsMonitoringEnabled = settings.smsMonitoringEnabled.firstOrNull(),
            masterSwitchEnabled = prefs.masterSwitchEnabled.firstOrNull(),
        )
    }

    /**
     * 显式取序列化器。
     *
     * ★ 为什么不用 `json.encodeToString(value)` / `json.decodeFromString<T>(text)`：
     *   那两个是 `kotlinx.serialization` 包里的**顶层扩展函数**，必须显式 import
     *   （`import kotlinx.serialization.encodeToString`）。忘了 import 时编译器只报
     *   unresolved reference，看起来很像「库版本不对」，极易误判。
     *   而下面这种「显式传序列化器」的形式是 StringFormat 的**成员函数**，
     *   零 import、与项目里 TagRepository 的写法完全一致 —— 不会出这类问题。
     */
    private val backupSerializer = ConfigBackup.serializer()

    /** 序列化为 JSON 文本。失败抛异常，由调用方兜住（不要静默返回空串）。 */
    fun encode(backup: ConfigBackup): String = json.encodeToString(backupSerializer, backup)

    /** 解析 JSON 文本。 */
    fun decode(text: String): ConfigBackup {
        val parsed = json.decodeFromString(backupSerializer, text)
        if (parsed.schema > BACKUP_SCHEMA) {
            throw IllegalArgumentException(
                "备份文件版本过新（schema=" + parsed.schema + " > 支持的 " + BACKUP_SCHEMA + "）",
            )
        }
        return parsed
    }

    /**
     * 应用一份备份，返回给人看的结果摘要。
     *
     * 顺序有讲究：
     *   ① 先写标签与关注人 —— 关注人里的 tagId 必须能在标签里找到；
     *   ② 再写各项偏好；
     *   ③ 最后处理总开关 —— 它会启动/停止前台服务，放最后避免中途被权限提示打断。
     */
    suspend fun restore(
        container: AppContainer,
        backup: ConfigBackup,
        context: Context,
        scope: CoroutineScope,
    ): String {
        val prefs = container.appPreferences
        val settings = container.settingsRepository
        val notes = mutableListOf<String>()

        // ① 标签与关注人
        var contactCount = 0
        if (backup.tags.isNotEmpty()) {
            val result = container.tagRepository.replaceAll(backup.tags, backup.contacts)
            if (result is TagOpResult.Failure) {
                return "导入失败：标签数据不合法（" + result.error.name + "）"
            }
            contactCount = container.tagRepository.watchedContacts.value.size
            notes += backup.tags.size.toString() + " 个标签"
            notes += contactCount.toString() + " 个关注人"
        }
        if (backup.keywords.isNotEmpty() || backup.tags.isNotEmpty()) {
            settings.replaceKeywords(backup.keywords.toSet())
        }
        if (backup.smsKeywords.isNotEmpty()) {
            settings.replaceSmsKeywords(backup.smsKeywords.toSet())
        }

        // ② 偏好（逐项判空，只覆盖备份里存在的字段）
        backup.themeId?.let { prefs.setThemeId(it) }
        backup.messageLimit?.let { prefs.setMessageLimit(it) }
        backup.retentionHours?.let { prefs.setRetentionHours(it) }
        backup.alertEnabled?.let { prefs.setAlertEnabled(it) }
        backup.alertVolume?.let { prefs.setAlertVolume(it) }
        backup.alertSound?.let { prefs.setAlertSound(it) }
        backup.repeatEnabled?.let { prefs.setRepeatEnabled(it) }
        backup.repeatIntervalMinutes?.let { prefs.setRepeatIntervalMinutes(it) }
        backup.repeatMaxTimes?.let { prefs.setRepeatMaxTimes(it) }
        backup.clearReadOnTap?.let { prefs.setClearReadOnTap(it) }
        backup.monitorWechat?.let { prefs.setMonitorWechat(it) }
        backup.monitorQq?.let { prefs.setMonitorQq(it) }
        backup.monitorWeCom?.let { prefs.setMonitorWeCom(it) }
        backup.monitorTim?.let { prefs.setMonitorTim(it) }
        backup.smsMonitoringEnabled?.let { settings.setSmsMonitoringEnabled(it) }

        // ③ 总开关：走编排器，保证「服务状态」与「开关状态」一致 ——
        //    直接写偏好会出现「开关是开的但服务没起来」。
        backup.masterSwitchEnabled?.let { enabled ->
            runCatching {
                OverlayOrchestrator.setMasterSwitch(
                    context = context,
                    enabled = enabled,
                    scope = scope,
                )
            }.onFailure { Logx.swallow(TAG, "restore-masterSwitch", it) }
            notes += if (enabled) "总开关已开" else "总开关已关"
        }

        val summary = if (notes.isEmpty()) "备份里没有可导入的配置" else notes.joinToString("、")
        Logx.i(TAG, "导入完成：" + summary)
        return "导入完成：" + summary
    }
}
