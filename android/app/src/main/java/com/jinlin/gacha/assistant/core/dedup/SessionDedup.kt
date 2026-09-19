package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.GachaRecord

/**
 * 会话内实时位置判重 —— Kotlin 镜像 PC `gacha_exporter/analysis/stats.py::StatsEngine`
 * 的**位置对齐部分**（`begin_session_shift` / `notify_total` / `add`）。
 *
 * 判定分层（与 PC 一致）：
 *
 * - **批次级**：整批记录的有序指纹（`(pool_id,item_id,timestamp)` 序列）已见 → 整批跳过
 *   （只拦「网络重传 / 同帧重复解析」）。
 * - **事件级**：记录 ts 命中历史**完整事件**（size ∈ {1,10}）→ 当场跳过。完整事件的
 *   跨会话判重只看 ts——历史 pos 是旧·页内坐标还是新·绝对坐标都无关，同一次抽卡不会
 *   因坐标系不同变成两次抽卡。
 * - **位置级**（Δ 已设置）：其余记录逐条算 `norm = pos − Δ`，`(ts, norm)` 命中历史
 *   位置 → 当场不收。判重是**实时**的，所以抓包过程中界面计数即为准。
 * - **部分历史事件**（size ∉ {1,10}）：记录**照常收下**（不做位置判重），送到收尾
 *   merge 做整体替换/合并——不收下就到不了收尾。
 *
 * **会话内不做事件内容去重**：十连内同名角色（A4 允许）位置不同，不会被误当成复抓吞掉。
 *
 * **Δ 动态跟随**：Δ 的正确定义是「当前 total − 落库基准 total」，随会话中途抽卡动态
 * 增长（见 [notifyTotal]）。锁死 Δ 会在中途抽卡时让旧记录全部对不上位置、被当成新记录
 * 重复计入（PC 2026-09-13 实测多算 10 条）。
 *
 * 纯 JVM、零 Android 依赖。
 *
 * @param historyRecords 本次会话开始时的**落库历史快照**（不含本次会话新增）
 */
class SessionDedup(private val historyRecords: List<GachaRecord>) {

    /** 位置平移量 Δ（= 当前权威 total − 落库基准 total）。 */
    var shift: Int = 0
        private set

    /** 落库基准 total；null = Δ 未知（首个响应未到 / 异常路径）→ add 保守全收。 */
    private var baselineTotal: Int? = null

    /** 最近一次响应的权威 total（判断会话中途抽卡用）。 */
    private var totalSeen: Int? = null

    /** 历史 + 本会话已收记录的 `(ts, pos)` 集合（pos 已是基准坐标系）。 */
    private val knownPositions = mutableSetOf<Pair<Long, Int>>()

    /** 已见过的批次指纹（同会话整页重发）。 */
    private val seenBatches = mutableSetOf<List<Triple<String, String, Long>>>()

    /** 本次会话新增的记录（收尾时交给合并落库）。 */
    private val session = mutableListOf<GachaRecord>()

    /** 历史中完整事件（size ∈ {1,10}）的 ts（事件级判重，会话起点快照）。 */
    private var completeTs: Set<Long> = emptySet()

    /** 历史中部分事件（size ∉ {1,10}）的 ts。 */
    private var partialTs: Set<Long> = emptySet()

    /** 会话开始时已存在的事件 ts（仅用于 [overlappedHistory] 信号）。 */
    private val sessionStartEvents: Set<Long> = historyRecords.map { it.timestamp }.toSet()

    /** 本次会话经去重后实际收下的条数（>0 表示抓到了新记录）。 */
    var sessionNewCount: Int = 0
        private set

    /** 本次会话是否抓到过历史里已有事件的记录（已翻到历史重叠边界）。 */
    var overlappedHistory: Boolean = false
        private set

