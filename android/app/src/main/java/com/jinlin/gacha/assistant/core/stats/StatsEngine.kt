package com.jinlin.gacha.assistant.core.stats

import com.jinlin.gacha.assistant.core.GachaRecord

/**
 * 统计引擎（展示层）—— Kotlin 逐字镜像 PC `gacha_exporter/analysis/stats.py::StatsEngine`
 * 的**展示统计半边**。
 *
 * ### 为什么只移植「半边」
 * PC `StatsEngine` 一体两面：
 *  1. **会话/去重半边**（`add` / `begin_session_shift` / `notify_total` / `session_records` /
 *     `load_records` 的会话索引副作用）——移动端**已有等价实现**
 *     （`core/dedup/SessionDedup` + `DedupPipeline` + `persistence/HistoryStore.merge`）。
 *     再移一份会形成**双份去重逻辑**，必然打架，故**不移植**。
 *  2. **展示统计半边**（本类）——对「一份记录列表」纯函数式产出，无会话状态。
 *
 * 同理，PC 的 `suspicious_events()` / `unresolved_events()`（事件大小 / 位置断裂诊断）
 * 也属收尾/去重侧，`DedupPipeline` 已提供，本类**不重复实现**。
 *
 * ### 输入契约
 * 元数据（[characters] / [pools] / [pityGroups] / [shielded]）由**构造参数注入**——
 * 引擎不读文件、不联网，故本包纯 JVM、零 Android 依赖，可 junit 对拍。
 *
 * @param characters item_id → [Character]；**不在表里 = 陌生 ID**（保底按 6 星计）
 * @param pools pool_id → [Pool]
 * @param pityGroups group_id → [PityGroup]；缺省空 → 所有池兜底 solo
 *   （[PoolGroups.resolveGroups] 处理）
 * @param shielded 用户屏蔽的陌生 item_id 集合；这些 ID 在保底计算中按非 6 星处理
 *   （陌生 ID 默认按 6 星，屏蔽则降为 4/5 星）。**仅对未收录 ID 生效**，元数据收录后
 *   真实星级自动覆盖、屏蔽条目自动失效。
 */
