package com.jinlin.gacha.assistant.persistence

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [StorageCleaner] 单测 —— 临时目录 + 纯 JVM（不需要 Context / Android 运行时）。
 *
 * 覆盖「数据清理」子页数据层的四条底线：
 * - **五类识别**：备份 / 录包 pcap / 诊断包 / 元数据缓存 / App 日志都能扫到，且备份带账号名与记录条数
 *   （UI 跨账号展示要用）；
 * - **孤儿存档不入列表**：已删账号的归档与备份**完全同形**（`ProfileStore.uniqueArchive` 与备份
 *   命名一致），但它是该账号数据的唯一副本 ⇒ 删掉即永久丢失，故按注册表 id 前缀天然排除；
 * - **权威数据拒绝删除**：即便调用方（或未来某处代码）把 `profiles.json` / `settings.json` /
 *   `users/<id>.json`（记录本体）塞进待删列表，[StorageCleaner.delete] 也必须拒绝；
 * - **目录穿越拒绝**：`users/../profiles.json` 这类路径经 `canonicalPath` 解析后不在允许目录内，
 *   必须拒绝。
 */
class StorageCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir: File get() = File(tmp.root, "files").apply { mkdirs() }
    private val externalDir: File get() = File(tmp.root, "ext").apply { mkdirs() }

    private fun cleaner() = StorageCleaner(filesDir, externalDir)

    private val usersDir: File get() = File(filesDir, "users")

    /** 写文件（自动建父目录）。 */
    private fun write(f: File, content: String = "{}"): File {
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    /** 造一份含 [n] 条记录、符合 schema 的备份 JSON。 */
    private fun backupJson(n: Int): String {
        val recs = (0 until n).joinToString(",") {
            """{"pool_id":"0","item_id":"1$it","timestamp":${1000 + it},"batch_seq":0,"position_in_batch":$it}"""
        }
        return """{"records":[$recs],"view_totals":{"0@0":$n},"total":$n}"""
    }

    private val accountId = "abcdef123456"
    private val names = mapOf(accountId to "默认账号")

    // —— ① 四类识别 ——

    @Test
    fun `scan finds all four kinds with account name and record count`() {
        write(File(usersDir, "${accountId}_20260917_110000.json"), backupJson(3))
        write(File(File(externalDir, "records"), "full_20260917_110000.pcap"), "x")
        write(File(File(externalDir, "diagnose"), "dump_gap_20260917_110001.pcap"), "x")
        write(File(File(filesDir, "cache"), "role_cache.json"), "{}")

        val cats = cleaner().scan(names).associateBy { it.kind }

        assertEquals(1, cats.getValue(CleanKind.BACKUP).count)
        assertEquals(1, cats.getValue(CleanKind.CAPTURE_PCAP).count)
        assertEquals(1, cats.getValue(CleanKind.DIAG_PCAP).count)
        assertEquals(1, cats.getValue(CleanKind.META_CACHE).count)

        // 备份必须带账号名与条数 —— 本页是**跨账号**治理，列表要标明是谁的
        val b = cats.getValue(CleanKind.BACKUP).items[0]
        assertEquals("默认账号", b.accountName)
        assertEquals(3, b.records)

        // pcap 从文件名解出时刻与触发原因（诊断包的 `dump_` 前缀已剥掉）
        assertEquals("full", cats.getValue(CleanKind.CAPTURE_PCAP).items[0].reason)
        assertEquals("gap", cats.getValue(CleanKind.DIAG_PCAP).items[0].reason)
        assertTrue(cats.getValue(CleanKind.DIAG_PCAP).items[0].timestamp != null)
    }

    // —— ② 尺寸统计 / 空分类 ——

    @Test
    fun `scan reports sizes and empty categories`() {
        val b1 = write(File(usersDir, "${accountId}_20260917_110000.json"), backupJson(2))
        write(File(File(externalDir, "records"), "full_20260917_110000.pcap"), "0123456789")

        val cats = cleaner().scan(names).associateBy { it.kind }

        assertEquals(b1.length(), cats.getValue(CleanKind.BACKUP).bytes)
        assertEquals(10L, cats.getValue(CleanKind.CAPTURE_PCAP).bytes)
        // 目录不存在 / 无文件 → 空分类（UI 显示「无文件」并置灰）
        assertTrue(cats.getValue(CleanKind.DIAG_PCAP).isEmpty)
        assertTrue(cats.getValue(CleanKind.META_CACHE).isEmpty)
        assertEquals(0L, cats.getValue(CleanKind.DIAG_PCAP).bytes)
    }

    // —— ③ 孤儿存档不入列表 ——

    @Test
    fun `scan excludes archives of deleted accounts`() {
        // 两者**完全同形** —— 唯一区别是孤儿那份的 id 已不在注册表里。
        // 孤儿是该账号数据的唯一副本，删掉即永久丢失，故 v1 明确不收（09 §15）。
        write(File(usersDir, "${accountId}_20260917_110000.json"), backupJson(2))
        write(File(usersDir, "deadbeef0000_20260917_110000.json"), backupJson(9))

        val items = cleaner().scan(names).first { it.kind == CleanKind.BACKUP }.items

        assertEquals(1, items.size)
        assertEquals("${accountId}_20260917_110000.json", items[0].file.name)
    }

    // —— ④ 权威数据拒绝删除 ——

    @Test
    fun `delete refuses authoritative data even when passed in`() {
        // 「永不删清单」四项 + **记录本体**（users/<id>.json，无时间戳段，不匹配备份规约）
        val never = listOf(
            File(filesDir, "profiles.json"),
            File(filesDir, "settings.json"),
            File(filesDir, "char_shields.json"),
            File(filesDir, "pool_assignments.json"),
            File(usersDir, "$accountId.json"),
        )
        never.forEach { write(it, """{"k":1}""") }
        val victim = write(File(usersDir, "${accountId}_20260917_110000.json"), backupJson(1))

        // 模拟调用方出错：把权威数据也混进待删列表
        val items = never.map { CleanItem(it, it.length(), CleanKind.BACKUP) } +
            CleanItem(victim, victim.length(), CleanKind.BACKUP)

        val result = cleaner().delete(items)

        assertEquals("只应删掉那一份合法备份", 1, result.deleted)
        assertEquals("其余全部拒绝", never.size, result.failed)
        assertTrue("应真的释放了字节", result.freed > 0)
        never.forEach { assertTrue("权威数据不得被删：${it.name}", it.exists()) }
        assertFalse("合法备份应被删掉", victim.exists())
    }

    // —— ⑤ 目录穿越拒绝 ——

    @Test
    fun `delete refuses path traversal outside the allowed dir`() {
        val secret = write(File(filesDir, "profiles.json"), """{"k":1}""")
        write(File(usersDir, ".keep"), "") // 保证 users/ 存在
        // `users/../profiles.json` 经 canonicalPath 解析后落在 users/ 之外 ⇒ 必须拒绝
        val sneaky = File(usersDir, "../profiles.json")

        val result = cleaner().delete(listOf(CleanItem(sneaky, 9L, CleanKind.BACKUP)))

        assertEquals(0, result.deleted)
        assertEquals(1, result.failed)
        assertTrue("穿越路径不得删到 users/ 之外", secret.exists())
    }

    // —— ⑥ App 日志（2026-09-22 新增：第五类）——

    @Test
    fun `scan finds app logs and ignores names off the convention`() {
        write(File(File(externalDir, "applog"), "app-20260920.txt"), "1")
        write(File(File(externalDir, "applog"), "app-20260922.txt"), "12")
        // 名字不合规 ⇒ 不入列表（`app-notes.txt` 缺 8 位日期；`app-20260922.log` 后缀不符）
        write(File(File(externalDir, "applog"), "app-notes.txt"), "x")
        write(File(File(externalDir, "applog"), "app-20260922.log"), "x")

        val cat = cleaner().scan(names).first { it.kind == CleanKind.APP_LOG }

        assertEquals(2, cat.count)
        assertEquals(3L, cat.bytes)
        // 新在前（与备份/诊断包同一排序口径），时刻由文件名解出
        assertEquals("app-20260922.txt", cat.items[0].file.name)
        assertEquals("app-20260920.txt", cat.items[1].file.name)
        assertEquals(LocalDate.of(2026, 9, 20), cat.items[1].timestamp?.toLocalDate())
    }

    @Test
    fun `delete app log outside the applog dir is refused`() {
        val inside = write(File(File(externalDir, "applog"), "app-20260901.txt"), "abcd")
        // 同一条名规约、却落在别的目录 ⇒ 必须被「允许目录直接子项」这一闸拦下
        val outside = write(File(File(externalDir, "records"), "app-20260901.txt"), "abcd")

        val result = cleaner().delete(
            listOf(
                CleanItem(inside, inside.length(), CleanKind.APP_LOG),
                CleanItem(outside, outside.length(), CleanKind.APP_LOG),
            ),
        )

        assertEquals(1, result.deleted)
        assertEquals(1, result.failed)
        assertFalse(inside.exists())
        assertTrue("同名规约落在别的目录必须拒绝", outside.exists())
    }
}
