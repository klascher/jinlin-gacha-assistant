package com.jinlin.gacha.assistant.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HmacSign] 单测 —— 与同一套 HMAC-SHA256 实现做**固定向量自检**。
 *
 * 向量由同一套 HMAC-SHA256 算法生成：
 * `hmac.new(secret, (key + str(ts)).encode(), sha256).hexdigest()`。
 *
 * 其中 `ts=1700000000` 的摘要首字节高位为 1，专门守住一处修正：
 * 早期示例代码写的 `"%02x".format(it)`（`it` 是 `Byte`）会**符号扩展**
 * 成 `ffffffb5`；必须 `it.toInt() and 0xff` 才是正确的两位十六进制。
 */
class HmacSignTest {

    private val key = "example-key"
    private val secret = "example-secret-not-real-0"

    @Test
    fun `matches pc auth py fixed vectors`() {
        val vectors = linkedMapOf(
            0L to "001a79f3153507ffa8255c0b8a3dc7b50730327275d6a4b7ea1901002ff13031",
            1234567890L to "6ba85871cf82628a2b7b8c84fbb4d6b6f7b1919a22094ceccf0609c5262be94b",
            1700000000L to "eb07d88095156718f24833e797a39759edae1a880ea86101c40d261cf0b82500",
            1757836000L to "0f98682b6013bba0377d2b04ca68667c2ee0a2a9367cdf92f888ba6c3e234106",
            4102444800L to "01024166a8269009ae404de4a23efb63be3d847729e2062190e89b8f603d6fec",
        )
        for ((ts, expected) in vectors) {
            assertEquals("ts=$ts", expected, HmacSign.sign(key, secret, ts))
        }
    }

    @Test
    fun `digest is always 64 lowercase hex chars`() {
        for (ts in listOf(1L, 99L, 1234567890L, 1700000000L, 4102444800L)) {
            val sig = HmacSign.sign(key, secret, ts)
            assertEquals("len ts=$ts", 64, sig.length)
            assertEquals("lowercase ts=$ts", sig.lowercase(), sig)
            assertTrue("hex ts=$ts", sig.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun `first byte with high bit set is not sign extended`() {
        // ts=1700000000 → 摘要首字节高位为 1，正是会触发符号扩展的形态。
        val sig = HmacSign.sign(key, secret, 1700000000L)
        assertEquals("eb07d880", sig.substring(0, 8))
        assertTrue("不应以 ffffff 开头：$sig", !sig.startsWith("ffffff"))
    }

    @Test
    fun `signing message has no separator`() {
        assertEquals("example-key1700000000", HmacSign.signingMessage("example-key", 1700000000L))
    }

    @Test
    fun `different timestamps yield different signatures`() {
        assertTrue(HmacSign.sign(key, secret, 1) != HmacSign.sign(key, secret, 2))
    }
}
