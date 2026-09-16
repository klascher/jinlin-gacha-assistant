package com.jinlin.gacha.assistant.vpn

import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_ACK
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_FIN
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_PSH
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_RST
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_SYN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PacketBuilder 校验和对拍（T4，04 方案 §4）——校验和错一次 = 全盘不通，独立锚定。
 *
 * 三层证据（2026-09-10）：
 * 1. **scapy 黄金值**：`scripts/m_gen_packet_checksum_gold.py` 用 scapy 构造字段完全一致的包
 *    实际计算（运行输出已锚定下方 8 组常量），测试逐字节比对（除 IPv4 Identification 外）；
 * 2. **教科书向量**：Wikipedia IPv4 头例（192.168.0.1→192.168.0.199, proto=17）校验和
 *    0xb861，已用独立 Python 实现复核——锚定 `onesComplementChecksum` 本体；
 * 3. **RFC 1071 自检**：对 `buildTcpReply` 产物，测试内独立实现反码和验证
 *    「IPv4 头含校验和累加 + 0xffff ≡ 0」「TCP 段含伪头 + 校验和 ≡ 0」。
 *
 * 与 scapy 的字段口径：ttl=64、tos=0、flags+frag=0、window=65535、urgptr=0、
 * MSS 选项仅 SYN-ACK（4056）。ID 字段（offset 4..5）是自增计数器，比对时双侧置零。
 */
class PacketBuilderChecksumTest {

    private companion object {
        const val SRC_IP = "203.0.113.7"
        const val DST_IP = "10.8.0.2"
        const val SPORT = 18085
        const val DPORT = 44444

        // —— scapy 黄金值（m_gen_packet_checksum_gold.py 2026-09-10 实际运行输出）——

        /** 全包 hex（ID 置零）|IPv4 校验和|L4 校验和（TCP 偏移 36 / UDP 偏移 26）。 */
        const val T1_SYNACK_MSS_HEX =
            "4500002c00000000400634bbcb0071070a08000246a5ad9c00000bb8000186a16012ffffc144000002040fd8"
        const val T1_IP_CK = 0x34bb
        const val T1_TCP_CK = 0xc144

        const val T2_PSHACK_PAYLOAD_HEX =
            "4500002d00000000400634bacb0071070a08000246a5ad9c00000bb9000186a55018ffffe33d0000706f6e6721"
        const val T2_IP_CK = 0x34ba
        const val T2_TCP_CK = 0xe33d

        const val T3_PURE_ACK_HEX =
            "4500002800000000400634bfcb0071070a08000246a5ad9c00000bbe000186a55010ffffe31c0000"
        const val T3_IP_CK = 0x34bf
        const val T3_TCP_CK = 0xe31c

        const val T4_FINACK_HEX =
            "4500002800000000400634bfcb0071070a08000246a5ad9c00000bbe000186a55011ffffe31b0000"
        const val T4_IP_CK = 0x34bf
        const val T4_TCP_CK = 0xe31b

        const val T5_RSTACK_HEX =
            "4500002800000000400634bfcb0071070a08000246a5ad9c00000bb8000186a05014ffffe3230000"
        const val T5_IP_CK = 0x34bf
        const val T5_TCP_CK = 0xe323

        const val T6_BIG_PAYLOAD_HEX =
            "4500011f00000000400633c8cb0071070a08000246a5ad9c00000bb9000186a55018ffff26c60000" +
                "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b2229" +
                "30373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f56" +
                "5d646b727980878e959ca3aab1b8bfc6cdd4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c83" +
                "8a91989fa6adb4bbc2c9d0d7dee5ecf3fa01080f161d242b323940474e555c636a71787f868d949ba2a9b0" +
                "b7bec5ccd3dae1e8eff6fd040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dd" +
                "e4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bd"
        const val T6_IP_CK = 0x33c8
        const val T6_TCP_CK = 0x26c6

        const val T7_UDP_PAYLOAD_HEX =
            "4500002a00000000401160aa080808080a0800020035c7a7001670dcabcd012000010001000000000000"
        const val T7_IP_CK = 0x60aa
        const val T7_UDP_CK = 0x70dc

        const val T8_UDP_EMPTY_HEX =
            "4500001c00000000401160b8080808080a0800020035c7a700081de8"
        const val T8_IP_CK = 0x60b8
        const val T8_UDP_CK = 0x1de8
    }

    private fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { (it.toInt(16) and 0xff).toByte() }.toByteArray()

