package com.jinlin.gacha.assistant.core

import com.jinlin.gacha.assistant.core.dedup.DedupPipeline
import com.jinlin.gacha.assistant.core.dedup.EventAnomaly
import com.jinlin.gacha.assistant.core.dedup.GapSummary
import com.jinlin.gacha.assistant.core.dedup.MissingPages
import com.jinlin.gacha.assistant.core.dedup.ViewIdentity
import com.jinlin.gacha.assistant.core.dedup.ViewTracker

/**
 * 去重会话的只读快照（供记录页提示与收尾日志；纯数据、零 Android 依赖）。
 */
data class DedupSnapshot(
    /** 本场是否抓到过「全部卡池」视图的请求（false 且 [droppedPages] > 0 = 用户没翻对列表）。 */
    val allPoolsSeen: Boolean = false,
    /** 被 B8 丢弃的非「全部卡池」页数。 */
    val droppedPages: Int = 0,
    /** 被限流拒绝的页数（该次响应无数据，客户端会自动重发）。 */
    val rejectedPages: Int = 0,
    /** 本场收下的记录条数（已去重归一化）。 */
    val newCount: Int = 0,
    /** 本场从“被采纳”响应解析出的记录条数（含被判重跳过的旧记录）；>0 表示正在读到数据。 */
    val parsedCount: Int = 0,
    /** 本场是否翻到过与历史重叠的边界。 */
    val overlapped: Boolean = false,
    /** 按权威 total 推算仍缺的页数（翻到底可补齐）。 */
    val missingPageCount: Int = 0,

    // —— U5（2026-09-18）健康指示所需字段；全部是**只读派生**，不改任何判定 ——

    /**
     * 会话开始前**是否已有历史**（发现 4 的闸）。
     *
     * PC `main_window.py:807-815` 的重叠自检有三道闸，这是第一道，Android 移植时漏掉 ⇒
     * 新账号首抓 / 清空后重抓也会被判「与历史无重叠」。所有「与历史相关」的判据先过它。
     */
    val hadHistory: Boolean = false,

    /**
     * 是否存在 **A 类首屏缺口**（缺口早于本场已见的最小 offset）。
     *
     * 判据是 O(1) 的增量值（`ViewTracker.minSeenOffset > 0` 且该视图有权威 total）——
     * 这是「第一页丢了却零提示」那个缺陷的正面判据：**继续翻拿不到，只能重新登录**。
     */
    val hasHeadGap: Boolean = false,

    /** 是否存在 **B 类中段空洞**（缺口夹在已见页中间）—— 翻回去就能补，与历史无关。 */
    val hasMidGap: Boolean = false,

    /** A 类缺口 offset（升序；**落盘口径**，UI 层换算成「第 N 页」）。 */
    val headGapPages: List<Int> = emptyList(),

    /** B 类缺口 offset（升序）。 */
    val midGapPages: List<Int> = emptyList(),

    /** C 类缺口 offset（升序；「尾部未翻」，继续往下翻即可）。 */
    val tailGapPages: List<Int> = emptyList(),

    /** 「已拿到 N/M 页」的 **N**（全部卡池视图、口径与 PC `missing_pages` 的 seenCount 一致）。 */
    val gapSeenPages: Int = 0,

    /** 「已拿到 N/M 页」的 **M**（由服务器权威 total 推出；0 = 本场没拿到 total，判不出缺口）。 */
    val gapExpectedPages: Int = 0,

    /** 本场已见的最小 offset；**-1 = 该视图一页都没见**（判不出首屏缺口）。 */
    val minSeenOffset: Int = -1,

    /** 本场已见的最大 offset；-1 = 未知。 */
    val maxSeenOffset: Int = -1,

    /** 服务器权威总抽数（响应 field2）；**0 = 本场没拿到**（⇒ 判不出缺口）。子页「服务器记录 N 条」用。 */
    val authoritativeTotal: Int = 0,
)

