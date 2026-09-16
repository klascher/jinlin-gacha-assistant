package com.jinlin.gacha.assistant.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.persistence.BackupInfo
import com.jinlin.gacha.assistant.persistence.HistoryEpoch
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.Profile
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import java.io.File

/**
 * **用户管理组**（原设置页「账号组」）。
 *
 * 2026-09-14 用户决定：账号管理整体移入**统计页（Tab3）顶部**并改名「用户管理」，设置页不再保留。
 * 依据 `mobile/docs/09-设置模块设计.md` §3.1 / §12.1（本文件承载该组的全部 UI 与交互）。
 *
 * ### 交互契约（对齐 PC `ProfileManager`，「即改即存」无保存按钮）
 * - 切换：`ProfileStore.setActive` → 因账号是进程级 StateFlow，抓包页顶栏与下次抓包的落库账号**即时联动**；
 * - 新建 / 重命名：名称去空白；与现有账号**重名则拒绝**（Toast，避免两个同名账号无法区分）；
 * - 删除：红字二次确认；**仅剩一个账号时按钮禁用**（Store 内部同样拒绝，双保险）；删除时记录文件归档为
 *   `<id>_<stamp>.json`。
 *
 * ### 清空 / 还原（2026-09-16 新增，对齐 PC `btn_clear` + `_restore_data`）
 * 「清空当前账号历史」与「还原抽卡数据」放在本组而非设置页：PC 的清空按钮就紧挨账号下拉框
 * （`main_window.py:264`），且 docstring 写死语义是「放弃**当前账号**的旧本地历史」——它是账号
 * 维度操作，与账号管理同组才顺。设置页「清理本地数据」是**全局存储治理**（跨账号、不备份），
 * 两者边界不同，见 `09-设置模块设计.md` §3.5。
 *
 * **两项都要求先停止抓包**（抓包中置灰）：本场会话的 Δ 已在 `SessionDedup` 内按会话开始时的
 * 基准实时归一化（收下即 `pos − Δ`），中途清空或换坐标系会让本场记录位置整体错位。
 */
