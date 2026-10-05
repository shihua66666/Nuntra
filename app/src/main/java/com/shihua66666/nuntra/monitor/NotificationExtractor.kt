package com.shihua66666.nuntra.monitor

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import com.shihua66666.nuntra.core.Logx

/**
 * 从通知里抽取出来的候选文本字段。
 *
 * 每个字段都可能为 null —— 这不是异常情况，而是常态：
 * 微信/QQ 的通知内容由它们自己拼装，字段空缺或被系统折叠都很常见。
 */
data class ExtractedNotification(
    /** EXTRA_TITLE：私聊为联系人名，群聊为群名。 */
    val title: String?,
    /** EXTRA_TEXT：可能被系统折叠成「你收到一条新消息」。 */
    val text: String?,
    /** EXTRA_SUB_TEXT：群聊常见为群名，或「N 条新消息」。 */
    val subText: String?,
    /** EXTRA_CONVERSATION_TITLE：Android 11+，微信不一定填。 */
    val conversationTitle: String?,
    /** EXTRA_MESSAGES 展开出的逐条内容（可能为空）。 */
    val messages: List<ExtractedMessage>,
    /** 通知的 group key，用于合并去重。 */
    val groupKey: String?,
) {
    /** 参与匹配的全部文本片段（顺序固定，便于复现）。 */
    val haystacks: List<String?>
        get() = buildList {
            add(title)
            add(conversationTitle)
            add(text)
            add(subText)
            messages.forEach { message ->
                add(message.sender)
                add(message.content)
            }
        }

    /** 合并后的正文（用于入库与展示）。 */
    val mergedBody: String
        get() {
            if (messages.isNotEmpty()) {
                return messages.joinToString(separator = "\n") { message ->
                    if (message.sender.isNullOrBlank()) message.content.orEmpty()
                    else message.sender + "：" + message.content.orEmpty()
                }.trim()
            }
            return text.orEmpty().trim()
        }

    /** 是否取不到任何可用正文（用于短信合并通知的丢弃判定）。 */
    val hasUsableBody: Boolean get() = mergedBody.isNotEmpty()
}

/** EXTRA_MESSAGES 中的一条。 */
data class ExtractedMessage(val sender: String?, val content: String?)

/**
 * 通知字段抽取器。
 *
 * 设计原则：**只抽取，不判断**。是否该保留由 NotificationFilter 决定。
 * 所有读取都做空值与类型兜底，任何异常都只返回部分结果而不是抛出。
 */
object NotificationExtractor {

    private const val TAG = "Extractor"

    /** EXTRA_MESSAGES 元素里可能的键名。不同系统/版本约定不一致，逐个尝试。 */
    private val MESSAGE_TEXT_KEYS = arrayOf("text", "message", "content", "android.text")
    private val MESSAGE_SENDER_KEYS = arrayOf("sender", "sender_person", "from", "name")

    fun extract(sbn: StatusBarNotification): ExtractedNotification {
        val extras = sbn.notification?.extras
        if (extras == null) {
            return ExtractedNotification(null, null, null, null, emptyList(), null)
        }
        if (Logx.verbose) dumpExtrasOnce(sbn.packageName, extras)
        return ExtractedNotification(
            title = stringOrNull(extras, Notification.EXTRA_TITLE),
            text = stringOrNull(extras, Notification.EXTRA_TEXT),
            subText = stringOrNull(extras, Notification.EXTRA_SUB_TEXT),
            conversationTitle = stringOrNull(extras, Notification.EXTRA_CONVERSATION_TITLE),
            messages = readMessages(extras),
            groupKey = sbn.notification?.group,
        )
    }

    /** 读取 CharSequence 型字段并转成 String。 */
    private fun stringOrNull(extras: Bundle, key: String): String? = runCatching {
        when (val value = extras.getCharSequence(key)) {
            null -> null
            is String -> value.trim().ifEmpty { null }
            else -> value.toString().trim().ifEmpty { null }
        }
    }.getOrNull()

    /**
     * 展开 EXTRA_MESSAGES。
     *
     * 版本差异（必须分支，否则可能 ClassCastException）：
     *  · API 24+ 元素为 Parcelable（实际类型 Bundle）
     *  · API 33+ 提供带类型的 getParcelableArray 重载，避免 deprecated 警告
     *
     * 这里按 minSdk 29 落地：33 及以上走带类型重载，其余走旧重载。
     */
    private fun readMessages(extras: Bundle): List<ExtractedMessage> = runCatching {
        val raw: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        if (raw == null) return emptyList()
        raw.mapNotNull { item ->
            val bundle = item as? Bundle ?: return@mapNotNull null
            val content = firstNonBlank(bundle, MESSAGE_TEXT_KEYS)
            val sender = firstNonBlank(bundle, MESSAGE_SENDER_KEYS)
            if (content == null && sender == null) null else ExtractedMessage(sender, content)
        }
    }.getOrElse { tr ->
        Logx.swallow(TAG, "readMessages", tr)
        emptyList()
    }

    private fun firstNonBlank(bundle: Bundle, keys: Array<String>): String? {
        for (key in keys) {
            val value = runCatching { bundle.getCharSequence(key) }.getOrNull() ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /**
     * 调试辅助：把 extras 的全部键打印一次。
     *
     * 每个包名只打一次，用于在真机上核对微信/QQ/短信的真实字段名
     * （尤其是 EXTRA_MESSAGES 的键是 text 还是别的）。
     */
    private val dumpedPackages = HashSet<String>()

    private fun dumpExtrasOnce(packageName: String, extras: Bundle) {
        synchronized(dumpedPackages) {
            if (!dumpedPackages.add(packageName)) return
        }
        val keys = extras.keySet().joinToString(", ")
        Logx.i(TAG, "extras 键名（" + packageName + "）：" + keys)
        for (key in extras.keySet()) {
            val value = runCatching { extras.get(key)?.toString() }.getOrNull()
            if (value != null) Logx.i(TAG, "  " + key + " = " + value.take(180))
        }
    }
}
