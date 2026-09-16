package com.jinlin.gacha.assistant.core

import android.util.Log
import com.jinlin.gacha.assistant.vpn.IPPacket
import com.jinlin.gacha.assistant.vpn.ParsedIpPacket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 切帧汇聚 worker（单消费者线程，对应设计稿 §3.7 / 规划 §4「1 个 worker 消费重组+切帧」）。
 *
 * 方案 A 转发的抽卡载荷来自两个线程（tun 主线程的 C→S + 各连接 socket 读线程的 S→C），
 * 而现有 [FrameAssembler] 非线程安全。本 worker 把两种输入收敛到一根消费队列、单线程
 * 顺序喂给 [FrameAssembler]，彻底消除竞态。
 *
 * 投递单元 [FrameInput] 用 4-tuple 取向（FrameAssembler 同键），保证重收/切帧一致。
 */
class ReassemblyWorker(
    private val splitter: FrameSplitter = FrameSplitter,
    private val consumer: FrameConsumer? = null,
    /** M2 特征锁定回呼：切出 S→C 完整帧且命中抽卡特征时，以 (serverIp, serverPort) 通知确认。 */
    private val confirmer: ((serverIp: String, serverPort: Int) -> Unit)? = null,
    private val recognizer: GachaRecognizer = GachaRecognizer,
    /** 诊断统计上报（B1 命中恒 0 / B2 高位清空等判据消费，见 DiagnoseDumper）。 */
    private val statsListener: ((DiagnoseDumper.Stats) -> Unit)? = null,
) {
    /** 单帧输入：方向 + 载荷 + （由转发层给的）seq + 四元组。 */
    data class FrameInput(
        val direction: Direction,
        val seq: Long,
        val payload: ByteArray,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
    )

    private val queue = LinkedBlockingQueue<FrameInput>(512)
    private val assembler = FrameAssembler(splitter, consumer)
    private val thread: Thread

    /** —— 诊断统计（喂给 statsListener）—— */
    private var serverFrames = 0L
    private var gachaHits = 0L

    /** 队列满被丢弃的输入数（原先 offer 失败直接静默丢弃，出过载没有任何线索）。 */
    @Volatile
    private var droppedInputs = 0L

    /** feed 抛异常次数（原先是空 catch，切帧被脏数据打挂时线上毫无线索）。 */
    private var feedErrors = 0L

    /** 是否仍接收新输入。[shutdown] 置 false 后进入「排空 + 退出」。 */
    @Volatile
    private var accepting = true

    /** [shutdown] 是否已执行（幂等守卫，重复调用直接返回）。 */
    @Volatile
    private var stopped = false

    companion object {
        private const val TAG = "GachaReasm"
        /**
         * 空闲流回收的巡检周期。用 [LinkedBlockingQueue.poll] 的超时顺带巡检，
         * 因此即使完全没有输入也能定期跑 [FrameAssembler.sweep]；
         * 否则断连的 Flow 会永久驻留，长时间挂机内存单调增长。
         */
        private const val SWEEP_INTERVAL_MS = 5_000L

        /**
         * 排空哨兵：由 [shutdown] 投到队尾，worker 遇到它即知「其前的输入都已消费」→ 退出。
         * 以引用相等（`===`）识别，与真实输入（每帧新构造）不会混淆。
         */
        private val POISON = FrameInput(Direction.SERVER_TO_CLIENT, -1L, ByteArray(0), "", 0, "", 0)

        /** [shutdown] 等待排空的最长时间；超时（异常积压）则放弃排空并强制停线程。 */
        private const val DRAIN_TIMEOUT_MS = 1_000L
    }

    init {
        thread = Thread(::runLoop, "reassembly-worker").also { it.isDaemon = true; it.start() }
    }

    /** 投递一帧输入；队列满则丢弃并计数（异常连接/过载，不阻塞转发主路径）。
     * [shutdown] 之后一律拒绝（避免哨兵之后又混进真实输入，破坏排空边界）。 */
    fun receive(
        direction: Direction,
        seq: Long,
        payload: ByteArray,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
    ) {
        if (!accepting) return
        val accepted = queue.offer(FrameInput(direction, seq, payload, srcIp, srcPort, dstIp, dstPort))
        if (!accepted) {
            droppedInputs++
            // 首条 + 每百条打一次，避免过载时把 logcat 刷爆
            if (droppedInputs == 1L || droppedInputs % 100L == 0L) {
                Log.w(TAG, "汇聚队列已满，丢弃输入（累计 $droppedInputs 条）")
            }
        }
    }

    private fun runLoop() {
        var lastSweep = System.currentTimeMillis()
        while (true) {
            val input = try {
                queue.poll(SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
            if (input === POISON) break // 队尾哨兵：其前的输入已全部消费
            if (input != null) feed(input)
            val now = System.currentTimeMillis()
            if (now - lastSweep >= SWEEP_INTERVAL_MS) {
                lastSweep = now
                assembler.sweep(now = now)
            }
        }
    }

    private fun feed(input: FrameInput) {
        val pkt = ParsedIpPacket(
            protocol = IPPacket.PROTO_TCP,
            srcIp = input.srcIp,
            dstIp = input.dstIp,
            srcPort = input.srcPort,
            dstPort = input.dstPort,
            raw = ByteArray(0),
            length = 0,
            isTcp = true,
            isUdp = false,
            tcpSeq = input.seq,
            tcpPayload = input.payload,
        )
        try {
            val frames = assembler.onTcpSegment(pkt, input.direction)
            // M2 特征锁定 + 命中统计：S→C 完整帧跑判定，命中即确认端点并计数
            if (input.direction == Direction.SERVER_TO_CLIENT) {
                serverFrames += frames.size
                for (f in frames) {
                    if (f.body.isNotEmpty() && recognizer.looksLikeGacha(f.body)) {
                        gachaHits++
                        val confirm = confirmer
                        if (confirm != null) {
                            confirm(input.srcIp, input.srcPort)
                            break // 一次确认足够，端点不可逆提升
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 异常流不该拖垮 worker，但必须留痕：此前这里是个空 catch，
            // 一旦切帧被脏数据打挂（如长度头异常），线上完全没有线索可查。
            feedErrors++
            Log.w(
                TAG,
                "feed 异常（第 $feedErrors 次）dir=${input.direction} seq=${input.seq} len=${input.payload.size}",
                e,
            )
        }
        // 每次投递都上报实时快照（判据端自行判断是否越阈值）
        statsListener?.invoke(
            DiagnoseDumper.Stats(
                serverFrames = serverFrames,
                gachaHits = gachaHits,
                clearedTails = assembler.clearedTailCount,
                droppedInputs = droppedInputs,
                feedErrors = feedErrors,
                skippedGaps = assembler.skippedGapCount,
                sweptFlows = assembler.sweptFlowCount,
            )
        )
    }

    /**
     * 服务停止时调用：**先排空**队列中已到达的输入，再落停 worker（幂等）。
     *
     * 原先直接 `queue.clear()`：若停止那一刻「最后一页响应已入队、但 worker 尚未消费」，
     * 那几帧会被**静默丢弃** ⇒ 收尾落库少算（真实场景：用户翻完最后一页立刻点停止）。
     * 现改为投一枚**队尾哨兵**（同时唤醒正阻塞在 poll 上的 worker，不必等满
     * [SWEEP_INTERVAL_MS]），worker 把哨兵之前的输入消费完即退出；调用方随后再做收尾落库，
     * 拿到的是全量。`accepting = false` 先于投哨兵，保证哨兵之后不会再有真实输入。
     *
     * 队列满（积压 ≥ 512）时哨兵投不进去 → 放弃排空并强杀，避免拖住服务收尾。
     */
    @Synchronized
    fun shutdown() {
        if (stopped) return
        stopped = true
        accepting = false

        if (!queue.offer(POISON)) {
            Log.w(TAG, "停止时汇聚队列已满，放弃排空（丢弃 ${queue.size} 条）")
            queue.clear()
            thread.interrupt()
            return
        }
        try {
            thread.join(DRAIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            Log.w(TAG, "排空超时（${DRAIN_TIMEOUT_MS}ms），强制停止 worker")
            queue.clear()
            thread.interrupt()
        }
    }
}