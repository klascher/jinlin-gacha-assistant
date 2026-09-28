package com.jinlin.gacha.assistant.core

import com.jinlin.gacha.assistant.core.dedup.MiniJson

/**
 * 安卓端公告条目（**纯值对象**，与 Compose / android.* 无关 ⇒ 纯 JVM 可测）。
 *
 * schema 与 PC 公告文件逐字相同（§15 §3-B：内容各自维护、格式不另发明一套）：
 * `{id, type(pinned|update), title, content, ts, visible}`，见 `server/data/android_announcements.json`。
 */
data class Announcement(
    /** 稳定标识；「不再提示」按它记已读。缺 id 的条目解析层直接丢弃（无法记已读 ⇒ 会反复弹）。 */
    val id: String,
    /** `pinned`（置顶/免责，强制确认）或 `update`（更新，可「不再提示」）；缺省 `update`。 */
    val type: String,
    /** 标题（纯文本）。 */
    val title: String,
    /** 正文（极简 Markdown 白名单，渲染见 `core/markdown/MiniMarkdown`）。 */
    val content: String,
    /** Unix 秒时间戳，排序用（服务端已排，端内不再排）。 */
    val ts: Long,
)

/**
 * 公告响应解析（**纯函数**，用 [MiniJson]，零依赖）。
 *
 * 输入是 `GET /example-key/v1/announcements?type=apk` 的响应体
 * `{announcements: [...]}`。容错契约（对齐 PC `load_announcements` 的「坏了就当没有」）：
 * - 根 / `announcements` 不是预期结构 → 空列表；
 * - 缺 `id` / 空 `id` 的条目**跳过**（无法记已读，会反复弹）；
 * - `visible=false` 服务端已滤，端内**再兜一次**（§15 §4.2，双保险防服务端判据漂移）；
 * - 单条字段类型不对 → 该条跳过，不影响其它条。
 */
object AnnouncementParse {

    private const val TYPE_PINNED = "pinned"
    private const val TYPE_UPDATE = "update"

    /** 解析公告列表；任何结构异常都静默回空列表，绝不抛。 */
    fun parse(body: String?): List<Announcement> {
        if (body.isNullOrBlank()) return emptyList()
        val root = try {
            MiniJson.decode(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val list = (root as? Map<*, *>)?.get("announcements") as? List<*> ?: return emptyList()
        val out = ArrayList<Announcement>(list.size)
        for (item in list) {
            val entry = item as? Map<*, *> ?: continue
            val id = (entry["id"] as? String)?.trim().orEmpty()
            if (id.isEmpty()) continue
            // 服务端已滤 visible=false，端内再兜一次（草稿/归档不该被用户看到）
            if ((entry["visible"] as? Boolean) == false) continue
            val title = (entry["title"] as? String) ?: continue
            val content = (entry["content"] as? String) ?: continue
            val ts = (entry["ts"] as? Number)?.toLong() ?: continue
            val type = (entry["type"] as? String)
                ?.takeIf { it == TYPE_PINNED || it == TYPE_UPDATE }
                ?: TYPE_UPDATE
            out.add(
                Announcement(
                    id = id,
                    type = type,
                    title = title,
                    content = content,
                    ts = ts,
                )
            )
        }
        return out
    }
}
