package com.shihua66666.nuntra.ui.overlay

import com.shihua66666.nuntra.model.MessageSortMode
import com.shihua66666.nuntra.model.TerminalMessage

/**
 * 悬浮窗消息列表的「筛选 + 排序」纯函数。
 *
 * 为什么抽成独立函数：
 *   列表要按它渲染，悬浮窗服务要按它算「命中 N 个关键词」的提示。
 *   两处各写一遍迟早会不一致（顺序和提示对不上），所以只留一个实现。
 *
 * 两个排序模式（需求原文）：
 *   TIME         —— 按时间倒序，最新的在最上面。
 *   KEYWORD_HITS —— 关键词命中数多的在前；命中数相同的按时间倒序。
 *
 * 为什么不在 MessageStore 里排：
 *   store 的 sorted 还要供主界面使用，那里依赖「标签优先级」分组排序。
 *   悬浮窗需要的是另一种视角，放在列表层做一次变换，两边互不影响。
 */
fun orderOverlayMessages(
    messages: List<TerminalMessage>,
    selectedTagIds: Set<String>,
    hideRead: Boolean,
    sortMode: MessageSortMode,
): List<TerminalMessage> {
    // 先选范围（标签），再按已读收敛 —— 顺序不能颠倒，否则语义会变。
    val byTag = if (selectedTagIds.isEmpty()) {
        messages
    } else {
        messages.filter { it.tagId in selectedTagIds }
    }
    val visible = if (hideRead) byTag.filter { !it.read } else byTag
    return when (sortMode) {
        MessageSortMode.TIME -> visible.sortedWith(
            compareByDescending<TerminalMessage> { it.sortTime }.thenByDescending { it.id },
        )

        MessageSortMode.KEYWORD_HITS -> visible.sortedWith(
            compareByDescending<TerminalMessage> { it.matchedKeywords.size }
                .thenByDescending { it.sortTime }
                .thenByDescending { it.id },
        )
    }
}

/**
 * 当前可见消息里最大的一次关键词命中数。
 *
 * 取 max 而不是「第一条的命中数」：这样在两种排序模式下提示都稳定可读，
 * 切换排序时提示不会跳来跳去。
 */
fun maxKeywordHits(messages: List<TerminalMessage>): Int =
    messages.maxOfOrNull { it.matchedKeywords.size } ?: 0
