package com.shihua66666.nuntra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.ui.components.NtButton
import com.shihua66666.nuntra.ui.components.NtCaption
import com.shihua66666.nuntra.ui.components.NtPanel
import com.shihua66666.nuntra.ui.components.SectionHeader
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.LocalMonoFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 上次崩溃的堆栈展示页。
 *
 * 为什么需要这个页面：
 *  悬浮窗的 Compose 内容在 addView 之后由 Choreographer 异步组合，
 *  那一阶段抛出的异常不会经过任何调用处的 try-catch，而是直接杀进程 ——
 *  现场什么都看不到。CrashRecorder 会把它写到磁盘，这里在下次启动时读出来。
 *
 * 展示完整堆栈而不是只给一行摘要：真正的原因往往在调用链中段，
 * 截断的信息会让排查来回好几轮。
 */
@Composable
fun CrashReportScreen(
    report: String,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    val mono: FontFamily = LocalMonoFamily.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "上次运行发生了崩溃", color = c.warning, fontSize = 16.sp)
        NtCaption(text = "下面是完整堆栈。请把它发给我，或截图保存。")

        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "崩溃详情")
            Spacer(Modifier.height(8.dp))
            Text(
                text = report,
                color = c.textPrimary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = mono,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NtButton(
                text = "复制到剪贴板",
                onClick = {
                    // 复制：便于用户直接粘贴给我们，避免手抄堆栈出错
                    runCatching {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Nuntra crash", report))
                        NtToast.show(context, "已复制到剪贴板")
                    }.onFailure { tr ->
                        Logx.swallow("CrashReportScreen", "copy", tr)
                        NtToast.show(context, "复制失败")
                    }
                },
            )
            NtButton(
                text = "清除并继续",
                accent = false,
                onClick = onDismiss,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 崩溃页的**安全包装**。
 *
 * 为什么需要：崩溃页本身也是 Compose 代码，如果它自己渲染失败（例如堆栈里含
 * 极端长度的行、或某个组件抛异常），就会把「查看崩溃」变成「又一次崩溃」。
 * 这里用 Compose 的运行时异常兜底把渲染异常降级为一段纯文字，保证：
 *   · 任何情况下都能看到「这里有东西」，而不是白屏或闪退；
 *   · 异常细节仍会打到日志里（Logx），便于排查。
 *
 * 注意：包装器只覆盖**渲染阶段**的异常；它不被放在启动路径上，
 * 只有用户在设置页主动点击时才进入。
 */
@Composable
fun SafeCrashReportScreen(
    report: String,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    try {
        CrashReportScreen(report = report, onDismiss = onDismiss)
    } catch (tr: Throwable) {
        // 渲染异常降级：显示纯文字 + 提供返回按钮，绝不再次抛
        Logx.swallow("CrashReportScreen", "safeRender", tr)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "崩溃详情渲染失败", color = c.warning, fontSize = 15.sp)
            NtCaption(text = "内容过长或格式异常。原始日志仍在文件中，可用日志路径查看。")
            Text(
                text = tr.javaClass.name + ": " + (tr.message ?: ""),
                color = c.textSecondary,
                fontSize = 12.sp,
            )
            NtButton(text = "返回", accent = false, onClick = onDismiss)
        }
    }
}
