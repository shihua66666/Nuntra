package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.model.TagView
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.LocalMonoFamily

/**
 * 展开态终端面板。
 *
 * 结构（自上而下）：
 *   标题栏 —— 左侧强调色竖条 + 标题；右侧当前筛选摘要（等宽字体）。
 *   筛选栏 —— [TagFilterBar]，条目来自用户标签（可折叠隐藏）。
 *   内容区 —— 由 [content] 注入（第 4 步接入 MessageList；当前为占位）。
 *   底部操作栏 —— [PanelBottomBar]。
 *
 * 面板态与小窗态**共用本组件**：两者只是窗口尺寸不同（由 OverlayWindowController 控制），
 * 内部布局一致。小窗态下通过 [compact] 收紧内边距，避免内容被挤得局促。
 */
@Composable
fun ExpandedPanel(
    colors: com.shihua66666.nuntra.ui.theme.AppColors,
    title: String,
    tags: List<TagView>,
    selectedTagIds: Set<String>,
    onToggleTag: (String) -> Unit,
    onSelectAll: () -> Unit,
    filterVisible: Boolean,
    messageCount: Int,
    unreadCount: Int,
    compact: Boolean,
    onToggleFilter: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearMessages: () -> Unit,
    onCollapse: () -> Unit,
    /** 消息列表插槽：点击行为由插槽内部实现（标记已读 / 第 5 步跳转）。 */
    content: @Composable () -> Unit,
    /**
     * 上报标签栏的矩形（窗口本地坐标），供触摸拦截器建立「禁止拖动区」。
     *
     * 传 null 表示当前没有标签栏（隐藏或已销毁）——拦截器随即恢复全域拖动。
     */
    onFilterBarBounds: (android.graphics.Rect?) -> Unit = {},
) {
    val c = LocalAppColors.current
    val mono: FontFamily = LocalMonoFamily.current
    val horizontalPadding = if (compact) 10.dp else 12.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // ── 标题栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = title, color = c.textPrimary, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            // 筛选摘要：让用户一眼知道当前是不是「只看某些标签」
            Text(
                text = filterSummary(selectedTagIds.size, messageCount, unreadCount),
                color = c.textSecondary,
                fontSize = 11.sp,
                fontFamily = mono,
            )
        }

        // ── 筛选栏（可隐藏）──
        //
        //   ★ 必须把它的矩形登记给拦截器，否则横向滑动会被当成「拖动窗口」，
        //     标签栏只能点、不能滑（这正是之前的真机问题）。
        //   用 boundsInRoot()：它与拦截器里的 ev.x / ev.y 同为「窗口左上角」原点。
        if (filterVisible) {
            TagFilterBar(
                tags = tags,
                selectedTagIds = selectedTagIds,
                onToggleTag = onToggleTag,
                onSelectAll = onSelectAll,
                modifier = Modifier.onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    onFilterBarBounds(
                        android.graphics.Rect(
                            r.left.toInt(),
                            r.top.toInt(),
                            r.right.toInt(),
                            r.bottom.toInt(),
                        ),
                    )
                },
            )
        }

        // ── 内容区 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = horizontalPadding),
        ) {
            content()
        }

            // 标签栏隐藏或本组件被销毁时，必须清掉禁区，
            // 否则那块区域会永远「拖不动窗口」。
            DisposableEffect(filterVisible) {
                if (!filterVisible) onFilterBarBounds(null)
                onDispose { onFilterBarBounds(null) }
            }

            // ★ 为底部操作栏预留高度：底栏已移出 Column（见下方 Box 的 align），
            //   这里用等高的占位保证内容区不被底栏压住。
            Spacer(Modifier.height(BOTTOM_BAR_RESERVE))
        }

        // ★★ 底部操作栏锚定在窗口最底部 ★★
        //
        //   为什么从 Column 末尾移到这里：
        //     放在 Column 里时，一旦窗口高度不足（用户捏合缩得很小），
        //     固定子项总高超过窗口高度就会溢出 → 底栏被裁掉，
        //     用户再也点不到「折叠」「设置」。
        //     放进 Box 并 align(BottomCenter) 后由 Box 独立摆放，
        //     永远贴在窗口底部，不受内容区高度影响。
        PanelBottomBar(
            messageCount = messageCount,
            filterActive = filterVisible,
            onToggleFilter = onToggleFilter,
            onOpenSettings = onOpenSettings,
            onClearMessages = onClearMessages,
            onCollapse = onCollapse,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 底部操作栏预留高度：与 PanelBottomBar 实际高度相当（含上下 padding）。 */
private val BOTTOM_BAR_RESERVE = 48.dp

/** 标题栏右侧摘要：不显示无意义的前缀，尽可能短。 */
private fun filterSummary(selectedTagCount: Int, total: Int, unread: Int): String {
    val filter = if (selectedTagCount > 0) "筛选 " + selectedTagCount else "全部"
    return filter + " · " + total + " 条 · 未读 " + unread
}
