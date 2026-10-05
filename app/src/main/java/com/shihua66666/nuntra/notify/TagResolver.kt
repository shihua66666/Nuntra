package com.shihua66666.nuntra.notify

import com.shihua66666.nuntra.core.Keywords
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.data.SettingsRepository
import com.shihua66666.nuntra.data.TagRepository

/**
 * 标签解析结果。
 *
 * @param tagId 最终归属的标签 id
 * @param matchedContacts 命中的关注人名字（可能多个）
 * @param matchedKeywords 命中的关键词
 * @param viaDefaultTag true 表示没有任何关注人命中，标签来自兜底（例如仅命中关键词）
 */
data class TagResolution(
    val tagId: String,
    val matchedContacts: List<String>,
    val matchedKeywords: List<String>,
    val viaDefaultTag: Boolean,
)

/**
 * 关注人 / 关键词 → 标签的解析器。
 *
 * 规则（顺序即优先级）：
 *  1. 收集所有命中的关注人（按 tagId 去重）
 *     · 命中多个关注人则取其中 priority 最小的标签（列表里最靠上的）
 *  2. 没有任何关注人命中、但命中了关键词 → 标签 = isDefault 兜底标签
 *  3. 两者都没命中 → 该消息不会入库（过滤阶段已丢弃），因此不会出现「无标签」分支
 *
 * 关于「短信默认归入工作标签」：
 *  需求里写的「工作」正是默认预设中 isDefault = true 的那个标签，
 *  因此实现上统一写「归入 isDefault 兜底标签」，而不是硬编码去找名字叫「工作」的标签 ——
 *  用户可以把兜底标签改名成「其他」，按名字找会立刻失效。
 *  同理，企业微信/TIM 的消息只命中关键词（未命中关注人）时也走这条规则。
 *
 * 边界：关注人的 tagId 指向已不存在的标签时，降级为兜底标签并打告警日志，
 * 绝不返回空串、绝不抛异常。
 */
class TagResolver(
    private val tagRepository: TagRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 解析一条通知的归属标签。
     *
     * @param haystacks 用于匹配的文本片段（title / conversationTitle / body / subText）
     * @param keywords 关键词集合；为 null 时表示调用方没有提供，按「无关键词」处理
     */
    suspend fun resolve(
        haystacks: List<String?>,
        keywords: Collection<String>? = null,
    ): TagResolution {
        val tags = tagRepository.tags.value
        val fallback = tagRepository.defaultTag(tags)
        val matchedContacts = tagRepository.watchedContacts.value
            .filter { it.enabled && Keywords.contactHit(haystacks, it.name) }

        if (matchedContacts.isNotEmpty()) {
            val matchedTagIds = matchedContacts.map { it.tagId }.distinct()
            val chosenTagId = matchedTagIds.minByOrNull { tagRepository.priorityOf(it) }
            val resolvedTag = chosenTagId?.let { tagRepository.tagById(it) }
            if (resolvedTag == null) {
                Logx.w("TagResolver", "关注人绑定的标签已不存在，降级为兜底标签：" + chosenTagId)
                return TagResolution(
                    tagId = fallback.id,
                    matchedContacts = matchedContacts.map { it.name },
                    matchedKeywords = emptyList(),
                    viaDefaultTag = true,
                )
            }
            return TagResolution(
                tagId = resolvedTag.id,
                matchedContacts = matchedContacts.map { it.name },
                matchedKeywords = emptyList(),
                viaDefaultTag = false,
            )
        }

        // 走到这里说明没有任何关注人命中：短信、以及「只命中关键词」的企业微信 / TIM 消息
        // 都归入兜底标签（默认预设里就是「工作」）。
        val hitKeywords = if (keywords == null) emptyList() else Keywords.hits(haystacks, keywords)
        return TagResolution(
            tagId = fallback.id,
            matchedContacts = emptyList(),
            matchedKeywords = hitKeywords,
            viaDefaultTag = true,
        )
    }

    /** 该标签是否配置了特殊关注（需要播放提醒音并参与重复提醒）。 */
    fun isPriorityTag(tagId: String): Boolean = tagRepository.tagById(tagId)?.priorityAlert == true

    /** 该标签是否静音。 */
    fun isMutedTag(tagId: String): Boolean = tagRepository.tagById(tagId)?.muted == true
}
