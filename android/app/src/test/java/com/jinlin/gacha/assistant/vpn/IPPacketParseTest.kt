package com.jinlin.gacha.assistant.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IPPacket 解析器边界测试（T5，04 方案 §4）。
 *
 * 原则：**测试内独立构造 IP 字节**（不复用 PacketBuilder），避免「构造器 bug 掩盖解析器 bug」
 * 的自证。解析器不校验和，构造时校验和写 0 即可。
 *
 * 覆盖：IPv4 最小 TCP / IPv6 TCP / UDP / 纯 ACK 无载荷 / 带选项 TCP 头（dataOffset=24）/
 * 截断（totalLen > 缓冲）/ IHL<20 / 未知版本 / totalLen<ihl / TCP 头不完整（只有端口域）。
 */
class IPPacketParseTest {

    // ---- 独立字节构造 ----

    private fun ipv4Header(proto: Int, totalLen: Int, src: ByteArray, dst: ByteArray): ByteArray {
        val h = ByteArray(20)
        h[0] = 0x45
        h[2] = ((totalLen ushr 8) and 0xff).toByte(); h[3] = (totalLen and 0xff).toByte()
        h[8] = 64
        h[9] = proto.toByte()
        src.copyInto(h, 12)
        dst.copyInto(h, 16)
        return h
    }

    private fun tcpHeader(srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, dataOffsetWords: Int = 5): ByteArray {
        val len = dataOffsetWords * 4
        val h = ByteArray(len)
        h[0] = ((srcPort ushr 8) and 0xff).toByte(); h[1] = (srcPort and 0xff).toByte()
        h[2] = ((dstPort ushr 8) and 0xff).toByte(); h[3] = (dstPort and 0xff).toByte()
        for (i in 0 until 4) h[4 + i] = ((seq ushr (24 - 8 * i)) and 0xff).toByte()
        for (i in 0 until 4) h[8 + i] = ((ack ushr (24 - 8 * i)) and 0xff).toByte()
        h[12] = ((dataOffsetWords shl 4) and 0xff).toByte()
        h[13] = flags.toByte()
        return h
    }

    private fun ip4bytes(src: String): ByteArray = src.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun buildV4Tcp(
        srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int,
        payload: ByteArray = ByteArray(0),
        dataOffsetWords: Int = 5,
        declaredTotalLen: Int = -1,   // -1 = 按实际
        providedLen: Int = -1,        // parse 的 length 参数，-1 = 全量
        ihlOverride: Int = -1,        // 覆盖 ihl 字段（字节，如 16 模拟 IHL=4）
        versionOverride: Int = -1,    // 覆盖 version nibble
    ): ByteArray {
        val tcpLen = dataOffsetWords * 4 + payload.size
        val total = 20 + tcpLen
        // 缓冲按「实际内容」扩张（IP 头 20B + TCP 头 + 载荷）；挂载的头部字段独立填 declared/实际
        val buf = ByteArray(total)
        ipv4Header(IPPacket.PROTO_TCP, if (declaredTotalLen >= 0) declaredTotalLen else total, ip4bytes("203.0.113.7"), ip4bytes("10.8.0.2")).copyInto(buf, 0)
        tcpHeader(srcPort, dstPort, seq, ack, flags, dataOffsetWords).copyInto(buf, 20)
        payload.copyInto(buf, 20 + dataOffsetWords * 4)
        if (ihlOverride > 0) buf[0] = ((0x40 or (ihlOverride / 4)) and 0xff).toByte()
        if (versionOverride > 0) buf[0] = (((versionOverride shl 4) or 5) and 0xff).toByte()
        return if (providedLen >= 0) buf.copyOf(providedLen) else buf
    }

    // ---- IPv4 主路径 ----

