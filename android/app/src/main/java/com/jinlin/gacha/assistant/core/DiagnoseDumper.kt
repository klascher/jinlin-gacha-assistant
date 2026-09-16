package com.jinlin.gacha.assistant.core

import android.content.Context
import android.util.Log
import com.jinlin.gacha.assistant.vpn.IPPacket
import com.jinlin.gacha.assistant.vpn.ParsedIpPacket
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 诊断落盘器（对应 `mobile/docs/02-阶段1设计.md` §4）。
 *
 * 职责：给 M1 抓取 + M2 切分各挂一个「可疑信号判别器」。平时只把最近一段原始 IP 包
 * 圈在内存环形缓冲里（[RING_SIZE]），**不写盘**；一旦用户手动触发（通知栏「抓诊断」）
 * 或自动判据命中，就把该段时间的原始包落成标准 pcap，供拷回仅开发环境复用 PC 管线复现排查。
 *
 * 诚实边界 / 取向：真机正常操作里「隧道开了但还没开游戏」「只翻别人页面不翻抽卡」本就会
 * 产生「无包 / S->C 零载荷 / 判定恒 0」现象，自动判据天然易误报。因此 **自动判据默认只
 * 打印提示、不主动落盘**（[AUTO_TRIGGER_ENABLED]=false）；真正落盘以用户手动为主。
 *
 * 线程：observe() 由 tun 读线程调；录制写盘在独立线程，不阻塞转发主路径。
 */
class DiagnoseDumper(private val appContext: Context) {

    /** M2 切分统计快照（由 ReassemblyWorker 周期上报，供 B 类判据消费）。 */
    data class Stats(
        val serverFrames: Long = 0,
        val gachaHits: Long = 0,
        val clearedTails: Long = 0,
        /** 汇聚队列满而被丢弃的输入数（>0 说明 worker 消费跟不上，需关注）。 */
        val droppedInputs: Long = 0,
        /** feed 过程中被吞掉的异常次数（原为空 catch，出问题时无线索）。 */
        val feedErrors: Long = 0,
        /** 乱序缓冲越上限、被迫跳过 seq 缺口的次数（正常流应恒为 0）。 */
        val skippedGaps: Long = 0,
        /** 空闲流被回收的累计数量。 */
        val sweptFlows: Long = 0,
    )

    companion object {
        private const val TAG = "GachaDiag"
        private const val RING_SIZE = 4096           // 环形缓冲保最近 N 原包（约 MB 级常驻）
        private const val RECORD_WINDOW_MS = 12_000L // 触发后继续捞 12s 的包
        /** 自动落盘总开关：默认关（见类注释），开启后判据命中才真正写盘。 */
        private const val AUTO_TRIGGER_ENABLED = false
        private const val AUTO_SILENCE_MS = 20_000L  // 启动缓冲期：超此才算「持续异常」，避免起步误判
        private const val SYNC_PKT_BEFORE_WARN = 60L // A2：累计包数越此仍无 S->C 载荷才判可疑
        private const val CHECK_INTERVAL_MS = 1_000L
        private const val FULL_QUEUE = 65536        // 全量录包写缓冲；满则计丢包（不阻塞 tun 读）
        private const val FULL_SUBDIR = "records"   // 全量录包输出子目录（与诊断 diagnose/ 分开）
        /**
         * 全量录包单文件字节上限。长时间挂机录包若无上限会一路写满磁盘
         * （游戏流量可达数十 MB/min），到时不仅录包失败，整个 App 数据都可能被拖垮。
         */
        private const val FULL_MAX_BYTES = 512L * 1024 * 1024
        /** 剩余可用空间低于此值（MB）自动停录，避免写爆分区。 */
        private const val FULL_MIN_FREE_MB = 200L
    }

    @Volatile private var collecting = false
    private val startedAt = AtomicLong(0)

    // —— M1 抓取计数（模拟非阻塞，tun 线程写）——
    private val pktCount = AtomicLong(0)
    private val s2cPayloadPkts = AtomicLong(0)

    // —— M2 切分统计（reassembly worker 上报）——
    private val lastStats = AtomicReference(Stats())

    // —— 环形缓冲（tun 线程写 / 触发快照读）——
    private data class Record(val tsSec: Long, val tsUsec: Long, val raw: ByteArray)
    private val ring = ArrayDeque<Record>()
    private val ringLock = Object()

