package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import com.jinlin.gacha.assistant.core.sync.Record
import com.jinlin.gacha.assistant.core.sync.SyncFile
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 跨端记录互通 —— Android 侧 **IO 事务壳**（薄层：整组原子落库 + 只读快照 + 导出落盘）。
 *
 * ### 分层位置（与 PC 逐层对齐）
 * | 层 | PC | Android |
 * |---|---|---|
 * | 纯函数（解析 / 比对 / 计划 / 封装） | `storage/sync_file.py` | [SyncFile]（M4） |
 * | **IO 事务壳** | `storage/sync_apply.py` | **本类**（M5） |
 * | UI | `gui/sync_dialog.py` | `ui/screens/SyncImportScreen` / `SyncExportDialog`（M5） |
 *
 * ### 为什么单独一层
 * 两端既有落库 API 都是**单账号**的（PC `HistoryService` / Android [HistoryStore]）。互通要支持
 * 「一份文件含多个账号」，且用户 2026-09-21 裁定 **整组原子**：任一账号写失败 ⇒ 本批**全部回滚**
 * （契约 §6.2-b）。故在 IO 层包一层事务壳；纯函数层与既有落库算法**一行不动**。
 *
 * ### 与 PC 实现的一处结构性差异（**有意**，2026-09-21 定案「做法 A」）
 * PC 的 `sync_apply` 自己拼 payload、自己 `tmp + os.replace`、自己造备份名。Android 不重复实现
 * ——**「写盘三件事」（先备份 / 原子写 / 裁剪）在 [HistoryStore] 里只有一份实现**，本类通过
 * [HistoryStore.replaceRecords] 这个「小门」进去复用，不另写一套。于是：
 * - 备份命名 / 裁剪（[HistoryStore.MAX_BACKUPS] / `BUDGET_BYTES`）/ 跨端键透传 全部继承既有语义；
 * - 本类只负责**编排水位**：解算 → 逐个落盘 → 逆序回滚 → 撤掉本批新建账号。
 *
 * ### 整组原子怎么落（契约 §6.2 a/b/e + §6.3）
 * 1. **先解算、后落盘**：先把「写哪个文件、写前原文是什么」全部备齐，此刻**一个字节都还没写**；
 * 2. 逐个：`replaceRecords`（内部 = 备份现有文件 → `tmp + rename` 原子写）；
 * 3. 任一步抛错 ⇒ **逆序回滚**：把每个已入账账号还原为**写前原文**（写前不存在则删掉该文件），
 *    再撤掉本批**新建**的账号。
 *
 * ⚠️ **回滚依据是「写前原文」，不是磁盘上的备份文件**。备份只是按既有命名规约
 * （`<pid>_<yyyyMMdd_HHmmss>[_N].json`）留给用户在「还原抽卡数据」列表里选的，**不承担事务语义**
 * —— 这样即使某账号历史为空（按既有规约不产生备份文件），回滚依然精确。与 PC 的
 * `sync_apply._rollback` 同一约定。
 *
 * ### ⚠️ 边界（承重约定，不得越）
 * - 本类**不新增任何用户可见的删除入口**。唯一动用 `File.delete()` 的两处，都是**事务语义本身**：
 *   ① 回滚时删掉**本批自己刚写的**文件（写前不存在）；② 回滚成功后清掉**本批自己刚生成的**备份。
 *   删用户历史文件的唯一入口仍是 `StorageCleaner.delete()` 单点三闸。
 * - **抓包中不可调用**（调用前置，调用方判 `running`，两端同约定）。本类不认识抓包状态，无法自查。
 *   理由与「清空 / 还原在抓包中置灰」同源：`HistoryStore` 的写入者唯一性靠「抓包中不写」维持，
 *   否则会出现**静默漏写 = 丢数据**。
 * - **不做换行翻译**：导出文件按 [SyncFile.dumps] 的字节原样写 ⇒ 同一份内容在任何平台逐字节相同，
 *   两端样本可直接对哈希（对齐 PC `write_export_file` 的 `newline=""`）。
 */
object SyncBackend {

    /** 原子写的临时文件后缀（与被写文件同目录，保证 `rename` 是同卷重命名）。 */
    private const val TMP_SUFFIX = ".sync-tmp"

    /** 导出信封 `exported_at` 的格式（对齐 PC `strftime("%Y-%m-%dT%H:%M:%S")`，本地时间无时区）。 */
    private val ISO_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    // -----------------------------------------------------------------------
    // 结果结构（对齐 PC `ApplyResult` / `written[]` 的字段集）
    // -----------------------------------------------------------------------

