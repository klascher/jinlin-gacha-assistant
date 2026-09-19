package com.jinlin.gacha.assistant.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.DedupSnapshot
import com.jinlin.gacha.assistant.core.HealthReason
import com.jinlin.gacha.assistant.core.HealthState
import com.jinlin.gacha.assistant.core.dedup.ViewIdentity
import com.jinlin.gacha.assistant.core.gapCoveredByHistory
import com.jinlin.gacha.assistant.core.health
import com.jinlin.gacha.assistant.persistence.CaptureDetail
import com.jinlin.gacha.assistant.ui.theme.ColorUpGreen
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 记录页「数据完整性」卡 + 子页（U5，2026-09-18；设计见 `10-去重模块设计.md` §17.8.2 / §15.6.2）。
 *
 * ### 为什么落点在记录页而不是统计页
 * ① 用户就是在记录页发现「数据少了」的（原话「有的能从记录页顶部发现缺数据」）；
 * ② 记录页是抓包中**唯一**能看到本场新增记录的地方；
 * ③ **通知栏的「停止」弹不出窗**（`ACTION_STOP` 由通知按钮直发）⇒ 这张卡是本场缺口的
 *    **最后留痕处**。第 ③ 条是它不可省的硬理由。
 *
 * ### 三态与判据
 * 判据**全部来自** `core/LiveHealth`（单一来源）；本文件只负责「状态 → 颜色/文案」的映射，
 * 不自己判断任何东西 —— 否则三处落点（抓包页 / 弹窗 / 这里）迟早漂移。
 *
 * | 态 | 点色 | 文案 |
 * |---|---|---|
 * | 🟢 OK | 绿 | 数据完整 · 已拿 N/M 页 |
 * | 🟡 PENDING | 金 | 数据可能不完整（还能靠翻页补） |
 * | 🔴 PROBLEM | 红 | 数据有缺口（含首屏缺口，继续翻拿不到） |
 * | ⚪ UNCOLLECTED | 灰 | 本场没抓到抽卡数据 |
 */

// —— 记录页顶部：数据完整性卡（替代原 DedupNotice 小字块） ——

/**
 * 三态完整性卡，**整卡可点**进子页。
 *
 * 本场什么都没发生（快照仍是全默认值）时**不渲染** —— 冷启动就在记录页顶一张
 * 「本场没抓到抽卡数据」的卡是噪音；抓包页的三态点已经把「正在收到吗」讲清楚了。
 * 一旦有过任何解析 / 丢弃 / 新增，卡片就常驻到下一场开始。
 */
@Composable
internal fun HealthCard(dedup: DedupSnapshot, onOpen: () -> Unit) {
    val colors = LocalJinlinColors.current
    if (dedup == DedupSnapshot()) return

    val health = dedup.health
    val dot = when (health.state) {
        HealthState.OK -> ColorUpGreen
        HealthState.PENDING -> colors.gold
        HealthState.PROBLEM, HealthState.UNCOLLECTED -> ColorWarpedRed
    }
    val title = when (health.state) {
        HealthState.OK -> stringResource(R.string.health_card_ok)
        HealthState.PENDING -> stringResource(R.string.health_card_gap)
        HealthState.PROBLEM -> stringResource(R.string.health_card_problem)
        HealthState.UNCOLLECTED -> stringResource(R.string.health_card_uncollected)
    }
    // 详情行：🟢 给页覆盖、⚪ 给操作引导，其余按成因（与抓包页同一句，见 healthReasonLine）
    val detail = when (health.state) {
        // 🟢：**已接上历史时不显示「已拿 N/M 页」** —— 那个比值会被读成「还差 M−N 页没拿完」，
        // 而那 M−N 页其实全在历史里（见 `DedupSnapshot.gapCoveredByHistory`）。
        // 文案走 `pagesCoveredLine`（三处落点唯一实现），不要在本地另写一句。
        HealthState.OK -> if (dedup.gapCoveredByHistory) {
            pagesCoveredLine(dedup.gapSeenPages, dedup.gapExpectedPages)
        } else {
            stringResource(
                R.string.health_card_ok_detail, dedup.gapSeenPages, dedup.gapExpectedPages,
            )
        }
        HealthState.UNCOLLECTED -> stringResource(R.string.health_card_uncollected_detail)
        else -> healthReasonLine(dedup)
    }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(9.dp)
                    .background(dot, RoundedCornerShape(50)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (health.state == HealthState.OK) colors.onSurface else dot,
                )
                Text(text = detail, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceMuted)
            }
            Text(
                text = stringResource(R.string.health_card_view),
                fontSize = 12.sp,
                color = colors.onSurfaceDim,
            )
        }
    }
}