@Composable
internal fun AccountSection(colors: JinlinColors) {
    val context = LocalContext.current
    val store = remember { ProfileStore.get(context) }
    val prof by store.state.collectAsState()
    val running = GachaVpnService.state.collectAsState().value.running

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    // null = 未打开还原界面；非 null = 已扫好的还原点列表（每次点入口重新扫）
    var restoreList by remember { mutableStateOf<List<BackupInfo>?>(null) }
    var pendingRestore by remember { mutableStateOf<BackupInfo?>(null) }

    val emptyName = stringResource(R.string.account_value_empty)
    val activeName = prof.items.firstOrNull { it.id == prof.activeId }?.name ?: emptyName

    // 当前账号的历史读写口（纯 JVM；clear/restore/backups 均落此实例）
    val historyStore = remember(prof.activeId) {
        HistoryStore(File(context.filesDir, ProfileStore.USERS_DIR), prof.activeId)
    }

    GroupHeader(stringResource(R.string.stats_section_users), colors)
    SectionCard(colors) {
        ActionRow(
            label = stringResource(R.string.account_current),
            trailing = activeName,
            enabled = true,
            onClick = { showSwitchDialog = true },
            colors = colors,
        )
        RowDivider(colors)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.account_new), fontSize = 13.sp) }

            OutlinedButton(
                onClick = { showRenameDialog = true },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.account_rename), fontSize = 13.sp) }

            // 仅剩一个账号时禁用（Store 内部也会拒绝，双保险）
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                enabled = prof.items.size > 1,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.account_delete), fontSize = 13.sp) }
        }
        RowDivider(colors)
        // 抓包中禁用：本场会话的 Δ 基准已按会话开始时的历史定死，中途清空/换坐标系会让本场记录错位
        ActionRow(
            label = stringResource(R.string.account_clear_history),
            trailing = if (running) stringResource(R.string.account_need_stop) else "▸",
            enabled = !running,
            colors = colors,
            onClick = { showClearDialog = true },
        )
        RowDivider(colors)
        ActionRow(
            label = stringResource(R.string.account_restore_history),
            trailing = if (running) stringResource(R.string.account_need_stop) else "▸",
            enabled = !running,
            colors = colors,
            onClick = {
                // 每次点入口重扫（不用 remember 缓存：对话框可能要连开多次，且清空/还原后列表会变）
                restoreList = runCatching { historyStore.backups() }.getOrDefault(emptyList())
            },
        )
    }

    if (showSwitchDialog) {
        SwitchAccountDialog(
            items = prof.items,
            activeId = prof.activeId,
            colors = colors,
            onPick = { id ->
                val name = prof.items.firstOrNull { it.id == id }?.name.orEmpty()
                store.setActive(id)
                toast(context, context.getString(R.string.account_toast_switched, name))
                showSwitchDialog = false
            },
            onDismiss = { showSwitchDialog = false },
        )
    }

    if (showCreateDialog) {
        NameInputDialog(
            title = stringResource(R.string.account_dialog_new),
            initial = "",
            onConfirm = { raw ->
                val name = raw.trim()
                if (name.isNotEmpty() && prof.items.any { it.name == name }) {
                    toast(context, context.getString(R.string.account_toast_duplicate_name))
                } else {
                    val created = store.create(name)
                    toast(context, context.getString(R.string.account_toast_created, created.name))
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    if (showRenameDialog) {
        NameInputDialog(
            title = stringResource(R.string.account_dialog_rename),
            initial = activeName,
            onConfirm = { raw ->
                val name = raw.trim()
                if (name.isNotEmpty()) {
                    store.rename(prof.activeId, name)
                    toast(context, context.getString(R.string.account_toast_renamed, name))
                }
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.account_dialog_delete)) },
            text = {
                Text(
                    text = stringResource(R.string.account_delete_warning, activeName),
                    color = ColorWarpedRed,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val archived = store.delete(prof.activeId)
                    toast(
                        context,
                        context.getString(
                            if (archived != null) R.string.account_toast_deleted
                            else R.string.account_toast_delete_last_refused,
                        ),
                    )
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.account_action_confirm), color = ColorWarpedRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.account_action_cancel))
                }
            },
        )
    }

    // —— 清空当前账号历史（对齐 PC `clear_records`：二次确认 → 自动备份 → 清空） ——
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.account_clear_history)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.account_clear_warning,
                        activeName,
                        HistoryStore.MAX_BACKUPS,
                    ),
                    color = ColorWarpedRed,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    runCatching { historyStore.clear() }.fold(
                        onSuccess = { backup ->
                            // 本场会话记录一并清掉：停抓时已 merge 落库，此处清的是展示层残留
                            GachaVpnService.resetCapturedRecords()
                            HistoryEpoch.bump()
                            toast(
                                context,
                                context.getString(
                                    if (backup != null) R.string.account_toast_cleared_backed_up
                                    else R.string.account_toast_cleared,
                                ),
                            )
                        },
                        onFailure = {
                            toast(context, context.getString(R.string.account_toast_clear_failed))
                        },
                    )
                }) { Text(stringResource(R.string.account_action_confirm), color = ColorWarpedRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.account_action_cancel))
                }
            },
        )
    }

    // —— 还原抽卡数据 ①：选还原点（对齐 PC `_restore_data` 的 QInputDialog） ——
    restoreList?.let { list ->
        AlertDialog(
            onDismissRequest = { restoreList = null },
            title = { Text(stringResource(R.string.account_restore_dialog_title)) },
            text = {
                if (list.isEmpty()) {
                    Text(text = stringResource(R.string.account_restore_empty), fontSize = 14.sp)
                } else {
                    Column {
                        for (b in list) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        restoreList = null
                                        pendingRestore = b
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = "○", color = colors.onSurfaceDim)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(text = b.label, fontSize = 14.sp, color = colors.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { restoreList = null }) {
                    Text(stringResource(R.string.account_action_close))
                }
            },
        )
    }

    // —— 还原抽卡数据 ②：二次确认（覆盖当前历史不可撤销） ——
    pendingRestore?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.account_restore_history)) },
            text = {
                Text(
                    text = stringResource(R.string.account_restore_warning, target.label),
                    color = ColorWarpedRed,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    runCatching { historyStore.restore(target.file) }.fold(
                        onSuccess = {
                            GachaVpnService.resetCapturedRecords()
                            HistoryEpoch.bump()
                            toast(
                                context,
                                context.getString(R.string.account_toast_restored, target.label),
                            )
                        },
                        onFailure = {
                            toast(
                                context,
                                context.getString(R.string.account_toast_restore_failed),
                            )
                        },
                    )
                }) { Text(stringResource(R.string.account_action_restore), color = ColorWarpedRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.account_action_cancel))
                }
            },
        )
    }
}

// —— 弹窗 ——

/** 账号切换弹窗：单选列表（M3 AlertDialog；比下拉更省心，无展开态管理）。 */
@Composable
private fun SwitchAccountDialog(
    items: List<Profile>,
    activeId: String,
    colors: JinlinColors,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_dialog_switch)) },
        text = {
            Column {
                for (p in items) {
                    val selected = p.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(p.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selected) "●" else "○",
                            color = if (selected) colors.gold else colors.onSurfaceDim,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = p.name,
                            fontSize = 14.sp,
                            color = if (selected) colors.gold else colors.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.account_action_close))
            }
        },
    )
}

/** 名称输入弹窗（新建 / 重命名共用）。 */
@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.account_name_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.account_action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.account_action_cancel))
            }
        },
    )
}

// —— 辅助 ——

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
