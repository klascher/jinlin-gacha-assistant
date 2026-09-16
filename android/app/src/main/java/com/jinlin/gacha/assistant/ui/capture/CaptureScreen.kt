package com.jinlin.gacha.assistant.ui.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.DedupSnapshot
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.UnknownIdStore
import com.jinlin.gacha.assistant.ui.stats.StatsReport
import com.jinlin.gacha.assistant.ui.stats.statsReport
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.ColorUpGreen
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * 抓包主页 = 顶栏抓包控制 + 保底总览骨架（08-页面设计.md §3 / §4）。
 *
 * 2.1 期：顶栏接线完成（状态订阅 + 启停/录包 + 权限四门，替代阶段 1 的 Handler 轮询）。
 * 2026-09-14 补：控制区下方加**常驻提示**（B8「仅采纳全部卡池」），文案与 PC 逐字一致（08 §4.2）。
 * 保底总览依赖阶段 2 冻结统计（core/locator 的 Kotlin 平移），尚未接入，此期先渲染
 * 「待统计接入」空态占位；接入后此块替换为 §4 的分组/保底/陌生卡池指派。
 */
@Composable
fun CaptureScreen() {
    val context = LocalContext.current
    val colors = LocalJinlinColors.current

    val svc by GachaVpnService.state.collectAsState()
    val running = svc.running
    val recording = svc.recording
    val gameInstalled = remember { isTargetGameInstalled(context) }
    // 当前账号：设置页切换后此处即时联动（账号真相源 = ProfileStore）
    val prof by ProfileStore.get(context).state.collectAsState()
    val accountName = prof.items.firstOrNull { it.id == prof.activeId }?.name

    // —— 实时抓取反馈数据源：去重快照 + 本次会话新收记录（翻两页回来即可确认有没有在收录）——
    val dedup by GachaVpnService.dedupState.collectAsState()
    val records by GachaVpnService.records.collectAsState()
    var elapsedSeconds by remember { mutableStateOf(0) }
    // 抓包期间每秒计时，让用户知道「已经翻了多久 / 是否确实在收」（跑在 UI 协程，翻页在游戏里进行）。
    // 用单调时钟差衡量，避免用户在游戏里（App 在后台，Compose 协程可能被节流）导致自增偏慢——
    // 回到 App 时一次 tick 即刷新到真实耗时。停止时协程取消、归零。
    var startRealtime by remember { mutableStateOf(0L) }
    var nowRealtime by remember { mutableStateOf(0L) }
    LaunchedEffect(running) {
        if (running) {
            startRealtime = SystemClock.elapsedRealtime()
            nowRealtime = startRealtime
            while (true) {
                delay(1000)
                nowRealtime = SystemClock.elapsedRealtime()
            }
        }
    }
    elapsedSeconds = if (running) ((nowRealtime - startRealtime) / 1000L).toInt() else 0

    // 统计报表（保底总览 + 未映射 ID 横幅共用一份）：读盘 + 内存快照，账号/未映射状态变更即重算。
    // 提升到顶部一次计算，避免 `PityOverview` 与横幅各自算一遍。
    val report = statsReport(context)
    var showUnknownDialog by remember { mutableStateOf(false) }

    // —— 权限四门（设计 §3.1）：已装游戏 → POST_NOTIFICATIONS(33+) → VpnService.prepare() → ACTION_START ——
    // 注意声明顺序：notifLauncher 的 lambda 引用了 vpnLauncher，须先声明 vpnLauncher（Kotlin 禁止局部前向引用）。
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) doStart(context)
        else Toast.makeText(context, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> proceedVpn(context, vpnLauncher) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // —— 顶栏：标题 + 当前账号 + 状态徽标 ——
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "抓包",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (accountName != null) {
                Text(text = accountName, fontSize = 12.sp, color = colors.onSurfaceMuted)
            }
        }
        StatusBadge(running = running, recording = recording, gameInstalled = gameInstalled)

        // —— 顶栏：启停 + 录包 ——
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !running && gameInstalled,
                onClick = { ensureStart(context, notifLauncher, vpnLauncher) },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (running) "抓包中" else "开始抓包")
            }
            OutlinedButton(
                enabled = running,
                onClick = { sendService(context, GachaVpnService.ACTION_STOP) },
                modifier = Modifier.weight(1f),
            ) {
                Text("停止")
            }
        }
        // 端点行占位：候选/已确认端点展示随统计接入（「录包」按钮已移除，见口述隐藏清单）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("候选端点", fontSize = 11.sp, color = colors.onSurfaceMuted)
                Text(endPointHint(running), fontSize = 13.sp, color = colors.onSurfaceDim)
            }
        }

        // —— 常驻提示（08 §4.2）：控制区正下方、点「开始抓包」前就能看到。
        // 刻意压成浅灰小字且**不可点**（对齐 PC gui/main_window.py:269-276 的 capture_note_label，
        // 其注释明确「避免误认作可点操作区」）。文案与 PC 逐字一致，说明 B8：只采纳「全部卡池」。
        Text(
            text = stringResource(R.string.capture_note_all_pools),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = colors.onSurfaceMuted,
        )

        HorizontalDivider(color = colors.divider)

        // —— 实时抓取反馈（仅抓包期间）：翻两页回到 App 即可确认「有没有在收到/是否翻错视图」，
        // 防止白翻很久。它只做收录反馈，不算最终统计 ——「停止后一次性统计」仍以此为准。
        if (running) {
            LiveCaptureFeedback(
                dedup = dedup,
                recordCount = records.size,
                elapsedSeconds = elapsedSeconds,
                colors = colors,
            )
        }

        // —— 保底总览（阶段 2 统计接入；口径=停止后一次性、取账号全量历史）——
        PityOverview(report)

        // —— 未映射 ID（待办 70③，2026-09-16 用户拍板：陌生卡池 + 陌生角色都做 + 指派/屏蔽逻辑）——
        // 仅 `pendingCount > 0` 才出现（08 §4.4「N>0 才出现」）；N = 未屏蔽角色 + 未指派池，
        // 全处理完横幅自动消失。点击 → 指派弹窗（屏蔽降星 / 并组共享保底）。
        if (report.pendingCount > 0) {
            UnknownIdBanner(pendingCount = report.pendingCount, onOpen = { showUnknownDialog = true })
        }
    }

    if (showUnknownDialog) {
        UnknownIdDialog(report = report, onDismiss = { showUnknownDialog = false })
    }
}

