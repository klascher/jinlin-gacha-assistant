package com.jinlin.gacha.assistant.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PoolAssignments] 单测 —— 对拍 PC `gacha_exporter/storage/pool_assignments.py::apply_pool_assignments`。
 *
 * 只测纯合成半边（storage 半边由 `persistence.UnknownIdStore` 负责）。期望值来自 PC 同名函数语义：
 * 仅为「元数据未收录 且 指向已定义 pity_group」的陌生池合成 Pool；已收录 / 指向未定义组忽略。
 *
 * ⚠️ 仅开发环境无 Android 工具链 → Kotlin 未编译、本测试未执行；语义对拍不代表可编译。
 */
class PoolAssignmentsTest {

    private val gUp = PityGroup("g_up", "示例保底组1", hardPity = 70, hasState = true)
    private val gSel = PityGroup("g_sel", "示例保底组4", hardPity = 70, hasState = true)

    private fun pools() = linkedMapOf(
        "501" to Pool("501", "示例保底组1甲", "UP", upCharacter = "六星A", pityGroup = "g_up"),
        "502" to Pool("502", "示例保底组4", "遴选", upCharacter = "六星B", pityGroup = "g_sel"),
    )

    @Test
    fun `synthesizes unknown pool pointing at defined group`() {
        val res = PoolAssignments.apply(pools(), mapOf("g_up" to gUp), mapOf("888" to "g_up"))
        // 原 pools 不改动；新增一条合成池
        assertEquals(3, res.size)
        assertTrue(res.containsKey("501"))
        assertFalse("已收录池 501 不能被指派覆盖", res["501"]!!.id == "888")

        val p888 = res["888"]!!
        assertEquals("g_up", p888.pityGroup)
        assertEquals("UP", p888.type) // 取组内引用池 type（501 是 UP）
        assertEquals(PoolAssignments.UNKNOWN_POOL_ORDER, p888.order)
        assertEquals("未知(888)", p888.name)
    }

    @Test
    fun `ignores assignment to undefined group and already collected pool`() {
        val res = PoolAssignments.apply(
            pools(),
            mapOf("g_up" to gUp),
            mapOf(
                "999" to "nonexistent", // 指向未定义组 → 忽略
                "501" to "g_up",        // 已收录池 → 忽略（不覆盖合成）
            ),
        )
        assertEquals(2, res.size) // 501 + 502，原样
        assertFalse(res.containsKey("999"))
        assertEquals("示例保底组1甲", res["501"]!!.name)
    }

    @Test
    fun `type inherits featured ref pool`() {
        // 888 并入 g_sel（引用池是遴选）→ type=遴选（isFeatured=true）
        val res = PoolAssignments.apply(pools(), mapOf("g_up" to gUp, "g_sel" to gSel), mapOf("888" to "g_sel"))
        val p888 = res["888"]!!
        assertEquals("g_sel", p888.pityGroup)
        assertEquals("遴选", p888.type)
        assertTrue(p888.isFeatured)
    }

    @Test
    fun `no pity groups means nothing applies`() {
        val res = PoolAssignments.apply(pools(), emptyMap(), mapOf("888" to "g_up"))
        assertEquals(2, res.size)
        assertFalse(res.containsKey("888"))
    }
}