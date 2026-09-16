package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.GachaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DedupPipeline] 单测 —— 手构与真机同构的 C→S 请求 / S→C 响应帧体，逐例断言整条链路：
 * 请求登记视图 → `noteResponse` 先于早退 → B8 过滤 → Δ 基准与动态跟随 → 实时位置判重 → 缺口。
 *
 * 帧结构（对齐 `core/Frame.kt` 与 `PoolRequestCodecTest`）：
 *   C→S = [4B seq][2B 0x000c][2B 0x0800][1B seq%127][业务体]
 *   S→C = [2B 0x000c][2B 0x0800][2B result][1B seq回显][1B ack][业务体]
 *   请求业务体 = `08 <pool_id> 10 <offset> [18 <list_type>]`
 *   响应业务体 = `08 <offset回显> 10 <total>` + repeated `1a <len> (08 pool 10 item 18 ts)`
 */
class DedupPipelineTest {

    // —— 帧构造 ——

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        while (true) {
            var b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
            if (v == 0L) break
        }
        return out.toByteArray()
    }

    private fun c2sFrame(seq: Long, poolId: Long, offset: Long, listType: Long? = null): ByteArray {
        var body = byteArrayOf(
            ((seq ushr 24) and 0xff).toByte(),
            ((seq ushr 16) and 0xff).toByte(),
            ((seq ushr 8) and 0xff).toByte(),
            (seq and 0xff).toByte(),
            0x00, 0x0c,
            0x08, 0x00,
            (seq % 127L).toByte(),
        )
        body = body + byteArrayOf(0x08) + varint(poolId) + byteArrayOf(0x10) + varint(offset)
        if (listType != null) body = body + byteArrayOf(0x18) + varint(listType)
        return body
    }

    private fun recordField(pool: Long, item: Long, ts: Long): ByteArray {
        val sub = byteArrayOf(0x08) + varint(pool) +
            byteArrayOf(0x10) + varint(item) +
            byteArrayOf(0x18) + varint(ts)
        return byteArrayOf(0x1a) + varint(sub.size.toLong()) + sub
    }

    private fun s2cFrame(
        seqEcho: Int,
        offset: Long,
        total: Long,
        records: List<Triple<Long, Long, Long>> = emptyList(),
        result: Int = 0,
    ): ByteArray {
        val head = byteArrayOf(
            0x00, 0x0c, 0x08, 0x00,
            ((result ushr 8) and 0xff).toByte(), (result and 0xff).toByte(),
            seqEcho.toByte(), 0x00,
        )
        if (result == 142) return head
        var body = head + byteArrayOf(0x08) + varint(offset) + byteArrayOf(0x10) + varint(total)
        for ((pool, item, ts) in records) body = body + recordField(pool, item, ts)
        return body
    }

    /** 一个十连事件：10 条共享同一 ts。 */
    private fun tenPull(ts: Long, pool: Long = 0, firstItem: Long = 100): List<Triple<Long, Long, Long>> =
        (0 until 10).map { Triple(pool, firstItem + it, ts) }

    /** 一页 5 条（页大小）。 */
    private fun page5(ts: Long, pool: Long = 0, firstItem: Long = 100): List<Triple<Long, Long, Long>> =
        (0 until 5).map { Triple(pool, firstItem + it, ts) }

    private fun historyEvent(ts: Long, pool: String = "0", firstItem: Int = 100, count: Int = 10): List<GachaRecord> =
        (0 until count).map { GachaRecord(pool, (firstItem + it).toString(), ts, 0, it) }

    // —— 用例 ——

    @Test
    fun `all pools page accepted then identical refetch skipped`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(seq = 1, poolId = 0, offset = 0, listType = 0))
        assertTrue(pipe.allPoolsSeen)

        val out1 = pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = tenPull(2000)))
        assertEquals(DedupPipeline.Outcome.Kind.ACCEPTED, out1.kind)
        assertEquals("0@0", out1.view)
        assertEquals(10, out1.kept.size)
        assertEquals(0, out1.kept.first().positionInBatch)
        assertEquals(9, out1.kept.last().positionInBatch)

        // 同页重发（整批指纹相同）→ 整批跳过
        val out2 = pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = tenPull(2000)))
        assertEquals(DedupPipeline.Outcome.Kind.ACCEPTED, out2.kind)
        assertTrue(out2.kept.isEmpty())
        assertEquals(10, pipe.sessionNewCount)
    }

    @Test
    fun `overlapping page refetch skips only colliding positions`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(1, poolId = 0, offset = 0, listType = 0))
        val out1 = pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = page5(2000, firstItem = 100)))
        assertEquals(5, out1.kept.size)

        // 第二次翻到 offset=3：位置 3,4 与上一页重叠 → 只收 5,6,7
        pipe.onRequest(c2sFrame(2, poolId = 0, offset = 3, listType = 0))
        val out2 = pipe.onResponse(s2cFrame(2, offset = 3, total = 10, records = page5(2000, firstItem = 200)))
        assertEquals(3, out2.kept.size)
        assertEquals(listOf(5, 6, 7), out2.kept.map { it.positionInBatch })
        assertEquals(8, pipe.sessionNewCount)
    }

    @Test
    fun `non all pools view is dropped by B8`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(seq = 1, poolId = 0, offset = 0, listType = 3)) // 活动契约
        assertFalse(pipe.allPoolsSeen)

        val out = pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = tenPull(2000)))
        assertEquals(DedupPipeline.Outcome.Kind.DROPPED_VIEW, out.kind)
        assertEquals("0@3", out.view)
        assertEquals(1, pipe.droppedPages)
        assertEquals(0, pipe.sessionNewCount)
    }

    @Test
    fun `single pool view is dropped and later all pools view accepted`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(1, poolId = 30005, offset = 0, listType = 0))
        assertEquals(DedupPipeline.Outcome.Kind.DROPPED_VIEW, pipe.onResponse(
            s2cFrame(1, offset = 0, total = 20, records = page5(2000))
        ).kind)

        pipe.onRequest(c2sFrame(2, poolId = 0, offset = 0, listType = 0))
        assertTrue(pipe.allPoolsSeen)
        val out = pipe.onResponse(s2cFrame(2, offset = 0, total = 10, records = page5(2000)))
        assertEquals(DedupPipeline.Outcome.Kind.ACCEPTED, out.kind)
        assertEquals(5, out.kept.size)
    }

    @Test
    fun `rejected page detected before record parsing`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(seq = 1, poolId = 0, offset = 0, listType = 0))

        val out = pipe.onResponse(s2cFrame(1, offset = 0, total = 0, result = 142))
        assertEquals(DedupPipeline.Outcome.Kind.REJECTED, out.kind)
        assertEquals("0@0", out.view)
        assertEquals(listOf("0@0" to 0), pipe.rejectedPages())
    }

    @Test
    fun `unknown view records are accepted without position dedup`() {
        // 从未抓到 C→S 请求 → resolveView 为 null → Q6：只收不判重
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        val out = pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = tenPull(2000)))
        assertEquals(DedupPipeline.Outcome.Kind.ACCEPTED, out.kind)
        assertNull(out.view)
        assertEquals(10, out.kept.size)

        // 仍受整批指纹兜底（字节级重传被拦），但位置不判重
        val out2 = pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = tenPull(2000)))
        assertTrue(out2.kept.isEmpty())
        assertEquals(10, pipe.sessionNewCount)
    }

    @Test
    fun `response with unknown seq echo falls back to current view`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(seq = 1, poolId = 0, offset = 0, listType = 0))

        val out = pipe.onResponse(s2cFrame(seqEcho = 99, offset = 0, total = 10, records = tenPull(2000)))
        assertEquals(DedupPipeline.Outcome.Kind.ACCEPTED, out.kind)
        assertEquals("0@0", out.view)
    }

    @Test
    fun `all pools page without records reports no records`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(seq = 1, poolId = 0, offset = 0, listType = 0))
        assertEquals(
            DedupPipeline.Outcome.Kind.NO_RECORDS,
            pipe.onResponse(s2cFrame(1, offset = 0, total = 10)).kind,
        )
    }

    @Test
    fun `heartbeat response is not gacha`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        val heartbeat = byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x00, 0x05, 0x00)
        assertEquals(DedupPipeline.Outcome.Kind.NOT_GACHA, pipe.onResponse(heartbeat).kind)
    }

    @Test
    fun `shift follows total growth mid session`() {
        // 历史：完整十连 ts=1000，Δ 基准 total=10
        val pipe = DedupPipeline(historyEvent(1000), baselineTotal = 10)
        pipe.onRequest(c2sFrame(1, poolId = 0, offset = 0, listType = 0))
        pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = tenPull(2000)))
        assertEquals(0, pipe.shift)

        // 会话中途抽了 5 抽：total 10 → 15 → Δ 立即 +5
        pipe.onRequest(c2sFrame(2, poolId = 0, offset = 0, listType = 0))
        val out = pipe.onResponse(s2cFrame(2, offset = 0, total = 15, records = tenPull(3000)))
        assertEquals(5, pipe.shift)
        assertEquals(10, out.kept.size)
        // 收下即归一化：pos − Δ = 0..9 − 5 = −5..4
        assertEquals((-5..4).toList(), out.kept.map { it.positionInBatch })
    }

    @Test
    fun `history complete event refetch is skipped at event level`() {
        // 历史完整十连 ts=1000；再抓同一 ts 的同一事件 → 事件级跳过（ts 即身份）
        val pipe = DedupPipeline(historyEvent(1000), baselineTotal = 10)
        pipe.onRequest(c2sFrame(1, poolId = 0, offset = 0, listType = 0))
        val out = pipe.onResponse(s2cFrame(1, offset = 0, total = 10, records = tenPull(1000)))
        assertEquals(DedupPipeline.Outcome.Kind.ACCEPTED, out.kind)
        assertTrue(out.kept.isEmpty())
        assertTrue(pipe.overlappedHistory)
    }

    @Test
    fun `missing pages computed from authoritative total`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        pipe.onRequest(c2sFrame(seq = 1, poolId = 0, offset = 0, listType = 0))
        pipe.onResponse(s2cFrame(1, offset = 0, total = 12, records = page5(2000)))

        // total=12 → 应有页 offset 0/5/10；只见 0 → 缺 2 页
        val gaps = pipe.missingPages()
        assertEquals(1, gaps.size)
        assertEquals("0@0", gaps[0].view)
        assertEquals(listOf(5, 10), gaps[0].missing)
        assertEquals(1, gaps[0].seenCount)
        assertEquals(3, gaps[0].expectedCount)
        assertEquals(mapOf("0@0" to 12), pipe.viewTotals())
    }

    @Test
    fun `suspicious and unresolved events reported`() {
        val pipe = DedupPipeline(emptyList(), baselineTotal = null)
        // 只抓到十连的前 5 条（大小 5 ∉ {1,10}），且再补 offset=5 的 3 条 → 位置 {0..4,5,6,7} 连续
        // 改用 offset=20 的 3 条制造空洞：位置 {0..4,20,21,22}
        pipe.onRequest(c2sFrame(1, poolId = 0, offset = 0, listType = 0))
        pipe.onResponse(s2cFrame(1, offset = 0, total = 30, records = page5(2000, firstItem = 100)))
        pipe.onRequest(c2sFrame(2, poolId = 0, offset = 20, listType = 0))
        pipe.onResponse(s2cFrame(2, offset = 20, total = 30, records = (0..2).map { Triple(0L, 300L + it, 2000L) }))

        val susp = pipe.suspiciousEvents()
        assertEquals(1, susp.size)
        assertEquals(2000L, susp[0].ts)
        assertEquals(8, susp[0].size)

        val unresolved = pipe.unresolvedEvents()
        assertEquals(1, unresolved.size)
        assertEquals(2000L, unresolved[0].ts)
    }
}