    /**
     * 单个账号的落库摘要（PC `ApplyResult.written` 的一项）。
     *
     * @param records 落库后的条数
     * @param backup 本次生成的备份文件；无备份（新建账号 / 幂等跳过）为 `null`
     * @param changed `false` = 该账号与磁盘内容**逐字相同**，被幂等闸跳过（未写盘、未备份）
     */
    data class Written(
        val pid: String,
        val name: String,
        val mode: String,
        val records: Int,
        val backup: File?,
        val changed: Boolean,
    )

    /**
     * 整组落库的结果。
     *
     * @param ok 是否成功。**失败时 [written] 恒为空** —— 整组回滚后不留半份结果。
     * @param error 失败原因（人话，可直接进对话框）
     * @param created 本批**新建**的账号 id（成功时用于刷新账号列表）
     */
    data class ApplyResult(
        val ok: Boolean,
        val error: String? = null,
        val written: List<Written> = emptyList(),
        val created: List<String> = emptyList(),
    )

    // -----------------------------------------------------------------------
    // 内部：准备项与回滚日志
    // -----------------------------------------------------------------------

    /** 阶段 1 备齐的「一个账号要落什么」（此刻磁盘未动）。 */
    private class Prepared(
        val pid: String,
        val name: String,
        val mode: String,
        val file: File,
        val existed: Boolean,
        val prevText: String?,
        val write: SyncFile.WriteBundle,
    )

    /**
     * 回滚日志的一条（对齐 PC `journal[]`）。
     *
     * [backup] 先留 `null`、由 [HistoryStore.replaceRecords] 的 `onBackupCreated` 回调补上 ——
     * 回调时刻是「备份已生成、写盘尚未开始」，故**写盘中途失败也能被回滚覆盖**。
     */
    private class Journal(
        val pid: String,
        val file: File,
        val existed: Boolean,
        val prevText: String?,
        var backup: File? = null,
    )

    // -----------------------------------------------------------------------
    // ① 设备侧只读快照（导入比对 / 导出取材两处共用）
    // -----------------------------------------------------------------------

    /**
     * 读出设备侧**全部账号现值**（只读快照）。
     *
     * **直接读盘**而不是问 [HistoryStore]：① 落库动作一律先收尾再合并，故非抓包态下磁盘即真相；
     * ② 不必给 [HistoryStore] 这个承重类加新访问器（对齐 PC `load_device_accounts` 的同一考虑）。
     *
     * 文件缺失 / 损坏时该账号按**空记录**返回（不抛、不跳过 —— 账号列表必须与 [ProfileStore]
     * 一一对应，否则导入侧的「同名账号」判据会漏项）。
     *
     * @return 顺序 = [ProfileStore.list] 的账号显示顺序
     */
    fun loadDeviceAccounts(profiles: ProfileStore): List<SyncFile.DeviceAccount> =
        profiles.list().map { p ->
            val payload = readJson(profiles.userFile(p.id))
            SyncFile.DeviceAccount(
                id = p.id,
                name = p.name,
                records = recordsOf(payload?.get("records")),
                total = SyncFile.asLong(payload?.get("total")),
                viewTotals = SyncFile.viewTotalsOf(payload?.get("view_totals")),
            )
        }

    // -----------------------------------------------------------------------
    // ② 整组原子落库
    // -----------------------------------------------------------------------

