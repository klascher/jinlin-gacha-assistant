package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnnouncementParse] 单测 —— §15 §4.2 容错契约：缺 id 跳过、visible 再兜一次、
 * 单条坏不连坐、结构错回空列表（绝不抛）。
 */
class AnnouncementParseTest {

    private fun entry(
        id: String = "a-1",
        type: String = "update",
        title: String = "标题",
        content: String = "正文",
        ts: Long = 1780000000L,
        visible: Boolean? = null,
    ): String {
        val vis = visible?.let { ",\"visible\":$it" } ?: ""
        val ty = if (type.isEmpty()) "" else ",\"type\":\"$type\""
        return """{"id":"$id"$ty,"title":"$title","content":"$content","ts":$ts$vis}"""
    }

    private fun body(vararg entries: String) =
        """{"announcements":[${entries.joinToString(",")}]}"""

    // —— 正常路径 ——

    @Test
    fun `parses full entry`() {
        val list = AnnouncementParse.parse(body(entry(id = "x-1", type = "pinned", ts = 1790568000L)))
        assertEquals(1, list.size)
        assertEquals(Announcement("x-1", "pinned", "标题", "正文", 1790568000L), list[0])
    }

    @Test
    fun `missing type defaults to update`() {
        val list = AnnouncementParse.parse("""{"announcements":[{"id":"a","title":"t","content":"c","ts":1}]}""")
        assertEquals("update", list[0].type)
    }

    @Test
    fun `multiple entries keep server order`() {
        val list = AnnouncementParse.parse(body(entry(id = "b"), entry(id = "a")))
        assertEquals(listOf("b", "a"), list.map { it.id }) // 服务端已排序，端内不再排
    }

    // —— 容错 ——

    @Test
    fun `missing or blank id is skipped`() {
        val list = AnnouncementParse.parse(
            body(
                """{"title":"t","content":"c","ts":1}""", // 缺 id
                entry(id = "  "),                          // 空 id
                entry(id = "ok"),
            )
        )
        assertEquals(listOf("ok"), list.map { it.id })
    }

    @Test
    fun `visible false is filtered again client-side`() {
        val list = AnnouncementParse.parse(
            body(entry(id = "draft", visible = false), entry(id = "live", visible = true))
        )
        assertEquals(listOf("live"), list.map { it.id })
    }

    @Test
    fun `bad entry does not poison the rest`() {
        val list = AnnouncementParse.parse(
            body(
                """{"id":"no-ts","title":"t","content":"c"}""",     // 缺 ts
                """{"id":"bad-title","content":"c","ts":1}""",      // 缺 title
                """"just a string"""",                               // 合法 JSON 但非 dict（类型不对）
                entry(id = "fine"),
            )
        )
        assertEquals(listOf("fine"), list.map { it.id })
    }

    @Test
    fun `malformed json returns empty list not throw`() {
        assertTrue(AnnouncementParse.parse("{not json").isEmpty())
        assertTrue(AnnouncementParse.parse("""{"announcements":"oops"}""").isEmpty())
        assertTrue(AnnouncementParse.parse("""{"other":[]}""").isEmpty())
    }

    @Test
    fun `null or blank body returns empty list`() {
        assertTrue(AnnouncementParse.parse(null).isEmpty())
        assertTrue(AnnouncementParse.parse("  ").isEmpty())
    }
}