    /**
     * 本次会话开始时**是否已有历史**（U5 发现 4，2026-09-18）。
     *
     * 这是 PC `gui/main_window.py:807-815` 重叠自检**三闸里的第一闸**
     * （`had_history` = 会话开始前的历史状态），Android 移植时曾漏掉 —— 于是
     * 「新账号首次抓取 / 清空后重抓」也会被判「与历史无重叠」，而它**根本没有历史可重叠**。
     *
     * 是**会话起点的只读快照**（[historyRecords] 构造后不再变化），**不参与任何判重**：
     * `overlappedHistory` / `knownPositions` / 各条置真路径一律不动。
     */
    val hadHistory: Boolean = historyRecords.isNotEmpty()

    /**
     * 会话开始：记录落库基准 total，Δ 从 0 起算，并重建历史位置/事件索引
     * （镜像 `begin_session_shift`）。
     *
     * 由调用方在会话内**首个响应**到达后调用（此时权威 total 已知）。
     *
     * @param baselineTotalIn 上次落库时的权威 total；历史为空/被清空时传本次 total
     */
    fun beginSessionShift(baselineTotalIn: Int) {
        baselineTotal = baselineTotalIn
        totalSeen = baselineTotalIn
        shift = 0
        knownPositions.clear()
        knownPositions.addAll(historyRecords.map { it.timestamp to it.positionInBatch })
        rebuildEventIndex()
    }

    /**
     * 每次响应后上报权威 total；**会话中途抽卡 → Δ 动态跟随**（镜像 `notify_total`）。
     *
     * total 每涨 k，Δ 立即 +k，**已收记录不受影响**（它们收下时已按当时的 Δ 归一化，
     * 而归一化坐标是恒定的基准坐标）。
     */
    fun notifyTotal(total: Int) {
        val seen = totalSeen ?: return
        if (baselineTotal == null) return
        if (total > seen) {
            val delta = total - seen
            totalSeen = total
            shift += delta
        }
    }

    /**
     * 收下本批记录（实时判重两层：事件级 ts + 位置级 `pos − Δ`），返回收下的条数
     * （镜像 `StatsEngine.add` 的位置对齐分支）。
     */
    fun add(records: List<GachaRecord>): Int {
        if (records.isEmpty()) return 0

        // 批次级去重：整批记录的有序序列指纹（只拦完全相同的重传）
        val fingerprint = records.map { Triple(it.poolId, it.itemId, it.timestamp) }
        if (!seenBatches.add(fingerprint)) return 0

        val accepted = mutableListOf<GachaRecord>()
        var skippedRealtime = 0
        var skippedEvent = 0

        for (rec in records) {
            if (rec.timestamp in completeTs && baselineTotal != null) {
                // 事件级判重：历史完整事件 + Δ 已知 → ts 即身份，整条跳过。
                // Δ 未知时不跳（宁重复勿漏，跨会话碎片续传场景依赖全收）。
                skippedEvent++
                continue
            }
            if (rec.timestamp in partialTs) {
                // 部分历史事件：不做位置判重，全收（收尾 merge 替换/合并）。
                // Δ 已知时同样归一化，保证落库坐标与其余新记录一致。
                overlappedHistory = true
                accepted.add(
                    if (baselineTotal != null) {
                        rec.copy(positionInBatch = rec.positionInBatch - shift)
                    } else {
                        rec
                    }
                )
                continue
            }
            if (baselineTotal == null) {
                // Δ 未知（首个响应未到 / 异常路径）→ 无法归一化，保守全收
                accepted.add(rec)
                continue
            }
            val norm = rec.positionInBatch - shift
            val key = rec.timestamp to norm
            if (!knownPositions.add(key)) {
                skippedRealtime++
                continue
            }
            // **收下即归一化**：落库数据恒在同一坐标系，保底消费序 / 位置连续性判定
            // 才成立；收尾 merge 因此无需再归一化（shift=0 幂等复核）
            accepted.add(rec.copy(positionInBatch = norm))
        }

        session.addAll(accepted)
        sessionNewCount += accepted.size
        if (skippedRealtime > 0 || skippedEvent > 0 ||
            records.any { it.timestamp in sessionStartEvents }
        ) {
            overlappedHistory = true
        }
        return accepted.size
    }

