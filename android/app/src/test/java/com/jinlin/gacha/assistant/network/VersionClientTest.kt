package com.jinlin.gacha.assistant.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionClient] 单测 —— 注入假 `HttpGetter`，全离线。
 * 重点：字段抽取（含 `ready` fail-open 默认 false）、**静默契约**（失败一律 null）、
 * `latest_version` 缺失 = 关键字段不足回 null。
 */
class VersionClientTest {

    private val validBody =
        """{"latest_version":"0.2.0","update_type":"prompt","changelog":"## 更新","ready":true}"""

    private class FakeHttp(var result: HttpOutcome) : HttpGetter {
        var url: String = ""
        override suspend fun invoke(u: String, h: Map<String, String>): HttpOutcome {
            url = u
            return result
        }
    }

    private fun fetchWith(fake: FakeHttp) = runBlocking { VersionClient.fetch(http = fake) }

    @Test
    fun `success parses all fields`() {
        val info = fetchWith(FakeHttp(HttpOutcome.Ok(200, validBody)))!!
        assertEquals("0.2.0", info.latestVersion)
        assertEquals("prompt", info.updateType)
        assertEquals("## 更新", info.changelog)
        assertTrue(info.ready)
    }

    @Test
    fun `request targets apk platform`() {
        val fake = FakeHttp(HttpOutcome.Ok(200, validBody))
        fetchWith(fake)
        assertTrue(fake.url.endsWith("type=apk"))
    }

    @Test
    fun `missing ready defaults to false fail-open`() {
        val body = """{"latest_version":"0.2.0","update_type":"force","changelog":""}"""
        val info = fetchWith(FakeHttp(HttpOutcome.Ok(200, body)))!!
        // ready 判不出 = false ⇒ force 不拦抓包（下不到就不拦）
        assertEquals(false, info.ready)
    }

    @Test
    fun `missing latest_version returns null`() {
        val body = """{"update_type":"prompt","changelog":"x"}"""
        assertNull(fetchWith(FakeHttp(HttpOutcome.Ok(200, body))))
    }

    @Test
    fun `non-2xx returns null`() {
        for (status in listOf(401, 404, 500, 503)) {
            assertNull("HTTP $status 应回 null", fetchWith(FakeHttp(HttpOutcome.Ok(status, "nope"))))
        }
    }

    @Test
    fun `network error returns null`() {
        assertNull(fetchWith(FakeHttp(HttpOutcome.Err(java.io.IOException("down")))))
    }

    @Test
    fun `garbage body returns null`() {
        assertNull(fetchWith(FakeHttp(HttpOutcome.Ok(200, "{not json"))))
    }
}