class StatsEngine(
    val characters: Map<String, Character>,
    val pools: Map<String, Pool>,
    val pityGroups: Map<String, PityGroup> = emptyMap(),
    val shielded: Set<String> = emptySet(),
) {

    companion object {
        /**
         * 手动补录的垫抽填充 `item_id`（镜像 PC `storage/pool_patches.MANUAL_FILL_ID`）。
         *
         * 判为手动补录的记录**不污染分布展示**（[rarityDistribution] / [poolDistribution] /
         * [unknownIdStats] 跳过），但**参与保底计算**（[sharedPityTracking] 不跳过）。
         */
        const val MANUAL_FILL_ID = "__MANUAL_FILL__"
    }

    /** 当前记录集（[loadRecords] 写入；分析类方法只读）。 */
    val records: MutableList<GachaRecord> = mutableListOf()

    /** 总记录数（镜像 PC `StatsEngine.total`）。 */
    val total: Int get() = records.size

    /**
     * 载入记录。
     *
     * > 相比 PC 的 `load_records`，这里**省去了会话索引副作用**（`_session_start_events` /
     * > `_refresh_known_positions` / `_rebuild_event_index`）——那是去重半边，移动端在
     * > `DedupPipeline` 里。本类只维护 `records`。
     *
     * @param records 待载入记录（通常是落库历史）
     * @param replace `true` 时替换全部记录；`false`（默认，对齐 PC）时追加
     */
    fun loadRecords(records: List<GachaRecord>, replace: Boolean = false) {
        if (replace) this.records.clear()
        this.records.addAll(records)
    }

    /** 清空所有记录（镜像 PC `StatsEngine.clear` 的记录部分）。 */
    fun clear() {
        records.clear()
    }

    // —— 分布类（跳过手动补录；PC `_is_manual_fill` 语义）——

    /**
     * 稀有度分布（镜像 PC `rarity_distribution`）。
     *
     * 跳过手动补录。已收录角色按 `char.rarity` 计数；**未收录 item_id 不计入分布**，
     * 而是收进第二个返回值（`unmapped`）。返回的 map 按稀有度**降序**（对齐
     * PC `dict(sorted(counter.items(), reverse=True))`）。
     *
     * @return `(rarity → 条数, 未收录 item_id 集合)`
     */
    fun rarityDistribution(): Pair<Map<Int, Int>, Set<String>> {
        val counter = LinkedHashMap<Int, Int>()
        val unmapped = LinkedHashSet<String>()
        for (rec in records) {
            if (isManualFill(rec)) continue
            val char = characters[rec.itemId]
            if (char != null) {
                counter[char.rarity] = (counter[char.rarity] ?: 0) + 1
            } else {
                unmapped.add(rec.itemId)
            }
        }
        // sorted(counter.items(), reverse=True)（key 唯一 → 等价于按 rarity 降序）
        val sorted = LinkedHashMap<Int, Int>()
        for (e in counter.entries.sortedByDescending { it.key }) sorted[e.key] = e.value
        return sorted to unmapped
    }

    /**
     * 卡池分布（镜像 PC `pool_distribution`）。
     *
     * 跳过手动补录。按 [Pool.name] 聚合（未知池用 `"未知(<pool_id>)"`），
     * 按条数**降序**、同数保持**首次出现顺序**（对齐 `Counter.most_common()` 的稳定排序）。
     */
    fun poolDistribution(): Map<String, Int> {
        val counter = LinkedHashMap<String, Int>()
        for (rec in records) {
            if (isManualFill(rec)) continue
            val name = pools[rec.poolId]?.name ?: "未知(${rec.poolId})"
            counter[name] = (counter[name] ?: 0) + 1
        }
        val sorted = LinkedHashMap<String, Int>()
        // `most_common()` = 按 value 降序的**稳定**排序 → 同数保持插入序
        for (e in counter.entries.sortedByDescending { it.value }) sorted[e.key] = e.value
        return sorted
    }

    /**
     * 未知 item_id 统计（镜像 PC `unknown_id_stats`）。
     *
     * 返回**所有不在元数据中**的 item_id 及出现次数（客观事实，与是否被 [shielded] 无关）；
     * 跳过手动补录。按首次出现顺序。
     */
    fun unknownIdStats(): Map<String, Int> {
        val counter = LinkedHashMap<String, Int>()
        for (rec in records) {
            if (isManualFill(rec)) continue
            if (!characters.containsKey(rec.itemId)) {
                counter[rec.itemId] = (counter[rec.itemId] ?: 0) + 1
            }
        }
        return counter
    }

    /**
     * 未知 pool_id 统计（镜像 PC `unknown_id_stats` 的卡池半边 / `_unknown_pool_ids`）。
     *
     * 返回**所有不在元数据（含已指派合成的有效池）中**的 pool_id 及出现次数。
     * 基于 [pools] 判定 —— 统计侧构造引擎时已把陌生池指派合成进 [pools]
     * （见 `PoolAssignments.apply`），故**已指派池从这里消失**（记为已处理）。
     * 跳过手动补录；按首次出现顺序。
     */
    fun unknownPoolStats(): Map<String, Int> {
        val counter = LinkedHashMap<String, Int>()
        for (rec in records) {
            if (isManualFill(rec)) continue
            if (!pools.containsKey(rec.poolId)) {
                counter[rec.poolId] = (counter[rec.poolId] ?: 0) + 1
            }
        }
        return counter
    }

    // —— 保底追踪 ——

    /**
     * 各保底分组的共享保底追踪（6 星）（镜像 PC `shared_pity_tracking`）。
     *
     * 同组所有池的记录按 timestamp 升序（旧 -> 新）合并成一条序列扫描；pity 从最近一次
     * 6 星后累计，命中 6 星归 0 并按 `isUp` 翻转小/大保底状态（UP 命中 → 小保底；
     * 歪 → 大保底）。`hasState=false` 的独立组不跑状态机。
     *
     * 每池分解 `perPool.padded` = 该池在组内最近一次 6 星之后的抽数，
     * 组内各池 padded 加和 = 组 `currentPity`。
     *
     * 分组展示顺序：**有状态机组在前、独立组在后**，各自按 `label` 升序、再按 gkey 升序
     * （对齐 PC `_sort_key`）。
     */
    fun sharedPityTracking(): Map<String, PityGroupStat> {
        val (poolToGroup, groupConfigs) = PoolGroups.resolveGroups(pools, pityGroups)

        // 按分组聚合记录
        val groupRecords = LinkedHashMap<String, MutableList<GachaRecord>>()
        for (rec in records) {
            val gkey = poolToGroup[rec.poolId] ?: ("solo:" + rec.poolId)
            groupRecords.getOrPut(gkey) { mutableListOf() }.add(rec)
        }

        // 分组展示顺序：有状态机（共享组）在前，独立组在后；各自按 label、再按 gkey
        val sortedKeys = groupRecords.keys.sortedWith(
            compareBy<String>(
                { gk -> val cfg = groupConfigs[gk]; if (cfg != null && cfg.hasState) 0 else 1 },
                { gk -> groupConfigs[gk]?.label ?: gk },
                { gk -> gk },
            )
        )

        val result = LinkedHashMap<String, PityGroupStat>()
        for (gkey in sortedKeys) {
            val cfg = groupConfigs[gkey]
                ?: PityGroup(id = gkey, label = gkey, hardPity = 70, hasState = false)

            val recs = orderRecordsForPity(groupRecords[gkey]!!)

            var pity = 0
            var state: String? = if (cfg.hasState) "小保底" else null
            val hits = mutableListOf<PityHit>()
            var perPoolPadded = LinkedHashMap<String, Int>()
            val perPoolTotal = LinkedHashMap<String, Int>()
            val perPoolHits = LinkedHashMap<String, MutableList<PerPoolPityHit>>()

            for (rec in recs) {
                pity += 1
                perPoolPadded[rec.poolId] = (perPoolPadded[rec.poolId] ?: 0) + 1
                perPoolTotal[rec.poolId] = (perPoolTotal[rec.poolId] ?: 0) + 1

                val char = characters[rec.itemId]
                val isSix = if (char != null) {
                    char.rarity == 6
                } else {
                    // 陌生 ID：默认按 6 星；被屏蔽则按 4/5 星（不触发归零）
                    rec.itemId !in shielded
                }
                if (!isSix) continue

                val pool = pools[rec.poolId] ?: Pool.default(rec.poolId)
                val name = char?.name ?: "未知(${rec.itemId})"
                val isUp = if (char != null) {
                    pool.isFeatured && pool.upCharacter == char.name
                } else {
                    // 陌生 6 星：常驻歪角都已在元数据里，陌生的必是新角色 = UP 命中 → 小保底
                    pool.isFeatured
                }
                hits.add(
                    PityHit(
                        pity = pity,
                        ts = rec.timestamp,
                        poolId = rec.poolId,
                        poolName = pool.name,
                        name = name,
                        isUp = isUp,
                    )
                )
                perPoolHits.getOrPut(rec.poolId) { mutableListOf() }.add(
                    PerPoolPityHit(pity = pity, ts = rec.timestamp, name = name, isUp = isUp)
                )
                // 命中 6 星：组保底归 0，各池 padded 同步归 0
                pity = 0
                perPoolPadded = LinkedHashMap()
                // 状态翻转（仅 hasState 组）
                if (cfg.hasState) state = if (isUp) "小保底" else "大保底"
            }

            val perPool = LinkedHashMap<String, PerPoolStat>()
            for (pid in perPoolTotal.keys) {
                val ph = perPoolHits[pid]
                perPool[pid] = PerPoolStat(
                    padded = perPoolPadded[pid] ?: 0,
                    total = perPoolTotal[pid] ?: 0,
                    sixCount = ph?.size ?: 0,
                    upCount = ph?.count { it.isUp } ?: 0,
                    hits = ph ?: emptyList(),
                )
            }

            result[gkey] = PityGroupStat(
                label = cfg.label,
                hardPity = cfg.hardPity,
                hasState = cfg.hasState,
                state = state,
                total = recs.size,
                currentPity = pity,
                distanceToPity = maxOf(0, cfg.hardPity - pity),
                sixCount = hits.size,
                upCount = hits.count { it.isUp },
                hits = hits,
                perPool = perPool,
                poolIds = poolToGroup.filterValues { it == gkey }.keys.sorted(),
            )
        }
        return result
    }

    /**
     * 每池/每组不歪率（镜像 PC `non_warp_rates`）。
     *
     * 对每个 `hasState` 分组，按组内 hits 全局时间序（旧 -> 新，与
     * [sharedPityTracking] 排序一致）**重放小/大保底状态机**，给每次 6 星命中标记当时
     * 的保底态：
     *  - 小保底命中 UP（50/50 胜）→ 计入 `wins` 与 `allUp`；
     *  - 大保底必出 UP → 只计入 `allUp`（保底补偿，不算运气）；
     *  - 歪（小保底非 UP）→ 两者都不计。
     *
     * 按 hits 的 `poolId` 分桶，即「UP 出现在哪个池就算哪个池」；跨池继承的大保底必出
     * 计入**承接池** `allUp`，不搬回产生它的池。组/合计 = `Σwins / ΣallUp`。`rate=null`
     * 表示 `allUp == 0`（无 UP 数据，如整池全歪）。非 `hasState` 组不产出。
     *
     * @param tracking [sharedPityTracking] 的返回；缺省时内部调用一次
     */
    fun nonWarpRates(tracking: Map<String, PityGroupStat>? = null): Map<String, NonWarpStat> =
        computeNonWarpRates(tracking ?: sharedPityTracking())

    // —— 排序（保底消费序）——

    /**
     * 保底消费序：**旧 -> 新**（镜像 PC `_order_records_for_pity`）。
     *
     * 事件之间按 `timestamp` 升序；事件内部按 [orderWithinEvent]。
     */
    fun orderRecordsForPity(records: List<GachaRecord>): List<GachaRecord> {
        val byTs = LinkedHashMap<Long, MutableList<GachaRecord>>()
        for (rec in records) byTs.getOrPut(rec.timestamp) { mutableListOf() }.add(rec)
        val ordered = mutableListOf<GachaRecord>()
        for (ts in byTs.keys.sorted()) {
            ordered.addAll(orderWithinEvent(ts, byTs[ts]!!))
        }
        return ordered
    }

    /**
     * 同一事件（同一 ts）内的旧 -> 新序：**位置降序**（镜像 PC `_order_within_event`）。
     *
     * `pos` 越小越新（A5）→ 位置**降序**即「大 pos 先 = 旧先 = 旧 -> 新」。若不这样排，
     * 十连内最新一条会被排到最前、保底计数错位（PC 原注释）。`batch_seq` 仅作次级兼容键
     * （新数据恒 0）。单条事件直接原样返回。
     */
    fun orderWithinEvent(ts: Long, group: List<GachaRecord>): List<GachaRecord> {
        if (group.size == 1) return listOf(group[0])
        return group.sortedWith(
            compareByDescending<GachaRecord> { it.batchSeq }
                .thenByDescending { it.positionInBatch }
        )
    }

    /** 是否为手动补录的垫抽填充记录（镜像 PC `_is_manual_fill`）。 */
    private fun isManualFill(rec: GachaRecord): Boolean = rec.itemId == MANUAL_FILL_ID
}

