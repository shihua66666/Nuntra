package com.shihua66666.nuntra.overlay

/**
 * 悬浮窗三种形态。
 *
 *  · [CAPSULE] 折叠态小胶囊（默认）
 *  · [PANEL]   展开态终端面板
 *  · [MINI]    小窗态：尺寸比面板小，与面板共用同一套组件
 *
 * 为什么不用 ColorOS 自由浮窗：没有公开 API。
 * 为什么不用 Android PiP：尺寸与交互受限，且会占用系统 PiP 槽位。
 * 三者都是同一个 WindowManager 窗口的不同尺寸与布局，切换即改 LayoutParams。
 */
enum class OverlayWindowState {
    CAPSULE,
    PANEL,
    MINI;

    val isExpanded: Boolean get() = this != CAPSULE

    fun toggleExpanded(): OverlayWindowState = when (this) {
        CAPSULE -> PANEL
        PANEL -> CAPSULE
        MINI -> CAPSULE
    }

    /** 双击：胶囊与面板互切，小窗回到胶囊。 */
    fun toggleMini(): OverlayWindowState = when (this) {
        MINI -> CAPSULE
        CAPSULE -> MINI
        PANEL -> MINI
    }
}
