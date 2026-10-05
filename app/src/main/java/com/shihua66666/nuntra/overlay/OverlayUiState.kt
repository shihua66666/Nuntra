package com.shihua66666.nuntra.overlay

/**
 * 悬浮窗的完整 UI 状态。
 *
 * 拆成 [OverlayWindowState]（形态）+ 这里（形态之外的一切），原因：
 *  · 形态决定 WindowManager 的 LayoutParams，属于「窗口层」；
 *  · 筛选、未读数、查询串属于「内容层」，不应该影响窗口尺寸计算；
 *  · 第 2 步只用到 windowState，其余字段在第 3/4 步填充。
 */
data class OverlayUiState(
    val windowState: OverlayWindowState = OverlayWindowState.CAPSULE,
    /** 当前选中的标签筛选（空集表示「全部」）。 */
    val selectedTagIds: Set<String> = emptySet(),
    /** 关键词筛选（悬浮窗内的即时过滤，与设置页的关注人关键词不是一回事）。 */
    val query: String = "",
    /** 顶部标签筛选栏是否可见（底栏「筛选」按钮控制）。 */
    val filterVisible: Boolean = true,
    /** 状态灯是否呼吸（设置里可关；关闭时静态显示，避免夜间干扰）。 */
    val animateLamp: Boolean = true,
    /** 是否正在拖动：用于降低透明度作为反馈。 */
    val dragging: Boolean = false,
    /** 长按快捷菜单是否展开。 */
    val quickMenuVisible: Boolean = false,
) {
    val isExpanded: Boolean get() = windowState.isExpanded

    /** 长按菜单只在非拖动时弹出。 */
    fun withQuickMenu(visible: Boolean): OverlayUiState =
        copy(quickMenuVisible = visible, dragging = false)

    fun withFilterVisible(visible: Boolean): OverlayUiState = copy(filterVisible = visible)

    fun withState(next: OverlayWindowState): OverlayUiState =
        copy(windowState = next, quickMenuVisible = false)

    fun withDragging(value: Boolean): OverlayUiState =
        copy(dragging = value, quickMenuVisible = if (value) false else quickMenuVisible)
}
