package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 「数据清理」子页可治理的四类文件（对齐 `09-设置模块设计.md` §3.5 / §15）。 */
enum class CleanKind {
    /** 抽卡数据**备份**（副本，`users/<id>_<stamp>[_N].json`）—— 删它不动任何现有记录。 */
    BACKUP,

    /**
     * 全量录包（`records/full_<stamp>.pcap`）。
     *
     * ⚠️ **数据层保留、UI 默认不展示**（2026-09-18 用户定案：pcap 永远不对用户产生）——
     * 展示开关在 `ui/screens/DataCleanupScreen.kt` 的 `HIDDEN_KINDS`；本类与 `StorageCleanerTest`
     * 一概不动（仍扫四类、守卫不变）。故**看到本枚举在 UI 上不出现，属预期，不是死代码**。
     */
    CAPTURE_PCAP,

    /** 诊断包（`diagnose/dump_<reason>_<stamp>.pcap`）。 */
    DIAG_PCAP,

    /** 元数据缓存（`cache/role_cache.json`）—— 删后下次渲染自动从 APK assets 重新播种。 */
    META_CACHE,
}

/**
 * 一条可清理项。
 *
 * **刻意只带结构化字段、不带展示文案**：文案归 UI 层（可本地化、可测），本类保持纯数据。
 *
 * @param file 目标文件
 * @param bytes 文件长度（`File.length()`，扫描时取一次）
 * @param kind 分类
 * @param accountName 账号名（仅 [CleanKind.BACKUP]；跨账号列表要显示它）
 * @param timestamp 从文件名解析出的时刻（BACKUP / 两类 pcap；解析不出为 null）
 * @param records 备份内的记录条数（仅 [CleanKind.BACKUP]；读取失败为 0）
 * @param reason 诊断包的触发原因（仅 [CleanKind.DIAG_PCAP]）
 */
data class CleanItem(
    val file: File,
    val bytes: Long,
    val kind: CleanKind,
    val accountName: String? = null,
    val timestamp: LocalDateTime? = null,
    val records: Int? = null,
    val reason: String? = null,
)

