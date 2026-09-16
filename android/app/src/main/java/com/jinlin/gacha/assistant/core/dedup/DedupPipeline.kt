package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.GachaRecord
import com.jinlin.gacha.assistant.core.RecordParser

/**
 * 会话内去重流水线（纯 JVM）—— 把 S1 的五件套编排成 PC `main_window.on_packet` 的等价链路：
 *
 * ```
 * C→S 请求 → ViewTracker 登记 seq→(view, offset)
 * S→C 响应 → noteResponse（必须在任何早退之前）→ B8 过滤 → 解析记录 → SessionDedup 实时判重
 * ```
 *
 * **为什么独立成类而不写进 `RecordFrameConsumer`**：本类零 Android 依赖（不碰 CaptureLog /
 * logcat），使「请求与响应交错 → 到底收下哪些记录」这条最容易出错的链路可在纯 JVM 单测里
 * 逐例断言；消费者退化为「打点 + 转发」的薄壳。
 *
 * 与 PC 的三处一致性要点（对照 `analysis/stats.py` / `analysis/pool_view.py`）：
 *
 * 1. **`noteResponse` 必须在最前**：被限流拒绝的响应是 8B 截断帧、不含任何记录，若放在
 *    「无记录提前返回」之后，被拒页永远检测不到（用户对频繁限流无感）。
 * 2. **B8（仅采纳「全部卡池」）**：非全部卡池视图的页整页丢弃——全部卡池是其它视图的超集，
 *    只保留它，位置坐标才唯一可比（失去多视图冗余后由缺口报告兜底）。
 * 3. **Δ 动态跟随**：`beginSessionShift` 定基准后，每次响应 `notifyTotal`；会话中途抽卡
 *    使 total 涨 k 时 Δ 立即 += k，否则旧记录会全部对不上位置而被重复计入（PC 实测多算 10 条）。
 *
 * **线程约定**：由 `ReassemblyWorker` 的唯一消费线程调用；收尾落库发生在另一线程，故调用方
 * 需自行加锁（见 `RecordFrameConsumer` 的 `@Synchronized`）。
 *
 * @param historyRecords 会话开始时的**落库历史快照**（Δ 归一的判重基准，不含本次会话新增）
 * @param baselineTotal 历史落库时的「全部卡池」权威 total；null = 无历史/未知 → Δ 从 0 起算
 */