/** 状态徽标（对齐设计 §3 状态机表）：录制中(红) > 抓包中(绿) > 未抓包(灰) > 游戏未装(红)。 */
@Composable
private fun StatusBadge(running: Boolean, recording: Boolean, gameInstalled: Boolean) {
    val colors = LocalJinlinColors.current
    val (label, dot) = when {
        recording -> "录包中" to ColorWarpedRed
        running -> "抓包中" to ColorUpGreen
        !gameInstalled -> "目标游戏未安装" to ColorWarpedRed
        else -> "未抓包" to colors.onSurfaceMuted
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier.size(10.dp),
                shape = RoundedCornerShape(50),
                colors = CardDefaults.cardColors(containerColor = dot),
            ) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, fontSize = 15.sp, color = colors.onSurface)
        }
    }
}

@Composable
private fun LiveCaptureFeedback(
    dedup: DedupSnapshot,
    recordCount: Int,
    elapsedSeconds: Int,
    colors: JinlinColors,
) {
    // parseCount>0 表示正在读到数据（可能是历史重复）；recordCount=0 仅表示「本场还没新增」，
    // 不一定是没抓到。区分两种 0：
    //  - parsedCount==0：一条都没读到 → 真·没抓到（视图错 / 工具异常），红色提醒；
    //  - parsedCount>0 且 recordCount==0：读到了但全是历史已有 → 正常，翻到新数据页才新增。
    val adopting = dedup.allPoolsSeen
    val reading = dedup.parsedCount > 0
    val warn = !adopting || !reading
    val dot = if (warn) ColorWarpedRed else ColorUpGreen

    val statusText = when {
        !adopting && dedup.droppedPages > 0 -> "在非「全部卡池」视图翻，数据会被丢弃"
        !adopting -> "等待识别「全部卡池」视图"
        !reading -> "已识别全部卡池，但尚未读到记录"
        recordCount == 0 -> "正在读取（均为历史已有，翻到新数据页才会新增）"
        else -> "正在收录"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "实时抓取",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "已持续 ${formatElapsed(elapsedSeconds)}",
                    fontSize = 11.sp,
                    color = colors.onSurfaceMuted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(dot, RoundedCornerShape(50)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "已收 $recordCount 条（本次读取 ${dedup.parsedCount} 条） · $statusText",
                    fontSize = 12.sp,
                    color = colors.onSurface,
                )
            }
            if (warn) {
                Text(
                    text = when {
                        !adopting -> "请切到『全部卡池』列表后再逐页翻（其它视图的数据不会收录）"
                        !reading -> "尚未读到任何记录——若在全部卡池翻页仍为 0，请检查抓包是否正常"
                        else -> ""
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = ColorWarpedRed,
                )
            }
            // 已翻到历史边界（dedup.overlapped）时不报「还差N页」：此刻缺的都是已落库旧历史页，
            // 再翻到底也不会产生新数据（2026-09-16 待办 5 反馈「翻到历史了就不说没翻到了」）。
            if (dedup.missingPageCount > 0 && !dedup.overlapped) {
                Text(
                    text = "「全部卡池」还差 ${dedup.missingPageCount} 页没翻到，继续翻到底更完整。",
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                )
            }
        }
    }
}

private fun endPointHint(running: Boolean): String {
    return if (running) "抓包中…实时统计待接入" else "启动后随翻页填充"
}

/** 秒 → "x分y秒" 展示。 */
private fun formatElapsed(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}

// —— 未映射 ID（待办 70③）——

