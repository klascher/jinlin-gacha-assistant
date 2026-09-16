package com.jinlin.gacha.assistant.core.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视图身份 JVM 对拍 —— 对齐 PC `pool_view.make_view` / `split_view` / `is_all_pools`
 * / `expected_page_offsets`。
 *
 * B8（仅采纳「全部卡池」）的正确性是整个位置对齐方案的前提，这里显式覆盖它的判定边界：
 * 裸 `"0"`（老协议）、`"0@0"`（新协议全部卡池）都算全部卡池；`"0@3"`/`"0@4"`（活动/遴选
 * 汇总页）与具体池 `"30005@0"` 都不算。
 */
class ViewIdentityTest {

    @Test
    fun `make view joins pool and list type`() {
        assertEquals("0@0", ViewIdentity.makeView(0, 0))
        assertEquals("0@3", ViewIdentity.makeView(0, 3))
        assertEquals("30005@0", ViewIdentity.makeView(30005, 0))
        assertEquals("0", ViewIdentity.makeView(0, null))   // 老协议无 field3
        assertEquals("30005", ViewIdentity.makeView(30005, null))
    }

    @Test
    fun `split view round trips`() {
        assertEquals("0" to 3, ViewIdentity.splitView("0@3"))
        assertEquals("30005" to 0, ViewIdentity.splitView("30005@0"))
        assertEquals("0" to null, ViewIdentity.splitView("0"))
        assertEquals("" to null, ViewIdentity.splitView(""))
        assertEquals("" to null, ViewIdentity.splitView(null))
    }

    @Test
    fun `is all pools accepts bare zero and zero at all`() {
        assertTrue(ViewIdentity.isAllPools("0@0"))
        assertTrue(ViewIdentity.isAllPools("0"))       // 老协议回退
    }

    @Test
    fun `is all pools rejects contract pages and single pools`() {
        assertFalse(ViewIdentity.isAllPools("0@3"))    // 活动契约汇总页
        assertFalse(ViewIdentity.isAllPools("0@4"))    // 遴选契约汇总页
        assertFalse(ViewIdentity.isAllPools("30005@0"))
        assertFalse(ViewIdentity.isAllPools("30005"))
        assertFalse(ViewIdentity.isAllPools(null))
    }

    @Test
    fun `expected page offsets match measured totals`() {
        val p521 = ViewIdentity.expectedPageOffsets(521)
        assertEquals(105, p521.size)                   // 104×5 + 1
        assertEquals(0, p521.first())
        assertEquals(520, p521.last())
        assertEquals(listOf(0, 5, 10), ViewIdentity.expectedPageOffsets(12))

        val p792 = ViewIdentity.expectedPageOffsets(792)
        assertEquals(159, p792.size)                   // 158×5 + 2
        assertEquals(790, p792.last())
    }

    @Test
    fun `expected page offsets edge cases`() {
        assertTrue(ViewIdentity.expectedPageOffsets(0).isEmpty())
        assertTrue(ViewIdentity.expectedPageOffsets(-3).isEmpty())
        assertEquals(listOf(0), ViewIdentity.expectedPageOffsets(5))
        assertEquals(listOf(0), ViewIdentity.expectedPageOffsets(1))
        assertEquals(104, ViewIdentity.expectedPageOffsets(520).size)  // 整除：末页正好 5 条
        assertTrue(ViewIdentity.expectedPageOffsets(10, size = 0).isEmpty())
    }

    @Test
    fun `list type labels cover the three aggregate pages`() {
        assertEquals("全部卡池", ViewIdentity.LIST_TYPE_LABELS[0])
        assertEquals("活动契约", ViewIdentity.LIST_TYPE_LABELS[3])
        assertEquals("遴选契约", ViewIdentity.LIST_TYPE_LABELS[4])
        assertEquals(5, ViewIdentity.PAGE_SIZE)
    }
}
