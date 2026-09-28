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
import com.jinlin.gacha.assistant.core.health
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** 进程级抓包运行态（供 Compose 顶栏订阅；替代阶段 1 的 Handler 800ms 轮询）。 */
data class CaptureServiceState(
    val running: Boolean = false,
    val recording: Boolean = false,
    /**
     * 本场抓包的**开始时刻**（epoch millis，U5 2026-09-18）；`0` = 未在抓包。
     *
     * 刻意**只在内存**（不落盘）：进程被杀后本场归零、记录页显示「本次　未在抓包」，语义正确；
     * 而「上一场」的起止由历史文件里的 `last_capture_detail` 承载。
     * 用 epoch millis 而非格式化串：它是**时刻**，排版（跨天补月-日）交给 UI 层。
     */
    val sessionStartedAt: Long = 0L,

    /**
     * **本场会话属于哪个账号**（`users/<id>.json` 的 `<id>`；`""` = 本场不存在。
     *
     * ### 为什么必须带这个字段（2026-09-19 实机反馈修的缺陷）
     * 会话内存态（[records][GachaVpnService.records] / [dedupState][GachaVpnService.dedupState]
     * / `sessionStartedAt`）是**进程级单例**，语义上却属于**某一个账号**。原先它没有任何归属标记，
     * 只在「开始抓包」时清空 ⇒ **切换账号后、还没抓包时**，新账号会在记录页看到：
     * 上一个账号的记录（新账号历史为空 ⇒ `historyKeys` 为空 ⇒ 旧会话记录整批漏进并集），
     * 以及上一个账号的完整性信息（`dedup` 快照）。用户实测反馈的原话。
     *
     * ### 为什么用「归属标记 + 消费侧过滤」而不是「切账号时调一次清空」
     * 改 `activeId` 的入口不止一处（切换 / 删除活动账号后重指 / 新建后切换 / 进程冷启）——
     * 逐个入口补 `resetCapturedRecords()` 属于「漏一处就复发」（同日记录页页覆盖口径就栽在这上面）。
     * 把归属写进**数据**、由消费侧一处判定（[ownsSession]），任何改 `activeId` 的路径都自动覆盖。
     *
     * 收尾复位走 `_state.value = CaptureServiceState()`（全量复位）⇒ 本字段自然回到 `""`，
     * 即「本场结束、会话数据不再对外可见」；而它此后仍留在内存里的那批记录会随下次
     * 「开始抓包」清掉，不影响落库（停抓时已 `merge` 进所属账号的历史）。
     */
    val sessionProfileId: String = "",
)

/**
 * 本场会话内存态（records / dedup / 开始时刻）**是否属于 [profileId]** —— 消费侧的唯一判据。
 *
 * `profileId` 为空（无当前账号）时一律为 `false`：宁可让界面显示「本场没抓到数据」，
 * 也不能把别的账号的数据挂到当前账号名下。
 */
