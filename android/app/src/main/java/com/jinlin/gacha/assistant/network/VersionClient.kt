package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.core.dedup.MiniJson

/**
 * 版本检查结果（§15 §4.2）—— `GET /example-key/v1/version?type=apk` 的响应值对象。
 *
 * @property latestVersion 服务端最新版本号（`android_version.json` 的 `latest_version`，
 *                        与 APK 的 `versionName` 同源，发版基线校验见 `packaging/check_android_version.py`）
 * @property updateType   `silent` / `prompt` / `force`；**未识别 / 缺失按 `prompt` 处理**（PC 同款兜底）
 * @property changelog    更新说明（极简 Markdown 白名单，渲染同公告）
 * @property ready        该平台 OSS 对象是否已上传；**fail-open 语义**：OSS 异常时服务端回 false
 *                        ⇒ 端内 force 不拦抓包（下不到就不拦，误拦会卡死全体用户）
 */
data class UpdateInfo(
    val latestVersion: String,
    val updateType: String,
    val changelog: String,
    val ready: Boolean,
)

/**
 * 版本检查联网拉取（§15 §4.2）—— `GET /example-key/v1/version?type=apk` + HMAC 三头。
 *
 * **静默契约（对齐 PC `load_version_info`）**：任何失败 = `null`，**不抛、不弹错** ——
 * 检查不到更新就当「无更新」，用户无感。HTTP 层、鉴权头、超时复用 [MetadataClient]。
 *
 * 与公告同一次启动时序拉取（先公告后更新，§4.4），结果仅本次会话内存有效（不落盘）。
 */
object VersionClient {

    const val DEFAULT_URL = "https://example.com/v1/version?type=apk"

    /**
     * 拉取版本信息。
     *
     * @param url  接口地址（缺省生产 URL；测试注入本地地址）
     * @param http HTTP 实现（注入缝；缺省 [MetadataClient.defaultHttp]）
     * @return 成功 = [UpdateInfo]；网络 / 非 2xx / 缺关键字段（`latest_version`）= `null`（静默）
     */
    suspend fun fetch(
        url: String = DEFAULT_URL,
        http: HttpGetter = { u, h -> MetadataClient.defaultHttp(u, h) },
    ): UpdateInfo? {
        return when (val out = http(url, MetadataClient.authHeaders())) {
            is HttpOutcome.Err -> null
            is HttpOutcome.Ok -> {
                if (out.status !in 200..299) return null
                parse(out.body)
            }
        }
    }

    /** 解析响应体；`latest_version` 缺失 / 空 = 关键字段不足，回 `null`（当「检查不到」）。 */
    private fun parse(body: String): UpdateInfo? {
        val root = try {
            MiniJson.decode(body)
        } catch (e: Exception) {
            return null
        }
        root as? Map<*, *> ?: return null
        val latest = (root["latest_version"] as? String)?.trim().orEmpty()
        if (latest.isEmpty()) return null
        return UpdateInfo(
            latestVersion = latest,
            updateType = (root["update_type"] as? String)?.trim().orEmpty(),
            changelog = (root["changelog"] as? String) ?: "",
            ready = (root["ready"] as? Boolean) ?: false,
        )
    }
}
