package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.persistence.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [UpdateCenter] 单测（2026-09-29 元数据自动拉取）—— 注入假 `HttpGetter`，**全离线**不碰网络。
 *
 * 覆盖：启动闩锁 CAS；checkNow 并行拉元数据的成功 / 失败路径 ——
 * - role 成功 → 缓存逐字覆盖 + `metadata_version`/`metadata_fetched_at` 两键写入（与手动拉取同口径）；
 * - role 失败（503 / 抛意外异常）→ **静默**：不动缓存、不写设置、不影响公告/版本照常更新、不抛；
 * - 公告 / 版本解析失败 → 沿用各自 Client 既有契约（空列表 / null）。
 *
 * 断言用的 [SettingsStore] 是**传入构造器的同一实例**（StateFlow 共享），无需从 center 内部取。
 */
class UpdateCenterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val validRoleBody = """{"version":"9.9.9","characters":{},"pools":{}}"""

    private val cacheFile: File get() = MetaLoader.cacheFile(tmp.root)

    private fun newCenter(): Pair<UpdateCenter, SettingsStore> {
        val settings = SettingsStore(File(tmp.root, "settings.json"))
        return UpdateCenter(tmp.root, settings) to settings
    }

    /** 假 HttpGetter：按 URL 分流三个接口（role / announcements / version），各自回放预设 outcome。 */
    private class RoutedFakeHttp(
        private var role: HttpOutcome,
        private val announcements: HttpOutcome = HttpOutcome.Ok(200, "[]"),
        private val version: HttpOutcome = HttpOutcome.Ok(200, "{}"),
    ) : HttpGetter {
        var roleCalled = false
        override suspend fun invoke(u: String, h: Map<String, String>): HttpOutcome = when {
            u.contains("role") -> { roleCalled = true; role }
            u.contains("announcement") -> announcements
            u.contains("version") -> version
            else -> error("未预期的 URL: $u")
        }
    }

    /** 抛意外异常（非 MetadataException）的假实现：验证 checkNow 的 runCatching 兜底。 */
    private class ThrowingFakeHttp : HttpGetter {
        override suspend fun invoke(u: String, h: Map<String, String>): HttpOutcome =
            throw IllegalStateException("boom")
    }

    // —— 启动闩锁 ——

    @Test
    fun `startup latch returns true only once`() {
        val (center, _) = newCenter()
        assertTrue(center.beginStartupCheck())
        assertFalse(center.beginStartupCheck())
        assertFalse(center.beginStartupCheck())
    }

    // —— checkNow：元数据成功路径 ——

    @Test
    fun `check now success writes cache and updates settings metadata`() = runBlocking {
        val (center, settings) = newCenter()
        val fake = RoutedFakeHttp(HttpOutcome.Ok(200, validRoleBody))

        center.checkNow(http = fake)

        // 缓存逐字覆盖为响应原样文本
        assertTrue(fake.roleCalled)
        assertEquals(validRoleBody, cacheFile.readText(Charsets.UTF_8))
        // 设置两键与手动拉取同口径
        assertEquals("9.9.9", settings.state.value.metadataVersion)
        assertTrue(settings.state.value.metadataFetchedAt.isNotEmpty())
        // 公告 / 版本 / checked 照常（空 body 沿用各自 Client 的静默契约）
        assertTrue(center.announcements.value.isEmpty())
        assertEquals(null, center.updateInfo.value)
        assertTrue(center.checked.value)
    }

    // —— checkNow：元数据失败静默 ——

    @Test
    fun `check now metadata failure is silent and keeps previous state`() = runBlocking {
        val (center, settings) = newCenter() // metadataVersion 初始为空串
        val fake = RoutedFakeHttp(HttpOutcome.Ok(503, "unavailable"))

        center.checkNow(http = fake) // 不抛

        assertEquals("", settings.state.value.metadataVersion)
        assertEquals("", settings.state.value.metadataFetchedAt)
        assertFalse(cacheFile.exists())
        // 公告 / 版本 / checked 不受影响
        assertTrue(center.announcements.value.isEmpty())
        assertEquals(null, center.updateInfo.value)
        assertTrue(center.checked.value)
    }

    @Test
    fun `check now metadata failure keeps existing cache intact`() = runBlocking {
        val (center, settings) = newCenter()
        // 预写旧缓存（出厂种子等价物）
        val oldBody = """{"version":"1.0.6","characters":{},"pools":{}}"""
        cacheFile.parentFile!!.mkdirs()
        cacheFile.writeText(oldBody, Charsets.UTF_8)

        center.checkNow(http = RoutedFakeHttp(HttpOutcome.Ok(503, "unavailable")))

        // 回退语义：旧缓存逐字未动
        assertEquals(oldBody, cacheFile.readText(Charsets.UTF_8))
        assertEquals("", settings.state.value.metadataVersion)
    }

    @Test
    fun `check now survives unexpected exception from http`() = runBlocking {
        val (center, settings) = newCenter()

        center.checkNow(http = ThrowingFakeHttp()) // 三路都抛，checkNow 仍正常完成

        assertTrue(center.checked.value)
        assertEquals("", settings.state.value.metadataVersion)
        assertFalse(cacheFile.exists())
    }

    // —— checkNow：公告 / 版本正常返回仍解析 ——

    @Test
    fun `check now parses announcements and version when valid`() = runBlocking {
        val (center, _) = newCenter()
        val fake = RoutedFakeHttp(
            role = HttpOutcome.Ok(200, validRoleBody),
            announcements = HttpOutcome.Ok(
                200,
                """{"announcements":[{"id":"a1","title":"t","content":"b","ts":1700000000,"type":"update"}]}""",
            ),
            version = HttpOutcome.Ok(200, """{"latest_version":"1.0.0","update_type":"prompt","changelog":"x"}"""),
        )

        center.checkNow(http = fake)

        assertTrue(center.announcements.value.isNotEmpty())
        assertNotNull(center.updateInfo.value)
    }
}
