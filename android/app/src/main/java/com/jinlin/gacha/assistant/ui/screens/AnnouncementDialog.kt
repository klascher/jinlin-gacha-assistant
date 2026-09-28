package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.Announcement
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.JinlinColors

/**
 * 公告弹窗（§15 §4.3③）—— 交互语义照搬 PC `announcement_dialog.py`：
 *
 * - `pinned`（置顶/免责）：**「确认并同意」固定在弹窗底部**（内容再长也一眼可见 ——
 *   2026-09-28 真机反馈：原内联按钮随内容滚到很下面，用户找不到）；确认即记已读，
 *   **此后不再弹**（2026-09-28 用户裁定，偏离原 §15「每次启动都弹」设计）；
 * - `update`：每条一个「不再提示」勾选框，**关闭时只记勾上的**（未勾的下次还弹）；
 * - 「全部不再提示」：更新全部记已读 + 关闭。**pinned 未确认前置灰**（2026-09-28 真机反馈：
 *   原版可直接绕过「确认并同意」，免责语义失效）——此处**有意偏离 PC** `_mark_all_read` 的
 *   「置顶视同已确认」行为；
 * - 底部固定：反馈邮箱 + 赞助语（与 PC 同句）。
 *
 * @param items     **未读**公告（调用方已按服务端顺序过滤；pinned 在前）
 * @param onClose   关闭回调；参数 = 本轮要记已读的 id 集合（调用方写 [com.jinlin.gacha.assistant.persistence.AnnouncementReadStore]）
 */
@Composable
internal fun AnnouncementDialog(
    items: List<Announcement>,
    colors: JinlinColors,
    onClose: (markedRead: Set<String>) -> Unit,
) {
    // 每条确认/勾选状态（内存即可；关闭时一次性落盘）
    val confirmed = remember { mutableStateOf(setOf<String>()) }
    val dontPrompt = remember { mutableStateOf(setOf<String>()) }
    val pinnedIds = remember(items) { items.filter { it.type == "pinned" }.map { it.id } }
    val allPinnedConfirmed = pinnedIds.all { it in confirmed.value }

    Dialog(
        // pinned 未确认完：返回键/点外部拦下（PC `rejectCloseEvent` 同款腔调）
        onDismissRequest = { if (allPinnedConfirmed) onClose(confirmed.value + dontPrompt.value) },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = androidx.compose.material3.MaterialTheme.shapes.large,
            color = colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // —— 公告列表（内容超出内部滚动；单条内不再滚，整体滚）——
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items.size) { index ->
                        val ann = items[index]
                        Column {
                            if (ann.type == "pinned") {
                                Text(
                                    text = stringResource(R.string.ann_pinned_badge),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ColorWarpedRed,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.ann_update_badge),
                                    fontSize = 12.sp,
                                    color = colors.onSurfaceMuted,
                                )
                            }
                            Text(
                                text = ann.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            MiniMarkdownText(
                                text = ann.content,
                                colors = colors,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (ann.type != "pinned") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    Checkbox(
                                        checked = ann.id in dontPrompt.value,
                                        onCheckedChange = { on ->
                                            dontPrompt.value = if (on) {
                                                dontPrompt.value + ann.id
                                            } else {
                                                dontPrompt.value - ann.id
                                            }
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.ann_dont_prompt),
                                        fontSize = 13.sp,
                                        color = colors.onSurfaceDim,
                                    )
                                }
                            }
                        }
                        if (index != items.lastIndex) {
                            HorizontalDivider(color = colors.divider)
                        }
                    }
                }

                // pinned 未确认完：红字提示（解释「全部不再提示」为何置灰，PC 同句）
                if (!allPinnedConfirmed && pinnedIds.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.ann_close_blocked),
                        fontSize = 12.sp,
                        color = ColorWarpedRed,
                    )
                }

                // —— 底部按钮行：确认并同意（未确认时）/ 关闭 + 全部不再提示 ——
                // 主按钮固定在滚动区外，长内容也遮挡不住（2026-09-28 真机反馈）；
                // 「全部不再提示」在 pinned 确认前置灰，不可绕过免责确认（同上反馈）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        enabled = allPinnedConfirmed,
                        onClick = {
                            // PC `_mark_all_read` 的更新部分：更新全部记已读（pinned 已确认完才走到这）
                            onClose(confirmed.value + pinnedIds + dontPrompt.value +
                                items.filter { it.type != "pinned" }.map { it.id })
                        },
                    ) {
                        Text(stringResource(R.string.ann_mark_all))
                    }
                    Button(
                        onClick = {
                            if (allPinnedConfirmed) {
                                onClose(confirmed.value + dontPrompt.value)
                            } else {
                                // 一键确认全部 pinned（确认后主按钮变「关闭」）
                                confirmed.value = confirmed.value + pinnedIds
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (allPinnedConfirmed) R.string.ann_close else R.string.ann_confirm
                            )
                        )
                    }
                }

                // —— 固定联系信息（邮箱 + 赞助，与 PC 同句）——
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.ann_contact),
                        fontSize = 11.sp,
                        color = colors.onSurfaceMuted,
                    )
                    Text(
                        text = stringResource(R.string.settings_footer_sponsor),
                        fontSize = 11.sp,
                        color = colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}
