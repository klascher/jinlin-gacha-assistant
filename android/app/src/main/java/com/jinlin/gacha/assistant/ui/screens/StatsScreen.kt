package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.persistence.CaptureDetail
import com.jinlin.gacha.assistant.persistence.HistoryEpoch
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.ui.theme.BlueRarity
import com.jinlin.gacha.assistant.ui.theme.GoldRarity
import com.jinlin.gacha.assistant.ui.theme.GrayRarity
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.ui.theme.PurpleRarity
import com.jinlin.gacha.assistant.ui.stats.StatsReport
import com.jinlin.gacha.assistant.ui.stats.statsReport
import java.io.File
import java.util.Locale

/**
 * 统计页（Tab 3）。
 *
 * ### 版面（2026-09-14 用户拍板，见 `mobile/docs/09-设置模块设计.md` §12 与 08 §6）
 * 自上而下：**页标题「统计」→「用户管理」组 → 统计内容**。
 *
 * - **用户管理**：账号管理（切换 / 新建 / 重命名 / 删除）由设置页整体移入此处，实现见 [AccountSection]。
 * - **统计内容**：封面摘要 / 卡池分布 / 稀有度分布 / 非歪率 —— 引擎（`core/stats/StatsEngine` +
 *   `StatsSummary`）已就绪并完成 PC 对拍，经 [statsReport] 只读取数后渲染。
 *
 * 口径（§3.78 拍板）：停止抓包后一次性计算、取账号全量历史；抓包运行中顶部显示灰字提示、
 * 数据照常展示已落库历史。纯只读展示，不做实时叠加 / 悬浮球。
 */
@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val colors = LocalJinlinColors.current
    val report = statsReport(context)
    // 上一场**已落库**抓包的起止（U5 §17.8.1）：提示条第二行「截至 …」用。
    // 与记录页同源同口径（`last_capture_detail`，**只**由停抓收尾那条 merge 写入）。
    val prof by ProfileStore.get(context).state.collectAsState()
    val epoch by HistoryEpoch.state.collectAsState()
    val lastCapture: CaptureDetail? = remember(prof.activeId, report.isRunning, epoch) {
        HistoryStore(File(context.filesDir, ProfileStore.USERS_DIR), prof.activeId).captureDetail
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.stats_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // —— 运行中提示条（U5 §17.8.1；§3.78 口径：统计取停止后全量，抓包中展示的是停止前已落库历史）——
        // 机制本来就是「停止后才更新」（报告缓存的 key **不含实时数据**）⇒ 这里只把「一行 12sp 灰字」
        // 改成「带底色的提示条」，并把「截至时刻」写出来，让「看到的是哪一次的数据」无歧义。
        // ⚠️ 本页正是靠 `cleanup()` 里「**先落库、后复位状态**」的顺序，在停止瞬间读到新数据 ——
        //    那个顺序是**承重**的，不可调换（调换后本页会**静默**显示旧数据，不报错也不刷新）。
        if (report.isRunning) {
            item { StatsRunningBar(lastCapture = lastCapture, colors = colors) }
        }

        // —— 用户管理（账号切换 / 新建 / 重命名 / 删除）——
        item { AccountSection(colors) }

        if (!report.hasData) {
            item { StatsEmpty(colors) }
            return@LazyColumn
        }

        // —— 封面摘要 ——
        item { SummaryCard(report, colors) }

        // —— 卡池分布 ——
        item { DistribCard(report, colors) }

        // —— 稀有度分布 ——
        item { RarityCard(report, colors) }

        // —— 非歪率 ——
        item { NonWarpCard(report, colors) }
    }
}

/**
 * 统计页「抓包中」提示条（U5 §17.8.1）：**带底色**（`surfaceVariant`）而非裸灰字，一眼能看见。
 *
 * 两行：
 * 1. `stats_running_hint` —— 说明下面显示的是**上次抓完**的数据、停止后才更新；
 * 2. `stats_last_capture`（「截至 …」）—— 把「上次抓取时刻」写出来；**从未停抓过则不出这一行**
 *    （不写「截至 —」，宁可少一行也不给假的确定感）。
 *
 * ⚠️ 时刻**带月-日**（[formatCaptureRange]），刻意不写「今天」：本页会跨夜停留。
 */
