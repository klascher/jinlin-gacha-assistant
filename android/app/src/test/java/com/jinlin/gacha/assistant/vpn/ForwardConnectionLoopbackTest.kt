package com.jinlin.gacha.assistant.vpn

import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_ACK
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_FIN
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_PSH
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_RST
import com.jinlin.gacha.assistant.vpn.IPPacket.FLAG_SYN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ForwardConnection 本地回环测试（T8，04 方案 §4）——遗留问题 #1 乐观握手修复的 JVM 归因手段。
 *
 * 原理：用 `127.0.0.1` 的 [ServerSocket] 扮演游戏服务器，测试自己扮演游戏内核
 * （构造 [ParsedIpPacket] 喂 `onClientPacket`），把 `tunEgress` 换成捕获队列，
 * 再用 [IPPacket.parse] 解析 App 发出的包逐字段断言。全程无 Android 设备、无游戏：
 * - 唯一的 android 依赖 `Log` 由 `unitTests.isReturnDefaultValues=true`（build.gradle.kts）变成空操作；
 * - `protect` 是注入的 lambda（测试传空 / 用 latch 卡位）；
 * - TUN 由 `tunEgress` lambda 替身。
 *
 * 覆盖（对应 05-遗留问题.md #1 与 P1-9/P1-10）：
 * 1. SYN **立即**回 SYN-ACK（带 MSS），真实 connect 尚未发生（乐观握手核心）；
 * 2. 重复 SYN 幂等重发 SYN-ACK 且序号不推进（P1-9）；
 * 3. connect 进行中 C→S 载荷缓冲、连上后按序冲刷（用 latch **确定性**构造微秒级窗口，实机上无法按需复现）;
 * 4. 双向搬运 seq/ack 连续 + 服务器关闭传播 FIN + 客户端 RST 关闭；
 * 5. connect 失败回 RST、不悬挂、`onClosed` 触发；
 * 6. 客户端 FIN 半关传播到真实 socket + 双边 FIN 后自动关闭；
 * 7. **connect 尚未完成时收到 FIN**：载荷进缓冲、FIN 延迟到连上并冲刷后补做半关闭
 *    （回归 `ForwardConnection` 审计项 #C：旧实现直接 `shutdownOutput()` 会抛异常被吞，服务器收不到 EOF）。
 *
 * 不覆盖（归全量环境实机复验）：tun 读写、ConnectionManager 装配、真机内核 TCP 栈行为。
 */
class ForwardConnectionLoopbackTest {

