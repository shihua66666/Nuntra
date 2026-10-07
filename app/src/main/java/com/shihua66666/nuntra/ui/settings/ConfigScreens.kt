package com.shihua66666.nuntra.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shihua66666.nuntra.core.AlertPlayer
import com.shihua66666.nuntra.core.NtToast
import com.shihua66666.nuntra.model.Tag
import com.shihua66666.nuntra.model.WatchedContact
import com.shihua66666.nuntra.ui.components.NtButton
import com.shihua66666.nuntra.ui.components.NtCaption
import com.shihua66666.nuntra.ui.components.NtDivider
import com.shihua66666.nuntra.ui.components.NtPanel
import com.shihua66666.nuntra.ui.components.NtSwitchRow
import com.shihua66666.nuntra.ui.components.SectionHeader
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.TagPalette
import kotlin.math.roundToInt

/**
 * 三个真正的配置页：标签管理 / 关注人与关键词 / 提醒音与重复提醒。
 *
 * 为什么集中在一个文件：
 *  三者共用同一套壳（返回栏 + 滚动列 + 面板），拆三个文件会让共用部分重复三遍。
 *  等它们的体量继续增长（例如标签页加入分组）再拆也不迟。
 *
 * 共同约束：页面本身**不持有数据**，全部通过参数传入、通过回调写出，
 *  由 MainActivity 统一读写 DataStore。这样页面是纯函数式 UI，易于验证。
 */

// ══════════════════════════════════════════════════════════════════
// ①  标签管理
// ══════════════════════════════════════════════════════════════════

/**
 * 标签管理页。
 *
 * 能力：新增 / 改名 / 改色 / 拖拽排序（含上下移动兜底）/ 删除 / 每个标签的
 * 「特殊关注」「静音」开关。
 *
 * 拖拽实现说明：
 *  用 detectDragGesturesAfterLongPress（长按后拖动），松手时按「累计位移 ÷ 行高」
 *  算出移动了几个位置。行高由 onSizeChanged 实测，因此展开颜色选择器导致行变高时
 *  依然准确。同时保留 ↑↓ 按钮 —— 拖拽在小屏上不容易命中，按钮是可靠路径。
 */
@Composable
fun TagManagerScreen(
    tags: List<Tag>,
    onAdd: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onRecolor: (String, String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleFlags: (String, Boolean, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalAppColors.current
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(TagPalette.PRESETS.first()) }
    var renaming by remember { mutableStateOf<Tag?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    var colorEditorFor by remember { mutableStateOf<String?>(null) }

    ConfigScaffold(title = "标签管理", onBack = onBack) {
        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "新增标签")
            Spacer(Modifier.height(8.dp))
            NtOutlinedField(
                value = newName,
                onValueChange = { newName = it },
                label = "标签名（1~" + Tag.NAME_MAX_LENGTH + " 字）",
            )
            Spacer(Modifier.height(10.dp))
            NtCaption(text = "颜色")
            Spacer(Modifier.height(6.dp))
            ColorSwatchRow(selected = newColor, onSelect = { newColor = it })
            Spacer(Modifier.height(12.dp))
            NtButton(
                text = "新增标签",
                enabled = newName.isNotBlank(),
                onClick = {
                    onAdd(newName, newColor)
                    newName = ""
                },
            )
        }

        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "标签列表（" + tags.size + "）")
            Spacer(Modifier.height(4.dp))
            NtCaption(text = "长按拖动排序，或用 ↑ ↓ 按钮；点名字改名，点色块改色。")
            Spacer(Modifier.height(10.dp))
            tags.forEachIndexed { index, tag ->
                if (index > 0) {
                    Spacer(Modifier.height(6.dp))
                    NtDivider()
                    Spacer(Modifier.height(6.dp))
                }
                TagRow(
                    index = index,
                    tag = tag,
                    total = tags.size,
                    colorEditorOpen = colorEditorFor == tag.id,
                    onToggleColorEditor = {
                        colorEditorFor = if (colorEditorFor == tag.id) null else tag.id
                    },
                    onPickColor = { hex ->
                        onRecolor(tag.id, hex)
                        colorEditorFor = null
                    },
                    onStartRename = {
                        renaming = tag
                        renameText = tag.name
                    },
                    onMove = onMove,
                    onToggleFlags = { priority, muted ->
                        onToggleFlags(tag.id, priority, muted)
                    },
                    onDelete = { deleting = tag },
                )
            }
        }
    }

    // 改名对话框
    val renameTarget = renaming
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(text = "重命名标签", color = c.textPrimary) },
            text = {
                NtOutlinedField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = "新名字",
                )
            },
            confirmButton = {
                NtButton(
                    text = "保存",
                    onClick = {
                        onRename(renameTarget.id, renameText)
                        renaming = null
                    },
                )
            },
            dismissButton = {
                NtButton(text = "取消", accent = false, onClick = { renaming = null })
            },
            containerColor = c.panelElevated,
        )
    }

    // 删除确认对话框：删除会把该标签下的消息迁移到兜底标签，属于不可逆操作，必须确认。
    val deleteTarget = deleting
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(text = "删除标签「" + deleteTarget.name + "」？", color = c.textPrimary) },
            text = {
                NtCaption(text = "该标签下的消息会迁移到兜底标签，消息本身不会丢失。")
            },
            confirmButton = {
                NtButton(
                    text = "删除",
                    onClick = {
                        onDelete(deleteTarget.id)
                        deleting = null
                    },
                )
            },
            dismissButton = {
                NtButton(text = "取消", accent = false, onClick = { deleting = null })
            },
            containerColor = c.panelElevated,
        )
    }
}

