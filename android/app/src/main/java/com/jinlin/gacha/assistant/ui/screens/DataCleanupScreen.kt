package com.jinlin.gacha.assistant.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.persistence.CleanCategory
import com.jinlin.gacha.assistant.persistence.CleanItem
import com.jinlin.gacha.assistant.persistence.CleanKind
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.StorageCleaner
import com.jinlin.gacha.assistant.persistence.formatBytes
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 默认不展示的分类（**唯一开关**）。
 *
 * **2026-09-18 用户定案**：pcap **永远不对用户产生**（录包能力只保留给开发 / 测试期，
 * 见 `mobile/docs/14-Android端待办.md` §2.3 D1）⇒「录包文件」这类对用户**长期为空**，
 * 列出来只会引出「这是什么 / 为什么是 0」。用户原话：
 * 「*我觉得录包文件这个，直接隐藏吧，我开发要用的时候再手动取消注释展示出来*」。
 *
 * **开发期要清录包产物** ⇒ 把下面集合里 `CleanKind.CAPTURE_PCAP` 那一行**注释掉**即可
 * 恢复展示（无需改任何其它代码）。
 *
 * ⚠️ **这是展示层开关，不是功能删减**：数据层 `StorageCleaner` 仍扫**五类**（2026-09-22 新增 App 日志）、三闸守卫不变，
 * `StorageCleanerTest` 的 5 例（含四类扫描）也不变；`CleanKind.CAPTURE_PCAP` 的枚举分支
 * （[kindLabelRes] / [itemTitle]）**照留** —— 删了 `when` 不穷尽、恢复时还要重写。
 */
private val HIDDEN_KINDS: Set<CleanKind> = setOf(
    CleanKind.CAPTURE_PCAP, // ← 开发期要用：注释掉本行，子页立刻恢复显示「录包文件」类
)

/**
 * **数据清理子页**（设置 → 数据维护 → 清理本地数据）。
 *
 * 落地待办 **U4**（原「设置页 S4 · 数据维护」），并把 2026-09-17 用户提出的「备份删除」并入
 * —— 删除入口从「还原点列表行内」移到此处，**还原对话框保持纯洁性不动**（只做还原）。
 * 设计依据 `mobile/docs/09-设置模块设计.md` §3.5 / §15。
 *
 * ### 「录包文件」类**默认隐藏**（2026-09-18 用户定案）
 * pcap **永远不对用户产生**（录包能力只留开发 / 测试期，见 `14-Android端待办.md` §2.3 D1）
 * ⇒ 该类对用户**长期为空**，列出来只会引出「这是什么 / 为什么是 0」的疑问。
 * 由 [HIDDEN_KINDS] 控制：**开发期要清录包产物时，把集合里那一行注释掉即恢复**。
 *
 * ### 边界（与数据层同一份不变式，见 [StorageCleaner] 类注释）
 * 本页**只碰副本与诊断产物**；抽卡记录本体、账号注册表、设置等权威数据**永不在此页删**。
 * 记录本体只在**统计页 → 用户管理 → 清空当前账号历史**（账号维度、有备份、可还原）。
 *
 * ### 抓包中：**整页不可用**（2026-09-17 用户裁决）
 * 入口行已置灰（见 `SettingsScreen`），但**本页停留期间状态仍可能翻转** —— 抓包的启停不需要回到
 * 本页，**通知栏就能开停**（VPN 服务）。所以这里订阅 [GachaVpnService.state] **跟随**：
 * `running` 一翻转，立刻关闭已打开的弹窗 + 所有操作置灰。**不用「进入时取一次」** ——
 * 那会留下「进页时未抓包 → 停在页面 → 通知栏开抓包 → 回来点删除」的误删窗口。
 *
 * 出于同一考虑，真正执行删除前还会再读一次 [GachaVpnService.isRunning]（数据层兜底）——
 * UI 置灰挡不住「点确认的瞬间恰好开抓包」。
 */
