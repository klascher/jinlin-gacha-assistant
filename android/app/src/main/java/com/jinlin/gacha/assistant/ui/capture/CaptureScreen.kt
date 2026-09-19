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
import com.jinlin.gacha.assistant.core.ChannelSwitchGuard
import com.jinlin.gacha.assistant.core.DedupSnapshot
import com.jinlin.gacha.assistant.core.HealthReason
import com.jinlin.gacha.assistant.core.HealthState
import com.jinlin.gacha.assistant.core.gapCoveredByHistory
import com.jinlin.gacha.assistant.core.health
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.SettingsStore
import com.jinlin.gacha.assistant.persistence.UnknownIdStore
import com.jinlin.gacha.assistant.ui.screens.healthFixLine
import com.jinlin.gacha.assistant.ui.screens.healthReasonLine
import com.jinlin.gacha.assistant.ui.screens.pagesCoveredLine
import com.jinlin.gacha.assistant.ui.screens.pageText
import com.jinlin.gacha.assistant.ui.screens.rememberOwnedSession
import com.jinlin.gacha.assistant.ui.screens.TargetPackagesDialog
import com.jinlin.gacha.assistant.ui.stats.StatsReport
import com.jinlin.gacha.assistant.ui.stats.statsReport
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.ColorUpGreen
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import com.jinlin.gacha.assistant.vpn.TargetPackages
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

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
    // 首次引导是否已读（U5 §17.7）：`guide_seen_version < GUIDE_VERSION` ⇒ 还没看过，展示三步骤卡。
    val settings by SettingsStore.get(context).state.collectAsState()
    // 门 1 判据（渠道服支持，2026-09-18）：三态 Resolution，见 TargetPackages。
    // `remember(pickRefresh)` 保持既有「进入本页取一次」语义 —— 切 Tab 会重建组合，故在设置页
    // 改完接管范围、切回来即刷新；本页选完渠道后 pickRefresh++ 也能就地刷新。
    var pickRefresh by remember { mutableStateOf(0) }
    val target = remember(pickRefresh) { TargetPackages.resolve(context) }
    val gameInstalled = target is TargetPackages.Resolution.Ready
    // 检出多个渠道而用户未选定时：不置灰，改为点按钮时引导选择（否则用户只看到「未安装」无从下手）
    val needPickChannel = target is TargetPackages.Resolution.NeedsChoice
    var showPickChannel by remember { mutableStateOf(false) }
    // 当前账号：设置页切换后此处即时联动（账号真相源 = ProfileStore）
    val prof by ProfileStore.get(context).state.collectAsState()
    val accountName = prof.items.firstOrNull { it.id == prof.activeId }?.name

    // —— 换渠道提醒（渠道服支持，2026-09-18）——
    // 记录**不按渠道隔离**：同一用户先抓官服、再切渠道服，两边的记录会混进同一份历史，
    // 保底/统计会串。[ChannelSwitchGuard] 只报「换成了一个没抓过的渠道」这一种情况
    // （同渠道 / 新用户 / 渠道未定 / 早混过，都不报）。
    // 读历史是 I/O ⇒ **预读成快照**（IO 线程，随渠道/用户/运行态重算），点击时只读快照。
    var channelWarnOthers by remember { mutableStateOf<List<String>>(emptyList()) }
    var showChannelWarn by remember { mutableStateOf(false) }
    LaunchedEffect(target, prof.activeId, running) {
        val readyPkg = (target as? TargetPackages.Resolution.Ready)?.packageName
        channelWarnOthers = withContext(Dispatchers.IO) {
            if (running || readyPkg == null) {
                emptyList()
            } else {
                runCatching {
                    val store =
                        HistoryStore(File(context.filesDir, ProfileStore.USERS_DIR), prof.activeId)
                    store.load()
                    ChannelSwitchGuard.evaluate(readyPkg, store.sourcePackages).otherChannels
                }.getOrDefault(emptyList())
            }
        }
    }

    // —— 实时抓取反馈数据源：去重快照 + 本次会话新收记录（翻两页回来即可确认有没有在收录）——
    // ⚠️ 走 `rememberOwnedSession` 而不是直接读 `GachaVpnService.*`：会话内存态是进程级的，
    // 必须校验**属于当前账号**，否则切换账号后会看到上一个账号的记录与完整性（2026-09-19 实机反馈）。
    val session = rememberOwnedSession(prof.activeId)
    val dedup = session.dedup
    val records = session.records
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
    // 停止确认弹窗（U5 §17.6）：**所有状态都弹**，按缺口分类换主按钮（见 StopConfirmDialog）。
    var showStopDialog by remember { mutableStateOf(false) }

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
        StatusBadge(
            running = running,
            recording = recording,
            gameInstalled = gameInstalled,
            needPickChannel = needPickChannel,
        )

        // —— 顶栏：启停 + 录包 ——
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                // needPickChannel 时**保持可点**：点了先让用户选渠道，而不是像「未安装」那样直接置灰
                enabled = !running && (gameInstalled || needPickChannel),
                onClick = {
                    when {
                        needPickChannel -> showPickChannel = true
                        !gameInstalled ->
                            Toast.makeText(context, R.string.game_missing, Toast.LENGTH_SHORT).show()
                        // 换渠道但没换用户：先确认一次 —— 真正把数据混在一起的是「开始抓包」这一刻
                        channelWarnOthers.isNotEmpty() -> showChannelWarn = true
                        else -> ensureStart(context, notifLauncher, vpnLauncher)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (running) "抓包中" else "开始抓包")
            }
            OutlinedButton(
                enabled = running,
                // 不再直发 ACTION_STOP：先过确认弹窗（U5 §17.6，用户要求「点停止要有提示」）。
                onClick = { showStopDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Text("停止")
            }
        }

        // —— 接管渠道（只读，2026-09-18）——
        // 渠道切换**只在设置页**（A 方案）：白名单在 establish() 时定型，跑着改会「以为换了其实没换」。
        // 这里只负责把「当前接管的是哪个渠道」亮出来 —— 多渠道的显示名常一字不差（都叫「夜幕之下」），
        // 不看渠道标识就分不清抓的是哪一个。
        Text(
            text = "接管渠道：" + when {
                running -> TargetPackages.channelLabel(
                    (target as? TargetPackages.Resolution.Ready)?.packageName.orEmpty(),
                )
                target is TargetPackages.Resolution.Ready ->
                    TargetPackages.channelLabel(target.packageName)
                needPickChannel -> "检测到多个渠道，点「开始抓包」选择一个"
                else -> "未检测到可接管的游戏"
            },
            fontSize = 12.sp,
            color = colors.onSurfaceDim,
        )

        // —— 首次引导卡（U5 §17.7「事前」层）：首次进抓包页、且还没开抓时给一遍正确顺序 ——
        // 只在未抓包时显示：「先点开始抓包」这句话在抓包中显示会自相矛盾。
        if (!running && settings.guideSeenVersion < GUIDE_VERSION) {
            FirstRunGuide(onDismiss = { SettingsStore.get(context).setGuideSeenVersion(GUIDE_VERSION) })
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

    if (showStopDialog) {
        StopConfirmDialog(
            dedup = dedup,
            recordCount = records.size,
            onDismiss = { showStopDialog = false },
            onStop = {
                showStopDialog = false
                sendService(context, GachaVpnService.ACTION_STOP)
            },
        )
    }

    // —— 接管范围选择（渠道服支持，2026-09-18）——
    // 检出**多个**渠道时由本页「开始抓包」唤起（`NeedsChoice`，见 startGate）：服务侧不能弹窗，
    // 若在那里静默挑一个，用户会「以为抓的是 A、其实抓的是 B」。**单选**。
    // 选完只落盘、不自动开抓 —— 与弹窗文案「改动在下次开始抓包时生效」一致（G2）。
    if (showPickChannel) {
        TargetPackagesDialog(
            colors = colors,
            selected = settings.targetPackage,
            running = running,
            onDismiss = { showPickChannel = false },
            onConfirm = { picked ->
                SettingsStore.get(context).setTargetPackage(picked)
                showPickChannel = false
                pickRefresh++ // 就地刷新 status 徽标 / 接管渠道行
            },
        )
    }

    // —— 换渠道确认（渠道服支持，2026-09-18，用户拍板）——
    // 措辞用「用户」不用「账号」：App 里读不到游戏账号（协议无账号标识），说「账号」会误导；
    // 且要**说清后果 + 给出动作**（只写「数据混淆」用户只会无脑点确认，对齐 U5 文案口径）。
    if (showChannelWarn) {
        AlertDialog(
            onDismissRequest = { showChannelWarn = false },
            title = { Text(stringResource(R.string.channel_switch_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.channel_switch_body,
                        accountName ?: "-",
                        channelWarnOthers.joinToString("、") { TargetPackages.channelLabel(it) },
                        (target as? TargetPackages.Resolution.Ready)?.packageName
                            ?.let { TargetPackages.channelLabel(it) } ?: "-",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showChannelWarn = false
                        ensureStart(context, notifLauncher, vpnLauncher)
                    },
                ) { Text(stringResource(R.string.channel_switch_proceed)) }
            },
            dismissButton = {
                TextButton(onClick = { showChannelWarn = false }) {
                    Text(stringResource(R.string.account_action_cancel))
                }
            },
        )
    }
}

