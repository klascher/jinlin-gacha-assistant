package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.core.DedupSnapshot
import com.jinlin.gacha.assistant.core.GachaRecord
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.persistence.HistoryEpoch
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.RoleCacheSeed
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记录页（Tab 2）—— 展示**账号全量历史 + 本次会话新增**，实时更新。
 *
 * 数据源两路并集：
 * - 历史 = `HistoryStore.records`（落库 json，按账号隔离；停抓时 `merge` 已把本次会话并进
 *   历史落盘，故这里天然含上一次会话——以 `running` 翻转触发重读最新磁盘）。
 * - 本次会话新增 = `GachaVpnService.records`（进程级 StateFlow，实时追加，每次「开始抓包」清空；
 *   自 2026-09-14 起这批已过去重）。
 * 两路按记录身份 `(pool_id,item_id,timestamp)` 去重，避免「停抓后历史已含会话」时重复计。
 *
 * 顶部的 [DedupNotice] 把两类「静默失败」变成可见提示：① 只翻单池/活动/遴选 → 本次会话空；
 * ② 有页没抓到 → 提示还缺几页。筛选/搜索/导出留 2.2。
 */
@Composable
fun RecordsScreen() {
    val context = LocalContext.current
    val colors = LocalJinlinColors.current
    val prof by ProfileStore.get(context).state.collectAsState()
    val running = GachaVpnService.state.collectAsState().value.running
    val records by GachaVpnService.records.collectAsState()
    val dedup by GachaVpnService.dedupState.collectAsState()
    // 角色/卡池 id → 展示名（元数据按账号缓存；未知回退「未知(id)」，同统计页口径）。
    val nameMaps = rememberNameMaps(context, prof.activeId)

    // 历史内容版本（清空 / 还原后 bump）。清空不改 activeId、也不启停抓包，若不算进 key，
    // 本页会一直显示被清掉之前的旧列表。
    val epoch by HistoryEpoch.state.collectAsState()

    // 历史（落库）：按账号；停抓时 merge 已落盘，故以 running 翻转触发重读，读到本次会话并后的样子。
    val history: List<GachaRecord> = remember(prof.activeId, running, epoch) {
        HistoryStore(File(context.filesDir, ProfileStore.USERS_DIR), prof.activeId).records
    }
    val historyKeys: Set<Triple<String, String, Long>> = remember(history) {
        history.map { Triple(it.poolId, it.itemId, it.timestamp) }.toSet()
    }
    // 并集：本次会话新增（按身份过滤，去掉已并进历史的）+ 历史；均最新在前（会话 reverse / 历史本就 ts 倒序）。
    val allRecords: List<GachaRecord> = buildList {
        addAll(records.filter { Triple(it.poolId, it.itemId, it.timestamp) !in historyKeys }.reversed())
        addAll(history)
    }
    // 顶栏可见拆分：已存历史 = 磁盘落盘部分；本次 = 并集中非历史部分（本次新抓、尚未并入历史）。
    val historyCount = history.size
    val sessionCount = allRecords.size - historyCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "记录",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = recordHeader(allRecords.size, historyCount, sessionCount, dedup.overlapped),
                fontSize = 13.sp,
                color = colors.onSurfaceMuted,
            )
        }

        DedupNotice(dedup, colors)

        if (allRecords.isEmpty()) {
            EmptyRecords(colors)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(allRecords) { index, rec ->
                    RecordRow(rec, index = index, names = nameMaps)
                }
            }
        }
    }
}

/**
 * 去重提示条（08 §7 Q2=C）：只在需要用户动作时出现，避免常驻噪音。
 *
 * - **未抓到「全部卡池」**（丢弃过页且从未见全部卡池请求）→ 强提示：这一场记录会是空的，
 *   必须切到「全部卡池」列表再翻页（去重只采纳它是其它视图的超集）。
 * - 抓到过全部卡池、但也翻过其它视图 → 弱提示（丢弃属预期）。
 * - 有缺口（按 total 推算的应有页未全见）→ 提示还缺几页，翻到底可补齐。
 *   **但已翻到历史边界（[DedupSnapshot.overlapped]）时不再提示**：此时「还缺」的都是
 *   已落库的旧历史页（再翻底也不会产生新数据），照旧提示只会误报「还差N页」
 *   （2026-09-16 待办 5/7 反馈）。
 */
