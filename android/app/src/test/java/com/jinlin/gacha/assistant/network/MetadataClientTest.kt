package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.network.HttpOutcome
import com.jinlin.gacha.assistant.persistence.HmacSign
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [MetadataClient]（S3b 联网拉取）单测 —— 注入假 `HttpGetter`，**全离线**不碰网络。
 *
 * 覆盖 11 设计稿 §3 失败分层逐条 + §1 请求头契约：
 * - 2xx + 合法 body → `Result.success` 且 `writeRaw` 已落（与响应 body 同字节）；
 * - 401/403 → `AUTH`；503 → `NOT_READY`；2xx 缺键 → `FORMAT`；其它 4xx/5xx → `HTTP(码)`；
 * - `Err(IOException)` → `NETWORK`；
 * - 请求头三件齐全，`X-Auth-Signature` = `HmacSign.sign(key, secret, X-Auth-Timestamp)`。
 */
class MetadataClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假响应体：合法（含 `characters` + `pools`），版本号便于断言。 */
    private val validBody = """{"version":"9.9.9","characters":{},"pools":{}}"""

    private val cacheFile: File get() = MetaLoader.cacheFile(tmp.root)

    /** 假 HttpGetter：记录 url/headers，回放预设 outcome。 */
    private class FakeHttp(var result: HttpOutcome) :
        HttpGetter {
        var url: String = ""
        val headers = mutableMapOf<String, String>()
        override suspend fun invoke(u: String, h: Map<String, String>): HttpOutcome {
            url = u
            headers.clear()
            headers.putAll(h)
            return result
        }
    }

    private fun fetchWith(fake: FakeHttp, file: File = cacheFile) =
        runBlocking { MetadataClient.fetch(file, http = fake) }

    // —— 成功路径 ——

    @Test
    fun `success writes raw body to cache and returns meta`() {
        val fake = FakeHttp(HttpOutcome.Ok(200, validBody))
        val result = fetchWith(fake)
        assertTrue(result.isSuccess)
        val meta = result.getOrNull()!!
        assertEquals("9.9.9", meta.version)
        // 覆盖写已落：缓存文件存的是响应原样字节
        assertEquals(validBody, cacheFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `request carries three auth headers and valid signature`() {
        val fake = FakeHttp(HttpOutcome.Ok(200, validBody))
        fetchWith(fake)
        assertEquals(MetadataClient.DEFAULT_ROLE_URL, fake.url)
        assertEquals(MetadataClient.KEY, fake.headers["X-Auth-Key"])
        val ts = fake.headers["X-Auth-Timestamp"]!!.toLong()
        val expectedSig = HmacSign.sign(MetadataClient.KEY, MetadataClient.SECRET, ts)
        assertEquals(expectedSig, fake.headers["X-Auth-Signature"])
    }

    @Test
    fun `success overwrites the cache body written by a prior fetch`() {
        val file = cacheFile
        // 第一次拉取 v9.9.9
        fetchWith(FakeHttp(HttpOutcome.Ok(200, validBody)), file)
        // 第二次拉取新版本 → 缓存被逐字覆盖
        val body2 = """{"version":"9.9.10","characters":{},"pools":{}}"""
        fetchWith(FakeHttp(HttpOutcome.Ok(200, body2)), file)
        assertEquals(body2, file.readText(Charsets.UTF_8))
    }

    // —— 失败分层（11 §3）——

    @Test
    fun `401 maps to auth`() = assertReason(401, MetaErrorReason.AUTH)

    @Test
    fun `403 maps to auth`() = assertReason(403, MetaErrorReason.AUTH)

    @Test
    fun `503 maps to not ready`() = assertReason(503, MetaErrorReason.NOT_READY)

    @Test
    fun `2xx missing required keys maps to format`() = assertReason(200, MetaErrorReason.FORMAT, body = """{"version":"1.0.0"}""")

    @Test
    fun `500 maps to http with code`() {
        val fake = FakeHttp(HttpOutcome.Ok(500, "boom"))
        val result = fetchWith(fake)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as MetadataException
        assertEquals(MetaErrorReason.HTTP, ex.reason)
        assertEquals(500, ex.httpCode)
    }

    @Test
    fun `io exception maps to network`() {
        val fake = FakeHttp(HttpOutcome.Err(IOException("conn refused")))
        val result = fetchWith(fake)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as MetadataException
        assertEquals(MetaErrorReason.NETWORK, ex.reason)
    }

    @Test
    fun `failure does not write cache`() {
        val fake = FakeHttp(HttpOutcome.Ok(503, "unavailable"))
        fetchWith(fake)
        assertTrue(!cacheFile.exists())
    }

    private fun assertReason(
        status: Int,
        expectedReason: MetaErrorReason,
        body: String = validBody,
    ) {
        val fake = FakeHttp(HttpOutcome.Ok(status, body))
        val result = fetchWith(fake)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("期望 MetadataException，实为 ${ex?.javaClass?.simpleName}", ex is MetadataException)
        assertEquals(expectedReason, (ex as MetadataException).reason)
        if (ex.httpCode != null) assertEquals(status, ex.httpCode)
    }

    // —— MetaLoader.writeRaw 补测（11 §4）——

    @Test
    fun `write raw roundtrip and creates parent dir`() {
        val target = MetaLoader.cacheFile(tmp.root) // <root>/cache/role_cache.json（cache 尚不存在）
        assertTrue(MetaLoader.writeRaw(target, validBody))
        assertTrue(target.exists())
        assertEquals(validBody, target.readText(Charsets.UTF_8))
    }

    @Test
    fun `write raw overwrites existing content`() {
        val target = tmp.newFile("role_cache.json")
        target.writeText("OLD", Charsets.UTF_8)
        assertTrue(MetaLoader.writeRaw(target, validBody))
        assertEquals(validBody, target.readText(Charsets.UTF_8))
    }

    @Test
    fun `write raw to unwritable path returns false not throw`() {
        // tmp.root 已存在（是目录）→ 拿它当文件写会失败，应返回 false 而不抛
        val asFile = tmp.root
        val ok = try {
            MetaLoader.writeRaw(asFile, validBody)
        } catch (e: Exception) {
            fail("writeRaw 不应抛异常: $e")
            false
        }
        assertTrue(!ok)
    }
}