/** 一个分类的扫描结果。 */
data class CleanCategory(
    val kind: CleanKind,
    val items: List<CleanItem>,
) {
    val bytes: Long get() = items.sumOf { it.bytes }
    val count: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 删除结果。
 *
 * @param deleted 成功删除的文件数
 * @param freed 实际释放的字节（只累加删除成功的）
 * @param failed 被跳过的文件数（守卫拒绝 or `delete()` 返回 false）
 */
data class CleanResult(
    val deleted: Int,
    val freed: Long,
    val failed: Int,
)

/**
 * **存储清理的扫描与删除**（`设置 → 数据清理` 子页的数据层）。
 *
 * ### 边界不变式（**本类存在的全部意义**）
 * > **只碰「副本」与「诊断产物」；权威数据一律不删。**
 *
 * | 类别 | 内容 | 可删 | 理由 |
 * |---|---|---|---|
 * | 副本 | `users/<id>_<stamp>[_N].json` | ✅ | 删它只减少「可回退步数」，当前记录一条不丢 |
 * | 诊断产物 | `records下*.pcap`、`diagnose下*.pcap` | ✅ | 纯抓包留痕，与业务数据无关 |
 * | 可重建缓存 | `cache/role_cache.json` | ✅ | 下次渲染由 `MetaLoader` 自动回退出厂版本 |
 * | **权威数据** | `users/<id>.json`（记录本体）、`profiles.json`、`settings.json`、`char_shields.json`、`pool_assignments.json` | ❌ **永不** | 删了数据直接消失。记录本体只在**统计页 → 用户管理 → 清空当前账号历史**（账号维度、有备份、可还原） |
 *
 * 与 2026-09-16「方案 A 去掉抽卡记录」**不冲突**：那次去掉的是**记录本体**（权威数据），
 * 本页加的是**记录备份**（副本）—— 判据没变，只是清单多了一项副本。
 *
 * ### 三道删除守卫（[delete] 逐条复核，任一不满足即跳过并计入 `failed`）
 * 1. `canonicalPath` 的父目录**精确等于**该分类的允许目录（防 `..` 目录穿越）；
 * 2. 文件名符合该分类规约（备份须带 `<8位日期>_<6位时间>`；pcap 须 `.pcap`；缓存须固定名）；
 * 3. 不在「永不删清单」内。
 *
 * ⚠️ 第 2 条同时排除了**记录本体** `users/<id>.json`（无时间戳段，不匹配备份规约）—— 这是
 * 「记录本体永不在此页删」的**机械保证**，不依赖调用方自觉。
 *
 * ### 不入列表的两类（刻意）
 * - **已删账号的孤儿存档**：与备份**完全同形**（`ProfileStore.uniqueArchive` 与 PC 的
 *   `profiles._unique_archive` 命名一致），但它是该账号数据的**唯一副本**，删掉 = 永久丢失。
 *   [scanBackups] 按**注册表 id 前缀**匹配，故孤儿天然不入列表（详见 `09` §15「孤儿存档」）。
 * - **日志**：`core/CaptureLog` 只写内存环形缓冲（200 行）+ logcat，**全程不落盘** ⇒ 无可清理物。
 *
 * ### 并发
 * 本类**不 new `HistoryStore`**，只操作文件系统 —— 以免给「同一文件多写入者」这个已登记的不变量
 * 再加一个实例。备份删除与 Service 的写盘**不共享文件**（Service 只新建备份、从不改写已存在的）。
 *
 * 纯 JVM（只用 [File] + [MiniJson]，不依赖 `Context`）→ 可在 junit 里用临时目录对拍。
 *
 * @param filesDir 生产 = `context.filesDir`
 * @param externalDir 生产 = `context.getExternalFilesDir(null)`；**可能为 null**（外部存储不可用）
 */
class StorageCleaner(
    private val filesDir: File,
    private val externalDir: File?,
) {

    companion object {
        private const val JSON = ".json"
        private const val PCAP = ".pcap"

        /** 账号数据目录（与 `ProfileStore.USERS_DIR` 同值；此处自持以免依赖其初始化）。 */
        private const val USERS_DIR = "users"

        /** 元数据缓存位置（与 `MetaLoader.CACHE_DIR` / `CACHE_FILENAME` 同值）。 */
        private const val CACHE_DIR = "cache"
        private const val CACHE_FILENAME = "role_cache.json"

        /** 录包与诊断包子目录（与 `DiagnoseDumper` 的 `FULL_SUBDIR` / `"diagnose"` 同值，后者为 private 无法引用）。 */
        private const val RECORDS_DIR = "records"
        private const val DIAG_DIR = "diagnose"

        /**
         * 永不删清单（**直接位于 `filesDir` 下的权威数据**）。
         *
         * `users/<id>.json`（记录本体）**不在**此表，它由 [BACKUP_NAME] 正则机械排除（见类注释）。
         */
        private val NEVER_DELETE = setOf(
            "profiles.json", // 账号注册表
            "settings.json", // 设置
            "char_shields.json", // 未映射角色 id 表
            "pool_assignments.json", // 未映射卡池归属表
        )

        /**
         * 备份名规约 `<id>_<yyyyMMdd_HHmmss>[_N].json`。
         *
         * id 段用 `[0-9a-zA-Z]+` 而非 `[0-9a-f]{12}`：现行 id 是 12 位 hex（`uuid4().hex[:12]`，
         * 不含下划线），但需兼容早期遗留账号；**关键是 id 段不含 `_`**（与 `ProfileStore.newId`
         * 的注释一致：正因如此归档名 `_` 分隔才无歧义）。
         */
        private val BACKUP_NAME = Regex("""^[0-9a-zA-Z]+_\d{8}_\d{6}(_\d+)?\.json$""")

        /** 备份名去掉账号前缀后的剩余部分（[scanBackups] 用注册表 id 匹配前缀后再校这一段）。 */
        private val BACKUP_TAIL = Regex("""^\d{8}_\d{6}(_\d+)?\.json$""")

        /** pcap 名尾部的时间戳：`full_<stamp>.pcap` / `dump_<reason>_<stamp>.pcap`（前缀段非贪婪失败时贪婪回退）。 */
        private val PCAP_STAMP = Regex("""^(.+)_(\d{8}_\d{6})\.pcap$""")

        private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }

    /**
     * 扫描四类文件。
     *
     * @param profileNames 账号 id → 账号名（来自 `ProfileStore`）；用于给备份标注账号名，
     *   同时**界定哪些备份算「有效」** —— 不在表内的 id 即已删账号的孤儿存档，不入列表。
     */
    fun scan(profileNames: Map<String, String>): List<CleanCategory> = listOf(
        CleanCategory(CleanKind.BACKUP, scanBackups(profileNames)),
        // externalDir 为 null（外部存储不可用）时不构造相对路径，直接给 null ⇒ 该分类为空
        CleanCategory(
            CleanKind.CAPTURE_PCAP,
            scanPcaps(externalDir?.let { File(it, RECORDS_DIR) }, CleanKind.CAPTURE_PCAP),
        ),
        CleanCategory(
            CleanKind.DIAG_PCAP,
            scanPcaps(externalDir?.let { File(it, DIAG_DIR) }, CleanKind.DIAG_PCAP),
        ),
        CleanCategory(CleanKind.META_CACHE, scanMetaCache()),
    )

    /**
     * 删除给定项；逐文件容错，**单个失败不中断**。
     *
     * 每一项都在删前走 [isDeletable] 三道守卫 —— 即便调用方传进了「权威数据」，也**不会**被删。
     */
    fun delete(items: List<CleanItem>): CleanResult {
        var deleted = 0
        var freed = 0L
        var failed = 0
        for (item in items) {
            if (!isDeletable(item)) {
                failed++
                continue
            }
            val len = item.file.length()
            if (item.file.delete()) {
                deleted++
                freed += len
            } else {
                failed++
            }
        }
        return CleanResult(deleted, freed, failed)
    }

    // —— 扫描 ——

    /**
     * 扫备份：**按注册表 id 前缀**逐个匹配（而非去解析文件名里的 id）—— id 是 12 位 hex、
     * 不含下划线，前缀匹配无歧义；更重要的是它**天然排除孤儿存档**（其 id 已不在注册表）。
     */
    private fun scanBackups(profileNames: Map<String, String>): List<CleanItem> {
        val files = File(filesDir, USERS_DIR).listFiles() ?: return emptyList()
        val out = ArrayList<CleanItem>()
        for ((id, name) in profileNames) {
            val prefix = "${id}_"
            for (f in files) {
                if (!f.isFile || !f.name.startsWith(prefix)) continue
                val tail = f.name.substring(prefix.length)
                if (!BACKUP_TAIL.matches(tail)) continue
                out += CleanItem(
                    file = f,
                    bytes = f.length(),
                    kind = CleanKind.BACKUP,
                    accountName = name,
                    timestamp = parseStamp(tail.removeSuffix(JSON)),
                    records = countRecords(f),
                )
            }
        }
        // 新在前（同秒 `_N` 大者更新）—— 与还原对话框、HistoryStore.backups() 的口径一致
        return out.sortedWith(
            compareByDescending<CleanItem> { it.timestamp }.thenByDescending { it.file.name },
        )
    }

    /** 扫某个 pcap 目录（`records/` 与 `diagnose/` 共用；`externalDir` 为 null 时返回空）。 */
    private fun scanPcaps(dir: File?, kind: CleanKind): List<CleanItem> {
        if (dir == null || !dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(PCAP) }
            .sortedByDescending { it.name }
            .map { f ->
                val m = PCAP_STAMP.matchEntire(f.name)
                val prefix = m?.groupValues?.get(1)
                CleanItem(
                    file = f,
                    bytes = f.length(),
                    kind = kind,
                    timestamp = m?.groupValues?.get(2)
                        ?.let { runCatching { LocalDateTime.parse(it, TS_FMT) }.getOrNull() },
                    // 诊断包名是 `dump_<reason>_<stamp>.pcap`，正则的 group1 会连 `dump_` 一起捕获 ——
                    // 剥掉前缀，展示出真正的触发原因（如 `gap`）。录包名 `full_<stamp>.pcap` 无 reason
                    // 段，其 group1 恒为 "full"；UI 对录包只展示时间，不展示它。
                    reason = if (kind == CleanKind.DIAG_PCAP) prefix?.removePrefix("dump_") else prefix,
                )
            }
    }

    /** 扫元数据缓存（固定单文件）。 */
    private fun scanMetaCache(): List<CleanItem> {
        val f = File(File(filesDir, CACHE_DIR), CACHE_FILENAME)
        if (!f.isFile) return emptyList()
        return listOf(CleanItem(file = f, bytes = f.length(), kind = CleanKind.META_CACHE))
    }

    // —— 守卫 ——

    /** 三道守卫的联合判定；见类注释。 */
    private fun isDeletable(item: CleanItem): Boolean {
        val f = item.file
        if (!f.isFile) return false
        val dir = allowedDir(item.kind) ?: return false
        if (!isDirectChildOf(f, dir)) return false
        if (!nameMatches(f.name, item.kind)) return false
        if (isNeverDelete(f)) return false
        return true
    }

    /** 该分类允许的目录；[CleanKind.CAPTURE_PCAP] / [CleanKind.DIAG_PCAP] 在外部存储不可用时为 null。 */
    private fun allowedDir(kind: CleanKind): File? = when (kind) {
        CleanKind.BACKUP -> File(filesDir, USERS_DIR)
        CleanKind.META_CACHE -> File(filesDir, CACHE_DIR)
        CleanKind.CAPTURE_PCAP -> externalDir?.let { File(it, RECORDS_DIR) }
        CleanKind.DIAG_PCAP -> externalDir?.let { File(it, DIAG_DIR) }
    }

    /**
     * `f` 是否为 `dir` 的**直接子项**（用 `canonicalPath` ⇒ `..` 已被解析，目录穿越在此被拒）。
     * 只允许直接子项、不允许更深层 —— 本页治理的都是平铺文件。
     */
    private fun isDirectChildOf(f: File, dir: File): Boolean {
        val d = canonical(dir) ?: return false
        val p = canonical(f) ?: return false
        return File(p).parent == d
    }

    private fun nameMatches(name: String, kind: CleanKind): Boolean = when (kind) {
        // 要求带 `<8位日期>_<6位时间>` ⇒ `users/<id>.json`（记录本体）在此被机械排除
        CleanKind.BACKUP -> BACKUP_NAME.matches(name)
        CleanKind.CAPTURE_PCAP, CleanKind.DIAG_PCAP -> name.endsWith(PCAP)
        CleanKind.META_CACHE -> name == CACHE_FILENAME
    }

    /** 是否为「永不删清单」里的权威数据（须直接位于 `filesDir` 下）。 */
    private fun isNeverDelete(f: File): Boolean {
        val root = canonical(filesDir) ?: return true // 根都确认不了 ⇒ 保守判定为不可删
        val parent = canonical(f)?.let { File(it).parent } ?: return true
        return parent == root && f.name in NEVER_DELETE
    }

    /** `canonicalPath`；失败（IO / 非法路径）返回 null —— 调用方一律按「不可删」处理。 */
    private fun canonical(f: File): String? = runCatching { f.canonicalPath }.getOrNull()

    // —— 辅助 ——

    /**
     * 从备份名的时间戳段解析时刻（`yyyyMMdd_HHmmss`，可能带 `_N` 后缀）；不合规返回 null。
     * 与 `HistoryStore.parseBackupTs` 同口径。
     */
    private fun parseStamp(tsPart: String): LocalDateTime? {
        var s = tsPart
        val parts = s.split("_")
        if (parts.size == 3 && parts[2].toIntOrNull() != null) s = parts.take(2).joinToString("_")
        return runCatching { LocalDateTime.parse(s, TS_FMT) }.getOrNull()
    }

    /** 读备份内的记录条数（读不出按 0，与 `HistoryStore.countRecords` 同口径）。 */
    private fun countRecords(f: File): Int = runCatching {
        val root = MiniJson.decode(f.readText(Charsets.UTF_8)) as? Map<*, *>
        (root?.get("records") as? List<*>)?.size ?: 0
    }.getOrDefault(0)
}

/**
 * 体积格式化（`124 KB` / `1.2 MB` / `1.1 GB`）。
 *
 * 固定 [Locale.US] 以避开默认 Locale 的小数点差异（单测需要稳定输出）。
 * UI 与 [CleanCategory.summary] 共用。
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