fun CaptureServiceState.ownsSession(profileId: String): Boolean =
    profileId.isNotEmpty() && sessionProfileId == profileId

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

        // 目标游戏包名 —— 2026-09-18 起**不再写死在此**。
        // 官服（com.bmystu.peng.gw）与渠道服（如 OPPO com.bmystu.peng1.nearme.gamecenter）
        // 包名不同，写死一个 ⇒ 另一半玩家一律被判「游戏未安装」。
        // 现由 TargetPackages.resolve 解析：用户勾选 > 自动检出 > 官服兜底。

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

    /**
     * 本场接管的渠道包名（渠道服支持，2026-09-18）。
     *
     * 用途：收尾落库时写进 `users/<id>.json` 的 `source_packages`（账号级渠道标记），
     * 供「换渠道但没换账号」的提醒判断使用。由 [startVpn] 在解析成功后赋值。
     */
    private var activeTargetPackage: String? = null

    /**
     * 本场开抓时该账号的历史条数（2026-09-19 新增）。
     *
     * 用途：收尾时把场次明细的 `added` 算成**本场净增** = `store.records.size - 本值`。
     * ⚠️ 不能直接用 `merge` 的返回值 —— 每 5 秒的 [checkpointPersist] 已把本场记录并进历史，
     * 收尾那次 merge 会把它们全部判重跳过 ⇒ 返回值恒为 0 ⇒ 记录页「上次」显示 0 条。
     */
    private var sessionBaselineRecordCount: Int = 0
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

        // 目标包名解析（渠道服支持，2026-09-18）：用户选定 > 自动检出（仅 1 个）> 官服兜底。
        // **单选**：Ready 至多一个包名；NeedsChoice = 检出多个而用户没选，服务侧无法弹窗 ⇒
        // 拒绝启动并记日志，由抓包页（UI）请用户先选（不替他决定，见 TargetPackages.decide）。
        // 注意：addAllowedApplication 对未安装的包会抛 NameNotFoundException，
        // 故 resolve() 只回已安装项 —— 不要在这里自行拼包名。
        val target = when (val r = TargetPackages.resolve(applicationContext)) {
            is TargetPackages.Resolution.Ready -> r.packageName
            is TargetPackages.Resolution.NeedsChoice -> {
                // 走 CaptureLog 而非裸 Log.w（U11，2026-09-18）：与 TargetPackages 的打点同一条链路，
                // 才能回答用户点名的「为什么提示未获取到应用」。
                CaptureLog.w(
                    TAG,
                    "检出多个可接管渠道但用户未选定（${r.candidates.size} 个）⇒ 拒绝启动，" +
                        "请先在设置页「接管范围」里选一个",
                )
                stopSelf()
                return
            }
            TargetPackages.Resolution.NotInstalled -> {
                CaptureLog.w(TAG, "目标游戏未安装：未检出任何 ${TargetPackages.ROOT} 系列应用")
                stopSelf()
                return
            }
        }

        val gameUid = resolveGameUid(listOf(target))
        if (gameUid == null) {
            CaptureLog.w(TAG, "目标应用已检出但解不出 uid：$target")
            stopSelf()
            return
        }
        // 记下本场渠道：收尾落库时写进账号级 `source_packages`（换渠道提醒的依据）
        activeTargetPackage = target

        val builder = Builder().apply {
            setSession(getString(R.string.vpn_session_name))
            setMetered(false)
            addAddress("10.224.0.1", 24)
            addRoute("0.0.0.0", 0)   // 全部出站导入 tun，由本服务承运
            // 白名单：**只接管选定的那一个目标应用**（官服或用户选定的渠道服），其余 App 走系统原路（降感）。
            // 单选是刻意的：两个渠道同时进 tun 会让两套抽卡请求撞进同一张视图识别表（详见 TargetPackages）。
            addAllowedApplication(target)
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            CaptureLog.e(TAG, "VPN 通道建立失败", e)
            stopSelf()
            return
        }
        if (fd == null) {
            CaptureLog.e(TAG, "VPN 通道建立失败: establish() 返回空")
            stopSelf()
            return
        }

        pfd = fd
        running = true
        runningNow = true
        // 本场开始时刻（U5）：只存内存，供记录页「本次」行与停抓时的场次明细使用。
        _state.value = _state.value.copy(running = true, sessionStartedAt = System.currentTimeMillis())
        resetCapturedRecords() // 记录页展示「本次抓到的抽」：每次开抓从空开始

        // —— 去重会话：加载历史（Δ 基准）→ 建会话；落库见 cleanup() → finalizeSession() ——
        // 账号取自 ProfileStore（profiles.json 的 active_id，与 PC 布局一致；设置页可切换）。
        // 每账号一个历史文件 users/<id>.json，故切换账号即切换历史与 Δ 基准。
        val profileId = ProfileStore.get(applicationContext).active
        sessionProfileId = profileId
        // 会话归属写进进程级状态：消费侧（记录页 / 抓包页）据此判断「这批记录是不是当前账号的」。
        // 缺了它，切换账号后新账号会看到上一个账号的记录与完整性信息（2026-09-19 实机反馈）。
        _state.value = _state.value.copy(sessionProfileId = profileId)
        val store = HistoryStore(File(filesDir, USERS_DIR), profileId)
        historyStore = store
        recordConsumer.beginSession(store.records, store.lastTotal)
        // 本场基线：收尾时「上次 N 条」= 收尾后总条数 − 本值（checkpoint 已提前并入，见字段说明）
        sessionBaselineRecordCount = store.records.size
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
            "VPN 会话已建立（uid=$gameUid，MTU=$MTU，仅接管 $TARGET_GAME_NAME（$target））" +
                "候选中=${locator.candidates().size}",
        )
        refreshEndpoints() // 设置页「已知端点」快照（同实例复用时同步既有确认）
        // U2：异常自动落盘开关 —— **开抓时读一次**（`09` §5 G2「下次生效」）。
        // 运行中不再重读，故本次会话沿用开抓那一刻的设置；抓包中改开关只影响下一场。
        dumper.autoTriggerEnabled = SettingsStore.get(applicationContext).autoDiagnose
        dumper.start() // 诊断判别器重启（重置计时/计数；自动判据按 autoTriggerEnabled 决定是否落盘）

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

        // —— U11：注册诊断报告（.txt）的上下文来源（10 号稿 16.4 节）——
        // **触发时求值** ⇒ 报告里是点「抓诊断」那一刻的会话态，不是开抓那一刻的。
        // 数据源一律取**已发布的 StateFlow**（_state / _dedupState / _endpoints），
        // 不跨线程直读去重流水线 —— 与「会话态归属写进数据、消费侧一处判定」同一约定。
        dumper.reportSource = DiagnoseDumper.ReportSource {
            val st = _state.value
            val dd = _dedupState.value
            val h = dd.health
            // 两块先各算成字符串：避免把 if / 链式调用直接摆进 `+` 的操作数位（可读性 + 少一处解析歧义）
            val startedText = if (st.sessionStartedAt > 0L) {
                Instant.ofEpochMilli(st.sessionStartedAt).atZone(ZoneId.systemDefault())
                    .toLocalDateTime().format(HistoryStore.CAPTURE_DETAIL_FMT)
            } else {
                "未知（本次未在抓包，或进程重启后状态已复位）"
            }
            val endpointsText = _endpoints.value.joinToString("、") { it.ip + ":" + it.port }
                .ifEmpty { "（尚无）" }
            listOf(
                "会话" to listOf(
                    "账号: " + sessionProfileId.ifEmpty { "未知" },
                    "接管包名: " + target,
                    "会话开始: " + startedText,
                    "已确认抽卡端点: " + endpointsText,
                    "全量录包中: " + dumper.isFullRecording(),
                ).joinToString("\n"),
                "完整性" to listOf(
                    "健康判定: " + h.state + " / " + h.reason,
                    "全部卡池: " + (if (dd.allPoolsSeen) "已见" else "未见") +
                        "；丢弃非「全部卡池」页 " + dd.droppedPages + " 页",
                    "覆盖: 已拿 " + dd.gapSeenPages + "/" + dd.gapExpectedPages + " 页；服务器权威总抽数 " +
                        dd.authoritativeTotal + " 条；仍缺 " + dd.missingPageCount + " 页",
                    "缺口（A 首屏，继续翻拿不到）: " + dd.headGapPages,
                    "缺口（B 中段空洞，翻回去可补）: " + dd.midGapPages,
                    "缺口（C 尾部未翻）: " + dd.tailGapPages,
                    "（以上为 offset，落盘口径；UI 层才换算成「第 N 页」并补注「第 1 页 = 最新那一页」）",
                    "本场: 收下 " + dd.newCount + " 条 / 解析 " + dd.parsedCount + " 条（含判重跳过的旧记录）",
                    "被限流拒页: " + dd.rejectedPages + "；已见 offset 区间: " +
                        dd.minSeenOffset + " ~ " + dd.maxSeenOffset,
                    "与历史重叠: " + dd.overlapped + "（会话开始前有历史: " + dd.hadHistory + "）",
                ).joinToString("\n"),
                "合并" to listOf(
                    "本次未接入 —— MergeReport 只在收尾的 finalizeSession() 里产出，",
                    "点「抓诊断」的时刻还取不到；HistoryStore.lastReport 不落盘、恒为 null（10 号稿已登记）。",
                    "收尾时的合并结果，请见本报告日志段的「去重收尾：…」一行。",
                ).joinToString("\n"),
            )
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

    /**
     * 解析目标游戏的 uid（L1 日志用），取**第一个**解得出的目标包。
     *
     * 多包接管（官服 + 渠道服同装）时各包 uid 不同，但**本值只用于日志展示**：
     * tun 的按包过滤已由 `addAllowedApplication` 在建立通道时完成，运行期不再按 uid 筛包。
     */
    @Suppress("DEPRECATION")
    private fun resolveGameUid(packages: List<String>): Int? = packages.firstNotNullOfOrNull { pkg ->
        runCatching {
            packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA).uid
        }.getOrNull()
    }

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
                CaptureLog.i(TAG, "读 tun 退出")
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
            CaptureLog.w(TAG, "转发层关闭异常", e)
        }
        reassembly.shutdown()
        // 收尾落库：worker 已停（且 consumer 方法与之互斥）→ 把本场去重后的新增并入历史
        //
        // ⚠️ **这个顺序是承重的，不要调换**（2026-09-18 加注释钉住，U5）：
        //   · 必须早于下面那句 `_state.value = CaptureServiceState()` —— 场次明细要读
        //     `_state.value.sessionStartedAt`，状态复位后再读就只剩 0，本场明细直接丢失；
        //   · 也必须早于其余复位 —— 「先落库、后复位状态」是统计页能在停止那一刻读到
        //     新数据的原因（10 号稿发现 1）。
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
     *
     * ⚠️ **本路径刻意传 `keepRestorePoint = false`（2026-09-17 修复）**：它的职责只是「进程被杀
     * 不丢数据」，靠写 `default.json` 本身达成，**不需要还原点**。原先走默认 `true`（写盘即备份）
     * 时，备份环以「每 5 秒一份」的速率被刷（当时上限仅 5 份）—— 用户主动产生的还原点
     * （清空前 / 还原前 / 停抓收尾）**约 25 秒**就被挤出并删除，「还原到出问题之前」形同虚设。
     * 缺陷分析与修复见 `09-设置模块设计.md` §15。传 `false` 只影响「是否留副本」，**历史照写**，
     * 兜底能力不受影响。
     */
    @Synchronized
    private fun checkpointPersist() {
        historyStore?.run {
            merge(
                recordConsumer.sessionRecords(),
                recordConsumer.viewTotals(),
                keepRestorePoint = false,
            )
        }
    }

    /**
     * 会话收尾 —— 把本次去重后新增的记录按位置并入历史并落库（对齐 PC 的停抓/切号/还原/关窗
     * 四条落库路径里「停抓」这条），并输出缺口 / 不变量报警。
     *
     * 幂等且可重入：`historyStore` 先置 null 再干活，`cleanup()` 被重复调用时只有第一次生效。
     * 落库走 [HistoryStore.merge] → 内容幂等（payload 与磁盘一致则不写盘不备份）。
     *
     * 本路径**保留还原点**（走 [HistoryStore.merge] 的默认 `keepRestorePoint = true`）——
     * 「停抓」是用户可感知的状态点，与每 5 秒一次的 [checkpointPersist]（刻意传 `false`）
     * 语义不同，见 `09-设置模块设计.md` §15。
     *
     * 注：写盘在调用线程同步完成（PC 亦在 GUI 线程落库）。会话结束才写一次、payload 为
     * 纯文本 JSON（数千条 ≈ 数百 KB），量级可接受；若未来历史到十万级再改为异步 + 落盘队列。
     */
    @Synchronized
    private fun finalizeSession() {
        val store = historyStore ?: return
        historyStore = null

        val session = recordConsumer.sessionRecords()
        val gaps = recordConsumer.missingPages()

        // U5：把「本场缺口明细 + 场次明细」**随历史一起落盘**（同一文件，写入者仍是本类唯一）。
        // ⚠️ 只有这一条路径写这两个键 —— 周期 checkpointPersist 走默认 null（= 不改动），
        // 否则「上次抓包」会显示成正在进行中的这一场，结束时刻还会每 5 秒跳一次。
        val gapMap = LinkedHashMap<String, List<Int>>()
        for (g in gaps) gapMap[g.view] = g.missing

        val added = store.merge(
            session,
            recordConsumer.viewTotals(),
            scanGaps = gapMap,
            captureStartedAt = captureStartedText(),
            captureEndedAt = LocalDateTime.now().format(HistoryStore.CAPTURE_DETAIL_FMT),
            // 账号级渠道标记（渠道服支持）：本场接管的是哪个渠道包
            sourcePackage = activeTargetPackage,
            // 场次明细的「新增」按**本场净增**算，而不是这次 merge 的新增（checkpoint 已提前并入 ⇒ 恒 0）
            sessionBaselineRecords = sessionBaselineRecordCount,
        )
        // 本场真正落库的新增条数（= 收尾后总条数 − 会话起点）—— 日志与明细同口径
        val netAdded = (store.records.size - sessionBaselineRecordCount).coerceAtLeast(0)
        val snap = recordConsumer.snapshot()

        // 缺口报告：B8 失去多视图冗余后的**唯一兜底**（告诉用户还缺哪几页，回翻即可补齐）
        for (gap in gaps) {
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
        if (session.isNotEmpty() && netAdded == 0) {
            CaptureLog.i(TAG, "本场 ${session.size} 条全部与历史重叠（按位置判重跳过），历史未变")
        }
        CaptureLog.i(
            TAG,
            "去重收尾：本场收下 ${session.size} 条 → 本场落库新增 $netAdded 条（末次 merge 计 $added 条），" +
                "历史共 ${store.records.size} 条" +
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

    /**
     * 本场开抓时刻的 ISO 文本（U5）；无有效开始时刻（进程重启后本场归零等异常路径）返回 null
     * —— 此时 [HistoryStore.merge] 不会写场次明细，记录页「上次」保持旧值，
     * 而不是写出一条「09:40–09:40 · 0 条」式的半截记录。
     */
    private fun captureStartedText(): String? {
        val ms = _state.value.sessionStartedAt
        if (ms <= 0L) return null
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .toLocalDateTime().format(HistoryStore.CAPTURE_DETAIL_FMT)
    }

    private fun stopVpn() {
        CaptureLog.i(TAG, "请求停止抓包")
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
        CaptureLog.w(TAG, "VPN 授权被撤销，服务自停")
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