/**
 * 不歪率状态机重放（镜像 PC `non_warp_rates` 对 tracking 的消费部分）。
 *
 * 抽成**顶层纯函数**，让 [StatsEngine.nonWarpRates] 与 [StatsSummary] 共用同一份实现，
 * 避免逻辑重复。只消费 [StatsEngine.sharedPityTracking] 的输出，**不重算 tracking**。
 *
 * 对每个 `hasState` 分组，按组内 hits 全局时间序重放小/大保底状态机：
 *  - 小保底命中 UP（50/50 胜）→ 计入 `wins` 与 `allUp`；
 *  - 大保底必出 UP → 只计入 `allUp`（保底补偿，不算运气）；
 *  - 歪（小保底非 UP）→ 两者都不计。
 * 按 hits 的 `poolId` 分桶（「UP 出现在哪个池就算哪个池」）；非 `hasState` 组不产出。
 */
internal fun computeNonWarpRates(
    tracking: Map<String, PityGroupStat>,
): Map<String, NonWarpStat> {
    val result = LinkedHashMap<String, NonWarpStat>()
    for ((gkey, info) in tracking) {
        if (!info.hasState) continue
        var wins = 0
        var allUp = 0
        val perWins = LinkedHashMap<String, Int>()
        val perAllUp = LinkedHashMap<String, Int>()
        var state = "小保底" // 初始态与 sharedPityTracking 状态机一致

        for (h in info.hits) {
            val isUp = h.isUp
            if (state == "小保底" && isUp) {
                wins += 1
                perWins[h.poolId] = (perWins[h.poolId] ?: 0) + 1
            }
            if (isUp) {
                allUp += 1
                perAllUp[h.poolId] = (perAllUp[h.poolId] ?: 0) + 1
            }
            state = if (isUp) "小保底" else "大保底"
        }

        val perPool = LinkedHashMap<String, PerPoolNonWarp>()
        for (pid in perAllUp.keys) {
            val w = perWins[pid] ?: 0
            val a = perAllUp[pid] ?: 0
            perPool[pid] = PerPoolNonWarp(
                wins = w,
                allUp = a,
                rate = if (a != 0) w.toDouble() / a else null,
            )
        }

        result[gkey] = NonWarpStat(
            wins = wins,
            allUp = allUp,
            rate = if (allUp != 0) wins.toDouble() / allUp else null,
            perPool = perPool,
        )
    }
    return result
}

