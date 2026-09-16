package com.jinlin.gacha.assistant.core.meta

import com.jinlin.gacha.assistant.core.stats.PityGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MetaParser] 的解析语义回归 —— 期望值取自 PC `models.py` 的
 * `parse_character` / `parse_pool` / `parse_pity_groups` 真实行为（对照见
 * `_verify/mobile_meta_mirror_check.py`）。
 *
 * 注意：MiniJson 解出的整数恒为 [Long]，故构造输入时数值一律写 `6L` / `70L`
 * 以**忠实模拟真实解码产物**（不是笔误）。
 */
class MetaParserTest {

    // —— parseCharacter ——

    @Test
    fun `parse character keeps name rarity limited`() {
        val c = MetaParser.parseCharacter(
            "90000010",
            mapOf("limited" to true, "name" to "示例角色033", "rarity" to 6L),
        )
        assertEquals("90000010", c.id)
        assertEquals("示例角色033", c.name)
        assertEquals(6, c.rarity)
        assertTrue(c.limited)
    }

    @Test
    fun `parse character defaults name rarity limited`() {
        val c = MetaParser.parseCharacter("999", emptyMap())
        assertEquals("未知(999)", c.name) // 对齐 PC f"未知({item_id})"
        assertEquals(0, c.rarity)
        assertFalse(c.limited)
    }

    /** `limited` 显式为 false 与缺省同值，但都要能过（4/5 星角色不带该字段）。 */
    @Test
    fun `parse character without limited field is false`() {
        val c = MetaParser.parseCharacter("90000001", mapOf("name" to "示例角色029", "rarity" to 4L))
        assertFalse(c.limited)
        assertEquals(4, c.rarity)
    }

    // —— parsePool ——

    @Test
    fun `parse pool keeps all fields`() {
        val p = MetaParser.parsePool(
            "30001",
            mapOf(
                "name" to "示例卡池001",
                "pity_group" to "shared:UP",
                "rarity" to 6L,
                "type" to "UP",
                "up_character" to "示例角色014",
            ),
        )
        assertEquals("30001", p.id)
        assertEquals("示例卡池001", p.name)
        assertEquals("UP", p.type)
        assertEquals(6, p.rarity)
        assertEquals("示例角色014", p.upCharacter) // UP 角色的**名字**（非 id）
        assertEquals("shared:UP", p.pityGroup)
        assertEquals(0, p.order) // 缺省
        assertTrue(p.isUp)
        assertTrue(p.isFeatured)
    }

    @Test
    fun `parse pool defaults type unknown and strings empty`() {
        val p = MetaParser.parsePool("888", emptyMap())
        assertEquals("未知(888)", p.name)
        assertEquals("未知", p.type) // 对齐 PC data.get("type", "未知")
        assertEquals("", p.upCharacter)
        assertEquals("", p.pityGroup) // 空 = 未配 → 由 PoolGroups 兜底 solo
        assertEquals(0, p.rarity)
        assertFalse(p.isUp)
        assertFalse(p.isFeatured)
    }

    /** 「遴选」池 isFeatured=true（对齐 PC `Pool.is_featured`），但 isUp=false。 */
    @Test
    fun `selection pool is featured but not up`() {
        val p = MetaParser.parsePool("20002", mapOf("type" to "遴选", "rarity" to 6L))
        assertTrue(p.isFeatured)
        assertFalse(p.isUp)
    }

    // —— parsePityGroups ——

    @Test
    fun `parse pity groups keeps label hard pity has state`() {
        val g = MetaParser.parsePityGroups(
            mapOf(
                "shared:UP" to mapOf("hard_pity" to 70L, "has_state" to true, "label" to "示例保底组1"),
                "solo:10001" to mapOf("hard_pity" to 30L, "has_state" to false, "label" to "示例卡池008"),
            ),
        )
        assertEquals(2, g.size)
        assertEquals(PityGroup("shared:UP", "示例保底组1", 70, true), g["shared:UP"])
        assertEquals(PityGroup("solo:10001", "示例卡池008", 30, false), g["solo:10001"])
    }

