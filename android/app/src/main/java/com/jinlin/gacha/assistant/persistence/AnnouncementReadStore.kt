package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import java.io.File

/**
 * 公告已读状态持久化 —— Kotlin 镜像 PC `gacha_exporter/storage/announcements.py`。
 *
 * **文件布局与 PC 逐字一致**（未来互通/diff 稳定）：
 * `announcements_read.json` → `{"read": ["<id>", …]}`，**排序写入**；
 * 读侧兼容标准写法与裸数组 `[...]`、数字 id 强转字符串（PC 同款宽容）。
 *
 * 「已读」语义（2026-09-28 用户裁定）：`pinned` 型 = **首次强制确认**（弹窗拦到确认为止，
 * 确认即记已读、此后不再弹）；`update` 型 = 「不再提示」勾选即记已读。
 * （原「pinned 不进已读集合、每次启动都弹」的 §15 设计已作废——真机实测被用户判为 bug。）
 * 作用域：跨账号全局。
 *
 * @param rootDir 数据根目录（生产 = `filesDir`；测试 = 临时目录）
 */
class AnnouncementReadStore(private val rootDir: File) {

    companion object {
        const val FILENAME = "announcements_read.json"

        private const val KEY_READ = "read"

        /** UI 共用的进程级实例。 */
        @Volatile
        private var shared: AnnouncementReadStore? = null

        fun get(context: android.content.Context): AnnouncementReadStore =
            shared ?: synchronized(this) {
                shared ?: AnnouncementReadStore(context.filesDir).also { shared = it }
            }

        /** 仅供单测：重置进程级单例。 */
        internal fun resetSharedForTest() {
            shared = null
        }
    }

    private val file: File get() = File(rootDir, FILENAME)

    private val _read = LinkedHashSet<String>()

    init {
        load()
    }

    /** 某 id 是否已读（「不再提示」过）。 */
    @Synchronized
    fun isRead(id: String): Boolean = id in _read

    /** 当前已读集合快照（过滤未读公告用；返回副本，外部改不动内存态）。 */
    @Synchronized
    fun readIds(): Set<String> = _read.toSet()

    /** 标记已读；幂等（重复标记不重复落盘）。 */
    @Synchronized
    fun markRead(id: String) {
        if (id.isBlank() || !_read.add(id)) return
        persist()
    }

    /** 读盘；缺/坏 → 空集，不抛（对齐 PC `load_read_ids` 的「坏了用空集」）。 */
    private fun load() {
        _read.clear()
        try {
            val root = MiniJson.decode(file.readText(Charsets.UTF_8))
            val raw = when (root) {
                is Map<*, *> -> root[KEY_READ]
                is List<*> -> root
                else -> null
            } ?: return
            (raw as? List<*>)?.forEach { id -> id?.toString()?.let { _read.add(it) } }
        } catch (e: Exception) {
            // 缺/坏 → 空已读集合，公告会重弹一次（可接受的降级，与 PC 一致）
        }
    }

    /** 落盘；失败只降级（不抛、不影响内存态）。 */
    private fun persist() {
        try {
            rootDir.mkdirs()
            file.writeText(
                MiniJson.encodePretty(linkedMapOf(KEY_READ to _read.sorted()), indent = 2),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            // 只降级：内存态仍生效，下次启动回到上次成功落盘的状态（与 PC 一致）
        }
    }
}