// —— 抓包页状态行的文案（与卡片同源，故放这里供两处共用） ——

/**
 * 按 [HealthReason] 给出一句状态描述。抓包页的实时行与记录页卡片都调它 ——
 * 两处各写一份的话，「同一件事两种说法」几乎必然发生。
 */
@Composable
internal fun healthReasonLine(dedup: DedupSnapshot): String {
    val health = dedup.health
    return when (health.reason) {
        HealthReason.UNCOLLECTED -> stringResource(R.string.live_reason_uncollected)
        HealthReason.WRONG_LIST -> stringResource(R.string.live_reason_wrong_list)
        HealthReason.NO_DATA -> stringResource(R.string.live_reason_no_data)
        HealthReason.HEAD_GAP ->
            stringResource(R.string.live_reason_head_gap, pageText(dedup.headGapPages))
        HealthReason.MID_GAP ->
            stringResource(R.string.live_reason_mid_gap, dedup.midGapPages.size)
        HealthReason.TAIL_GAP ->
            stringResource(R.string.live_reason_tail_gap, dedup.tailGapPages.size)
        HealthReason.UNCLEAR_HISTORY -> stringResource(R.string.live_reason_unclear)
        HealthReason.NONE -> stringResource(R.string.live_reason_none)
    }
}

/** 状态行下面的处置建议（🟢 时无建议，返回 null）。 */
@Composable
internal fun healthFixLine(dedup: DedupSnapshot): String? =
    when (dedup.health.reason) {
        HealthReason.WRONG_LIST -> stringResource(R.string.live_fix_wrong_list)
        HealthReason.NO_DATA -> stringResource(R.string.live_fix_no_data)
        HealthReason.HEAD_GAP -> stringResource(R.string.live_fix_head_gap)
        HealthReason.MID_GAP -> stringResource(R.string.live_fix_mid_gap)
        HealthReason.UNCLEAR_HISTORY -> stringResource(R.string.live_fix_unclear)
        else -> null
    }

/**
 * 「已接上历史」时的页覆盖句（**唯一实现**）—— 抓包页实时卡 / 记录页完整性卡 / 完整性子页三处共用。
 *
 * ### 为什么要抽出来（2026-09-19 实机反馈的返工）
 * `gapCoveredByHistory` 这个口径昨天只在**记录页卡片**和**完整性子页**接了。抓包页的实时卡
 * 是**另一条渲染路径**（`CaptureScreen` 自渲染 `live_pages`），没跟着走 ⇒ 用户装包后看到
 * 「记录页对了、抓包页还是「已拿 15/107 页」」。同一件事在三处渲染，漏一处就复发一次。
 *
 * ⇒ 以后凡「页覆盖/缺口」类文案，**一律走这里**：它同时钉住三件事 ——
 * ① 已接上历史时不显示「已拿 N/M」那个比值；② `M − N` 的算术与 `coerceAtLeast(0)` 只写一次
 * （`gapSeenPages` 与 `gapExpectedPages` 理论上是两个独立字段，直接相减可能为负）；
 * ③ 文案 key 只有一条，不会三处漂移成三种说法。
 */
@Composable
internal fun pagesCoveredLine(seen: Int, expected: Int): String =
    stringResource(
        R.string.health_pages_covered,
        seen,
        (expected - seen).coerceAtLeast(0),
    )

// —— 子页：数据完整性 ——

/**
 * 数据完整性子页（`RecordsScreen` 内用 `rememberSaveable` 切换，不引 NavHost）。
 *
 * @param storedCount 该账号**已落库**的条数（不含本场未落库的新增）——
 *   「差」= 服务器权威 − 已落库，与 PC `_coverage_gap_text` 的 `total − grabbed` 同口径。
 * @param running / [sessionStartedAt] 本场状态，用于「本场时间」行。
 */
