package com.jinlin.gacha.assistant.core

import com.jinlin.gacha.assistant.core.dedup.DedupPipeline
import com.jinlin.gacha.assistant.core.dedup.EventAnomaly
import com.jinlin.gacha.assistant.core.dedup.MissingPages
import com.jinlin.gacha.assistant.core.dedup.ViewIdentity

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

    /** 当前 UI 快照。 */
    @Synchronized
    fun snapshot(): DedupSnapshot {
        val p = pipeline ?: return DedupSnapshot()
        return DedupSnapshot(
            allPoolsSeen = p.allPoolsSeen,
            droppedPages = p.droppedPages,
            rejectedPages = p.rejectedPages().size,
            newCount = p.sessionNewCount,
            parsedCount = p.parsedCount,
            overlapped = p.overlappedHistory,
            // 只统计「全部卡池」视图的缺页（镜像 PC `main_window._coverage_gap_text` 传
            // `missing_pages(view=all)`）：B8 下其它视图本就被丢弃，其缺页不属于采纳数据的
            // 完整性缺口，纳入会误报「数据齐了还提示缺 X 页」（2026-09-14 实机反馈修复）。
            missingPageCount = p.missingPages().filter { ViewIdentity.isAllPools(it.view) }
                .sumOf { it.missing.size },
        )
    }
}