    /**
     * **不做位置判重**直接收下（Q6 路径：C→S 请求完全没抓到 → 视图未知，坐标系未确认）。
     *
     * 只登记本会话新增，**不写 [knownPositions]**——避免把未确认坐标系的位置混进判重表
     * （那样会用它去「命中」真实记录，造成少算）。仍保留**整批指纹**兜底（那是纯内容比对，
     * 不涉及坐标系，只拦字节级重传）。代价是同位置不同内容的重复拦不住，属项目既定的
     * 「宁重复勿漏」取向。
     *
     * @return 实际收下的记录（调用方据此增量展示）
     */
    fun acceptUnchecked(records: List<GachaRecord>): List<GachaRecord> {
        if (records.isEmpty()) return emptyList()
        val fingerprint = records.map { Triple(it.poolId, it.itemId, it.timestamp) }
        if (!seenBatches.add(fingerprint)) return emptyList()
        session.addAll(records)
        sessionNewCount += records.size
        return records.toList()
    }

    /** 本次会话新增的记录（收尾时交给跨会话合并落库）。 */
    fun sessionRecords(): List<GachaRecord> = session.toList()

    /**
     * 违反「事件大小 ∈ {1,10}」不变量的事件（镜像 `suspicious_events`）。
     *
     * **不要在每页 add 后立即调用**——十连首页天然只有 5 条，尚未收齐会误报。
     */
    fun suspiciousEvents(): List<EventAnomaly> =
        anomalies(historyRecords + session) { it.size !in VALID_EVENT_SIZES }

    /**
     * **未定序**事件：事件内位置不连续（镜像 `unresolved_events`）。
     *
     * 比大小异常更严：能抓出「长度恰好 10 但中间缺一格、另有一格重复」这类大小合法
     * 却未定序的事件——保底消费序按位置排，拼反即保底整段错位。
     */
    fun unresolvedEvents(): List<EventAnomaly> =
        anomalies(historyRecords + session) { !isContiguous(it) }

    /** 按 ts 升序，对满足 [predicate] 的事件产出 [EventAnomaly]（单条事件不算异常）。 */
    private fun anomalies(
        records: List<GachaRecord>,
        predicate: (List<GachaRecord>) -> Boolean,
    ): List<EventAnomaly> {
        val out = mutableListOf<EventAnomaly>()
        for ((ts, group) in PositionAlign.groupByTs(records).toSortedMap()) {
            if (group.size <= 1) continue
            if (predicate(group)) out.add(EventAnomaly(ts, group.first().poolId, group.size))
        }
        return out
    }

    /** 事件内位置是否连续成一段（缺中格 / 坐标系不一致即为 false）。 */
    private fun isContiguous(group: List<GachaRecord>): Boolean {
        val positions = group.map { it.positionInBatch }.sorted()
        return positions == List(positions.size) { positions.first() + it }
    }

    /**
     * 按历史记录重建事件完整性索引（事件级判重用）。
     *
     * 完整事件（size ∈ {1,10}）→ [completeTs]（实时整条跳过）；部分事件（size ∉ {1,10}）
     * → [partialTs]（实时照常收下，收尾 merge 做整体替换）。索引是**会话起点快照**：
     * 会话内新收的记录不更新本索引（同会话重复由批指纹 + 位置判重兜住）。
     */
    private fun rebuildEventIndex() {
        val sizes = mutableMapOf<Long, Int>()
        for (r in historyRecords) sizes[r.timestamp] = (sizes[r.timestamp] ?: 0) + 1
        completeTs = sizes.filterValues { it in VALID_EVENT_SIZES }.keys
        partialTs = sizes.keys - completeTs
    }
}
