package com.jinlin.gacha.assistant.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [UnknownIdStore] 单测 —— 临时目录纯 JVM 对拍（不需要 Context / Android 运行时）。
 *
 * 覆盖 PC `char_shields.py` / `pool_assignments.py` 的存储契约：
 * - 缺文件 → 空屏蔽 + 空指派；
 * - 屏蔽/指派写入即可见且落盘，重启（新实例）从磁盘恢复；
 * - 清除指派（空串）→ 回独立并从文件移除；
 * - payload 用 PC 同款键名（`shielded` / `assignments`）。
 */
class UnknownIdStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = UnknownIdStore(tmp.root)

    @Test
    fun `missing files yield empty snapshot`() {
        val s = store()
        assertTrue(s.state.value.shielded.isEmpty())
        assertTrue(s.state.value.assignments.isEmpty())
        assertFalse(s.isShielded("9999"))
        assertNull(s.assignmentOf("888"))
    }

    @Test
    fun `toggle shield and assignment persist across restart`() {
        val s = store()
        s.toggleShield("9999")
        s.toggleShield("9999") // 两次 = 取消（回到未屏蔽）
        assertFalse(s.isShielded("9999"))

        // 重新屏蔽 + 指派
        s.toggleShield("9999")
        s.setPoolAssignment("888", "g_up")
        assertTrue(s.isShielded("9999"))
        assertEquals("g_up", s.assignmentOf("888"))
        assertEquals(setOf("9999"), s.state.value.shielded)
        assertEquals(mapOf("888" to "g_up"), s.state.value.assignments)

        // 重启（新实例）从磁盘恢复
        val reloaded = store()
        assertTrue(reloaded.isShielded("9999"))
        assertEquals("g_up", reloaded.assignmentOf("888"))
    }

    @Test
    fun `clearing assignment removes it`() {
        val s = store()
        s.setPoolAssignment("888", "g_up")
        assertTrue(File(tmp.root, UnknownIdStore.ASSIGNMENTS_FILENAME).exists())

        s.setPoolAssignment("888", null)
        assertNull(s.assignmentOf("888"))
        assertTrue(s.state.value.assignments.isEmpty())
    }

    @Test
    fun `payload uses pc-compatible keys`() {
        val s = store()
        s.toggleShield("9999")
        s.setPoolAssignment("888", "g_up")

        val shields = File(tmp.root, UnknownIdStore.SHIELDS_FILENAME).readText(Charsets.UTF_8)
        assertTrue("屏蔽文件应含 PC 键 shielded", shields.contains("\"shielded\""))

        val assigns = File(tmp.root, UnknownIdStore.ASSIGNMENTS_FILENAME).readText(Charsets.UTF_8)
        assertTrue("指派文件应含 PC 键 assignments", assigns.contains("\"assignments\""))
        assertTrue(assigns.contains("888"))
    }

    @Test
    fun `corrupt files fall back to empty without throwing`() {
        File(tmp.root, UnknownIdStore.SHIELDS_FILENAME).writeText("{ not json", Charsets.UTF_8)
        File(tmp.root, UnknownIdStore.ASSIGNMENTS_FILENAME).writeText("{ not json", Charsets.UTF_8)
        val s = store()
        assertTrue(s.state.value.shielded.isEmpty())
        assertTrue(s.state.value.assignments.isEmpty())
    }
}