package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.core.meta.Meta
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.persistence.HmacSign
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 元数据拉取失败的原因 → 用户文案（对齐 `mobile/docs/11-元数据联网刷新设计.md` §3，
 * 网络契约见 `09-设置模块设计.md §3.4.2`）。
 *
 * - [NETWORK]   IO / 连接 / 读超时 / DNS ——「网络不可用，请检查后重试」
 * - [AUTH]      HTTP 401/403 ——「鉴权失败（可能 key/secret 已更新）」
 * - [NOT_READY] HTTP 503 ——「服务端数据未就绪」
 * - [FORMAT]    2xx 但解不了 / 缺 `characters`+`pools` ——「返回数据格式异常」
 * - [HTTP]      其它 4xx/5xx ——「拉取失败（HTTP {code}）」
 */
enum class MetaErrorReason(val httpCode: Int? = null) {
    NETWORK,
    AUTH(401),
    NOT_READY(503),
    FORMAT,
    HTTP,
}

/** [MetadataClient.fetch] 的失败载体；[reason] 供 UI 映射文案，[httpCode] 供 HTTP 子类展示。 */
class MetadataException(val reason: MetaErrorReason, val httpCode: Int? = reason.httpCode) :
    Exception("metadata fetch ${reason.name}${httpCode?.let { " (http=$it)" } ?: ""}")

/**
 * HTTP 结果：[Ok] 携带状态与 body（含 4xx/5xx 的 errorStream 文本）；[Err] 为网络层异常。
 * （顶层声明：typealias 不允许嵌套在 object/class 体内）
 */
sealed interface HttpOutcome {
    data class Ok(val status: Int, val body: String) : HttpOutcome
    data class Err(val cause: Throwable) : HttpOutcome
}

/** 注入缝：默认 [MetadataClient.defaultHttp]；单测注入假实现，全离线断言。 */
typealias HttpGetter = suspend (url: String, headers: Map<String, String>) -> HttpOutcome

/**
 * 元数据联网拉取（S3b）—— HMAC 签名 + HTTP GET + 2xx 覆盖缓存。
 *
 * 数据源链路：`fetch` → `MetaLoader.writeRaw`（覆盖 `role_cache.json`）→ 读侧
 * `MetaLoader.read` → `StatsEngine`（§3.79 已接）。object 无状态，`cacheFile` 一律入参
 * （生产者拿 `context.filesDir`，测试拿临时目录），因此纯 JVM 可测、不持 `Context`。
 *
 * 与 PC `load_role_data` 远程半边一字对齐：URL / key / secret / 签名串（`key+ts` 无分隔符）/
 * 三请求头 / 10s 超时 / 2xx 且含 `characters`+`pools` 才算成功。
 */
object MetadataClient {

    const val DEFAULT_ROLE_URL = "https://example.com/v1/role"
    const val KEY = "example-key"
    const val SECRET = "example-secret-not-real-0"
    const val TIMEOUT_MS = 10_000

    private const val HDR_KEY = "X-Auth-Key"
    private const val HDR_TS = "X-Auth-Timestamp"
    private const val HDR_SIG = "X-Auth-Signature"

    /**
     * 拉取并覆盖缓存。
     *
     * @param cacheFile 目标缓存（生产 = `MetaLoader.cacheFile(context.filesDir)`）
     * @param url       接口地址（缺省产 URL，PC 同源）
     * @param http      HTTP 实现（注入缝）
     * @return 成功 = 已写缓存并解析为 [Meta]；失败 = [MetadataException]（[MetaErrorReason] 分层）
     */
    suspend fun fetch(
        cacheFile: File,
        url: String = DEFAULT_ROLE_URL,
        http: HttpGetter = { u, h -> defaultHttp(u, h) },
    ): Result<Meta> {
        val ts = System.currentTimeMillis() / 1000
        val sig = HmacSign.sign(KEY, SECRET, ts)
        val headers = mapOf(
            HDR_KEY to KEY,
            HDR_TS to ts.toString(),
            HDR_SIG to sig,
        )
        return when (val out = http(url, headers)) {
            is HttpOutcome.Err -> Result.failure(MetadataException(MetaErrorReason.NETWORK))
            is HttpOutcome.Ok -> classify(out.status, out.body, cacheFile)
        }
    }

    /** 只读当前缓存（09 §4.3 契约的 `cached()`；实现即 [MetaLoader.read]，此处一行委托对齐 API）。 */
    fun cached(cacheFile: File): Meta? = MetaLoader.read(cacheFile)

    // —— 内部 ——

    private fun classify(status: Int, body: String, cacheFile: File): Result<Meta> {
        if (status == 401 || status == 403) return Result.failure(MetadataException(MetaErrorReason.AUTH, status))
        if (status == 503) return Result.failure(MetadataException(MetaErrorReason.NOT_READY, status))
        if (status !in 200..299) return Result.failure(MetadataException(MetaErrorReason.HTTP, status))
        val meta = MetaLoader.parse(body)
            ?: return Result.failure(MetadataException(MetaErrorReason.FORMAT))
        // 成功：写原始响应到缓存（后续读侧 `MetaLoader.read` 直接可读）。
        MetaLoader.writeRaw(cacheFile, body)
        return Result.success(meta)
    }

    /** 默认 HTTP：内置 [HttpURLConnection]，零三方库；不进 [Err] 只抛走最后兜底的网络异常。 */
    private fun defaultHttp(url: String, headers: Map<String, String>): HttpOutcome {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "GET"
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val status = c.responseCode
                val body = (if (status in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                HttpOutcome.Ok(status, body)
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            HttpOutcome.Err(e)
        }
    }
}