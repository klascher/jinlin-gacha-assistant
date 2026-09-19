package com.jinlin.gacha.assistant.core.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺口三分类 JVM 对拍（U5 `10-去重模块设计.md` §17.3 / §17.10）。
 *
 * [ViewTracker.classifyGap] 是「缺页提示」从「一刀切」走向「按成因给对策」的关键一步：
 * 它把 `应有页 − 已见页` 切成 **A 首屏 / B 中段 / C 尾部** 三类，因为三类的补救动作
 * **完全不同**（重登 / 翻回去 / 继续往下翻）。分类错 = 让用户做无用功，所以逐类断言。
 *
 * ⚠️ 本文件**不碰** `SessionDedup`：发现 5（`overlappedHistory` 在本场重复时误置真）
 * 已随 `docs/抽卡记录结构规则.md` **C5 页缓存**证据**撤回**，判定语义一行未动，
 * 原计划的 `SessionDedupAttributionTest` 一并删除（见 §17.2 发现 5）。
 */
class GapClassifyTest {

    /** total=25 条 ÷ 每页 5（规则 B4）⇒ 应有 5 页，offset = [0, 5, 10, 15, 20]。 */
    private val expected5 = ViewIdentity.expectedPageOffsets(25)

    /** 「全部卡池」视图标识。 */
    private val view = ViewIdentity.makeView(poolId = 0, listType = ViewIdentity.LIST_TYPE_ALL)

    @Test
    fun `expected page offsets are derived from authoritative total`() {
        assertEquals(listOf(0, 5, 10, 15, 20), expected5)
        // 末页不足 5 条也要算一页（total=21 ⇒ [0,5,10,15,20]；total=26 ⇒ 多一页 25）
        assertEquals(listOf(0, 5, 10, 15, 20), ViewIdentity.expectedPageOffsets(21))
        assertEquals(listOf(0, 5, 10, 15, 20, 25), ViewIdentity.expectedPageOffsets(26))
    }

    /**
     * ① 只有尾部缺口 → 全部归 `tail`。
     *
     * **这是防 2026-09-16 那次修复回归的守门断言**：当时用户反馈「翻到历史了还提示缺页」，
     * 修法是给提示加 `!overlapped` 闸；本次（U5）把「尾部未翻」单列成 C 类，
     * 语义必须仍是「继续往下翻」，**不能被误判成首屏缺口**（那会让用户白重登一次）。
     */
    @Test
    fun `only tail gap is classified as tail`() {
        val gap = ViewTracker.classifyGap(expected5, seen = setOf(0, 5))
        assertEquals(listOf(10, 15, 20), gap.tail)
        assertTrue(gap.head.isEmpty())
        assertTrue(gap.mid.isEmpty())
        assertFalse(gap.hasHead)
        assertTrue(gap.hasTail)
    }

    /** ② 只有首屏缺口 → 全部归 `head`（A 类，只能靠重新登录补）。 */
    @Test
    fun `only head gap is classified as head`() {
        val gap = ViewTracker.classifyGap(expected5, seen = setOf(10, 15, 20))
        assertEquals(listOf(0, 5), gap.head)
        assertTrue(gap.mid.isEmpty())
        assertTrue(gap.tail.isEmpty())
        assertTrue(gap.hasHead)
    }

    /** ③ 夹在已见页中间的空洞 → `mid`（B 类，翻回去就能补）。 */
    @Test
    fun `hole between seen pages is classified as mid`() {
        val gap = ViewTracker.classifyGap(expected5, seen = setOf(0, 5, 15, 20))
        assertEquals(listOf(10), gap.mid)
        assertTrue(gap.head.isEmpty())
        assertTrue(gap.tail.isEmpty())
        assertTrue(gap.hasMid)
    }

    /** ④ 无缺口 → 全空（且 `missing` 并集为空）。 */
    @Test
    fun `no gap yields empty summary`() {
        val gap = ViewTracker.classifyGap(expected5, seen = setOf(0, 5, 10, 15, 20))
        assertTrue(gap.isEmpty)
        assertTrue(gap.head.isEmpty() && gap.mid.isEmpty() && gap.tail.isEmpty())
    }

    /**
     * ⑤ **`total` 中途变大时不产生假缺口**。
     *
     * 场景：本场按 total=25 翻完 5 页，随后又抽了卡 ⇒ 服务器 total 变 26 ⇒ 多出第 6 页？
     * 正确行为是**只多出一个尾部缺口**（offset=25），**绝不能**因为新旧 total 不一致
     * 而报出首屏缺口（那会让用户被告知「第 1 页丢了」而白重登）。
     */
    @Test
    fun `larger total only adds tail gap never a fake head gap`() {
        val seen = setOf(0, 5, 10, 15, 20)
        // total 25 → 无缺口
        assertTrue(ViewTracker.classifyGap(expected5, seen).isEmpty)
        // total 变成 26 → 只多一页（offset 25），归尾部
        val grown = ViewTracker.classifyGap(ViewIdentity.expectedPageOffsets(26), seen)
        assertEquals(listOf(25), grown.tail)
        assertTrue(grown.head.isEmpty())
        assertTrue(grown.mid.isEmpty())
    }

