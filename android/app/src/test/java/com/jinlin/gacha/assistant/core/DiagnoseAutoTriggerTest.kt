package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiagnoseDumper.automaticHit] 单测（U2，2026-09-20）—— 纯函数逐条对拍，不需 Context / Android 运行时。
 *
 * 为什么值得单测一个「判据」：它决定**异常时要不要自动落盘**，是这套诊断机制的核心；抽成纯函数前
 * 它埋在依赖 `Context` 与后台线程的 `checkAutomatic()` 里，动一行都不好回归。这里把三条判据的
 * **触发条件、优先级、门槛边界**钉死，防止将来调阈值时无意改变语义。
 *
 * 判据（与 `DiagnoseDumper.automaticHit` 逐条对应）：
 *  - **A1**：`packets == 0`（隧道空转 / 白名单没拦到）
 *  - **A2**：`packets >= 阈值 && s2c 载荷回包 == 0`（方向 / 端口 / 连接异常）
 *  - **B1**：`serverFrames >= 3 && gachaHits == 0`（方向反 / 信封错位 / 协议判错）
 *
 * 三者**互斥、按 A1 > A2 > B1 优先**，只报第一个命中的；`elapsed < silence` 一律不判。
 */
class DiagnoseAutoTriggerTest {

    // 真实调用点使用的阈值（DiagnoseDumper：AUTO_SILENCE_MS / SYNC_PKT_BEFORE_WARN）
    private val silence = 20_000L
    private val syncThreshold = 60L

    /** 便捷入口：默认「已过起步缓冲期」；其余计数由用例显式给。 */
    private fun hit(
        elapsedMs: Long = 30_000L,
        packets: Long = 0L,
        s2c: Long = 0L,
        serverFrames: Long = 0L,
        gachaHits: Long = 0L,
    ): String? = DiagnoseDumper.automaticHit(
        elapsedMs = elapsedMs,
        silenceMs = silence,
        packets = packets,
        s2cPayloadPackets = s2c,
        syncPacketThreshold = syncThreshold,
        serverFrames = serverFrames,
        gachaHits = gachaHits,
    )

    @Test
    fun `silence window suppresses every judgment`() {
        // 起步缓冲期内即使一个包都没见到也不判（避免隧道刚建好就误报）
        assertNull(hit(elapsedMs = 5_000L, packets = 0L))
        assertNull(hit(elapsedMs = 19_999L, packets = 0L))
    }

    @Test
    fun `A1 fires when no packet ever seen`() {
        val r = hit(packets = 0L)
        assertTrue("应命中 A1，实际=$r", r != null && r.startsWith("A1"))
    }

    @Test
    fun `A1 reports elapsed seconds`() {
        val r = hit(elapsedMs = 45_000L, packets = 0L)
        assertTrue("A1 文案应含已过秒数 45s，实际=$r", r != null && r.contains("45s"))
    }

    @Test
    fun `A2 fires on packets without any server payload`() {
        val r = hit(packets = 60L, s2c = 0L)
        assertTrue("应命中 A2，实际=$r", r != null && r.startsWith("A2"))
    }

    @Test
    fun `A2 not fired below packet threshold`() {
        // 差一个包不算：阈值语义是「累计包数越此仍无回包」
        assertNull(hit(packets = 59L, s2c = 0L))
    }

    @Test
    fun `A2 not fired when server payload present`() {
        assertNull(hit(packets = 200L, s2c = 1L, serverFrames = 0L, gachaHits = 0L))
    }

    @Test
    fun `B1 fires when frames split but zero hits`() {
        val r = hit(packets = 200L, s2c = 5L, serverFrames = 3L, gachaHits = 0L)
        assertTrue("应命中 B1，实际=$r", r != null && r.startsWith("B1"))
    }

    @Test
    fun `B1 not fired below frame floor`() {
        // 已切 2 帧仍属噪声区，不判（下限 = 3 帧）
        assertNull(hit(packets = 200L, s2c = 5L, serverFrames = 2L, gachaHits = 0L))
    }

    @Test
    fun `B1 not fired when there are hits`() {
        assertNull(hit(packets = 200L, s2c = 5L, serverFrames = 10L, gachaHits = 1L))
    }

    @Test
    fun `priority A1 over A2 and B1`() {
        // 零包时即使切分统计异常也报 A1（与改造前 if/else if 的优先级一致）
        val r = hit(packets = 0L, s2c = 0L, serverFrames = 9L, gachaHits = 0L)
        assertTrue("互斥应只报 A1，实际=$r", r != null && r.startsWith("A1"))
    }

    @Test
    fun `priority A2 over B1`() {
        val r = hit(packets = 100L, s2c = 0L, serverFrames = 9L, gachaHits = 0L)
        assertTrue("互斥应只报 A2，实际=$r", r != null && r.startsWith("A2"))
    }

    @Test
    fun `healthy stream yields no hit`() {
        assertNull(hit(packets = 500L, s2c = 50L, serverFrames = 20L, gachaHits = 8L))
    }
}
