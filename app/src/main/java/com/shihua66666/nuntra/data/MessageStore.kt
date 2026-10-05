package com.shihua66666.nuntra.data

import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.model.TagView
import com.shihua66666.nuntra.model.TerminalMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashSet

/**
 * 消息仓库：全应用唯一的消息真相源。
 *
 * 职责边界：
 *  · 只做内存管理：去重、上限淘汰、24 小时保留、未读数、标签排序。
 *  · **不碰磁盘**。消息不持久化是刻意的设计选择：通知内容属于敏感数据，
 *    重启即清空，避免长期留存带来的隐私与容量问题。
 *  · 配置数据（标签/关注人/设置）走 DataStore，与这里完全分离。
 *
 * 线程安全：所有写操作都在 [lock] 同步块内完成，读操作读不可变 List 快照。
 */
class MessageStore(
    /**
     * 标签优先级查询。
     *
     * 作为构造参数注入而不是每个方法临时传入：
     *  publishLocked() 在 add / sweep / markRead / clear 等多处被调用，
     *  只要有一处忘了传，排序就会静默退化成「纯按时间」—— 这正是之前的缺陷。
     *  注入后只有一个来源，不可能漏。
     */
    private val tagPriorityOf: (String) -> Int = { Int.MAX_VALUE },
) {

    private val lock = Any()

    private val unread = LinkedHashSet<Long>()

    /** 去重键表，容量上限，超出时淘汰最旧的键。 */
    private val dedupKeys = LinkedHashSet<String>()

    private var seq: Long = 0L

    private var limit: Int = DEFAULT_LIMIT

    private var retentionHours: Int = DEFAULT_RETENTION_HOURS

    private var _messages: List<TerminalMessage> = emptyList()

    private val _sorted = MutableStateFlow<List<TerminalMessage>>(emptyList())

    private val _unreadCount = MutableStateFlow(0)

    /** 当前全部消息（按「标签优先级升序，同标签时间倒序」排好）。 */
    val sorted: StateFlow<List<TerminalMessage>> = _sorted.asStateFlow()

    /** 未读数。 */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /** 设置上限。调小后立即裁剪，避免「设置改了但旧消息还占内存」。 */
    fun setLimit(newLimit: Int) {
        val safe = newLimit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        synchronized(lock) {
            limit = safe
            trimByCountLocked()
            publishLocked()
        }
    }

    /** 设置保留小时数。调小后立即裁剪。 */
    fun setRetentionHours(hours: Int) {
        val safe = hours.coerceIn(MIN_RETENTION_HOURS, MAX_RETENTION_HOURS)
        synchronized(lock) {
            retentionHours = safe
            trimByAgeLocked(System.currentTimeMillis())
            publishLocked()
        }
    }

    fun currentLimit(): Int = synchronized(lock) { limit }

    fun currentRetentionHours(): Int = synchronized(lock) { retentionHours }

    /**
     * 入库一条消息。
     *
     * @return true 表示真的入库；false 表示被去重或内容为空。
     */
    fun add(message: TerminalMessage): Boolean {
        val body = message.body.trim()
        if (message.contact.isNullOrBlank() && body.isEmpty()) {
            // 既没有联系人也没有正文：存下来也没有展示价值，直接丢弃。
            return false
        }
        synchronized(lock) {
            if (message.dedupKey.isNotEmpty() && !dedupKeys.add(message.dedupKey)) {
                Logx.d("MessageStore", "去重命中，忽略：" + message.dedupKey.take(12))
                return false
            }
            while (dedupKeys.size > DEDUP_CAPACITY) {
                val first = dedupKeys.firstOrNull() ?: break
                dedupKeys.remove(first)
            }
            // 先按时间清，再按数量清（顺序要求来自需求文档）
            trimByAgeLocked(System.currentTimeMillis())
            _messages = _messages + message
            trimByCountLocked()
            if (!message.read) unread.add(message.id)
            publishLocked()
            return true
        }
    }

    /** 手动触发一次清理：前台服务启动、低频定时器回调时调用。 */
    fun sweep(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            trimByAgeLocked(now)
            publishLocked()
        }
    }

    /** 全部标记已读：打开面板时调用。 */
    fun markAllRead() {
        synchronized(lock) {
            if (unread.isEmpty()) return
            unread.clear()
            _messages = _messages.map { if (it.read) it else it.copy(read = true) }
            publishLocked()
        }
    }

    /** 单条标记已读：点击某条消息时调用。 */
    fun markRead(id: Long) {
        synchronized(lock) {
            if (!unread.remove(id)) return
            _messages = _messages.map { if (it.id == id) it.copy(read = true) else it }
            publishLocked()
        }
    }

    /** 记录一次重复提醒。 */
    fun bumpRepeat(id: Long) {
        synchronized(lock) {
            _messages = _messages.map { if (it.id == id) it.copy(repeatCount = it.repeatCount + 1) else it }
            publishLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            _messages = emptyList()
            unread.clear()
            dedupKeys.clear()
            _sorted.value = emptyList()
            _unreadCount.value = 0
        }
    }

    /** 供 UI 计算标签徽章/筛选栏使用：每个标签的未读数。 */
    fun unreadCountByTag(): Map<String, Int> {
        val snapshot = _messages
        val set = unread
        return snapshot.asSequence()
            .filter { it.id in set }
            .groupingBy { it.tagId }
            .eachCount()
    }

    /** 未读消息里优先级最高的标签 id；无未读时返回 null。 */
    fun highestPriorityUnreadTagId(tagPriorityOf: (String) -> Int): String? {
        val snapshot = _messages
        val set = unread
        return snapshot.asSequence()
            .filter { it.id in set }
            .minByOrNull { tagPriorityOf(it.tagId) }
            ?.tagId
    }

    /** 构建标签筛选栏用的视图数据。 */
    fun tagViews(tags: List<com.shihua66666.nuntra.model.Tag>): List<TagView> {
        val counts = unreadCountByTag()
        return tags.map { TagView(tag = it, unreadCount = counts[it.id] ?: 0) }
    }

    private fun trimByAgeLocked(now: Long) {
        if (_messages.isEmpty()) return
        val cutoff = now - retentionHours * 3_600_000L
        val kept = _messages.filter { it.sortTime >= cutoff }
        if (kept.size != _messages.size) {
            val removed = _messages.size - kept.size
            _messages = kept
            rebindUnreadLocked()
            Logx.d("MessageStore", "按 24 小时保留清除 " + removed + " 条（保留 " + retentionHours + " 小时）")
        }
    }

    private fun trimByCountLocked() {
        if (_messages.size <= limit) return
        val overflow = _messages.size - limit
        _messages = _messages.subList(overflow, _messages.size).toList()
        rebindUnreadLocked()
        Logx.d("MessageStore", "按上限清除 " + overflow + " 条（上限 " + limit + "）")
    }

    /** 裁剪后未读集合可能指向已删除的消息，这里重建一次。 */
    private fun rebindUnreadLocked() {
        if (unread.isEmpty()) return
        val alive = _messages.asSequence().map { it.id }.toHashSet()
        unread.retainAll(alive)
    }

    private fun publishLocked() {
        val snapshot = _messages
        _sorted.value = snapshot.sortedWith(
            compareBy<TerminalMessage> { tagPriorityOf(it.tagId) }
                .thenByDescending { it.sortTime }
                .thenByDescending { it.id }
        )
        _unreadCount.value = unread.size
    }

    /** 生成下一个主键。time 前缀保证同一毫秒内多次调用也单调递增。 */
    fun nextId(): Long = synchronized(lock) {
        val candidate = System.currentTimeMillis() * 1000L + (seq % 1000L)
        seq = if (candidate > seq) candidate else seq + 1
        seq
    }

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MIN_LIMIT = 20
        const val MAX_LIMIT = 2000
        const val DEFAULT_RETENTION_HOURS = 24
        const val MIN_RETENTION_HOURS = 1
        const val MAX_RETENTION_HOURS = 168
        private const val DEDUP_CAPACITY = 500
    }
}