/** 首次引导卡的版本号；引导文案改版时把它 +1，即可让老用户重新看一遍（§17.7）。 */
private const val GUIDE_VERSION = 1

/** 状态徽标（对齐设计 §3 状态机表）：录制中(红) > 抓包中(绿) > 未抓包(灰) > 游戏未装(红)。 */
@Composable
private fun StatusBadge(
    running: Boolean,
    recording: Boolean,
    gameInstalled: Boolean,
    needPickChannel: Boolean,
) {
    val colors = LocalJinlinColors.current
    val (label, dot) = when {
        recording -> "录包中" to ColorWarpedRed
        running -> "抓包中" to ColorUpGreen
        // 检出多个渠道、用户还没选：**不是**「未安装」（包都在），要的是让用户选一个
        needPickChannel -> "请选择要接管的渠道" to ColorWarpedRed
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

/**
 * 实时抓取反馈（仅抓包期间）：把「有没有在收 / 有没有缺口」实时告诉用户。
 *
 * 三态与成因**全部来自** `core/LiveHealth`（唯一判据来源）——本函数只做「状态 → 颜色」映射；
 * 文案走 [healthReasonLine] / [healthFixLine]，与记录页完整性卡、停止弹窗**共用同一套句子**，
 * 避免同一件事在三处出现三种说法。
 *
 * | 态 | 点色 | 含义 |
 * |---|---|---|
 * | 🟢 OK | 绿 | 在收数据、无已知缺口（含「重抓同一段、全被判重跳过」这种正常情况） |
 * | 🟡 PENDING | 金 | 缺口能靠继续翻补上（中段 / 尾部），或衔接无法校验 |
 * | 🔴 PROBLEM | 红 | 翻错列表 / 读不到数据 / **A 类首屏缺口** |
 * | ⚪ UNCOLLECTED | 灰红 | 本场几乎什么都没收到 ——「没有信号」不等于「数据完整」 |
 *
 * ⚠️ 不做 O(N) 计算：参与判定的字段都是 O(1) 增量值（见 `ViewTracker` 的增量计数），
 * 这样每次状态刷新才敢挂在热路径上。
 */
@Composable
private fun LiveCaptureFeedback(
    dedup: DedupSnapshot,
    recordCount: Int,
    elapsedSeconds: Int,
    colors: JinlinColors,
) {
    val health = dedup.health
    val dot = when (health.state) {
        HealthState.OK -> ColorUpGreen
        HealthState.PENDING -> colors.gold
        HealthState.PROBLEM, HealthState.UNCOLLECTED -> ColorWarpedRed
    }
    val reasonText = healthReasonLine(dedup)
    val fixText = healthFixLine(dedup)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.live_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.live_elapsed, formatElapsed(elapsedSeconds)),
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
                    text = reasonText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (health.state == HealthState.OK) colors.onSurface else dot,
                )
            }
            // 两个 0 要分开看：`parsedCount == 0` 才是「真没读到」，`recordCount == 0` 只表示
            // 「本场还没新增」（可能读到的全是历史已有 → 正常）。
            Text(
                text = stringResource(R.string.live_count, recordCount, dedup.parsedCount),
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
            )
            // 页覆盖：**已接上历史时不显示「已拿 N/M 页」那个比值**（2026-09-19 实机反馈）——
            // 用户把它读成「还差 M−N 页没拿完」，而那 M−N 页全在历史里（`gapCoveredByHistory`）。
            // ⚠️ 本处与记录页卡片、完整性子页是**三条独立渲染路径**：昨天只改了后者两处，
            // 抓包页就漏了 ⇒ 统一走 `pagesCoveredLine`（唯一实现），别再在本地拼字符串。
            if (dedup.gapExpectedPages > 0) {
                Text(
                    text = if (dedup.gapCoveredByHistory) {
                        pagesCoveredLine(dedup.gapSeenPages, dedup.gapExpectedPages)
                    } else {
                        stringResource(
                            R.string.live_pages, dedup.gapSeenPages, dedup.gapExpectedPages,
                        )
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                )
            }
            if (fixText != null) {
                Text(
                    text = fixText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (health.state == HealthState.PROBLEM) ColorWarpedRed else colors.gold,
                )
            }
        }
    }
}

