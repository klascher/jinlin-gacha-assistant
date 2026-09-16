package com.jinlin.gacha.assistant.vpn

import android.util.Log
import com.jinlin.gacha.assistant.core.CaptureLog
import com.jinlin.gacha.assistant.core.ReassemblyWorker
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_ACK
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_RST
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_SYN
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局连接管理（对应设计稿 §3.1/§3.6 与 §5）。
 *
 * - 按 4-tuple 维护所有目标游戏的转发连接（**全部**连接都转发，否则游戏联网失败
 *   ——见设计稿 §0.3；「只盯抽卡端点」仅落实在切帧侧，由 FlowMatcher 判定）；
 * - tun 每收一个包据此派发：TCP → [ForwardConnection]（不存在且 SYN 则新建），UDP → [UDPForwarder]；
 * - 维护生命周期/回收，FIN/RST/超时/服务停止时正确清理。
 */
class ConnectionManager(
    private val matcher: FlowMatcher,
    private val reassembly: ReassemblyWorker?,
    private val tunWrite: (ByteArray) -> Unit,
    private val protectTcp: (Socket) -> Unit,
    private val protectUdp: (DatagramSocket) -> Unit,
) {
    private val tcpFlows = ConcurrentHashMap<String, ForwardConnection>()
    private val udp = UDPForwarder(protectUdp, tunWrite)

    /** tun 收包入口（tun 主线程调用）：观察候选 + 按协议派发转发。 */
    fun ingress(pkt: ParsedIpPacket) {
        matcher.observe(pkt)
        when {
            pkt.isTcp -> handleTcp(pkt)
            pkt.isUdp -> udp.ingress(pkt)
            // 其它协议不转发（本工具只承运 TCP/UDP）
        }
    }

    private fun handleTcp(pkt: ParsedIpPacket) {
        val key = tcpKey(pkt)
        val existing = tcpFlows[key]
        if (existing != null) {
            existing.onClientPacket(pkt)
            return
        }
        // 无既有连接：仅当是建连 SYN 才为之新建；否则丢弃（服务器回包/重传闻不存在的流）
        if (pkt.tcpFlags and FLAG_SYN == 0) return
        val conn: ForwardConnection
        try {
            conn = ForwardConnection.establish(
                serverIp = pkt.dstIp,
                serverPort = pkt.dstPort,
                clientIp = pkt.srcIp,
                clientPort = pkt.srcPort,
                protect = protectTcp,
                reassembly = reassembly,
                tunEgress = tunWrite,
                onClosed = { tcpFlows.remove(key) },
            )
        } catch (e: IOException) {
            CaptureLog.w("ConnMgr", "向服务器建连失败 $key", e)
            // 回 RST 给游戏让它重试，避免悬挂
            resetClient(pkt)
            return
        }
        // 建连竞态：后台 connect 线程可能在我们入表之前就已失败并触发 onClosed，
        // 那一刻 remove(key) 删的是空，若不检查仍入表，这条死连接将永不回收。
        if (conn.isClosed()) {
            Log.w("ConnMgr", "连接在入表前已关闭，丢弃 $key")
            return
        }
        tcpFlows[key] = conn
        // 07 设计 L4：建连成功打点（并入 CaptureLog 供 App 内观测，含活跃连接数）
        CaptureLog.i("ConnMgr", "已接管连接 $key（活跃连接 #${tcpFlows.size}）")
        // 首个 SYN 也要喂给连接回 SYN-ACK（旧实现只建 B 就 return，SYN 被吞——握手要等游戏
        // 重传；且服务器先发数据会打进未建连的 SYN_SENT 触发 RST）。重复 SYN 由连接内部去重。
        conn.onClientPacket(pkt)
    }

    /** 建连失败向游戏回 RST。 */
    private fun resetClient(pkt: ParsedIpPacket) {
        try {
            tunWrite(
                PacketBuilder.buildTcpReply(
                    pkt.dstIp, pkt.srcIp, pkt.dstPort, pkt.srcPort,
                    seq = 0, ack = 0,
                    flags = FLAG_RST or FLAG_ACK,
                )
            )
        } catch (e: IOException) {
            // 忽略
        }
    }

    private fun tcpKey(pkt: ParsedIpPacket): String =
        "${pkt.srcIp}:${pkt.srcPort}->${pkt.dstIp}:${pkt.dstPort}"

    /** 服务停止：关闭全部 TCP 转发与 UDP 绑定。 */
    fun shutdown() {
        tcpFlows.values.forEach { it.shutdown() }
        tcpFlows.clear()
        udp.shutdown()
    }

    fun flowCount(): Int = tcpFlows.size
    fun udpBindCount(): Int = udp.bindCount()
}