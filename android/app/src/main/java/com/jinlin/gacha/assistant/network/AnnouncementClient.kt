package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.core.Announcement
import com.jinlin.gacha.assistant.core.AnnouncementParse

/**
 * 公告联网拉取（§15 §4.2）—— `GET /example-key/v1/announcements?type=apk` + HMAC 三头。
 *
 * **静默契约（对齐 PC `load_announcements`）**：失败 = 空列表，**不抛、不弹错** ——
 * 公告是锦上添花，网络坏了不该打扰用户。HTTP 层、鉴权头、超时全部复用
 * [MetadataClient]（`defaultHttp` / `authHeaders`），App 内只此一份 HTTP 栈。
 *
 * 拉取频率：**每次启动一次、结果仅本次会话内存有效**（不落盘、不限频，§5 定案：
 * 限频会被「当天第二次启动看不到新公告」击穿，且省不了多少流量）。
 */
object AnnouncementClient {

    const val DEFAULT_URL = "https://example.com/v1/announcements?type=apk"

    /**
     * 拉取公告列表。
     *
     * @param url  接口地址（缺省生产 URL；测试注入本地地址）
     * @param http HTTP 实现（注入缝；缺省 [MetadataClient.defaultHttp]）
     * @return 成功 = 解析后的列表（服务端已过滤排序，端内不再排）；
     *         网络 / 非 2xx / 解析失败 = **空列表**（静默）
     */
    suspend fun fetch(
        url: String = DEFAULT_URL,
        http: HttpGetter = { u, h -> MetadataClient.defaultHttp(u, h) },
    ): List<Announcement> {
        return when (val out = http(url, MetadataClient.authHeaders())) {
            is HttpOutcome.Err -> emptyList()
            is HttpOutcome.Ok -> {
                if (out.status !in 200..299) emptyList()
                else AnnouncementParse.parse(out.body)
            }
        }
    }
}
