package com.jinlin.gacha.assistant.core

import com.jinlin.gacha.assistant.core.dedup.DedupPipeline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 记录消费者聚合快照 —— 回归 2026-09-14 实机反馈：
 * 「数据已齐（全部卡池翻完）仍提示缺页」。
 *
 * 根因：`RecordFrameConsumer.snapshot().missingPageCount` 原先把 viewTracker.missingPages()
 * **所有视图**的缺页加总；而 B8（仅采纳「全部卡池」）下其它视图本就被丢弃，其缺页
 * 不属于采纳数据的完整性缺口，纳入即误报。修复后对齐 PC `_coverage_gap_text`
 * （`missing_pages(view=全部卡池)`），**只统计「全部卡池」视图**。
 *
 * 本测试构造「全部卡池已齐 + 单池有缺口」两视图，断言快照缺口为 0（只看全部卡池）。
 */
class RecordFrameConsumerTest {

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
    ): ByteArray {
        val head = byteArrayOf(0x00, 0x0c, 0x08, 0x00, 0x00, 0x00, seqEcho.toByte(), 0x00)
        var body = head + byteArrayOf(0x08) + varint(offset) + byteArrayOf(0x10) + varint(total)
        for ((pool, item, ts) in records) body = body + recordField(pool, item, ts)
        return body
    }

    private fun page5(ts: Long, pool: Long = 0, firstItem: Long = 100): List<Triple<Long, Long, Long>> =
        (0 until 5).map { Triple(pool, firstItem + it, ts) }

    private fun c2s(consumer: RecordFrameConsumer, seq: Long, poolId: Long, offset: Long, listType: Long?) {
        consumer.onFrame(Frame(c2sFrame(seq, poolId, offset, listType), header = null, serverToClient = false), Direction.CLIENT_TO_SERVER)
    }

    private fun s2c(consumer: RecordFrameConsumer, seq: Int, offset: Long, total: Long, records: List<Triple<Long, Long, Long>>) {
        consumer.onFrame(Frame(s2cFrame(seq, offset, total, records), header = null, serverToClient = true), Direction.SERVER_TO_CLIENT)
    }

    @Test
    fun `snapshot gap counts only all-pools view`() {
        val received = mutableListOf<GachaRecord>()
        val consumer = RecordFrameConsumer(onRecords = { received += it }, onActivity = {})

        consumer.beginSession(historyRecords = emptyList(), baselineTotal = null)

        // —— 全部卡池（poolId=0, listType=0，视图 "0@0"）：total=5 → 应有页 [0] ——
        c2s(consumer, seq = 1, poolId = 0, offset = 0, listType = 0)
        s2c(consumer, seq = 1, offset = 0, total = 5, records = page5(1000))

        // —— 单池（poolId=30001, listType=0，视图 "30001@0"）：total=15 → 应有页 [0,5,10]
        //    只见了 offset 0 → 缺 2 页。B8 下该视图被丢弃，其缺页不属采纳数据缺口。 ——
        c2s(consumer, seq = 2, poolId = 30001, offset = 0, listType = 0)
        s2c(consumer, seq = 2, offset = 0, total = 15, records = page5(2000, pool = 30001, firstItem = 200))

        val snap = consumer.snapshot()
        assertEquals(true, snap.allPoolsSeen)
        // 单池 5 页数据被 B8 丢弃 → 记录只来自全部卡池
        assertEquals(5, snap.newCount)
        assertEquals(5, snap.parsedCount) // 只统计“被采纳”的全部卡池页（单池丢弃不计）
        // 全部卡池已齐 → 缺口 0（单池的 2 页缺口不得计入）
        assertEquals(0, snap.missingPageCount)
    }

    @Test
    fun `snapshot gap still reported when all-pools itself incomplete`() {
        val consumer = RecordFrameConsumer(onRecords = {}, onActivity = {})
        consumer.beginSession(historyRecords = emptyList(), baselineTotal = null)

        // 全部卡池 total=12 → 应有页 [0,5,10]；只见 offset 0 → 缺 2 页（真实缺口须保留）
        c2s(consumer, seq = 1, poolId = 0, offset = 0, listType = 0)
        s2c(consumer, seq = 1, offset = 0, total = 12, records = page5(1000))

        val snap = consumer.snapshot()
        assertEquals(2, snap.missingPageCount)
    }

    @Test
    fun `snapshot reading but all dup keeps parsed positive new zero`() {
        // 历史已有一条完整事件 ts=9000；本次再抓到同一 ts → 事件级判重全部跳过。
        // 这对应实机「翻到的页都是历史已有」：newCount=0 但 parsedCount>0（确实在读到数据），
        // 面板靠 parsedCount 区分「没抓到/工具坏」vs「正常但都是旧记录」，后者不该红色提醒。
        val history = listOf(GachaRecord("0", "100", 9000L, 0, 0))
        val consumer = RecordFrameConsumer(onRecords = {}, onActivity = {})
        consumer.beginSession(historyRecords = history, baselineTotal = 1)

        c2s(consumer, seq = 1, poolId = 0, offset = 0, listType = 0)
        s2c(consumer, seq = 1, offset = 0, total = 1, records = listOf(Triple(0L, 100L, 9000L)))

        val snap = consumer.snapshot()
        assertEquals(true, snap.allPoolsSeen)
        assertEquals(0, snap.newCount) // 全被历史判重跳过 → 0 新增
        assertEquals(1, snap.parsedCount) // 但确实读到了 1 条 → 抓取正常
    }
}