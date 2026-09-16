package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.GachaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内实时判重 JVM 对拍 —— 对齐 PC `StatsEngine.begin_session_shift` / `notify_total`
 * / `add`（位置对齐部分）与 `suspicious_events` / `unresolved_events`。
 *
 * 覆盖四条关键语义：
 * 1. 批次指纹拦「整页重传」；
 * 2. 历史完整事件（size ∈ {1,10}）按 ts 整条实时跳过；
 * 3. **Δ 动态跟随**：会话中途抽卡 → total 涨 k → Δ += k，新记录按新 Δ 归一化落库；
 * 4. 收下即归一化：会话新增记录的 pos 恒在同一基准坐标系。
 */
class SessionDedupTest {

    private companion object {
        const val POOL = "30005"
        const val TS = 1_789_000_000_000L
        val ITEMS = listOf("A", "B", "C", "D", "E", "A", "F", "G", "H", "I")

        fun r(item: String, pos: Int, ts: Long = TS, pool: String = POOL): GachaRecord =
            GachaRecord(poolId = pool, itemId = item, timestamp = ts, batchSeq = 0, positionInBatch = pos)

        fun ten(items: List<String>, base: Int, ts: Long = TS): List<GachaRecord> =
            items.mapIndexed { i, it -> r(it, base + i, ts) }
    }

    @Test
    fun `fresh session accepts all and dedups identical batch`() {
        val d = SessionDedup(emptyList())
        d.beginSessionShift(521)
        assertEquals(10, d.add(ten(ITEMS, 100)))
        assertEquals(0, d.add(ten(ITEMS, 100)))       // 整批指纹重复 → 整批跳过
        assertEquals(10, d.sessionRecords().size)
        assertFalse(d.overlappedHistory)
    }

    @Test
    fun `historical complete event is skipped at ts level`() {
        val history = ten(ITEMS, 100)                 // 完整十连 → completeTs
        val d = SessionDedup(history)
        d.beginSessionShift(521)
        assertEquals(0, d.add(ten(ITEMS, 100)))       // ts 即身份，整事件实时跳过
        assertTrue(d.overlappedHistory)
    }

    @Test
    fun `shift follows total growth and normalizes new records`() {
        val history = ten(ITEMS.subList(0, 5), 100)   // 部分事件（size 5）→ partialTs
        val d = SessionDedup(history)
        d.beginSessionShift(521)
        assertEquals(0, d.shift)
        d.notifyTotal(551)                            // 会话中途抽了 30 抽
        assertEquals(30, d.shift)
        // 本次列表头部多了 30 条 → 位置 5~9 落在 135~139；归一化后回到 105~109
        assertEquals(5, d.add(ten(ITEMS.subList(5, 10), 135)))
        assertEquals(listOf(105, 106, 107, 108, 109), d.sessionRecords().map { it.positionInBatch })
    }

    @Test
    fun `position dedup within same session`() {
        val d = SessionDedup(emptyList())
        d.beginSessionShift(521)
        assertEquals(10, d.add(ten(ITEMS, 100)))
        // 第二批与第一批同 ts、位置 105~109 已见 → 全部位置判重跳过
        assertEquals(0, d.add(ten(ITEMS.subList(5, 10), 105)))
        assertEquals(10, d.sessionNewCount)
    }

    @Test
    fun `delta unknown accepts all conservatively`() {
        val d = SessionDedup(emptyList())             // 未调用 beginSessionShift → Δ 未知
        assertEquals(10, d.add(ten(ITEMS, 100)))
        assertEquals(0, d.shift)
        assertEquals(listOf(100, 101, 102, 103, 104, 105, 106, 107, 108, 109),
            d.sessionRecords().map { it.positionInBatch })
    }

    @Test
    fun `suspicious events reports illegal size`() {
        val d = SessionDedup(ten(ITEMS.subList(0, 8), 100))   // 8 条：非法事件大小
        d.beginSessionShift(521)
        assertEquals(listOf(EventAnomaly(TS, POOL, 8)), d.suspiciousEvents())
    }

    @Test
    fun `unresolved events catches gap even when size legal`() {
        // 长度 2 但位置 [100, 102] 有洞 → 未定序（大小异常同时命中）
        val d = SessionDedup(listOf(r("A", 100), r("B", 102)))
        d.beginSessionShift(521)
        assertEquals(listOf(EventAnomaly(TS, POOL, 2)), d.unresolvedEvents())

        // 连续且大小合法 → 两条都为空
        val ok = SessionDedup(ten(ITEMS, 100))
        ok.beginSessionShift(521)
        assertTrue(ok.unresolvedEvents().isEmpty())
        assertTrue(ok.suspiciousEvents().isEmpty())
    }
}
