package com.jinlin.gacha.assistant.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SettingsStore] 单测 —— 临时目录纯 JVM 对拍（不需要 Context / Android 运行时）。
 *
 * 覆盖 09 设计稿 §4.1 的读写契约：
 * - 缺文件读默认值；写入即可见且落盘；重启（新实例）从磁盘恢复；
 * - **损坏文件回退默认值且不删原文件**；
 * - **值未变化时不写盘**（幂等闸）；
 * - payload 用 PC 同款 snake_case 键名，且**不含 `active_profile`**（账号真相源在 [ProfileStore]）；
 * - `last_capture` / `metadata_*` 读写往返；`reload()` 感知外部改动。
 */
class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun settingsFile() = File(tmp.root, SettingsStore.FILE_NAME)

    private fun store() = SettingsStore(settingsFile())

    @Test
    fun `missing file yields defaults`() {
        val s = store()
        assertFalse(s.current.autoDiagnose)
        assertTrue(s.current.lastCapture.isEmpty())
        assertEquals("", s.current.metadataVersion)
        assertEquals("", s.current.metadataFetchedAt)
    }

    @Test
    fun `write is immediately visible and persisted`() {
        val s = store()
        s.setAutoDiagnose(true)
        s.setMetadata("v1.0.4", "2026-09-14 11:30")

        assertTrue(s.current.autoDiagnose)
        assertEquals("v1.0.4", s.current.metadataVersion)
        assertTrue(settingsFile().exists())

        // 模拟 App 重启：新实例从磁盘恢复
        val reloaded = store()
        assertTrue(reloaded.current.autoDiagnose)
        assertEquals("v1.0.4", reloaded.current.metadataVersion)
        assertEquals("2026-09-14 11:30", reloaded.current.metadataFetchedAt)
    }

    @Test
    fun `corrupt file falls back to defaults and is not deleted`() {
        settingsFile().writeText("{ this is not json", Charsets.UTF_8)
        val s = store()
        assertFalse(s.current.autoDiagnose)
        assertEquals("", s.current.metadataVersion)
        assertTrue("损坏文件不应被删除", settingsFile().exists())
    }

    @Test
    fun `payload uses pc-compatible snake case keys`() {
        val s = store()
        s.setAutoDiagnose(true)
        s.setMetadata("v1.0.4", "2026-09-14 11:30")

        val text = settingsFile().readText(Charsets.UTF_8)
        assertTrue(text.contains("\"auto_diagnose\""))
        assertTrue(text.contains("\"last_capture\""))
        assertTrue(text.contains("\"metadata_version\""))
        assertTrue(text.contains("\"metadata_fetched_at\""))
    }

    @Test
    fun `does not carry active profile key`() {
        val s = store()
        s.setAutoDiagnose(true)
        val text = settingsFile().readText(Charsets.UTF_8)
        assertFalse(
            "当前账号只应存在于 profiles.json（ProfileStore 的 active_id），此处不得冗余",
            text.contains("active_profile"),
        )
    }

    @Test
    fun `unchanged value skips disk write`() {
        val s = store()
        s.setMetadata("v1.0.4", "2026-09-14 11:30")
        assertTrue(settingsFile().exists())

        // 删掉磁盘文件后写「同一个值」：幂等闸应拦住，不重建文件
        assertTrue(settingsFile().delete())
        s.setMetadata("v1.0.4", "2026-09-14 11:30")
        assertFalse("同值更新不应重新写盘", settingsFile().exists())
    }

    @Test
    fun `last capture accumulates per profile`() {
        val s = store()
        s.setLastCapture("p_a", "2026-09-14 11:30")
        s.setLastCapture("p_b", "2026-09-14 12:00")
        s.setLastCapture("p_a", "2026-09-14 13:00")

        assertEquals("2026-09-14 13:00", s.current.lastCapture["p_a"])
        assertEquals("2026-09-14 12:00", s.current.lastCapture["p_b"])
        assertEquals(2, s.current.lastCapture.size)

        // 落盘往返
        assertEquals("2026-09-14 13:00", store().current.lastCapture["p_a"])
    }

    @Test
    fun `reload picks up external changes`() {
        val s = store()
        s.setMetadata("v1.0.0", "t1")

        // 另一个实例改写同一文件（模拟外部写入，如导入覆盖）
        SettingsStore(settingsFile()).setMetadata("v2.0.0", "t2")

        assertEquals("v1.0.0", s.current.metadataVersion)
        s.reload()
        assertEquals("v2.0.0", s.current.metadataVersion)
        assertEquals("t2", s.current.metadataFetchedAt)
    }
}
