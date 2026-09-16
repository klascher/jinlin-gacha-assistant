package com.jinlin.gacha.assistant.core.stats

import com.jinlin.gacha.assistant.core.GachaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatsEngine] 单测 —— 对拍 PC `gacha_exporter/analysis/stats.py` 的展示统计半边。
 *
 * **期望值全部来自 PC 真实 StatsEngine 的执行**（`_verify/mobile_stats_mirror_check.py`
 * 的 fixture，D1~D10 十个数据集）。该脚本同时把 Kotlin 逻辑转写为 Python 与 PC 逐字段
 * 对拍 **120/120 通过**（含 dict 键顺序的敏感检查）。
 *
 * ⚠️ 证据等级：仅开发环境无 Android 工具链 → **Kotlin 未编译、本测试未执行**。对拍只证明
 * 语义等价，不证明可编译。
 */
class StatsEngineTest {

    private val CHARS = mapOf(
        "1001" to Character("1001", "六星A", 6),
        "1002" to Character("1002", "六星B", 6),
        "2001" to Character("2001", "五星X", 5),
        "3001" to Character("3001", "四星Y", 4),
    )

    private fun rec(poolId: String, itemId: String, ts: Long, pos: Int = 0, batch: Int = 0) =
        GachaRecord(poolId = poolId, itemId = itemId, timestamp = ts,
            batchSeq = batch, positionInBatch = pos)

    private fun upPool(id: String, name: String, up: String, group: String) =
        Pool(id = id, name = name, type = "UP", upCharacter = up, pityGroup = group)

    private fun group(id: String, label: String, hard: Int = 70, state: Boolean = false) =
        PityGroup(id = id, label = label, hardPity = hard, hasState = state)

    // ---- D1 单池无 6 星（solo 组，无状态） ----

    @Test
    fun `d1 single pool no six star`() {
        val e = StatsEngine(CHARS, mapOf("101" to Pool("101", "示例卡池008", "新手")))
        e.loadRecords(List(10) { rec("101", "2001", 1000L + it, pos = it) })

        val t = e.sharedPityTracking()
        assertEquals(listOf("solo:101"), t.keys.toList())
        val g = t["solo:101"]!!
        assertEquals("示例卡池008", g.label)
        assertEquals(70, g.hardPity)
        assertFalse(g.hasState)
        assertNull(g.state)
        assertEquals(10, g.total)
        assertEquals(10, g.currentPity)
        assertEquals(60, g.distanceToPity)
        assertEquals(0, g.sixCount)
        assertEquals(0, g.upCount)
        assertTrue(g.hits.isEmpty())
        assertEquals(listOf("101"), g.poolIds)
        assertEquals(PerPoolStat(padded = 10, total = 10, sixCount = 0, upCount = 0, hits = emptyList()),
            g.perPool["101"])

        val (rarity, unmapped) = e.rarityDistribution()
        assertEquals(mapOf(5 to 10), rarity)
        assertTrue(unmapped.isEmpty())
        assertEquals(mapOf("示例卡池008" to 10), e.poolDistribution())
        assertTrue(e.unknownIdStats().isEmpty())
        // 非状态机组不进不歪率
        assertTrue(e.nonWarpRates().isEmpty())
    }

    // ---- D2 十连 tiebreaker：位置降序决定 6★ 落点 ----

    @Test
    fun `d2 ten pull tiebreaker orders by position desc`() {
        val e = StatsEngine(
            CHARS,
            mapOf("201" to upPool("201", "示例保底组1甲", "六星A", "g_up")),
            mapOf("g_up" to group("g_up", "示例保底组1", state = true)),
        )
        e.loadRecords(
            listOf(
                rec("201", "1001", 2000, pos = 7), // 6★
                rec("201", "3001", 2000, pos = 3),
                rec("201", "3001", 2000, pos = 0),
                rec("201", "3001", 2000, pos = 9),
                rec("201", "2001", 2000, pos = 2),
            )
        )

        val g = e.sharedPityTracking()["g_up"]!!
        // 位置降序 => 9,7,3,2,0 → 6★ 落在第 2 抽（若用升序会落到第 4 抽，本断言可鉴别）
        assertEquals(1, g.sixCount)
        assertEquals(2, g.hits[0].pity)
        assertEquals(2000L, g.hits[0].ts)
        assertEquals("201", g.hits[0].poolId)
        assertEquals("六星A", g.hits[0].name)
        assertTrue(g.hits[0].isUp)
        assertEquals(3, g.currentPity)
        assertEquals(67, g.distanceToPity)
        assertEquals("小保底", g.state)

        val (rarity, _) = e.rarityDistribution()
        assertEquals(mapOf(6 to 1, 5 to 1, 4 to 3), rarity)
        assertEquals(listOf(6, 5, 4), rarity.keys.toList()) // 稀有度降序
    }

