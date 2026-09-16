package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * ReassemblyWorker 停止语义测试（回归审计项 #R7：收尾清队丢帧）。
 *
 * 缺陷背景：旧 `shutdown()` 是 `running=false; queue.clear(); interrupt()`。若停止那一刻
 * 「最后一页响应已入队、但 worker 尚未消费」，队列里那几帧被**静默丢弃** ⇒ 收尾落库少算。
 * 真实触发场景：用户翻完最后一页立刻点停止。
 *
 * 现实现改为投一枚**队尾哨兵**：先 `accepting=false` 拒绝新输入，再投哨兵唤醒被 poll 阻塞的
 * worker，worker 消费到哨兵即退出；`shutdown()` 返回时队列已排空，随后的收尾落库拿到全量。
 *
 * 确定性构造：用**慢消费者**（每帧 sleep）把消费拖到远慢于投递，于是「投递完成时队列非空」
 * 成为必然（投递耗时微秒级 vs 消费 N×perFrame）。这样 t1 对**旧实现必然失败、新实现必然通过**，
 * 不是靠时序概率。
 *
 * 全纯 JVM（`Log` 由 `unitTests.isReturnDefaultValues=true` 置空），无需设备/模拟器。
 */
class ReassemblyWorkerTest {

    private companion object {
        const val SERVER_IP = "203.0.113.7"
        const val SERVER_PORT = 18085
        const val CLIENT_IP = "10.8.0.2"
        const val CLIENT_PORT = 44444
        val S2C = Direction.SERVER_TO_CLIENT

        /** 每帧消费耗时（ms）：把 worker 拖慢，保证投递结束时队列仍有积压。 */
        const val PER_FRAME_MS = 5L
    }

    /** S->C 最小合法帧体：GACHA 信封 8B 头 + 4B 业务体（同 FrameAssemblerTest 口径）。 */
    private fun s2cBody(filler: Int): ByteArray =
        byteArrayOf(0x00, 0x0C, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00) +
            ByteArray(4) { filler.toByte() }

    /** 帧 = 4B 大端长度头 + 帧体。 */
    private fun frameBytes(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = ((body.size ushr 24) and 0xff).toByte()
        out[1] = ((body.size ushr 16) and 0xff).toByte()
        out[2] = ((body.size ushr 8) and 0xff).toByte()
        out[3] = (body.size and 0xff).toByte()
        body.copyInto(out, 4)
        return out
    }

    /** 可调速消费者：`perFrameMs > 0` 时每帧阻塞，用于确定性制造消费落后于投递。 */
    private class SlowConsumer(private val perFrameMs: Long) : FrameConsumer {
        val frames = mutableListOf<Frame>()

        override fun onFrame(frame: Frame, direction: Direction) {
            if (perFrameMs > 0) Thread.sleep(perFrameMs)
            frames.add(frame)
        }
    }

    // ---- R7-1：停止时排空（核心）----

    @Test(timeout = 20_000)
    fun `t1 shutdown 排空队列 停止瞬间已到达的帧全部消费不丢`() {
        val consumer = SlowConsumer(PER_FRAME_MS)
        val worker = ReassemblyWorker(consumer = consumer)

        // 30 帧投递（微秒级）vs 30×5ms 消费 → 投递完成时队列必然仍有积压
        val n = 30
        var seq = 100L
        for (i in 0 until n) {
            val f = frameBytes(s2cBody(i and 0xff))
            worker.receive(S2C, seq, f, SERVER_IP, SERVER_PORT, CLIENT_IP, CLIENT_PORT)
            seq += f.size
        }
        // 立刻停止：旧实现在这里 queue.clear() ⇒ 队列中未消费的帧全部丢失
        worker.shutdown()

        assertEquals("停止后队列中已到达的帧应全部被消费（旧实现会清队丢弃）", n, consumer.frames.size)
    }

    // ---- R7-2：停机后拒绝新输入 ----

    @Test(timeout = 20_000)
    fun `t2 shutdown 之后 receive 被拒绝 不再进入消费`() {
        val consumer = SlowConsumer(0)
        val worker = ReassemblyWorker(consumer = consumer)

        val f = frameBytes(s2cBody(0x11))
        worker.receive(S2C, 100L, f, SERVER_IP, SERVER_PORT, CLIENT_IP, CLIENT_PORT)
        worker.shutdown()
        assertEquals("停机前那一帧应已消费", 1, consumer.frames.size)

        // 停机后再投：必须被拒绝（否则会在哨兵之后混入真实输入、破坏排空边界）
        worker.receive(S2C, 100L + f.size, f, SERVER_IP, SERVER_PORT, CLIENT_IP, CLIENT_PORT)
        Thread.sleep(100)
        assertEquals("停机后投递必须被拒绝", 1, consumer.frames.size)
    }

    // ---- R7-3：幂等 + 不被 poll 超时拖住 ----

    @Test(timeout = 20_000)
    fun `t3 shutdown 幂等且立即返回 不等满 sweep 轮询超时`() {
        val consumer = SlowConsumer(0)
        val worker = ReassemblyWorker(consumer = consumer)
        worker.receive(S2C, 100L, frameBytes(s2cBody(0x11)), SERVER_IP, SERVER_PORT, CLIENT_IP, CLIENT_PORT)

        val t0 = System.nanoTime()
        worker.shutdown()
        val firstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        // 哨兵必须唤醒阻塞在 poll 上的 worker；若只靠 interrupt/超时，这里会逼近 SWEEP_INTERVAL_MS=5000ms
        assertTrue("shutdown 应立即返回（实测 ${firstMs}ms），不应等满 poll 轮询超时", firstMs < 2_000)

        // 重复调用幂等：不抛异常、不阻塞（cleanup 会经 stopVpn 与 onDestroy 走两次）
        val t1 = System.nanoTime()
        worker.shutdown()
        val secondMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1)
        assertTrue("重复 shutdown 应直接返回（实测 ${secondMs}ms）", secondMs < 500)
        assertEquals("重复停止不应影响已消费结果", 1, consumer.frames.size)
    }
}