    @Test(timeout = 5_000)
    fun `v1 ipv4-tcp 带载荷全字段解析`() {
        val buf = buildV4Tcp(18085, 44444, seq = 3000, ack = 100001, flags = IPPacket.FLAG_PSH or IPPacket.FLAG_ACK, payload = "abc".toByteArray())
        val p = IPPacket.parse(buf, buf.size)
        assertNotNull(p)
        p!!
        assertEquals(IPPacket.PROTO_TCP, p.protocol)
        assertEquals("203.0.113.7", p.srcIp)
        assertEquals("10.8.0.2", p.dstIp)
        assertEquals(18085, p.srcPort)
        assertEquals(44444, p.dstPort)
        assertTrue(p.isTcp)
        assertEquals(3000L, p.tcpSeq)
        assertEquals(100001L, p.tcpAck)
        assertEquals(20, p.tcpDataOffset)
        assertArrayEquals("abc".toByteArray(), p.tcpPayload)
        assertEquals(IPPacket.FLAG_PSH or IPPacket.FLAG_ACK, p.tcpFlags)
        assertEquals(buf.size, p.length)
    }

    @Test(timeout = 5_000)
    fun `v2 纯 ack 无载荷 payload 为空数组`() {
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK)
        val p = IPPacket.parse(buf, buf.size)!!
        assertTrue(p.tcpPayload.isEmpty())
    }

    @Test(timeout = 5_000)
    fun `v3 带选项 tcp 头 dataOffset=24 载荷从选项后开始`() {
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_SYN or IPPacket.FLAG_ACK, payload = "xy".toByteArray(), dataOffsetWords = 6)
        // 写入 MSS 选项（kind=2 len=4 value=4056）到选项区（IP 头 20 + TCP 固定头 20 = offset 40）
        val opts = byteArrayOf(0x02, 0x04, 0x0f, 0xd8.toByte())
        System.arraycopy(opts, 0, buf, 40, 4)
        val p = IPPacket.parse(buf, buf.size)!!
        assertEquals(24, p.tcpDataOffset)
        // 载荷从 ihl+dataOffset 开始，选项区不在 payload 内
        assertArrayEquals("xy".toByteArray(), p.tcpPayload)
    }

    @Test(timeout = 5_000)
    fun `v4 ipv4-udp 载荷解析`() {
        val total = 20 + 8 + 4
        val buf = ByteArray(total)
        ipv4Header(IPPacket.PROTO_UDP, total, ip4bytes("8.8.8.8"), ip4bytes("10.8.0.2")).copyInto(buf, 0)
        val udp = ByteArray(8 + 4)
        udp[0] = 0x00; udp[1] = 0x35       // sport 53
        udp[2] = 0xc7.toByte(); udp[3] = 0xa7.toByte()       // dport 51111
        udp[4] = ((total - 20) ushr 8).toByte(); udp[5] = ((total - 20) and 0xff).toByte()
        "abcd".toByteArray().copyInto(udp, 8)
        udp.copyInto(buf, 20)
        val p = IPPacket.parse(buf, buf.size)!!
        assertTrue(p.isUdp)
        assertEquals(53, p.srcPort)
        assertEquals(51111, p.dstPort)
        assertArrayEquals("abcd".toByteArray(), p.udpPayload)
        assertTrue(!p.isTcp)
        assertEquals(0, p.tcpFlags)
    }

    // ---- 边界 / 畸形 ----

    @Test(timeout = 5_000)
    fun `e1 截断 totalLen 大于缓冲 返回 null 等更多字节`() {
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK, declaredTotalLen = 100)
        assertNull(IPPacket.parse(buf, buf.size))
    }

    @Test(timeout = 5_000)
    fun `e2 缓冲不足 20b 返回 null`() {
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK)
        assertNull(IPPacket.parse(buf, 19))
    }

    @Test(timeout = 5_000)
    fun `e3 ihl 小于 20 返回 null`() {
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK, ihlOverride = 16)
        assertNull(IPPacket.parse(buf, buf.size))
    }

    @Test(timeout = 5_000)
    fun `e4 未知版本 返回 null`() {
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK, versionOverride = 3)
        assertNull(IPPacket.parse(buf, buf.size))
    }

    @Test(timeout = 5_000)
    fun `e5 totalLen 小于 ihl 返回 null`() {
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK, declaredTotalLen = 10)
        assertNull(IPPacket.parse(buf, buf.size))
    }

    @Test(timeout = 5_000)
    fun `e6 tcp 头不完整 只有端口域 端口可解 载荷空`() {
        // totalLen = ihl + 5（TCP 头只有 5B）——端口域可解析，seq/flags 不可
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK, declaredTotalLen = 25)
        val p = IPPacket.parse(buf, buf.size)
        assertNotNull(p)
        p!!
        assertEquals(18085, p.srcPort)
        assertEquals(44444, p.dstPort)
        assertTrue(p.tcpPayload.isEmpty())
        assertEquals(-1L, p.tcpSeq)
        assertEquals(0, p.tcpFlags)
    }

    @Test(timeout = 5_000)
    fun `e7 dataOffset 小于 20 且体不足一个完整 TCP 头 载荷置空不越界切`() {
        // dataOffsetWords=4（声明 16B 头），但 totalLen-ihl=18 < 20：TCP 头不完整，
        // 生产守卫（需满 20B 头才解析 TCP 字段）不进入——偏移保持未知(0)、载荷绝不越界切
        val buf = buildV4Tcp(18085, 44444, seq = 1, ack = 2, flags = IPPacket.FLAG_ACK, payload = "zz".toByteArray(), dataOffsetWords = 4)
        val p = IPPacket.parse(buf, buf.size)!!
        assertEquals(0, p.tcpDataOffset)   // 头不完整 → 不声称偏移（防误切）
        assertTrue("体不足 20B，不得越界切载荷", p.tcpPayload.isEmpty())
    }

    // ---- IPv6 ----

    @Test(timeout = 5_000)
    fun `v5 ipv6-tcp 最小头全字段解析`() {
        val total = 40 + 20 + 3
        val buf = ByteArray(total)
        buf[0] = 0x60
        buf[4] = ((total - 40) ushr 8).toByte(); buf[5] = ((total - 40) and 0xff).toByte()
        buf[6] = IPPacket.PROTO_TCP.toByte()
        // src 2001:db8::1 → 头内偏移 8..23（buf[8..11]=2001:0db8，buf[23]=1）
        buf[8] = 0x20; buf[9] = 0x01; buf[10] = 0x0d; buf[11] = 0xb8.toByte(); buf[23] = 1
        // dst 头内偏移 24..39：末 4 字节（buf[36..39]）= 1.2.3.4
        buf[36] = 1; buf[37] = 2; buf[38] = 3; buf[39] = 4
        tcpHeader(18085, 44444, seq = 42, ack = 43, flags = IPPacket.FLAG_ACK).copyInto(buf, 40)
        "abc".toByteArray().copyInto(buf, 60)

        val p = IPPacket.parse(buf, buf.size)!!
        assertTrue(p.isTcp)
        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0001", p.srcIp)
        assertEquals("0000:0000:0000:0000:0000:0000:0102:0304", p.dstIp)
        assertEquals(18085, p.srcPort)
        assertEquals(44444, p.dstPort)
        assertEquals(42L, p.tcpSeq)
        assertEquals(43L, p.tcpAck)
        assertArrayEquals("abc".toByteArray(), p.tcpPayload)
    }

    @Test(timeout = 5_000)
    fun `v6 ipv6 截断 返回 null`() {
        val total = 40 + 20
        val buf = ByteArray(total)
        buf[0] = 0x60
        buf[4] = ((total - 40) ushr 8).toByte(); buf[5] = ((total - 40) and 0xff).toByte()
        buf[6] = IPPacket.PROTO_TCP.toByte()
        tcpHeader(1, 2, seq = 0, ack = 0, flags = IPPacket.FLAG_ACK).copyInto(buf, 40)
        // 实际只给一半
        assertNull(IPPacket.parse(buf, 30))
    }
}