@Composable
internal fun HealthDetail(
    dedup: DedupSnapshot,
    lastCapture: CaptureDetail?,
    storedCount: Int,
    running: Boolean,
    sessionStartedAt: Long,
    onBack: () -> Unit,
) {
    val colors = LocalJinlinColors.current
    val health = dedup.health

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 返回 + 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text(text = "‹ " + stringResource(R.string.health_back), fontSize = 13.sp, color = colors.gold)
            }
            Text(
                text = stringResource(R.string.health_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
        }

        // —— 三个大数字：服务器权威 / 已落库 / 差 ——
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    BigNumber(
                        value = dedup.authoritativeTotal,
                        label = stringResource(R.string.health_big_server),
                        modifier = Modifier.weight(1f),
                    )
                    BigNumber(
                        value = storedCount,
                        label = stringResource(R.string.health_big_local),
                        modifier = Modifier.weight(1f),
                    )
                    BigNumber(
                        value = (dedup.authoritativeTotal - storedCount).coerceAtLeast(0),
                        label = stringResource(R.string.health_big_diff),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.health_big_note),
                    fontSize = 11.sp,
                    color = colors.onSurfaceMuted,
                )
            }
        }

        // —— 缺口区（按 A/B/C 分三类，说「第 N 页」不说 offset） ——
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 「已接上历史」时**不再报「缺口 · N 页」**：那 N 页是历史里已有的旧记录，
                // 报成缺口会与「数据完整」自相矛盾，并把用户赶回去翻一堆本来就不缺的页。
                val covered = dedup.gapCoveredByHistory
                val showGap = dedup.missingPageCount > 0 && !covered
                Text(
                    text = when {
                        covered -> stringResource(R.string.health_covered_title)
                        showGap -> stringResource(R.string.health_sec_gap, dedup.missingPageCount)
                        else -> stringResource(R.string.health_no_gap)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (showGap) ColorWarpedRed else ColorUpGreen,
                )
                if (covered) {
                    Text(
                        text = stringResource(
                            R.string.health_covered_note,
                            dedup.gapSeenPages,
                            (dedup.gapExpectedPages - dedup.gapSeenPages).coerceAtLeast(0),
                        ),
                        fontSize = 12.sp,
                        color = colors.onSurfaceDim,
                    )
                }
                if (dedup.hasHeadGap) {
                    GapLine(stringResource(R.string.health_gap_head, pageText(dedup.headGapPages)), colors)
                }
                if (dedup.hasMidGap) {
                    GapLine(stringResource(R.string.health_gap_mid, pageText(dedup.midGapPages)), colors)
                }
                // C 类（尾部未翻）**已接上历史时不列**：那些页历史里已有，列出来就是在劝用户白翻
                if (dedup.tailGapPages.isNotEmpty() && !covered) {
                    GapLine(stringResource(R.string.health_gap_tail, pageText(dedup.tailGapPages)), colors)
                }
                if (covered) {
                    Text(
                        text = stringResource(R.string.health_covered_optional),
                        fontSize = 11.sp,
                        color = colors.onSurfaceMuted,
                    )
                }
                // 「已拿 N/M 页」：**已接上历史时不显示**。两个原因 ——
                // ① 与上方「已接上历史」直接矛盾（用户会读成「还差 M−N 页没拿完」，2026-09-19 实机反馈）；
                // ② 这两个数字 `health_covered_note` 里已经说过一遍，重复只会加重误会。
                if (dedup.gapExpectedPages > 0 && !covered) {
                    Text(
                        text = stringResource(
                            R.string.health_cov, dedup.gapSeenPages, dedup.gapExpectedPages,
                        ),
                        fontSize = 12.sp,
                        color = colors.onSurfaceMuted,
                    )
                }
                // 扫描范围（诊断用）：只在本场确有已见页时显示，否则不写「offset -1 – -1」
                if (dedup.minSeenOffset >= 0 && dedup.maxSeenOffset >= 0) {
                    HorizontalDivider(color = colors.divider)
                    Text(
                        text = stringResource(R.string.health_sec_scan),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceMuted,
                    )
                    Text(
                        text = stringResource(
                            R.string.health_scan_value, dedup.minSeenOffset, dedup.maxSeenOffset,
                        ),
                        fontSize = 12.sp,
                        color = colors.onSurfaceDim,
                    )
                }
            }
        }

        // —— 本场 / 上次时间（与记录页 header 同源同口径） ——
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.health_sec_time),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceMuted,
                )
                Text(
                    text = captureTimeLine(lastCapture, running, sessionStartedAt),
                    fontSize = 12.sp,
                    color = colors.onSurface,
                )
            }
        }

        // —— 补救建议：只给「该做的那一件事」，且动作必须是 App 说得出、用户做得到的 ——
        // ⚠️ 首屏缺口（A 类）的补救**只能**是「停止 → 重新登录 → 先开抓包再进记录页」；
        //    不得出现「退出记录页重进」—— 本次登录已翻过的页有缓存、不会再请求（规则 C5）。
        if (health.reason == HealthReason.HEAD_GAP) {
            AdviceCard(text = stringResource(R.string.health_advice_head), emphasis = true)
        } else if (dedup.missingPageCount > 0 && !dedup.gapCoveredByHistory) {
            // 已接上历史时**不给这条建议**：它劝用户「回到全部卡池把上面列出的页翻出来」，
            // 而那些页历史里已有 —— 与「数据完整」并列等于把用户赶去白翻（2026-09-19 实机反馈）
            AdviceCard(text = stringResource(R.string.health_advice_other), emphasis = false)
        }
    }
}

