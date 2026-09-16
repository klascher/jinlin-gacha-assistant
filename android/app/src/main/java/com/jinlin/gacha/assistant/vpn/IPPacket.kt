package com.jinlin.gacha.assistant.vpn

/**
 * tun 读到的原始 IP 包（IPv4 / IPv6）极简解析器。
 *
 * VpnService 给的是 IP 层包：不像 Npcap 那样带以太头，所以没有 Pcap 头，需自行剥
 * IP header。阶段 1 只关心分流所需字段：**协议、源/目的地址、TCP/UDP 端口**，供
 * [FlowMatcher] 判定目标端点。完整 TCP 重组与切帧是 M2。
 *
 * 字节序注意：IP 报文字段全部大端；端口在 TCP/UDP 头前 4 字节（各 2B 大端）。
 */

data class ParsedIpPacket(
    val protocol: Int,
    val srcIp: String,
    val dstIp: String,
    /** 仅 TCP/UDP 有意义；其它协议为 0。 */
    val srcPort: Int,
    val dstPort: Int,
    /** 原始 IP 包字节（含头），供透传回 tun。 */
    val raw: ByteArray,
    /** 原始包净长。 */
    val length: Int,
    val isTcp: Boolean,
    val isUdp: Boolean,

    // —— 以下仅 TCP 有意义（重组层用）——
    /** TCP 报文段序号（首个数据字节的序列号），未解析时为 -1。 */
    val tcpSeq: Long = -1,
    /** TCP 应答号，未解析时为 -1。 */
    val tcpAck: Long = -1,
    /** TCP 头长度（字节）。 */
    val tcpDataOffset: Int = 0,
    /** TCP 载荷（即原始负载，不含 TCP 头），isTcp=false 时空数组。 */
    val tcpPayload: ByteArray = ByteArray(0),
    /** TCP flags（SYN/ACK/FIN/RST/PSH 等，见 [FLAG_*]），isTcp=false 时为 0。 */
    val tcpFlags: Int = 0,
    /** UDP 载荷（不含 UDP 头），isUdp=false 时空数组。 */
    val udpPayload: ByteArray = ByteArray(0),
)

object IPPacket {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    // TCP flags（TCP header 第 13 字节的低 6 位）
    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    /**
     * 尝试把缓冲解析为完整 IP 包；半包/不可识别协议返回 null。
     * @param buf 存放 IP 包的缓冲
     * @param length 缓冲中有效字节数
     */
    fun parse(buf: ByteArray, length: Int): ParsedIpPacket? {
        if (length < 20) return null
        val version = (buf[0].toInt() and 0xff) ushr 4
        return when (version) {
            4 -> parseV4(buf, length)
            6 -> parseV6(buf, length)
            else -> null
        }
    }

    private fun parseV4(buf: ByteArray, length: Int): ParsedIpPacket? {
        val first = buf[0].toInt() and 0xff
        val ihl = (first and 0x0f) * 4
        if (ihl < 20) return null
        val totalLen = u16(buf, 2)
        if (totalLen < ihl) return null
        if (totalLen > length) return null // 半包，等更多字节
        val protocol = buf[9].toInt() and 0xff
        val src = ipv4String(buf, 12)
        val dst = ipv4String(buf, 16)
        val isTcp = protocol == PROTO_TCP
        val isUdp = protocol == PROTO_UDP
        var srcPort = 0
        var dstPort = 0
        var tcpSeq = -1L
        var tcpAck = -1L
        var tcpDataLen = 0
        var tcpPayload = ByteArray(0)
        var tcpFlags = 0
        var udpPayload = ByteArray(0)
        if (isTcp || isUdp) {
            if (totalLen < ihl + 4) return null
            srcPort = u16(buf, ihl)
            dstPort = u16(buf, ihl + 2)
        }
        if (isUdp && totalLen > ihl + 8) {
            udpPayload = buf.copyOfRange(ihl + 8, totalLen)
        }
        if (isTcp && totalLen >= ihl + 20) {
            tcpSeq = u32(buf, ihl + 4)
            tcpAck = u32(buf, ihl + 8)
            tcpDataLen = ((buf[ihl + 12].toInt() ushr 4) and 0x0f) * 4
            if (tcpDataLen < 20) tcpDataLen = 20
            if (tcpDataLen < totalLen - ihl) {
                tcpPayload = buf.copyOfRange(ihl + tcpDataLen, totalLen)
            }
            tcpFlags = buf[ihl + 13].toInt() and 0xff
        }
        return ParsedIpPacket(
            protocol = protocol,
            srcIp = src,
            dstIp = dst,
            srcPort = srcPort,
            dstPort = dstPort,
            raw = buf.copyOf(totalLen),
            length = totalLen,
            isTcp = isTcp,
            isUdp = isUdp,
            tcpSeq = tcpSeq,
            tcpAck = tcpAck,
            tcpDataOffset = tcpDataLen,
            tcpPayload = tcpPayload,
            tcpFlags = tcpFlags,
            udpPayload = udpPayload,
        )
    }

