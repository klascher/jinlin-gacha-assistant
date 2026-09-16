package com.jinlin.gacha.assistant.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PoolGroups.resolveGroups] 单测 —— 对拍 PC `loaders/pool_groups.py`。
 *
 * 期望值全部来自 PC **真实执行**（见 `_verify/mobile_stats_mirror_check.py` 的 fixture）。
 */
class PoolGroupsTest {

    private fun pool(id: String, name: String, pityGroup: String = "") =
        Pool(id = id, name = name, type = "常驻", pityGroup = pityGroup)

    /** 合法引用：pool 归到已定义组，组配置原样保留。 */
    @Test
    fun `合法 pity_group 被采用`() {
        val groups = mapOf("g_up" to PityGroup("g_up", "示例保底组1", hardPity = 70, hasState = true))
        val pools = mapOf("201" to pool("201", "示例保底组1甲", pityGroup = "g_up"))

        val r = PoolGroups.resolveGroups(pools, groups)

        assertEquals("g_up", r.poolToGroup["201"])
        assertEquals(PityGroup("g_up", "示例保底组1", 70, true), r.groupConfigs["g_up"])
    }

    /** 空 pity_group → 兜底 solo：id=`solo:<pid>`、label=池名、hardPity=70、hasState=false。 */
    @Test
    fun `空 pity_group 兜底 solo`() {
        val r = PoolGroups.resolveGroups(mapOf("101" to pool("101", "示例卡池008")), emptyMap())

        assertEquals("solo:101", r.poolToGroup["101"])
        val solo = r.groupConfigs["solo:101"]!!
        assertEquals("示例卡池008", solo.label)
        assertEquals(70, solo.hardPity)
        assertFalse(solo.hasState)
    }

    /** 引用未定义组 → 同样兜底 solo（PC 只多打一条 warning，判定一致）。 */
    @Test
    fun `引用未定义组兜底 solo`() {
        val pools = mapOf("301" to pool("301", "示例保底组1甲", pityGroup = "g_missing"))
        val r = PoolGroups.resolveGroups(pools, emptyMap())

        assertEquals("solo:301", r.poolToGroup["301"])
        assertEquals("示例保底组1甲", r.groupConfigs["solo:301"]!!.label)
    }

    /** 多池共享同组：两个 pool 都归到同一个已定义组，组配置只一份。 */
    @Test
    fun `多池共享同组`() {
        val groups = mapOf("g_up" to PityGroup("g_up", "示例保底组1", hasState = true))
        val pools = mapOf(
            "301" to pool("301", "示例保底组1甲", pityGroup = "g_up"),
            "302" to pool("302", "示例保底组1乙", pityGroup = "g_up"),
            "101" to pool("101", "示例卡池008"),
        )
        val r = PoolGroups.resolveGroups(pools, groups)

        assertEquals("g_up", r.poolToGroup["301"])
        assertEquals("g_up", r.poolToGroup["302"])
        assertEquals("solo:101", r.poolToGroup["101"])
        // 每个 pool 都有归属，不落空
        assertTrue(r.poolToGroup.keys.containsAll(listOf("301", "302", "101")))
        // 兜底 solo 组配置被补齐
        assertTrue(r.groupConfigs.containsKey("solo:101"))
    }

    /** 空 pools → 空结果（不崩）。 */
    @Test
    fun `空 pools 返回空`() {
        val r = PoolGroups.resolveGroups(emptyMap(), emptyMap())
        assertTrue(r.poolToGroup.isEmpty())
        assertTrue(r.groupConfigs.isEmpty())
    }
}
