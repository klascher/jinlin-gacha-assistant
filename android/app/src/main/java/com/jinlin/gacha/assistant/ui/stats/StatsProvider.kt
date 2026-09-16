package com.jinlin.gacha.assistant.ui.stats

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.core.stats.NonWarpStat
import com.jinlin.gacha.assistant.core.stats.PityGroup
import com.jinlin.gacha.assistant.core.stats.PityGroupStat
import com.jinlin.gacha.assistant.core.stats.PoolAssignments
import com.jinlin.gacha.assistant.core.stats.StatsEngine
import com.jinlin.gacha.assistant.core.stats.StatsSummary
import com.jinlin.gacha.assistant.persistence.HistoryEpoch
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.RoleCacheSeed
import com.jinlin.gacha.assistant.persistence.UnknownIdStore
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import java.io.File

/**
 * 统计报表 —— 两页（Tab3 统计页 / Tab1 保底总览）共用的只读计算产物。
 *
 * 依据 2026-09-14 §3.78 拍板口径：**停止抓包后一次性计算，取「账号全量历史 + 本次会话」**。
 * 停止时 [com.jinlin.gacha.assistant.vpn.GachaVpnService] 已把本次会话 `merge` 进
 * `HistoryStore`（写盘），故这里的「历史」天然包含本次会话，无需拼接两个来源。
 *
 * 数据源链路（全部既有，零新增管线）：
 * `RoleCacheSeed.ensureSeeded` → `MetaLoader.read(cacheFile)` → `Meta` →
 * 构造 `StatsEngine` → `loadRecords(HistoryStore.records, replace=true)` →
 * `StatsSummary` / `sharedPityTracking` / 三类分布 / `nonWarpRates`（§3.73 已对拍 120/120）。
 */
data class StatsReport(
    /** 封面摘要（总抽数 / 6星 / 出货池 / 6星率 / 不歪率）；无数据时 total=0、rate=0。 */
    val summary: StatsSummary,
    /** 保底分组追踪（决定展示顺序，hasState 组在前；Tab1 消费）。 */
    val tracking: Map<String, PityGroupStat>,
    /** 卡池分布（池名 → 条数，条数降序）。 */
    val poolDist: Map<String, Int>,
    /** 稀有度分布（星级 → 条数，星级降序）。 */
    val rarityDist: Map<Int, Int>,
    /** 未收录 item_id → 出现次数（下批「未映射 ID」条备用）。 */
    val unmappedIds: Map<String, Int>,
    /** 未收录 pool_id → 出现次数（基于已指派合成后的有效池，故已指派池不在此列）。 */
    val unmappedPoolIds: Map<String, Int>,
    /** 已屏蔽的陌生 item_id 集合（面板「取消屏蔽」用）。 */
    val shielded: Set<String>,
    /** 陌生 pool_id → 保底分组 key（面板「改派/清派」用；缺 = 独立）。 */
    val poolAssignments: Map<String, String>,
    /** 可指派的保底分组（hasState=true，按元数据序；指派下拉候选）。 */
    val assignGroups: List<PityGroup>,
    /** 横幅计数：未知角色(未屏蔽) + 未知池(未指派) — 已处理即不算待办，全处理完横幅消失。 */
    val pendingCount: Int,
    /** 每 hasState 组非歪率。 */
    val nonWarp: Map<String, NonWarpStat>,
    /** 该账号是否有抽卡记录。 */
    val hasData: Boolean,
    /** 当前是否正在抓包（真则统计页顶部显示「停止后更新」提示）。 */
    val isRunning: Boolean,
    /** 元数据版本（"1.0.6" 样式）；缓存缺失时为 null。 */
    val metaVersion: String?,
    /** pool_id → 展示名（未知池回退 `未知(id)`）；供 Tab1 渲染池名。 */
    val poolNames: Map<String, String>,
)

/**
 * 按当前账号构建统计报表。只读：不写盘、不动 `vpn/`、`dedup/`。
 *
 * 关键点：
 * - 读 `ProfileStore` activeId → **账号切换即重算**（`AccountSection` 里切换后本页刷新）；
 * - `running` 只影响 `isRunning` 标志（运行中按口径照常展示已落库历史），不额外叠加；
 * - `remember(activeId)` 缓存 `Meta`（含出厂播种），避免每次重组读盘；
 * - `epoch`（[HistoryEpoch]）：清空 / 还原历史不改 `activeId` 也不启停抓包，若不把它算进
 *   key，本报告会一直返回被清掉之前的旧结果。
 */
@Composable
fun statsReport(context: Context): StatsReport {
    val prof by ProfileStore.get(context).state.collectAsState()
    val activeId = prof.activeId
    val isRunning = GachaVpnService.state.collectAsState().value.running
    // 未映射状态（屏蔽 + 指派）：跨账号全局，变更即重算报告（横幅/面板实时联动）。
    val uState by UnknownIdStore.get(context).state.collectAsState()
    // 历史内容版本（清空 / 还原后 bump）；不参与取数，仅用于让 remember 失效。
    val epoch by HistoryEpoch.state.collectAsState()

    val meta = remember(activeId) {
        RoleCacheSeed.ensureSeeded(context)
        MetaLoader.read(MetaLoader.cacheFile(context.filesDir))
    }

    return remember(meta, activeId, isRunning, uState, epoch) {
        val pityGroups = meta?.pityGroups ?: emptyMap()
        // 指派合成有效池（纯函数，不改元数据表）；engine.pools 含已指派陌生池
        val effectivePools = PoolAssignments.apply(
            pools = meta?.pools ?: emptyMap(),
            pityGroups = pityGroups,
            assignments = uState.assignments,
        )
        val engine = StatsEngine(
            characters = meta?.characters ?: emptyMap(),
            pools = effectivePools,
            pityGroups = pityGroups,
            shielded = uState.shielded,
        )
        val store = HistoryStore(File(context.filesDir, ProfileStore.USERS_DIR), activeId)
        engine.loadRecords(store.records, replace = true)

        val tracking = engine.sharedPityTracking()
        val rarity = engine.rarityDistribution().first
        val poolNames = LinkedHashMap<String, String>()
        engine.pools.forEach { (pid, pool) -> poolNames[pid] = pool.name }

        val unmappedChars = engine.unknownIdStats()
        val unmappedPools = engine.unknownPoolStats()
        val pendingCount =
            unmappedChars.count { it.key !in uState.shielded } + unmappedPools.size

        StatsReport(
            summary = StatsSummary.from(
                total = engine.total,
                tracking = tracking,
                totalPools = engine.pools.size,
            ),
            tracking = tracking,
            poolDist = engine.poolDistribution(),
            rarityDist = rarity,
            unmappedIds = unmappedChars,
            unmappedPoolIds = unmappedPools,
            shielded = uState.shielded,
            poolAssignments = uState.assignments,
            assignGroups = pityGroups.values.filter { it.hasState },
            pendingCount = pendingCount,
            nonWarp = engine.nonWarpRates(tracking),
            hasData = engine.total > 0,
            isRunning = isRunning,
            metaVersion = meta?.version?.takeIf { it.isNotEmpty() },
            poolNames = poolNames,
        )
    }
}