    private fun parseV6(buf: ByteArray, length: Int): ParsedIpPacket? {
        val payloadLen = u16(buf, 4)
        val total = payloadLen + 40
        if (total > length) return null // 半包
        val nextHeader = buf[6].toInt() and 0xff
        val src = ipv6String(buf, 8)
        val dst = ipv6String(buf, 24)
        val isTcp = nextHeader == PROTO_TCP
        val isUdp = nextHeader == PROTO_UDP
        var srcPort = 0
        var dstPort = 0
        var tcpSeq = -1L
        var tcpAck = -1L
        var tcpDataLen = 0
        var tcpPayload = ByteArray(0)
        var tcpFlags = 0
        var udpPayload = ByteArray(0)
        if (isTcp || isUdp) {
            // 常见场景 nextHeader 直接是 TCP/UDP（无扩展头），端口在偏移 40。
            val hdrOff = 40
            if (total < hdrOff + 4) return null
            srcPort = u16(buf, hdrOff)
            dstPort = u16(buf, hdrOff + 2)
        }
        if (isUdp && total >= 48) {
            udpPayload = buf.copyOfRange(48, total)
        }
        if (isTcp && total >= 40 + 20) {
            tcpSeq = u32(buf, 40 + 4)
            tcpAck = u32(buf, 40 + 8)
            tcpDataLen = ((buf[40 + 12].toInt() ushr 4) and 0x0f) * 4
            if (tcpDataLen < 20) tcpDataLen = 20
            if (tcpDataLen < total - 40) {
                tcpPayload = buf.copyOfRange(40 + tcpDataLen, total)
            }
            tcpFlags = buf[40 + 13].toInt() and 0xff
        }
        return ParsedIpPacket(
            protocol = nextHeader,
            srcIp = src,
            dstIp = dst,
            srcPort = srcPort,
            dstPort = dstPort,
            raw = buf.copyOf(total),
            length = total,
            isTcp = isTcp,
            isUdp = isUdp,
            tcpSeq = tcpSeq,
            tcpAck = tcpAck,
            tcpDataOffset = tcpDataLen,
            tcpPayload = tcpPayload,
            tcpFlags = tcpFlags,
            udpPayload = udpPayload,
        )
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

private fun u32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xff) shl 24) or
            ((buf[off + 1].toLong() and 0xff) shl 16) or
            ((buf[off + 2].toLong() and 0xff) shl 8) or
            (buf[off + 3].toLong() and 0xff)

    private fun ipv4String(buf: ByteArray, off: Int): String = buildString {
        for (i in 0 until 4) {
            if (i > 0) append('.')
            append(buf[off + i].toInt() and 0xff)
        }
    }

    @Suppress("SpellCheckingInspection")
    private fun ipv6String(buf: ByteArray, off: Int): String = buildString {
        for (i in 0 until 8) {
            if (i > 0) append(':')
            append(String.format("%04x", u16(buf, off + i * 2)))
        }
    }
}