@Composable
internal fun DataCleanupScreen(colors: JinlinColors, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prof by ProfileStore.get(context).state.collectAsState()
    val running = GachaVpnService.state.collectAsState().value.running

    val cleaner = remember { StorageCleaner(context.filesDir, context.getExternalFilesDir(null)) }
    val names = remember(prof.items) { prof.items.associate { it.id to it.name } }

    var categories by remember { mutableStateOf<List<CleanCategory>?>(null) }
    var scanToken by remember { mutableStateOf(0) }
    /** 打开中的分类弹窗（null = 未打开）。 */
    var openKind by remember { mutableStateOf<CleanKind?>(null) }
    /** 待确认删除的项（null = 未在确认中）。 */
    var pending by remember { mutableStateOf<List<CleanItem>?>(null) }
    /** 多选集（以 `absolutePath` 为键）。 */
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 抓包中：关掉所有弹窗，整页转只读（见类注释）
    LaunchedEffect(running) {
        if (running) {
            openKind = null
            pending = null
        }
    }
    // 返回键：弹窗打开时交给 Dialog 自己消费，否则退出子页
    BackHandler(enabled = openKind == null && pending == null) { onBack() }

    // 扫描（IO）；扫描范围 = 四个分类目录，空目录自然返回空分类
    LaunchedEffect(scanToken, names) {
        val all = withContext(Dispatchers.IO) { cleaner.scan(names) }
        // 过滤放在**扫描处**、不在渲染处：否则隐藏类仍计入上面的汇总「可清理 共 N 项 · X」，
        // 会出现「汇总里有这个数、列表里却找不到对应行」的错位。开关见 [HIDDEN_KINDS]。
        categories = all.filterNot { it.kind in HIDDEN_KINDS }
    }

    val cats = categories
    val totalBytes = cats?.sumOf { it.bytes } ?: 0L
    val totalCount = cats?.sumOf { it.count } ?: 0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // —— 页内返回 + 标题 ——
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBack() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "‹", fontSize = 22.sp, color = colors.gold)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.clean_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
            }
        }

        // —— 汇总（只读）——
        item {
            SectionCard(colors) {
                InfoRow(
                    label = stringResource(R.string.clean_summary_label),
                    value = if (cats == null) {
                        stringResource(R.string.clean_scanning)
                    } else {
                        stringResource(R.string.clean_summary_value, totalCount, formatBytes(totalBytes))
                    },
                    colors = colors,
                )
                RowDivider(colors)
                Text(
                    text = stringResource(R.string.clean_boundary_note),
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // —— 抓包中横幅（整页转只读的原因，说明白）——
        if (running) {
            item {
                Text(
                    text = stringResource(R.string.clean_need_stop_banner),
                    fontSize = 13.sp,
                    color = ColorWarpedRed,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // —— 分类列表 ——
        item { GroupHeader(stringResource(R.string.clean_section_kinds), colors) }
        item {
            SectionCard(colors) {
                val list = cats ?: emptyList()
                list.forEachIndexed { i, cat ->
                    if (i > 0) RowDivider(colors)
                    val empty = cat.isEmpty
                    ActionRow(
                        label = stringResource(kindLabelRes(cat.kind)),
                        trailing = when {
                            running -> stringResource(R.string.account_need_stop)
                            empty -> stringResource(R.string.clean_kind_empty)
                            else -> stringResource(R.string.clean_kind_summary, cat.count, formatBytes(cat.bytes))
                        },
                        enabled = !empty && !running,
                        colors = colors,
                        onClick = {
                            selected = emptySet()
                            openKind = cat.kind
                        },
                    )
                }
            }
        }

        // —— 页脚说明（上限数字取自 HistoryStore 常量，避免两处口径漂移）——
        item {
            Text(
                text = stringResource(
                    R.string.clean_footer_note,
                    HistoryStore.MAX_BACKUPS,
                    formatBytes(HistoryStore.BUDGET_BYTES),
                ),
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    // —— ① 多选列表弹窗 ——
    val openCat = openKind?.let { kind -> cats?.firstOrNull { it.kind == kind } }
    if (openCat != null) {
        MultiSelectDialog(
            cat = openCat,
            selected = selected,
            colors = colors,
            onToggle = { path ->
                selected = if (path in selected) selected - path else selected + path
            },
            onToggleAll = {
                selected = if (selected.size == openCat.count) {
                    emptySet()
                } else {
                    openCat.items.map { it.file.absolutePath }.toSet()
                }
            },
            onConfirm = {
                pending = openCat.items.filter { it.file.absolutePath in selected }
                openKind = null
            },
            onDismiss = { openKind = null },
        )
    }

    // —— ② 二次确认 ——
    pending?.let { targets ->
        val kind = targets.firstOrNull()?.kind
        val bytes = targets.sumOf { it.bytes }
        val isBackup = kind == CleanKind.BACKUP
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.clean_confirm_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            if (isBackup) R.string.clean_confirm_body_backup else R.string.clean_confirm_body_other,
                            targets.size,
                            formatBytes(bytes),
                        ),
                        color = ColorWarpedRed,
                        fontSize = 14.sp,
                    )
                    if (kind == CleanKind.META_CACHE) {
                        Text(
                            text = stringResource(R.string.clean_confirm_cache_note),
                            fontSize = 12.sp,
                            color = colors.onSurfaceMuted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    // 数据层兜底：UI 置灰挡不住「点确认的这一瞬间恰好开了抓包」
                    if (GachaVpnService.isRunning()) {
                        toast(context, context.getString(R.string.clean_toast_still_running))
                        return@TextButton
                    }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { cleaner.delete(targets) }
                        toast(
                            context,
                            if (result.failed == 0) {
                                context.getString(
                                    R.string.clean_toast_done,
                                    result.deleted,
                                    formatBytes(result.freed),
                                )
                            } else {
                                context.getString(
                                    R.string.clean_toast_partial,
                                    result.deleted,
                                    formatBytes(result.freed),
                                    result.failed,
                                )
                            },
                        )
                        scanToken++ // 删完重扫（含汇总数字），不只改内存
                    }
                }) { Text(stringResource(R.string.clean_action_delete), color = ColorWarpedRed) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.account_action_cancel))
                }
            },
        )
    }
}