    // 每个测试方法 JUnit 都会 new 一个新实例，队列/标志天然隔离。
    private val egress = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)

    private companion object {
        const val SERVER_IP = "127.0.0.1"
        const val CLIENT_IP = "10.8.0.2"
        const val CLIENT_PORT = 44444
        const val AWAIT_MS = 5_000L
    }

    // ---- 替身与断言辅助 ----

    /** 捕获 tun 出向包，用生产同款解析器解回结构（同时验证包对游戏内核是可解析的）。 */
    private fun parseEgress(raw: ByteArray): ParsedIpPacket =
        IPPacket.parse(raw, raw.size) ?: error("egress 包无法被 IPPacket.parse 解析（头不合法）")

    /** 轮询等待出向包中首个满足条件的包，超时 fail（不依赖 sleep、不依赖到达顺序）。 */
    private fun awaitPacket(what: String, pred: (ParsedIpPacket) -> Boolean): ParsedIpPacket {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS)
        while (System.nanoTime() < deadline) {
            val raw = egress.poll(50, TimeUnit.MILLISECONDS) ?: continue
            val p = parseEgress(raw)
            if (pred(p)) return p
        }
        throw AssertionError("${AWAIT_MS}ms 内未等到：$what（队列残留 ${egress.size} 包）")
    }

    private fun awaitTrue(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS)
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("${AWAIT_MS}ms 内未满足：$what")
    }

    /** 发 SYN 并等 SYN-ACK，返回该 SYN-ACK（seq 用游戏视角虚拟序号，不依赖私有常量）。 */
    private fun handshake(conn: ForwardConnection, serverPort: Int, isn: Long): ParsedIpPacket {
        conn.onClientPacket(clientPacket(serverPort, FLAG_SYN, seq = isn))
        return awaitPacket("SYN-ACK") {
            it.tcpFlags and (FLAG_SYN or FLAG_ACK) == FLAG_SYN or FLAG_ACK
        }
    }

    /** 扮演游戏内核构造入向包（直接构造 ParsedIpPacket，即 tun 读路径产出的同一结构）。 */
    private fun clientPacket(
        serverPort: Int, flags: Int, seq: Long,
        payload: ByteArray = ByteArray(0),
    ): ParsedIpPacket = ParsedIpPacket(
        protocol = IPPacket.PROTO_TCP,
        srcIp = CLIENT_IP, dstIp = SERVER_IP,
        srcPort = CLIENT_PORT, dstPort = serverPort,
        raw = ByteArray(0), length = 0,
        isTcp = true, isUdp = false,
        tcpSeq = seq, tcpAck = 0,
        tcpDataOffset = 20,
        tcpPayload = payload,
        tcpFlags = flags,
    )

    /** SYN-ACK 的 MSS 选项值（PacketBuilder 固定 ihl=5 → TCP 头在 offset 20，选项在 20+20=40）。 */
    private fun mssOptionValue(synAck: ParsedIpPacket): Int {
        val o = 20 + 20
        assertEquals("MSS option kind", 2, synAck.raw[o].toInt() and 0xff)
        assertEquals("MSS option length", 4, synAck.raw[o + 1].toInt() and 0xff)
        return ((synAck.raw[o + 2].toInt() and 0xff) shl 8) or (synAck.raw[o + 3].toInt() and 0xff)
    }

    /** 服务器侧收满 n 字节（socket 设 soTimeout 防挂死）。 */
    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) fail("对端在收满 $n 字节前关闭（已收 $off）")
            off += r
        }
        return out
    }

    private fun establish(serverPort: Int, protect: (Socket) -> Unit = {}): ForwardConnection =
        ForwardConnection.establish(
            serverIp = SERVER_IP, serverPort = serverPort,
            clientIp = CLIENT_IP, clientPort = CLIENT_PORT,
            protect = protect, reassembly = null,
            tunEgress = { egress.put(it) },
            onClosed = { closed.set(true) },
        )

    // ---- T8-1：乐观握手核心 ----

    @Test(timeout = 10_000)
    fun `t1 syn 立即回 syn-ack 带 mss 此时真实 connect 尚未发生`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            // latch 卡在 protect（connect 前最后一站）：确定性证明「connect 未跑，SYN-ACK 已回」
            val gate = CountDownLatch(1)
            val conn = establish(server.localPort) { gate.await() }
            try {
                val isn = 100_000L
                val synAck = handshake(conn, server.localPort, isn)

                assertEquals("flags = SYN|ACK", FLAG_SYN or FLAG_ACK, synAck.tcpFlags and (FLAG_SYN or FLAG_ACK))
                assertEquals("ack = isn+1", isn + 1, synAck.tcpAck)
                assertEquals("TCP 头 24B（20 + 4B MSS 选项）", 24, synAck.tcpDataOffset)
                assertEquals("MSS = DEFAULT_MSS", PacketBuilder.DEFAULT_MSS, mssOptionValue(synAck))
                assertEquals("SYN-ACK 方向：src=服务器视角", SERVER_IP, synAck.srcIp)

                // 乐观握手的关键证据：connect 被 latch 卡死，服务器侧不应有任何连接进来
                server.soTimeout = 300
                try {
                    server.accept()
                    fail("protect 卡住时不应有真实连接到达服务器")
                } catch (_: SocketTimeoutException) {
                    // 期望：没有连接
                }
            } finally {
                conn.shutdown()
                gate.countDown() // 释放后台线程，避免悬挂（后续动作会被 running=false 吞掉）
            }
        }
    }

    @Test(timeout = 10_000)
    fun `t2 重复 syn 幂等重发 syn-ack 且序号不推进`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val conn = establish(server.localPort)
            try {
                val isn = 200_000L
                val first = handshake(conn, server.localPort, isn)
                // 游戏内核重传同一 SYN（首个 SYN-ACK 可能丢了）
                conn.onClientPacket(clientPacket(server.localPort, FLAG_SYN, seq = isn))
                val second = awaitPacket("重复 SYN 的 SYN-ACK") {
                    it.tcpFlags and (FLAG_SYN or FLAG_ACK) == FLAG_SYN or FLAG_ACK
                }
                assertEquals("重发 SYN-ACK 的 seq 必须与首次相同（P1-9：不推进序号）", first.tcpSeq, second.tcpSeq)
                assertEquals("ack 同样不推进", first.tcpAck, second.tcpAck)
            } finally {
                conn.shutdown()
            }
        }
    }

    // ---- T8-3：缓冲与冲刷（乐观握手最难在实机复现的场景） ----

    @Test(timeout = 10_000)
    fun `t3 connect 进行中载荷先缓冲 连上后按序冲刷`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val gate = CountDownLatch(1)
            val conn = establish(server.localPort) { gate.await() }
            try {
                val isn = 300_000L
                handshake(conn, server.localPort, isn)

                val first = "hello-buffer".toByteArray()
                conn.onClientPacket(clientPacket(server.localPort, FLAG_ACK, seq = isn + 1, payload = first))
                // 游戏内核应立刻收到 ACK（ack 推进），哪怕真实 socket 还没连上
                val ack = awaitPacket("载荷 ACK") {
                    it.tcpFlags == FLAG_ACK && it.tcpPayload.isEmpty() && it.tcpAck == isn + 1 + first.size
                }
                assertEquals("ACK 的 src 是服务器视角", SERVER_IP, ack.srcIp)

                // connect 仍被 latch 卡死：服务器不应收到任何字节
                server.soTimeout = 300
                try {
                    server.accept()
                    fail("connect 未完成时不应有连接到达")
                } catch (_: SocketTimeoutException) {
                }

                // 放行 → connect 完成 → 缓冲必须第一时间按原序冲刷给 accept 到的连接
                gate.countDown()
                val accepted = server.accept()
                accepted.soTimeout = AWAIT_MS.toInt()
                assertArrayEquals("缓冲载荷按原序冲刷", first, readFully(accepted.inputStream, first.size))

                // 连上后再发的载荷走直通路径，不进缓冲
                val second = "second".toByteArray()
                conn.onClientPacket(
                    clientPacket(server.localPort, FLAG_ACK or FLAG_PSH, seq = isn + 1 + first.size, payload = second),
                )
                assertArrayEquals("连上后载荷直通", second, readFully(accepted.inputStream, second.size))
            } finally {
                conn.shutdown()
                gate.countDown()
            }
        }
    }

    // ---- T8-4：双向搬运 + FIN/RST 传播 ----

    @Test(timeout = 10_000)
    fun `t4 双向搬运 seq ack 连续 服务器关闭传播 fin 客户端 rst 关闭`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val conn = establish(server.localPort)
            try {
                val isn = 400_000L
                val synAck = handshake(conn, server.localPort, isn)
                val accepted = server.accept()
                accepted.soTimeout = AWAIT_MS.toInt()

                // C→S：游戏发 "ping"，服务器应收到
                val c2s = "ping".toByteArray()
                conn.onClientPacket(clientPacket(server.localPort, FLAG_ACK or FLAG_PSH, seq = isn + 1, payload = c2s))
                assertArrayEquals(c2s, readFully(accepted.inputStream, c2s.size))
                // 游戏应收到 ACK（ack 推进 isn+1+len；seq 不消费——纯 ACK 快照）
                awaitPacket("C→S 载荷的 ACK") { it.tcpFlags == FLAG_ACK && it.tcpAck == isn + 1 + c2s.size }

                // S→C：服务器回 "pong!"，游戏应收到 PSH|ACK，seq 紧接 SYN-ACK 连续推进
                val s2c = "pong!".toByteArray()
                accepted.outputStream.write(s2c)
                accepted.outputStream.flush()
                val data = awaitPacket("S→C 数据包") { it.tcpPayload.isNotEmpty() }
                assertArrayEquals(s2c, data.tcpPayload)
                assertEquals("PSH|ACK", FLAG_PSH or FLAG_ACK, data.tcpFlags and (FLAG_PSH or FLAG_ACK))
                assertEquals("S→C seq 紧接 SYN-ACK", synAck.tcpSeq + 1, data.tcpSeq)
                assertEquals("S→C ack = 游戏已发字节数", isn + 1 + c2s.size, data.tcpAck)
                assertEquals("回包方向：src=服务器", SERVER_IP, data.srcIp)
                assertEquals("回包方向：dst=游戏", CLIENT_IP, data.dstIp)
                assertEquals("回包端口", CLIENT_PORT, data.dstPort)

                // 服务器半关 → 游戏侧应收 FIN|ACK，seq 紧接数据段
                accepted.shutdownOutput()
                val fin = awaitPacket("服务器 FIN") { it.tcpFlags and FLAG_FIN != 0 }
                assertEquals("FIN|ACK", FLAG_FIN or FLAG_ACK, fin.tcpFlags and (FLAG_FIN or FLAG_ACK))
                assertEquals("FIN seq = 数据段 seq + len", data.tcpSeq + s2c.size, fin.tcpSeq)

                // 客户端 RST → 转运关闭、onClosed 触发（不悬挂）
                conn.onClientPacket(clientPacket(server.localPort, FLAG_RST, seq = isn + 1 + c2s.size))
                awaitTrue("RST 后连接关闭") { conn.isClosed() && closed.get() }
            } finally {
                conn.shutdown()
            }
        }
    }

    // ---- T8-5：connect 失败路径 ----

    @Test(timeout = 10_000)
    fun `t5 connect 失败回 rst 不悬挂 onClosed 触发`() {
        // 找一个当前无人监听的本地端口（先绑后关）
        val deadPort = ServerSocket(0).use { it.localPort }
        val conn = establish(deadPort)
        // establish 本身就启动了后台 connect，无需发 SYN，失败即应回 RST
        val rst = awaitPacket("connect 失败的 RST") { it.tcpFlags and FLAG_RST != 0 }
        assertEquals("RST|ACK", FLAG_RST or FLAG_ACK, rst.tcpFlags and (FLAG_RST or FLAG_ACK))
        assertEquals("RST 来自服务器视角", SERVER_IP, rst.srcIp)
        awaitTrue("连接标记为已关闭（不悬挂）") { conn.isClosed() }
        awaitTrue("onClosed 已触发（可从 flow 表移除）") { closed.get() }
    }

    // ---- T8-6：客户端 FIN 半关传播 + 双边 FIN 自动关闭 ----

    @Test(timeout = 10_000)
    fun `t6 客户端 fin 传播到真实 socket 双边 fin 后自动关闭`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val conn = establish(server.localPort)
            try {
                val isn = 500_000L
                handshake(conn, server.localPort, isn)
                val accepted = server.accept()
                accepted.soTimeout = AWAIT_MS.toInt()

                // 服务器先发一段数据并半关 → 游戏收到数据 + FIN
                val s2c = "tail".toByteArray()
                accepted.outputStream.write(s2c)
                accepted.outputStream.flush()
                val data = awaitPacket("S→C 数据包") { it.tcpPayload.isNotEmpty() }
                accepted.shutdownOutput()
                val serverFin = awaitPacket("服务器 FIN") { it.tcpFlags and FLAG_FIN != 0 }

                // 游戏回 FIN（半关）→ 应回复第二个 FIN|ACK（seq 紧接服务器 FIN），
                // 且双边 FIN 齐了 → 连接自动关闭
                conn.onClientPacket(clientPacket(server.localPort, FLAG_ACK or FLAG_FIN, seq = isn + 1))
                val clientFinReply = awaitPacket("客户端 FIN 的 FIN|ACK 回复") { it.tcpFlags and FLAG_FIN != 0 }
                assertEquals("第二个 FIN seq 紧接服务器 FIN", serverFin.tcpSeq + 1, clientFinReply.tcpSeq)
                awaitTrue("双边 FIN 后自动关闭") { conn.isClosed() && closed.get() }

                // FIN 传播到真实 socket：服务器读侧应见 EOF
                assertEquals("真实 socket 收到 EOF（客户端 shutdownOutput）", -1, accepted.inputStream.read())
            } finally {
                conn.shutdown()
            }
        }
    }

    // ---- T8-7：connect 未完成时收到 FIN（延迟半关闭，回归 #C）----

    @Test(timeout = 10_000)
    fun `t7 connect 完成前收到 fin 连上后补半关闭 服务器见 eof`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            // 闸门卡在 protect（真实 connect 前最后一站）：制造「客户端 FIN 先于 connect 完成」的确定窗口。
            // 这正是旧实现的缺陷路径——彼时 shutdownOutput() 会抛 SocketException 被吞，输出端永不关闭。
            val gate = CountDownLatch(1)
            val conn = establish(server.localPort) { gate.await() }
            try {
                val isn = 600_000L
                handshake(conn, server.localPort, isn)

                // connect 仍卡死 → 此刻的载荷进缓冲、FIN 只能走「延迟半关闭」
                val payload = "last-chunk".toByteArray()
                conn.onClientPacket(
                    clientPacket(server.localPort, FLAG_ACK or FLAG_FIN, seq = isn + 1, payload = payload),
                )
                awaitPacket("客户端 FIN 的 FIN|ACK 回复") { it.tcpFlags and FLAG_FIN != 0 }

                // 服务器此刻不应有连接（FIN 只被登记，未碰真实 socket）
                server.soTimeout = 300
                try {
                    server.accept()
                    fail("connect 卡住时不应有真实连接到达服务器")
                } catch (_: SocketTimeoutException) {
                }

                // 放行 → connect 完成 → 必须先冲刷缓冲载荷，再补上发送方向半关闭
                gate.countDown()
                val accepted = server.accept()
                accepted.soTimeout = AWAIT_MS.toInt()
                assertArrayEquals(
                    "FIN 之前的载荷仍按序送达",
                    payload,
                    readFully(accepted.inputStream, payload.size),
                )
                val eof: Int = try {
                    accepted.inputStream.read()
                } catch (_: SocketTimeoutException) {
                    fail("服务器读超时——延迟半关闭未生效（真实 socket 未收到客户端 FIN）")
                    -1
                }
                assertEquals("连上后补半关闭：服务器读侧应见 EOF", -1, eof)
            } finally {
                conn.shutdown()
                gate.countDown()
            }
        }
    }
}
