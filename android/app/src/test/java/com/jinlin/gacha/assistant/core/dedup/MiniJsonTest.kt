package com.jinlin.gacha.assistant.core.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [MiniJson] 单测 —— 覆盖历史 payload 需要的 JSON 子集：原语、转义、嵌套、缩进空白、
 * `\uXXXX`、数字类型、以及非法文本的报错路径。
 *
 * 这些用例的意义在于：`HistoryStore` 的正确性建立在「解码出的结构与重新构造的 payload
 * 结构判等」之上（内容幂等闸），所以解码器必须能读回 PC `json.dump(indent=2)` 的输出。
 */
class MiniJsonTest {

    @Test
    fun `encode primitives`() {
        assertEquals("null", MiniJson.encode(null))
        assertEquals("true", MiniJson.encode(true))
        assertEquals("false", MiniJson.encode(false))
        assertEquals("123", MiniJson.encode(123))
        assertEquals("123", MiniJson.encode(123L))
        assertEquals("\"a\"", MiniJson.encode("a"))
        assertEquals("1.5", MiniJson.encode(1.5))
    }

    @Test
    fun `encode escapes control chars and quotes`() {
        assertEquals("\"a\\nb\"", MiniJson.encode("a\nb"))
        assertEquals("\"q\\\"q\"", MiniJson.encode("q\"q"))
        assertEquals("\"\\\\\"", MiniJson.encode("\\"))
        assertEquals("\"\\t\"", MiniJson.encode("\t"))
        assertEquals("\"\\u0001\"", MiniJson.encode("\u0001"))
    }

    @Test
    fun `roundtrip history payload keeps structure`() {
        val payload = linkedMapOf<String, Any?>(
            "records" to arrayListOf<Any?>(
                linkedMapOf<String, Any?>(
                    "pool_id" to "0",
                    "item_id" to "100",
                    "timestamp" to 1700000000000L,
                    "batch_seq" to 0L,
                    "position_in_batch" to 3L,
                )
            ),
            "view_totals" to linkedMapOf<String, Any?>("0@0" to 521L),
            "total" to 521L,
        )
        assertEquals(payload, MiniJson.decode(MiniJson.encodePretty(payload, 2)))
        assertEquals(payload, MiniJson.decode(MiniJson.encode(payload)))
    }

    @Test
    fun `decode python style pretty printed file`() {
        // 与 PC json.dump(..., ensure_ascii=False, indent=2) 等价的输出形态
        val text = """
            {
              "records": [
                {
                  "pool_id": "0",
                  "item_id": "100",
                  "timestamp": 1700000000000,
                  "batch_seq": 0,
                  "position_in_batch": 0
                }
              ],
              "view_totals": {
                "0@0": 521
              },
              "total": null
            }
        """.trimIndent()
        val root = MiniJson.decode(text) as Map<*, *>
        assertNull(root["total"])
        val recs = root["records"] as List<*>
        assertEquals(1, recs.size)
        val rec = recs[0] as Map<*, *>
        assertEquals("0", rec["pool_id"])
        assertEquals(1700000000000L, rec["timestamp"])
        assertEquals(0L, rec["position_in_batch"])
    }

    @Test
    fun `decode numbers unicode and empty containers`() {
        assertEquals(1L, MiniJson.decode("1"))
        assertEquals(-2L, MiniJson.decode("-2"))
        assertEquals(1000.0, MiniJson.decode("1e3"))
        assertEquals(1.5, MiniJson.decode("1.5"))
        assertEquals("中", MiniJson.decode("\"\\u4e2d\""))
        assertEquals("a/b", MiniJson.decode("\"a\\/b\""))
        assertEquals(emptyMap<String, Any?>(), MiniJson.decode("{}"))
        assertEquals(emptyList<Any?>(), MiniJson.decode("[]"))
        assertEquals(emptyMap<String, Any?>(), MiniJson.decode("  {  }  "))
    }

    @Test
    fun `decode rejects malformed text`() {
        assertThrows(MiniJson.JsonError::class.java) { MiniJson.decode("") }
        assertThrows(MiniJson.JsonError::class.java) { MiniJson.decode("{") }
        assertThrows(MiniJson.JsonError::class.java) { MiniJson.decode("{} x") }
        assertThrows(MiniJson.JsonError::class.java) { MiniJson.decode("{'a': 1}") }
        assertThrows(MiniJson.JsonError::class.java) { MiniJson.decode("[1,]") }
        assertThrows(MiniJson.JsonError::class.java) { MiniJson.decode("\"unterminated") }
    }
}
