package com.jinlin.gacha.assistant.core

import com.jinlin.gacha.assistant.vpn.ParsedIpPacket
import java.io.ByteArrayOutputStream
import java.util.TreeMap

/**
 * 窄 TCP 重组器（对应规划 §3.3：per-flow 按 seq 有序缓冲，容忍乱序/重传/粘包半包）。
 *
 * 只服务「已确认抽卡端点」的单向连接流——每个包先按 (srcIp,srcPort,dstIp,dstPort) 四元组
 * 归流（方向天然随四元组确定，与 PC 用 4-tuple 键一致）。每流维护：
 * - [reorder]：按 TCP seq 排队的乱序/续段（**条数 + 字节双上限**，超限跳过缺口）；
 * - [tailBuf]：已拼成连续字节、但尚未切出完整帧的余留（可增长缓冲，避免 O(n²) 拷贝）；
 * - 重传（seq 已 ≤ expectedSeq）直接丢弃。
 *
 * 语义：M2 以「近线性序」为主（tun 大多按序到达），乱序小窗容忍；不追求全量乱序健壮，
 * 深度留阶段 2 前补。异常流以水位上限兜底，防止缓冲无界增长。
 */
class FrameAssembler(
    private val splitter: FrameSplitter = FrameSplitter,
    private val consumer: FrameConsumer? = null,
) {
    private val flows = HashMap<String, Flow>()

    /** 高位兜底清空次数（异常流：tail 长期拼不出帧涨破水位被丢弃）。诊断判别 B2 用。 */
    @Volatile
    var clearedTailCount: Long = 0
        private set

    /** 乱序缓冲越上限、被迫跳过 seq 缺口的次数（诊断用；正常流应恒为 0）。 */
    @Volatile
    var skippedGapCount: Long = 0
        private set

    /** 空闲流被回收的累计数量（诊断用）。 */
    @Volatile
    var sweptFlowCount: Long = 0
        private set

    /** 处理一个目标连接方向的 TCP 段，产出该段拼齐后切出的完整帧（可能为空）。 */
    fun onTcpSegment(pkt: ParsedIpPacket, direction: Direction): List<Frame> {
        if (!pkt.isTcp) return emptyList()
        val key = flowKey(pkt)
        val flow = flows[key] ?: Flow(
            direction,
            splitter,
            onClearedTail = { clearedTailCount++ },
            onSkippedGap = { skippedGapCount++ },
        ).also { flows[key] = it }
        val frames = flow.push(pkt.tcpSeq, pkt.tcpPayload)
        consumer?.let {
            for (f in frames) it.onFrame(f, direction)
        }
        return frames
    }

    fun flowCount(): Int = flows.size

    /**
     * 回收空闲超过 [idleMs] 的流（规划 §3.3 要求 60s 空闲回收）。
     *
     * 缺了这一步 [flows] 只增不减：每条抽卡连接断开后其 Flow 会永久驻留，
     * 长时间挂机抓包下内存单调增长。由 `ReassemblyWorker` 周期调用。
     *
     * @return 本次回收的流数量
     */
    fun sweep(
        idleMs: Long = FLOW_IDLE_TIMEOUT_MS,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (flows.isEmpty()) return 0
        var removed = 0
        val it = flows.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastActiveAt > idleMs) {
                it.remove()
                removed++
            }
        }
        if (removed > 0) sweptFlowCount += removed
        return removed
    }

    private fun flowKey(pkt: ParsedIpPacket): String =
        "${pkt.srcIp}:${pkt.srcPort}->${pkt.dstIp}:${pkt.dstPort}"

    companion object {
        /** 规划 §3.3：空闲 60s 回收流。 */
        const val FLOW_IDLE_TIMEOUT_MS = 60_000L
    }

    private class Flow(
        private val direction: Direction,
        private val splitter: FrameSplitter,
        private val onClearedTail: () -> Unit,
        private val onSkippedGap: () -> Unit,
    ) {
        private var expectedSeq = -1L
        private val reorder: TreeMap<Long, ByteArray> = TreeMap()
        private var reorderBytes = 0

        /**
         * 已连续但未成帧的余留。**必须**用可增长缓冲：原先写 `tail = tail + seg`，
         * 每次收段都把整个 tail 复制一遍，半包长期不成帧时是 O(n²)。
         */
        private val tailBuf = ByteArrayOutputStream(8192)

        /** 最近一次活跃时间，供 [FrameAssembler.sweep] 判定空闲回收。 */
        var lastActiveAt: Long = System.currentTimeMillis()
            private set

        private val SPLIT_CAP = 1 shl 20 // 1MB 未切帧余留兜底
        private val REORDER_MAX_SEGS = 256 // 乱序段条数上限
        private val REORDER_MAX_BYTES = 4 shl 20 // 乱序段字节上限 4MB

        fun push(seq: Long, data: ByteArray): List<Frame> {
            lastActiveAt = System.currentTimeMillis()
            if (data.isEmpty()) return emptyList()
            if (expectedSeq < 0) expectedSeq = seq
            if (seq < expectedSeq) return emptyList() // 重传/乱序已覆盖，丢弃
            // 同 seq 重复到达：M2 简单去重（保留先到，重叠尾部才会有缺口，交给后续续段）
            if (reorder.containsKey(seq)) return emptyList()

            reorder[seq] = data
            reorderBytes += data.size

            // 乱序缓冲越上限 => 缺口长期不愈合（丢包且对端未重传）。放任会无界增长，
            // 故强制推进到最小可用 seq 跳过缺口：宁可丢一段字节（切帧层有重对齐兜底），
            // 也不能让缓冲单调上涨直至 OOM。
            if (reorder.size > REORDER_MAX_SEGS || reorderBytes > REORDER_MAX_BYTES) {
                val next = reorder.firstKey()
                if (next > expectedSeq) {
                    expectedSeq = next
                    onSkippedGap()
                }
            }
            return drain()
        }

        /** 把从 expectedSeq 起连续的段拼进 tailBuf 并切帧。 */
        private fun drain(): List<Frame> {
            while (true) {
                val seg = reorder.remove(expectedSeq) ?: break
                reorderBytes -= seg.size
                tailBuf.write(seg, 0, seg.size)
                expectedSeq += seg.size
                // 清理越过 expectedSeq 的残留 key（重叠异常；M2 忽略）
                val stale = reorder.headMap(expectedSeq)
                for (s in stale.values) reorderBytes -= s.size
                stale.clear()
                if (tailBuf.size() > SPLIT_CAP) {
                    tailBuf.reset() // 异常流兜底：清空并丢弃，不崩
                    reorder.clear()
                    reorderBytes = 0
                    onClearedTail()
                    break
                }
            }
            if (tailBuf.size() == 0) return emptyList()
            val (frames, remaining) = splitter.extract(tailBuf.toByteArray(), direction)
            tailBuf.reset()
            tailBuf.write(remaining, 0, remaining.size)
            return frames
        }
    }
}