    /**
     * 把 [SyncFile.SyncPlan] **整组原子**落库。
     *
     * @param profiles 账号注册表（新建账号、校验目标账号都经它；它是账号真相源，**必须是 UI 持有的
     *   同一个实例** —— 导入新建的账号要立刻反映到账号列表）
     * @param plan [SyncFile.planImport] 的成功结果
     * @return [ApplyResult]；失败时 [ApplyResult.written] 恒为空
     *
     * ⚠️ 调用前置：**抓包中不可调用**（见类头「边界」）。
     * ⚠️ 调用后：成功时调用方须调**一次** `HistoryEpoch.bump()` 刷新记录页 / 统计页
     * （契约 §6.2-d「一次刷新」）。**刻意不在这里 bump** —— 沿用「`HistoryStore.clear()` /
     * `restore()` 由调用方 bump」的既有分工，让本类保持「纯 IO、可单测」。
     */
    fun applyPlan(profiles: ProfileStore, plan: SyncFile.SyncPlan): ApplyResult {
        if (!plan.ok) return ApplyResult(ok = false, error = "计划无效（planImport 未成功产出）")
        if (plan.accounts.isEmpty()) {
            return ApplyResult(ok = false, error = "计划为空：没有要落库的账号")
        }

        val usersDir = profiles.usersDir
        if (!usersDir.exists()) usersDir.mkdirs()

        // ---- 阶段 1：建账号 + 备齐写前状态（此刻磁盘一个字节都还没动）----
        val prepared = ArrayList<Prepared>(plan.accounts.size)
        val created = ArrayList<String>()
        try {
            for (entry in plan.accounts) {
                val pid: String
                val name: String
                if (entry.mode == SyncFile.MODE_NEW) {
                    val p = profiles.create(entry.name)
                    created += p.id
                    pid = p.id
                    name = p.name
                } else {
                    val target = entry.target
                        ?: throw IllegalStateException("合并项缺少目标账号 id")
                    val p = profiles.list().firstOrNull { it.id == target }
                        ?: throw IllegalStateException("目标账号不存在: $target")
                    pid = p.id
                    name = p.name
                }
                val file = profiles.userFile(pid)
                val existed = file.exists()
                prepared += Prepared(
                    pid = pid,
                    name = name,
                    mode = entry.mode,
                    file = file,
                    existed = existed,
                    // ⚠️ 只在文件存在时才去读：读不出（损坏 / 被占用）返回 null 而 `existed` 仍为
                    // true ⇒ 回滚阶段会「保留现状」，**绝不当成「原本不存在」去删文件**
                    //（那会把用户一份虽然坏、但可能可修的记录直接抹掉）。
                    prevText = if (existed) readTextOrNull(file) else null,
                    write = entry.write,
                )
            }
        } catch (e: Exception) {
            // 准备阶段失败：磁盘未动，撤掉已建账号即可
            undoCreated(profiles, created)
            return ApplyResult(ok = false, error = "准备阶段失败：${e.message}")
        }

        // ---- 阶段 2：逐个「备份 → 原子写」；journal 先入账再写，供逆序回滚 ----
        val journal = ArrayList<Journal>(prepared.size)
        val written = ArrayList<Written>(prepared.size)
        try {
            for (item in prepared) {
                // ⚠️ 先入账再写：写一半失败也能被回滚覆盖（对齐 PC `journal.append(item)` 的位置）。
                // 备份名此刻还不知道，由下面的回调在「备份已生成、写盘未开始」时补进来。
                val je = Journal(item.pid, item.file, item.existed, item.prevText)
                journal += je

                val store = HistoryStore(usersDir, item.pid)
                val backup = store.replaceRecords(
                    rawRecords = item.write.records,
                    total = item.write.total,
                    viewTotals = item.write.viewTotals,
                    keepRestorePoint = true,
                ) { b -> je.backup = b }

                // changed 用「写前原文 vs 写后原文」判定 —— 幂等闸在 HistoryStore 内部，
                // 同内容时它直接返回 null 且不写盘 ⇒ 两段文本必然相同。这样本类**不需要**
                // 复制一份 payload 解算逻辑去比对（那会让「单源」破功）。
                val changed = readTextOrNull(item.file) != item.prevText
                written += Written(
                    pid = item.pid,
                    name = item.name,
                    mode = item.mode,
                    records = item.write.records.size,
                    backup = backup,
                    changed = changed,
                )
            }
        } catch (e: Exception) {
            rollback(journal)
            undoCreated(profiles, created)
            return ApplyResult(ok = false, error = "写入失败，已全部回滚：${e.message}")
        }

        return ApplyResult(ok = true, written = written, created = created)
    }

    /**
     * 逆序把每个已入账账号还原为写前状态，并清掉本批自己造的备份。
     *
     * **尽力而为**：任一账号还原失败只跳过它、继续处理其余 —— 绝不让异常往上冒，否则调用方
     * 拿到的是「回滚过程的异常」而非真正的失败原因（对齐 PC `_rollback`）。
     */
    private fun rollback(journal: List<Journal>) {
        for (je in journal.asReversed()) {
            var restored = true
            try {
                when {
                    !je.existed -> if (je.file.exists()) {
                        // 写前不存在 ⇒ 本批自己刚造出来的，删掉即可（事务语义，非用户数据删除入口）
                        je.file.delete()
                    }
                    je.prevText == null -> {
                        // 写前就读不出（损坏 / 被占用）：任何「还原」都可能更糟，保留现状
                        restored = false
                    }
                    else -> atomicWriteText(je.file, je.prevText)
                }
            } catch (e: Exception) {
                restored = false
            }
            // ⚠️ 只在原文件**已成功还原**之后才丢备份 —— 还原失败时这份备份就是唯一的兜底副本
            if (restored) discardBackup(je.backup)
        }
    }