/**
 * 多选列表弹窗 —— 四类**共用一套**（缓存类恰好 1 项，行为一致：可勾可选可删）。
 *
 * 列表用 [LazyColumn] + `heightIn(max = 360.dp)`：30 份备份在手机上必然溢出，必须能滚。
 */
@Composable
private fun MultiSelectDialog(
    cat: CleanCategory,
    selected: Set<String>,
    colors: JinlinColors,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allSelected = selected.size == cat.count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(kindLabelRes(cat.kind))) },
        text = {
            Column {
                // 全选行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleAll() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (allSelected) "☑" else "☐",
                        color = if (allSelected) colors.gold else colors.onSurfaceDim,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.clean_select_all, selected.size, cat.count),
                        fontSize = 14.sp,
                        color = colors.onSurface,
                    )
                }
                RowDivider(colors)
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(cat.items, key = { it.file.absolutePath }) { item ->
                        val path = item.file.absolutePath
                        val checked = path in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(path) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = if (checked) "☑" else "☐",
                                color = if (checked) colors.gold else colors.onSurfaceDim,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = itemTitle(item),
                                    fontSize = 14.sp,
                                    color = if (checked) colors.gold else colors.onSurface,
                                )
                                Text(
                                    text = itemDetail(item),
                                    fontSize = 12.sp,
                                    color = colors.onSurfaceDim,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selected.isNotEmpty(),
            ) {
                Text(
                    text = stringResource(R.string.clean_action_delete_n, selected.size),
                    color = if (selected.isEmpty()) colors.onSurfaceMuted else ColorWarpedRed,
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

// —— 展示辅助 ——

private val ITEM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun kindLabelRes(kind: CleanKind): Int = when (kind) {
    CleanKind.BACKUP -> R.string.clean_kind_backup
    CleanKind.CAPTURE_PCAP -> R.string.clean_kind_records
    CleanKind.DIAG_PCAP -> R.string.clean_kind_diagnose
    CleanKind.META_CACHE -> R.string.clean_kind_cache
    CleanKind.APP_LOG -> R.string.clean_kind_applog
}

/**
 * 每项主行：备份显「账号名 · 时间」（**跨账号治理，故必须标账号**）；pcap 显「原因 · 时间」；
 * 缓存显文件名。解析不出时间时回退文件名 —— 宁可难看，不可错标。
 */
private fun itemTitle(item: CleanItem): String = when (item.kind) {
    CleanKind.BACKUP -> joinNonBlank(item.accountName, item.timestamp?.format(ITEM_FMT))
    CleanKind.CAPTURE_PCAP -> item.timestamp?.format(ITEM_FMT) ?: item.file.name
    CleanKind.DIAG_PCAP -> joinNonBlank(item.reason, item.timestamp?.format(ITEM_FMT))
    CleanKind.META_CACHE, CleanKind.APP_LOG -> item.file.name
}

/** 每项副行：备份多带条数（其余只有体积）。 */
@Composable
private fun itemDetail(item: CleanItem): String {
    val size = formatBytes(item.bytes)
    return if (item.kind == CleanKind.BACKUP) {
        stringResource(R.string.clean_item_backup_detail, item.records ?: 0, size)
    } else {
        size
    }
}

private fun joinNonBlank(vararg parts: String?): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(" · ")
