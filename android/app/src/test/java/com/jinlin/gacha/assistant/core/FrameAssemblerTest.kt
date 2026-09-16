package com.jinlin.gacha.assistant.core

import com.jinlin.gacha.assistant.vpn.IPPacket
import com.jinlin.gacha.assistant.vpn.ParsedIpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrameAssembler 行为测试（T3，04 方案 §4）——重组层主路径 + 水位兜底。
 *
 * 覆盖：按序切帧 / 粘包 / 半包续段 / 乱序补齐 / 重传去重 / 多流隔离（含方向）/
 * 消费者回调 / 缺口跳过（乱序条数水位，P1-4）/ tail 1MB 兜底清空 / 空闲回收 sweep。
 * 全部纯 JVM（FrameAssembler 只依赖 FrameSplitter/ParsedIpPacket，无 android 调用）。
 *
 * 帧口径：帧 = 4B 大端长度头 + 帧体；S->C 帧体 = [2B 类型][2B extId][2B result]
 * [1B seq回显][1B ack][业务体]。测试用 12B 体（8B 头 + 4B 业务体）的最小合法帧。
 */
class FrameAssemblerTest {

    private companion object {
        const val SERVER_IP = "203.0.113.7"
        const val SERVER_PORT = 18085
        const val CLIENT_IP = "10.8.0.2"
        val S2C = Direction.SERVER_TO_CLIENT
    }

    /** S->C 最小合法帧体：GACHA 信封 8B 头 + 4B 业务体（业务体内容用 filler 填充）。 */
    private fun s2cBody(filler: Int): ByteArray =
        byteArrayOf(0x00, 0x0C, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00) +
            ByteArray(4) { filler.toByte() }

    /** 帧 = 4B 大端长度头 + 帧体。 */
    private fun frameBytes(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = ((body.size ushr 24) and 0xff).toByte()
        out[1] = ((body.size ushr 16) and 0xff).toByte()
        out[2] = ((body.size ushr 8) and 0xff).toByte()
        out[3] = (body.size and 0xff).toByte()
        body.copyInto(out, 4)
        return out
    }

    private fun tcpPkt(srcPort: Int, seq: Long, payload: ByteArray): ParsedIpPacket =
        ParsedIpPacket(
            protocol = IPPacket.PROTO_TCP,
            srcIp = SERVER_IP, dstIp = CLIENT_IP,
            srcPort = srcPort, dstPort = 44444,
            raw = ByteArray(0), length = 0,
            isTcp = true, isUdp = false,
            tcpSeq = seq, tcpPayload = payload, tcpFlags = IPPacket.FLAG_ACK or IPPacket.FLAG_PSH,
        )

    private class RecordingConsumer : FrameConsumer {
        val frames = mutableListOf<Pair<Frame, Direction>>()
        override fun onFrame(frame: Frame, direction: Direction) {
            frames.add(frame to direction)
        }
    }

    // ---- 主路径 ----

