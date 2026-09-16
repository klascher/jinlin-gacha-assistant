package com.jinlin.gacha.assistant.vpn

import android.util.Log
import com.jinlin.gacha.assistant.core.Direction
import com.jinlin.gacha.assistant.core.ReassemblyWorker
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_ACK
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_FIN
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_PSH
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_RST
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_SYN
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * 单条 TCP 连接的「虚拟 TCP 中介」（方案 A 核心，对应设计稿 §3.3）。
 *
 * 免 root 下每一条「游戏连接 A」都由 App 在原内存里维护：
 * - 游戏内核通过 tun 与本对象通信（A 是纯内存连接，不上真实网卡、服务器不可见）；
 * - 本对象另建一条**真实 socket**到同一服务器、`protect()` 绕开自身 tun 后承运真实数据（B）；
 * - 同时把虚拟 seq/ack 平移到游戏看到的连接上，使游戏内核认为自己在与服务器直连。
 *
 * 职责：建连(SYN→SYN-ACK)、双向搬运（C→S 写真实 socket 并回 ACK；S→C 读真实 socket
 * 封回 tun）、FIN/RST 传播、以及抽卡连接的旁路投递（供切帧）。
 *
 * 参考设计稿`mobile/docs/02-阶段1设计.md` §2：独立实现，不引入任何 GPL 源码。
 */
