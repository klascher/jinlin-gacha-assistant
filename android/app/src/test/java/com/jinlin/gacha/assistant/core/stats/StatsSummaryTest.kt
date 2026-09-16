package com.jinlin.gacha.assistant.core.stats

import com.jinlin.gacha.assistant.core.GachaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StatsSummary] 单测 —— 对拍 PC `gui/pity_panel.py:417-454` 顶部摘要的派生公式。
 *
 * 期望值来自 PC 真实引擎输出（`_verify/mobile_stats_mirror_check.py` fixture 的 `summary` 段）。
 *
 * ⚠️ 证据等级：仅开发环境无 Android 工具链 → **未编译、未执行**。
 */
class StatsSummaryTest {

    private val CHARS = mapOf(
        "1001" to Character("1001", "六星A", 6),
        "1002" to Character("1002", "六星B", 6),
        "2001" to Character("2001", "五星X", 5),
        "3001" to Character("3001", "四星Y", 4),
    )

    private fun rec(poolId: String, itemId: String, ts: Long, pos: Int = 0) =
        GachaRecord(poolId = poolId, itemId = itemId, timestamp = ts, positionInBatch = pos)

    /** D2：单池 5 抽含 1 个 UP 6★ → 6星率 20%、不歪率 100%、出货池 1/1。 */
    @Test
    fun `d2 summary`() {
        val e = StatsEngine(
            CHARS,
            mapOf("201" to Pool("201", "示例保底组1甲", "UP", upCharacter = "六星A", pityGroup = "g_up")),
            mapOf("g_up" to PityGroup("g_up", "示例保底组1", 70, true)),
        )
        e.loadRecords(
            listOf(
                rec("201", "1001", 2000, pos = 7),
                rec("201", "3001", 2000, pos = 3),
                rec("201", "3001", 2000, pos = 0),
                rec("201", "3001", 2000, pos = 9),
                rec("201", "2001", 2000, pos = 2),
            )
        )

        val s = StatsSummary.from(e)
        assertEquals(5, s.total)
        assertEquals(1, s.sixTotal)
        assertEquals(1, s.shippedPools)
        assertEquals(1, s.totalPools)
        assertEquals(20.0, s.sixRatePercent, 1e-12)
        assertEquals(1, s.nonWarpWins)
        assertEquals(1, s.nonWarpAllUp)
        assertEquals(100.0, s.nonWarpRatePercent!!, 1e-12)
    }

    /** D1：10 抽无 6★ → 6星率 0（total≠0）、不歪率 null（无 UP 数据）。 */
    @Test
    fun `d1 summary zero six`() {
        val e = StatsEngine(CHARS, mapOf("101" to Pool("101", "示例卡池008", "新手")))
        e.loadRecords(List(10) { rec("101", "2001", 1000L + it, pos = it) })

        val s = StatsSummary.from(e)
        assertEquals(10, s.total)
        assertEquals(0, s.sixTotal)
        assertEquals(0, s.shippedPools)
        assertEquals(1, s.totalPools)
        assertEquals(0.0, s.sixRatePercent, 0.0)
        assertNull(s.nonWarpRatePercent)
    }

    /** D6：4 抽 4 个 6★、跨 3 池 → 出货池 3/3、6星率 100%。 */
    @Test
    fun `d6 summary shipped pools`() {
        val e = StatsEngine(
            CHARS,
            mapOf(
                "501" to Pool("501", "示例保底组1甲", "UP", upCharacter = "六星A", pityGroup = "g_up"),
                "502" to Pool("502", "示例保底组4", "遴选", upCharacter = "六星B", pityGroup = "g_sel"),
                "503" to Pool("503", "示例保底组2", "常驻"),
            ),
            mapOf(
                "g_up" to PityGroup("g_up", "示例保底组1", 70, true),
                "g_sel" to PityGroup("g_sel", "示例保底组4", 70, true),
            ),
        )
        e.loadRecords(
            listOf(
                rec("501", "1001", 1, pos = 1),
                rec("501", "1002", 2, pos = 1),
                rec("502", "1002", 3, pos = 1),
                rec("503", "1001", 4, pos = 1),
            )
        )

        val s = StatsSummary.from(e)
        assertEquals(4, s.total)
        assertEquals(4, s.sixTotal)
        assertEquals(3, s.shippedPools)
        assertEquals(3, s.totalPools)
        assertEquals(100.0, s.sixRatePercent, 1e-12)
        assertEquals(100.0, s.nonWarpRatePercent!!, 1e-12)
    }

    /** D8：总抽数含手动补录（6），但 six_total 只数保底命中 → 6星率 50%。 */
    @Test
    fun `d8 summary counts manual fill in total`() {
        val e = StatsEngine(
            CHARS,
            mapOf(
                "701" to Pool("701", "示例保底组2", "常驻"),
                "702" to Pool("702", "示例保底组1甲", "UP", upCharacter = "六星A", pityGroup = "g_up"),
            ),
            mapOf("g_up" to PityGroup("g_up", "示例保底组1", 70, true)),
        )
        e.loadRecords(
            listOf(
                rec("701", "1001", 1, pos = 1),
                rec("701", "3001", 2, pos = 1),
                rec("702", "3001", 3, pos = 1),
                rec("702", "2001", 4, pos = 1),
                rec("702", "1002", 5, pos = 1),
                rec("701", StatsEngine.MANUAL_FILL_ID, 6, pos = 1),
            )
        )

        val s = StatsSummary.from(e)
        assertEquals(6, s.total)      // 含手动补录
        assertEquals(3, s.sixTotal)
        assertEquals(2, s.shippedPools)
        assertEquals(50.0, s.sixRatePercent, 1e-12)
        assertNull(s.nonWarpRatePercent) // g_up 全歪 → allUp=0
    }

    /** D10：空数据 → total=0 → 6星率 0（不除零）。 */
    @Test
    fun `d10 summary empty no division by zero`() {
        val e = StatsEngine(CHARS, mapOf("101" to Pool("101", "示例卡池008", "新手")))
        val s = StatsSummary.from(e)
        assertEquals(0, s.total)
        assertEquals(0.0, s.sixRatePercent, 0.0)
        assertNull(s.nonWarpRatePercent)
        assertEquals(1, s.totalPools)
    }

    /** 两个重载等价：`from(engine)` == `from(total, tracking, pools.size)`。 */
    @Test
    fun `engine overload equals by parts overload`() {
        val e = StatsEngine(
            CHARS,
            mapOf("201" to Pool("201", "示例保底组1甲", "UP", upCharacter = "六星A", pityGroup = "g_up")),
            mapOf("g_up" to PityGroup("g_up", "示例保底组1", 70, true)),
        )
        e.loadRecords(listOf(rec("201", "1001", 1, pos = 1), rec("201", "3001", 2, pos = 1)))

        val byEngine = StatsSummary.from(e)
        val byParts = StatsSummary.from(e.total, e.sharedPityTracking(), e.pools.size)
        assertEquals(byEngine, byParts)
    }
}
