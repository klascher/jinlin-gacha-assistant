package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.GachaRecord

/**
 * 位置对齐去重（跨会话合并）—— Kotlin 逐字镜像 PC
 * `gacha_exporter/analysis/pos_align.py`。
 *
 * 核心一句话：**把「内容模式去重」换成「位置模式去重」**——同一视图内
 * `positionInBatch` 是记录在列表里的绝对位置，同名记录位置不同，所以位置即身份，
 * 天然不会吞同名第二份。
 *
 * 三件事：
 * 1. **单次抓包全收**（依据 B3 页互斥 + C5 页缓存，单次会话内不会有重复；整页指纹
 *    兜底在 [SessionDedup]）。
 * 2. **跨会话按位置去重**：归一化到历史坐标系后 `(ts, pos)` 相同即为重复。
 * 3. **Δ（位置平移量）= 权威 total 增量**（[inferShiftFromTotal]）。
 *
 * > 为什么不用「重叠段内容匹配」推 Δ：十连内**允许同名重复**（A4），内容相同不代表
 * > 是同一条记录。反例：`AABCD` 与 `BCDAE` 两段互补（真值 Δ=0），内容比对会得出
 * > Δ=3（B/C/D 三项"一致"）→ 归一化后 3 条撞上历史 → **漏记 3 条**。
 *
 * **安全阀**：历史里同一事件的位置若断裂（B8 之前混入多视图坐标系的脏数据），该事件
 * 不做位置判重、退化为整段追加并登记 `fallbackEvents`（宁可重复，绝不用不可比的
 * 坐标系去判重）；合并后事件大小 ∉ {1,10} 一律进 `suspicious` 报警。
 *
 * 纯 JVM、零 Android 依赖，可在 junit 里对拍 `tests/test_pos_align.py`。
 */

/** 合法事件大小：1 = 单抽、10 = 十连（规则 A1）。 */
val VALID_EVENT_SIZES: List<Int> = listOf(1, 10)

/** 一个事件的大小异常：`(ts, pool_id, size)`，size ∉ {1,10}。 */
data class EventAnomaly(val ts: Long, val poolId: String, val size: Int)

/**
 * 一次合并的结果与诊断（供调用方落库 + 报警），镜像 PC `MergeReport`。
 */
class MergeReport(val shift: Int = 0) {
    /** 应追加到记录列表的记录（调用方负责 extend + 落库）。 */
    val added = mutableListOf<GachaRecord>()

    /** 与历史有交集、按位置判重处理过的事件数。 */
    var mergedEvents: Int = 0

    /** 历史里没有的新事件数（新抽卡）。 */
    var newEvents: Int = 0

    /** 位置命中历史、判为重复而跳过的条数。 */
    var skipped: Int = 0

    /** 事件级判重跳过的**事件数**（历史完整事件重抓，ts 即身份）。 */
    var eventSkipped: Int = 0

    /** 被新完整事件整体替换的历史**部分事件** ts（调用方负责移除历史旧记录）。 */
    val replacedEvents = mutableListOf<Long>()

    /** 合并后大小 ∉ {1,10} 的事件（绝不静默）。 */
    val suspicious = mutableListOf<EventAnomaly>()

    /** 历史位置断裂、退化为整段追加的事件 ts（脏数据信号）。 */
    val fallbackEvents = mutableListOf<Long>()

    /** 新增条数。 */
    val totalAdded: Int get() = added.size
}

object PositionAlign {

    /** 按 timestamp 分组（一个 timestamp = 一次抽卡动作 = 一个事件，镜像 `group_by_ts`）。 */
    fun groupByTs(records: List<GachaRecord>): Map<Long, List<GachaRecord>> =
        records.groupBy { it.timestamp }

    /**
     * 由权威 total 推断位置平移量 Δ（镜像 `infer_shift_from_total`）。
     *
     * Δ = 本次 total − 上次落库时的 total。列表头部每次新增抽卡都会让历史记录的位置
     * 整体后移同样的条数，所以这个差值就是归一化时要减掉的后移量。
     *
     * 任一端缺失（首次抓包 / 清空或还原后重新起算）或 total 变少（清空/还原后抓得
     * 更少）→ 0：此时无历史可合并，Δ 取 0 最安全。
     */
    fun inferShiftFromTotal(currentTotal: Int?, previousTotal: Int?): Int {
        if (currentTotal == null || previousTotal == null) return 0
        return maxOf(0, currentTotal - previousTotal)
    }

