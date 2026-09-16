package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.dedup.MiniJson
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
 * [ProfileStore] 单测 —— 临时目录纯 JVM 对拍。
 *
 * 覆盖 PC `storage/profiles.py::ProfileManager` 的关键契约：
 * - 首次启动自动建「默认账号」；id 为 12 位 hex（不含下划线，避免与归档名歧义）；
 * - `profiles.json` 结构与 PC 一致（`active_id` + `order` + `profiles` **字典**）；
 * - 新建 / 重命名（空白忽略）/ 删除（归档 `<id>_<stamp>.json`、**拒绝删最后一个**、
 *   删当前账号回退首个）/ 切换；
 * - 顺序保持；损坏注册表重建；active 落盘往返。
 */
class ProfileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ProfileStore(tmp.root)

    private fun registry() = File(tmp.root, ProfileStore.REGISTRY_NAME)

    private fun usersDir() = File(tmp.root, ProfileStore.USERS_DIR)

    private fun userFile(id: String) = File(usersDir(), "$id.json")

    // —— bootstrap ——

    @Test
    fun `first launch bootstraps a default account`() {
        val s = store()
        assertEquals(1, s.list().size)
        assertEquals(ProfileStore.DEFAULT_NAME, s.list()[0].name)
        assertEquals(s.list()[0].id, s.active)
        assertTrue(registry().exists())
    }

    @Test
    fun `generated id is 12 lowercase hex chars`() {
        val id = store().active
        assertEquals(12, id.length)
        assertTrue("$id 应为小写 hex", id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `id has no underscore or hyphen`() {
        // 归档名是 "<id>_<stamp>.json"，id 含下划线会产生歧义（PC 注释同此）
        val id = store().active
        assertFalse(id.contains("_"))
        assertFalse(id.contains("-"))
        assertEquals(ProfileStore.USERS_DIR, "users")
    }

    // —— 结构兼容（互通硬约束）——

    @Test
    fun `registry matches pc schema`() {
        val s = store()
        s.create("小号")

        val text = registry().readText(Charsets.UTF_8)
        val root = MiniJson.decode(text) as Map<*, *>

        assertTrue(root.containsKey("active_id"))
        assertTrue(root["order"] is List<*>)
        assertTrue("profiles 必须是字典（PC 结构），不是数组", root["profiles"] is Map<*, *>)
        assertEquals(2, (root["order"] as List<*>).size)

        val profiles = root["profiles"] as Map<*, *>
        val one = profiles.values.first() as Map<*, *>
        assertTrue(one.containsKey("id"))
        assertTrue(one.containsKey("name"))
        assertTrue(one.containsKey("created_at"))
    }

    // —— 变更 ——

    @Test
    fun `create appends and does not switch active`() {
        val s = store()
        val first = s.active
        val created = s.create("小号")
        assertEquals(2, s.list().size)
        assertEquals("小号", created.name)
        assertEquals("新建不自动切换当前账号（对齐 PC）", first, s.active)
        assertEquals(listOf(first, created.id), s.list().map { it.id })
    }

    @Test
    fun `create with blank name uses default name`() {
        assertEquals(ProfileStore.DEFAULT_NAME, store().create("   ").name)
    }

    @Test
    fun `rename changes name and ignores blank`() {
        val s = store()
        val id = s.create("旧名").id
        s.rename(id, "新名")
        assertEquals("新名", s.list().first { it.id == id }.name)

        s.rename(id, "   ")
        assertEquals("新名", s.list().first { it.id == id }.name)
    }

    @Test
    fun `delete archives user file and removes from registry`() {
        val s = store()
        val first = s.active
        val second = s.create("小号").id
        userFile(second).writeText("{\"records\":[]}", Charsets.UTF_8)
        assertTrue(userFile(second).exists())

        val archive = s.delete(second)
        assertNotNull(archive)
        assertTrue(archive!!.exists())
        assertTrue(
            "归档名应为 <id>_<yyyyMMdd_HHmmss>.json，实际 ${archive.name}",
            archive.name.matches(Regex("${second}_\\d{8}_\\d{6}\\.json")),
        )
        assertFalse("原记录文件应已移走", userFile(second).exists())
        assertEquals(1, s.list().size)
        assertEquals(first, s.active)
    }

    @Test
    fun `delete last account is refused`() {
        val s = store()
        assertNull(s.delete(s.active))
        assertEquals(1, s.list().size)
    }

    @Test
    fun `deleting active account falls back to first`() {
        val s = store()
        val first = s.active
        val second = s.create("小号").id
        s.setActive(second)
        assertEquals(second, s.active)

        s.delete(second)
        assertEquals(first, s.active)
    }

    @Test
    fun `set active ignores unknown id`() {
        val s = store()
        val first = s.active
        s.setActive("not_exists")
        assertEquals(first, s.active)
    }

    // —— 持久化 ——

    @Test
    fun `active and order persist across reload`() {
        val s = store()
        val a = s.create("A").id
        val b = s.create("B").id
        s.setActive(b)

        val reloaded = store()
        assertEquals(b, reloaded.active)
        assertEquals(3, reloaded.list().size)
        assertEquals(listOf(a, b), reloaded.list().drop(1).map { it.id })
    }

    @Test
    fun `corrupt registry rebuilds default account`() {
        registry().writeText("{ not json", Charsets.UTF_8)
        val s = store()
        assertEquals(1, s.list().size)
        assertEquals(ProfileStore.DEFAULT_NAME, s.list()[0].name)
    }

    @Test
    fun `missing order is rebuilt from profiles`() {
        // 手写一个只有 profiles 的注册表（模拟 PC 早期版本/手改）
        usersDir().mkdirs()
        registry().writeText(
            """
            {"active_id":"","profiles":{"abc123":{"id":"abc123","name":"甲","created_at":""},
             "def456":{"id":"def456","name":"乙","created_at":""}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val s = store()
        assertEquals(2, s.list().size)
        assertEquals("active 失效应回退首个", "abc123", s.active)
    }
}
