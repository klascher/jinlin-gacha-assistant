package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.GachaRecord
import com.jinlin.gacha.assistant.core.dedup.MergeReport
import com.jinlin.gacha.assistant.core.dedup.MiniJson
import com.jinlin.gacha.assistant.core.dedup.PositionAlign
import com.jinlin.gacha.assistant.core.dedup.VALID_EVENT_SIZES
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 一个历史备份还原点（镜像 PC `history.BackupInfo`）。
 *
 * @param file 备份文件
 * @param whenTime 从文件名解析出的备份时刻（已剥离 `_N` 后缀）
 * @param records 该备份内的记录条数（读文件计数，读取失败按 0）
 * @param label 人类可读展示串，如 `2026-09-16 19:17:37（521 条）`；同秒多份追加 ` 同秒_N`
 */
data class BackupInfo(
    val file: File,
    val whenTime: LocalDateTime,
    val records: Int,
    val label: String,
)

/**
 * 历史记录持久化 —— Kotlin 镜像 PC `gacha_exporter/storage/history.py::HistoryService` 的
 * **落库部分**（load / merge / clear / 备份 / 还原），落点 `usersDir/<profileId>.json`。
 *
 * **payload schema 与 PC 逐字一致**（`records` / `view_totals` / `total`），因此 PC 的历史文件
 * 可直接拷给 Android（反之亦然）——这是「PC↔Android 数据互通」的第一块基石。
 *
 * ```json
 * {
 *   "records": [{"pool_id":"0","item_id":"100","timestamp":1700000000000,
 *                "batch_seq":0,"position_in_batch":0}],
 *   "view_totals": {"0@0": 521},
 *   "total": 521
 * }
 * ```
 *
 * 与 PC 的**分工差异**：Δ 归一化由 `SessionDedup` 在会话内**实时完成**（收下即 `pos − Δ`），
 * 所以 [merge] 恒以 `shift = 0` 调用 [PositionAlign.mergeByPosition]（只做幂等复核 + 追加），
 * 本类**不自行推 Δ**。
 *
 * 两条 PC 实测教训已内建：
 * - **内容幂等**：payload 与磁盘逐结构一致时不写盘、不备份（PC 曾 6 分钟生成 17 个备份）；
 * - **无新增也要落盘**：total 快照必须写下去，否则下次仍按旧基准推 Δ、位置整体错位。
 *
 * 纯 JVM（只用 [File] + [MiniJson]，不依赖 `Context`）→ 可在 junit 里用临时目录对拍。
 *
 * @param usersDir 账号数据目录（Android：`File(filesDir, "users")`）
 * @param profileId 账号档案 id（首版固定 "default"；多账号见 `09-设置模块设计.md` §6）
 */
class HistoryStore(private val usersDir: File, private val profileId: String) {

    companion object {
        /** 历史里「全部卡池」的视图键：B8 后写 "0@0"；老 PC 文件可能是裸 "0"。 */
        private val ALL_POOLS_VIEW_KEYS = listOf("0@0", "0")

        private const val KEY_RECORDS = "records"
        private const val KEY_VIEW_TOTALS = "view_totals"
        private const val KEY_TOTAL = "total"

        /** 备份 / 历史文件的后缀（解析与拼接共用）。 */
        private const val JSON_SUFFIX = ".json"

        private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** 还原点展示用时间格式（对齐 PC `BackupInfo.label` 的 `%Y-%m-%d %H:%M:%S`）。 */
        private val LABEL_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * 备份保留上限（**mobile 特有**，PC 无上限）。
         *
         * PC 跑在桌面文件系统、用户可自行清理 users/，故不做裁剪；Android 的备份落在
         * `filesDir/users/` 私有目录，**用户看不到也删不掉**，若不裁剪会随每次「清空历史」
         * 无限堆积。故每次生成备份后只保留最近 [MAX_BACKUPS] 份（按文件名时间戳倒序），
         * 更旧的直接删除。
         */
        const val MAX_BACKUPS: Int = 5
    }

    private val historyFile = File(usersDir, "$profileId.json")

    private var loaded: MutableList<GachaRecord> = mutableListOf()
    private val loadedTotals = LinkedHashMap<String, Int>()
    private var loadedTotal: Int? = null

    /** 最近一次 [merge] 的详细诊断（供调用方报警消费）；未合并过为 null。 */
    var lastReport: MergeReport? = null
        private set

    /** 当前历史记录（按 timestamp 倒序；副本，改动请走 [merge]）。 */
    val records: List<GachaRecord> get() = loaded.toList()

    /** 各视图权威 total 快照（副本）。 */
    val viewTotals: Map<String, Int> get() = LinkedHashMap(loadedTotals)

    /** 上次落库时的「全部卡池」权威 total（下次合并的 Δ 基准）；未知为 null。 */
    val lastTotal: Int? get() = loadedTotal