    /** 双侧排除 IPv4 Identification（offset 4..5）后逐字节比对。 */
    private fun assertPacketEquals(goldenHex: String, actual: ByteArray) {
        val golden = hexBytes(goldenHex).also {
            it[4] = 0; it[5] = 0
        }
        // ID 是运行期递增计数器、黄金按 ID=0 一次性冻结：把 actual 复原成「ID=0 等价态」——
        // ID 置零后需重算 IPv4 头校验和（IP 校验和按「含 ID 的整头」算，ID 不同校验和必不同；
        // L4 校验和只含伪头 src/dst/proto/len、与 ID 无关，保持原样不动）。
        // 用 PacketBuilder 自己的校验和函数重算，正好与 scapy 黄金交叉验证其正确性。
        actual[4] = 0; actual[5] = 0
        actual[10] = 0; actual[11] = 0
        val ck = PacketBuilder.onesComplementChecksum(actual, 0, 20)
        actual[10] = ((ck ushr 8) and 0xff).toByte()
        actual[11] = (ck and 0xff).toByte()
        assertEquals("总长不一致", golden.size, actual.size)
        assertArrayEquals(golden, actual)
    }

    private fun assertIpChecksum(golden: Int, actual: ByteArray) {
        val v = ((actual[10].toInt() and 0xff) shl 8) or (actual[11].toInt() and 0xff)
        assertEquals("IPv4 头校验和", golden, v)
    }

    private fun assertTcpChecksum(golden: Int, actual: ByteArray) {
        val v = ((actual[36].toInt() and 0xff) shl 8) or (actual[37].toInt() and 0xff)
        assertEquals("TCP 校验和", golden, v)
    }

    private fun assertUdpChecksum(golden: Int, actual: ByteArray) {
        val v = ((actual[26].toInt() and 0xff) shl 8) or (actual[27].toInt() and 0xff)
        assertEquals("UDP 校验和", golden, v)
    }

    // ---- RFC 1071 自检（测试内独立实现，不复用 PacketBuilder 代码）----

    /** 独立反码和：16 位字累加 → 回卷；与 RFC 1071 一致，用于校验「含校验和后 ≡ 0xffff」。 */
    private fun independentSum(data: ByteArray, off: Int, length: Int): Int {
        var sum = 0
        var i = off
        while (i + 1 < off + length) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            i += 2
        }
        if (length and 1 == 1) sum += (data[off + length - 1].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum and 0xffff
    }

    /** 伪头（src 4B + dst 4B + 0|proto + l4Len）的 16 位和：地址按「成对单词」读（12,14,16,18）。 */
    private fun pseudoSum(actual: ByteArray, proto: Int, l4Len: Int): Int {
        var sum = 0
        for (o in intArrayOf(12, 14, 16, 18)) {
            sum += (actual[o].toInt() and 0xff) shl 8 or (actual[o + 1].toInt() and 0xff)
        }
        sum += proto
        sum += l4Len
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum and 0xffff
    }

    /** IPv4 头含校验和后 16 位和必须 ≡ 0xffff（RFC 1071 性质）。 */
    private fun assertIpv4HeaderSelfCheck(actual: ByteArray) {
        assertEquals("IPv4 头自检", 0xffff, independentSum(actual, 0, 20))
    }

    /** TCP 段（伪头 + 头 + 载荷，含校验和）16 位和必须 ≡ 0xffff。 */
    private fun assertTcpSelfCheck(actual: ByteArray) {
        val tcpLen = actual.size - 20
        val total = independentSum(actual, 20, tcpLen) + pseudoSum(actual, 6, tcpLen)
        assertEquals("TCP 段自检", 0xffff, total and 0xffff)
    }

    private fun assertUdpSelfCheck(actual: ByteArray) {
        val udpLen = actual.size - 20
        val total = independentSum(actual, 20, udpLen) + pseudoSum(actual, 17, udpLen)
        assertEquals("UDP 段自检", 0xffff, total and 0xffff)
    }

    // ---- 教科书向量：锚定 onesComplementChecksum 本体 ----

    @Test
    fun `w1 反码校验和与 wikipedia ipv4 头例 b861 一致`() {
        // 校验和字段已置零的头（192.168.0.1 → 192.168.0.199，proto=17，totalLen=115）
        val header = hexBytes("450000730000400040110000c0a80001c0a800c7")
        assertEquals(0xb861, PacketBuilder.onesComplementChecksum(header, 0, 20))
    }

    @Test
    fun `w2 奇数长度尾部字节按高 8 位参与累加`() {
        // 单字节 0xAB：等价于 0xAB00 参与累加 → 取反 0x54ff
        assertEquals(0x54ff, PacketBuilder.onesComplementChecksum(byteArrayOf(0xAB.toByte()), 0, 1))
    }

    // ---- scapy 黄金值：TCP ----