// —— 首次引导卡（U5 §17.7「事前」层） ——

/**
 * 三步骤顺序卡：**首次进入抓包页、且还没开抓**时给一遍正确顺序。
 *
 * ⚠️ 顺序就是成败关键：进「抽卡记录」页的那一瞬间客户端就请求了第 1 页（`offset 0`），
 * 抓包没开 ⇒ **第 1 页（最新那一页）直接漏掉**，且本次登录**再也拿不到**（页缓存，规则 C5）。
 * 这正是用户反馈「数据对不上」里最隐蔽的那一类（自己比对游戏才发现少了）。
 *
 * 落点取舍（§17.7）：**不是**「安装后立刻弹」（与操作场景脱节，看完就忘），也**不是**
 * 「首次点开始抓包时弹」（那一刻用户可能已站在卡池页里，弹了等于叫他全部重来）。
 */
@Composable
private fun FirstRunGuide(onDismiss: () -> Unit) {
    val colors = LocalJinlinColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.guide_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
            )
            StepLine("1. " + stringResource(R.string.guide_step1), colors)
            StepLine("2. " + stringResource(R.string.guide_step2), colors)
            StepLine("3. " + stringResource(R.string.guide_step3), colors)
            Text(
                text = stringResource(R.string.guide_note),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceMuted,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.guide_dismiss),
                        fontSize = 12.sp,
                        color = colors.onSurfaceDim,
                    )
                }
            }
        }
    }
}

