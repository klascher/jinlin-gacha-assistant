package com.jinlin.gacha.assistant.vpn

import android.util.Log
import com.jinlin.gacha.assistant.vpn.IPPacket.PROTO_TCP
import com.jinlin.gacha.assistant.vpn.IPPacket.PROTO_UDP

/**
 * 终端回包构造器 —— 从零件构造发回 tun 的 IPv4 包（独立实现，标准 IP/TCP/UDP 封装）。
 *
 * 方案 A 转发的回包（真实 socket 读到的数据 / 虚拟 seq/ack 的 ACK/FIN/RST）都要由
 * App 自己重新包一层 IP/TCP 壳写回 tun，游戏内核才收得到。本模块负责：
 * - IPv4 头构造 + IPv4 头校验和；
 * - TCP 段构造（含虚拟 seq/ack 平移到游戏看到的连接）＋ TCP 校验和（含伪头）；
 * - UDP 段构造（DNS 等最小转发）＋ UDP 校验和。
 *
 * 参考 `mobile/docs/02-阶段1设计.md` §2：只借鉴「标准封装思路」，
 * 本实现为完全独立的 Kotlin 代码。
 */
object PacketBuilder {

    private const val TAG = "PacketBuilder"
    private const val IPv4_IHL = 20
    private const val TCP_HDR = 20
    private const val UDP_HDR = 8
    /** TCP MSS 选项占 4 字节（kind=1B, len=1B, value=2B）。 */
    private const val TCP_OPT_MSS_LEN = 4

    /**
     * 隧道 MTU（与 `GachaVpnService.MTU` 一致）推导出的 MSS。
     * SYN-ACK 不带 MSS 时对端按 IPv4 默认 536 字节发包，一个大请求被切成几十个小包，
     * 既加重重组层负担又放大乱序概率。
     */
    const val DEFAULT_MSS = 4096 - 20 - 20

    private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 构造发回 tun 的 TCP 包（seq/ack 为「游戏视角」的虚拟序号）。
     * @return 完整 IPv4 包字节。
     */
    fun buildTcpReply(
        srcIp: String, dstIp: String,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        /** >0 时在 TCP 头附带 MSS 选项（仅 SYN / SYN-ACK 有意义）；0 表示沿用 20B 头。 */
        mss: Int = 0,
    ): ByteArray {
        val src = ipToBytes(srcIp)
        val dst = ipToBytes(dstIp)
        val withMss = mss > 0
        val tcpHdr = if (withMss) TCP_HDR + TCP_OPT_MSS_LEN else TCP_HDR
        val tcpLen = tcpHdr + payload.size
        val totalLen = IPv4_IHL + tcpLen
        val buf = ByteArray(totalLen)

        // ---- IPv4 头（0..19）----
        buf[0] = 0x45.toByte()                 // ver=4, ihl=5
        write16(buf, 2, totalLen)
        write16(buf, 4, idCounter.incrementAndGet() and 0xffff)   // Identification
        write16(buf, 6, 0)                     // flags+frag offset = 0
        buf[8] = 64                            // TTL
        buf[9] = PROTO_TCP.toByte()            // protocol = TCP
        src.copyInto(buf, 12)
        dst.copyInto(buf, 16)
        // IPv4 头校验和（第 10..11 字节），写完头后再算
        write16(buf, 10, 0)
        write16(buf, 10, onesComplementChecksum(buf, 0, IPv4_IHL))

        // ---- TCP 头（20..39）----
        write16(buf, 20, srcPort)
        write16(buf, 22, dstPort)
        write32(buf, 24, seq)
        write32(buf, 28, ack)
        buf[32] = ((tcpHdr / 4) shl 4).toByte()   // data offset：5（无选项）或 6（带 MSS）
        buf[33] = flags.toByte()               // flags
        write16(buf, 34, 65535)                // window（宽松固定，简化转发）
        write16(buf, 36, 0)                    // checksum，随后填
        write16(buf, 38, 0)                    // urgent pointer

        if (withMss) {
            val o = IPv4_IHL + TCP_HDR
            buf[o] = 2                         // Option-Kind = 2：Maximum Segment Size
            buf[o + 1] = TCP_OPT_MSS_LEN.toByte() // Option-Length = 4
            write16(buf, o + 2, mss)
        }

        payload.copyInto(buf, IPv4_IHL + tcpHdr)

        // TCP 校验和（伪头：srcIP+dstIP+0|proto|tcpLen；与 TCP 段拼接后按 16 位反码和）
        val pseudoInitial = tcpPseudoChecksumInitial(src, dst, tcpLen)
        val tcpChecksum = onesComplementChecksum(buf, 20, tcpLen, pseudoInitial)
        write16(buf, 36, tcpChecksum)

        return buf
    }

