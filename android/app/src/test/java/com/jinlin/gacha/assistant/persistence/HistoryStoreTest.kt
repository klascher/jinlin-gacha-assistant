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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * [HistoryStore] 单测 —— 用临时目录对本类做纯 JVM 对拍（不需要 Context / Android 运行时）。
 *
 * 覆盖 PC `HistoryService` 的关键契约：
 * - payload schema（`records` / `view_totals` / `total`）读写往返，能与 PC 文件互通；
 * - 位置合并（新事件追加 / 重复事件跳过 / 部分事件被整体替换 / 位置断裂退化）；
 * - **内容幂等**：payload 与磁盘一致时不写盘、不备份（PC 6 分钟 17 个备份的教训）；
 * - `clear` 清空并留备份；无 total 快照的旧文件按条件回填；
 * - **备份列出 / 还原 / 裁剪**（2026-09-16）：`backups` 只认本账号的合规命名、同秒按 `_N` 排
 *   「新在前」（否则裁剪会误删最新的）、超 [HistoryStore.MAX_BACKUPS] 时裁剪；`restore` 覆盖前
 *   先另存当前、拒绝别的账号的备份；
 * - **裁剪语义（2026-09-17 二次改判）**：保留集 = **最近 N 份**（N = [HistoryStore.MAX_BACKUPS] = 30），
 *   并受总字节预算 [HistoryStore.BUDGET_BYTES]（20 MB）约束，**最新一份永不删**（软上限 + 保底）。
 *   曾短暂实现过「保留最早的非空备份作为**永久锚点**」，经用户澄清诉求是「回退到**出问题之前**」
 *   （= 最近的一个健康状态，本就在滚动窗口内）而非「回到最早」后**否决** —— 锚点只会白占一个槽位。
 *   决策抽成纯函数 [HistoryStore.keepSet]，可直接单测（20 MB 量级不适合靠写大文件触发）；
 * - **周期落盘不产生还原点**（2026-09-17，方案 A）：`merge(..., keepRestorePoint = false)`
 *   照常落盘但不留备份（`checkpointPersist` 走此路），主文件损坏时的保命备份不受该参数影响；
 * - **原子写**（2026-09-17，P1′）：主文件经 `<profileId>.json.tmp` + rename 落盘，不留半截 JSON。
 */
class HistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val usersDir: File get() = File(tmp.root, "users")

    private fun store() = HistoryStore(usersDir, "default")

    private fun names(): List<String> = (usersDir.listFiles() ?: emptyArray()).map { it.name }.sorted()

    private fun historyFile() = File(usersDir, "default.json")

    /** 备份文件名里的时间戳格式（与 `HistoryStore` 的 `TS_FMT` 同形）。 */
    private val stampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /** 造一份内容为「1 条记录」的合法备份文件。 */
    private fun writeOneRecordBackup(name: String): File {
        val one = """{"records":[{"pool_id":"0","item_id":"100","timestamp":1,"batch_seq":0,"position_in_batch":0}],"view_totals":{"0@0":1},"total":1}"""
        return File(usersDir, name).apply { writeText(one) }
    }

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
        // 契约（2026-09-17 二次改判）：裁剪 = **纯最近 N 份**（N = MAX_BACKUPS = 30）。
        // 曾实现的「最早非空备份 = 永久锚点」已否决：用户澄清诉求是「回退到**出问题之前**」——
        // 那是最近的一个健康状态，本就在滚动窗口内；锚点只会白占一个槽位（30 个位里占 1 个）。
        val store = store()
        val made = mutableListOf<File>()
        // 造出「比上限多 2 份」，多出来的那 2 份必须被裁掉
        repeat(HistoryStore.MAX_BACKUPS + 2) { i ->
            store.merge(tenPull(2000L + i * 1000L), mapOf("0@0" to (i + 1) * 10))
            store.clear()?.let { made += it } // 每轮清空生成 1 份（made 按生成顺序：旧 → 新）
        }
        assertEquals(HistoryStore.MAX_BACKUPS + 2, made.size)

        val kept = store.backups().map { it.file.name }.toSet()
        assertEquals(HistoryStore.MAX_BACKUPS, kept.size)
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

    // —— 周期落盘不得挤占还原点（2026-09-17，方案 A + C + P1′） ——

    @Test
    fun `merge with keepRestorePoint false still persists but creates no restore point`() {
        val store = store()
        // 周期落盘的语义：只写历史、不留备份
        assertEquals(10, store.merge(tenPull(2000), mapOf("0@0" to 10), keepRestorePoint = false))

        assertEquals(listOf("default.json"), names())
        assertEquals(10, store().records.size) // 已落盘 ——「进程被杀不丢数据」的兜底不受影响
        assertEquals(10, store().lastTotal)
    }

    @Test
    fun `periodic checkpoints do not evict the user restore point`() {
        val store = store()
        store.merge(tenPull(2000), mapOf("0@0" to 10))
        val userPoint = store.clear()!! // 用户「清空前」产生的还原点：10 条
        assertEquals(1, store.backups().size)

        // 模拟 12 次 5 秒周期落盘（≈60 秒抓包），每轮都有真实新增
        for (i in 1..12) {
            store.merge(
                tenPull(10_000L + i * 1000L),
                mapOf("0@0" to 10 * (i + 1)),
                keepRestorePoint = false,
            )
        }

        val list = store.backups()
        assertEquals("周期落盘不得产生还原点", 1, list.size)
        assertEquals(userPoint.name, list[0].file.name)
        assertEquals(10, list[0].records)
        assertEquals("12 轮新增一条都不能丢", 120, store.records.size)
        assertEquals(120, store().records.size) // 且已落盘
    }

    // —— 裁剪决策纯函数（keepSet）——
    // 预算 20 MB 的量级不适合「写大文件触发裁剪」的集成测法，故决策逻辑单列成纯函数直接测：
    // 传入尺寸即可精确构造「只有预算超」「单份就超预算」这些边界，且零磁盘代价。

    @Test
    fun `keepSet cuts on the byte budget when the count limit is not reached`() {
        // 3 份 8 MB + 1 份刚生成的小份：份数（4）≪ 30，但总量 24 MB > 20 MB ⇒ 只有预算会触发裁剪
        val mb8 = 8L * 1024 * 1024
        val newest = File(usersDir, "d.json") // 第 0 项 = 刚生成的那份
        val f3 = File(usersDir, "c.json")
        val f2 = File(usersDir, "b.json")
        val f1 = File(usersDir, "a.json") // 最旧
        val entries = listOf(newest to 200L, f3 to mb8, f2 to mb8, f1 to mb8) // 新 → 旧

        val keep = HistoryStore.keepSet(entries, HistoryStore.MAX_BACKUPS, HistoryStore.BUDGET_BYTES)

        // 收到 f1 时 200 + 8MB×3 = 24 MB > 20 MB ⇒ 停手，只留 3 份（≈16 MB）
        assertEquals(setOf(newest, f3, f2), keep)
        assertFalse("触发预算时最旧的一份应被裁掉", f1 in keep)
    }

    @Test
    fun `keepSet keeps the newest even when it alone exceeds the budget`() {
        // 单份 > 20 MB（现实中需 >13 万条记录，此处直接构造）：**保底 1 份**，接受超预算
        val huge = 30L * 1024 * 1024
        val newest = File(usersDir, "new.json")
        val old = File(usersDir, "old.json")

        val keep = HistoryStore.keepSet(
            listOf(newest to huge, old to 1L),
            HistoryStore.MAX_BACKUPS,
            HistoryStore.BUDGET_BYTES,
        )

        // 若预算严格生效，刚生成的这份会被自己立刻删掉 ⇒ 每次清空 / 停抓都白做、还原点列表永远为空
        assertEquals(setOf(newest), keep)
    }

    @Test
    fun `keepSet keeps at most maxBackups when the budget allows more`() {
        val entries = (1..5).map { File(usersDir, "f$it.json") to 1L } // 新 → 旧，都很小

        val keep = HistoryStore.keepSet(entries, 3, HistoryStore.BUDGET_BYTES)

        assertEquals(entries.take(3).map { it.first }.toSet(), keep)
    }

    @Test
    fun `restore does not prune away the target restore point`() {
        // 目标必须落在保留**窗口之外**才验得出 protected 的作用（上限已由 5 提到 30 ⇒ 得造 30+ 份），
        // 故把目标放在**最旧**的位置：没有 protected 时它会被最先裁掉。
        usersDir.mkdirs()
        val base = LocalDateTime.of(2026, 2, 1, 0, 0, 0)
        for (i in 1..HistoryStore.MAX_BACKUPS) {
            writeOneRecordBackup("default_${base.plusMinutes(i.toLong()).format(stampFmt)}.json")
        }
        val target = writeOneRecordBackup("default_20260101_000000.json") // 最旧

        val store = store()
        store.merge(tenPull(9000), mapOf("0@0" to 10))
        assertNotNull(store.restore(target))

        assertTrue("刚用过的还原点不能被裁掉", target.exists())
        assertEquals(1, store.records.size) // 历史确已回到目标内容
    }

    @Test
    fun `corrupt history file is rescued even when restore points are off`() {
        usersDir.mkdirs()
        historyFile().writeText("{ not json", Charsets.UTF_8)
        val store = store() // 损坏 → 视作空历史（不抛、不删原文件）
        assertTrue(store.isEmpty)

        store.merge(tenPull(2000), mapOf("0@0" to 10), keepRestorePoint = false)

        // 保命优先：损坏文件必须完整留档，不受「周期落盘不留还原点」影响
        val rescued = store.backups()
        assertEquals(1, rescued.size)
        assertEquals("{ not json", rescued[0].file.readText(Charsets.UTF_8))

        // 且只留一次：主文件已被改写成合法 payload，下一轮不再造备份
        store.merge(tenPull(9000), mapOf("0@0" to 20), keepRestorePoint = false)
        assertEquals(1, store.backups().size)
    }

    @Test
    fun `atomic write leaves no temp file behind`() {
        val store = store()
        // 两次都走「不留还原点」，第二次写时 default.json 已存在 → 覆盖路径也要走原子写
        store.merge(tenPull(2000), mapOf("0@0" to 10), keepRestorePoint = false)
        store.merge(tenPull(9000), mapOf("0@0" to 20), keepRestorePoint = false)

        assertEquals("不允许多出 .tmp 残留", listOf("default.json"), names())
        assertEquals(20, store().records.size)
        assertEquals(20, store().lastTotal)
    }

    /**
     * ★ 2026-09-19 实机反馈修复：**「上次 N 条」必须是本场净增**，不能被周期落盘吃掉。
     *
     * 抓包路径每 5 秒 `checkpointPersist()` 就 merge 一次（把本场记录并进历史），收尾再 merge 时
     * 同一批记录**全部被判重跳过** ⇒ 末次 merge 的返回值恒为 0。若场次明细直接用它，
     * 记录页「上次」永远是 `0 条`（用户实测：明明新增了 1 页数据却显示 0 条）。
     *
     * ⇒ 传 `sessionBaselineRecords` 后，明细取「收尾后总条数 − 会话起点条数」。
     */
    @Test
    fun `session detail added is net growth across checkpoint and final merge`() {
        val store = store()
        store.merge(tenPull(1000), mapOf("0@0" to 10))
        val baseline = store.records.size
        assertEquals(10, baseline)

        // 本场收下的记录（模拟「又抽了一发十连」）
        val session = tenPull(9000, firstItem = 200)

        // ① 周期落盘（每 5 秒一次，`keepRestorePoint = false`）：先把本场记录并进历史
        assertEquals(10, store.merge(session, keepRestorePoint = false))

        // ② 收尾：同一批记录全部判重跳过 —— 所以**末次 merge 的返回值是 0**
        val lastMergeAdded = store.merge(
            session,
            captureStartedAt = "2026-09-19T12:00:00",
            captureEndedAt = "2026-09-19T12:05:00",
            sessionBaselineRecords = baseline,
        )
        assertEquals("末次 merge 确实一条都加不进去（这正是原 bug 的来源）", 0, lastMergeAdded)

        // ③ 但「上次」必须记 10（本场净增），而不是 0
        assertEquals(10, store.captureDetail?.added)
        assertEquals(20, store.records.size)
        // 落盘往返：明细随历史一起写，重开 App 读回来还得是 10
        assertEquals(10, store().captureDetail?.added)
        assertEquals("2026-09-19T12:00:00", store().captureDetail?.startedAt)
    }

    /**
     * 不传 `sessionBaselineRecords` 时**退回旧行为**（= 本次 merge 的新增）——
     * 单次 merge 到底的调用方（单测 / 单次导入）语义不变，不会因这次修复而漂移。
     */
    @Test
    fun `session detail falls back to single merge added when no baseline given`() {
        val store = store()
        store.merge(
            tenPull(2000),
            captureStartedAt = "2026-09-19T12:00:00",
            captureEndedAt = "2026-09-19T12:05:00",
        )
        assertEquals(10, store.captureDetail?.added)
    }
}
