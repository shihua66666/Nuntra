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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.model.MessageSortMode
import com.shihua66666.nuntra.model.TagView
import com.shihua66666.nuntra.ui.components.NtTextButton
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
    /** 当前排序方式（顶部「排序」按钮切换）。 */
    sortMode: MessageSortMode,
    /** 关键词命中提示，例如「命中 2 个关键词」；null 表示没配置关键词，不显示。 */
    keywordHint: String?,
    onToggleSort: () -> Unit,
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

        // ── 排序栏（需求 2-3 / 2-4）──
        //
        //   放在标题栏正下方：这里是「展开态顶部」，位置最直观；
        //   底栏已经挤了 5 个按钮，再塞排序按钮会在窄面板里被压扁。
        //   「命中 N 个关键词」紧跟在排序按钮右侧 —— 需求要求的「显示在排序按钮旁边」。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NtTextButton(
                text = sortMode.label,
                onClick = onToggleSort,
                // 处在「按命中数」时用强调色，一眼能看出当前不是默认排序
                color = if (sortMode == MessageSortMode.KEYWORD_HITS) c.accent else null,
            )
            if (keywordHint.isNullOrEmpty()) {
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    text = keywordHint,
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
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
                    // 用 localToWindow + size 计算矩形，而不用 boundsInRoot()：
                    //   · size / localToWindow 都是 LayoutCoordinates 的**成员**，跨版本稳定；
                    //   · boundsInRoot / boundsInWindow 是 androidx.compose.ui.layout 里的
                    //     **顶层扩展函数**，需要额外 import —— 上一版正是漏了它导致编译失败。
                    // 坐标口径与拦截器里的 ev.x / ev.y 一致：都以窗口左上角为原点。
                    val topLeft = coords.localToWindow(Offset.Zero)
                    val top = topLeft.y.toInt()
                    val bottom = (topLeft.y + coords.size.height).toInt()
                    // ★★ 横向取「整窗宽度」而不是标签栏自身宽度（你的终极备选方案）★★
                    //
                    //   原因：横向坐标是最容易出错的一维 ——
                    //   只要窗口有偏移量、或状态栏/导航栏算错一格，
                    //   标签栏右侧就会落进「非禁区」，手指按在那里仍会被拖走。
                    //   与其在 X 轴上对齐两个坐标系，不如直接放弃 X 轴判定：
                    //   只要手势起点在这个**高度条带**内，就一律不拖窗口。
                    //
                    //   代价：标签栏那一行不能再拖动窗口 ——
                    //   但拖窗口可用标题栏/正文区，而「标签栏能滑」是不可妥协的。
                    onFilterBarBounds(
                        android.graphics.Rect(
                            -BAND_HALF_WIDTH,
                            top,
                            BAND_HALF_WIDTH,
                            bottom,
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

/**
 * 禁区条带的半宽（px）：横向只在 Y 轴上判定，X 轴取一个足够大的范围。
 *
 * 用「足够大」而不是 Int.MAX_VALUE：避免极端值在 Rect.contains 里产生算术意外，
 * 10 万 px 已远超任何真实屏幕宽度（最大也就 4K 竖屏 ≈ 2160px）。
 */
private const val BAND_HALF_WIDTH = 100_000

/** 标题栏右侧摘要：不显示无意义的前缀，尽可能短。 */
private fun filterSummary(selectedTagCount: Int, total: Int, unread: Int): String {
    val filter = if (selectedTagCount > 0) "筛选 " + selectedTagCount else "全部"
    return filter + " · " + total + " 条 · 未读 " + unread
}
