package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GachaRecognizer 边界测试（T6，04 方案 §4）——现有 177 向量对拍只盖主路径，此处补异常输入。
 *
 * 每条 `timeout=5s`：varint/长度越界类若回归成死循环（readVarint 的 nextOffset 不前进 /
 * len<0 倒退），超时红而不是卡死构建（P0-1 同族回归防线）。
 *
 * S->C 帧体口径：[2B 类型][2B extId][2B result][1B seq回显][1B ack][业务体]；
 * 业务体记录 = tag(0x1A=field3,wireType2) + varint(len) + 记录体；
 * 记录体 = [0x08 pool][0x10 item][0x18 timestamp]（全 varint，三者齐全且 ts≠0 才命中）。
 */
class GachaRecognizerEdgeTest {

    // ---- 构造辅助（与 FrameSchema/解析器口径独立对齐，互不复用）----

    private fun varint(v: Long): ByteArray {
        val out = ArrayList<Byte>()
        var x = v
        while (true) {
            val b = (x and 0x7f).toInt()
            x = x ushr 7
            if (x != 0L) out.add((b or 0x80).toByte()) else { out.add(b.toByte()); break }
        }
        return out.toByteArray()
    }

    /** 一条记录：0x1A + len + [0x08 pool][0x10 item][0x18 ts]。 */
    private fun record(pool: Long, item: Long, ts: Long): ByteArray {
        val body = byteArrayOf(0x08) + varint(pool) + byteArrayOf(0x10) + varint(item) + byteArrayOf(0x18) + varint(ts)
        return byteArrayOf(0x1A.toByte()) + varint(body.size.toLong()) + body
    }

    /** GACHA 信封 S->C 帧体（8B 头 + 业务体）。 */
    private fun gachaBody(result: Int = 0, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(
            0x00, 0x0C, 0x08, 0x00,
            ((result ushr 8) and 0xff).toByte(), (result and 0xff).toByte(),
            0x00, 0x00,
        ) + payload

    // ---- 头部边界 ----

    @Test(timeout = 5_000)
    fun `e1 空帧体返回 false`() {
        assertFalse(GachaRecognizer.looksLikeGacha(ByteArray(0)))
    }

    @Test(timeout = 5_000)
    fun `e2 帧体不足 8b 返回 false`() {
        assertFalse(GachaRecognizer.looksLikeGacha(ByteArray(7)))
    }

    @Test(timeout = 5_000)
    fun `e3 已知信封但非抽卡类型 返回 false`() {
        // 心跳 0x0001/0x0200：known 但 msgType != GACHA
        val body = byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00) + ByteArray(4) { 0x41 }
        assertFalse(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `e4 result 非 ok 142 返回 false`() {
        val body = gachaBody(result = ResultCode.REJECTED, payload = record(1, 2, 3))
        assertFalse(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `e5 信封合法但业务体无记录 返回 false`() {
        assertFalse(GachaRecognizer.looksLikeGacha(gachaBody()))
    }

    // ---- 记录遍历边界 ----

    @Test(timeout = 5_000)
    fun `p1 最小合法单记录命中`() {
        val body = gachaBody(payload = record(pool = 1, item = 2, ts = 1700000000))
        assertTrue(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `e6 field3 长度越界 截断不崩 不死循环`() {
        // tag 0x1A + len=100，但业务体只剩 3 字节 → i+len>n → break
        val body = gachaBody(payload = byteArrayOf(0x1A, 100, 0x08, 0x01))
        assertFalse(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `e7 10 字节超长 varint 保值位 63 变负 截断不死循环`() {
        // 9×0xFF + 0x01：bit63 置位 → len<0 → break（readVarint 防护，P0-1 同族）
        val body = gachaBody(payload = byteArrayOf(0x1A, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x01, 0x08, 0x01))
        assertFalse(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `e8 varint 截断在帧尾 nextoffset 前进 不死循环`() {
        // len 字段以 0x80 结尾被截断：readVarint 停在已消费位置（i=n），外层 while 正常退出
        val body = gachaBody(payload = byteArrayOf(0x1A, -128)) // 0x80：还有后续但数据没了
        assertFalse(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `p2 非记录字段字节跳过后仍能命中`() {
        // 前置两个非 field3 字节（0x08/0x05：field1 wireType0）→ 外层只前移 1 字节，不影响后续记录
        val body = gachaBody(payload = byteArrayOf(0x08, 0x05) + record(1, 2, 3))
        assertTrue(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `e9 记录缺 timestamp ts=0 不计`() {
        val body = gachaBody(payload = record(1, 2, 0))
        assertFalse(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `p3 混合记录 ts=0 不计 完整记录命中`() {
        val payload = record(1, 2, 0) + record(3, 4, 5)
        val body = gachaBody(payload = payload)
        assertTrue("只要有一条完整记录即命中", GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `e10 记录缺 pool 字段 不计`() {
        // 只有 item+timestamp（0x10 0x02 0x18 0x05），无 0x08 pool → poolId 空
        val body = gachaBody(payload = byteArrayOf(0x1A, 0x04, 0x10, 0x02, 0x18, 0x05))
        assertFalse(GachaRecognizer.looksLikeGacha(body))
    }

    @Test(timeout = 5_000)
    fun `p4 多字段大 pool item 值 多字节 varint 正常`() {
        // pool=300（0xAC 0x02），item=7000000000（5 字节 varint），ts=1700000000
        val body = gachaBody(payload = record(300, 7000000000L, 1700000000))
        assertTrue(GachaRecognizer.looksLikeGacha(body))
    }
}