// —— 输出数据结构（对应 PC 各方法的 dict 形状）——
/** 一次 6 星命中（[StatsEngine.sharedPityTracking] 的 `hits` 元素）。 */
data class PityHit(
    val pity: Int,
    val ts: Long,
    val poolId: String,
    val poolName: String,
    val name: String,
    val isUp: Boolean,
)

/** 每池 hits 元素（对应 PC `per_pool[pid]["hits"]`，比顶层 hits 少 `pool_id`/`pool_name`）。 */
data class PerPoolPityHit(
    val pity: Int,
    val ts: Long,
    val name: String,
    val isUp: Boolean,
)

/** 单池保底分解（对应 PC `per_pool[pid]`）。 */
data class PerPoolStat(
    val padded: Int,
    val total: Int,
    val sixCount: Int,
    val upCount: Int,
    val hits: List<PerPoolPityHit>,
)

/** 一个保底分组的追踪结果（对应 PC `shared_pity_tracking()[group_id]`）。 */
data class PityGroupStat(
    val label: String,
    val hardPity: Int,
    val hasState: Boolean,
    val state: String?, // "小保底" | "大保底" | null（非状态机组）
    val total: Int,
    val currentPity: Int,
    val distanceToPity: Int,
    val sixCount: Int,
    val upCount: Int,
    val hits: List<PityHit>,
    val perPool: Map<String, PerPoolStat>,
    val poolIds: List<String>,
)

/** 单池不歪率（对应 PC `non_warp_rates()[g][per_pool][pid]`）。 */
data class PerPoolNonWarp(
    val wins: Int,
    val allUp: Int,
    val rate: Double?, // null = allUp == 0
)

/** 一个分组的不歪率（对应 PC `non_warp_rates()[group_id]`）。 */
data class NonWarpStat(
    val wins: Int,
    val allUp: Int,
    val rate: Double?, // null = allUp == 0
    val perPool: Map<String, PerPoolNonWarp>,
)