    val isEmpty: Boolean get() = loaded.isEmpty()

    /** 历史文件（供设置页「数据维护」展示 / 导出 / 备份列表）。 */
    val file: File get() = historyFile

    init {
        load()
    }

    /**
     * 从磁盘加载历史（文件不存在 / 损坏 → 空历史，**不抛、不删原文件**，留给人工排查）。
     * 加载后按需回填 total（兼容无 total 快照的旧文件）。
     */
    fun load() {
        loaded = mutableListOf()
        loadedTotals.clear()
        loadedTotal = null
        lastReport = null
        if (!historyFile.exists()) return

        val root = try {
            MiniJson.decode(historyFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val map = root as? Map<*, *> ?: return

        val recs = map[KEY_RECORDS] as? List<*> ?: emptyList<Any?>()
        for (r in recs) {
            val m = r as? Map<*, *> ?: continue
            toRecord(m)?.let { loaded.add(it) }
        }
        (map[KEY_VIEW_TOTALS] as? Map<*, *>)?.forEach { (k, v) ->
            toInt(v)?.let { loadedTotals[k.toString()] = it }
        }
        loadedTotal = toInt(map[KEY_TOTAL])
        backfillTotalIfComplete()
    }

    /**
     * 把**本次会话去重后新增**的记录按位置并入历史并落库，返回新增条数。
     *
     * @param sessionRecords 本次会话新增（已由 `SessionDedup` 归一化到历史坐标系）
     * @param viewTotals 本次会话的 `{视图: 权威 total}` 快照；其中「全部卡池」的值成为
     *   下次合并的 Δ 基准
     * @return 落库新增条数（详细诊断见 [lastReport]）
     */
    fun merge(sessionRecords: List<GachaRecord>, viewTotals: Map<String, Int> = emptyMap()): Int {
        if (viewTotals.isNotEmpty()) {
            for ((k, v) in viewTotals) loadedTotals[k.toString()] = v
        }
        allPoolsTotal()?.let { loadedTotal = it }

        val report = PositionAlign.mergeByPosition(loaded, sessionRecords, shift = 0)
        lastReport = report

        if (report.replacedEvents.isNotEmpty()) {
            // 部分事件被新完整事件整体替换：移除历史旧记录（绝不静默，调用方读 lastReport 报警）
            val replaced = report.replacedEvents.toSet()
            loaded.removeAll { it.timestamp in replaced }
        }
        if (report.added.isNotEmpty()) {
            loaded.addAll(report.added)
            loaded.sortByDescending { it.timestamp }
        }
        // 无新增也落盘：total 快照必须写下去，否则下次仍按旧基准推 Δ、位置整体错位
        writePayload()
        return report.added.size
    }

    /**
     * 清空历史（内存 + 磁盘），清空前自动备份现有非空历史。
     *
     * total 快照一并清空 —— 清空后重新起算 Δ 基准，否则下次合并会按旧基准推出一个巨大的
     * Δ 把新记录位置整体挪错。用于「放弃旧数据、从当前抓取重新积累」。
     *
     * @return 清空前生成的备份文件；磁盘上无有效历史可备份时为 null
     */
    fun clear(): File? {
        loaded = mutableListOf()
        loadedTotals.clear()
        loadedTotal = null
        lastReport = null
        return writePayload()
    }

    /**
     * 扫描当前账号的可用还原点，**按备份时刻倒序**（镜像 PC `HistoryService.backups`）。
     *
     * 规约 `<profileId>_<yyyyMMdd_HHmmss>[_N].json`；**不合规的一律不列入**（防误选 ——
     * `usersDir` 下还有账号归档等其它文件）。无合规则返回空列表。
     *
     * 关于「账号删除归档」与备份**同规约**：`ProfileStore.delete` 归档出的
     * `<id>_<stamp>.json` 与本类的备份名完全同形（PC 亦然，`profiles._unique_archive` 的
     * 注释就写着「与 history._backup 命名一致」）。但归档属于**已被删除的账号**，其 id 已
     * 从注册表移除、永不可能成为 activeId，故按 `<profileId>_` 前缀过滤后不会混入本列表。
     */
    fun backups(): List<BackupInfo> =
        (usersDir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .mapNotNull { f ->
                val whenTime = parseBackupTs(f.name) ?: return@mapNotNull null
                val count = countRecords(f)
                BackupInfo(f, whenTime, count, labelOf(whenTime, count, f.name))
            }
            // 同秒多份（`_N` 后缀）时间戳完全相同，仅按 whenTime 排会退化成「目录枚举顺序」——
            // 裁剪时会把**最新**的几份当最旧的删掉。故以 `_N` 作次级键（N 越大创建越晚）。
            .sortedWith(
                compareByDescending<BackupInfo> { it.whenTime }
                    .thenByDescending { dupIndexOf(it.file.name) },
            )

    /**
     * 用某个还原点覆盖当前历史（镜像 PC `HistoryService.restore`），返回还原前另存的备份。
     *
     * 与 PC 的**一处刻意差异**：PC 无条件先 `_backup()` 再覆盖，当前历史为空时会在还原点
     * 列表里塞进一份 `（0 条）` 的空备份；这里改为**仅当内存里确有记录时**才另存，避免
     * 「反复还原」把还原点列表刷成噪音。语义不变（空状态无需保命备份）。
     *
     * @param backup 目标还原点（应来自 [backups]）
     * @return 还原前另存的备份；当前无记录可存时为 null
     * @throws IllegalArgumentException 备份文件不存在、或不属于当前账号
     */
    fun restore(backup: File): File? {
        require(backup.isFile) { "还原点不存在：${backup.name}" }
        require(parseBackupTs(backup.name) != null) { "不是本账号的备份：${backup.name}" }

        // 还原前另存当前。此处**刻意不走 [backupFile]**：那条路会顺带裁剪，而当目标还原点
        // 恰好位于保留窗口之外时会被删掉，随后 copyTo 直接抛 NoSuchFileException。
        val current = if (loaded.isNotEmpty() && historyFile.exists()) {
            uniqueBackupFile().also { historyFile.copyTo(it, overwrite = false) }
        } else {
            null
        }
        backup.copyTo(historyFile, overwrite = true)
        load()
        pruneBackups()
        return current
    }

    /** 把当前内存状态落库；返回本次生成的备份（无备份时为 null）。 */
    private fun writePayload(): File? {
        if (!usersDir.exists()) usersDir.mkdirs()
        val payload = buildPayload()

        var backup: File? = null
        if (historyFile.exists()) {
            val existing = try {
                MiniJson.decode(historyFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null // 损坏不可读：下面按「需保命备份」处理，不直接覆盖丢弃
            }
            if (existing != null && existing == payload) return null // 内容零变化：不写盘、不备份
            val existingRecords = (existing as? Map<*, *>)?.get(KEY_RECORDS)
            val hasRecords = existingRecords is List<*> && existingRecords.isNotEmpty()
            if (existing == null || hasRecords) backup = backupFile()
        }
        historyFile.writeText(MiniJson.encodePretty(payload, indent = 2), Charsets.UTF_8)
        return backup
    }

    /** 构造落库 payload；数值一律用 [Long]，好让「解码已有文件 == 新 payload」的结构判等成立。 */
    private fun buildPayload(): Map<String, Any?> {
        val recs = ArrayList<Any?>(loaded.size)
        for (r in loaded) {
            recs.add(
                linkedMapOf<String, Any?>(
                    "pool_id" to r.poolId,
                    "item_id" to r.itemId,
                    "timestamp" to r.timestamp,
                    "batch_seq" to r.batchSeq.toLong(),
                    "position_in_batch" to r.positionInBatch.toLong(),
                )
            )
        }
        val totals = LinkedHashMap<String, Any?>()
        for ((k, v) in loadedTotals) totals[k] = v.toLong()
        return linkedMapOf(
            KEY_RECORDS to recs,
            KEY_VIEW_TOTALS to totals,
            KEY_TOTAL to loadedTotal?.toLong(),
        )
    }

    /** 备份当前历史为 `<profileId>_<时间戳>.json`（同秒冲突加 `_N`），对齐 PC 命名规约。 */
    private fun backupFile(): File {
        val backup = uniqueBackupFile()
        historyFile.copyTo(backup, overwrite = false)
        pruneBackups()
        return backup
    }

    /**
     * 算一个不与现有文件冲突的备份名 `<profileId>_<时间戳>[_N].json`（不落盘、不裁剪）。
     *
     * 同秒内编号**单调递增、绝不复用**被裁剪掉的旧槽位：无后缀名永远只属于该秒的第一份。
     * 否则裁剪删掉无后缀/低位 `_N` 后，下一次生成会复用该名，产出「最新却按 N=0 排最旧」
     * 的文件，`pruneBackups` 会把刚生成的这份当最旧误删 ——「最新备份丢失」。
     */
    private fun uniqueBackupFile(): File {
        val ts = LocalDateTime.now().format(TS_FMT)
        val base = "${profileId}_$ts"
        var maxN = 0
        for (f in usersDir.listFiles() ?: emptyArray()) {
            val t = parseBackupTs(f.name) ?: continue
            if (t.format(TS_FMT) != ts) continue // 只看当前这一秒
            maxN = maxOf(maxN, dupIndexOf(f.name))
        }
        return if (maxN == 0 && !File(usersDir, "$base$JSON_SUFFIX").exists()) {
            File(usersDir, "$base$JSON_SUFFIX") // 该秒首份 → 无后缀
        } else {
            File(usersDir, "${base}_${maxN + 1}$JSON_SUFFIX") // 单调递增，不碰已删除的老编号
        }
    }

    /**
     * 裁剪超出 [MAX_BACKUPS] 的旧备份（mobile 特有，见常量注释）。
     *
     * 只在**新备份生成后**调用，故不会误删刚生成的那份。删除失败静默降级 —— 下轮生成备份
     * 时会再试，最多多占一点空间，不影响正确性。
     */
    private fun pruneBackups() {
        val all = backups()
        if (all.size <= MAX_BACKUPS) return
        all.drop(MAX_BACKUPS).forEach { it.file.delete() }
    }

    /**
     * 从备份文件名解析备份时刻；不合规返回 null。
     *
     * 规约 `<profileId>_<yyyyMMdd_HHmmss>[_N].json`（镜像 PC `_parse_backup_ts`）。
     * `<profileId>.json`（历史本体）不匹配前缀，天然被排除。
     */
    private fun parseBackupTs(name: String): LocalDateTime? {
        val prefix = "${profileId}_"
        if (!name.startsWith(prefix) || !name.endsWith(JSON_SUFFIX)) return null
        var tsPart = name.substring(prefix.length, name.length - JSON_SUFFIX.length)
        val parts = tsPart.split("_")
        if (parts.size == 3 && parts[2].toIntOrNull() != null) {
            tsPart = parts.take(2).joinToString("_") // 剥离同秒 `_N` 后缀
        }
        return try {
            LocalDateTime.parse(tsPart, TS_FMT)
        } catch (e: Exception) {
            null
        }
    }

    /** 读一份备份内的记录条数（镜像 PC `_count_records`；读取失败按 0）。 */
    private fun countRecords(f: File): Int = try {
        val root = MiniJson.decode(f.readText(Charsets.UTF_8)) as? Map<*, *>
        (root?.get(KEY_RECORDS) as? List<*>)?.size ?: 0
    } catch (e: Exception) {
        0
    }

    /**
     * 还原点展示串，如 `2026-09-16 19:17:37（521 条）`。
     *
     * 同秒多份备份（`_N` 后缀）必须区分：否则两份 label 完全相同，对话框无法选中第二份
     * （`_N` 越大创建越晚）。此点与 PC 处理一致。
     */
    private fun labelOf(whenTime: LocalDateTime, records: Int, name: String): String {
        val n = dupIndexOf(name)
        return "${whenTime.format(LABEL_FMT)}（$records 条）" + if (n > 0) " 同秒_$n" else ""
    }

    /** 同秒 `_N` 后缀的 N；无后缀返回 0（N 越大创建越晚，见 [backups] 的排序注释）。 */
    private fun dupIndexOf(name: String): Int {
        val tail = name.substring(profileId.length + 1, name.length - JSON_SUFFIX.length)
        val parts = tail.split("_")
        return if (parts.size == 3) parts[2].toIntOrNull() ?: 0 else 0
    }

    /** 当前已知的「全部卡池」权威 total；未知返回 null。 */
    private fun allPoolsTotal(): Int? {
        for (key in ALL_POOLS_VIEW_KEYS) loadedTotals[key]?.let { return it }
        return null
    }

    /**
     * 旧格式历史的**条件回填**（镜像 PC `_maybe_backfill_total`）：无 total 快照、且事件
     * 全部完整（size ∈ {1,10}）时，历史条数即当时的权威总数 → 回填 total 与 `0@0`。
     * 存在部分事件时无法确知真实总数（缺的记录数不可知），维持 null 由 Δ=0 兜底。
     */
    private fun backfillTotalIfComplete() {
        if (loadedTotal != null || loaded.isEmpty()) return
        val sizes = HashMap<Long, Int>()
        for (r in loaded) sizes[r.timestamp] = (sizes[r.timestamp] ?: 0) + 1
        if (sizes.values.any { it !in VALID_EVENT_SIZES }) return
        loadedTotal = loaded.size
        if (!loadedTotals.containsKey("0@0")) loadedTotals["0@0"] = loaded.size
    }

    private fun toRecord(m: Map<*, *>): GachaRecord? {
        val poolId = m["pool_id"]?.toString() ?: return null
        val itemId = m["item_id"]?.toString() ?: return null
        val ts = toLong(m["timestamp"]) ?: return null
        return GachaRecord(
            poolId = poolId,
            itemId = itemId,
            timestamp = ts,
            batchSeq = toInt(m["batch_seq"]) ?: 0,
            positionInBatch = toInt(m["position_in_batch"]) ?: 0,
        )
    }

    private fun toLong(v: Any?): Long? = when (v) {
        null -> null
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun toInt(v: Any?): Int? = toLong(v)?.toInt()
}