    /** 构造发回 tun 的 UDP 包（DNS 等最小转发）。 */
    fun buildUdpReply(
        srcIp: String, dstIp: String,
        srcPort: Int, dstPort: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val src = ipToBytes(srcIp)
        val dst = ipToBytes(dstIp)
        val udpLen = UDP_HDR + payload.size
        val totalLen = IPv4_IHL + udpLen
        val buf = ByteArray(totalLen)

        buf[0] = 0x45.toByte()
        write16(buf, 2, totalLen)
        write16(buf, 4, idCounter.incrementAndGet() and 0xffff)
        write16(buf, 6, 0)
        buf[8] = 64
        buf[9] = PROTO_UDP.toByte()
        src.copyInto(buf, 12)
        dst.copyInto(buf, 16)
        write16(buf, 10, 0)
        write16(buf, 10, onesComplementChecksum(buf, 0, IPv4_IHL))

        write16(buf, 20, srcPort)
        write16(buf, 22, dstPort)
        write16(buf, 24, udpLen)
        write16(buf, 26, 0)                    // checksum，随后填

        payload.copyInto(buf, IPv4_IHL + UDP_HDR)

        val pseudoInitial = udpPseudoChecksumInitial(src, dst, udpLen)
        val udpChecksum = onesComplementChecksum(buf, 20, udpLen, pseudoInitial)
        write16(buf, 26, udpChecksum)

        return buf
    }

    /** 15 位反码校验和（RFC 1071）：连续 16 位字累加 → 回卷 → 取反。允许传入 initial（如伪和）。 */
    fun onesComplementChecksum(data: ByteArray, off: Int, length: Int, initial: Int = 0): Int {
        var sum = initial and 0xffff
        var i = off
        var end = off + (length - (length and 1)) // 偶数部分
        while (i < end) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            if (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
            i += 2
        }
        if (length and 1 == 1) {
            sum += (data[end].toInt() and 0xff) shl 8
            if (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        }
        return (sum.inv()) and 0xffff
    }

    private fun tcpPseudoChecksumInitial(src: ByteArray, dst: ByteArray, segmentLen: Int): Int {
        val pseudo = ByteArray(12)
        src.copyInto(pseudo, 0)
        dst.copyInto(pseudo, 4)
        pseudo[9] = PROTO_TCP.toByte()
        write16(pseudo, 10, segmentLen)
        // 伪头长度 12 为偶数，无需舍入处理
        return pseudoChecksum(pseudo)
    }

    private fun udpPseudoChecksumInitial(src: ByteArray, dst: ByteArray, segmentLen: Int): Int {
        val pseudo = ByteArray(12)
        src.copyInto(pseudo, 0)
        dst.copyInto(pseudo, 4)
        pseudo[9] = PROTO_UDP.toByte()
        write16(pseudo, 10, segmentLen)
        return pseudoChecksum(pseudo)
    }

    /** 纯 16 位字反码和（不回卷取反，作为后续拼接的 initial）。 */
    private fun pseudoChecksum(pseudo: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < pseudo.size) {
            sum += ((pseudo[i].toInt() and 0xff) shl 8) or (pseudo[i + 1].toInt() and 0xff)
            if (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
            i += 2
        }
        return sum
    }

    /**
     * IPv4 字符串 → 4 字节。
     *
     * 原实现直接 `parts[i]` 下标取值：IPv6 / 缺段 / 非数字输入会抛
     * IndexOutOfBoundsException 或 NumberFormatException，落在转发主路径上就是崩线程。
     * 畸形输入改为兜底 0.0.0.0 并告警（IP 来自已解析的 ParsedIpPacket，正常走不到这里）。
     */
    private fun ipToBytes(ip: String): ByteArray {
        val parts = ip.split('.')
        if (parts.size == 4) {
            val out = ByteArray(4)
            for (i in 0..3) {
                val v = parts[i].toIntOrNull()
                if (v == null || v !in 0..255) break
                out[i] = v.toByte()
                if (i == 3) return out
            }
        }
        Log.w(TAG, "非法 IPv4 地址，兜底为 0.0.0.0: $ip")
        return ByteArray(4)
    }

    private fun write16(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value ushr 8) and 0xff).toByte()
        buf[off + 1] = (value and 0xff).toByte()
    }

    private fun write32(buf: ByteArray, off: Int, value: Long) {
        write16(buf, off, ((value ushr 16) and 0xffff).toInt())
        write16(buf, off + 2, (value and 0xffff).toInt())
    }

    /** 包一个 SYN flag 判断用的小常量，方便调用方少 import。 */
    fun hasFlag(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}