// —— 停止确认弹窗（U5 §17.6） ——

/**
 * 停止确认弹窗：**所有状态都弹**（含 🟢 —— 用户明确要求「点停止要有提示」，且停止是
 * 不可逆的状态点），但按成因给出**不同的主按钮**：
 *
 * | 情形 | 主按钮 | 理由 |
 * |---|---|---|
 * | **A 类首屏缺口** | **停止抓包** | 「继续抓」对它无用（C5 页缓存不会再请求第 1 页）⇒ 把对的动作放主位 |
 * | **B/C 类缺口** | **继续抓（推荐）** | 翻回去就能补；但**必须**留「仍然停止」（想停却停不掉更糟） |
 * | 🔴 其它 / 🟢 / ⚪ | **停止抓包** | 一键即可过，不是必答确认 |
 *
 * ⚠️ **不记忆「不再提示」**：每次缺口的成因不同（首屏 / 中段 / 尾部），记忆会让真缺口被静默。
 * ⚠️ 按钮只承诺 **App 做得到的事**：文案是「停止抓包」而非「停止并重新登录」——
 * 重登是游戏侧的事，App 既控制不了也校验不了（§17.12.5）。
 * ⚠️ 通知栏的「停止」按钮**弹不出这个窗**（`ACTION_STOP` 由通知直发）⇒ 记录页的
 * 完整性卡是本场缺口的最后留痕处。
 */
