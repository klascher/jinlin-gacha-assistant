package com.jinlin.gacha.assistant.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.stats.PerPoolStat
import com.jinlin.gacha.assistant.core.stats.PityGroupStat
import com.jinlin.gacha.assistant.core.stats.PityHit
import com.jinlin.gacha.assistant.ui.stats.StatsReport
import com.jinlin.gacha.assistant.ui.theme.ColorUpGreen
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 保底总览（Tab1 页身，对齐 08-页面设计.md §4 / pc `PityPanel`）。
 *
 * 从 [statsReport] 只读取数，纯展示引擎 `sharedPityTracking()` 的输出，**不自行排序/重算**。
 * 顺序由引擎天然决定：hasState 组（共享保底）在前、独立组（solo，如某独立池）在后。折叠态
 * 会话内记忆（对齐 pc "切换账号/清空才重置"；此处按账号文档约定为会话级）。
 *
 * 口径（§3.78）：停止抓包后一次性计算、取账号全量历史；抓包运行中展示停止前已落库历史。
 *
 * > 明确不含：陌生卡池指派 / 未映射 ID 条（`CaptureScreen` 的 TODO 锚点保留，属后续批，
 * > 见 `mobile/docs/09-设置模块设计.md` §13.3）。
 */
@Composable
internal fun PityOverview(report: StatsReport) {
    val colors = LocalJinlinColors.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.pity_overview_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )

        if (!report.hasData) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.pity_overview_empty_title),
                        fontSize = 13.sp,
                        color = colors.onSurfaceDim,
                    )
                    Text(
                        text = stringResource(R.string.pity_overview_empty_hint),
                        fontSize = 12.sp,
                        color = colors.onSurfaceMuted,
                    )
                }
            }
            return
        }

        report.tracking.forEach { (gkey, g) ->
            GroupBlock(gkey, g, report.poolNames, colors)
        }
    }
}

/** 一个保底分组：可折叠组头 + （展开时）池行与 6 星命中行。 */
@Composable
private fun GroupBlock(
    gkey: String,
    g: PityGroupStat,
    poolNames: Map<String, String>,
    colors: JinlinColors,
) {
    var expanded by rememberSaveable(gkey) { mutableStateOf(g.hasState) }

    val header = buildHeader(g)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▼" else "▶",
                fontSize = 11.sp,
                color = colors.onSurfaceDim,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = header,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
        }
    }

    if (!expanded) return

    // 池行顺序对齐 PC pity_panel._pool_sort_key：**pool_id 数值倒序**（号大 = 新卡池在前，
    // #1 = 最新）。Kotlin 引擎的 `poolIds` 是 `sorted()` 升序——倒序属展示层口径，
    // 在展示层排，不动冻结引擎。
    val orderedPids = g.poolIds.sortedWith(Comparator { a, b ->
        val an = a.toIntOrNull()
        val bn = b.toIntOrNull()
        when {
            an != null && bn != null -> bn.compareTo(an) // 纯数字：倒序，号大在前
            an != null -> -1                            // 数字在前
            bn != null -> 1                             // 非数字在后
            else -> a.compareTo(b)                      // 都非数字按字符串
        }
    })
    orderedPids.forEach { pid ->
        val ps = g.perPool[pid] ?: return@forEach
        PoolAndHits(pid, ps, g.hits, g.hasState, poolNames, colors)
    }
}

/** 组头文案：共享组带状态与距保底，独立组只带池名。 */
private fun buildHeader(g: PityGroupStat): String {
    // 共享组：`{label} 共享保底 {pity}/{hard} {状态} 距保底N抽`
    // 独立组：`{label} 独立保底 {pity}/{hard}`
    val prefix = if (g.hasState) {
        "共享保底 ${g.currentPity}/${g.hardPity}" + (g.state?.let { " · $it" } ?: "") +
            " · 距保底${g.distanceToPity}抽"
    } else {
        "独立保底 ${g.currentPity}/${g.hardPity}"
    }
    return "${g.label}   $prefix"
}

/** 一个池：池名行 +（下方）该池的 6 星命中行（ts 倒序，最近在上）。 */
@Composable
private fun PoolAndHits(
    pid: String,
    ps: PerPoolStat,
    groupHits: List<PityHit>,
    featured: Boolean,
    poolNames: Map<String, String>,
    colors: JinlinColors,
) {
    val name = poolNames[pid] ?: "未知($pid)"

    // 组头（如独立池的池名已在组头）不重复；这里只渲染该池的命中明细。
    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
        // 池名行：`{池名} 共X抽` + 无六星灰字标注
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$name  · 共${ps.total}抽",
                fontSize = 12.sp,
                color = colors.onSurfaceDim,
                modifier = Modifier.weight(1f),
            )
            if (ps.sixCount == 0) {
                Text(
                    text = stringResource(R.string.pity_untouched),
                    fontSize = 11.sp,
                    color = colors.onSurfaceMuted,
                )
            }
        }

        // 引擎 hits 为消费序（ts 升序，旧→新）；展示取反序，最近 6 星在上。
        val hits = groupHits.filter { it.poolId == pid }.asReversed()
        if (hits.isNotEmpty()) {
            Column(Modifier.padding(start = 14.dp)) {
                hits.forEach { h -> HitRow(h, featured, colors) }
            }
        }
    }
}

/** 命中行：`第N抽 [UP]/[歪] 角色名 时间`（仅 [featured] 池标 [UP]/[歪]），ts 倒序（最近在上）。 */
@Composable
private fun HitRow(h: PityHit, featured: Boolean, colors: JinlinColors) {
    val tagColor = if (h.isUp) ColorUpGreen else ColorWarpedRed
    val tag = if (h.isUp) stringResource(R.string.pity_tag_up) else stringResource(R.string.pity_tag_warp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第${h.pity}抽",
            fontSize = 12.sp,
            color = colors.onSurfaceMuted,
            modifier = Modifier.width(52.dp),
        )
        // 常驻/新手等无 state 组**没有「歪」概念**，不标记 [UP]/[歪]（对齐 PC HitRow。
        // 仅 featured（UP/遴选，对应 has_state 组）才渲染着色标记）。
        if (featured) {
            Text(
                text = tag,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = tagColor,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = h.name,
            fontSize = 12.sp,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatHitTime(h.ts),
            fontSize = 11.sp,
            color = colors.onSurfaceDim,
        )
    }
}

private val HIT_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatHitTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(HIT_TIME_FMT)