    // ---- D3 跨池共享保底 ----

    @Test
    fun `d3 shared pity across pools in one group`() {
        val e = StatsEngine(
            CHARS,
            mapOf(
                "301" to upPool("301", "示例保底组1甲", "六星A", "g_up"),
                "302" to upPool("302", "示例保底组1乙", "六星B", "g_up"),
            ),
            mapOf("g_up" to group("g_up", "示例保底组1", state = true)),
        )
        e.loadRecords(
            listOf(
                rec("301", "3001", 100, pos = 3),
                rec("301", "3001", 100, pos = 2),
                rec("302", "3001", 200, pos = 1),
                rec("302", "1002", 200, pos = 0), // 池乙 UP 角色=六星B → 命中 UP
            )
        )

        val g = e.sharedPityTracking()["g_up"]!!
        assertEquals(4, g.total)
        assertEquals(1, g.sixCount)      // 跨池计数：301 两条 + 302 两条 → 第 4 抽命中
        assertEquals(4, g.hits[0].pity)
        assertEquals("302", g.hits[0].poolId)
        assertEquals("六星B", g.hits[0].name)
        assertTrue(g.hits[0].isUp)
        assertEquals(0, g.currentPity)
        assertEquals(70, g.distanceToPity)
        assertEquals(listOf("301", "302"), g.poolIds) // 元数据归属，排序输出
        // padded：命中 6★ 后归 0 → 两池都是 0
        assertEquals(0, g.perPool["301"]!!.padded)
        assertEquals(2, g.perPool["301"]!!.total)
        assertEquals(0, g.perPool["302"]!!.padded)
        assertEquals(1, g.perPool["302"]!!.sixCount)

        val nw = e.nonWarpRates()["g_up"]!!
        assertEquals(1, nw.wins)
        assertEquals(1, nw.allUp)
        assertEquals(1.0, nw.rate!!, 0.0)
    }

    // ---- D4 陌生 item_id 默认按 6 星 ----

    @Test
    fun `d4 unknown item id counts as six star`() {
        val e = StatsEngine(
            CHARS,
            mapOf("401" to upPool("401", "示例保底组1甲", "新角色", "g_up")),
            mapOf("g_up" to group("g_up", "示例保底组1", state = true)),
        )
        e.loadRecords(listOf(rec("401", "3001", 10, pos = 2), rec("401", "9999", 10, pos = 1)))

        val g = e.sharedPityTracking()["g_up"]!!
        assertEquals(1, g.sixCount)
        assertEquals("未知(9999)", g.hits[0].name)
        assertTrue("陌生 6★ 在 featured 池 → is_up", g.hits[0].isUp)
        assertEquals(1, g.upCount)
        // 分布：陌生 ID 不进稀有度分布，进 unmapped / unknownIdStats
        assertEquals(mapOf("9999" to 1), e.unknownIdStats())
        assertEquals(setOf("9999"), e.rarityDistribution().second)
        assertEquals(mapOf(4 to 1), e.rarityDistribution().first)
    }

    // ---- D5 屏蔽把陌生 ID 降为非 6 星 ----

    @Test
    fun `d5 shielded unknown id downgraded to non six`() {
        val e = StatsEngine(
            CHARS,
            mapOf("401" to upPool("401", "示例保底组1甲", "新角色", "g_up")),
            mapOf("g_up" to group("g_up", "示例保底组1", state = true)),
            shielded = setOf("9999"),
        )
        e.loadRecords(listOf(rec("401", "3001", 10, pos = 2), rec("401", "9999", 10, pos = 1)))

        val g = e.sharedPityTracking()["g_up"]!!
        assertEquals(0, g.sixCount)       // 9999 被屏蔽 → 不算 6★
        assertEquals(2, g.currentPity)
        assertEquals(68, g.distanceToPity)
        assertNull(e.nonWarpRates()["g_up"]!!.rate) // allUp=0 → rate null
        // 屏蔽只影响保底计算，不影响 unknown_id_stats 的客观事实
        assertEquals(mapOf("9999" to 1), e.unknownIdStats())
    }

    // ---- D6 UP / 遴选判定 + 分组展示顺序 ----