@Composable
private fun StopConfirmDialog(
    dedup: DedupSnapshot,
    recordCount: Int,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = LocalJinlinColors.current
    val health = dedup.health
    val headGap = health.reason == HealthReason.HEAD_GAP
    // 「继续抓」只对 B/C 类缺口有效：它们晚于/夹在已见页之间，客户端会重发请求。
    val continueHelps = !headGap && health.state == HealthState.PENDING && dedup.missingPageCount > 0
    val okLike = health.state == HealthState.OK || health.state == HealthState.UNCOLLECTED
    val reasonText = healthReasonLine(dedup)
    val fixText = healthFixLine(dedup)
    val gapPages = (dedup.headGapPages + dedup.midGapPages + dedup.tailGapPages).sorted()
    // ⚠️ 用 firstOrNull 而不是 first：`missingPageCount` 与三张缺口页表虽同源，但它们是两个
    // 独立字段，理论上可被将来某次改动拆散 —— 这里**宁可在 UI 上显示「—」也不能抛异常**
    // （弹窗抛异常 = 用户卡在抓包页停不下来）。
    val firstGapPage = gapPages.firstOrNull()?.let { pageText(listOf(it)) } ?: "—"
    val lastGapPage = gapPages.lastOrNull()?.let { pageText(listOf(it)) } ?: "—"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(if (okLike) R.string.stop_ok_title else R.string.stop_title),
                color = colors.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    // 🟢 / ⚪：一句实情 + 一键确认，不做说教
                    okLike -> Text(
                        text = stringResource(R.string.stop_ok_body, recordCount),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = colors.onSurfaceMuted,
                    )

                    // 🔴 A 类首屏缺口：唯一需要「停→重登→开抓」序列的情形
                    headGap -> {
                        Text(
                            text = "🔴 " + stringResource(R.string.stop_head_title, pageText(dedup.headGapPages)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorWarpedRed,
                        )
                        Text(
                            text = stringResource(R.string.stop_head_cause),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = colors.onSurfaceMuted,
                        )
                        Text(
                            text = stringResource(R.string.stop_head_howto),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = colors.onSurface,
                        )
                        StepLine("1. " + stringResource(R.string.stop_head_step1), colors)
                        StepLine("2. " + stringResource(R.string.stop_head_step2), colors)
                        StepLine("3. " + stringResource(R.string.stop_head_step3), colors)
                        Text(
                            text = stringResource(R.string.stop_head_fallback),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = colors.onSurfaceDim,
                        )
                    }

                    // 🟡 B/C 类缺口：翻回去就能补
                    continueHelps -> {
                        Text(
                            text = "🟡 " + stringResource(
                                R.string.stop_mid_title,
                                dedup.missingPageCount,
                                pageText(gapPages),
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.gold,
                        )
                        Text(
                            text = stringResource(R.string.stop_mid_cause),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = colors.onSurfaceMuted,
                        )
                        StepLine("1. " + stringResource(R.string.stop_mid_step1), colors)
                        StepLine(
                            "2. " + stringResource(R.string.stop_mid_step2, firstGapPage, lastGapPage),
                            colors,
                        )
                        Text(
                            text = stringResource(R.string.stop_mid_fallback),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = colors.onSurfaceDim,
                        )
                    }

                    // 🔴 其它确定性问题（翻错列表 / 读不到数据）+ 🟡 衔接未校验：
                    // 用与抓包页完全相同的成因句 + 处置句，不另写一套。
                    else -> {
                        Text(
                            text = "⚠ " + reasonText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (health.state == HealthState.PROBLEM) ColorWarpedRed else colors.gold,
                        )
                        if (fixText != null) {
                            Text(
                                text = fixText,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = colors.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                continueHelps -> TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.stop_btn_continue_rec), color = colors.gold)
                }
                else -> TextButton(onClick = onStop) {
                    Text(text = stringResource(R.string.stop_btn_stop), color = colors.gold)
                }
            }
        },
        dismissButton = {
            if (continueHelps) {
                TextButton(onClick = onStop) {
                    Text(text = stringResource(R.string.stop_btn_stop_anyway), color = colors.onSurfaceMuted)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.stop_btn_continue), color = colors.onSurfaceMuted)
                }
            }
        },
    )
}

/** 编号步骤行（首次引导卡与停止弹窗共用）。 */
@Composable
private fun StepLine(text: String, colors: JinlinColors) {
    Text(text = text, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurface)
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

/**
 * 门 1 判据：是否存在**可接管**的目标应用（渠道服支持，2026-09-18）。
 *
 * 原实现只查官服包名 `com.bmystu.peng.gw` 是否存在 ⇒ 渠道服（用户实测 OPPO
 * `com.bmystu.peng1.nearme.gamecenter`）一律被判「未安装」，状态徽标报红、
 * 「开始抓包」按钮永久置灰。现改走 [TargetPackages.resolve]：官服 / 渠道服 /
 * 用户勾选的包，任一可接管即放行（`addAllowedApplication` 也按同一份清单接管）。
 */
/**
 * 门 1 判据：**有没有一个已定下来的可接管应用**（渠道服支持，2026-09-18）。
 *
 * 原实现只查官服包名 `com.bmystu.peng.gw` ⇒ 渠道服（用户实测 OPPO
 * `com.bmystu.peng1.nearme.gamecenter`）一律被判「未安装」，状态徽标报红、
 * 「开始抓包」按钮永久置灰。现改走 [TargetPackages.resolve]（**单选**，2026-09-18 晚由多选改）：
 * - [TargetPackages.Resolution.Ready] ⇒ 可启动；
 * - [TargetPackages.Resolution.NeedsChoice] ⇒ 检出多个渠道而用户没选：**不视作未安装**，
 *   由调用侧引导用户选一个（不替他决定）；
 * - [TargetPackages.Resolution.NotInstalled] ⇒ 才是真的没装。
 */
private fun startGate(context: Context): TargetPackages.Resolution = TargetPackages.resolve(context)

private fun ensureStart(
    context: Context,
    notifLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    // 门 1：目标应用已安装（官服或用户选定的渠道服）
    val gate = startGate(context)
    if (gate is TargetPackages.Resolution.NeedsChoice) {
        Toast.makeText(context, R.string.channel_need_pick, Toast.LENGTH_SHORT).show()
        return
    }
    if (gate !is TargetPackages.Resolution.Ready) {
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