    /**
     * 撤掉本批新建的账号。
     *
     * 调用时机固定在**文件回滚之后** —— 那时这些账号的 `users/<id>.json` 已被删掉，
     * [ProfileStore.delete] 找不到文件就不会再产生一份归档副本（不留垃圾）。
     */
    private fun undoCreated(profiles: ProfileStore, created: List<String>) {
        for (pid in created.asReversed()) {
            try {
                profiles.delete(pid)
            } catch (e: Exception) {
                // 回滚尽力而为，失败不抛
            }
        }
    }

    /** 删掉**本批自己刚生成**的备份文件（回滚成功后再留着只会污染「还原抽卡数据」列表）。 */
    private fun discardBackup(backup: File?) {
        if (backup == null) return
        try {
            if (backup.exists()) backup.delete()
        } catch (e: Exception) {
            // 尽力而为
        }
    }

    // -----------------------------------------------------------------------
    // ③ 导出侧落盘 + 平台元信息（时钟与来源由本层注入，纯函数层不取时钟）
    // -----------------------------------------------------------------------

    /**
     * 写导出的互通文件。
     *
     * ⚠️ **不做平台换行翻译**：[SyncFile.dumps] 产出什么字节就写什么字节 ⇒ 同一份内容在任何
     * 平台逐字节相同（对齐 PC `write_export_file` 的 `newline=""`）。
     */
    fun writeExportFile(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    /**
     * 写导出的互通文件到任意输出流 —— 「存到手机」走 SAF `content://` 时用。
     *
     * ⚠️ 与 [writeExportFile] 是**同一条字节规矩**：UTF-8、不做平台换行翻译 ⇒ 同一份内容
     * 无论落 `File` 还是落用户挑的 SAF 目标，都逐字节相同（对齐 PC `write_export_file`
     * 的 `newline=""`）。流由本函数负责关闭（`use`）。
     */
    fun writeExportStream(out: OutputStream, text: String) {
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** 导出信封的 `exported_at`（本地时间，秒精度，无时区）。 */
    fun nowIso(): String = LocalDateTime.now().withNano(0).format(ISO_FMT)

    /**
     * 导出信封的 `source`（平台标识）。
     *
     * @param appVersion 调用方注入（避免本层依赖 `BuildConfig` ⇒ 保持可单测）
     */
    fun exportSource(appVersion: String): Map<String, Any?> = linkedMapOf(
        "platform" to "android",
        "app" to "gacha-assistant",
        "app_version" to appVersion,
    )

    // -----------------------------------------------------------------------
    // 私有小工具
    // -----------------------------------------------------------------------

    /**
     * `tmp + rename` 原子写（回滚专用；正向落盘走 [HistoryStore.writeAtomic]，此处不重复）。
     *
     * rename 失败（极少见）时降级为直接写并清掉临时文件 —— 降级等价于旧行为，不引入新失败面。
     */
    private fun atomicWriteText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(file)) return
        } catch (e: Exception) {
            // 落到下面的降级分支
        }
        file.writeText(text, Charsets.UTF_8)
        tmp.delete()
    }

    /** 读文本；不存在或读不出返回 `null`（不抛）。 */
    fun readTextOrNull(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    /** 读 JSON 对象；不存在 / 解析失败 / 根不是对象一律 `null`（不抛）。 */
    private fun readJson(file: File): Map<*, *>? {
        val text = readTextOrNull(file) ?: return null
        return try {
            MiniJson.decode(text) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 取 `records[]`，逐条做**浅拷贝**并把键转成字符串（对齐 PC `[dict(r) for r in raw]`）。
     *
     * ⚠️ 拷贝是**浅**的：逐条记录若含嵌套结构则仍共享引用。互通载荷的记录是**扁平的 5 键**，
     * 不存在嵌套，故与 PC 的 `dict(r)` 等价。
     */
    private fun recordsOf(raw: Any?): List<Record> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<Record>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val copy = LinkedHashMap<String, Any?>(m.size)
            for ((k, v) in m) copy[k.toString()] = v
            out += copy
        }
        return out
    }
}