/**
 * 抽卡记录消费者 —— [FrameConsumer] 的去重实现：双向分流后交给 [DedupPipeline]，把
 * 「本次会话新增（已去重、已归一化）」的记录交给调用方（记录页展示 / 收尾落库）。
 *
 * 数据链路（镜像 PC `main_window.on_packet`）：
 * ```
 * C→S 帧 → pipeline.onRequest   登记 seq→(view, offset)
 * S→C 帧 → pipeline.onResponse  noteResponse（被拒页定位）→ B8 过滤 → 实时位置判重
 * ```
 *
 * **线程模型（重要）**：`onFrame` 由 `ReassemblyWorker` 的**唯一消费线程**调用，故流水线内部
 * 无锁；而**会话收尾落库发生在另一线程**（service 的 `cleanup`）。为消除这条跨线程读写竞态，
 * 本类所有会触碰流水线状态的方法一律 `@Synchronized`（锁粒度小、无阻塞 IO，热路径开销可忽略）。
 *
 * `GachaRecord` 与流水线均为纯 JVM；本类只额外经 [CaptureLog] 打点。
 *
 * @param onRecords 本页收下的新增记录（增量，供界面追加）
 * @param onActivity 去重相关状态变化时的轻量回调（供 service 刷新 [DedupSnapshot]）
 */
