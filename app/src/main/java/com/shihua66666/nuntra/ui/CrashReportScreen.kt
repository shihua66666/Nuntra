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