    @Test(timeout = 5_000)
    fun `t1 按序两段 各切一帧`() {
        val asm = FrameAssembler()
        val f1 = frameBytes(s2cBody(0x11))
        val f2 = frameBytes(s2cBody(0x22))
        val out1 = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = f1), S2C)
        val out2 = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100L + f1.size, payload = f2), S2C)
        assertEquals(1, out1.size)
        assertEquals(1, out2.size)
        assertArrayEquals(s2cBody(0x11), out1[0].body)
        assertArrayEquals(s2cBody(0x22), out2[0].body)
        assertTrue("信封应为已知抽卡", out1[0].header?.known == true)
        assertEquals(1, asm.flowCount())
    }

    @Test(timeout = 5_000)
    fun `t2 粘包 一段含两个帧一次切出`() {
        val asm = FrameAssembler()
        val two = frameBytes(s2cBody(0x11)) + frameBytes(s2cBody(0x22))
        val out = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = two), S2C)
        assertEquals(2, out.size)
        assertArrayEquals(s2cBody(0x22), out[1].body)
    }

    @Test(timeout = 5_000)
    fun `t3 半包 帧拆两段 续段后切出且无重复`() {
        val asm = FrameAssembler()
        val whole = frameBytes(s2cBody(0x33))
        val cut = whole.size / 2
        val out1 = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = whole.copyOfRange(0, cut)), S2C)
        val out2 = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100L + cut, payload = whole.copyOfRange(cut, whole.size)), S2C)
        assertEquals(0, out1.size)
        assertEquals(1, out2.size)
        assertArrayEquals(s2cBody(0x33), out2[0].body)
    }

    @Test(timeout = 5_000)
    fun `t4 乱序 第3段先到 第2段补齐后两帧一次出`() {
        val asm = FrameAssembler()
        val f1 = frameBytes(s2cBody(0x11))
        val f2 = frameBytes(s2cBody(0x22))
        val f3 = frameBytes(s2cBody(0x33))
        // 先用第 1 段建立 expectedSeq（首个数据段定基准）
        assertEquals(1, asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = f1), S2C).size)
        // 第 3 段先到：进乱序缓冲，无帧产出
        assertEquals(0, asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100L + f1.size + f2.size, payload = f3), S2C).size)
        // 第 2 段补齐：drain 从 expectedSeq 连续拼，两帧都出
        val out = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100L + f1.size, payload = f2), S2C)
        assertEquals(2, out.size)
        assertArrayEquals(s2cBody(0x22), out[0].body)
        assertArrayEquals(s2cBody(0x33), out[1].body)
    }

    @Test(timeout = 5_000)
    fun `t5 重传去重 不重复切帧 不污染后续`() {
        val asm = FrameAssembler()
        val f1 = frameBytes(s2cBody(0x11))
        val f2 = frameBytes(s2cBody(0x22))
        assertEquals(1, asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = f1), S2C).size)
        // 同 seq 同载荷重传：expectedSeq 已越过 → 丢弃
        assertEquals(0, asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = f1), S2C).size)
        // 后续正常段不受影响
        val out = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100L + f1.size, payload = f2), S2C)
        assertEquals(1, out.size)
        assertArrayEquals(s2cBody(0x22), out[0].body)
    }

    @Test(timeout = 5_000)
    fun `t6 多流隔离 4 元组各自 seq 空间互不串扰`() {
        val asm = FrameAssembler()
        val f = frameBytes(s2cBody(0x11))
        // 流 A：端口 18085；流 B：端口 18086（不同 4 元组，seq 空间独立）
        val a = asm.onTcpSegment(tcpPkt(srcPort = SERVER_PORT, seq = 100, payload = f), S2C)
        val b = asm.onTcpSegment(tcpPkt(srcPort = SERVER_PORT + 1, seq = 9000, payload = f), S2C)
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertEquals(2, asm.flowCount())
    }

    @Test(timeout = 5_000)
    fun `t7 非 tcp 包直接忽略 不建流`() {
        val asm = FrameAssembler()
        val udp = ParsedIpPacket(
            protocol = IPPacket.PROTO_UDP,
            srcIp = SERVER_IP, dstIp = CLIENT_IP,
            srcPort = 53, dstPort = 44444,
            raw = ByteArray(0), length = 0,
            isTcp = false, isUdp = true,
        )
        assertEquals(0, asm.onTcpSegment(udp, S2C).size)
        assertEquals(0, asm.flowCount())
    }

    @Test(timeout = 5_000)
    fun `t8 消费者回调 帧数与方向一致`() {
        val consumer = RecordingConsumer()
        val asm = FrameAssembler(consumer = consumer)
        val f = frameBytes(s2cBody(0x11))
        asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = f), S2C)
        asm.onTcpSegment(tcpPkt(SERVER_PORT + 1, seq = 100, payload = f), Direction.CLIENT_TO_SERVER)
        assertEquals(2, consumer.frames.size)
        assertEquals(S2C, consumer.frames[0].second)
        assertEquals(Direction.CLIENT_TO_SERVER, consumer.frames[1].second)
    }

    // ---- 水位兜底（P1-4 相关）----

    @Test(timeout = 10_000)
    fun `t9 乱序条数越上限 256 强制跳过缺口`() {
        val asm = FrameAssembler()
        val f1 = frameBytes(s2cBody(0x11))
        // 先建立 expectedSeq（第 1 段正常切出）
        assertEquals(1, asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = f1), S2C).size)
        // 缺口段：expectedSeq 停在 116，段在 2000（缺口不愈合，滞留乱序缓冲）
        assertEquals(0, asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 2000, payload = ByteArray(10) { 0x41 }), S2C).size)
        // 再灌 256 段 → 乱序条数 257 > 256 触发强制跳缺口
        for (i in 0 until 256) {
            asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 3000L + i, payload = byteArrayOf(0x42)), S2C)
        }
        assertEquals("缺口应被强制跳过 1 次", 1L, asm.skippedGapCount)
        assertEquals("不应清 tail（tail 未超 1MB）", 0L, asm.clearedTailCount)
    }

    @Test(timeout = 10_000)
    fun `t10 tail 超 1mb 水位清空后可继续正常切帧`() {
        val asm = FrameAssembler()
        fun be32(v: Int): ByteArray = byteArrayOf(
            ((v ushr 24) and 0xff).toByte(), ((v ushr 16) and 0xff).toByte(),
            ((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte(),
        )
        // 一段「合法长度头 40000 + 大量填充」：长度头 40000 在 [8,50000] 内 → 先切出 1 个
        // 40000 字节完整帧；余下 ~574KB 全为 0x41，作帧头读成长度 0x41414141 远超标，
        // resync 无候选 → 整段沦为「未成帧」尾缓冲
        // 单段 >1MB：drain 先把整个段 append 进 tailBuf、尚未轮到切帧就超 SPLIT_CAP(1MB)
        // → 兜底清空（splitter 对 >maxLen 的头部走「保留 3B + 丢弃」分支不会积出大尾，
        //   唯有整段本身超过 1MB 水位才走这里）
        val hugeTail = be32(50000) + ByteArray(1 shl 20 + 65536) { 0x41 } // 4 + 1MB+64KB > SPLIT_CAP
        assertEquals(0, asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = hugeTail), S2C).size)
        assertEquals("tail 超 1MB 兜底应触发清空", 1L, asm.clearedTailCount)
        // 清空后状态干净：正常帧照常切出
        val f = frameBytes(s2cBody(0x11))
        val out = asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100L + hugeTail.size, payload = f), S2C)
        assertEquals(1, out.size)
        assertArrayEquals(s2cBody(0x11), out[0].body)
    }

    @Test(timeout = 5_000)
    fun `t11 sweep 空闲流回收 计数与流表一致`() {
        val asm = FrameAssembler()
        val f = frameBytes(s2cBody(0x11))
        asm.onTcpSegment(tcpPkt(SERVER_PORT, seq = 100, payload = f), S2C)
        asm.onTcpSegment(tcpPkt(SERVER_PORT + 1, seq = 100, payload = f), S2C)
        assertEquals(2, asm.flowCount())
        // 刚活跃：未超 idle → 不回收
        assertEquals(0, asm.sweep(idleMs = 60_000, now = System.currentTimeMillis()))
        // 超时 → 全回收
        val removed = asm.sweep(idleMs = 60_000, now = System.currentTimeMillis() + 120_000)
        assertEquals(2, removed)
        assertEquals(0, asm.flowCount())
        assertEquals(2L, asm.sweptFlowCount)
    }
}