    // —— 录制写盘（写线程消费）——
    private val pending = LinkedBlockingQueue<Record>(2048)
    @Volatile private var recording = false
    @Volatile private var recExpireAt = 0L

    // —— 全量录包（通知栏「录包」开关：开→关之间把全部原始 IP 包完整写成一个 pcap，供拷回 PC 管线分析）——
    @Volatile private var fullRecording = false
    private val fullQueue = LinkedBlockingQueue<Record>(FULL_QUEUE)
    @Volatile private var fullDrops = 0L

    /**
     * 全量录包状态回调（对应 `07-App观测面板与录包入口设计.md` §3.1）。
     * 三处触发：toggle 开 `(true, file, null)` / toggle 关 `(false, null, null)` /
     * fullWriteLoop 提前停止（触顶 / 磁盘不足）`(false, file, stopReason)`。
     * 第 3 条是易漏点：不加的话，录包被自动打断后 UI 仍显示「停录」。
     * 由 GachaVpnService 注册，落到 companion 供 Activity 读取——**回调而非 Activity 轮询 dumper**。
     */
    var onRecordStateChanged: ((recording: Boolean, file: File?, reason: String?) -> Unit)? = null

    private var checkerThread: Thread? = null

    /** 隧道启动：重置计数 + 拉起判别器。 */
    fun start() {
        startedAt.set(System.currentTimeMillis())
        pktCount.set(0)
        s2cPayloadPkts.set(0)
        lastStats.set(Stats())
        collecting = true
        if (checkerThread?.isAlive != true) {
            checkerThread = Thread(::checkLoop, "diagnose-checker").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** 服务停止：停止录制（写线程检测 [collecting] 退出并关文件）。 */
    fun stop() {
        collecting = false
        recording = false
        fullRecording = false   // 停隧道即停全量录包（写线程收尾关文件）
    }

    /**
     * 旁路观察一个原始 IP 包（转发主路径外）。只做「计数 + 入环 + 若录制/录包中则投写」，
     * 无阻塞无分配热点（raw 已在 parse 时拷贝，直接复用）。
     */
    fun observe(pkt: ParsedIpPacket, nowMillis: Long) {
        if (!collecting) return
        pktCount.incrementAndGet()
        // A2 口径：S->C 方向（src=公网服务器）且带 TCP 载荷的回包
        if (pkt.isTcp && pkt.tcpPayload.isNotEmpty() && isServerSrc(pkt)) {
            s2cPayloadPkts.incrementAndGet()
        }
        addRaw(nowMillis, pkt.raw)
    }

    /**
     * 记录我们写回 tun 的 S->C 包（补全「完整流量」的服务器→手机一侧）。
     * tun 只会读到游戏发出来的包，读不到我们自己 [tunEgress] 写回的包，故必须在此单独 tee 一次，
     * 否则「完整复制一份流量」只剩半程。raw 为完整 IP 包字节。
     */
    fun recordOutgoing(raw: ByteArray, nowMillis: Long) {
        if (!collecting) return
        // A2 判据的 S->C 计数只能在这里补：tun 读循环（observe）只见得到游戏发出的 C->S 包，
        // 其 srcIp 是 addAddress 分配的私网地址，isServerSrc 恒 false；我们写回 tun 的 S->C 包
        // 根本不经过 readLoop。两处叠加 => s2cPayloadPkts 恒 0 => A2 每秒必报（判据完全失效）。
        val parsed = IPPacket.parse(raw, raw.size)
        if (parsed != null && parsed.isTcp && parsed.tcpPayload.isNotEmpty() && isServerSrc(parsed)) {
            s2cPayloadPkts.incrementAndGet()
        }
        addRaw(nowMillis, raw)
    }

    /** 统一入口：进环形缓冲 + 若诊断/录包中则入写队列。 */
    private fun addRaw(nowMillis: Long, raw: ByteArray) {
        val rec = Record(nowMillis / 1000, (nowMillis % 1000) * 1000, raw)
        synchronized(ringLock) {
            ring.addLast(rec)
            while (ring.size > RING_SIZE) ring.removeFirst()
        }
        if (recording) pending.offer(rec)
        if (fullRecording && !fullQueue.offer(rec)) fullDrops++
    }

    /** ReassemblyWorker 周期上报切分统计。 */
    fun onReassemblyStats(s: Stats) {
        lastStats.set(s)
    }

    /** 累计观察到的原始 IP 包数（tun 读循环统计，供观测汇总用）。 */
    fun packetCount(): Long = pktCount.get()

    /** 最近一次切分统计快照（供观测汇总用）。 */
    fun lastStats(): Stats = lastStats.get()

    /** 用户手动触发（通知栏「抓诊断」等）。总是落盘。 */
    fun manualTrigger() {
        CaptureLog.i(TAG, "手动触发诊断落盘")
        trigger("manual")
        CaptureLog.i(TAG, "诊断已触发，12s 后见 diagnose/ 目录")
    }

    private fun trigger(reason: String) {
        if (recording) return // 已有录制中，幂等
        val snapshot: List<Record>
        synchronized(ringLock) {
            snapshot = ring.toList()
        }
        recording = true
        recExpireAt = System.currentTimeMillis() + RECORD_WINDOW_MS
        Thread({
            writePcap(reason, snapshot)
        }, "diag-writer").also { it.isDaemon = true }.start()
    }

    // —— 全量录包（通知栏「录包」开关，开→关之间完整复制一份原始流量）——

    /** 通知栏「录包」开关当前是否开启。 */
    fun isFullRecording(): Boolean = fullRecording

    /**
     * 通知栏「录包」toggle：开 → 从当前起把每个原始 IP 包持续写盘为一个 pcap；
     * 关 → 写线程把缓冲写空后收尾完成文件。返回开关后的状态（false 也可能是隧道未运行被拒）。
     */
    fun toggleFullRecord(): Boolean {
        if (fullRecording) {
            fullRecording = false
            CaptureLog.i(TAG, "停止全量录包，写线程收尾")
            onRecordStateChanged?.invoke(false, null, null)
            return false
        }
        if (!collecting) {
            CaptureLog.w(TAG, "隧道未运行，无法录包")
            return false
        }
        fullRecording = true
        fullDrops = 0L
        val dir = File(appContext.getExternalFilesDir(null), FULL_SUBDIR).apply { mkdirs() }
        val file = File(dir, "full_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pcap")
        Thread({ fullWriteLoop(file) }, "full-record-writer").also { it.isDaemon = true }.start()
        CaptureLog.i(TAG, "开始全量录包 -> records/${file.name}")
        onRecordStateChanged?.invoke(true, file, null)
        return true
    }

    /** 全量录包写线程：持续写 [fullQueue]；收到停止（[fullRecording]=false 或隧道停）后写空缓冲再关文件。 */
    private fun fullWriteLoop(file: File) {
        val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))
        var written = 0L
        var stopReason: String? = null
        try {
            writeHeader(out)
            while (fullRecording || !fullQueue.isEmpty()) {
                val r = fullQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                writeRecord(out, r)
                written += r.raw.size + 16 // 16 = pcap 记录头
                if (written >= FULL_MAX_BYTES) {
                    stopReason = "已达单文件上限 ${FULL_MAX_BYTES / 1024 / 1024}MB"
                    break
                }
                val free = usableFreeMb(file)
                if (free > 0 && free < FULL_MIN_FREE_MB) {
                    stopReason = "磁盘剩余空间不足 ${FULL_MIN_FREE_MB}MB（当前 ${free}MB）"
                    break
                }
            }
            out.flush()
        } catch (e: Exception) {
            CaptureLog.w(TAG, "全量录包失败", e)
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
        if (stopReason != null) {
            // 主动停录：置回开关并清空缓冲，否则写线程会一直判定「仍在录」而空转
            fullRecording = false
            fullQueue.clear()
            CaptureLog.w(TAG, "全量录包提前停止：$stopReason -> records/${file.name}")
            // 易漏点：被自动打断时若不回调，Activity 侧按钮会永远停在「停录」
            onRecordStateChanged?.invoke(false, file, stopReason)
        } else {
            val n = fullDrops
            CaptureLog.i(
                TAG,
                if (n > 0) "全量录包完成（丢包 $n，文件不完整）: records/${file.name}"
                else "全量录包完成: records/${file.name}",
            )
        }
    }

    /** 目标文件所在分区的可用空间（MB）；取不到返回 0，调用方据此不设限。 */
    private fun usableFreeMb(file: File): Long = try {
        file.usableSpace / (1024 * 1024)
    } catch (_: Exception) {
        0L
    }

    // —— 判别器（每秒）：A1/A2 抓取异常、B 切分疑似，默认仅提示 ——
    private fun checkLoop() {
        while (collecting) {
            try {
                checkAutomatic()
            } catch (e: Exception) {
                Log.w(TAG, "判别器异常", e)
            }
            try {
                Thread.sleep(CHECK_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun checkAutomatic() {
        val now = System.currentTimeMillis()
        val elapsed = now - startedAt.get()
        if (elapsed < AUTO_SILENCE_MS) return // 起步缓冲期，不开火
        val pkts = pktCount.get()
        val s2c = s2cPayloadPkts.get()
        val st = lastStats.get()

        var hit: String? = null
        // A1：启动后至今一个包都没见到（隧道空转 / 白名单没拦到）
        if (pkts == 0L) hit = "A1 隧道建立后 ${elapsed / 1000}s 仍无任何 IP 包"
        // A2：有不少包却没有一条服务器回包（方向/端口/连接建立异常）
        else if (pkts >= SYNC_PKT_BEFORE_WARN && s2c == 0L) hit =
            "A2 累计 ${pkts} 包仍无 S->C 载荷回包"
        // B1：S->C 切出帧不少却判定恒 0（方向反 / 信封错位 / 协议判错）
        else if (st.serverFrames >= 3L && st.gachaHits == 0L) hit =
            "B1 S->C 已切 ${st.serverFrames} 帧但 looks_like_gacha 恒 0"

        if (hit != null) {
            val msg = "$hit；切分层统计=${st}  ← 若现象可疑请在通知栏点「抓诊断」存原始流量"
            if (AUTO_TRIGGER_ENABLED) {
                CaptureLog.w(TAG, "自动落盘: $msg")
                trigger("auto")
            } else {
                CaptureLog.w(TAG, msg)
            }
        }
    }

    // —— pcap 写入（标准 RAW linktype=101，scapy 可读；格式与仅开发环境 m_diag_replay 一致）——
    private fun writePcap(reason: String, snapshot: List<Record>) {
        val dir = File(appContext.getExternalFilesDir(null), "diagnose").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "dump_${reason}_$stamp.pcap")
        val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))
        try {
            writeHeader(out)
            for (r in snapshot) writeRecord(out, r)
            while (collecting) {
                val r = pending.poll(250, TimeUnit.MILLISECONDS)
                if (r != null) {
                    writeRecord(out, r)
                } else if (System.currentTimeMillis() > recExpireAt) {
                    break
                }
            }
            out.flush()
            CaptureLog.i(TAG, "已写诊断文件: diagnose/${file.name}")
        } catch (e: Exception) {
            CaptureLog.w(TAG, "诊断落盘失败", e)
        } finally {
            try { out.close() } catch (_: Exception) {}
            recording = false
        }
    }

    private fun writeHeader(out: DataOutputStream) {
        // pcap 全局头（24B，小端）：magic/ver/sig/linksize/linktype=101(RAW)
        le32(out, 0xa1b2c3d4)          // magic（big-endian 合法，scapy 认）
        le16(out, 2); le16(out, 4)      // version 2.4
        le32(out, 0); le32(out, 0)      // thiszone/sigfigs
        le32(out, 65535); le32(out, 101) // snaplen / linktype RAW
    }

    private fun writeRecord(out: DataOutputStream, r: Record) {
        le32(out, r.tsSec); le32(out, r.tsUsec)
        le32(out, r.raw.size.toLong()); le32(out, r.raw.size.toLong())
        out.write(r.raw)
    }

    private fun le16(out: DataOutputStream, v: Int) {
        out.writeByte(v and 0xff); out.writeByte((v ushr 8) and 0xff)
    }

    private fun le32(out: DataOutputStream, v: Long) {
        out.writeByte((v and 0xff).toInt())
        out.writeByte(((v ushr 8) and 0xff).toInt())
        out.writeByte(((v ushr 16) and 0xff).toInt())
        out.writeByte(((v ushr 24) and 0xff).toInt())
    }

    /** 方向判定：src 非 RFC1918 即视为服务器（C->S 的 src 是手机私网，天然被排除）。 */
    private fun isServerSrc(pkt: ParsedIpPacket): Boolean {
        val o = pkt.srcIp.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        val (a, b) = o[0] to o[1]
        if (a == 10 || a == 127 || a == 0) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 169 && b == 254) return false
        if (a in 224..255) return false
        return true
    }
}