package com.shihua66666.nuntra.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.model.Tag
import com.shihua66666.nuntra.model.TerminalMessage
import com.shihua66666.nuntra.ui.theme.LocalAppColors

/**
 * 消息列表。
 *
 * 职责：筛选 + 渲染 + 已读联动。排序**不在这里做** ——
 * MessageStore.sorted 已经在入库时就按「标签优先级升序、同标签时间倒序」排好了（见 publishLocked），
 * 列表再排一次只会重复计算，还容易与存储层排序规则不一致。
 *
 * 三个关键实现点：
 *  1. **筛选**：selectedTagIds 为空集表示「全部」；否则只看选中标签。
 *  2. **stable key**：用 message.id（Long，单调递增且唯一），保证新增消息时已有项不重排、
 *     且能正确复用组合状态（滚动位置、动画状态）。
 *  3. **新消息淡入**：AnimatedVisibility + fadeIn(220ms)，只淡入不位移 ——
 *     需求明确「新消息淡入，不闪烁」，位移动画会让面板内容跳动。
 *
 * 已读联动交给上层（OverlayService）：列表只负责「点击了某条」时回调。
 */
@Composable
fun MessageList(
    tags: List<Tag>,
    selectedTagIds: Set<String>,
    messages: List<TerminalMessage>,
    onMessageClick: (TerminalMessage) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否隐藏已读消息（对应设置页「点击后自动清理已读」，默认开）。
     *
     * ★ 为什么必须有这一层过滤：
     *   点击消息会跳转并把该条标记已读，但已读项**仍然留在列表里**，
     *   它会把新到的未读消息压在下面 —— 用户看到的现象就是
     *   「点了跳转，消息还在，新消息也看不到」。
     *   打开这个开关后，已读项在下一次重组时立即消失。
     */
    hideRead: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
) {
    val c = LocalAppColors.current
    val tagById = remember(tags) { tags.associateBy { it.id } }
    val filtered = remember(messages, selectedTagIds, hideRead) {
        val byTag = if (selectedTagIds.isEmpty()) {
            messages
        } else {
            messages.filter { it.tagId in selectedTagIds }
        }
        // 已读过滤放在标签过滤之后：两者是「先选范围、再按已读收敛」的关系。
        if (hideRead) byTag.filter { !it.read } else byTag
    }

    if (filtered.isEmpty()) {
        EmptyState(
            filteredByTag = selectedTagIds.isNotEmpty(),
            // 有消息但被已读过滤清空时，空态文案要说清原因，否则用户以为消息丢了
            hiddenByRead = hideRead && messages.any { it.read },
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(
            items = filtered,
            key = { message -> message.id },
        ) { message ->
            MessageCardRow(
                message = message,
                tag = tagById[message.tagId],
                onClick = { onMessageClick(message) },
            )
        }
    }
}

/**
 * 单条卡片的进入动画包装。
 *
 * 用 LaunchedEffect 在首次组合后把 visible 置 true，从而触发一次 fadeIn：
 *  · 新入库的消息（列表新增项）第一次组合就会淡入；
 *  · 已有项因 key 稳定不会重新组合，不会反复淡入（避免滚动时闪动）。
 */
@Composable
private fun MessageCardRow(
    message: TerminalMessage,
    tag: Tag?,
    onClick: () -> Unit,
) {
    // 注意：这里必须用 by 委托，因此文件需要 getValue/setValue 两个 import
    // （属性委托的 operator 在源码里不出现，删 import 工具极易误删，见 README §7.6）
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message.id) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = FADE_IN_MS)),
    ) {
        MessageCard(message = message, tag = tag, onClick = onClick)
    }
}

/**
 * 空态：区分三种「没有条目」的原因，避免用户以为漏消息。
 *
 *  1. 真的没有消息；
 *  2. 标签筛选后为空；
 *  3. **消息都在，但已读项被「点击后自动清理已读」隐藏了** ——
 *     这一种最容易被误判成「消息丢了」，所以文案必须点明并给出关闭入口。
 */
@Composable
private fun EmptyState(
    filteredByTag: Boolean,
    hiddenByRead: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when {
                    hiddenByRead -> "已读消息已自动隐藏"
                    filteredByTag -> "该标签下暂无消息"
                    else -> "暂无消息"
                },
                color = c.textSecondary,
                fontSize = 13.sp,
            )
            Text(
                text = when {
                    hiddenByRead -> "可在「设置 → 点击行为」关闭「点击后自动清理已读」"
                    filteredByTag -> "试试底栏的「筛选」切换回全部"
                    else -> "开启总开关后，符合监控范围的通知会出现在这里"
                },
                color = c.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 淡入时长：220ms —— 稍慢于默认的 150ms，观感更「柔和」，符合护眼风格。 */
private const val FADE_IN_MS = 220