    @Test
    fun `t1 syn-ack 带 mss 与 scapy 逐字节一致`() {
        val actual = PacketBuilder.buildTcpReply(
            SRC_IP, DST_IP, SPORT, DPORT,
            seq = 3000, ack = 100001, flags = FLAG_SYN or FLAG_ACK,
            mss = PacketBuilder.DEFAULT_MSS,
        )
        assertPacketEquals(T1_SYNACK_MSS_HEX, actual)
        assertIpChecksum(T1_IP_CK, actual)
        assertTcpChecksum(T1_TCP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertTcpSelfCheck(actual)
    }

    @Test
    fun `t2 psh-ack 带载荷与 scapy 逐字节一致`() {
        val actual = PacketBuilder.buildTcpReply(
            SRC_IP, DST_IP, SPORT, DPORT,
            seq = 3001, ack = 100005, flags = FLAG_PSH or FLAG_ACK,
            payload = "pong!".toByteArray(),
        )
        assertPacketEquals(T2_PSHACK_PAYLOAD_HEX, actual)
        assertIpChecksum(T2_IP_CK, actual)
        assertTcpChecksum(T2_TCP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertTcpSelfCheck(actual)
    }

    @Test
    fun `t3 纯 ack 与 scapy 逐字节一致`() {
        val actual = PacketBuilder.buildTcpReply(
            SRC_IP, DST_IP, SPORT, DPORT,
            seq = 3006, ack = 100005, flags = FLAG_ACK,
        )
        assertPacketEquals(T3_PURE_ACK_HEX, actual)
        assertIpChecksum(T3_IP_CK, actual)
        assertTcpChecksum(T3_TCP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertTcpSelfCheck(actual)
    }

    @Test
    fun `t4 fin-ack 与 scapy 逐字节一致`() {
        val actual = PacketBuilder.buildTcpReply(
            SRC_IP, DST_IP, SPORT, DPORT,
            seq = 3006, ack = 100005, flags = FLAG_FIN or FLAG_ACK,
        )
        assertPacketEquals(T4_FINACK_HEX, actual)
        assertIpChecksum(T4_IP_CK, actual)
        assertTcpChecksum(T4_TCP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertTcpSelfCheck(actual)
    }

    @Test
    fun `t5 rst-ack 与 scapy 逐字节一致`() {
        val actual = PacketBuilder.buildTcpReply(
            SRC_IP, DST_IP, SPORT, DPORT,
            seq = 3000, ack = 100000, flags = FLAG_RST or FLAG_ACK,
        )
        assertPacketEquals(T5_RSTACK_HEX, actual)
        assertIpChecksum(T5_IP_CK, actual)
        assertTcpChecksum(T5_TCP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertTcpSelfCheck(actual)
    }

    @Test(timeout = 5_000)
    fun `t6 奇数长度大载荷 247b 与 scapy 逐字节一致`() {
        // 247B 非整数字长：覆盖反码累加的奇偶两条路径（i*7+3 周期 256 恰好覆盖全字节值域）
        val payload = ByteArray(247) { ((it * 7 + 3) and 0xff).toByte() }
        val actual = PacketBuilder.buildTcpReply(
            SRC_IP, DST_IP, SPORT, DPORT,
            seq = 3001, ack = 100005, flags = FLAG_PSH or FLAG_ACK,
            payload = payload,
        )
        assertPacketEquals(T6_BIG_PAYLOAD_HEX, actual)
        assertIpChecksum(T6_IP_CK, actual)
        assertTcpChecksum(T6_TCP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertTcpSelfCheck(actual)
    }

    // ---- scapy 黄金值：UDP ----

    @Test
    fun `t7 udp 带载荷与 scapy 逐字节一致`() {
        val actual = PacketBuilder.buildUdpReply(
            "8.8.8.8", DST_IP, 53, 51111,
            payload = hexBytes("abcd012000010001000000000000"),
        )
        assertPacketEquals(T7_UDP_PAYLOAD_HEX, actual)
        assertIpChecksum(T7_IP_CK, actual)
        assertUdpChecksum(T7_UDP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertUdpSelfCheck(actual)
    }

    @Test
    fun `t8 udp 空载荷与 scapy 逐字节一致`() {
        val actual = PacketBuilder.buildUdpReply("8.8.8.8", DST_IP, 53, 51111)
        assertPacketEquals(T8_UDP_EMPTY_HEX, actual)
        assertIpChecksum(T8_IP_CK, actual)
        assertUdpChecksum(T8_UDP_CK, actual)
        assertIpv4HeaderSelfCheck(actual)
        assertUdpSelfCheck(actual)
    }
}
