package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    /** 单击（仅胶囊态响应）。 */
    onSingleTap: () -> Unit = {},
    /** 双击：与单击互斥，因此用 detectTapGestures 统一处理。 */
    onDoubleTap: () -> Unit = {},
    /** 长按：呼出快捷菜单。 */
    onLongPress: () -> Unit = {},
    /** 上报标签栏矩形（窗口本地坐标），供触摸拦截器建立禁止拖动区。 */
    onFilterBarBounds: (android.graphics.Rect?) -> Unit = {},

    /**
     * 双指捏合缩放回调。
     *
     * 参数是**相对倍率**（本次事件相对上一次的变化量），调用方直接乘当前尺寸即可。
     */
    onResizeBy: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val c = LocalAppColors.current
    val shape = when (windowState) {
        OverlayWindowState.CAPSULE -> OverlayDimens.capsuleShape()
        OverlayWindowState.PANEL -> OverlayDimens.panelShape()
        OverlayWindowState.MINI -> OverlayDimens.miniShape()
    }
    // ★★ Compose 侧手势绑定 ★★
    //
    //   为什么用 detectTapGestures 而不是 clickable：
    //     clickable 与双击判定天然冲突 —— clickable 会在第一次抬起后立即触发单击，
    //     用户想双击时会出现「先展开、又切小窗」的抖动。
    //     detectTapGestures 内部会等待双击超时，单击/双击/长按在同一套状态机里互斥。
    //
    //   手势挂在根容器上，与窗口层拖动分工：
    //     拖动由 OverlayTouchInterceptor 在越阈值时抢走手势；
    //     阈值内的点击留在这里处理，两者互不干扰。
    //
    //   面板态只保留双击与长按：单击留给列表项与筛选按钮，
    //   否则点一下列表空白就把面板收起来，交互会很难用。
    val tapGestures = Modifier.pointerInput(windowState) {
        if (windowState == OverlayWindowState.CAPSULE) {
            detectTapGestures(
                onTap = { onSingleTap() },
                onDoubleTap = { onDoubleTap() },
                onLongPress = { onLongPress() },
            )
        } else {
            detectTapGestures(
                onDoubleTap = { onDoubleTap() },
                onLongPress = { onLongPress() },
            )
        }
    }

    // ★ 双指捏合缩放：仅展开态生效（胶囊态只有一个圆点，缩放没有意义）。
    //   与拖动的关系：OverlayTouchInterceptor 在 pointerCount > 1 时刻意不抢手势，
    //   所以双指事件会完整到达这里。
    val zoomGesture = Modifier.pointerInput(windowState) {
        if (windowState != OverlayWindowState.CAPSULE) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom != 1f) onResizeBy(zoom)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(c.panel.copy(alpha = PANEL_ALPHA))
            .border(1.dp, c.border, shape)
            .then(tapGestures)
            .then(zoomGesture),
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
    /** 单击（胶囊态展开）。 */
    onSingleTap: () -> Unit = {},
    /** 双击（切换小窗）。 */
    onDoubleTap: () -> Unit = {},
    /** 长按（快捷菜单）。 */
    onLongPress: () -> Unit = {},
    /** 双指捏合缩放（相对倍率）。 */
    onResizeBy: (Float) -> Unit = {},
    /** 上报标签栏矩形，供触摸拦截器建立禁止拖动区。 */
    onFilterBarBounds: (android.graphics.Rect?) -> Unit = {},
) {
    OverlayRoot(
        colors = colors,
        windowState = windowState,
        unreadCount = unreadCount,
        dragging = dragging,
        onSingleTap = onSingleTap,
        onDoubleTap = onDoubleTap,
        onLongPress = onLongPress,
        onResizeBy = onResizeBy,
        onFilterBarBounds = onFilterBarBounds,
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
