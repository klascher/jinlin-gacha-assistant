package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.GachaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [HistoryStore] 单测 —— 用临时目录对本类做纯 JVM 对拍（不需要 Context / Android 运行时）。
 *
 * 覆盖 PC `HistoryService` 的关键契约：
 * - payload schema（`records` / `view_totals` / `total`）读写往返，能与 PC 文件互通；
 * - 位置合并（新事件追加 / 重复事件跳过 / 部分事件被整体替换 / 位置断裂退化）；
 * - **内容幂等**：payload 与磁盘一致时不写盘、不备份（PC 6 分钟 17 个备份的教训）；
 * - `clear` 清空并留备份；无 total 快照的旧文件按条件回填；
 * - **备份列出 / 还原 / 裁剪**（2026-09-16）：`backups` 只认本账号的合规命名、同秒按 `_N` 排
 *   「新在前」（否则裁剪会误删最新的）、超 [HistoryStore.MAX_BACKUPS] 裁旧；`restore` 覆盖前
 *   先另存当前、拒绝别的账号的备份。
 */
class HistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val usersDir: File get() = File(tmp.root, "users")

    private fun store() = HistoryStore(usersDir, "default")

    private fun names(): List<String> = (usersDir.listFiles() ?: emptyArray()).map { it.name }.sorted()

    private fun historyFile() = File(usersDir, "default.json")

    /** 一个完整十连（ts 共享，位置 0..9）。 */
    private fun tenPull(ts: Long, pool: String = "0", firstItem: Int = 100): List<GachaRecord> =
        (0 until 10).map { GachaRecord(pool, (firstItem + it).toString(), ts, 0, it) }

    // —— 加载 ——

    @Test
    fun `missing file yields empty store`() {
        val store = store()
        assertTrue(store.isEmpty)
        assertEquals(0, store.records.size)
        assertNull(store.lastTotal)
        assertTrue(store.viewTotals.isEmpty())
    }

    @Test
    fun `loads pc format file`() {
        usersDir.mkdirs()
        historyFile().writeText(
            """
            {
              "records": [
                {"pool_id": "0", "item_id": "100", "timestamp": 1700000000000,
                 "batch_seq": 0, "position_in_batch": 0}
              ],
              "view_totals": {"0@0": 521},
              "total": 521
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val store = store()
        assertEquals(1, store.records.size)
        assertEquals("0", store.records[0].poolId)
        assertEquals(1700000000000L, store.records[0].timestamp)
        assertEquals(521, store.lastTotal)
        assertEquals(mapOf("0@0" to 521), store.viewTotals)
    }

    @Test
    fun `corrupt file is treated as empty and not deleted`() {
        usersDir.mkdirs()
        historyFile().writeText("{ this is not json", Charsets.UTF_8)
        val store = store()
        assertTrue(store.isEmpty)
        assertTrue(historyFile().exists()) // 不删原文件，留给人工排查
    }

    @Test
    fun `backfills total when all events complete and no total snapshot`() {
        usersDir.mkdirs()
        val recs = tenPull(1000).joinToString(",\n") {
            """{"pool_id": "${it.poolId}", "item_id": "${it.itemId}", "timestamp": ${it.timestamp}, """ +
                """"batch_seq": 0, "position_in_batch": ${it.positionInBatch}}"""
        }
        historyFile().writeText("""{"records": [$recs]}""", Charsets.UTF_8)
        val store = store()
        assertEquals(10, store.records.size)
        assertEquals(10, store.lastTotal) // 事件全完整 → 条数即权威总数
    }

    @Test
    fun `does not backfill total when a partial event exists`() {
        usersDir.mkdirs()
        val recs = tenPull(1000).take(5).joinToString(",\n") {
            """{"pool_id": "${it.poolId}", "item_id": "${it.itemId}", "timestamp": ${it.timestamp}, """ +
                """"batch_seq": 0, "position_in_batch": ${it.positionInBatch}}"""
        }
        historyFile().writeText("""{"records": [$recs]}""", Charsets.UTF_8)
        assertNull(store().lastTotal)
    }

    // —— 合并 ——

    @Test
    fun `merge new session persists and reloads`() {
        val store = store()
        assertEquals(10, store.merge(tenPull(2000), mapOf("0@0" to 10)))
        assertFalse(store.isEmpty)
        assertEquals(10, store.records.size)
        assertEquals(10, store.lastTotal)
        assertEquals(mapOf("0@0" to 10), store.viewTotals)

        // 新实例从磁盘读回同一份
        val reloaded = store()
        assertEquals(10, reloaded.records.size)
        assertEquals(10, reloaded.lastTotal)
    }

    @Test
    fun `merge is content idempotent and does not spam backups`() {
        val store = store()
        val ten = tenPull(2000)
        assertEquals(10, store.merge(ten, mapOf("0@0" to 10)))
        assertEquals(listOf("default.json"), names())

        // 再合并同一事件：事件级判重 → 0 新增；payload 与磁盘逐结构一致 → 不写盘、不备份
        assertEquals(0, store.merge(ten, mapOf("0@0" to 10)))
        assertEquals(listOf("default.json"), names())

        // 有新内容 → 写前先备份
        assertEquals(10, store.merge(tenPull(1000, firstItem = 200), mapOf("0@0" to 20)))
        assertEquals(2, names().size)
        assertTrue(names().any { it.matches(Regex("default_\\d{8}_\\d{6}\\.json")) })
        assertEquals(20, store.records.size)
    }

    @Test
    fun `partial history event replaced by complete one`() {
        val store = store()
        // 历史：ts=3000 只有 3 条且位置在旧坐标系（100/101/102）——B8 前遗留的形态
        val partial = (0..2).map { GachaRecord("0", (500 + it).toString(), 3000L, 0, 100 + it) }
        store.merge(partial, mapOf("0@0" to 3))
        assertEquals(3, store.records.size)

        // 新完整事件（10 条，位置 0..9）零命中历史位置 → 整体替换
        assertEquals(10, store.merge(tenPull(3000), mapOf("0@0" to 13)))
        assertEquals(10, store.records.size) // 旧 3 条被移除，不是 13
        assertNotNull(store.lastReport)
        assertEquals(listOf(3000L), store.lastReport!!.replacedEvents)
    }

    @Test
    fun `merge without additions still persists total snapshot`() {
        val store = store()
        store.merge(tenPull(2000), mapOf("0@0" to 10))
        // 无新增，仅 total 变化 → 仍落盘（否则下次按旧基准推 Δ 会整体错位）
        assertEquals(0, store.merge(emptyList(), mapOf("0@0" to 25)))
        assertEquals(25, store.lastTotal)
        assertEquals(25, store().lastTotal)
    }

    // —— 清空 ——

    @Test
    fun `clear empties and keeps a backup`() {
        val store = store()
        store.merge(tenPull(2000), mapOf("0@0" to 10))
        val backup = store.clear()
        assertNotNull(backup)
        assertTrue(backup!!.exists())
        assertTrue(store.isEmpty)
        assertNull(store.lastTotal)
        assertEquals(0, store().records.size)
        // 备份里仍是清空前的内容
        assertTrue(backup.readText(Charsets.UTF_8).contains("\"records\""))
    }

    @Test
    fun `clear on empty store writes empty payload`() {
        val store = store()
        assertNull(store.clear()) // 磁盘上无有效历史 → 不产生备份
        assertTrue(historyFile().exists())
        assertTrue(store.isEmpty)
    }

    // —— 备份列出 / 还原 / 裁剪（2026-09-16） ——

    @Test
    fun `backups lists only conforming files of this account`() {
        val store = store()
        store.merge(tenPull(2000), mapOf("0@0" to 10))
        store.clear()
        // 干扰项：后缀不符 / 别的账号的备份。两者都不得进入本账号还原点列表。
        File(usersDir, "default_20260101_000000.txt").writeText("x")
        File(usersDir, "other_20260101_000000.json").writeText("{}")

        val list = store.backups()
        assertEquals(1, list.size)
        assertEquals(10, list[0].records)
        assertTrue(list[0].file.name.startsWith("default_"))
    }

    @Test
    fun `backup label carries human readable time and record count`() {
        val store = store()
        store.merge(tenPull(2000), mapOf("0@0" to 10))
        store.clear()

        val label = store.backups()[0].label
        assertTrue("label 应含人类可读时间与条数，实际 $label", label.contains("（10 条）"))
        assertTrue(
            "label 时间格式应为 yyyy-MM-dd HH:mm:ss，实际 $label",
            label.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}（10 条）.*""")),
        )
    }

    @Test
    fun `backups puts newest first`() {
        val store = store()
        store.merge(tenPull(2000), mapOf("0@0" to 10))
        val older = store.clear()!!
        store.merge(tenPull(9000), mapOf("0@0" to 20))
        val newer = store.clear()!!

        val list = store.backups()
        assertEquals(2, list.size)
        assertEquals(newer.name, list[0].file.name)
        assertEquals(older.name, list[1].file.name)
    }

    @Test
    fun `backups orders same-second backups by _N suffix`() {
        // 手工造同秒三份（走 clear 会依赖「两次调用落在同一秒」的巧合，断言会 flaky）
        usersDir.mkdirs()
        val ts = "20260916_191737"
        val empty = """{"records":[],"view_totals":{},"total":null}"""
        File(usersDir, "default_$ts.json").writeText(empty) // 最早
        File(usersDir, "default_${ts}_1.json").writeText(empty)
        File(usersDir, "default_${ts}_2.json").writeText(empty) // 最新

        val names = store().backups().map { it.file.name }

        // 三份时间戳完全相同 → 必须靠 `_N` 降序定序，否则退化成目录枚举顺序，
        // pruneBackups 会把最新的当最旧的删掉。
        assertEquals(
            listOf("default_${ts}_2.json", "default_${ts}_1.json", "default_$ts.json"),
            names,
        )
    }

    @Test
    fun `backups are pruned to MAX_BACKUPS keeping the newest`() {
        val store = store()
        val made = mutableListOf<File>()
        repeat(7) { i ->
            store.merge(tenPull(2000L + i * 1000L), mapOf("0@0" to (i + 1) * 10))
            store.clear()?.let { made += it } // 每轮清空生成 1 份备份 → 共 7 份
        }
        assertEquals(7, made.size)

        val kept = store.backups().map { it.file.name }.toSet()
        assertEquals(HistoryStore.MAX_BACKUPS, kept.size)
        // 保留的必须是「最近的」：最后 N 份都在，更早的都被裁掉
        made.takeLast(HistoryStore.MAX_BACKUPS).forEach {
            assertTrue("最近的备份应保留：${it.name}", it.name in kept)
        }
        made.dropLast(HistoryStore.MAX_BACKUPS).forEach {
            assertFalse("更旧的备份应被裁掉：${it.name}", it.name in kept)
        }
    }

    @Test
    fun `restore overwrites history and keeps current as a backup`() {
        val store = store()
        store.merge(tenPull(2000), mapOf("0@0" to 10))
        val target = store.clear()!! // 还原点里是 ts=2000 的那 10 条
        store.merge(tenPull(9000), mapOf("0@0" to 20))
        assertEquals(9000L, store.records.first().timestamp)

        val current = store.restore(target)

        // 还原前的状态被另存（可反复还原）
        assertNotNull(current)
        assertTrue(current!!.exists())
        // 内存与磁盘都已回到还原点内容
        assertEquals(2000L, store.records.first().timestamp)
        assertEquals(2000L, store().records.first().timestamp)
    }

    @Test
    fun `restore rejects a backup belonging to another account`() {
        val store = store()
        usersDir.mkdirs()
        val foreign = File(usersDir, "other_20260101_000000.json")
        foreign.writeText("{}")

        val ex = runCatching { store.restore(foreign) }.exceptionOrNull()
        assertTrue("应拒绝别的账号的备份，实际 $ex", ex is IllegalArgumentException)
    }
}