@Composable
private fun DedupNotice(dedup: DedupSnapshot, colors: JinlinColors) {
    val showMissing = dedup.missingPageCount > 0 && !dedup.overlapped
    if (dedup.droppedPages == 0 && !showMissing) return
    val blocked = dedup.droppedPages > 0 && !dedup.allPoolsSeen

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (blocked) {
                Text(
                    text = "未抓到「全部卡池」列表",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.gold,
                )
                Text(
                    text = "已丢弃 ${dedup.droppedPages} 页非「全部卡池」视图的数据。" +
                        "去重只采纳「全部卡池」（它是单池 / 活动契约 / 遴选契约视图的超集）" +
                        "——请切到『全部卡池』列表后再翻页。",
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                )
            } else if (dedup.droppedPages > 0) {
                Text(
                    text = "已丢弃 ${dedup.droppedPages} 页非「全部卡池」视图数据（按设计只采纳全部卡池）",
                    fontSize = 12.sp,
                    color = colors.onSurfaceDim,
                )
            }
            if (showMissing) {
                Text(
                    text = "还缺 ${dedup.missingPageCount} 页未抓到（未翻到或未响应），继续翻到底可补齐",
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                )
            }
        }
    }
}

/** 空态：还没抓到抽时的引导（对齐 CaptureScreen 空态风格）。 */
@Composable
private fun EmptyRecords(colors: JinlinColors) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "暂无抽卡记录",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = "没有抽卡记录。开启抓包后在游戏抽卡记录页切到『全部卡池』列表再逐页翻到底，" +
                    "即可实时收录；本页会展示历史与本次新增的全部记录（池 / 角色 / 时间）。" +
                    "抓到的新记录在抓包停止（或抓取中周期落盘）时自动保存，重开 App 仍可见。",
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

/** 角色/卡池 id → 展示名映射（未知 id 不在表中时回退「未知(id)」，同统计页 `未知(…)` 口径）。 */
private data class NameMaps(
    val pool: Map<String, String>,
    val character: Map<String, String>,
)

/**
 * 按账号加载元数据名映射（镜像统计页 `statsReport` 的缓存口径：`RoleCacheSeed.ensureSeeded` →
 * `MetaLoader.read(cacheFile)`；`remember(activeId)` 账号切换即重读，重组不重复读盘）。
 *
 * 记录页原先直接展示 poolId/itemId（待办 8）——有元数据时显示角色/卡池名称，缺缓存回落
 * 成 id，别让整页因元数据缺失而空掉。
 */
@Composable
private fun rememberNameMaps(context: android.content.Context, activeId: String): NameMaps {
    return remember(activeId) {
        RoleCacheSeed.ensureSeeded(context)
        val meta = MetaLoader.read(MetaLoader.cacheFile(context.filesDir))
        NameMaps(
            pool = meta?.pools?.mapValues { (_, p) -> p.name } ?: emptyMap(),
            character = meta?.characters?.mapValues { (_, c) -> c.name } ?: emptyMap(),
        )
    }
}

/** 单条记录行：#全局序号 · `池名：[角色名]` · 时间；附「位序」（落库坐标 = pos − Δ，与历史同坐标系）。 */
@Composable
private fun RecordRow(rec: GachaRecord, index: Int, names: NameMaps) {
    val colors = LocalJinlinColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#${index + 1}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
                modifier = Modifier.width(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${names.pool[rec.poolId] ?: "未知(${rec.poolId})"}：[${names.character[rec.itemId] ?: "未知(${rec.itemId})"}]",
                    fontSize = 13.sp,
                    color = colors.onSurface,
                )
                Text(
                    text = "位序 #${rec.positionInBatch}",
                    fontSize = 11.sp,
                    color = colors.onSurfaceDim,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatTime(rec.timestamp),
                fontSize = 11.sp,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 记录页顶栏计数：把「已存历史」单独亮出（区别于本次会话），让「历史有没有加载到」一目了然——
 * 空态时先看这里，别把「没落盘」误当成「记录页不显示历史」。
 */
private fun recordHeader(total: Int, historyCount: Int, sessionCount: Int, overlapped: Boolean): String {
    var s = "共 $total 条 · 已存历史 $historyCount 条"
    if (sessionCount > 0) s += " · 本次 $sessionCount 条"
    if (overlapped) s += " · 已去重"
    return s
}

/** 毫秒时间戳 → 本机时区 "yyyy-MM-dd HH:mm:ss"。 */
private fun formatTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(TIME_FMT)

// 统计页（Tab 3）已迁出到独立文件 StatsScreen.kt（2026-09-14：顶部「用户管理」组 + 统计内容占位）。
// 设置页（Tab 4）已迁出到独立文件 SettingsScreen.kt（S1 实施，见 mobile/docs/09-设置模块设计.md）。