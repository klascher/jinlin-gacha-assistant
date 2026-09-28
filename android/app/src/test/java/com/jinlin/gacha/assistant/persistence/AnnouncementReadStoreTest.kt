package com.jinlin.gacha.assistant.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AnnouncementReadStore] 单测 —— 对齐 PC `storage/announcements.py` 的读写契约：
 * `{"read": sorted([...])}` 排序写入、裸数组兼容、坏文件回空集、幂等标记不重复落盘。
 */
class AnnouncementReadStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = AnnouncementReadStore(tmp.root)

    // —— 读写 ——

    @Test
    fun `empty dir returns empty set`() {
        val store = newStore()
        assertTrue(store.readIds().isEmpty())
        assertFalse(store.isRead("x-1"))
    }

    @Test
    fun `markRead persists and reloads`() {
        newStore().markRead("b-2")
        // 新实例（模拟下次启动）从盘上读回
        val store2 = AnnouncementReadStore(tmp.root)
        assertTrue(store2.isRead("b-2"))
        assertEquals(setOf("b-2"), store2.readIds())
    }

    @Test
    fun `file layout matches PC exactly`() {
        newStore().markRead("b")
        newStore().markRead("a")
        // 排序写入，PC `{"read": sorted(read_ids)}` 同款（两侧都去空白后比对，不锁 pretty 格式）
        assertEquals("""{"read":["a","b"]}""", readNormalized())
    }

    @Test
    fun `markRead is idempotent`() {
        val store = newStore()
        store.markRead("x")
        store.markRead("x")
        assertEquals(setOf("x"), store.readIds())
    }

    @Test
    fun `blank id is ignored`() {
        val store = newStore()
        store.markRead("  ")
        assertTrue(store.readIds().isEmpty())
        assertFalse(tmp.root.resolve(AnnouncementReadStore.FILENAME).exists()) // 无有效标记时不落盘
    }

    // —— 兼容与容错（PC 同款宽容） ——

    @Test
    fun `reads bare array layout`() {
        tmp.newFile(AnnouncementReadStore.FILENAME).writeText("""["a", "b"]""", Charsets.UTF_8)
        val store = newStore()
        assertEquals(setOf("a", "b"), store.readIds())
    }

    @Test
    fun `numeric ids are coerced to strings`() {
        tmp.newFile(AnnouncementReadStore.FILENAME).writeText("""{"read": [1, 2]}""", Charsets.UTF_8)
        val store = newStore()
        assertEquals(setOf("1", "2"), store.readIds())
    }

    @Test
    fun `corrupt file falls back to empty set`() {
        tmp.newFile(AnnouncementReadStore.FILENAME).writeText("{not json", Charsets.UTF_8)
        val store = newStore()
        assertTrue(store.readIds().isEmpty())
        // 且后续 markRead 能把坏文件覆盖回来
        store.markRead("ok")
        assertTrue(AnnouncementReadStore(tmp.root).isRead("ok"))
    }

    @Test
    fun `wrong structure falls back to empty set`() {
        tmp.newFile(AnnouncementReadStore.FILENAME).writeText("""{"read": "oops"}""", Charsets.UTF_8)
        assertTrue(newStore().readIds().isEmpty())
    }

    // —— 辅助 ——

    /** 读盘去空白（encodePretty 的键值间空格不必逐字断言，语义等价即可）。 */
    private fun readNormalized(): String =
        tmp.root.resolve(AnnouncementReadStore.FILENAME)
            .readText(Charsets.UTF_8).replace(Regex("\\s+"), "")
}
