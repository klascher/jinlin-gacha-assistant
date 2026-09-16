package com.jinlin.gacha.assistant.core.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切池/翻页帧编解码 JVM 对拍 —— 手构与真机同构的 C→S 请求 / S→C 响应帧体，断言按 PC
 * `parse_pool_request` / `parse_pool_list_type` / `parse_pool_response` 契约解析。
 *
 * 帧结构（对齐 `core/Frame.kt`）：
 *   C→S = [4B seq][2B 类型 0x000c][2B extId 0x0800][1B counter=seq%127][业务体]
 *   S→C = [2B 类型][2B extId][2B result][1B seq回显][1B ack][业务体]
 *   请求业务体 = `08 <pool_id> 10 <offset> [18 <list_type>]`
 *   响应业务体 = `08 <offset回显> 10 <total>` + repeated 记录
 */
class PoolRequestCodecTest {

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

    /** C→S 抽卡帧（类型 0x000c / extId 0x0800 / counter = seq%127）。 */
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

    /** S→C 抽卡帧；result=142 时返回 8 字节截断帧（无业务体）。 */
    private fun s2cFrame(seqEcho: Int, offset: Long, total: Long, result: Int = 0): ByteArray {
        val body = byteArrayOf(
            0x00, 0x0c, 0x08, 0x00,
            ((result ushr 8) and 0xff).toByte(), (result and 0xff).toByte(),
            seqEcho.toByte(), 0x00,
        )
        if (result == 142) return body
        return body + byteArrayOf(0x08) + varint(offset) + byteArrayOf(0x10) + varint(total)
    }

    // —— 请求侧 ——

    @Test
    fun `parse request with list type`() {
        val req = PoolRequestCodec.parse(c2sFrame(seq = 0x3b, poolId = 0, offset = 5, listType = 3))
        assertEquals(0x3bL, req!!.seq)
        assertEquals(0, req.poolId)
        assertEquals(5, req.offset)
        assertEquals(3, req.listType)
        assertEquals("0@3", ViewIdentity.makeView(req.poolId, req.listType))
    }

    @Test
    fun `parse request without list type falls back to bare pool id`() {
        val req = PoolRequestCodec.parse(c2sFrame(seq = 7, poolId = 0, offset = 0))
        assertEquals(0, req!!.poolId)
        assertNull(req.listType)
        assertEquals("0", ViewIdentity.makeView(req.poolId, req.listType))
    }

    @Test
    fun `deep paging offset GE 130 is multibyte varint`() {
        val req = PoolRequestCodec.parse(c2sFrame(seq = 9, poolId = 30005, offset = 130, listType = 0))
        assertEquals(30005, req!!.poolId)
        assertEquals(130, req.offset)
    }

    @Test
    fun `non gacha envelope yields null`() {
        // 心跳信封：类型 0x0001 / extId 0x0200
        val heartbeat = byteArrayOf(
            0x00, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x02, 0x00,
            0x05,
        ) + byteArrayOf(0x08, 0x00, 0x10, 0x00)
        assertNull(PoolRequestCodec.parse(heartbeat))
        assertNull(PoolRequestCodec.parseListType(heartbeat))
    }

    @Test
    fun `list type is null when field3 absent`() {
        assertNull(PoolRequestCodec.parseListType(c2sFrame(seq = 3, poolId = 0, offset = 0)))
        assertEquals(4, PoolRequestCodec.parseListType(c2sFrame(seq = 3, poolId = 0, offset = 0, listType = 4)))
    }

    // —— 响应侧 ——

    @Test
    fun `parse normal response`() {
        val resp = PoolResponseCodec.parse(s2cFrame(seqEcho = 5, offset = 0, total = 521))
        assertEquals(5, resp!!.seq)
        assertEquals(0, resp.offset)
        assertEquals(521, resp.total)
        assertFalse(resp.rejected)
    }

    @Test
    fun `parse rejected response is 8 byte truncated`() {
        val resp = PoolResponseCodec.parse(s2cFrame(seqEcho = 5, offset = 0, total = 0, result = 142))
        assertEquals(5, resp!!.seq)
        assertTrue(resp.rejected)
        assertEquals(0, resp.total)
    }

    @Test
    fun `response with non gacha envelope yields null`() {
        val heartbeat = byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x00, 0x05, 0x00)
        assertNull(PoolResponseCodec.parse(heartbeat))
    }
}
