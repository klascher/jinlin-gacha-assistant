package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.persistence.HmacSign
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnnouncementClient] 单测 —— 注入假 `HttpGetter`，全离线。
 * 重点：**静默契约**（失败一律空列表，不抛）+ 请求头/URL 契约与 [MetadataClient] 同源。
 */
class AnnouncementClientTest {

    private val validBody =
        """{"announcements":[{"id":"a-1","type":"pinned","title":"t","content":"c","ts":1}]}"""

    private class FakeHttp(var result: HttpOutcome) : HttpGetter {
        var url: String = ""
        val headers = mutableMapOf<String, String>()
        override suspend fun invoke(u: String, h: Map<String, String>): HttpOutcome {
            url = u
            headers.clear()
            headers.putAll(h)
            return result
        }
    }

    private fun fetchWith(fake: FakeHttp) =
        runBlocking { AnnouncementClient.fetch(http = fake) }

    @Test
    fun `success parses list`() {
        val list = fetchWith(FakeHttp(HttpOutcome.Ok(200, validBody)))
        assertEquals(1, list.size)
        assertEquals("a-1", list[0].id)
    }

    @Test
    fun `request carries apk platform param and auth headers`() {
        val fake = FakeHttp(HttpOutcome.Ok(200, validBody))
        fetchWith(fake)
        assertTrue(fake.url.endsWith("type=apk"))
        assertEquals(MetadataClient.KEY, fake.headers["X-Auth-Key"])
        val ts = fake.headers["X-Auth-Timestamp"]!!.toLong()
        assertEquals(
            HmacSign.sign(MetadataClient.KEY, MetadataClient.SECRET, ts),
            fake.headers["X-Auth-Signature"],
        )
    }

    @Test
    fun `non-2xx returns empty list`() {
        for (status in listOf(401, 403, 404, 500, 503)) {
            val list = fetchWith(FakeHttp(HttpOutcome.Ok(status, "nope")))
            assertTrue("HTTP $status 应回空列表", list.isEmpty())
        }
    }

    @Test
    fun `network error returns empty list`() {
        val list = fetchWith(FakeHttp(HttpOutcome.Err(java.io.IOException("down"))))
        assertTrue(list.isEmpty())
    }

    @Test
    fun `garbage body returns empty list`() {
        val list = fetchWith(FakeHttp(HttpOutcome.Ok(200, "{not json")))
        assertTrue(list.isEmpty())
    }
}
