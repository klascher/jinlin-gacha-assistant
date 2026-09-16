package com.jinlin.gacha.assistant.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.CaptureLog
import com.jinlin.gacha.assistant.core.DedupSnapshot
import com.jinlin.gacha.assistant.core.DiagnoseDumper
import com.jinlin.gacha.assistant.core.FrameConsumer
import com.jinlin.gacha.assistant.core.FrameSplitter
import com.jinlin.gacha.assistant.core.GachaRecord
import com.jinlin.gacha.assistant.core.ReassemblyWorker
import com.jinlin.gacha.assistant.core.RecordFrameConsumer
import com.jinlin.gacha.assistant.locator.Endpoint
import com.jinlin.gacha.assistant.locator.ObservedEndpointCollector
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** 进程级抓包运行态（供 Compose 顶栏订阅；替代阶段 1 的 Handler 800ms 轮询）。 */
data class CaptureServiceState(
    val running: Boolean = false,
    val recording: Boolean = false,
)

/**
 * 抓包隧道服务（阶段 1 M1 + 方案 A 正式修复）。
 *
 * 数据通路（替换原 loopback 透传，见 `mobile/docs/02-阶段1设计.md` §2）：
 * - `addAllowedApplication` 只接管目标游戏，游戏全部流量进 tun；
 * - 主线程读 tun → [ConnectionManager] 按 4-tuple 派发：
 *   每 TCP 连接建真实 socket + protect() 透明转运（游戏联网），UDP（DNS）最小转发；
 * - 抽卡连接的载荷旁路复制给 [ReassemblyWorker]（单线程）切帧（M2），特征确认定位仍在。
 *
 * 线程：读 tun 单线程；每连接一个 socket 读线程；切帧在 ReassemblyWorker 单消费者。
 */
class GachaVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.jinlin.gacha.assistant.START"
        const val ACTION_STOP = "com.jinlin.gacha.assistant.STOP"

        private const val TAG = "GachaVpn"
        private const val CHANNEL_ID = "gacha_vpn"
        private const val NOTIFICATION_ID = 1001

        /** 目标游戏包名（M1 阶段固定；uid 每次启动解析，兼容重装/分包变化）。 */
        const val TARGET_PACKAGE = "com.bmystu.peng.gw"

        /** 目标游戏展示名：用户可见处一律用此，不把包名写出来。 */
        const val TARGET_GAME_NAME = "夜幕之下"

        /** 历史文件所在子目录（对齐 PC `data/users/`）。 */
        private const val USERS_DIR = "users"

        /** 抓包完成时间戳格式（写入 settings.json 的 `last_capture`）。 */
        private val CAPTURE_TS_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private const val MTU = 4096

        /** 进程级运行态（供 Activity 查询）。 */
        @Volatile
        private var runningNow = false

        /**
         * 进程级运行态推送（阶段 1 的 @Volatile 标志保留，供旧调用 isRunning()/isRecording()；
         * Compose 顶栏统一订阅 [state]，三处改态点同步更新本值，见设计 08 §3.2）。
         */
        private val _state = MutableStateFlow(CaptureServiceState())
        val state: StateFlow<CaptureServiceState> = _state.asStateFlow()

        /**
         * 去重会话状态（记录页据此提示「未翻全部卡池」「还缺 N 页」等）。
         * 由 ReassemblyWorker 消费者线程写入、Compose 订阅读取。
         */
        private val _dedupState = MutableStateFlow(DedupSnapshot())
        val dedupState: StateFlow<DedupSnapshot> = _dedupState.asStateFlow()

        /**
         * 已确认抽卡端点快照（设置页「抓包」组只读展示）。
         *
         * 按 09 设计稿 §8-Q3 决策**不做持久化**：冷启动后为空，重新抓包确认即恢复。
         * 由 [refreshEndpoints] 在「会话启动」与「特征确认到新端点」时刷新。
         */
        private val _endpoints = MutableStateFlow<List<Endpoint>>(emptyList())
        val endpoints: StateFlow<List<Endpoint>> = _endpoints.asStateFlow()

        // —— 抽卡记录（切帧 → 去重 → 记录页展示；跨会话按位置合并落库，见 HistoryStore）——
        private val _records = MutableStateFlow<List<GachaRecord>>(emptyList())
        val records: StateFlow<List<GachaRecord>> = _records.asStateFlow()

        /** 单生产者（ReassemblyWorker 消费者线程）追加一批**去重后**新增的记录。 */
        fun appendRecords(records: List<GachaRecord>) {
            _records.value = _records.value + records
        }

        /** 新会话清洗：每次「开始抓包」重置记录列表（展示当前这次抓到的抽）。 */
        fun resetCapturedRecords() {
            _records.value = emptyList()
        }

        // —— 录包状态（由 dumper 回调落这里，供 Activity 读取；不用 Activity 轮询 dumper）——
        @Volatile
        private var recordingNow = false

        @Volatile
        private var lastRecordPath: File? = null

        @Volatile
        private var lastRecordStopReason: String? = null

        fun isRunning(): Boolean = runningNow
        fun isRecording(): Boolean = recordingNow
        fun lastRecordPath(): File? = lastRecordPath
        fun lastRecordStopReason(): String? = lastRecordStopReason
    }

    /** 主线程 Handler：录包回调（Toast / 通知刷新）里要触 UI，须切主线程（回调来自写线程/命令线程）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val locator = ObservedEndpointCollector()

    // —— 切帧（单一 worker 汇聚，M2）——
    // confirmer：切出 S→C 抽卡特征帧即把该 server 端点风险提升为「已确认」，供正式消费
    // 记录消费者：双向分流 → DedupPipeline（视图追踪 / B8 过滤 / 实时位置判重）
    private val recordConsumer = RecordFrameConsumer(
        onRecords = { appendRecords(it) },
        onActivity = { refreshDedupState() },
    )
    private val frameConsumer: FrameConsumer = recordConsumer

    /** 历史落库（会话开始时加载；抓取中由 [checkpointPersist] 周期落盘兜底非干净退出，收尾由 [finalizeSession] merge；未开抓时为 null）。 */
    private var historyStore: HistoryStore? = null

    /** 本场抓包使用的账号 id（取自 [ProfileStore] 的 active；收尾写 last_capture 用）。 */
    @Volatile
    private var sessionProfileId: String = ""
    private val dumper: DiagnoseDumper by lazy { DiagnoseDumper(applicationContext) }
    /** 已确认打点去重（07 设计 L5：每端点仅提示一次）。 */
    private val confirmedSeen = ConcurrentHashMap.newKeySet<String>()
    private val reassembly = ReassemblyWorker(
        splitter = FrameSplitter,
        consumer = frameConsumer,
        confirmer = { serverIp, serverPort ->
            locator.confirm(Endpoint(serverIp, serverPort))
            refreshEndpoints() // 设置页「已知端点」只读展示随确认即时刷新
            // 07 设计 L5：端点被抽卡特征确认后追加一行
            if (confirmedSeen.add("$serverIp:$serverPort")) {
                CaptureLog.i(TAG, "已确认为抽卡端点：$serverIp:$serverPort")
            }
        },
        // 切分统计喂给诊断判据（B1 命中恒 0 / B2 高位清空）
        statsListener = { dumper.onReassemblyStats(it) },
    )

    // —— 转发（方案 A）——
    private var matcher: FlowMatcher? = null
    private var connectionManager: ConnectionManager? = null

    private var pfd: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null

    @Volatile
    private var running = false

    /** L2 首包打点：每次会话只打一条。 */
    @Volatile
    private var firstPacketLogged = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (running) return

        // 前台服务安全：startForegroundService() 后必须 5s 内 startForeground()（实机曾首现崩溃）。
        startForeground(NOTIFICATION_ID, buildNotification())

        val gameUid = resolveGameUid()
        if (gameUid == null) {
            Log.w(TAG, "目标游戏未安装: $TARGET_PACKAGE")
            stopSelf()
            return
        }

        val builder = Builder().apply {
            setSession(getString(R.string.vpn_session_name))
            setMetered(false)
            addAddress("10.224.0.1", 24)
            addRoute("0.0.0.0", 0)   // 全部出站导入 tun，由本服务承运
            // 白名单：只接管目标游戏，其余 App 走系统原路（降感）
            addAllowedApplication(TARGET_PACKAGE)
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "VPN 通道建立失败", e)
            stopSelf()
            return
        }
        if (fd == null) {
            Log.e(TAG, "VPN 通道建立失败: establish() 返回空")
            stopSelf()
            return
        }

        pfd = fd
        running = true
        runningNow = true
        _state.value = _state.value.copy(running = true)
        resetCapturedRecords() // 记录页展示「本次抓到的抽」：每次开抓从空开始

        // —— 去重会话：加载历史（Δ 基准）→ 建会话；落库见 cleanup() → finalizeSession() ——
        // 账号取自 ProfileStore（profiles.json 的 active_id，与 PC 布局一致；设置页可切换）。
        // 每账号一个历史文件 users/<id>.json，故切换账号即切换历史与 Δ 基准。
        val profileId = ProfileStore.get(applicationContext).active
        sessionProfileId = profileId
        val store = HistoryStore(File(filesDir, USERS_DIR), profileId)
        historyStore = store
        recordConsumer.beginSession(store.records, store.lastTotal)
        _dedupState.value = DedupSnapshot()
        CaptureLog.i(
            TAG,
            "去重会话开始：历史 ${store.records.size} 条，Δ 基准 total=" +
                "${store.lastTotal?.toString() ?: "无（首次抓取）"}（账号 $profileId）",
        )

        // 建转发层：tun 写统一加锁（多线程写回 tun：主线程 ACK/RST + 各连接读线程数据回包）
        val writeStream = FileOutputStream(fd.fileDescriptor)
        val tunWrite: (ByteArray) -> Unit = { bytes ->
            dumper.recordOutgoing(bytes, System.currentTimeMillis()) // 记 S->C，使完整录包含双向
            synchronized(writeStream) { writeStream.write(bytes) }
        }
        val flowMatcher = FlowMatcher(locator)
        matcher = flowMatcher
        connectionManager = ConnectionManager(
            matcher = flowMatcher,
            reassembly = reassembly,
            tunWrite = tunWrite,
            protectTcp = { sock -> this.protect(sock) },
            protectUdp = { sock -> this.protect(sock) },
        )

        // 07 设计 L1：隧道建立成功一行（App 内 + logcat）
        CaptureLog.i(
            TAG,
            "VPN 会话已建立（uid=$gameUid，MTU=$MTU，仅接管 $TARGET_GAME_NAME）候选中=${locator.candidates().size}",
        )
        refreshEndpoints() // 设置页「已知端点」快照（同实例复用时同步既有确认）
        dumper.start() // 诊断判别器重启（重置计时/计数；自动判据默认仅提示）

        // 07 设计 3.1：注册录包状态回调 → 落 companion + Toast + 刷通知按钮文案
        dumper.onRecordStateChanged = { recording, file, reason ->
            recordingNow = recording
            lastRecordPath = file
            lastRecordStopReason = reason
            _state.value = _state.value.copy(recording = recording)
            if (recording) {
                mainHandler.post {
                    // 用 applicationContext：回调可能在 stopSelf 后延迟执行，此时 Service 已失效
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.record_started, file?.name ?: ""),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else if (reason != null) {
                CaptureLog.w(TAG, "全量录包自动停止：$reason")
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.record_auto_stopped, reason),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
                mainHandler.post {
                    Toast.makeText(
                        applicationContext, getString(R.string.record_stopped), Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            mainHandler.post { startForeground(NOTIFICATION_ID, buildNotification()) }
        }

        firstPacketLogged = false
        readerThread = Thread({ readLoop(fd) }, "vpn-reader").also { it.start() }

        // 07 设计 L7：每秒级汇总（daemon，running=false 退出）
        Thread({
            while (running) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                checkpointPersist() // 抓取过程周期落盘：进程被划掉/强杀（onDestroy 不保证）也不丢已收历史
                val st = dumper.lastStats()
                val mgr = connectionManager
                CaptureLog.i(
                    TAG,
                    "5s 汇总：包 ${dumper.packetCount()} / 帧 ${st.serverFrames} / 命中 ${st.gachaHits} / " +
                        "活跃连接 ${mgr?.flowCount() ?: 0} / 丢包 ${st.droppedInputs}",
                )
            }
        }, "observe-summary").also { it.isDaemon = true; it.start() }
    }

    private fun resolveGameUid(): Int? = runCatching {
        packageManager.getApplicationInfo(TARGET_PACKAGE, PackageManager.GET_META_DATA).uid
    }.getOrNull()

    private fun readLoop(fd: ParcelFileDescriptor) {
        val readStream = FileInputStream(fd.fileDescriptor)
        val manager = connectionManager ?: return
        val buf = ByteArray(MTU)

        while (running) {
            val n = try {
                val got = readStream.read(buf)
                if (got < 0) break
                got
            } catch (e: IOException) {
                Log.i(TAG, "读 tun 退出")
                break
            }
            if (n <= 0) continue

            val pkt = IPPacket.parse(buf, n) ?: continue
            // 07 设计 L2：每会话首个包提示一次「已接管流量」
            if (!firstPacketLogged) {
                firstPacketLogged = true
                val proto = if (pkt.isTcp) "TCP" else if (pkt.isUdp) "UDP" else "IP"
                CaptureLog.i(
                    TAG,
                    "已接管流量：首包 ${pkt.srcIp}:${pkt.srcPort} → ${pkt.dstIp}:${pkt.dstPort} ($proto, ${n}B)",
                )
            }
            dumper.observe(pkt, System.currentTimeMillis()) // 诊断旁路：环形缓冲/计数
            manager.ingress(pkt)
        }
        cleanup()
    }

    private fun cleanup() {
        running = false
        runningNow = false
        try {
            connectionManager?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "转发层关闭异常", e)
        }
        reassembly.shutdown()
        // 收尾落库：worker 已停（且 consumer 方法与之互斥）→ 把本场去重后的新增并入历史
        finalizeSession()
        dumper.stop() // 停止录制（写线程检测后自行关文件）
        // 复位 companion 录包状态（隧道已停，按钮应回到「未运行禁用」态）
        recordingNow = false
        lastRecordPath = null
        lastRecordStopReason = null
        _state.value = CaptureServiceState() // 全部复位（按钮回到「未运行禁用」态）
        try {
            pfd?.close()
        } catch (_: IOException) {
        }
        pfd = null
    }

    /** 去重状态变化 → 刷新 UI 快照（在 ReassemblyWorker 消费者线程调用）。 */
    private fun refreshDedupState() {
        _dedupState.value = recordConsumer.snapshot()
    }

    /**
     * 刷新 companion 的「已确认端点」快照（设置页抓包组只读展示）。
     * 调用点：会话启动、特征确认到新端点。按 Q3 不做持久化，进程重启后自然归零。
     */
    private fun refreshEndpoints() {
        _endpoints.value = locator.confirmedEndpoints().sortedBy { it.ip + ":" + it.port }
    }

    /**
     * 抓取过程**非破坏性**周期落盘 —— 兜底「进程被杀 / 划掉 App 未走干净停止」时本次会话不丢
     * （Android 不保证进程被杀时走 [onDestroy]，只有通知栏/UI 停止才一定触发 [finalizeSession]）。
     *
     * 区别于 [finalizeSession]：不 null 掉 [historyStore]，可反复调用；[HistoryStore.merge] 内容
     * 幂等（payload 与磁盘一致 → `writePayload` 不写盘），重复合并同一会话切片由
     * `PositionAlign.mergeByPosition` 位置判重（`added=0`），故无新增时不产生磁盘写。
     * 竞态安全：与 [finalizeSession] 同为 `@Synchronized` 串行（共享同一 `HistoryStore`），
     * 且 cleanup 先把 historyStore 置 null，本方法读到 null 即跳过，不与 finalize 重复写。
     */
    @Synchronized
    private fun checkpointPersist() {
        historyStore?.run {
            merge(recordConsumer.sessionRecords(), recordConsumer.viewTotals())
        }
    }

    /**
     * 会话收尾 —— 把本次去重后新增的记录按位置并入历史并落库（对齐 PC 的停抓/切号/还原/关窗
     * 四条落库路径里「停抓」这条），并输出缺口 / 不变量报警。
     *
     * 幂等且可重入：`historyStore` 先置 null 再干活，`cleanup()` 被重复调用时只有第一次生效。
     * 落库走 [HistoryStore.merge] → 内容幂等（payload 与磁盘一致则不写盘不备份）。
     *
     * 注：写盘在调用线程同步完成（PC 亦在 GUI 线程落库）。会话结束才写一次、payload 为
     * 纯文本 JSON（数千条 ≈ 数百 KB），量级可接受；若未来历史到十万级再改为异步 + 落盘队列。
     */
    @Synchronized
    private fun finalizeSession() {
        val store = historyStore ?: return
        historyStore = null

        val session = recordConsumer.sessionRecords()
        val added = store.merge(session, recordConsumer.viewTotals())
        val snap = recordConsumer.snapshot()

        // 缺口报告：B8 失去多视图冗余后的**唯一兜底**（告诉用户还缺哪几页，回翻即可补齐）
        for (gap in recordConsumer.missingPages()) {
            CaptureLog.w(
                TAG,
                "抓取缺口：视图 ${gap.view} 应有 ${gap.expectedCount} 页、实得 ${gap.seenCount} 页，" +
                    "缺 ${gap.missing.size} 页（offset 前 20 个：${gap.missing.take(20)}）",
            )
        }
        // 不变量报警：事件大小 / 事件内位置连续性（绝不静默）
        for (a in recordConsumer.suspiciousEvents()) {
            CaptureLog.w(TAG, "事件大小异常：ts=${a.ts} 池=${a.poolId} 共 ${a.size} 条（合法仅 1/10）")
        }
        for (a in recordConsumer.unresolvedEvents()) {
            CaptureLog.w(TAG, "事件未定序：ts=${a.ts} 池=${a.poolId} 共 ${a.size} 条（事件内位置不连续）")
        }
        if (session.isNotEmpty() && added == 0) {
            CaptureLog.i(TAG, "本场 ${session.size} 条全部与历史重叠（按位置判重跳过），历史未变")
        }
        CaptureLog.i(
            TAG,
            "去重收尾：本场收下 ${session.size} 条 → 落库新增 $added 条，历史共 ${store.records.size} 条" +
                "（翻到全部卡池=${snap.allPoolsSeen}，丢弃非全部卡池页 ${snap.droppedPages}，" +
                "被拒页 ${snap.rejectedPages}，与历史重叠=${snap.overlapped}）",
        )
        _dedupState.value = snap

        // 记录本账号最近一次抓包完成时间（settings.json 的 `last_capture`，与 PC 同名键）
        if (sessionProfileId.isNotEmpty()) {
            SettingsStore.get(applicationContext)
                .setLastCapture(sessionProfileId, LocalDateTime.now().format(CAPTURE_TS_FMT))
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "请求停止抓包")
        running = false
        readerThread?.interrupt()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 用户在系统设置里撤销 VPN 授权，或另一个 App 抢占 VPN 时由系统调用。
     *
     * 不重写它服务不会自停：隧道 fd 失效后读循环空转（read 立即返回 0 / 抛错），
     * 通知栏却还挂着「监听中」，用户以为在抓包、实际什么都没抓到，且无法从通知栏停掉。
     */
    override fun onRevoke() {
        Log.w(TAG, "VPN 授权被撤销，服务自停")
        running = false
        readerThread?.interrupt()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        cleanup()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.jinlin.gacha.assistant.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // 注：通知栏「录包 / 停录」与「抓诊断」两个写原始流量入口已移除（口述隐藏清单）。
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }
}