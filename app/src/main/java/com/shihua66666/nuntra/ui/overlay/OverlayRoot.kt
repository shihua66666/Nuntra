package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shihua66666.nuntra.model.TagView
import com.shihua66666.nuntra.overlay.OverlayWindowState
import com.shihua66666.nuntra.ui.theme.AppColors
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.OverlayDimens

/**
 * 悬浮窗内容根容器：负责「外壳」与三态分发。
 *
 * 外壳统一处理三种形态共用的视觉：圆角、半透明面板底、1dp 细边框。
 * 之所以用半透明纯色而不是真实毛玻璃：窗口级模糊需要 API 31+ 的 RenderEffect，
 * 在 minSdk 29 的设备上不可用；用半透明底色能拿到接近的柔和观感，且全版本一致。
 *
 * 三态内容：
 *   CAPSULE → [CollapsedCapsule]（状态灯 + 未读数）
 *   PANEL / MINI → [ExpandedPanel]（标题栏 + 筛选栏 + 内容 + 底栏），两者共用组件，仅尺寸不同
 */
@Composable
fun OverlayRoot(
    colors: AppColors,
    windowState: OverlayWindowState,
    unreadCount: Int,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = LocalAppColors.current
    val shape = when (windowState) {
        OverlayWindowState.CAPSULE -> OverlayDimens.capsuleShape()
        OverlayWindowState.PANEL -> OverlayDimens.panelShape()
        OverlayWindowState.MINI -> OverlayDimens.miniShape()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(c.panel.copy(alpha = PANEL_ALPHA))
            .border(1.dp, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 三态分发器。
 *
 * 把「选哪个形态」集中在一处，OverlayService 只负责提供数据与回调。
 * 这样第 4 步接入真实消息列表时，只需要替换 [messageList] 这一个插槽。
 */
@Composable
fun OverlayContent(
    colors: AppColors,
    windowState: OverlayWindowState,
    unreadCount: Int,
    unreadTagColor: Color?,
    dragging: Boolean,
    tags: List<TagView>,
    selectedTagIds: Set<String>,
    messageCount: Int,
    filterVisible: Boolean,
    animateLamp: Boolean,
    onToggleTag: (String) -> Unit,
    onSelectAll: () -> Unit,
    onToggleFilter: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearMessages: () -> Unit,
    onCollapse: () -> Unit,
    /**
     * 消息列表插槽。
     *
     * 点击行为（标记已读 / 第 5 步的跳转）由插槽内部实现，
     * 因此这里不再单独暴露 onMessageClick，避免出现两个语义重复的回调。
     */
    messageList: @Composable () -> Unit,
) {
    OverlayRoot(
        colors = colors,
        windowState = windowState,
        unreadCount = unreadCount,
        dragging = dragging,
    ) {
        when (windowState) {
            OverlayWindowState.CAPSULE -> CollapsedCapsule(
                unreadCount = unreadCount,
                unreadTagColor = unreadTagColor,
                animateLamp = animateLamp,
            )

            OverlayWindowState.PANEL, OverlayWindowState.MINI -> ExpandedPanel(
                colors = colors,
                title = if (windowState == OverlayWindowState.MINI) MINI_TITLE else PANEL_TITLE,
                tags = tags,
                selectedTagIds = selectedTagIds,
                onToggleTag = onToggleTag,
                onSelectAll = onSelectAll,
                filterVisible = filterVisible,
                messageCount = messageCount,
                unreadCount = unreadCount,
                compact = windowState == OverlayWindowState.MINI,
                onToggleFilter = onToggleFilter,
                onOpenSettings = onOpenSettings,
                onClearMessages = onClearMessages,
                onCollapse = onCollapse,
                content = messageList,
            )
        }
    }
}

/** 面板半透明度：0.96 —— 隐约透出底层内容，但不影响文字可读性。 */
private const val PANEL_ALPHA = 0.96f

private const val PANEL_TITLE = "通知中继终端"
private const val MINI_TITLE = "终端 · 小窗"
