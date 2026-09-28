package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.ui.theme.JinlinColors

/**
 * 设置类页面的**行级公共积木**：组头 + 卡片 + 只读行 + 操作行 + 分隔线。
 *
 * 为什么抽出来：设置页（Tab4）与统计页（Tab3）的「用户管理」组用同一套排版语言，
 * 复制一份必然分叉。同类积木原本在 `SettingsScreen.kt` 内私有，2026-09-14 账号组移入
 * 统计页时抽出到此文件（**仅去重，不改样式**）。
 *
 * > 注意：08-页面设计.md §8 规划的统一组件层（`ListCard` / `LoadingBox` / `EmptyBox` /
 * > `ErrorBox` / `UiFeedback`）**仍未抽取**，这里只解决两页之间的行级重复，不是 §8 的落地。
 */
@Composable
internal fun GroupHeader(title: String, colors: JinlinColors) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.gold,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** 一组设置项的外壳卡片（白/深底 + 圆角，内容纵向排列）。 */
@Composable
internal fun SectionCard(colors: JinlinColors, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column { content() }
    }
}

/** 只读信息行：左标签 + 右值。 */
@Composable
internal fun InfoRow(label: String, value: String, colors: JinlinColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = colors.onSurface)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            color = colors.onSurfaceDim,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 操作行：左标签 + 右尾注（可选 ▸）。
 *
 * [enabled] = false 时整体置灰且**不可点**（占位态：S3/S4 未实现项走此路径，
 * 尾注显式写「开发中」，避免做出可点但无反应的假入口）。
 */
@Composable
internal fun ActionRow(
    label: String,
    trailing: String,
    enabled: Boolean,
    colors: JinlinColors,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (enabled) colors.onSurface else colors.onSurfaceMuted,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = trailing,
            fontSize = 12.sp,
            color = if (enabled) colors.gold else colors.onSurfaceMuted,
        )
    }
}

/** 组内行分隔线（左侧留 14dp 缩进，对齐行内容起始）。 */
@Composable
internal fun RowDivider(colors: JinlinColors) {
    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 14.dp))
}

/**
 * 开关行：左标签（+ 可选副行说明）/ 右 `Switch`（U2，2026-09-20）。
 *
 * 整行可拨：用 `toggleable` 让**行**当命中区、`Switch` 传 `onCheckedChange = null`
 * —— 否则行与开关各接一次点击，可能双双翻转（净效果=没变）。
 * [enabled] = false 时整体置灰且不可拨（与 [ActionRow] 同款占位语义）。
 */
@Composable
internal fun SwitchRow(
    label: String,
    sub: String?,
    checked: Boolean,
    enabled: Boolean,
    colors: JinlinColors,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) { onCheckedChange(it) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = if (enabled) colors.onSurface else colors.onSurfaceMuted,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    fontSize = 11.sp,
                    color = colors.onSurfaceDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * 单选行：左「◉ / ○」+ 右标签，整行可点（U1 数据互通，2026-09-21 新增）。
 *
 * 为什么与 [SwitchRow] 分开：选项**互斥**且需要「一个都不选」的中间态（导入方式 /
 * 导出范围），用 `Switch` 表达不了。符号口径沿用 [TargetPackagesDialog] 既有的
 * `◉` / `○` —— 那就是本项目的单选语言，不另起一套。
 *
 * [enabled] = false 时整行置灰且不可点（与 [ActionRow] / [SwitchRow] 同款占位语义）。
 */
@Composable
internal fun ChoiceRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    colors: JinlinColors,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (checked) "◉" else "○",
            fontSize = 15.sp,
            color = when {
                !enabled -> colors.onSurfaceMuted
                checked -> colors.gold
                else -> colors.onSurfaceDim
            },
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (enabled) colors.onSurface else colors.onSurfaceMuted,
        )
    }
}
