package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FrameSplitter 鲁棒性回归 —— P0-1 死循环回归 + 脏输入终止性/行为一致（T2，手写）。
 *
 * 期望值以 PC `gacha_exporter/capture/engine.py::extract_frames` 实测锚定 + 逐字节推演复核
 * （2026-09-10，`mobile/docs/06-切帧对拍测试设计.md` §4.1 / §4.2）。每条向量带 `timeout`：
 * 切帧若回归成死循环，5s 后超时红，而不是卡死整个构建。
 *
 * 切帧语义（与 PC 一致）：4B **大端无符号**长度头；越界整帧在则跳 / 半包 break / 错位按
 * 「长度合理 + 信封自洽」重对齐 / 无候选保留尾部 3B。信封只用于重对齐评分,**不阻塞按长度
 * 切合法帧**（R5 的任意 8B body 照样切出）。
 */
class FrameSplitterRobustnessTest {

    private fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { ((it.toInt(16)) and 0xff).toByte() }.toByteArray()

    @Test(timeout = 5_000)
    fun `r1 负长度头 FF FF FF FC 不再死循环`() {
        // P0-1 直接回归：0xFFFFFFFC 转 Int = -4 → 修复前推进量 0 → 死循环。
        // 修复后 == PC：无候选 → 重对齐保留尾部 3B。
        val (frames, tail) = FrameSplitter.extract(hexBytes("FFFFFFFC" + "41".repeat(40)), Direction.SERVER_TO_CLIENT)
        assertEquals(0, frames.size)
        assertArrayEquals(hexBytes("414141"), tail)
    }

    @Test(timeout = 5_000)
    fun `r2 0x80000000 不再数组越界`() {
        // 同 P0-1 高阶位：转 Int 为负大数 → 越界/死循环类；修复后无害保留尾 3B。
        val (frames, tail) = FrameSplitter.extract(hexBytes("80000000" + "41".repeat(40)), Direction.SERVER_TO_CLIENT)
        assertEquals(0, frames.size)
        assertArrayEquals(hexBytes("414141"), tail)
    }

    @Test(timeout = 5_000)
    fun `r3 长度 0 超短帧走越界跳过`() {
        val (frames, tail) = FrameSplitter.extract(hexBytes("00000000" + "41".repeat(40)), Direction.SERVER_TO_CLIENT)
        assertEquals(0, frames.size)
        assertArrayEquals(hexBytes("414141"), tail)
    }

    @Test(timeout = 5_000)
    fun `r4 长度 3 小于 minLen 8 走越界跳过`() {
        val (frames, tail) = FrameSplitter.extract(hexBytes("00000003" + "41".repeat(40)), Direction.SERVER_TO_CLIENT)
        assertEquals(0, frames.size)
        assertArrayEquals(hexBytes("414141"), tail)
    }

    @Test(timeout = 5_000)
    fun `r5 合法 2 帧各 len8 正常路径不变`() {
        // 修复零污染：length 合法即按长度切，信封未知不阻塞（body 任意 8B）。
        val (frames, tail) = FrameSplitter.extract(
            hexBytes("00000008" + "41".repeat(8) + "00000008" + "42".repeat(8)),
            Direction.SERVER_TO_CLIENT,
        )
        assertEquals(2, frames.size)
        assertArrayEquals(hexBytes("4141414141414141"), frames[0].body)
        assertArrayEquals(hexBytes("4242424242424242"), frames[1].body)
        assertArrayEquals(hexBytes(""), tail)
    }

    @Test(timeout = 5_000)
    fun `r6 半包长度头后 data 不足 按半包等待并全保留`() {
        // len=100 但只有 54B → 4+100>54 半包 break，余留 = 4B 头 + 50B 全保留。
        val (frames, tail) = FrameSplitter.extract(hexBytes("00000064" + "43".repeat(50)), Direction.SERVER_TO_CLIENT)
        assertEquals(0, frames.size)
        assertArrayEquals(hexBytes("00000064" + "43".repeat(50)), tail) // 54B
    }

    @Test(timeout = 5_000)
    fun `r7 脏流重对齐到 p5 后按半包保留余留 11B`() {
        // DE AD BE EF + 00 00 00 08 + 8D：p=4 半包条件 4+8=12>12 不成立 → 否；
        // p=5 长度头 data[5:9]=00 00 08 44=2116 合法、body 7B<9 读不全 → 半包暂定通过 → 重对齐到 5，
        // 再读 2116 半包 break → 余留 data[5:] = 00 00 08 + 8D = 11B。
        // ⚠️ 推此向量最容易在 00 00 00 08 的三连 00 上错位（见 06 稿 §4.1 警告），期望值为 PC 实测锚定。
        val (frames, tail) = FrameSplitter.extract(
            hexBytes("DEADBEEF" + "00000008" + "44".repeat(8)),
            Direction.SERVER_TO_CLIENT,
        )
        assertEquals(0, frames.size)
        assertArrayEquals(hexBytes("000008" + "44".repeat(8)), tail) // 11B
    }
}