    @Test
    fun `parse pity groups defaults label to gid and hard pity 70`() {
        val g = MetaParser.parsePityGroups(mapOf("shared:X" to emptyMap<String, Any?>()))
        assertEquals(PityGroup("shared:X", "shared:X", 70, false), g["shared:X"])
    }

    @Test
    fun `parse pity groups non dict yields empty`() {
        assertTrue(MetaParser.parsePityGroups(null).isEmpty())
        assertTrue(MetaParser.parsePityGroups("nope").isEmpty())
        assertTrue(MetaParser.parsePityGroups(listOf(1, 2)).isEmpty())
        assertTrue(MetaParser.parsePityGroups(5L).isEmpty())
    }

    /** 非对象的条目跳过（对齐 PC `if not isinstance(gdata, dict): continue`）。 */
    @Test
    fun `parse pity groups skips non object entries`() {
        val g = MetaParser.parsePityGroups(
            mapOf<String, Any?>("ok" to mapOf("label" to "L"), "bad" to 5L, "bad2" to listOf(1)),
        )
        assertEquals(1, g.size)
        assertTrue(g.containsKey("ok"))
    }

    // —— parse（整份）——

    @Test
    fun `parse builds three tables and version`() {
        val root: Map<String, Any?> = mapOf(
            "characters" to mapOf("1" to mapOf("name" to "A", "rarity" to 6L)),
            "pity_groups" to mapOf("shared:UP" to mapOf("label" to "示例保底组1", "hard_pity" to 70L, "has_state" to true)),
            "pools" to mapOf("9" to mapOf("name" to "P", "type" to "UP")),
            "version" to "1.0.6",
        )
        val meta = MetaParser.parse(root)
        assertEquals(1, meta.characters.size)
        assertEquals("A", meta.characters["1"]!!.name)
        assertEquals("UP", meta.pools["9"]!!.type)
        assertEquals("示例保底组1", meta.pityGroups["shared:UP"]!!.label)
        assertEquals("1.0.6", meta.version)
    }

    /** 三张表按 JSON 书写顺序保序（LinkedHashMap），使 UI 次序与 PC 一致。 */
    @Test
    fun `parse keeps insertion order`() {
        val root: Map<String, Any?> = mapOf(
            "characters" to linkedMapOf<String, Any?>(
                "c3" to mapOf("name" to "3"),
                "c1" to mapOf("name" to "1"),
                "c2" to mapOf("name" to "2"),
            ),
            "pools" to linkedMapOf<String, Any?>("p9" to mapOf("name" to "9"), "p1" to mapOf("name" to "1")),
        )
        val meta = MetaParser.parse(root)
        assertEquals(listOf("c3", "c1", "c2"), meta.characters.keys.toList())
        assertEquals(listOf("p9", "p1"), meta.pools.keys.toList())
    }

    /** 段缺失时降级为空集合、version 为空串，不抛（PC 在此会崩，见类注释）。 */
    @Test
    fun `parse tolerates missing sections`() {
        val meta = MetaParser.parse(
            mapOf<String, Any?>("characters" to emptyMap<String, Any?>(), "pools" to emptyMap<String, Any?>()),
        )
        assertTrue(meta.characters.isEmpty())
        assertTrue(meta.pools.isEmpty())
        assertTrue(meta.pityGroups.isEmpty())
        assertEquals("", meta.version)
    }

    /** Long → Int 收窄（MiniJson 数字恒 Long；漏收窄会编译期暴露，此处守语义）。 */
    @Test
    fun `parse narrows long rarity to int`() {
        val meta = MetaParser.parse(
            mapOf<String, Any?>(
                "characters" to mapOf("1" to mapOf("name" to "A", "rarity" to 6L)),
                "pools" to mapOf("9" to mapOf("rarity" to 6L, "type" to "UP")),
            ),
        )
        assertEquals(6, meta.characters["1"]!!.rarity)
        assertEquals(6, meta.pools["9"]!!.rarity)
    }
}