@Composable
private fun TagRow(
    index: Int,
    tag: Tag,
    total: Int,
    colorEditorOpen: Boolean,
    onToggleColorEditor: () -> Unit,
    onPickColor: (String) -> Unit,
    onStartRename: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleFlags: (Boolean, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    // 实测行高：拖拽换算位置时用它，行内容变化（如展开色板）后依然准确
    var rowHeightPx by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .pointerInput(tag.id, total) {
                var accumulated = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                    onDragEnd = {
                        if (rowHeightPx > 0f) {
                            val steps = (accumulated / rowHeightPx).roundToInt()
                            if (steps != 0) {
                                onMove(index, (index + steps).coerceIn(0, total - 1))
                            }
                        }
                        accumulated = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount.y
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 颜色块：点它展开/收起色板
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(TagPalette.resolve(tag.color))
                    .border(1.dp, c.border, CircleShape)
                    .clickable(onClick = onToggleColorEditor),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.name + if (tag.isDefault) "（兜底标签）" else "",
                    color = c.textPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onStartRename),
                )
                NtCaption(
                    text = "优先级 " + tag.priority +
                        if (tag.priorityAlert) " · 特殊关注" else if (tag.muted) " · 静音" else "",
                )
            }
            NtButton(
                text = "↑",
                accent = false,
                enabled = index > 0,
                onClick = { onMove(index, index - 1) },
            )
            Spacer(Modifier.width(4.dp))
            NtButton(
                text = "↓",
                accent = false,
                enabled = index < total - 1,
                onClick = { onMove(index, index + 1) },
            )
            Spacer(Modifier.width(4.dp))
            NtButton(
                text = "删除",
                accent = false,
                // 兜底标签不可删除（TagRepository 也会拒绝，这里提前禁用避免误操作）
                enabled = !tag.isDefault && total > 1,
                onClick = onDelete,
            )
        }

        Spacer(Modifier.height(4.dp))
        NtSwitchRow(
            title = "特殊关注",
            subtitle = "该标签的新消息播放提醒音",
            checked = tag.priorityAlert,
            onCheckedChange = { on -> onToggleFlags(on, false) },
        )
        NtSwitchRow(
            title = "静音",
            subtitle = "不播放任何提醒音",
            checked = tag.muted,
            onCheckedChange = { on -> onToggleFlags(false, on) },
        )

        if (colorEditorOpen) {
            Spacer(Modifier.height(6.dp))
            ColorSwatchRow(selected = tag.color, onSelect = onPickColor)
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// ②  关注人与关键词
// ══════════════════════════════════════════════════════════════════

/**
 * 关注人与关键词页。
 *
 * 这是「标签分类」能否生效的关键页：
 *  只有在这里建立了「张三 → 家人」的绑定，张三的消息才会归入「家人」；
 *  没有任何关注人命中时，消息统一归入兜底标签（默认预设里是「工作」）——
 *  这正是用户在没配置关注人时看到「全都被分到工作」的原因，属于设计行为而非错误。
 *
 * 关键词的作用：只做「命中记录与展示」，不决定标签归属（归属只看关注人）。
 */
@Composable
fun ContactsScreen(
    contacts: List<WatchedContact>,
    tags: List<Tag>,
    keywords: Set<String>,
    onUpsertContact: (String, String) -> Unit,
    onRemoveContact: (String) -> Unit,
    onToggleContact: (String, Boolean) -> Unit,
    onAddKeyword: (String) -> Unit,
    onRemoveKeyword: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalAppColors.current
    val tagById = remember(tags) { tags.associateBy { it.id } }
    var contactName by remember { mutableStateOf("") }
    var selectedTagId by remember { mutableStateOf(tags.firstOrNull()?.id.orEmpty()) }
    var keywordInput by remember { mutableStateOf("") }

    ConfigScaffold(title = "关注人与关键词", onBack = onBack) {
        if (tags.isEmpty()) {
            NtPanel(modifier = Modifier.fillMaxWidth()) {
                NtCaption(text = "还没有标签可用：请先在「标签管理」里创建标签。")
            }
        }

        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "新增关注人")
            Spacer(Modifier.height(8.dp))
            NtCaption(text = "填联系人名字并选择标签：命中后该联系人的消息归入所选标签。")
            Spacer(Modifier.height(8.dp))
            NtOutlinedField(
                value = contactName,
                onValueChange = { contactName = it },
                label = "联系人名字（如：张三）",
            )
            Spacer(Modifier.height(10.dp))
            NtCaption(text = "绑定标签")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    NtButton(
                        text = tag.name,
                        accent = selectedTagId == tag.id,
                        onClick = { selectedTagId = tag.id },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            NtButton(
                text = "保存关注人",
                enabled = contactName.isNotBlank() && selectedTagId.isNotEmpty(),
                onClick = {
                    onUpsertContact(contactName, selectedTagId)
                    contactName = ""
                },
            )
        }

        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "关注人列表（" + contacts.size + "）")
            Spacer(Modifier.height(4.dp))
            if (contacts.isEmpty()) {
                NtCaption(
                    text = "还没有关注人 —— 当前所有消息都会归入兜底标签。「工作」就是默认的兜底标签。",
                )
            } else {
                contacts.forEach { contact ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = contact.name, color = c.textPrimary, fontSize = 14.sp)
                            NtCaption(
                                text = "→ " + (tagById[contact.tagId]?.name ?: "标签已删除"),
                            )
                        }
                        NtButton(
                            text = if (contact.enabled) "停用" else "启用",
                            accent = false,
                            onClick = { onToggleContact(contact.name, !contact.enabled) },
                        )
                        Spacer(Modifier.width(4.dp))
                        NtButton(
                            text = "删除",
                            accent = false,
                            onClick = { onRemoveContact(contact.name) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "关键词（" + keywords.size + "）")
            Spacer(Modifier.height(6.dp))
            NtCaption(text = "命中关键词会在消息上标注；标签归属仍由关注人决定。")
            Spacer(Modifier.height(10.dp))
            NtOutlinedField(
                value = keywordInput,
                onValueChange = { keywordInput = it },
                label = "新增关键词",
            )
            Spacer(Modifier.height(8.dp))
            NtButton(
                text = "添加",
                enabled = keywordInput.isNotBlank(),
                onClick = {
                    onAddKeyword(keywordInput)
                    keywordInput = ""
                },
            )
            Spacer(Modifier.height(10.dp))
            if (keywords.isEmpty()) {
                NtCaption(text = "暂无关键词。")
            } else {
                keywords.forEach { word ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = word,
                            color = c.textPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        NtButton(
                            text = "移除",
                            accent = false,
                            onClick = { onRemoveKeyword(word) },
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// ③  提醒音与重复提醒
// ══════════════════════════════════════════════════════════════════

/** 重复提醒间隔预设（分钟）。 */
private val REPEAT_INTERVAL_PRESETS = listOf(1, 2, 5, 10)

/** 重复提醒次数预设；0 表示不限次数。 */
private val REPEAT_MAX_PRESETS = listOf(1, 3, 5, 10)

/**
 * 提醒音与重复提醒页。
 *
 * 「试听」是真实播放：走 AlertPlayer，音源优先级为
 *   res/raw/alert_priority → res/raw/alert_fallback → 系统默认通知音。
 * 这样即使没有放入 alert_priority.mp3，试听也一定有声音（或明确提示失败）。
 */
@Composable
fun ReminderScreen(
    alertEnabled: Boolean,
    onToggleAlert: (Boolean) -> Unit,
    alertVolume: Float,
    onChangeVolume: (Float) -> Unit,
    alertSound: String,
    onChangeSound: (String) -> Unit,
    repeatEnabled: Boolean,
    onToggleRepeat: (Boolean) -> Unit,
    repeatIntervalMinutes: Int,
    onChangeInterval: (Int) -> Unit,
    repeatMaxTimes: Int,
    onChangeMaxTimes: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    ConfigScaffold(title = "提醒音与重复提醒", onBack = onBack) {
        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "提醒音")
            Spacer(Modifier.height(6.dp))
            NtSwitchRow(
                title = "启用提醒音",
                subtitle = "关闭后任何标签都不会发声（特殊关注也一并失效）",
                checked = alertEnabled,
                onCheckedChange = onToggleAlert,
            )
            Spacer(Modifier.height(10.dp))
            NtCaption(text = "音源")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NtButton(
                    text = "内置提示音",
                    accent = alertSound == AlertPlayer.SOUND_BUILTIN,
                    onClick = { onChangeSound(AlertPlayer.SOUND_BUILTIN) },
                )
                NtButton(
                    text = "系统默认通知音",
                    accent = alertSound == AlertPlayer.SOUND_SYSTEM,
                    onClick = { onChangeSound(AlertPlayer.SOUND_SYSTEM) },
                )
            }
            Spacer(Modifier.height(6.dp))
            NtCaption(
                text = "内置音优先使用 res/raw/alert_priority" +
                    "（放入 mp3 即自动生效），缺失时回退内置兜底音。",
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NtButton(
                    text = "试听",
                    onClick = {
                        val ok = AlertPlayer.play(context, alertVolume, alertSound)
                        if (!ok) NtToast.show(context, "播放失败：没有可用的提醒音")
                    },
                )
                NtButton(
                    text = "停止",
                    accent = false,
                    onClick = { AlertPlayer.stop() },
                )
            }
            Spacer(Modifier.height(12.dp))
            NtCaption(text = "音量 " + (alertVolume * 100).roundToInt() + "%")
            Slider(
                value = alertVolume,
                onValueChange = onChangeVolume,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        NtPanel(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "重复提醒")
            Spacer(Modifier.height(6.dp))
            NtSwitchRow(
                title = "启用重复提醒",
                subtitle = "特殊关注的消息如果一直没被处理，会按间隔反复提醒",
                checked = repeatEnabled,
                onCheckedChange = onToggleRepeat,
            )
            Spacer(Modifier.height(12.dp))
            NtCaption(text = "提醒间隔（当前 " + repeatIntervalMinutes + " 分钟）")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REPEAT_INTERVAL_PRESETS.forEach { minutes ->
                    NtButton(
                        text = minutes.toString() + " 分钟",
                        accent = repeatIntervalMinutes == minutes,
                        onClick = { onChangeInterval(minutes) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            NtCaption(
                text = "最大次数（当前 " +
                    (if (repeatMaxTimes <= 0) "不限" else repeatMaxTimes.toString() + " 次") + "）",
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REPEAT_MAX_PRESETS.forEach { times ->
                    NtButton(
                        text = times.toString() + " 次",
                        accent = repeatMaxTimes == times,
                        onClick = { onChangeMaxTimes(times) },
                    )
                }
                NtButton(
                    text = "不限",
                    accent = repeatMaxTimes <= 0,
                    onClick = { onChangeMaxTimes(0) },
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// 共用组件
// ══════════════════════════════════════════════════════════════════

/** 配置页外壳：返回栏 + 可滚动内容列。与设置页保持同一套视觉。 */
@Composable
private fun ConfigScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalAppColors.current
    Scaffold(containerColor = c.background) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtButton(text = "返回", onClick = onBack, accent = false)
                Spacer(Modifier.width(12.dp))
                Text(text = title, color = c.textPrimary, fontSize = 16.sp)
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 统一风格的输入框：避免每个页面各写一套颜色配置。 */
@Composable
private fun NtOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    val c = LocalAppColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = label, fontSize = 12.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.textPrimary,
            unfocusedTextColor = c.textPrimary,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.border,
            focusedLabelColor = c.accent,
            unfocusedLabelColor = c.textSecondary,
            cursorColor = c.accent,
        ),
    )
}

/** 标签配色选择：只允许 TagPalette.PRESETS 里的颜色，保证与徽标渲染一致。 */
@Composable
private fun ColorSwatchRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val c = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TagPalette.PRESETS.forEach { hex ->
            Box(
                modifier = Modifier
                    .size(if (hex == selected) 26.dp else 22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TagPalette.resolve(hex))
                    .border(
                        width = if (hex == selected) 2.dp else 1.dp,
                        color = if (hex == selected) c.textPrimary else c.border,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(hex) },
            )
        }
    }
}
