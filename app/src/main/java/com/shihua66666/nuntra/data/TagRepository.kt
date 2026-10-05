package com.shihua66666.nuntra.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.model.Tag
import com.shihua66666.nuntra.model.WatchedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 标签与关注人仓库。
 *
 * 设计要点：
 *  · 标签是**唯一真相源**，所有校验（长度/非空/重名/颜色）都在这里强制，不依赖 UI 自觉。
 *  · isDefault 是兜底标签的身份标识；删除时必须迁移，绝不留下悬空 tagId。
 *  · 首次启动写入四个默认标签，用 migration flag 标记，只跑一次；
 *    任何异常都降级为默认标签，绝不崩溃。
 */
class TagRepository(private val context: Context) {

    private val store = context.nuntraDataStore

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val tagCodec = ListSerializer(Tag.serializer())

    private val contactCodec = ListSerializer(WatchedContact.serializer())

    private val _tags = MutableStateFlow(DEFAULT_TAGS)

    private val _watched = MutableStateFlow<List<WatchedContact>>(emptyList())

    /** 标签列表，顺序即优先级。 */
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    /** 关注人列表。 */
    val watchedContacts: StateFlow<List<WatchedContact>> = _watched.asStateFlow()

    /** 兜底标签。理论上一定存在；若数据损坏则回退到 [DEFAULT_TAGS] 里的兜底项。 */
    fun defaultTag(current: List<Tag> = _tags.value): Tag =
        current.firstOrNull { it.isDefault } ?: current.firstOrNull() ?: DEFAULT_TAGS[1]

    /** 优先级查询：未知标签给一个很大值，排到最后而不是崩。 */
    fun priorityOf(tagId: String): Int =
        _tags.value.firstOrNull { it.id == tagId }?.priority ?: Int.MAX_VALUE - 1

    fun tagById(tagId: String): Tag? = _tags.value.firstOrNull { it.id == tagId }

    /** 偏好设置中持久化的标签 JSON（供设置页导出/调试查看）。 */
    val tagsJson: Flow<String> = store.data.map { it[KEY_TAGS] ?: "" }

    /**
     * 确保标签已初始化（幂等）。
     *
     * 由 Application 预热与前台服务启动时各调用一次；重复调用无副作用。
     */
    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = store.data.first()
            val migrated: Boolean = prefs[KEY_TAGS_INITIALIZED] ?: false
            val rawTags = prefs[KEY_TAGS].orEmpty()
            val rawContacts = prefs[KEY_WATCHED].orEmpty()

            val loadedTags = if (migrated) {
                decodeTagsOrNull(rawTags) ?: run {
                    Logx.w("TagRepository", "标签 JSON 解析失败，降级为默认预设")
                    DEFAULT_TAGS
                }
            } else {
                migrateLegacyOrSeed(rawTags)
            }

            val loadedContacts = decodeContactsOrNull(rawContacts).orEmpty()

            // 不变量修正：至少 1 个标签、有且仅有一个兜底、priority 连续
            val normalized = normalize(loadedTags)

            _tags.value = normalized
            _watched.value = loadedContacts

