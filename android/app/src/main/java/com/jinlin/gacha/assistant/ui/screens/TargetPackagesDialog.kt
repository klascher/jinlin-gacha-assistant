package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.TargetPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「接管范围」弹窗（设置页 → 抓包组）—— 列出本机检出的**目标游戏候选**，供用户**单选**。
 *
 * ### 为什么需要它（2026-09-18）
 * 官服是 `com.bmystu.peng.gw`，渠道服包名不同（用户实测 OPPO
 * `com.bmystu.peng1.nearme.gamecenter`）。用户实测反馈「渠道服无法获取到包信息」——
 * 根因是包名写死 + `<queries>` 只声明一个包。规则放宽（见 [TargetPackages]）之后，
 * 还需**让用户看得见、选得了**：自动检出只是猜测，命中几个、命中哪几个必须可见，
 * 否则出错时无从判断。
 *
 * ### ⚠️ 单选（2026-09-18 晚由多选改回）
 * 多选会让两个渠道的流量**同时进 tun**，而整场抓包只有**一套**视图识别表
 * （`core/dedup/ViewTracker` 的 `seq % 127` 配对表是全局单例）⇒ 两个游戏各自独立的
 * seq 空间会撞进同一张表、响应互相错配。且记录不按渠道隔离。「当前在玩哪个渠道」
 * 才是正确语义 ⇒ 本弹窗一次只能选一个，换渠道时回来改选。
 *
 * ### 交互契约
 * - **打开时才扫描**（`LaunchedEffect` + `Dispatchers.IO`）：`queryIntentActivities` 是 IPC，
 *   不放在组合期做；列表**不跨次缓存** —— 用户装完渠道服再打开即为最新；
 * - **已选但未被检出**的项也照列（如无启动器活动的渠道包）—— 否则用户无法取消它，
 *   会留下一个看不到却仍在生效的包；
 * - **预选规则对齐 `TargetPackages.decide`**：已配置过 ⇒ 预选它；未配置过时**只有恰好检出
 *   1 个才预选**（等价于会自动生效的那个），检出多个则**不预选**，逼用户自己指明；
 * - **抓包中不可改**：入口行已置灰，但抓包可**从通知栏起停**，故此处仍订阅状态，
 *   翻转为 true 时当场关闭（与 [DataCleanupScreen] 同一约定，不用「打开时取一次」）；
 * - **拒绝空选**：`picked == null` 时保存按钮禁用（设置页保存时至少留一个）。
 *
 * @param selected 当前持久化的选定（`SettingsStore.targetPackage`；null = 未配置过）。
 * @param running 抓包服务是否在跑（状态**跟随**，非打开时取样）。
 * @param onConfirm 保存回调，参数为选中的包名。
 */
@Composable
internal fun TargetPackagesDialog(
    colors: JinlinColors,
    selected: String?,
    running: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<TargetPackages.Candidate>>(emptyList()) }
    var picked by remember { mutableStateOf(selected) }

    LaunchedEffect(Unit) {
        val detected = withContext(Dispatchers.IO) { TargetPackages.detect(context) }
        // 已选却没被检出的项补成一行（label 回落为包名），否则用户无法取消它。
        val missing = listOfNotNull(selected)
            .filter { s -> detected.none { it.packageName == s } }
            .map { TargetPackages.Candidate(packageName = it, label = it) }
        rows = detected + missing
        // 预选：已配置过用配置值；否则**仅当恰好检出 1 个**才预选（与 decide 的自动采用口径一致）。
        if (picked == null && detected.size == 1) picked = detected.first().packageName
        loading = false
    }

    // 抓包中整条链路不可改：状态翻转（通知栏开抓）时当场关闭。
    LaunchedEffect(running) { if (running) onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.target_packages_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.target_packages_hint),
                    fontSize = 12.sp,
                    color = colors.onSurfaceDim,
                )
                Spacer(modifier = Modifier.height(10.dp))
                when {
                    loading -> Text(
                        text = stringResource(R.string.target_packages_scanning),
                        fontSize = 13.sp,
                        color = colors.onSurfaceDim,
                    )

                    rows.isEmpty() -> Text(
                        text = stringResource(R.string.target_packages_empty),
                        fontSize = 13.sp,
                        color = colors.onSurfaceDim,
                    )

                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(rows, key = { it.packageName }) { row ->
                            val checked = row.packageName == picked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 单选：点哪一行就只选那一行（再点已选行不取消 —— 取消由「取消」按钮承担）
                                    .clickable { picked = row.packageName }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = if (checked) "◉" else "○",
                                    color = if (checked) colors.gold else colors.onSurfaceDim,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = row.label,
                                        fontSize = 14.sp,
                                        color = if (checked) colors.gold else colors.onSurface,
                                    )
                                    // 包名照实展示：多个渠道服的显示名常一字不差（都叫「夜幕之下」），
                                    // 只有包名能区分 —— 用户报告渠道服问题时给的也正是包名。
                                    Text(
                                        text = row.packageName,
                                        fontSize = 11.sp,
                                        color = colors.onSurfaceDim,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { picked?.let(onConfirm) },
                enabled = picked != null,
            ) {
                Text(
                    text = stringResource(R.string.target_packages_save),
                    color = if (picked == null) colors.onSurfaceMuted else colors.gold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.account_action_cancel))
            }
        },
    )
}
