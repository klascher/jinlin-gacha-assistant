package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.SEQ_MOD

/**
 * 视图追踪 —— Kotlin 逐字镜像 PC `gacha_exporter/analysis/pool_view.py::PoolViewTracker`
 * 的**在役部分**（旧事件账本的「翻页方向」信号 / `filter_records` 跨视图过滤已随
 * 位置对齐方案退役，不再移植）。
 *
 * 维护：
 * - `seq % 127 -> (view, offset)`：请求/响应配对，确定每条响应来自哪个视图；
 *   offset 供被拒响应定位被拒的是哪一页
 * - `currentView`：最近一次请求的视图（请求没抓到时的回退）
 * - `viewTotals`：各视图服务器权威总抽数（响应 field2）→ 完整性校验 + 缺口报告
 * - `seenPages`：已成功拿到数据的 `(view, offset)` 页集合
 * - `rejectedPages`：被限流拒绝的 `(view, offset)`（该次响应无数据，客户端随后自动重发）
 *
 * **线程约束（重要）**：请求与响应走同一根消费队列（`ReassemblyWorker.queue`），
 * TCP 因果序保证「请求先于其响应入队」。所以视图追踪**必须与记录消费同线程**，
 * 不能另起线程——否则收到响应时 seq→view 表可能尚未登记。
 *
 * 纯 JVM、零 Android 依赖。
 */
class ViewTracker {

    /** seq % [SEQ_MOD] -> (view, offset)。 */
    private val seqToReq = mutableMapOf<Int, Pair<String, Int>>()

    private var currentView: String? = null

    /** 被限流拒绝的页 `[(view, offset)]`；offset=-1 表示请求未抓到、对象未知。 */
    private val rejected = mutableListOf<Pair<String, Int>>()

    /** 视图 -> 服务器权威总抽数。 */
    private val viewTotals = mutableMapOf<String, Int>()

    /** 已成功拿到数据的 `(view, offset)` 页集合（被拒页不计入）。 */
    private val seenPages = mutableSetOf<Pair<String, Int>>()

    /** 最近一次请求的视图；null = 尚未抓到任何切池请求。 */
    val current: String? get() = currentView

    /** 被限流拒绝的页列表（只读副本）。 */
    val rejectedPages: List<Pair<String, Int>> get() = rejected.toList()

    /** 各视图权威 total（只读副本）。 */
    val totals: Map<String, Int> get() = viewTotals.toMap()

    /** 已见页集合（只读副本）。 */
    val seen: Set<Pair<String, Int>> get() = seenPages.toSet()

    /**
     * 处理一条 C→S 请求帧：登记 `seq%127 -> (view, offset)` 并更新当前视图。
     *
     * 配对键用 `seq % SEQ_MOD`（=127）：响应只回显该回绕值（seq 127→0x00、
     * 128→0x01，抓包逐帧验证）。窗口内未完成请求数远达不到 127，碰撞可忽略。
     *
     * @param body C→S 帧体（不含长度头，含前 4 字节 seq）
     * @return 解析出的请求；非切池/翻页请求时为 null（不改变任何状态）
     */
    fun trackRequest(body: ByteArray): PoolRequest? {
        val req = PoolRequestCodec.parse(body) ?: return null
        val view = ViewIdentity.makeView(req.poolId, req.listType)
        seqToReq[(req.seq % SEQ_MOD).toInt()] = view to req.offset
        currentView = view
        return req
    }

    /**
     * 由响应帧回显的 seq 查出该页所属视图（镜像 `resolve_view`）。
     *
     * 优先用响应回显的 seq 反查；未登记（请求没抓到）则回退 [current]；两者都未知返回 null。
     *
     * @param body S→C 帧体（不含长度头）
     */
    fun resolveView(body: ByteArray): String? {
        val resp = PoolResponseCodec.parse(body) ?: return currentView
        return seqToReq[resp.seq]?.first ?: currentView
    }

    /**
     * 登记服务器响应：记录权威 total，识别被限流拒绝的响应（镜像 `note_response`）。
     *
     * **必须在调用方「无记录提前返回」之前调用**：被拒响应是 8 字节截断帧、不含任何
     * 记录，若放在记录解析之后按记录数早退就永远检测不到——频繁限流导致某页本次无
     * 响应时用户无感知。
     *
     * @param body S→C 帧体（不含长度头）
     * @return 被拒页 `(view, offset)`；正常响应或结构不符返回 null
     */
    fun noteResponse(body: ByteArray): Pair<String, Int>? {
        val resp = PoolResponseCodec.parse(body) ?: return null
        val resolved = seqToReq[resp.seq]?.first ?: currentView
        if (!resp.rejected) {
            // 正常响应：记录该视图的权威 total（恒定，重复覆盖无害），并登记
            // 「这一页已拿到数据」——缺口报告据此与应有页相减。
            if (resolved != null) {
                if (resp.total > 0) viewTotals[resolved] = resp.total
                if (resp.offset >= 0) seenPages.add(resolved to resp.offset)
            }
            return null
        }
        val view = resolved ?: "?"
        val offset = seqToReq[resp.seq]?.second ?: -1
        rejected.add(view to offset)
        return view to offset
    }

    /**
     * 缺页报告（镜像 `missing_pages`）。
     *
     * 由「权威 total + 每页 5 条」（B4）推算应有页，再减去已成功响应的页。
     * 用途：收尾时明确告诉用户「还缺哪几页」——这是「仅采纳全部卡池」（B8）后
     * 失去多视图冗余的**唯一兜底**。
     *
     * @param view 只报告该视图；null 表示报告全部有 total 的视图
     * @return 按视图标识升序排列的报告；无缺口（或该视图无权威 total）时为空
     */
    fun missingPages(view: String? = null): List<MissingPages> {
        val out = mutableListOf<MissingPages>()
        for ((v, total) in viewTotals.toSortedMap()) {
            if (view != null && v != view) continue
            val expected = ViewIdentity.expectedPageOffsets(total)
            if (expected.isEmpty()) continue
            val expectedSet = expected.toSet()
            val seenForView = seenPages.filter { it.first == v }.map { it.second }.toSet()
            val missing = expected.filter { it !in seenForView }
            if (missing.isNotEmpty()) {
                out.add(
                    MissingPages(
                        view = v,
                        missing = missing,
                        seenCount = seenForView.count { it in expectedSet },
                        expectedCount = expected.size,
                    )
                )
            }
        }
        return out
    }

    /** 清空状态（账号切换 / 清空时复用），镜像 `reset`。 */
    fun reset() {
        seqToReq.clear()
        currentView = null
        rejected.clear()
        viewTotals.clear()
        seenPages.clear()
    }
}

/** 一个视图的缺页报告：[view] 还缺 [missing] 这些 offset 页（已见 [seenCount]/[expectedCount]）。 */
data class MissingPages(
    val view: String,
    val missing: List<Int>,
    val seenCount: Int,
    val expectedCount: Int,
)
