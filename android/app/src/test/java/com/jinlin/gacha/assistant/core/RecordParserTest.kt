package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecordParser JVM 对拍 —— 手构与真机同构的 S→C 抽卡帧体，断言按 PC `extract_records`
 * 契约解码出记录（pool/item/ts）与 offset 归一化的 positionInBatch。
 *
 * 帧结构（对齐 `core/Frame.kt` / PC `frame_schema.py`）：
 *   S→C 头 8B = [type2][extId2][result2][seqEcho1][ack1]，payloadOffset=8；
 *   业务体 = `08 <offset varint> 10 <total varint> 1a <len> + 记录(08 pool/10 item/18 ts)`。
 */
class RecordParserTest {

    // —— 字节构造 ——

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

    private fun record(pool: Long, item: Long, ts: Long): ByteArray =
        byteArrayOf(0x08) + varint(pool) + byteArrayOf(0x10) + varint(item) + byteArrayOf(0x18) + varint(ts)

    /** S→C 抽卡头（类型 0x000c / extId 0x0800 / result 0）。 */
    private val gachaHeader = byteArrayOf(0x00, 0x0c, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)

    private fun gachaFrame(offset: Long, total: Long, vararg records: ByteArray): ByteArray {
        var payload = byteArrayOf(0x08) + varint(offset) + byteArrayOf(0x10) + varint(total)
        for (r in records) payload = payload + byteArrayOf(0x1a) + varint(r.size.toLong()) + r
        return gachaHeader + payload
    }

    // —— 用例 ——

    @Test
    fun `records decoded with offset-based position`() {
        val ts = 1_789_044_076_003L
        val body = gachaFrame(
            offset = 0L, total = 2L,
            record(20003L, 90000017L, ts),
            record(20003L, 90000018L, ts),
        )
        val recs = RecordParser.extractRecords(body)
        assertEquals(2, recs.size)
        assertEquals("20003", recs[0].poolId)
        assertEquals("90000017", recs[0].itemId)
        assertEquals(ts, recs[0].timestamp)
        assertEquals(0, recs[0].positionInBatch)   // offset(0) + 0
        assertEquals(1, recs[1].positionInBatch)   // offset(0) + 1
        assertEquals(0, recs[0].batchSeq)          // 有 offset → batchSeq 恒 0
        assertEquals(0, recs[1].batchSeq)
    }

    @Test
    fun `deep paging offset GE 130 is multibyte varint`() {
        val offset = 130L
        val body = gachaFrame(offset = offset, total = 1L, record(20003L, 90000018L, 1_000L))
        val recs = RecordParser.extractRecords(body)
        assertEquals(1, recs.size)
        assertEquals(offset.toInt(), recs[0].positionInBatch)  // 130 + 0
    }

    @Test
    fun `non gacha envelope yields none`() {
        // 心跳信封：类型 0x0001 / extId 0x0200
        val heartbeat = byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00) +
            byteArrayOf(0x08, 0x00, 0x10, 0x00)
        assertTrue(RecordParser.extractRecords(heartbeat).isEmpty())
    }

    @Test
    fun `rejected frame yields none`() {
        // 抽卡信封但 result=142（限流被拒）→ 无业务体
        // 0x8e = 142 超出 Byte 上界，须显式转 Byte
        val rejected = byteArrayOf(0x00, 0x0c, 0x08, 0x00, 0x00, 0x8e.toByte(), 0x00, 0x00)
        assertTrue(RecordParser.extractRecords(rejected).isEmpty())
    }

    @Test
    fun `record missing timestamp is skipped`() {
        // 只有 pool + item，缺 timestamp → 整个子记录判为不完整，跳过
        val partial = byteArrayOf(0x08) + varint(20003L) + byteArrayOf(0x10) + varint(90000018L)
        val body = gachaFrame(offset = 0L, total = 1L, record(20003L, 90000017L, 1_000L), partial)
        val recs = RecordParser.extractRecords(body)
        assertEquals(1, recs.size)
        assertEquals("90000017", recs[0].itemId)
    }

    @Test
    fun `leaf unknown wire type stops but keeps parsed`() {
        // 业务体混入一个不认识的 wire type（如 0x05）后的记录不会被解析
        val rec = record(20003L, 90000017L, 1_000L)
        val body = gachaHeader +
            byteArrayOf(0x08, 0x00, 0x10, 0x01) +
            byteArrayOf(0x1a) + varint(rec.size.toLong()) + rec +
            byteArrayOf(0x05)  // 未知 wire type → 停止解析（保留已解析记录）
        val recs = RecordParser.extractRecords(body)
        assertEquals(1, recs.size)
    }

    @Test
    fun `silently ignores invalid offset when total tag missing`() {
        // 业务体取不到 0x10(total) 门 → parsePoolOffset 返回 null → positionInBatch 退回帧内序号
        val rec = record(20003L, 90000017L, 1_000L)
        val bad = gachaHeader +
            byteArrayOf(0x08, 0x00) +
            byteArrayOf(0x1a) + varint(rec.size.toLong()) + rec
        val recs = RecordParser.extractRecords(bad)
        assertEquals(1, recs.size)
        assertEquals(0, recs[0].positionInBatch)  // pageOffset 未知 → 帧内序号 0
    }
}