@Composable
private fun BigNumber(value: Int, label: String, modifier: Modifier = Modifier) {
    val colors = LocalJinlinColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "$value", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Text(text = label, fontSize = 11.sp, color = colors.onSurfaceMuted)
    }
}

@Composable
private fun GapLine(text: String, colors: JinlinColors) {
    Text(text = "· $text", fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurface)
}

@Composable
private fun AdviceCard(text: String, emphasis: Boolean) {
    val colors = LocalJinlinColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (emphasis) colors.gold else colors.onSurfaceMuted,
            modifier = Modifier.padding(16.dp),
        )
    }
}

// —— 页码与时间换算（**唯一**的「offset → 第 N 页」实现，抓包页与记录页共用） ——

/**
 * offset 列表 → 用户口径的「第几页」文本：`第 N 页 = offset / PAGE_SIZE + 1`，第 1 页 = 最新。
 *
 * 只列前 5 个（缺口可能上百个，全列会把小屏挤爆），超出以「等 N 页」收尾。
 * ⚠️ 面向用户的文案**一律不出现 offset**（用户 2026-09-18 明确要求）；offset 只留在
 * 日志 / 子页的「扫描范围」（那是诊断信息，且明写了 offset 字样）。
 */
internal fun pageText(offsets: List<Int>): String {
    if (offsets.isEmpty()) return "—"
    val head = offsets.take(5).joinToString("、") { (it / ViewIdentity.PAGE_SIZE + 1).toString() }
    return if (offsets.size > 5) "$head 等 ${offsets.size} 页" else head
}

private val HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MD_HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 解析 `last_capture_detail` 里的 ISO 文本；解析不了返回 null（页面显示「暂无记录」，不显示半截）。 */
internal fun parseCaptureTime(s: String): LocalDateTime? = try {
    LocalDateTime.parse(s)
} catch (e: Exception) {
    null
}

/**
 * 「上一场」的起止文本：**左端总是带月-日**（这场抓包是哪天），右端同日只给时分、跨天补日期。
 *
 * ⚠️ 刻意**不写「今天」** —— 记录页是长期停留页，隔夜再看「今天」就错了。
 * ⇒ 一律 `MM-dd HH:mm–HH:mm`（跨天 `MM-dd HH:mm–MM-dd HH:mm`）。
 */
internal fun formatCaptureRange(start: LocalDateTime, end: LocalDateTime): String {
    val left = start.format(MD_HM_FMT)
    val right = if (start.toLocalDate() == end.toLocalDate()) end.format(HM_FMT) else end.format(MD_HM_FMT)
    return "$left–$right"
}

/** 「本次」的单个时刻：与今天同天只给时分，跨天补月-日（与 [formatCaptureRange] 同一口径）。 */
internal fun formatClock(t: LocalDateTime, today: LocalDate): String =
    if (t.toLocalDate() == today) t.format(HM_FMT) else t.format(MD_HM_FMT)

/**
 * 「本场时间 / 上次时间」合并成一行（子页用）：本场优先（抓包中或刚停），否则回落到上一场。
 */
internal fun captureTimeLine(
    lastCapture: CaptureDetail?,
    running: Boolean,
    sessionStartedAt: Long,
): String {
    val today = LocalDate.now()
    if (running && sessionStartedAt > 0L) {
        val start = java.time.Instant.ofEpochMilli(sessionStartedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        return "本场 ${formatClock(start, today)} 开始"
    }
    val start = lastCapture?.let { parseCaptureTime(it.startedAt) }
    val end = lastCapture?.let { parseCaptureTime(it.endedAt) }
    if (start != null && end != null) return "上次 ${formatCaptureRange(start, end)} · ${lastCapture.added} 条"
    return "尚未抓过包"
}
