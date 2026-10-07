package com.shihua66666.nuntra.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.shihua66666.nuntra.ui.theme.LocalAppColors

/**
 * 静态空壳胶囊 —— **悬浮窗的最后一道防线**。
 *
 * 设计约束（这是被「组合期崩溃」事故逼出来的）：
 *
 *  · **不读取任何数据源**：不碰 ServiceLocator、不碰 MessageStore/TagRepository、
 *    不 collectAsState、不 remember 任何外部对象。
 *  · **不依赖任何可变状态**：连 uiState 都不读，因此不可能因为状态异常而崩。
 *  · 只用 [LocalAppColors]（由外层 OverlayContentShell 提供，纯内存值）。
 *
 * 触发场景：依赖容器尚未就绪。此时宁可显示一个「安静的小点」，
 * 也绝不能让首次组合抛异常 —— 组合期异常无法被 try-catch 拦截，会直接杀进程。
 */
@Composable
fun StaticOverlayCapsule(modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(c.muted),
        )
        Spacer(Modifier.width(8.dp))
    }
}