class ForwardConnection private constructor(
    /** 服务器端点（客户端看到的目标），也是真实 socket 的连接目标。 */
    private val serverIp: String,
    private val serverPort: Int,
    /** 客户端端点（游戏侧）。 */
    private val clientIp: String,
    private val clientPort: Int,
    private val reassembly: ReassemblyWorker?,
    private val tunEgress: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {

    private val socket: Socket
    @Volatile
    private var running = true

    /** 首个 SYN 是否已回 SYN-ACK（防重复 SYN 重复 bump vsSeq）。 */
    @Volatile
    private var handshaken = false
    /** 真实 socket 读线程是否已启动（仅在真实 socket 连上后才开）。 */
    @Volatile
    private var readerStarted = false
    /** 真实 socket 是否已连上（后台 connect 线程置位）。连上前收到的 C→S 载荷进 [pendingWrites] 缓冲。 */
    @Volatile
    private var connected = false
    /** 真实 connect 是否已失败（失败仅回 RST 由游戏重连，无悬挂）。 */
    @Volatile
    private var connectFailed = false
    /** connect 进行中被 ACK 给客户端、但尚未转发到服务器的载荷（持 [#STATE_LOCK] 访问，见 fwdOrBuffer/onServerConnected）。 */
    private val pendingWrites = ByteArrayOutputStream()

    /**
     * 客户端已发 FIN（其发送方向半关闭）、但真实 socket 当时尚未连上 → 推迟到
     * [onServerConnected] 冲刷完缓冲后再补 `shutdownOutput()`（持 [#STATE_LOCK] 访问）。
     */
    private var clientFinPending = false

    /**
     * 虚拟 TCP 状态（游戏视角）。**必须**整体加锁：这些字段被三个线程并发读写——
     * tun 读线程（onClientPacket）、真实 socket 读线程（serverReadLoop）、
     * connect 线程（beginConnect 失败时 sendRst）。原先只有 pendingWrites 受锁保护，
     * vsSeq / clientExpected 完全裸奔 ⇒ seq/ack 错乱，游戏内核判定乱序/越窗后直接 RST 重连。
     */
    private val vstate = VirtualState(VIRTUAL_ISN)

    private val TAG = "FwdConn"

    /** 缓冲写入/冲刷与 connected 标志的唯一锁（保证 onClientPacket 缓冲 与 onServerConnected 冲刷 不交错，见 fwdOrBuffer）。 */
    private val STATE_LOCK = Any()

    /** 关闭动作的串行锁：close 可能被 tun 线程 / socket 读线程 / connect 线程同时触发。 */
    private val CLOSE_LOCK = Any()

    companion object {
        /** 虚拟 isn 基值（自选；与真实 socket 无关，仅游戏视角）。 */
        private const val VIRTUAL_ISN = 3000L

        /** 由 ConnectionManager 在收到目标游戏首个 SYN 时创建。
         * 仅登记 + 后台启动真实 connect 后**立即返回**，绝不阻塞 tun 读线程
         * （旧实现在此同步 `socket.connect(…,4000)`，读线程被单条连接卡最长 4s）。 */
        fun establish(
            serverIp: String, serverPort: Int,
            clientIp: String, clientPort: Int,
            protect: (Socket) -> Unit,
            reassembly: ReassemblyWorker?,
            tunEgress: (ByteArray) -> Unit,
            onClosed: () -> Unit,
        ): ForwardConnection {
            val conn = ForwardConnection(
                serverIp, serverPort, clientIp, clientPort,
                reassembly, tunEgress, onClosed,
            )
            conn.beginConnect(protect)
            return conn
        }
    }

    init {
        val sock = Socket()
        sock.tcpNoDelay = true   // 收到即发，不做 Nagle 聚合，保真实时序（设计稿 §7）
        socket = sock
    }

    /** 后台启动真实 socket connect（独立线程，不阻塞 tun 读线程）。
     * 成功后 [onServerConnected] 冲刷缓冲并启动读线程；失败回 RST 给客户端由游戏重连。 */
    private fun beginConnect(protect: (Socket) -> Unit) {
        Thread({
            try {
                protect(socket)
                socket.connect(InetSocketAddress(serverIp, serverPort), 4000)
                onServerConnected()
            } catch (e: IOException) {
                Log.w(TAG, "向服务器建连失败 $serverIp:$serverPort", e)
                connectFailed = true
                sendRst()   // 通知客户端，让游戏重连（该连接由 onClosed 从 map 移除）
                close(false)
            }
        }, "fwd-connect").also { it.isDaemon = true }.start()
    }

    /** 真实 socket 连上：置 connected、把缓冲的 C→S 载荷冲刷出去，再启动读线程（顺序在 fwdOrBuffer 锁内保持）。 */
    private fun onServerConnected() {
        synchronized(STATE_LOCK) {
            if (!running) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                return
            }
            connected = true
            val pending = pendingWrites.toByteArray()
            pendingWrites.reset()
            if (pending.isNotEmpty()) {
                try {
                    socket.getOutputStream().write(pending)
                    socket.getOutputStream().flush()
                    Log.i(TAG, "冲刷缓冲 ${pending.size} B -> $serverIp:$serverPort")
                } catch (e: IOException) {
                    Log.w(TAG, "冲刷缓冲失败", e)
                    close(true)
                    return
                }
            }
            if (clientFinPending) {
                // 客户端在本 socket 连上前就发了 FIN：此刻其载荷已按序冲刷完，补上发送方向半关闭。
                // 否则服务器永远收不到 EOF（这一半连接挂着等，直到超时）。
                shutdownRealOutput()
                Log.i(TAG, "补发客户端 FIN（半关闭）-> $serverIp:$serverPort")
            }
        }
        startServerRead()
    }

    /** 收到客户端（游戏）发的包：①已有的 IP 分段被忽略；②处理 payload/FIN/RST + 回 ACK。 */
    fun onClientPacket(pkt: ParsedIpPacket) {
        if (!running) return
        if (pkt.tcpFlags and FLAG_SYN != 0) {
            if (!handshaken) {
                // 建连：记客户端 isn，**立即**回 SYN-ACK（不等真实 connect，微秒级）+ 后台 connect（beginConnect）
                handshaken = true
                replySynAck(pkt.tcpSeq)
                // 读线程由 onServerConnected（真实 socket 连上后）启动，此处不再 startServerRead
            } else if (vstate.isDuplicateSyn(pkt.tcpSeq)) {
                // 重复 SYN：首个 SYN-ACK 可能在 tun 侧丢失/被丢。标准 TCP 行为是幂等重发，
                // 原先这里一律 return，游戏只能干等超时重传 => 表现为「连一次要重连 2-3 次」。
                resendSynAck()
            }
            return
        }
        if (pkt.tcpFlags and FLAG_RST != 0) {
            Log.i(TAG, "客户端 RST，转运关闭")
            close(false)
            return
        }

        val payload = pkt.tcpPayload
        if (payload.isNotEmpty()) {
            // 旁路复制：**必须先投喂消费队列，再转发到真实 socket**（R6′ 修复）。
            // 因果序：若先写 socket，服务器可能秒回、由 fwd-socket-reader 线程把该响应
            // **先于本请求**投进同一根消费队列 ⇒ 消费者先处理响应、后处理请求，ViewTracker
            // 那时查不到 seq→view，只能回退 currentView；若两者分属不同视图，就会把非
            // 「全部卡池」的页误判为全部卡池而收下 ⇒ 引入不可比坐标系（少算/多算）。
            // 本帧先入队后，「请求先于其响应」由程序序保证，不依赖网络往返时长。
            // 任意连接都投喂，供 M2 特征锁定判定（该 server 端点未确认前亦是候选）。
            reassembly?.receive(
                Direction.CLIENT_TO_SERVER, pkt.tcpSeq, payload,
                clientIp, clientPort, serverIp, serverPort,
            )
            fwdOrBuffer(payload)
        }
        // 客户端在收到 payload/要推进滑窗后回 ACK（seq 用游戏视角 vsSeq，ack 用已收字节）
        if (payload.isNotEmpty() || pkt.tcpFlags and FLAG_FIN != 0) {
            val (seq, ack) = vstate.onClientData(pkt.tcpSeq, payload.size)
            replyAck(seq, ack)
        } else {
            // 纯 ACK/窗口探测，不回包避免 ACK 风暴
        }

        if (pkt.tcpFlags and FLAG_FIN != 0) {
            val bothFinished = vstate.setClientFin()
            finishClientSendSide()
            replyFin()
            if (bothFinished) {
                close(true)
                return
            }
        }
        maybeFullyClose()
    }

    /**
     * 客户端发送方向半关闭（收到其 FIN）→ 把 FIN 转成真实 socket 的 `shutdownOutput()`。
     *
     * **为什么不能直接调用 `shutdownOutput()`**：真实 socket 由后台线程 connect，客户端完全可能
     * 在本 socket 连上**之前**就发 FIN；此时 `shutdownOutput()` 抛 `SocketException("not connected")`，
     * 被 catch 吞掉 ⇒ 输出端永不关闭、服务器收不到 FIN（半开连接挂到超时）。
     * 故改为**延迟执行**：未连上就置 [clientFinPending]，交给 [onServerConnected] 在其缓冲载荷
     * 冲刷完之后补做。
     *
     * 持 [#STATE_LOCK] 与 [fwdOrBuffer] / [onServerConnected] 互斥，保证 FIN 一定排在已缓冲的
     * C→S 载荷之后。两种交错顺序都不会漏掉半关闭：
     * ① 本方法先拿到锁（`connected == false`）→ 置位，由 onServerConnected 补做；
     * ② onServerConnected 先拿到锁（此刻尚未置位 → 跳过）→ 本方法后拿锁，见 `connected == true` 立即执行。
     */
    private fun finishClientSendSide() {
        synchronized(STATE_LOCK) {
            clientFinPending = true
            if (connected) shutdownRealOutput()
        }
    }

    /** 半关真实 socket 的发送方向（幂等；对端已关或未连上时忽略异常）。 */
    private fun shutdownRealOutput() {
        try {
            socket.shutdownOutput()
        } catch (e: IOException) {
            // 对端已关，忽略
        }
    }

    /** 把 C→S 载荷写入真实 socket；真实 connect 尚未完成时（[connected]=false）缓冲进 [pendingWrites]，
     * 待 onServerConnected 冲刷。持 [#STATE_LOCK] 使「缓冲」与「冲刷+置 connected」互不交错，保证字节序不乱。 */
    private fun fwdOrBuffer(payload: ByteArray) {
        synchronized(STATE_LOCK) {
            if (!running) return
            if (connected) {
                try {
                    socket.getOutputStream().write(payload)
                    socket.getOutputStream().flush()
                } catch (e: IOException) {
                    Log.w(TAG, "写真实 socket 失败", e)
                    close(true)
                }
            } else if (!connectFailed) {
                pendingWrites.write(payload)   // connect 进行中：先缓冲，onServerConnected 冲刷
            }
            // connectFailed 且尚未 close 的极窄窗口：直接丢弃（马上会回 RST）
        }
    }

    /** 启动真实 socket 读线程（仅一次；在真实 socket 连上（onServerConnected）后才调用。
     * 真实 socket 已是连好状态，服务器先发的数据不会再打进 SYN_SENT（§3.43 修复二由设计保证）。 */
    private fun startServerRead() {
        if (readerStarted) return
        readerStarted = true
        Thread(::serverReadLoop, "fwd-socket-reader").also { it.isDaemon = true }.start()
    }

    /** 真实 socket 读线程：S→C 数据封回 tun + 切帧 + 传播 FIN/RST。 */
    private fun serverReadLoop() {
        val input = socket.getInputStream()
        val buf = ByteArray(4096)
        while (running) {
            val n = try {
                val got = input.read(buf)
                if (got < 0) {
                    // EOF：服务器侧半关闭
                    vstate.setServerFin()
                    replyFin()
                    break
                }
                got
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "读真实 socket 被中断")
                break
            } catch (e: IOException) {
                Log.w(TAG, "读真实 socket 异常", e)
                if (running) {
                    vstate.setServerFin()
                    replyFin()
                }
                break
            }
            if (n <= 0) continue

            val data = buf.copyOf(n)
            // 序号分配必须在锁内完成：与 tun 线程的 replyAck/replyFin 争抢同一个 vsSeq
            val (dataSeq, ackNow) = vstate.takeDataSeq(n)
            // 封回 tun：src=服务器（游戏看到的源），dst=客户端，seq=vsSeq（虚拟）
            tunEgress(
                PacketBuilder.buildTcpReply(
                    serverIp, clientIp, serverPort, clientPort,
                    seq = dataSeq, ack = ackNow,
                    flags = FLAG_ACK or FLAG_PSH, payload = data,
                )
            )
            // 旁路复制：任意连接都投喂，供 M2 特征锁定判定（S→C 源即 server 端点）
            reassembly?.receive(
                Direction.SERVER_TO_CLIENT, dataSeq, data,
                serverIp, serverPort, clientIp, clientPort,
            )
        }
        maybeFullyClose()
    }

    /** 真实 connect 失败或转运异常时发 RST 给客户端，让游戏重连（不再悬挂）。 */
    private fun sendRst() {
        if (!running) return
        val (seq, ack) = vstate.snapshot()
        try {
            tunEgress(
                PacketBuilder.buildTcpReply(
                    serverIp, clientIp, serverPort, clientPort,
                    seq = seq, ack = ack,
                    flags = FLAG_RST or FLAG_ACK,
                )
            )
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 首次 SYN：回 SYN-ACK（带 MSS 选项），SYN 占一个序号。 */
    private fun replySynAck(clientIsnSeq: Long) {
        val (seq, ack) = vstate.onSyn(clientIsnSeq)
        tunEgress(
            PacketBuilder.buildTcpReply(
                serverIp, clientIp, serverPort, clientPort,
                seq = seq, ack = ack,
                flags = FLAG_SYN or FLAG_ACK,
                mss = PacketBuilder.DEFAULT_MSS,
            )
        )
    }

    /** 重复 SYN：幂等重发 SYN-ACK，**不推进序号**。序号用首次 SYN-ACK 的 ISN（而非推进后的 vsSeq）——
     * SYN 本身占一个序号，重发的 SYN-ACK 必须回客户端期待的同一 ISN（P1-9）。 */
    private fun resendSynAck() {
        val (seq, ack) = vstate.synAckSnapshot()
        tunEgress(
            PacketBuilder.buildTcpReply(
                serverIp, clientIp, serverPort, clientPort,
                seq = seq, ack = ack,
                flags = FLAG_SYN or FLAG_ACK,
                mss = PacketBuilder.DEFAULT_MSS,
            )
        )
    }

    private fun replyAck(seq: Long, ack: Long) {
        tunEgress(
            PacketBuilder.buildTcpReply(
                serverIp, clientIp, serverPort, clientPort,
                seq = seq, ack = ack,
                flags = FLAG_ACK,
            )
        )
    }

    private fun replyFin() {
        val p = vstate.takeFin() ?: return // 尚未发出有效序号，无 FIN 可回
        tunEgress(
            PacketBuilder.buildTcpReply(
                serverIp, clientIp, serverPort, clientPort,
                seq = p.first, ack = p.second,
                flags = FLAG_FIN or FLAG_ACK,
            )
        )
    }

    private fun maybeFullyClose() {
        if (vstate.bothFinished()) close(true)
    }

    /** 关闭连接并清理（幂等；多线程并发调用时 [onClosed] 只触发一次）。 */
    private fun close(graceful: Boolean) {
        synchronized(CLOSE_LOCK) {
            if (!running) return
            running = false
        }
        try {
            socket.close()
        } catch (e: IOException) {
            // 忽略
        }
        onClosed()
    }

    /** 服务停止时由 ConnectionManager 调用。 */
    fun shutdown() = close(false)

    /**
     * 连接是否已关闭。
     *
     * 供 ConnectionManager 处理建连竞态：`establish()` 内部是**后台线程** connect，
     * 它可能在调用方把本对象放进 `tcpFlows` 之前就失败并触发 `onClosed`——那一刻
     * `remove(key)` 是空操作。若调用方不加判断仍入表，这条死连接将永久驻留、永不回收。
     */
    fun isClosed(): Boolean = !running

    /**
     * 虚拟序号状态机（游戏视角）。**所有**读写都在 [lock] 内完成，且只做纯计算——
     * 发包（tunEgress）一律在锁外进行，避免把 IO 拉进临界区导致相互阻塞。
     */
    private class VirtualState(private val startSeq: Long) {
        private val lock = Any()
        private var vsSeq: Long = startSeq
        private var clientExpected: Long = 0L
        private var clientIsn: Long = -1L
        private var synAckSeq: Long = startSeq   // 首次 SYN-ACK 实际下发的 seq（ISN），重发严格回它
        private var clientFinishedSend = false
        private var serverFinishedSend = false

        /** SYN：登记客户端 isn，返回 SYN-ACK 应带的 (seq, ack)；SYN 占一个序号。 */
        fun onSyn(clientSeq: Long): Pair<Long, Long> = synchronized(lock) {
            clientIsn = clientSeq
            clientExpected = clientSeq + 1
            synAckSeq = vsSeq
            val p = vsSeq to clientExpected
            vsSeq += 1
            p
        }

        /** 重发 SYN-ACK 的口径：(ISN, clientExpected)——seq 用首次下发的 ISN，不随 vsSeq 推进（P1-9）。 */
        fun synAckSnapshot(): Pair<Long, Long> = synchronized(lock) { synAckSeq to clientExpected }

        /** 是否已握过手且该 seq 与首个 SYN 相同（即重复 SYN，需要重发 SYN-ACK）。 */
        fun isDuplicateSyn(clientSeq: Long): Boolean = synchronized(lock) {
            clientIsn >= 0 && clientSeq == clientIsn
        }

        /** C→S 载荷：推进 clientExpected，返回回 ACK 应带的 (seq, ack)。 */
        fun onClientData(seq: Long, len: Int): Pair<Long, Long> = synchronized(lock) {
            val end = seq + len
            if (end > clientExpected) clientExpected = end
            vsSeq to clientExpected
        }

        /** S→C 数据：占 [n] 个序号，返回本段应带的 (seq, ack)。 */
        fun takeDataSeq(n: Int): Pair<Long, Long> = synchronized(lock) {
            val s = vsSeq
            vsSeq += n
            s to clientExpected
        }

        /** FIN：占一个序号，返回 (seq, ack)；尚未发出有效序号时返回 null。 */
        fun takeFin(): Pair<Long, Long>? = synchronized(lock) {
            if (vsSeq <= startSeq) {
                null
            } else {
                val p = vsSeq to clientExpected
                vsSeq += 1
                p
            }
        }

        /** 取当前 (seq, ack) 快照（RST / 重发 SYN-ACK 用，**不**推进序号）。 */
        fun snapshot(): Pair<Long, Long> = synchronized(lock) { vsSeq to clientExpected }

        fun setClientFin(): Boolean = synchronized(lock) {
            clientFinishedSend = true
            clientFinishedSend && serverFinishedSend
        }

        fun setServerFin(): Boolean = synchronized(lock) {
            serverFinishedSend = true
            clientFinishedSend && serverFinishedSend
        }

        fun bothFinished(): Boolean = synchronized(lock) { clientFinishedSend && serverFinishedSend }
    }
}
