package com.shihua66666.nuntra.monitor

import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import com.shihua66666.nuntra.core.Logx
import java.util.ArrayDeque

/**
 * 保存通知自带的 contentIntent，供点击消息时跳转。
 *
 * 为什么只放内存：
 *  · PendingIntent 跨进程反序列化不可靠，且受 FLAG_IMMUTABLE 语义影响；
 *  · 通知内容本身不持久化是既定设计（重启即清空），intent 跟着消息一起消失是一致的。
 *
 * 命名规则：优先用 dedupKey（与消息一一对应）；短信等没有稳定 dedupKey 的场景
 * 用「包名 + 通知 id + postTime」作为后备键。
 */
class PendingIntentResolver {

    private val lock = Any()

    /** 主索引：dedupKey → intent。 */
    private val byDedupKey = HashMap<String, PendingIntent>()

    /** 后备索引：包名|id|postTime → intent（用于 dedupKey 不稳定时）。 */
    private val byFallbackKey = HashMap<String, PendingIntent>()

    /** 插入顺序，用于容量淘汰（保持内存有界）。 */
    private val insertionOrder = ArrayDeque<String>()

    fun remember(sbn: StatusBarNotification, dedupKey: String) {
        val intent = sbn.notification?.contentIntent ?: return
        synchronized(lock) {
            if (dedupKey.isNotEmpty()) {
                byDedupKey[dedupKey] = intent
                track(dedupKey)
            }
            val fallback = fallbackKey(sbn)
            if (fallback != null) {
                byFallbackKey[fallback] = intent
                track(fallback)
            }
            evictIfNeeded()
        }
    }

    /** 按 dedupKey 取；取不到再尝试后备键。 */
    fun find(dedupKey: String, packageName: String?, notificationId: Int, postTime: Long): PendingIntent? =
        synchronized(lock) {
            byDedupKey[dedupKey]
                ?: byFallbackKey[makeFallbackKey(packageName, notificationId, postTime)]
        }

    /** 通知被移除时清理，避免持有已失效的 intent。 */
    fun forget(sbn: StatusBarNotification) {
        synchronized(lock) {
            val fallback = fallbackKey(sbn)
            if (fallback != null) {
                byFallbackKey.remove(fallback)
                insertionOrder.remove(fallback)
            }
            // 主索引无法从 sbn 反推 dedupKey（它依赖抽取结果），
            // 由调用方在能算出 dedupKey 时调用 forgetByDedupKey。
        }
    }

    fun forgetByDedupKey(dedupKey: String) {
        synchronized(lock) {
            byDedupKey.remove(dedupKey)
            insertionOrder.remove(dedupKey)
        }
    }

    fun clear() {
        synchronized(lock) {
            byDedupKey.clear()
            byFallbackKey.clear()
            insertionOrder.clear()
        }
    }

    fun size(): Int = synchronized(lock) { byDedupKey.size + byFallbackKey.size }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun track(key: String) {
        if (insertionOrder.contains(key)) return
        insertionOrder.addLast(key)
    }

    private fun evictIfNeeded() {
        while (insertionOrder.size > CAPACITY) {
            val oldest = insertionOrder.pollFirst() ?: break
            byDedupKey.remove(oldest)
            byFallbackKey.remove(oldest)
        }
        if (byDedupKey.size > CAPACITY) {
            Logx.w("PendingIntentResolver", "主索引超限，整体清理一次（正常不应发生）")
            byDedupKey.clear()
        }
    }

    private fun fallbackKey(sbn: StatusBarNotification): String? {
        if (sbn.id == 0 || sbn.postTime <= 0L) return null
        return makeFallbackKey(sbn.packageName, sbn.id, sbn.postTime)
    }

    private fun makeFallbackKey(packageName: String?, notificationId: Int, postTime: Long): String =
        (packageName ?: "") + "|" + notificationId + "|" + postTime

    private companion object {
        /** 上限：内存里有界，避免长跑累积。 */
        const val CAPACITY = 300
    }
}
