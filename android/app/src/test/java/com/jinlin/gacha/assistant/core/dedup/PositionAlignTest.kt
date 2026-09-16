package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.GachaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置对齐去重 JVM 对拍 —— 逐字对齐 PC `tests/test_pos_align.py` 的 14 例。
 *
 * 核心断言：**位置即身份**——同名记录 pos 不同，因此位置去重天然不会吞同名第二份，
 * 也不会把跨会话重抓的重复计入。Δ（位置平移量）来自**权威 total 增量**，不使用内容比对
 * （十连同名使内容比对不可靠）。
 */
class PositionAlignTest {

    private companion object {
        const val POOL = "30005"
        const val TS = 1_789_000_000_000L

        /** 十连（含同名：位置 0 与位置 5 都是 A）—— 同名第二份是历史三大缺陷的主角。 */
        val ITEMS = listOf("A", "B", "C", "D", "E", "A", "F", "G", "H", "I")

        fun r(item: String, pos: Int, ts: Long = TS, pool: String = POOL): GachaRecord =
            GachaRecord(poolId = pool, itemId = item, timestamp = ts, batchSeq = 0, positionInBatch = pos)

        /** 按顺序构造一批记录，位置从 base 起连续递增。 */
        fun ten(items: List<String>, base: Int, ts: Long = TS): List<GachaRecord> =
            items.mapIndexed { i, it -> r(it, base + i, ts) }
    }

    // ---------- Δ 来源：权威 total 增量 ----------

    @Test
    fun `infer shift from total growth`() {
        assertEquals(30, PositionAlign.inferShiftFromTotal(551, 521))
        assertEquals(0, PositionAlign.inferShiftFromTotal(521, 521))
    }

    @Test
    fun `infer shift handles first capture and reset`() {
        assertEquals(0, PositionAlign.inferShiftFromTotal(521, null))
        assertEquals(0, PositionAlign.inferShiftFromTotal(null, 521))
    }

    @Test
    fun `infer shift never negative`() {
        assertEquals(0, PositionAlign.inferShiftFromTotal(100, 521))
    }

    // ---------- 单次抓包：全收（不做事件内去重） ----------

    @Test
    fun `single session keeps same name copies`() {
        val recs = ten(ITEMS, 100)
        val report = PositionAlign.mergeByPosition(emptyList(), recs, shift = 0)
        assertEquals(10, report.added.size)
        assertEquals(2, report.added.count { it.itemId == "A" })
        assertTrue(report.suspicious.isEmpty())
        assertEquals(1, report.newEvents)
        assertEquals(0, report.mergedEvents)
    }

    // ---------- 跨会话：位置去重 ----------

    @Test
    fun `merge with overlap dedups by position`() {
        val hist = ten(ITEMS.subList(0, 6), 100)      // pos 100~105（含位置 5 的 A）
        val new = ten(ITEMS.subList(4, 10), 104)      // pos 104~109（重叠 104/105）
        val report = PositionAlign.mergeByPosition(hist, new, shift = 0)
        assertEquals(2, report.skipped)
        assertEquals(4, report.added.size)
        val merged = hist + report.added
        assertEquals(10, merged.size)
        assertEquals(ITEMS, merged.sortedBy { it.positionInBatch }.map { it.itemId })
        assertTrue(report.suspicious.isEmpty())
    }

    @Test
    fun `merge complementary halves not swallowed`() {
        // 关键：两段互补（无重叠、未平移）→ 全收 10 条，同名第二份不得被吞。
        val hist = ten(ITEMS.subList(0, 5), 100)      // 位置 0~4
        val new = ten(ITEMS.subList(5, 10), 105)      // 位置 5~9
        val report = PositionAlign.mergeByPosition(hist, new, shift = 0)
        assertEquals(5, report.added.size)
        assertEquals(0, report.skipped)
        val merged = hist + report.added
        assertEquals(10, merged.size)
        assertEquals(ITEMS, merged.sortedBy { it.positionInBatch }.map { it.itemId })
    }

    @Test
    fun `merge after draw uses total delta`() {
        val hist = ten(ITEMS.subList(0, 5), 100)                 // 上次：位置 0~4 在列表 100~104
        val shift = PositionAlign.inferShiftFromTotal(551, 521)  // 本次 total 多了 30
        val new = ten(ITEMS.subList(3, 10), 103 + shift)         // 本次：位置 3~9 → 列表 133~139
        val report = PositionAlign.mergeByPosition(hist, new, shift = shift)
        assertEquals(30, report.shift)
        // 位置 3、4 已在历史里（100+3 / 100+4）→ 只加 5 条
        assertEquals(2, report.skipped)
        assertEquals(5, report.added.size)
        assertEquals(10, (hist + report.added).size)
        assertTrue(report.suspicious.isEmpty())
    }