    /** ⑥ 一页都没见（与应有页无交集）→ 全归 `mid`，**不臆断**成首屏或尾部。 */
    @Test
    fun `no overlap with expected yields all mid`() {
        // 应有页是 [0,5,10,15,20]，已见全在别的视图范围（这里用超界 offset 模拟）
        val gap = ViewTracker.classifyGap(expected5, seen = setOf(100, 105))
        assertEquals(listOf(0, 5, 10, 15, 20), gap.mid)
        assertTrue(gap.head.isEmpty())
        assertTrue(gap.tail.isEmpty())
    }

    /** ⑦ 三类互斥且并集 = 全部缺失（保序升序）—— 分类只做切片，不丢页、不重页。 */
    @Test
    fun `head mid tail are disjoint and cover every missing page`() {
        val gap = ViewTracker.classifyGap(expected5, seen = setOf(10))
        assertEquals(listOf(0, 5), gap.head)
        assertEquals(listOf(15, 20), gap.tail)
        assertTrue(gap.mid.isEmpty())
        assertEquals(gap.missing, (gap.head + gap.mid + gap.tail).sorted())
        assertEquals(listOf(0, 5, 15, 20), gap.missing)
    }

    /**
     * ⑧ 增量计数与「现算」必须一致（随机 200 次 `noteResponse` 后逐项相等）。
     *
     * `ViewTracker` 的 `seenMin` / `seenMax` / `seenCount` 是为了让抓包页实时判据 O(1)
     * 才引入的**冗余状态** —— 冗余状态唯一会出事的地方就是「登记时漏更新」，或
     * 「同一页被重复响应时重复计数」。这里用固定种子的随机序列覆盖：跳页、重复页、
     * 乱序回访都在内，最后与 `seenPages`（唯一真相）现算的结果逐项比对。
     */
    @Test
    fun `incremental counters stay equal to recomputed values`() {
        val tracker = ViewTracker()
        val random = java.util.Random(20260918L)
        val seenOffsets = mutableSetOf<Int>()
        var seq = 0L

        repeat(200) {
            // 10 页里随便挑一页（重复访问很常见：翻回去看 / 限流重发）
            val offset = random.nextInt(10) * ViewIdentity.PAGE_SIZE
            tracker.trackRequest(c2sFrame(seq = seq, offset = offset.toLong(), listType = 0L))
            tracker.noteResponse(
                s2cFrame(seqEcho = (seq % 127L).toInt(), offset = offset.toLong(), total = 50L),
            )
            seenOffsets += offset
            seq++
        }

        // 唯一真相：seenPages 现算
        val recomputed = tracker.seen.filter { it.first == view }.map { it.second }.toSet()
        assertEquals(seenOffsets, recomputed)

        // 增量维护值必须与现算逐项相等
        assertEquals(recomputed.min(), tracker.minSeenOffset(view)!!)
        assertEquals(recomputed.max(), tracker.maxSeenOffset(view)!!)
        assertEquals(recomputed.size, tracker.seenPageCount(view))
    }

    /** ⑨ `reset()` 必须把三个增量计数一并清空（漏清会让下一场继承上一场的最小 offset）。 */
    @Test
    fun `reset clears incremental counters`() {
        val tracker = ViewTracker()
        tracker.trackRequest(c2sFrame(seq = 1L, offset = 10L, listType = 0L))
        tracker.noteResponse(s2cFrame(seqEcho = 1, offset = 10L, total = 50L))
        assertEquals(10, tracker.minSeenOffset(view)!!)

        tracker.reset()
        assertNull(tracker.minSeenOffset(view))
        assertNull(tracker.maxSeenOffset(view))
        assertEquals(0, tracker.seenPageCount(view))
    }

    // —— 帧构造（与 `ViewTrackerTest` 同款；协议细节见 `core/dedup/PoolRequestCodec`） ——

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

    /** C→S 请求帧（全部卡池：poolId=0、listType=0）。 */
    private fun c2sFrame(seq: Long, offset: Long, listType: Long): ByteArray {
        var body = byteArrayOf(
            ((seq ushr 24) and 0xff).toByte(),
            ((seq ushr 16) and 0xff).toByte(),
            ((seq ushr 8) and 0xff).toByte(),
            (seq and 0xff).toByte(),
            0x00, 0x0c,
            0x08, 0x00,
            (seq % 127L).toByte(),
        )
        body = body + byteArrayOf(0x08) + varint(0L) + byteArrayOf(0x10) + varint(offset)
        body = body + byteArrayOf(0x18) + varint(listType)
        return body
    }

    /** S→C 正常响应帧（回显 seq + offset + 权威 total）。 */
    private fun s2cFrame(seqEcho: Int, offset: Long, total: Long): ByteArray =
        byteArrayOf(
            0x00, 0x0c, 0x08, 0x00,
            0x00, 0x00,
            seqEcho.toByte(), 0x00,
        ) + byteArrayOf(0x08) + varint(offset) + byteArrayOf(0x10) + varint(total)
}
