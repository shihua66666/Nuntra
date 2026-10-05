package com.shihua66666.nuntra.model

/**
 * 一条入库的消息。
 *
 * 关键约定：**只存 tagId，不存标签名与色值**。
 * 渲染时通过 TagRepository 查表得到名称与颜色，
 * 因此重命名/改色对所有历史消息自动生效。
 */
data class TerminalMessage(
    /** 自增主键，列表 key。 */
    val id: Long,
    /** 去重键：sha256(包名|标题|内容|时间)。 */
    val dedupKey: String,
    val packageName: String,
    val source: SourceApp,
    /** 通知 EXTRA_TITLE（私聊为联系人名，群聊为群名）。 */
    val contact: String?,
    /** 通知 EXTRA_CONVERSATION_TITLE（Android 11+，微信不一定填）。 */
    val conversationTitle: String?,
    /** 正文，多条消息会被拼接。 */
    val body: String,
    /** 通知 EXTRA_SUB_TEXT。 */
    val subText: String?,
    /** 通知的 group key（相同则属同一合并组，用于去重与调试）。 */
    val groupKey: String?,
    /** 命中的关键词，用于高亮。 */
    val matchedKeywords: List<String> = emptyList(),
    /** 命中的关注人名字，空表示仅靠关键词命中。 */
    val matchedContacts: List<String> = emptyList(),
    /** 由 TagResolver 解析出的标签 id。 */
    val tagId: String,
    /** 通知 postTime。 */
    val postedAt: Long,
    /** 入库时间（用于 24 小时保留判定与排序兜底）。 */
    val storedAt: Long = postedAt,
    /** 是否已读（打开面板或点击后置位）。 */
    val read: Boolean = false,
    /** 是否摘要消息（合并通知取不到逐条内容时置 true）。 */
    val isSummary: Boolean = false,
    /** 特殊关注消息：需要播放提醒音并参与重复提醒。 */
    val isPriority: Boolean = false,
    /** 重复提醒已触发次数。 */
    val repeatCount: Int = 0,
) {
    /** 排序时间基准：优先通知时间，缺失时用入库时间。 */
    val sortTime: Long get() = if (postedAt > 0L) postedAt else storedAt
}
