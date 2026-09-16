package com.jinlin.gacha.assistant.core.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视图追踪 JVM 对拍 —— 对齐 PC `PoolViewTracker.track_request` / `resolve_view` /
 * `note_response` / `missing_pages` / `reset`。
 *
 * 重点覆盖三条易错语义：
 * 1. 配对键是 `seq % 127`（响应只回显回绕值），seq ≥ 127 也要能配对；
 * 2. **被拒响应（8B 截断帧）必须在「无记录早退」之前被登记**，且能定位到被拒的 offset；
 * 3. 请求没抓到 / seq 未登记时回退 `currentView`。
 */
class ViewTrackerTest {

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

    private fun s2cFrame(seqEcho: Int, offset: Long, total: Long, result: Int = 0): ByteArray {
        val body = byteArrayOf(
            0x00, 0x0c, 0x08, 0x00,
            ((result ushr 8) and 0xff).toByte(), (result and 0xff).toByte(),
            seqEcho.toByte(), 0x00,
        )
        if (result == 142) return body
        return body + byteArrayOf(0x08) + varint(offset) + byteArrayOf(0x10) + varint(total)
    }

    @Test
    fun `tracks request and resolves response view by seq`() {
        val tracker = ViewTracker()
        val req = tracker.trackRequest(c2sFrame(seq = 5, poolId = 0, offset = 0, listType = 0))
        assertEquals("0@0", tracker.current)
        assertEquals(0, req!!.poolId)
        // 响应回显 seq=5 → 配对到 "0@0"
        assertEquals("0@0", tracker.resolveView(s2cFrame(seqEcho = 5, offset = 0, total = 521)))
    }

    @Test
    fun `seq pairing uses modulo 127`() {
        val tracker = ViewTracker()
        tracker.trackRequest(c2sFrame(seq = 200, poolId = 30005, offset = 15, listType = 0))
        // 200 % 127 = 73，响应回显即该值
        assertEquals("30005@0", tracker.resolveView(s2cFrame(seqEcho = 73, offset = 15, total = 60)))
    }

    @Test
    fun `records view totals and seen pages`() {
        val tracker = ViewTracker()
        tracker.trackRequest(c2sFrame(seq = 5, poolId = 0, offset = 0, listType = 0))
        assertNull(tracker.noteResponse(s2cFrame(seqEcho = 5, offset = 0, total = 521)))
        assertEquals(mapOf("0@0" to 521), tracker.totals)
        assertTrue(tracker.seen.contains("0@0" to 0))
    }

    @Test
    fun `detects rejected page and locates its offset`() {
        val tracker = ViewTracker()
        tracker.trackRequest(c2sFrame(seq = 5, poolId = 0, offset = 10, listType = 0))
        val rejected = tracker.noteResponse(s2cFrame(seqEcho = 5, offset = 0, total = 0, result = 142))
        assertEquals("0@0" to 10, rejected)                 // offset 来自请求登记，不是响应（被拒帧无业务体）
        assertEquals(listOf("0@0" to 10), tracker.rejectedPages)
        assertTrue(tracker.seen.isEmpty())                  // 被拒页不计入已见
    }

    @Test
    fun `missing pages computed from total`() {
        val tracker = ViewTracker()
        tracker.trackRequest(c2sFrame(seq = 5, poolId = 0, offset = 0, listType = 0))
        tracker.noteResponse(s2cFrame(seqEcho = 5, offset = 0, total = 12))   // 应有页 [0, 5, 10]
        val report = tracker.missingPages()
        assertEquals(1, report.size)
        assertEquals("0@0", report[0].view)
        assertEquals(listOf(5, 10), report[0].missing)
        assertEquals(1, report[0].seenCount)
        assertEquals(3, report[0].expectedCount)
    }

    @Test
    fun `resolve falls back to current view when seq unknown`() {
        val tracker = ViewTracker()
        tracker.trackRequest(c2sFrame(seq = 5, poolId = 0, offset = 0, listType = 0))
        // seq=99 未登记（请求没抓到）→ 回退 currentView
        assertEquals("0@0", tracker.resolveView(s2cFrame(seqEcho = 99, offset = 0, total = 1)))
    }

    @Test
    fun `reset clears all state`() {
        val tracker = ViewTracker()
        tracker.trackRequest(c2sFrame(seq = 5, poolId = 0, offset = 0, listType = 0))
        tracker.noteResponse(s2cFrame(seqEcho = 5, offset = 0, total = 521))
        tracker.reset()
        assertNull(tracker.current)
        assertTrue(tracker.totals.isEmpty())
        assertTrue(tracker.seen.isEmpty())
        assertTrue(tracker.rejectedPages.isEmpty())
    }
}