            // 迁移的结果写回磁盘（只跑一次）
            store.edit { prefs ->
                prefs[KEY_TAGS] = json.encodeToString(tagCodec, normalized)
                prefs[KEY_WATCHED] = json.encodeToString(contactCodec, loadedContacts)
                prefs[KEY_TAGS_INITIALIZED] = true
            }
            Logx.i("TagRepository", "标签初始化完成，共 " + normalized.size + " 个，关注人 " + loadedContacts.size + " 个")
        }.onFailure { tr ->
            // 降级路径：磁盘读写或序列化失败时，至少在内存里提供可用默认值
            Logx.swallow("TagRepository", "ensureInitialized", tr)
            _tags.value = DEFAULT_TAGS
            _watched.value = emptyList()
        }
    }

    // ── 标签 CRUD ────────────────────────────────────────────────

    /** 新增标签。名称非法或重名时返回 [TagOpResult.Failure]。 */
    suspend fun addTag(name: String, colorHex: String, priorityAlert: Boolean = false, muted: Boolean = false): TagOpResult {
        val trimmed = name.trim()
        validateName(trimmed, ignoreId = null)?.let { return it }
        val color = normalizeColor(colorHex)
        val current = _tags.value
        val created = Tag(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            color = color,
            priority = current.size,
            isDefault = false,
            priorityAlert = priorityAlert && !muted,
            muted = muted && !priorityAlert,
        )
        persistTags(current + created)
        return TagOpResult.Success
    }

    /** 重命名。id 不变，因此已绑定关注人自动跟随。 */
    suspend fun renameTag(id: String, newName: String): TagOpResult {
        val trimmed = newName.trim()
        validateName(trimmed, ignoreId = id)?.let { return it }
        val current = _tags.value
        if (current.none { it.id == id }) return TagOpResult.Failure(TagOpError.NOT_FOUND)
        persistTags(current.map { if (it.id == id) it.copy(name = trimmed) else it })
        return TagOpResult.Success
    }

    /** 改颜色。id 不变，历史消息徽章自动跟随。 */
    suspend fun recolorTag(id: String, colorHex: String): TagOpResult {
        val current = _tags.value
        if (current.none { it.id == id }) return TagOpResult.Failure(TagOpError.NOT_FOUND)
        val color = normalizeColor(colorHex)
        persistTags(current.map { if (it.id == id) it.copy(color = color) else it })
        return TagOpResult.Success
    }

    /**
     * 更新标签的两个提醒开关（互斥，由 UI 保证只切换一个，这里再做一次兜底）。
     */
    suspend fun updateTagAlertFlags(id: String, priorityAlert: Boolean, muted: Boolean): TagOpResult {
        val current = _tags.value
        if (current.none { it.id == id }) return TagOpResult.Failure(TagOpError.NOT_FOUND)
        persistTags(
            current.map { tag ->
                if (tag.id != id) tag
                else tag.copy(
                    priorityAlert = priorityAlert && !muted,
                    muted = muted && !priorityAlert,
                )
            }
        )
        return TagOpResult.Success
    }

    /** 拖拽排序：传入新顺序的 id 列表，整体重算 priority。 */
    suspend fun reorderTags(orderedIds: List<String>) {
        val current = _tags.value
        val byId = current.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }
        // 未出现在 orderedIds 里的标签追加到末尾，避免排序操作意外丢标签
        val missing = current.filter { it.id !in orderedIds }
        persistTags(renumber(reordered + missing))
    }

    /**
     * 删除标签。
     *
     * @param moveToDefault true 表示把绑定该标签的关注人迁移到兜底标签；
     *                      false 表示把这些关注人一并取消关注。
     */
    suspend fun deleteTag(id: String, moveToDefault: Boolean): TagOpResult {
        val current = _tags.value
        val target = current.firstOrNull { it.id == id }
            ?: return TagOpResult.Failure(TagOpError.NOT_FOUND)
        if (target.isDefault) return TagOpResult.Failure(TagOpError.IS_DEFAULT)
        if (current.size <= 1) return TagOpResult.Failure(TagOpError.LAST_TAG)

        val fallback = defaultTag(current)
        val nextWatched = if (moveToDefault) {
            _watched.value.map { if (it.tagId == id) it.copy(tagId = fallback.id) else it }
        } else {
            _watched.value.filterNot { it.tagId == id }
        }
        val nextTags = renumber(current.filterNot { it.id == id })

        runCatching {
            store.edit { prefs ->
                prefs[KEY_TAGS] = json.encodeToString(tagCodec, nextTags)
                prefs[KEY_WATCHED] = json.encodeToString(contactCodec, nextWatched)
            }
            _tags.value = nextTags
            _watched.value = nextWatched
        }.onFailure {
            Logx.swallow("TagRepository", "deleteTag", it)
            return TagOpResult.Failure(TagOpError.STORAGE_ERROR)
        }
        return TagOpResult.Success
    }

    // ── 关注人 ───────────────────────────────────────────────────

    suspend fun upsertContact(name: String, tagId: String, enabled: Boolean = true): TagOpResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return TagOpResult.Failure(TagOpError.NAME_EMPTY)
        if (_tags.value.none { it.id == tagId }) return TagOpResult.Failure(TagOpError.NOT_FOUND)
        val current = _watched.value
        val existingIndex = current.indexOfFirst { it.name.equals(trimmed, ignoreCase = true) }
        val next = if (existingIndex >= 0) {
            current.toMutableList().also { it[existingIndex] = WatchedContact(trimmed, tagId, enabled) }
        } else {
            current + WatchedContact(trimmed, tagId, enabled)
        }
        persistContacts(next)
        return TagOpResult.Success
    }

    suspend fun removeContact(name: String) {
        persistContacts(_watched.value.filterNot { it.name.equals(name, ignoreCase = true) })
    }

    suspend fun setContactEnabled(name: String, enabled: Boolean) {
        persistContacts(_watched.value.map { if (it.name.equals(name, ignoreCase = true)) it.copy(enabled = enabled) else it })
    }

    /** 该标签绑定了几个人（删除确认弹窗要用）。 */
    fun contactsBoundTo(tagId: String): List<WatchedContact> = _watched.value.filter { it.tagId == tagId }

    // ── 内部实现 ─────────────────────────────────────────────────

    private suspend fun persistTags(next: List<Tag>) {
        val normalized = normalize(next)
        runCatching {
            store.edit { prefs ->
                prefs[KEY_TAGS] = json.encodeToString(tagCodec, normalized)
                prefs[KEY_TAGS_INITIALIZED] = true
            }
            _tags.value = normalized
        }.onFailure { Logx.swallow("TagRepository", "persistTags", it) }
    }

    private suspend fun persistContacts(next: List<WatchedContact>) {
        runCatching {
            store.edit { prefs -> prefs[KEY_WATCHED] = json.encodeToString(contactCodec, next) }
            _watched.value = next
        }.onFailure { Logx.swallow("TagRepository", "persistContacts", it) }
    }

    private fun validateName(trimmed: String, ignoreId: String?): TagOpResult.Failure? {
        if (trimmed.isEmpty()) return TagOpResult.Failure(TagOpError.NAME_EMPTY)
        if (trimmed.length > Tag.NAME_MAX_LENGTH) return TagOpResult.Failure(TagOpError.NAME_TOO_LONG)
        val duplicated = _tags.value.any { it.id != ignoreId && it.name == trimmed }
        if (duplicated) return TagOpResult.Failure(TagOpError.NAME_DUPLICATE)
        return null
    }

    private fun normalizeColor(colorHex: String): String =
        if (colorHex in TagPalette_PRESETS) colorHex else TagPalette_PRESETS[0]

    /** 修正不变量：至少 1 个标签、有且仅有一个兜底、priority 连续。 */
    private fun normalize(input: List<Tag>): List<Tag> {
        if (input.isEmpty()) return DEFAULT_TAGS
        val deduped = input.distinctBy { it.id }
        val defaultIndex = deduped.indexOfFirst { it.isDefault }
        val withDefault = if (defaultIndex >= 0) {
            deduped.mapIndexed { index, tag -> if (index == defaultIndex) tag else tag.copy(isDefault = false) }
        } else {
            // 没有任何兜底标签：把第一个提升为兜底，保证不变量成立
            deduped.mapIndexed { index, tag -> tag.copy(isDefault = index == 0) }
        }
        return renumber(withDefault)
    }

    private fun renumber(input: List<Tag>): List<Tag> =
        input.mapIndexed { index, tag -> tag.copy(priority = index) }

    private fun decodeTagsOrNull(raw: String): List<Tag>? {
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString(tagCodec, raw) }.getOrNull()
    }

    private fun decodeContactsOrNull(raw: String): List<WatchedContact>? {
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString(contactCodec, raw) }.getOrNull()
    }

    /**
     * 旧版本迁移：当前仓库不存在 enum 版标签（工程是从本版开始写的），
     * 因此这一段是**防御性**的：能识别旧数据就映射，识别不到就直接写默认预设。
     * 必须幂等 —— 跑两次结果一致。
     */
    private fun migrateLegacyOrSeed(rawTags: String): List<Tag> {
        val legacy = decodeTagsOrNull(rawTags)
        if (legacy != null && legacy.isNotEmpty()) {
            Logx.i("TagRepository", "检测到既有标签数据，按现状接管（" + legacy.size + " 个）")
            return legacy
        }
        Logx.i("TagRepository", "首次启动：写入四个默认标签")
        return DEFAULT_TAGS
    }

    companion object {

        private val KEY_TAGS = stringPreferencesKey("tags_json")
        private val KEY_WATCHED = stringPreferencesKey("watched_contacts_json")
        // 必须与写入值的类型一致：写的是 Boolean（prefs[KEY] = true），
        // 因此这里是 booleanPreferencesKey。用成 stringPreferencesKey 会得到
        // 「Argument type mismatch: actual type is Boolean, but String was expected」。
        private val KEY_TAGS_INITIALIZED = booleanPreferencesKey("tags_initialized")

        /**
         * 标签色板。唯一来源是 ui/theme/TagPalette.kt，这里直接复用。
         * 不做第二份硬编码 —— 两处写死会在改色板时静默不一致。
         */
        val TagPalette_PRESETS: List<String> = com.shihua66666.nuntra.ui.theme.TagPalette.PRESETS

        /** 四个默认预设：顺序即 priority，工作为兜底。 */
        val DEFAULT_TAGS: List<Tag> = listOf(
            Tag(id = "seed-emergency", name = "紧急", color = "#C96B6B", priority = 0, isDefault = false),
            Tag(id = "seed-work", name = "工作", color = "#6B9BBF", priority = 1, isDefault = true),
            Tag(id = "seed-family", name = "家人", color = "#5E8C6A", priority = 2, isDefault = false),
            Tag(id = "seed-friend", name = "朋友", color = "#A38FA8", priority = 3, isDefault = false),
        )

        /** 标签 JSON 的规范化颜色（供外部校验输入）。 */
        fun isValidColor(colorHex: String): Boolean = colorHex in TagPalette_PRESETS
    }
}

/** 标签操作结果。 */
sealed interface TagOpResult {
    data object Success : TagOpResult
    data class Failure(val error: TagOpError) : TagOpResult
}

/** 标签操作错误，UI 需要据此显示中文提示。 */
enum class TagOpError {
    NAME_EMPTY,
    NAME_TOO_LONG,
    NAME_DUPLICATE,
    NOT_FOUND,
    IS_DEFAULT,
    LAST_TAG,
    STORAGE_ERROR,
}