/**
 * 「未映射 ID」横幅（08 §4.4）：金色警示条，仅 `pendingCount>0` 时由调用方渲染；
 * 点击 → 指派弹窗。[pendingCount] 口径 = 未屏蔽角色 + 未指派池（全处理完横幅消失）。
 */
@Composable
private fun UnknownIdBanner(pendingCount: Int, onOpen: () -> Unit) {
    val colors = LocalJinlinColors.current
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "⚠", fontSize = 16.sp, color = colors.gold)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "有 $pendingCount 个未映射 ID · 指派 ›",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 指派弹窗：分「未收录角色（屏蔽降星）」与「未收录卡池（并组共享保底）」两区。
 *
 * 状态写 `UnknownIdStore`，跨账号全局持久化；报告在 [report] 内 collect 该 Store，
 * 故每次屏蔽/指派后 [report] 重算 → 本弹窗与抓包页横幅即时刷新（含 `pendingCount`）。
 * 列表用 [report.unmappedIds] / [report.unmappedPoolIds] **全量**（含已处理的，便于改/撤）。
 */
@Composable
private fun UnknownIdDialog(report: StatsReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { UnknownIdStore.get(context) }
    val colors = LocalJinlinColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未映射 ID", color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (report.unmappedIds.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "未收录角色（${report.unmappedIds.size}）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceMuted,
                        )
                        report.unmappedIds.forEach { (id, count) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "未知($id)", fontSize = 13.sp, color = colors.onSurface)
                                    Text(text = "出现 $count 次", fontSize = 11.sp, color = colors.onSurfaceMuted)
                                }
                                TextButton(onClick = { store.toggleShield(id) }) {
                                    Text(
                                        text = if (report.shielded.contains(id)) "取消屏蔽" else "屏蔽降星",
                                        fontSize = 12.sp,
                                        color = if (report.shielded.contains(id)) colors.onSurfaceDim else colors.gold,
                                    )
                                }
                            }
                        }
                    }
                }
                if (report.unmappedPoolIds.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "未收录卡池（${report.unmappedPoolIds.size}）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurfaceMuted,
                        )
                        report.unmappedPoolIds.forEach { (pid, count) ->
                            PoolAssignRow(pid, count, report, store, colors)
                        }
                        Text(
                            text = "新卡池通常属遴选/UP，并入对应保底组后与组内其他池共享保底；" +
                                "也可保留「独立」。元数据收录该池后真实配置自动覆盖。",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
                if (report.unmappedIds.isEmpty() && report.unmappedPoolIds.isEmpty()) {
                    Text("没有未映射 ID，都已处理。", fontSize = 13.sp, color = colors.onSurfaceMuted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = colors.gold) }
        },
    )
}

/** 单个未收录卡池行的指派选择：当前组 → 独立 / 各 hasState 共享组（选即 `store.setPoolAssignment`）。 */
@Composable
private fun PoolAssignRow(
    pid: String,
    count: Int,
    report: StatsReport,
    store: UnknownIdStore,
    colors: JinlinColors,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = report.poolAssignments[pid]
    val currentLabel = report.assignGroups.firstOrNull { it.id == current }?.label ?: "独立"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "未知($pid)", fontSize = 13.sp, color = colors.onSurface)
            Text(text = "出现 $count 次", fontSize = 11.sp, color = colors.onSurfaceMuted)
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = if (current == null) "指派 ›" else "组：$currentLabel",
                    fontSize = 12.sp,
                    color = if (current == null) colors.gold else colors.onSurface,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (current == null) "✓ 独立（不并组）" else "独立（不并组）") },
                    onClick = {
                        store.setPoolAssignment(pid, null)
                        expanded = false
                    },
                )
                report.assignGroups.forEach { g ->
                    DropdownMenuItem(
                        text = { Text(if (g.id == current) "✓ ${g.label}" else g.label) },
                        onClick = {
                            store.setPoolAssignment(pid, g.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// —— 启动流 ——

private fun isTargetGameInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getApplicationInfo(
        GachaVpnService.TARGET_PACKAGE, PackageManager.GET_META_DATA,
    )
}.isSuccess

private fun ensureStart(
    context: Context,
    notifLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    // 门 1：游戏已安装
    if (!isTargetGameInstalled(context)) {
        Toast.makeText(context, R.string.game_missing, Toast.LENGTH_SHORT).show()
        return
    }
    // 门 2：通知权限（API 33+ 前台服务通知）
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) {
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return
    }
    proceedVpn(context, vpnLauncher)
}

private fun proceedVpn(context: Context, vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>) {
    // 门 3：VPN 授权（未授权返回待确认 Intent）
    val prepare = VpnService.prepare(context)
    if (prepare != null) {
        vpnLauncher.launch(prepare)
        return
    }
    doStart(context)
}

private fun doStart(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, GachaVpnService::class.java).setAction(GachaVpnService.ACTION_START),
    )
}

private fun sendService(context: Context, action: String) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, GachaVpnService::class.java).setAction(action),
    )
}