class RecordFrameConsumer(
    private val onRecords: (List<GachaRecord>) -> Unit,
    private val onActivity: (() -> Unit)? = null,
    private val tag: String = "GachaRec",
) : FrameConsumer {

    private var pipeline: DedupPipeline? = null
    private var droppedLogged = 0
    private var rejectedLogged = 0

    /**
     * 开抓：建本次会话（历史快照 + Δ 基准）并重置视图追踪。
     * 由 service 在 `startVpn` 调用；未调用时 [onFrame] 直接忽略（不在抓包，不该有帧）。
     */
    @Synchronized
    fun beginSession(historyRecords: List<GachaRecord>, baselineTotal: Int?) {
        pipeline = DedupPipeline(historyRecords, baselineTotal)
        droppedLogged = 0
        rejectedLogged = 0
    }

    @Synchronized
    override fun onFrame(frame: Frame, direction: Direction) {
        val pipe = pipeline ?: return

        if (direction == Direction.CLIENT_TO_SERVER) {
            // C→S：切池/翻页请求 → 登记 seq→视图（非切池请求返回 null，静默忽略）
            pipe.onRequest(frame.body)
            return
        }

        val out = pipe.onResponse(frame.body)
        when (out.kind) {
            DedupPipeline.Outcome.Kind.NOT_GACHA -> return // 与去重无关的帧：不打点、不回调

            DedupPipeline.Outcome.Kind.REJECTED -> {
                rejectedLogged++
                if (rejectedLogged == 1 || rejectedLogged % 20 == 0) {
                    CaptureLog.w(
                        tag,
                        "页被限流拒绝（第 $rejectedLogged 页）：视图 ${out.view} offset=${out.offset}" +
                            "（客户端会自动重发，若持续出现请放慢翻页）",
                    )
                }
            }

            DedupPipeline.Outcome.Kind.DROPPED_VIEW -> {
                droppedLogged++
                if (droppedLogged == 1 || droppedLogged % 20 == 0) {
                    CaptureLog.w(
                        tag,
                        "丢弃非「全部卡池」视图页（第 $droppedLogged 页）：视图 ${out.view}" +
                            "（去重只采纳全部卡池＝其它视图的超集）",
                    )
                }
            }

            DedupPipeline.Outcome.Kind.NO_RECORDS -> {
                // 全部卡池页但本次无记录（末页越界 / 空页）：正常路径，不打点
            }

            DedupPipeline.Outcome.Kind.ACCEPTED -> {
                if (out.view == null) {
                    CaptureLog.w(
                        tag,
                        "视图未知（C→S 请求未抓到）→ 直收 ${out.kept.size} 条不做位置判重" +
                            "（宁重复勿漏；请确认抓包期间未断连）",
                    )
                }
                if (out.kept.isNotEmpty()) {
                    onRecords(out.kept)
                    CaptureLog.i(
                        tag,
                        "收下 ${out.kept.size} 条（本页解析 ${out.parsed} 条，视图 ${out.view}，" +
                            "offset=${out.offset}，total=${out.total}）",
                    )
                }
            }
        }
        onActivity?.invoke()
    }

    // —— 供 service 查询 / 收尾落库（全部与 onFrame 互斥）——

    /** 本次会话收下的记录（已归一化），收尾时交给 `HistoryStore.merge`。 */
    @Synchronized
    fun sessionRecords(): List<GachaRecord> = pipeline?.sessionRecords() ?: emptyList()

    /** 本会话各视图权威 total 快照。 */
    @Synchronized
    fun viewTotals(): Map<String, Int> = pipeline?.viewTotals() ?: emptyMap()

    /** 缺页报告（收尾时提示「还缺哪几页」）。 */
    @Synchronized
    fun missingPages(): List<MissingPages> = pipeline?.missingPages() ?: emptyList()

    /** 被限流拒绝的页。 */
    @Synchronized
    fun rejectedPages(): List<Pair<String, Int>> = pipeline?.rejectedPages() ?: emptyList()

    /** 大小 ∉ {1,10} 的事件。 */
    @Synchronized
    fun suspiciousEvents(): List<EventAnomaly> = pipeline?.suspiciousEvents() ?: emptyList()

    /** 事件内位置不连续的事件。 */
    @Synchronized
    fun unresolvedEvents(): List<EventAnomaly> = pipeline?.unresolvedEvents() ?: emptyList()

    /** 当前 UI 快照（含 U5 健康指示所需的只读派生字段）。 */
    @Synchronized
    fun snapshot(): DedupSnapshot {
        val p = pipeline ?: return DedupSnapshot()

        // —— 缺页报告：只统计「全部卡池」视图 ——
        // 镜像 PC `main_window._coverage_gap_text` 传 `missing_pages(view=all)`）：B8 下其它
        // 视图本就被丢弃，其缺页不属于采纳数据的完整性缺口，纳入会误报「数据齐了还提示缺 X 页」
        // （2026-09-14 实机反馈修复）。
        //
        // ⚠️ `missingPages()` 是 O(应有页数)，但它**本就在本方法里调用**（既有行为；§17.5
        // 明确不改其算法）⇒ 下面的三分类**复用同一份结果**，本批**零新增** O(N) 调用。
        val gaps = p.missingPages().filter { ViewIdentity.isAllPools(it.view) }
        val gap = gaps.firstOrNull()

        // 「全部卡池」的权威 total。key 可能是 "0@0"，老文件是裸 "0" ⇒ 用 isAllPools 判，不写死。
        val totals = p.viewTotals()
        val allPoolsKey = totals.keys.firstOrNull { ViewIdentity.isAllPools(it) }
        val total = allPoolsKey?.let { totals[it] } ?: 0
        val expected = ViewIdentity.expectedPageOffsets(total)
        val missing = gap?.missing ?: emptyList()

        // 三分类（A 首屏 / B 中段 / C 尾部）：`classifyGap` 只用到 `expected ∩ seen`，
        // 而已知 `missing = expected − seen` ⇒ 交集可由 `expected − missing` 精确还原，
        // 不必再遍历一次 seenPages。
        val missingSet = missing.toSet()
        val summary = if (expected.isEmpty()) {
            GapSummary(emptyList(), emptyList(), emptyList(), emptyList())
        } else {
            ViewTracker.classifyGap(expected, expected.filterNot { it in missingSet }.toSet())
        }

        return DedupSnapshot(
            allPoolsSeen = p.allPoolsSeen,
            droppedPages = p.droppedPages,
            rejectedPages = p.rejectedPages().size,
            newCount = p.sessionNewCount,
            parsedCount = p.parsedCount,
            overlapped = p.overlappedHistory,
            missingPageCount = gaps.sumOf { it.missing.size },
            hadHistory = p.hadHistory,
            // hasHeadGap / headGapPages **同源**（都取 summary）：若各算一份，界面显示的
            // 缺口明细与「有没有首屏缺口」会漂移。ViewTracker 的 O(1) minSeenOffset 仍
            // 输出到快照供诊断展示，其与 summary 的一致性由单测守卫。
            hasHeadGap = summary.hasHead,
            hasMidGap = summary.hasMid,
            headGapPages = summary.head,
            midGapPages = summary.mid,
            tailGapPages = summary.tail,
            gapSeenPages = if (expected.isEmpty()) 0 else expected.size - missing.size,
            gapExpectedPages = expected.size,
            authoritativeTotal = total,
            minSeenOffset = allPoolsKey?.let { p.viewTracker.minSeenOffset(it) } ?: -1,
            maxSeenOffset = allPoolsKey?.let { p.viewTracker.maxSeenOffset(it) } ?: -1,
        )
    }
}
