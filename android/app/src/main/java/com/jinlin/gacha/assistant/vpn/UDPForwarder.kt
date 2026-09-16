package com.jinlin.gacha.assistant.vpn

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 最小 UDP 转发（目标游戏进程的 DNS 等，对应设计稿 §3.5）。
 *
 * 方案 A 接管游戏全部流量后，其 UDP（主要是 DNS）若不转发，游戏可能解析/登录失败，
 * 故做无状态映射：每条 (clientIp,clientPort→serverIp,serverPort) 建一个真实
 * `DatagramSocket` 并 `protect()` 承运往返。空闲超时回收，不做 UDP 重组（抽卡不走 UDP）。
 */
class UDPForwarder(
    private val protect: (DatagramSocket) -> Unit,
    private val tunEgress: (ByteArray) -> Unit,
) {
    private data class Bind(
        val clientIp: String,
        val clientPort: Int,
        val serverIp: String,
        val serverPort: Int,
        val socket: DatagramSocket,
    ) {
        @Volatile var lastActive: Long = System.currentTimeMillis()
    }

    private val binds = ConcurrentHashMap<String, Bind>()

    /** 收到客户端 UDP 出包：按 (src→dst) 归到绑定 socket 发往服务器。 */
    fun ingress(pkt: ParsedIpPacket) {
        val key = "${pkt.srcIp}:${pkt.srcPort}:>${pkt.dstIp}:${pkt.dstPort}"
        val existing = binds[key]
        if (existing != null) {
            existing.lastActive = System.currentTimeMillis()
            sendClientPayload(key, existing, pkt.udpPayload)
            sweep()
            return
        }
        // 首次该四元组：建真实 socket + protect + 起接收线程，再落 binds（IO 失败不留空槽）
        val sock = try {
            DatagramSocket().also { it.connect(InetSocketAddress(pkt.dstIp, pkt.dstPort)) }
        } catch (e: IOException) {
            Log.w("UdpFwd", "UDP socket 建立失败 $key", e)
            return
        }
        protect(sock)
        val fresh = Bind(pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort, sock)
        val raced = binds.putIfAbsent(key, fresh)
        if (raced != null) {
            // 并发竞态：另一线程已建同键绑定，丢弃本线程新建 socket、复用既有绑定
            sock.close()
            raced.lastActive = System.currentTimeMillis()
            sendClientPayload(key, raced, pkt.udpPayload)
            sweep()
            return
        }
        Thread({ receiveLoop(key) }, "udp-$key").also { it.isDaemon = true }.start()
        sendClientPayload(key, fresh, pkt.udpPayload)
        sweep()
    }

    /** 把客户端 UDP 出包经绑定 socket 发往服务器（payload 空则仅刷新活跃，不 send）。 */
    private fun sendClientPayload(key: String, bind: Bind, payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            bind.socket.send(DatagramPacket(payload, payload.size))
        } catch (e: IOException) {
            Log.w("UdpFwd", "UDP 发送失败", e)
            removeBind(key)
        }
    }

    private fun receiveLoop(key: String) {
        val bind = binds[key] ?: return
        val buf = ByteArray(2048)
        while (binds.containsKey(key)) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                bind.socket.receive(dp)
                bind.lastActive = System.currentTimeMillis()
                // 服务器回包 → 改写 src=服务器，封回客户端
                val payload = dp.data.copyOfRange(dp.offset, dp.offset + dp.length)
                val reply = PacketBuilder.buildUdpReply(
                    bind.serverIp, bind.clientIp, bind.serverPort, bind.clientPort, payload,
                )
                tunEgress(reply)
            } catch (e: IOException) {
                break
            }
        }
        binds.remove(key)
        bind.socket.close()
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        binds.entries.removeIf { (now - it.value.lastActive) > IDLE_TIMEOUT_MS }
    }

    private fun removeBind(key: String) {
        val bind = binds.remove(key) ?: return
        bind.socket.close()
    }

    /** 服务停止时关闭全部 UDP 绑定。 */
    fun shutdown() {
        binds.keys.forEach { removeBind(it) }
    }

    fun bindCount(): Int = binds.size

    companion object {
        private const val IDLE_TIMEOUT_MS = 60_000L
    }
}