    /**
     * 把本次抓到的 [new] 按位置并入 [history]，返回应新增的记录与诊断
     * （镜像 `merge_by_position`）。
     *
     * 判定规则（逐事件；`hits` = 新记录 `pos − shift` 命中历史位置集合的条数）：
     *
     * | 情形 | 处理 |
     * |---|---|
     * | 历史里没有该 ts | 全新事件 → 全部收下 |
     * | 历史十连完整（size=10） | 事件级判重：ts 即身份 → 整事件跳过 |
     * | 历史单抽 + 新事件单条 + hits=0 | 事件级判重（坐标系不可比）→ 跳过 |
     * | 历史部分事件 + 新完整事件 + hits=0 | 整体替换（登记 [MergeReport.replacedEvents]） |
     * | 历史部分事件、位置断裂 | 整段追加 + 登记 [MergeReport.fallbackEvents] |
     * | 其余 | 逐槽：`pos − shift` 命中历史位置 → 跳过；否则收下 |
     *
     * @param history 历史记录（已落库的全部记录）
     * @param new 本次抓到的记录（单次会话内已全收、且已按会话 Δ 归一化）
     * @param shift 位置平移量 Δ；生产路径下恒 0（归一化在 [SessionDedup] 实时完成）
     */
    fun mergeByPosition(
        history: List<GachaRecord>,
        new: List<GachaRecord>,
        shift: Int = 0,
    ): MergeReport {
        val report = MergeReport(shift)
        val historyGroups = groupByTs(history)

        for ((ts, newRecs) in groupByTs(new)) {
            val histRecs = historyGroups[ts]
            val merged: MutableList<GachaRecord>
            if (histRecs == null || histRecs.isEmpty()) {
                report.added.addAll(newRecs)
                report.newEvents++
                merged = newRecs.toMutableList()
            } else {
                val known = histRecs.map { it.positionInBatch }.toMutableSet()
                val hits = newRecs.count { (it.positionInBatch - shift) in known }
                when {
                    // 事件级判重：历史十连完整，ts 即身份，整事件跳过。
                    histRecs.size == 10 -> {
                        report.eventSkipped++
                        merged = histRecs.toMutableList()
                    }
                    // 事件级判重：历史单抽 + 单条重抓 + 零命中（坐标系不可比）。
                    histRecs.size == 1 && newRecs.size == 1 && hits == 0 -> {
                        report.eventSkipped++
                        merged = histRecs.toMutableList()
                    }
                    // 历史部分事件 + 新完整事件 + 零命中 → 整体替换（子集关系，无损失）。
                    histRecs.size !in VALID_EVENT_SIZES &&
                        newRecs.size in VALID_EVENT_SIZES && hits == 0 -> {
                        report.replacedEvents.add(ts)
                        report.added.addAll(newRecs)
                        report.mergedEvents++
                        merged = newRecs.toMutableList()
                    }
                    // 历史坐标系不可比（B8 前混视图遗留）→ 不做位置判重，宁重复勿漏抓。
                    !positionsComparable(histRecs) -> {
                        report.fallbackEvents.add(ts)
                        report.added.addAll(newRecs)
                        merged = (histRecs + newRecs).toMutableList()
                    }
                    else -> merged = mergeBySlots(report, histRecs, newRecs, shift)
                }
            }
            checkEvent(ts, merged)?.let { report.suspicious.add(it) }
        }
        return report
    }

    /**
     * 既有逐槽判定：`new.pos − shift` 命中历史位置 → 跳过；否则收下。
     * 同一事件内的位置集合就地扩充，同批内的重复位置也会被拦。
     */
    private fun mergeBySlots(
        report: MergeReport,
        histRecs: List<GachaRecord>,
        newRecs: List<GachaRecord>,
        shift: Int,
    ): MutableList<GachaRecord> {
        val known = histRecs.map { it.positionInBatch }.toMutableSet()
        val merged = histRecs.toMutableList()
        for (rec in newRecs) {
            val normalized = rec.positionInBatch - shift
            if (normalized in known) {
                report.skipped++
                continue
            }
            known.add(normalized)
            report.added.add(rec)
            merged.add(rec)
        }
        report.mergedEvents++
        return merged
    }

    /**
     * 同一事件内的位置是否处于**同一坐标系**（可以按位置判重），镜像
     * `_positions_comparable`。
     *
     * 两条判据（都来自规则文件）：
     * 1. **位置不重复**：同一坐标系下一条记录只有一个位置，出现重复说明混入了另一个
     *    坐标系的记录（B8 之前抓包混多视图时实测有 50 个 pos 值重复）。
     * 2. **跨度 ≤ 10**：一个事件最多 10 条、占 10 个连续位置（B1、A1），跨度超过 10
     *    必然是两个坐标系拼在一起。
     *
     * 注意**不能要求位置连续**：历史可能只抓到事件的一部分（如十连只抓到第 1 抽与
     * 第 6 抽，pos 100 与 105），这种「有洞但同坐标系」是合法且常见的，强制连续会把
     * 它误判成脏数据、退化成整段追加而产生重复。
     */
    fun positionsComparable(records: List<GachaRecord>): Boolean {
        if (records.size <= 1) return true
        val positions = records.map { it.positionInBatch }
        if (positions.toSet().size != positions.size) return false
        return (positions.max() - positions.min()) <= 10
    }

    /** 合并后事件大小是否合法；不合法返回 [EventAnomaly]。 */
    private fun checkEvent(ts: Long, merged: List<GachaRecord>): EventAnomaly? {
        val size = merged.size
        if (size in VALID_EVENT_SIZES) return null
        return EventAnomaly(ts, merged.firstOrNull()?.poolId ?: "", size)
    }
}