    @Test
    fun `d6 up and featured judgement plus group order`() {
        val e = StatsEngine(
            CHARS,
            mapOf(
                "501" to upPool("501", "示例保底组1甲", "六星A", "g_up"),
                "502" to Pool("502", "示例保底组4", "遴选", upCharacter = "六星B", pityGroup = "g_sel"),
                "503" to Pool("503", "示例保底组2", "常驻"),
            ),
            mapOf(
                "g_up" to group("g_up", "示例保底组1", state = true),
                "g_sel" to group("g_sel", "示例保底组4", state = true),
            ),
        )
        e.loadRecords(
            listOf(
                rec("501", "1001", 1, pos = 1), // UP 命中（featured 且名字相等）
                rec("501", "1002", 2, pos = 1), // 歪
                rec("502", "1002", 3, pos = 1), // 遴选命中
                rec("503", "1001", 4, pos = 1), // 常驻 → featured=false → 非 up
            )
        )

        val t = e.sharedPityTracking()
        // 有状态机组在前（label "示例保底组1" < "示例保底组4"），独立组在后
        assertEquals(listOf("g_up", "g_sel", "solo:503"), t.keys.toList())
        assertTrue(t["g_up"]!!.hits[0].isUp)
        assertFalse(t["g_up"]!!.hits[1].isUp)
        assertEquals("大保底", t["g_up"]!!.state) // 小保底歪 → 转大保底
        assertEquals(2, t["g_up"]!!.sixCount)
        assertEquals(1, t["g_up"]!!.upCount)
        assertTrue(t["g_sel"]!!.hits[0].isUp)     // 遴选 is_featured=true
        assertFalse(t["solo:503"]!!.hits[0].isUp) // 常驻 is_featured=false
        assertFalse(t["solo:503"]!!.hasState)
        assertNull(t["solo:503"]!!.state)

        assertEquals(listOf("g_up", "g_sel"), e.nonWarpRates().keys.toList())
        val pd = e.poolDistribution()
        assertEquals(mapOf("示例保底组1甲" to 2, "示例保底组4" to 1, "示例保底组2" to 1), pd)
        assertEquals(listOf("示例保底组1甲", "示例保底组4", "示例保底组2"), pd.keys.toList()) // 同数保持插入序
    }

    // ---- D7 小/大保底状态机 + 非歪率 ----

    @Test
    fun `d7 pity state machine and non warp rate`() {
        val e = StatsEngine(
            CHARS,
            mapOf("601" to upPool("601", "示例保底组1甲", "六星A", "g_up")),
            mapOf("g_up" to group("g_up", "示例保底组1", state = true)),
        )
        e.loadRecords(
            listOf(
                rec("601", "3001", 1, pos = 1),
                rec("601", "1001", 2, pos = 1), // 小保底命中 UP → win
                rec("601", "1002", 3, pos = 1), // 歪 → 大保底
                rec("601", "1001", 4, pos = 1), // 大保底必出 → 只计 all_up
                rec("601", "1001", 5, pos = 1), // 小保底命中 UP → win
            )
        )

        val g = e.sharedPityTracking()["g_up"]!!
        assertEquals(4, g.sixCount)
        assertEquals(3, g.upCount)
        assertEquals(0, g.currentPity)
        assertEquals("小保底", g.state) // 最后一次是 UP 命中 → 回到小保底

        val nw = e.nonWarpRates()["g_up"]!!
        assertEquals(2, nw.wins)  // 小保底命中 2 次
        assertEquals(3, nw.allUp) // 全部 UP 出货 3 次（含大保底那次）
        assertEquals(2.0 / 3.0, nw.rate!!, 1e-12)
        assertEquals(0.6666666666666666, nw.perPool["601"]!!.rate!!, 1e-12)
    }

    // ---- D8 分布排序 + 手动补录过滤 ----

    @Test
    fun `d8 distribution sorting and manual fill filter`() {
        val e = StatsEngine(
            CHARS,
            mapOf(
                "701" to Pool("701", "示例保底组2", "常驻"),
                "702" to upPool("702", "示例保底组1甲", "六星A", "g_up"),
            ),
            mapOf("g_up" to group("g_up", "示例保底组1", state = true)),
        )
        e.loadRecords(
            listOf(
                rec("701", "1001", 1, pos = 1),
                rec("701", "3001", 2, pos = 1),
                rec("702", "3001", 3, pos = 1),
                rec("702", "2001", 4, pos = 1),
                rec("702", "1002", 5, pos = 1),
                rec("701", StatsEngine.MANUAL_FILL_ID, 6, pos = 1), // 手动补录
            )
        )

        val (rarity, unmapped) = e.rarityDistribution()
        assertEquals(mapOf(6 to 2, 5 to 1, 4 to 2), rarity) // MANUAL_FILL 被跳过
        assertEquals(listOf(6, 5, 4), rarity.keys.toList())
        assertTrue(unmapped.isEmpty())

        val pd = e.poolDistribution()
        assertEquals(mapOf("示例保底组1甲" to 3, "示例保底组2" to 2), pd)
        assertEquals(listOf("示例保底组1甲", "示例保底组2"), pd.keys.toList()) // 按条数降序

        // 保底侧**不**跳手动补录：solo:701 的 hits 含 MANUAL_FILL 那条
        val t = e.sharedPityTracking()
        assertEquals(listOf("g_up", "solo:701"), t.keys.toList())
        assertEquals(2, t["solo:701"]!!.sixCount)
        assertEquals("未知(__MANUAL_FILL__)", t["solo:701"]!!.hits[1].name)
    }

