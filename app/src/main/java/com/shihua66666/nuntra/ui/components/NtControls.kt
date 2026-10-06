package com.shihua66666.nuntra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.ui.theme.LocalAppColors

/**
 * 交互控件集合。
 *
 * 统一原则：低对比、细边框、圆角 6–8dp、无涟漪以外的动效。
 */

/** 主按钮：强调色描边 + 透明底，避免大面积色块刺眼。 */
@Composable
fun NtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = true,
) {
    val colors = LocalAppColors.current
    val borderColor = when {
        !enabled -> colors.border
        accent -> colors.accent
        else -> colors.border
    }
    val textColor = when {
        !enabled -> colors.muted
        accent -> colors.accent
        else -> colors.textPrimary
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = textColor, fontSize = 14.sp)
    }
}

/** 次级按钮：次要文字色，用于「清空」「重试」这类非主路径操作。 */
@Composable
fun NtTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = when {
            !enabled -> colors.muted
            color != null -> color
            else -> colors.infoBlue
        },
        fontSize = 13.sp,
    )
}

/**
 * 开关行：左侧标题 + 可选说明，右侧开关。
 *
 * ★★ 修「点了没反应」的两个关键设计 ★★
 *
 *  ① **整行可点**：Material3 的 Switch 本体只有约 32dp 宽，只响应落在它自己边界内的触摸。
 *     而用户看到的是「一条带标题的开关行」，很自然会去点标题文字 —— 那里原本没有任何可点区域，
 *     表现就是「像点在空气上」。现在整行都是命中区域。
 *
 *  ② **幂等去重**：整行可点之后，同一次点击理论上可能同时被 Switch 与 Row 各处理一次，
 *     结果变成「开→关」，用户看到的仍然是「没反应」。因此这里所有入口统一走 [applyToggle]，
 *     并由 300ms 窗口去重，保证一次点击只应用一个目标值。
 *
 * [debugToast] 打开时每次点击都会弹带序号的 Toast，用于区分：
 *  · 不弹 → 触摸没到达控件（禁用 / 被遮挡 / 布局问题）；
 *  · 弹出且开关变化 → 正常；
 *  · 弹出但序号一次跳 2 → 双触发（去重已生效，提示会写明）。
 */
@Composable
fun NtSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    debugToast: Boolean = false,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current

    var debugSeq by remember { mutableStateOf(0) }
    var lastAppliedAt by remember { mutableLongStateOf(0L) }
    var lastExpected by remember { mutableStateOf<Boolean?>(null) }

    val applyToggle: (Boolean, String) -> Unit = { expected, source ->
        debugSeq += 1
        if (debugToast) {
            NtToast.show(context, "#" + debugSeq + " " + source + " → 目标=" + expected + " 当前=" + checked)
        }
        if (!enabled) {
            if (debugToast) NtToast.show(context, "开关处于禁用状态，点击无效")
        } else {
            val now = android.os.SystemClock.elapsedRealtime()
            val duplicated = lastExpected == expected && (now - lastAppliedAt) < DEDUP_WINDOW_MS
            if (duplicated) {
                if (debugToast) NtToast.show(context, "#" + debugSeq + " 已去重（双触发防护）")
            } else {
                lastAppliedAt = now
                lastExpected = expected
                onCheckedChange(expected)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { applyToggle(!checked, "整行点击") }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) colors.textPrimary else colors.muted,
                fontSize = 14.sp,
            )
            if (subtitle != null) NtCaption(subtitle)
        }
        Switch(
            checked = checked,
            onCheckedChange = { newValue -> applyToggle(newValue, "开关控件") },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.panelElevated,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}

/** 双触发去重窗口（ms）：同一次点击的两个回调不会间隔超过这个值。 */
private const val DEDUP_WINDOW_MS = 300L