    @Test
    fun `merge identical reobservation is fully skipped`() {
        val hist = ten(ITEMS, 100)
        val new = ten(ITEMS, 100)
        val report = PositionAlign.mergeByPosition(hist, new, shift = 0)
        assertTrue(report.added.isEmpty())
        assertEquals(1, report.eventSkipped)
        assertEquals(0, report.skipped)
        assertTrue(report.suspicious.isEmpty())
    }

    @Test
    fun `merge new events are appended`() {
        val hist = ten(ITEMS, 100)
        val fresh = ten(listOf("N1", "N2", "N3", "N4", "N5"), 95, ts = TS + 1)
        val report = PositionAlign.mergeByPosition(hist, fresh, shift = 0)
        assertEquals(5, report.added.size)
        assertEquals(1, report.newEvents)
        assertEquals(0, report.mergedEvents)
    }

    @Test
    fun `merge single pull event`() {
        val hist = listOf(r("S1", 50, ts = TS + 2))
        val same = listOf(r("S1", 50, ts = TS + 2))
        val rep = PositionAlign.mergeByPosition(hist, same, shift = 0)   // 坐标命中 → 位置判重跳过
        assertEquals(0, rep.totalAdded)
        assertEquals(1, rep.skipped)
        val other = listOf(r("S1", 60, ts = TS + 2))                     // 同 ts、坐标不命中 → 同一次抽卡
        val rep2 = PositionAlign.mergeByPosition(hist, other, shift = 0)
        assertEquals(0, rep2.totalAdded)
        assertEquals(1, rep2.eventSkipped)
    }

    // ---------- 异常与安全阀 ----------

    @Test
    fun `suspicious events reported not silent`() {
        val hist = ten(ITEMS.subList(0, 6), 100)
        val new = ten(ITEMS.subList(6, 10), 106)          // 只补 4 条 → 合并 10 条（合法）
        assertTrue(PositionAlign.mergeByPosition(hist, new, shift = 0).suspicious.isEmpty())

        val newPartial = ten(ITEMS.subList(6, 8), 106)    // 只补 2 条 → 合并 8 条（非法）
        val rep = PositionAlign.mergeByPosition(hist, newPartial, shift = 0)
        assertEquals(listOf(EventAnomaly(TS, POOL, 8)), rep.suspicious)
    }

    @Test
    fun `history with broken positions falls back to append`() {
        val hist = listOf(r("A", 100), r("B", 130))       // 位置断裂：100 与 130
        val new = listOf(r("A", 100), r("B", 101))
        val report = PositionAlign.mergeByPosition(hist, new, shift = 0)
        assertEquals(listOf(TS), report.fallbackEvents)
        assertEquals(2, report.added.size)
        assertEquals(0, report.skipped)
        assertTrue(report.suspicious.isNotEmpty())        // 合并后 4 条 ∉ {1,10} → 报警
    }

    @Test
    fun `valid event sizes exported`() {
        assertEquals(listOf(1, 10), VALID_EVENT_SIZES)
    }

    @Test
    fun `group by ts splits events`() {
        val recs = ten(listOf("x"), 1, ts = 1) + ten(listOf("y"), 1, ts = 2)
        val groups = PositionAlign.groupByTs(recs)
        assertEquals(setOf(1L, 2L), groups.keys)
        assertEquals(1, groups[1L]!!.size)
    }

    // ---------- positionsComparable 边界（PC 私有函数，这里显式覆盖） ----------

    @Test
    fun `positions comparable allows holes but not span over 10`() {
        assertTrue(PositionAlign.positionsComparable(emptyList()))
        assertTrue(PositionAlign.positionsComparable(listOf(r("A", 100))))
        assertTrue(PositionAlign.positionsComparable(listOf(r("A", 100), r("B", 105))))   // 有洞但同坐标系
        assertTrue(PositionAlign.positionsComparable(listOf(r("A", 100), r("B", 110))))   // 跨度恰好 10
        assertTrue(!PositionAlign.positionsComparable(listOf(r("A", 100), r("B", 130))))  // 跨度 > 10
        assertTrue(!PositionAlign.positionsComparable(listOf(r("A", 100), r("B", 100))))  // 位置重复
    }
}
