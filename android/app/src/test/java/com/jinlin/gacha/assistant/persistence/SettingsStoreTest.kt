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
 * [SettingsStore] 单测 —— 临时目录纯 JVM 对拍（不需要 Context / Android 运行时）。
 *
 * 覆盖 09 设计稿 §4.1 的读写契约：
 * - 缺文件读默认值；写入即可见且落盘；重启（新实例）从磁盘恢复；
 * - **损坏文件回退默认值且不删原文件**；
 * - **值未变化时不写盘**（幂等闸）；
 * - payload 用 PC 同款 snake_case 键名，且**不含 `active_profile`**（账号真相源在 [ProfileStore]）；
 * - `last_capture` / `metadata_*` 读写往返；`reload()` 感知外部改动；
 * - `target_packages`（2026-09-18 新增，渠道服接管范围）读写往返 + 去重 + **坏值降级**；
 * - `app_log_enabled`（2026-09-22 新增，App 级日志开关）默认关 + 读写往返 + **坏值降级**。
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

    // —— target_package（接管范围**单选**，2026-09-18 渠道服支持；同日晚由多选改单选）——

    @Test
    fun `target package defaults to unset and round-trips`() {
        val s = store()
        assertNull("null = 从未配置过（走自动检出）", s.current.targetPackage)

        s.setTargetPackage("com.bmystu.peng.gw")
        assertEquals("com.bmystu.peng.gw", s.current.targetPackage)
        // 落盘往返
        assertEquals("com.bmystu.peng.gw", store().current.targetPackage)

        // 换渠道 = 直接改（单选语义：旧值不残留）
        s.setTargetPackage("com.bmystu.peng.bilibili")
        assertEquals("com.bmystu.peng.bilibili", s.current.targetPackage)
        assertEquals("com.bmystu.peng.bilibili", store().current.targetPackage)
    }

    @Test
    fun `blank target package is treated as unset`() {
        val s = store()
        s.setTargetPackage("com.bmystu.peng.gw")
        s.setTargetPackage("   ")
        assertNull("空白视作清空（回「从未配置过」）", s.current.targetPackage)
    }

    @Test
    fun `target package uses snake case key and tolerates bad values`() {
        val s = store()
        s.setTargetPackage("com.bmystu.peng.gw")
        assertTrue(settingsFile().readText(Charsets.UTF_8).contains("\"target_package\""))

        // 手改坏 settings.json 不该让 App 起不来（与既有降级契约一致）：
        // 非字符串 ⇒ 回「未配置」；且不删原文件。
        settingsFile().writeText("""{"target_package": 123}""", Charsets.UTF_8)
        assertNull(store().current.targetPackage)
        assertTrue("坏值不应导致文件被删", settingsFile().exists())
    }

    @Test
    fun `legacy multi-select key is read as first item only`() {
        // 多选时代的旧键：**只读兼容取首项**（2026-09-18 单选化后不再写入）
        settingsFile().writeText(
            """{"target_packages": ["com.bmystu.peng.gw", "com.bmystu.peng.mi"]}""",
            Charsets.UTF_8,
        )
        assertEquals("com.bmystu.peng.gw", store().current.targetPackage)

        // 旧键里的坏项同样要能容忍：null 丢弃、非字符串转文本
        settingsFile().writeText("""{"target_packages": [null, 3]}""", Charsets.UTF_8)
        assertEquals("3", store().current.targetPackage)

        settingsFile().writeText("""{"target_packages": {"not": "a list"}}""", Charsets.UTF_8)
        assertNull(store().current.targetPackage)
    }

    @Test
    fun `new single key wins over legacy key`() {
        settingsFile().writeText(
            """{"target_packages": ["old.one"], "target_package": "new.one"}""",
            Charsets.UTF_8,
        )
        assertEquals("new.one", store().current.targetPackage)
    }

    // —— app_log_enabled（App 级日志开关，2026-09-22 新增）——

    @Test
    fun `app log switch defaults off and round-trips`() {
        val s = store()
        assertFalse("默认关闭：不打开就不写日志文件，行为与从前一致", s.current.appLogEnabled)

        s.setAppLogEnabled(true)
        assertTrue(s.current.appLogEnabled)
        assertTrue(settingsFile().readText(Charsets.UTF_8).contains("\"app_log_enabled\""))
        // 落盘往返（模拟 App 重启）
        assertTrue(store().current.appLogEnabled)

        // 坏值降级：非布尔 ⇒ 回默认 false，且**不删原文件**（与既有降级契约一致）
        settingsFile().writeText("""{"app_log_enabled": "yes"}""", Charsets.UTF_8)
        assertFalse(store().current.appLogEnabled)
        assertTrue("坏值不应导致文件被删", settingsFile().exists())
    }


    // —— acknowledged_version（prompt 更新弹窗已确认版本，§15 §4.2）——

    @Test
    fun `acknowledged version defaults empty and round-trips`() {
        val s = store()
        assertEquals("默认空 = 从未确认过 ⇒ prompt 弹窗该弹就弹", "", s.current.acknowledgedVersion)

        s.setAcknowledgedVersion("0.2.0")
        assertEquals("0.2.0", s.current.acknowledgedVersion)
        assertTrue(settingsFile().readText(Charsets.UTF_8).contains("\"acknowledged_version\""))
        // 落盘往返（模拟 App 重启）
        assertEquals("0.2.0", store().current.acknowledgedVersion)
    }

    @Test
    fun `acknowledged version tolerates bad values`() {
        settingsFile().writeText("""{"acknowledged_version": 123}""", Charsets.UTF_8)
        // 非字符串 ⇒ 回空串（重新弹一次可接受的降级），不删原文件
        assertEquals("", store().current.acknowledgedVersion)
        assertTrue(settingsFile().exists())
    }
}