    // ---- D9 未知池兜底（label 回退用 gkey）----

    @Test
    fun `d9 unknown pool falls back to solo with gkey label`() {
        val e = StatsEngine(CHARS, mapOf("801" to Pool("801", "示例保底组2", "常驻")))
        e.loadRecords(listOf(rec("801", "3001", 1, pos = 1), rec("888", "3001", 2, pos = 1)))

        val t = e.sharedPityTracking()
        // 独立组；888 无配置 → label 回退用 gkey（"solo:888"），'s' < '常' → 排在前面
        assertEquals(listOf("solo:888", "solo:801"), t.keys.toList())
        assertEquals("solo:888", t["solo:888"]!!.label)
        assertTrue(t["solo:888"]!!.poolIds.isEmpty()) // 888 不在元数据 → pool_ids 空
        assertEquals("示例保底组2", t["solo:801"]!!.label)
        assertEquals(listOf("801"), t["solo:801"]!!.poolIds)

        val pd = e.poolDistribution()
        assertEquals(mapOf("示例保底组2" to 1, "未知(888)" to 1), pd)
        assertEquals(listOf("示例保底组2", "未知(888)"), pd.keys.toList())
    }

    // ---- 未知池统计（unknownPoolStats，PC `_unknown_pool_ids` 半边）----

    @Test
    fun `unknown pool stats counts uncollected pools in first-appearance order`() {
        val e = StatsEngine(
            CHARS,
            mapOf("101" to Pool("101", "示例保底组2", "常驻")),
        )
        e.loadRecords(
            listOf(
                rec("101", "1001", 1, pos = 1),                       // 已收录池 → 不计
                rec("888", "1001", 2, pos = 1),                       // 陌生池 #1
                rec("888", "2001", 3, pos = 1),                       // 同池出现 2 次
                rec("999", "1001", 4, pos = 1),                       // 陌生池 #2
                rec("888", StatsEngine.MANUAL_FILL_ID, 5, pos = 1),   // 手动补录 → 跳过
            )
        )
        val stats = e.unknownPoolStats()
        assertEquals(mapOf("888" to 2, "999" to 1), stats)
        assertEquals(listOf("888", "999"), stats.keys.toList()) // 首次出现序
    }

    @Test
    fun `unknown pool stats empty when all pools collected`() {
        val e = StatsEngine(CHARS, mapOf("101" to Pool("101", "示例保底组2", "常驻")))
        e.loadRecords(listOf(rec("101", "1001", 1, pos = 1), rec("101", "2001", 2, pos = 1)))
        assertTrue(e.unknownPoolStats().isEmpty())
    }

    // ---- D10 空记录 ----

    @Test
    fun `d10 empty records`() {
        val e = StatsEngine(CHARS, mapOf("101" to Pool("101", "示例卡池008", "新手")))
        assertEquals(0, e.total)
        assertTrue(e.sharedPityTracking().isEmpty())
        assertTrue(e.nonWarpRates().isEmpty())
        assertTrue(e.rarityDistribution().first.isEmpty())
        assertTrue(e.poolDistribution().isEmpty())
        assertTrue(e.unknownIdStats().isEmpty())
    }

    // ---- 排序 / 载入语义 ----

    @Test
    fun `order records for pity sorts ts asc then position desc`() {
        val e = StatsEngine(CHARS, emptyMap())
        val ordered = e.orderRecordsForPity(
            listOf(
                rec("p", "a", 200, pos = 5),
                rec("p", "b", 100, pos = 1),
                rec("p", "c", 200, pos = 9),
                rec("p", "d", 100, pos = 0),
            )
        )
        // ts 升序：100 组(pos 1,0) 在前，200 组(pos 9,5) 在后；组内位置降序
        assertEquals(listOf("b", "d", "c", "a"), ordered.map { it.itemId })
    }

    @Test
    fun `order within event returns single as is`() {
        val e = StatsEngine(CHARS, emptyMap())
        val only = rec("p", "x", 1, pos = 3)
        assertEquals(listOf(only), e.orderWithinEvent(1L, listOf(only)))
    }

    @Test
    fun `load records appends by default and replace clears`() {
        val e = StatsEngine(CHARS, emptyMap())
        e.loadRecords(listOf(rec("p", "a", 1)))
        e.loadRecords(listOf(rec("p", "b", 2)))
        assertEquals(2, e.total) // 默认追加（对齐 PC load_records replace=False）

        e.loadRecords(listOf(rec("p", "c", 3)), replace = true)
        assertEquals(1, e.total)

        e.clear()
        assertEquals(0, e.total)
    }
}