class DedupPipeline(
    private val historyRecords: List<GachaRecord>,
    private val baselineTotal: Int?,
) {

    /** 一条 S→C 帧的处理结果（消费者据此打点，单测据此断言）。 */
    data class Outcome(
        val kind: Kind,
        val view: String? = null,
        val offset: Int = -1,
        val parsed: Int = 0,
        val kept: List<GachaRecord> = emptyList(),
        val total: Int = -1,
    ) {
        enum class Kind {
            /** 非抽卡信封 / 结构不符：与去重无关。 */
            NOT_GACHA,

            /** 被限流拒绝的页（result=142，8B 截断响应，无业务体）。 */
            REJECTED,

            /** B8：非「全部卡池」视图 → 整页丢弃。 */
            DROPPED_VIEW,

            /** 全部卡池页但本次没解析出记录（空页 / 末页越界）。 */
            NO_RECORDS,

            /** 正常：本页收下 [kept] 条（可能为空 = 全被判重跳过；[view] 为 null 见 [onResponse] 注释）。 */
            ACCEPTED,
        }
    }

    val viewTracker = ViewTracker()

    private val dedup = SessionDedup(historyRecords)

    private var sessionStarted = false

    /** 是否已抓到「全部卡池」视图的请求（Q2=C 的记录页提示用）。 */
    var allPoolsSeen: Boolean = false
        private set

    /** 被 B8 丢弃的页数。 */
    var droppedPages: Int = 0
        private set

    /** 当前位置平移量 Δ（= 当前权威 total − 落库基准 total）。 */
    val shift: Int get() = dedup.shift

    /** 会话内收下的记录条数。 */
    val sessionNewCount: Int get() = dedup.sessionNewCount

    /** 会话内是否翻到过与历史重叠的边界。 */
    val overlappedHistory: Boolean get() = dedup.overlappedHistory

    /** 本次会话从「被采纳」响应里解析出的记录条数（**含被判重跳过的旧记录**）。
     *  判「有没有在读到数据」用：>0 说明抓取在正常收流，只是可能全是被历史去重跳过的重复页。 */
    private var parsedTotal = 0

    /** [parsedTotal] 的只读访问。 */
    val parsedCount: Int get() = parsedTotal

    /**
     * 处理一条 C→S 帧体：是切池/翻页请求时登记 `seq→(view, offset)` 并更新当前视图。
     *
     * @return 解析出的请求；非切池/翻页请求（心跳、未知信封）返回 null 且不改动任何状态
     */
    fun onRequest(body: ByteArray): PoolRequest? {
        val req = viewTracker.trackRequest(body) ?: return null
        if (ViewIdentity.isAllPools(ViewIdentity.makeView(req.poolId, req.listType))) {
            allPoolsSeen = true
        }
        return req
    }

    /**
     * 处理一条 S→C 帧体（抽卡响应），返回本帧的处置结果。
     *
     * 判定顺序（顺序本身即正确性的一部分）：
     *
     * 1. `noteResponse` —— 登记权威 total / 已见页，并识别被拒页（**必须在任何早退之前**）；
     * 2. B8 过滤 —— 非「全部卡池」整页丢弃；
     * 3. `view == null`（C→S 请求完全没抓到）→ **只收不做位置判重**并打点（Q6：不能拿未
     *    确认的坐标系去判重）；
     * 4. 首个有效响应确定 Δ 基准 → `notifyTotal` 动态跟随 → `add` 实时位置判重。
     */
    fun onResponse(body: ByteArray): Outcome {
        // ① 必须最先：被拒响应是 8B 截断帧、不含记录，放到解析之后永远检测不到
        val rejected = viewTracker.noteResponse(body)
        if (rejected != null) {
            return Outcome(Outcome.Kind.REJECTED, rejected.first, rejected.second)
        }

        val resp = PoolResponseCodec.parse(body) ?: return Outcome(Outcome.Kind.NOT_GACHA)
        val view = viewTracker.resolveView(body)

        // ② 视图未知（C→S 请求未抓到）：只收不判重 —— 用未确认的坐标系判重会造成少算
        if (view == null) {
            val records = RecordParser.extractRecords(body)
            if (records.isEmpty()) {
                return Outcome(Outcome.Kind.NO_RECORDS, null, resp.offset, total = resp.total)
            }
            val accepted = dedup.acceptUnchecked(records)
            parsedTotal += records.size
            return Outcome(
                Outcome.Kind.ACCEPTED, null, resp.offset,
                parsed = records.size, kept = accepted, total = resp.total,
            )
        }

        // ③ B8：只采纳「全部卡池」（其它视图是它的子集，纳入会引入不可比坐标系）
        if (!ViewIdentity.isAllPools(view)) {
            droppedPages++
            return Outcome(Outcome.Kind.DROPPED_VIEW, view, resp.offset, total = resp.total)
        }

        val records = RecordParser.extractRecords(body)
        if (records.isEmpty()) {
            return Outcome(Outcome.Kind.NO_RECORDS, view, resp.offset, total = resp.total)
        }

        // ④ 首个有效响应：定 Δ 基准（历史基准 total；无历史则取本次 total）
        if (!sessionStarted) {
            dedup.beginSessionShift(baselineTotal ?: resp.total)
            sessionStarted = true
        }
        // ⑤ Δ 动态跟随：会话中途抽卡 → total 涨 k → Δ += k
        dedup.notifyTotal(resp.total)
        // ⑥ 实时位置判重 + 收下即归一化（pos − Δ）
        val keptCount = dedup.add(records)
        parsedTotal += records.size
        val kept = if (keptCount > 0) dedup.sessionRecords().takeLast(keptCount) else emptyList()
        return Outcome(
            Outcome.Kind.ACCEPTED, view, resp.offset,
            parsed = records.size, kept = kept, total = resp.total,
        )
    }

    /** 本次会话收下的记录（已归一化到历史坐标系），收尾时交给 `HistoryStore.merge`。 */
    fun sessionRecords(): List<GachaRecord> = dedup.sessionRecords()

    /** 本会话各视图的权威 total 快照（并入历史，供下次推 Δ）。 */
    fun viewTotals(): Map<String, Int> = viewTracker.totals

    /** 缺页报告（`total` 推应有页 − 已见页）。 */
    fun missingPages(): List<MissingPages> = viewTracker.missingPages()

    /** 被限流拒绝的页 `[(view, offset)]`。 */
    fun rejectedPages(): List<Pair<String, Int>> = viewTracker.rejectedPages

    /** 合并后大小 ∉ {1,10} 的事件（历史 + 本会话）。 */
    fun suspiciousEvents(): List<EventAnomaly> = dedup.suspiciousEvents()

    /** 事件内位置不连续的事件（比大小异常更严，能抓「长度 10 但中间缺一格」）。 */
    fun unresolvedEvents(): List<EventAnomaly> = dedup.unresolvedEvents()

    /** 最近一次请求的视图（诊断用）。 */
    fun currentView(): String? = viewTracker.current
}