@Composable
private fun StatsRunningBar(lastCapture: CaptureDetail?, colors: JinlinColors) {
    val start = lastCapture?.startedAt?.let(::parseCaptureTime)
    val end = lastCapture?.endedAt?.let(::parseCaptureTime)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_running_hint),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
            )
            if (start != null && end != null) {
                Text(
                    text = stringResource(R.string.stats_last_capture, formatCaptureRange(start, end)),
                    fontSize = 11.sp,
                    color = colors.onSurfaceMuted,
                )
            }
        }
    }
}

/** 空态：尚无该账号抽卡记录。 */
@Composable
private fun StatsEmpty(colors: JinlinColors) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.stats_empty_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.stats_empty_hint),
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

/** 封面摘要卡：对齐 PC PityPanel 顶部 `总抽数 N · 6星 K · 出货池 S/T · 6星率 R% · 不歪率 R%`。 */
@Composable
private fun SummaryCard(report: StatsReport, colors: JinlinColors) {
    val s = report.summary
    val totalLabel = stringResource(R.string.stats_summary_total)
    val sixLabel = stringResource(R.string.stats_summary_six)
    val shippedLabel = stringResource(R.string.stats_summary_shipped)
    val rateLabel = stringResource(R.string.stats_summary_rate)
    val nonWarpLabel = stringResource(R.string.stats_summary_nonwarp)

    var line = "$totalLabel ${s.total} · $sixLabel ${s.sixTotal} · " +
        "$shippedLabel ${s.shippedPools}/${s.totalPools} · " +
        "$rateLabel ${pct(s.sixRatePercent)}"
    if (s.nonWarpRatePercent != null) line += " · $nonWarpLabel ${pct(s.nonWarpRatePercent)}"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = line, fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurface)
        }
    }
}

/** 卡池分布：池名 + 轻量柱状（比例宽）+ 条数，条数降序。 */
@Composable
private fun DistribCard(report: StatsReport, colors: JinlinColors) {
    val entries = report.poolDist.toList()
    val max = entries.maxOfOrNull { it.second } ?: 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.stats_pool_dist_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_no_data),
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                entries.forEach { (name, count) ->
                    DistributionBar(
                        label = name,
                        count = count,
                        max = max,
                        barColor = colors.gold,
                        colors = colors,
                    )
                }
            }
        }
    }
}

/** 稀有度分布：星级色条（金/紫/蓝/灰）+ 条数，星级降序。 */
@Composable
private fun RarityCard(report: StatsReport, colors: JinlinColors) {
    val entries = report.rarityDist.toList()
    val max = entries.maxOfOrNull { it.second } ?: 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.stats_rarity_dist_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            entries.forEach { (rarity, count) ->
                DistributionBar(
                    label = rarityLabel(rarity),
                    count = count,
                    max = max,
                    barColor = rarityColor(rarity),
                    colors = colors,
                )
            }
        }
    }
}

/** 非歪率：每 hasState 组 `{label} wins/allUp (rate%)`，allUp==0 显示 `-`。 */
@Composable
private fun NonWarpCard(report: StatsReport, colors: JinlinColors) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.stats_nonwarp_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (report.nonWarp.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_no_data),
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                report.nonWarp.forEach { (gkey, st) ->
                    val label = report.tracking[gkey]?.label ?: gkey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${st.wins}/${st.allUp} ${pct(st.rate)}",
                            fontSize = 13.sp,
                            color = if (st.rate != null) colors.gold else colors.onSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}

/** 分布横条：左标签 + 中比例色条 + 右条数。 */
@Composable
private fun DistributionBar(
    label: String,
    count: Int,
    max: Int,
    barColor: Color,
    colors: JinlinColors,
) {
    val fraction = if (max > 0) count.toFloat() / max else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier.width(72.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(barColor, RoundedCornerShape(4.dp)),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count",
            fontSize = 12.sp,
            color = colors.onSurfaceDim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
    }
}

// —— 派生 ——

private fun rarityLabel(r: Int): String = when (r) {
    6 -> "6星"
    5 -> "5星"
    4 -> "4星"
    3 -> "3星"
    else -> "${r}星"
}

private fun rarityColor(r: Int): Color = when (r) {
    6 -> GoldRarity
    5 -> PurpleRarity
    4 -> BlueRarity
    else -> GrayRarity
}

private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v)

private fun pct(v: Double?): String = v?.let { pct(it) } ?: "—"