package com.shihua66666.nuntra.model

/**
 * 悬浮窗消息列表的排序方式。
 *
 * 由悬浮窗上的「排序」按钮在两种模式间切换，默认 [TIME]。
 *
 * 为什么不放在 MessageStore 里做：
 *   MessageStore.sorted 还要供主界面使用，那里依赖「标签优先级」分组排序；
 *   而悬浮窗需要的是「纯时间」或「关键词命中数」两种视角。
 *   把排序做成列表层的一次纯函数变换，两边互不影响。
 */
enum class MessageSortMode {
    /** 按时间排序：最新的消息排在最上面。 */
    TIME,

    /** 按关键词命中数排序：命中越多越靠前，命中数相同的按时间倒序。 */
    KEYWORD_HITS,
    ;

    /** 下一次点击排序按钮时切换到的模式（只有两种，所以是二元循环）。 */
    fun next(): MessageSortMode = if (this == TIME) KEYWORD_HITS else TIME

    /** 排序按钮上的文案。 */
    val label: String get() = if (this == TIME) "排序·时间" else "排序·命中"
}
