package com.shihua66666.nuntra.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 可编辑标签。
 *
 * 核心设计：Tag 是**用户数据**，不是固定 enum。
 * 「紧急/工作/家人/朋友」只是首次启动写入的默认预设，
 * 用户可改名、改色、增删、拖拽排序。
 *
 * 关于 [isDefault]：
 *  它是**兜底标签**的身份标识，全局有且仅有一个。
 *  判断「谁是兜底」只能看 isDefault，**绝不能按名字判断** ——
 *  用户可以把兜底标签改名成「其他」，按名字判断会立刻失效。
 */
@Serializable
data class Tag(
    /** UUID 字符串，创建时生成，此后永不改变（重命名/改色都不动 id）。 */
    val id: String = UUID.randomUUID().toString(),
    /** 显示名：1..6 个字符，非空，与其他标签不重复。 */
    val name: String,
    /** 颜色，形如 #C96B6B，只能取自 TagPalette.PRESETS。 */
    val color: String,
    /** 优先级：由列表顺序决定，越小越优先；保持 0..n-1 连续。 */
    val priority: Int,
    /** 是否兜底标签（全局唯一，不可删除）。 */
    val isDefault: Boolean = false,
    /** 特殊关注：入库时播放提醒音。与 [muted] 互斥。 */
    val priorityAlert: Boolean = false,
    /** 静音：不播放任何提醒音。与 [priorityAlert] 互斥。 */
    val muted: Boolean = false,
) {
    companion object {
        const val NAME_MAX_LENGTH = 6
    }
}

/** 关注人：绑定一个标签。 */
@Serializable
data class WatchedContact(
    /** 匹配用的名字；与通知的 title / conversationTitle / body 做包含匹配。 */
    val name: String,
    /** 绑定的标签 id。 */
    val tagId: String,
    /** 可单独停用而不删除。 */
    val enabled: Boolean = true,
)

/**
 * 标签的展示态：把「定义」与「运行期派生信息」分开，
 * 避免给可序列化的 Tag 塞运行时字段。
 */
data class TagView(
    val tag: Tag,
    val unreadCount: Int = 0,
)
