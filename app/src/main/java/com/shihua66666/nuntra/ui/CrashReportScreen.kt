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
import androidx.compose.runtime.remember
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

/**
 * 崩溃页的**安全包装**。
 *
 * ★ 为什么不能直接 try/catch（编译器硬性限制）：
 *   Compose 编译器会变换 composable 函数，**禁止**把 composable 调用包在 try/catch 里，
 *   否则报错 “Try catch is not supported around composable function invocation”。
 *   因此这里改成「**纯 Kotlin 预检 + 条件渲染**」：
 *     · 预检只读取字符串（非 composable，可以安全地放在 remember 里）；
 *     · 预检通过就正常渲染；不通过就渲染一个纯文字的降级界面。
 *
 * 注意：预检只能拦住「内容异常」（超长、含 NUL 等），
 * 拦不住「渲染逻辑本身有 bug」—— 那种情况要靠外层（设置页点击入口）来限制影响范围，
 * 而**绝不能让崩溃页出现在启动路径上**（上一版正是因此造成启动死循环）。
 */
@Composable
fun SafeCrashReportScreen(
    report: String,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current

    // ── 纯 Kotlin 预检（非 composable，因此可以包 runCatching）──
    val precheck = remember(report) { precheckReport(report) }

    if (precheck.renderable) {
        // 预检通过：直接调用，**不包 try/catch**
        CrashReportScreen(report = report, onDismiss = onDismiss)
    } else {
        // 降级界面：纯文字 + 返回按钮，同样不含任何可能抛异常的东西
        Logx.w("CrashReportScreen", "崩溃详情未通过预检：" + precheck.reason)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "崩溃详情渲染失败", color = c.warning, fontSize = 15.sp)
            NtCaption(text = "原因：" + precheck.reason)
            NtCaption(text = "原始日志仍在文件中，可在同一分区点击「显示日志路径」查看。")
            NtButton(text = "返回", accent = false, onClick = onDismiss)
        }
    }
}

/** 预检结论。 */
private data class ReportPrecheck(val renderable: Boolean, val reason: String)

/**
 * 崩溃报告的纯 Kotlin 预检。
 *
 * 只做字符串层面的健康检查，**不调用任何 composable、不做 IO**，
 * 因此可以安全地放在 remember 里，也不受 Compose 的 try/catch 限制。
 */
private fun precheckReport(report: String): ReportPrecheck {
    return try {
        when {
            report.isEmpty() -> ReportPrecheck(false, "内容为空")
            report.length > MAX_REPORT_CHARS ->
                ReportPrecheck(false, "内容过长（" + report.length + " 字符）")
            report.contains('\u0000') -> ReportPrecheck(false, "含非法字符")
            else -> {
                val maxLine = report.lineSequence().map { it.length }.maxOrNull() ?: 0
                if (maxLine > MAX_REPORT_LINE_CHARS) {
                    ReportPrecheck(false, "单行过长（" + maxLine + " 字符）")
                } else {
                    ReportPrecheck(true, "")
                }
            }
        }
    } catch (tr: Throwable) {
        // 连字符串检查都抛异常（理论上不会），直接判定不可渲染
        ReportPrecheck(false, "预检异常：" + tr.javaClass.simpleName)
    }
}

/** 报告总长度上限：超过就不直接渲染，避免超长文本拖垮 Text。 */
private const val MAX_REPORT_CHARS = 200_000

/** 单行长度上限：极端长行会让文本测量变慢甚至异常。 */
private const val MAX_REPORT_LINE_CHARS = 20_000
