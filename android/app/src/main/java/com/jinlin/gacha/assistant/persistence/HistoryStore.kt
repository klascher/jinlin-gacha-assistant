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
 * 上一场抓包的明细（U5，2026-09-18）—— 落 `users/<id>.json` 的 `last_capture_detail`。
 *
 * @param startedAt 开抓时刻（ISO-8601 本地日期时间，见 `HistoryStore.CAPTURE_DETAIL_FMT`）
 * @param endedAt 停抓时刻（同一格式）
 * @param added **该场新增**条数（不是结束时的累计）—— 记录页三行里「上次 / 本次」都回答
 *   「这段时间新增了多少」；取累计会与「总计」语义重叠，且让两行口径不一致。
 */
data class CaptureDetail(
    val startedAt: String,
    val endedAt: String,
    val added: Int,
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
 * Android 特有的三条补充约定（**2026-09-17 两轮修订**）：
 * - **还原点 ≠ 每次写盘都留一份**：周期兜底落盘（每 5 秒）走 [merge] 的 `keepRestorePoint = false`，
 *   **只写历史、不留还原点**；否则备份环约 25 秒即被自动同步刷满，把用户主动产生的还原点
 *   （清空前 / 还原前 / 停抓收尾）挤出并删除（缺陷与修复见 `09-设置模块设计.md` §15）；
 * - **裁剪 = 最近 [MAX_BACKUPS] 份 + 字节预算 [BUDGET_BYTES]**（[pruneBackups]）：份数 5 → 30、
 *   另加 20 MB 软预算（超预算时从最旧删起，但**保底留 1 份**）。**不留「永久锚点」** —— 该设计
 *   曾短暂实现（保留最早的非空备份），经用户澄清诉求是「回退到**出问题之前**」而非「回到最早」
 *   后否决：锚点占槽位而收益为零，见 `09-设置模块设计.md` §15 的改判记录；
 * - **原子写**（[writeAtomic]）：tmp + rename。周期落盘不再留副本后，「写盘中途被杀留下半截
 *   JSON」失去兜底 —— 而半截 JSON 会被 [load] 静默当成「空历史」，比报错更危险，故从根上消除。
 *
 * ⚠️ **写入者唯一性不变量**：本类在工程内有多个实例（`GachaVpnService` 写入 + UI / 统计只读），
 * 各持一份内存副本、**磁盘文件是唯一汇合点**。规则：**任一时刻只允许一个实例写同一文件，
 * UI 侧实例一律只读**（当前靠「Service 停抓后置 null」+「清空 / 还原在抓包中置灰」两条约束维持）。
 * 放开前必须先把写操作收敛到单点或加文件锁 —— 否则会出现**静默漏写 = 丢数据**。
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

        /**
         * 停抓时算出的**缺页明细**（U5，2026-09-18）：`{"0@0": [0, 5]}`（视图 → 缺失 offset 数组）。
         *
         * 落进**历史文件**（`users/<id>.json`）而不是 `settings.json`，两条理由：
         * 1. 它是**按账号**的，而历史文件本身就按账号分文件，不需要 profile_id 映射；
         * 2. PC 读历史只用 `records` / `view_totals` / `total` 三个键，**未知键静默忽略**
         *    ⇒ 加**新键**零兼容风险（反例：改**已有键的值类型**是另一回事，见 10 号稿 §17.12.3）。
         *
         * ⇒ 写入者仍是本类**唯一**，不为一个明细数组引入第二个写盘面。
         */
        private const val KEY_SCAN_GAPS = "scan_gaps"
        /**
         * 该账号**抓过哪些渠道包**（渠道服支持，2026-09-18）。
         *
         * 用途：换渠道时判断「本次渠道是否与该账号历史里的渠道不同」⇒ 提醒用户
         * 「两个渠道的数据会混进同一份历史、保底与统计会串」。
         *
         * 为什么落在**账号级**而不是每条记录里：
         * 1. `records` schema 保持与 PC **逐字一致**（PC 只读 `records`/`view_totals`/`total`，
         *    **未知键静默忽略**）⇒ 加这个键**不破坏**「历史文件可互通」；
         * 2. 渠道是「这个账号在本设备上被抓过的来源」，本就是账号级属性，塞进每条记录纯属冗余。
         */
        private const val KEY_SOURCE_PACKAGES = "source_packages"

        /**
         * 上一场抓包的**明细**（U5，2026-09-18）：`{started_at, ended_at, added}`。
         *
         * 不要与 `settings.json` 的 `last_capture` 混为一谈：那个键 **PC 也在用**
         * （`storage/settings.py:70-96`，值是**单个**时间字符串，PC 拿它显示「最近更新」），
         * 且 `load_last_capture` 有 `isinstance(val, str)` 守卫 —— 把值升级成对象 PC 不崩，
         * 但会**静默变空串**，属真兼容问题。本键是**新增**的、只由 Android 读写的内部键，
         * 两者分属两个文件、互不影响。
         *
         * 存在的理由：`last_capture` 只有**一个时刻**（抓包「完成」），既没有开始时刻、
         * 也没有新增条数 ⇒ 记录页「上次　09-17 21:08–21:31 · 601 条」这三样它一样都给不出。
         */
        private const val KEY_LAST_CAPTURE_DETAIL = "last_capture_detail"

        /**
         * 本类**拥有**（自己生产、并负责写回）的顶层键。其余顶层键一律按「跨端键」原样透传，
         * 见 [foreignKeysOf]。
         */
        private val OWNED_KEYS: Set<String> = setOf(
            KEY_RECORDS,
            KEY_VIEW_TOTALS,
            KEY_TOTAL,
            KEY_SCAN_GAPS,
            KEY_SOURCE_PACKAGES,
            KEY_LAST_CAPTURE_DETAIL,
        )

        /** 明细里的三个子键（mobile 内部约定）。 */
        private const val KEY_DETAIL_STARTED_AT = "started_at"
        private const val KEY_DETAIL_ENDED_AT = "ended_at"
        private const val KEY_DETAIL_ADDED = "added"

        /** 备份 / 历史文件的后缀（解析与拼接共用）。 */
        private const val JSON_SUFFIX = ".json"

        private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /**
         * 场次明细的时间格式（ISO-8601 **本地**日期时间，秒级）：`2026-09-18T09:40:12`。
         *
         * 为什么存文本而不是 epoch 毫秒：这是**给人看**的时间戳，落盘后直接打开 json 就能读懂，
         * 且与 `settings.json` 里 PC 那个 `last_capture` 的「格式化字符串」风格一致。
         * UI 层负责换算成人读的 `09:40–09:58`（跨天补月-日）。
         */
        val CAPTURE_DETAIL_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /** 还原点展示用时间格式（对齐 PC `BackupInfo.label` 的 `%Y-%m-%d %H:%M:%S`）。 */
        private val LABEL_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * 备份保留**份数**上限（**mobile 特有**，PC 无上限），2026-09-17 由 5 提到 **30**。
         *
         * PC 跑在桌面文件系统、用户可自行清理 users/，故不做裁剪；Android 的备份落在
         * `filesDir/users/` 私有目录，**用户看不到也删不掉**，若不裁剪会无限堆积。
         *
         * 为什么是 30：一份备份约 125 KB（793 条实测），30 份 ≈ 3.7 MB —— 对手机存储是万分之几，
         * **空间从来不是瓶颈**。真正的瓶颈是「用户的手」（私有目录删不掉），故用算法兜底的同时把
         * 回退窗口放大到「几个月」，并配合 `设置 → 数据清理` 子页的手动删除（U4）把主动权还给用户。
         *
         * ⚠️ 上限是**软**的：须同时满足份数与 [BUDGET_BYTES]，且**刚生成的那份永不删**（见 [pruneBackups]）。
         */
        const val MAX_BACKUPS: Int = 30

        /**
         * 备份总字节预算（20 MB，**mobile 特有**），与 [MAX_BACKUPS] 取**先到者**。
         *
         * 为什么需要它：**份数是不可靠的代理** —— 10 份可能是 1 MB，也可能是 50 MB（取决于历史
         * 条数）。只按份数裁，遇到「历史暴涨」时占用会失控；按字节算才真正可控。
         *
         * 量级参考（按实测 159 B/条）：793 条 ≈ 125 KB（30 份 ≈ 3.75 MB，余量 5 倍）；
         * 约 4,400 条 ≈ 700 KB（30 份 ≈ 21 MB，**刚好触到预算**）；约 20,000 条 ≈ 3.2 MB
         * （30 份 ≈ 96 MB ⇒ 实际只能留 6 份）。
         *
         * ⚠️ 同样**不严格**：若单份历史本身超过预算（需 > 13 万条），删到只剩 1 份仍超预算则
         * **停手认超**（[pruneBackups] 的保底支）。详见 `09-设置模块设计.md` §15。
         */
        const val BUDGET_BYTES: Long = 20L * 1024 * 1024

        /**
         * 由「新 → 旧」的文件序列算出**保留集**；[pruneBackups] 调用它，单测也直接测它。
         *
         * 抽成纯函数（收尺寸、不摸 IO）的原因：预算规则是本批的核心逻辑，而 20 MB 的量级让
         * 「靠写大文件触发裁剪」的集成测试不划算（要往临时目录写 20 MB+）。传入尺寸即可精确构造
         * 「份数没超、只有预算超」与「单份就超预算」这些边界，不产生任何磁盘代价。
         *
         * @param entries 已按**新 → 旧**排序的 `(文件, 字节)`；第 0 项即「刚生成的那份」
         * @param maxBackups 份数上限
         * @param budgetBytes 总字节预算
         * @param protected 额外必须保住的一份（[restore] 传刚被使用的还原点；不在 [entries] 时
         *   按 0 字节计，不影响预算判定）
         */
        internal fun keepSet(
            entries: List<Pair<File, Long>>,
            maxBackups: Int,
            budgetBytes: Long,
            protected: File? = null,
        ): Set<File> {
            if (entries.isEmpty()) return emptySet()
            val sizeOf = HashMap<File, Long>(entries.size)
            for ((f, n) in entries) sizeOf[f] = n

            val keep = LinkedHashSet<File>()
            keep += entries.first().first // 刚生成的（或最新的一份）：任何情况下都不删
            protected?.let { keep += it }

            var bytes = keep.sumOf { sizeOf[it] ?: 0L }
            for ((f, n) in entries) {
                if (f in keep) continue
                if (keep.size >= maxBackups) break // 份数已满
                if (bytes + n > budgetBytes) break // 再收它就超预算（含「保底 1 份仍超」的认超支）
                keep += f
                bytes += n
            }
            return keep
        }
    }

    private val historyFile = File(usersDir, "$profileId.json")

    private var loaded: MutableList<GachaRecord> = mutableListOf()
    private val loadedTotals = LinkedHashMap<String, Int>()
    private var loadedTotal: Int? = null

    /** 最近一次 [merge] 的详细诊断（供调用方报警消费）；未合并过为 null。 */
    var lastReport: MergeReport? = null
        private set

    /** 磁盘上最近一次停抓的缺页明细（视图 → 缺失 offset 升序）；空 = 无缺口或从未停抓过。 */
    private var loadedScanGaps: Map<String, List<Int>> = emptyMap()

    /** 该账号抓过的渠道包（账号级；见 [KEY_SOURCE_PACKAGES]）。 */
    private var loadedSourcePackages: MutableSet<String> = linkedSetOf()

    /** 磁盘上最近一次停抓的场次明细；null = 从未停抓过（记录页显示「上次　暂无记录」）。 */
    private var loadedCaptureDetail: CaptureDetail? = null

    /**
     * 磁盘上**本类不拥有**的顶层键 —— 跨端键透传的落点（U1，2026-09-21）。
     *
     * 为什么需要它：本类原先写盘时只写自己那 6 个键（[OWNED_KEYS]），于是**任何一次
     * Android 写盘都会把别端写的键抹掉**。真实例子：PC `history.py::save()` 会写
     * `"session": {"current_pool_id", "capture_start"}` ⇒ 「手机 → PC → 手机」走一趟，
     * 手机这一写就把 PC 的 `session` 删了。
     *
     * 规则对齐 PC `history.py::_carry_foreign_keys`：
     * - **不认识的一律原样继承**（不解读、不校验），将来任一端新增键都自动跟随；
     * - 例外只有 [clear]：清空 = 放弃记录 ⇒ 连同这些键一起丢弃
     *   （对齐 PC `_write_payload(preserve_unknown=False)`），否则会出现
     *   「记录清空了、会话上下文还留着」的自相矛盾。
     */
    private var loadedForeignKeys: Map<String, Any?> = emptyMap()

    /**
     * 最近一场**已落库**抓包的明细（记录页「上次」行用）；从未停抓过为 null。
     *
     * ⚠️ 它**不随周期落盘更新** —— 只由停抓收尾那条 [merge] 写入。否则「上次」会显示成
     * 「正在进行中的这一场」，且结束时刻每 5 秒跳一次（与「周期落盘不留还原点」同一类坑：
     * 周期性写入不能承担状态点语义）。
     */
    val captureDetail: CaptureDetail? get() = loadedCaptureDetail

    /**
     * 最近一次停抓算出的缺页明细（视图 → 缺失 offset 数组，升序）。
     *
     * ⚠️ offset 是**内部 / 落盘 / PC 对齐**口径；面向用户的文案一律由 UI 层换算成
     * 「第 N 页」（`第 N 页 = offset / PAGE_SIZE + 1`，第 1 页 = 最新），见 10 号稿 §18.3。
     */
    val scanGaps: Map<String, List<Int>> get() = LinkedHashMap(loadedScanGaps)

    /** 该账号抓过的渠道包（副本；用于「换渠道是否会混数据」的判断与展示）。 */
    val sourcePackages: Set<String> get() = LinkedHashSet(loadedSourcePackages)

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
        loadedScanGaps = emptyMap()
        loadedSourcePackages = linkedSetOf()
        loadedCaptureDetail = null
        loadedForeignKeys = emptyMap()
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
        loadedScanGaps = toGapMap(map[KEY_SCAN_GAPS])
        // 渠道标记（账号级，未知/坏值一律降级成空集 —— 与既有「手改坏不该起不来」契约一致）
        loadedSourcePackages = (map[KEY_SOURCE_PACKAGES] as? List<*>)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.toMutableSet() ?: linkedSetOf()
        loadedCaptureDetail = toCaptureDetail(map[KEY_LAST_CAPTURE_DETAIL])
        loadedForeignKeys = foreignKeysOf(map)
        backfillTotalIfComplete()
    }

    /**
     * 把**本次会话去重后新增**的记录按位置并入历史并落库，返回新增条数。
     *
     * @param sessionRecords 本次会话新增（已由 `SessionDedup` 归一化到历史坐标系）
     * @param viewTotals 本次会话的 `{视图: 权威 total}` 快照；其中「全部卡池」的值成为
     *   下次合并的 Δ 基准
     * @param keepRestorePoint 是否在写盘前留一份还原点（默认 `true` = 既有行为）。
     *   **只影响「是否留还原点」，不影响落盘** —— 传 `false` 时历史照写，只是不产生备份，
     *   故「进程被杀不丢数据」的兜底能力不受影响。周期落盘必须传 `false`（见 `09 §15`）。
     *   唯一例外：主文件损坏不可读时**无条件**留档（保命，不走本参数）。
     * @param scanGaps 本场停抓算出的缺页明细（视图 → 缺失 offset）。**null = 不改动** ——
     *   周期落盘（5 秒一次）必须走 null，否则「上次抓包的缺口」会被正在进行中的场次刷掉；
     *   传**空 map** 是有效语义（本场无缺口 ⇒ 清掉上次的明细），与 null 不同。
     * @param captureStartedAt 本场**开抓**时刻（ISO-8601 本地日期时间，见 [CAPTURE_DETAIL_FMT]）。
     *   **null = 不改动 `last_capture_detail`**（周期落盘走这条）；只由停抓收尾传值，
     *   故「上次」永远是**已落库**的那一场。
     * @param captureEndedAt 本场**停抓**时刻（同格式）。两者必须成对给，缺一不写。
     * @return 落库新增条数（详细诊断见 [lastReport]）
     */
    fun merge(
        sessionRecords: List<GachaRecord>,
        viewTotals: Map<String, Int> = emptyMap(),
        keepRestorePoint: Boolean = true,
        scanGaps: Map<String, List<Int>>? = null,
        captureStartedAt: String? = null,
        captureEndedAt: String? = null,
        sourcePackage: String? = null,
        /**
         * 会话起点时的历史条数（服务侧传入）—— 仅用于把场次明细的 `added` 算成**本场净增**。
         *
         * 不传（null）时退回 [MergeReport.added]（= **本次** merge 的新增）—— 只对「一次 merge
         * 到底」的调用方正确（单测 / 单次导入）；**抓包路径必须传**，原因见写 `last_capture_detail` 处。
         */
        sessionBaselineRecords: Int? = null,
    ): Int {
        if (viewTotals.isNotEmpty()) {
            for ((k, v) in viewTotals) loadedTotals[k.toString()] = v
        }
        allPoolsTotal()?.let { loadedTotal = it }
        // U5：两个「停抓才更新」的键。null 与空 map 语义不同，别写反（见上方 @param）。
        // 场次明细不在这里赋值 —— 它的 `added` 要用下面 report 的结果，见 writePayload 之前。
        if (scanGaps != null) loadedScanGaps = scanGaps
        // 渠道标记累加（**只增不减**）：这是「该账号被哪些渠道抓过」的账本，
        // 换回旧渠道时据此判定「与历史一致 ⇒ 不必提醒」。
        if (sourcePackage != null) loadedSourcePackages.add(sourcePackage)

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
        // U5 场次明细：`added` 取 **merge 真正落库的新增数**，而不是本场收下的 `session.size`
        // —— session 里可能含与历史重叠、被位置判重吞掉的记录。记录页三行要求
        // 「上次 + 本次 = 总计」，用 session.size 会凑不上那个等式。
        if (captureStartedAt != null && captureEndedAt != null) {
            // ⚠️ `added` 必须取**本场净增**（收尾后总条数 − 会话起点条数），**不能**用 `report.added.size`：
            // 每 5 秒的 `GachaVpnService.checkpointPersist()` 已把本场记录并进历史 ⇒ 收尾这次 merge
            // 会把它们全部判重跳过 ⇒ `report.added.size` 恒为 0 ⇒ 记录页「上次」永远显示 0 条
            //（2026-09-19 实机反馈；与 §3.88「checkpoint 挤占还原点」同一类交叉影响 —— 周期落盘
            // 与收尾流程的交互没推演全）。
            val net = sessionBaselineRecords
                ?.let { (loaded.size - it).coerceAtLeast(0) }
                ?: report.added.size
            loadedCaptureDetail = CaptureDetail(captureStartedAt, captureEndedAt, net)
        }

        // 无新增也落盘：total 快照必须写下去，否则下次仍按旧基准推 Δ、位置整体错位
        writePayload(keepRestorePoint)
        return report.added.size
    }

    /**
     * 清空历史（内存 + 磁盘），清空前自动备份现有非空历史。
     *
     * total 快照一并清空 —— 清空后重新起算 Δ 基准，否则下次合并会按旧基准推出一个巨大的
     * Δ 把新记录位置整体挪错。用于「放弃旧数据、从当前抓取重新积累」。
     *
     * 清空前**必留还原点**（不传 `keepRestorePoint`，即默认 `true`）—— 这是还原点的主用途。
     *
     * @return 清空前生成的备份文件；磁盘上无有效历史可备份时为 null
     */
    fun clear(): File? {
        loaded = mutableListOf()
        loadedTotals.clear()
        loadedTotal = null
        // 明细描述的是「上一场抓包」，而记录已被放弃 —— 留着会让记录页显示
        // 「上次 601 条」而列表却是空的（两者对不上，用户会以为清空没生效）。
        loadedScanGaps = emptyMap()
        loadedSourcePackages = linkedSetOf()
        loadedCaptureDetail = null
        loadedForeignKeys = emptyMap()
        lastReport = null
        return writePayload(keepRestorePoint = true)
    }

    /**
     * 用导入计划的「三件套」**整份替换**本账号的历史（U1 互通导入专用，2026-09-21）。
     *
     * ### 为什么不复用 [merge]
     * 导入的语义是**整份替换**，不是「按位置并入」：`sync_file.planImport` 已经解算出最终
     * 要落盘的三件套（`MERGE` 选「文件里的」⇒ 取文件全部记录；选「应用里的」⇒ 写回设备现值，
     * 实为 no-op）。而 [merge] 走 `PositionAlign.mergeByPosition`（位置判重 + 追加），
     * 拿来做导入会得到完全不同的结果。
     *
     * ### 固定做三件事（对齐 PC `sync_apply.build_payload` + `history._carry_foreign_keys`）
     * 1. 三个自有键（`records` / `view_totals` / `total`）**整体覆盖**为计划值；
     * 2. `last_capture_detail` **显式作废**（`null`）—— 它描述「上一场抓包」，而记录已被整份
     *    换掉，留着会让记录页显示一个对不上的「上次 N 条」；与 PC 的
     *    `_CLEARED_FOREIGN_KEYS = ("last_capture_detail",)` 同义；
     * 3. `scan_gaps` / `source_packages` / **跨端键**（[loadedForeignKeys]）保持 [load] 时
     *    读到的原值原样写回。
     *
     * 备份、备份裁剪（[MAX_BACKUPS] / [BUDGET_BYTES]）、原子写**全部复用 [writePayload]** ——
     * 这是「写盘三件事只有一份实现」的落点（2026-09-21 定案：不给导入另写一套）。
     *
     * > ⚠️ 与 PC 的一处**有意差异**：输出按 `timestamp` **稳定倒序**（沿用本类不变量
     * > 「[records] 倒序」），PC 则按文件里的原顺序写。对判重 / 统计 / 保底消费序**无影响**
     * > （`PositionAlign.mergeByPosition` 按 `timestamp` 分组，与数组顺序无关），
     * > 而正常文件本身就是倒序 ⇒ 绝大多数情况下逐位相同。
     * >
     * > 另一处差异在**备份触发条件**：本类对「主文件损坏不可读」无条件留档（保命），
     * > PC 的 `sync_apply._backup_copy` 在同样情形下不留 —— 沿用 Android 既有
     * > [writePayload] 行为，M5 不改。
     *
     * @param rawRecords 计划里的 `write.records`（JSON 形态 5 键，与 [load] 读到的同形）
     * @param total 计划里的 `write.total`（允许 `null`）。取 [Long] 而非 [Int] 是为了与
     *   `SyncFile.WriteBundle`（wire 形态，整数一律 [Long]）**同型**，调用方不必手动转换；
     *   类内部仍是 [Int]（分页计数，量级远小于 2^31），在此处一次收敛。
     * @param viewTotals 计划里的 `write.view_totals`（同上，[Long] 版本）
     * @param keepRestorePoint 是否在写盘前留还原点。导入路径**恒传 `true`**（镜像 PC
     *   `sync_apply` 的「逐个备份原文件」）—— 契约 §6.2-e 的「一个还原点」指**一轮**备份，
     *   而非「全批只此一份」：备份文件按账号分文件，一份不可能覆盖全部账号。
     * @param onBackupCreated **备份刚生成、写盘尚未开始**时回调（给 [SyncBackend] 的
     *   回滚日志用）。实现**必须不抛**（抛会中断落盘）；默认 `null` = 不关心。
     * @return 本次生成的备份文件；磁盘上无有效历史可备份时为 null
     * @throws IllegalStateException 某条记录缺身份键（`parse()` 已拦，出现即上游 bug）
     */
    fun replaceRecords(
        rawRecords: List<Map<String, Any?>>,
        total: Long?,
        viewTotals: Map<String, Long>,
        keepRestorePoint: Boolean = true,
        onBackupCreated: ((File) -> Unit)? = null,
    ): File? {
        val converted = ArrayList<GachaRecord>(rawRecords.size)
        for (m in rawRecords) {
            converted.add(
                toRecord(m)
                    ?: throw IllegalStateException(
                        "导入记录缺身份键（pool_id/item_id/timestamp）：$m",
                    )
            )
        }
        // 稳定倒序：满足本类不变量，且同一事件内（同 ts）的相对顺序不变。
        loaded = converted.sortedByDescending { it.timestamp }.toMutableList()
        loadedTotals.clear()
        for ((k, v) in viewTotals) loadedTotals[k] = v.toInt()
        loadedTotal = total?.toInt()
        // 会话明细作废（上方第 2 条）；scan_gaps / source_packages / 跨端键保持原值。
        loadedCaptureDetail = null
        lastReport = null
        return writePayload(keepRestorePoint, onBackupCreated)
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
        // 保护刚被使用的目标：它可能落在保留窗口之外（比如是列表里居中的那份），但用户刚用过它 ——
        // 裁掉等于「还原完就把这个还原点弄没了」，想再还原一次就找不到了。它只是本轮 [pruneBackups]
        // 的 keep 成员，不改变 30 份 / 20 MB 的判定口径。
        pruneBackups(protected = backup)
        return current
    }

    /**
     * 把当前内存状态落库；返回本次生成的备份（无备份时为 null）。
     *
     * @param keepRestorePoint 见 [merge]。**损坏兜底不受它影响** —— 主文件不可读时无条件留档。
     * @param onBackupCreated **备份刚生成、[writeAtomic] 尚未开始**时回调。存在的唯一理由：
     *   导入路径（[replaceRecords]）需要在写盘**动到主文件之前**把这份备份记进回滚日志 ——
     *   否则「写盘中途失败 ⇒ 主文件被截断」时，这份备份是无主文件，回滚既清不掉也定位不到，
     *   而主文件已损坏 ⇒ 真丢数据。默认 `null` = 不关心（[merge] / [clear] / [restore] 走默认）。
     */
    private fun writePayload(
        keepRestorePoint: Boolean = true,
        onBackupCreated: ((File) -> Unit)? = null,
    ): File? {
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
            // 损坏（existing == null）时**无条件**留档：这是「保命」而非「还原点」，不受
            // keepRestorePoint 影响。该支天然只触发一次 —— 首次写盘即把主文件改写成合法
            // payload，下一轮 existing != null 且 hasRecords = false ⇒ 不再造备份。
            // （残余：写盘持续失败时主文件将一直是损坏态 ⇒ 可能反复走该支；此时备份写入本身
            // 同样大概率失败，属极端场景，不额外加代码。）
            if (existing == null || (keepRestorePoint && hasRecords)) {
                val created = backupFile()
                backup = created
                onBackupCreated?.invoke(created)
            }
        }
        writeAtomic(payload)
        return backup
    }

    /**
     * 原子写主文件：先写 `<profileId>.json.tmp`，成功后再 rename 覆盖。
     *
     * 为什么要原子：写盘中途进程被杀会留下**半截 JSON**，而 [load] 会把不可解析的文件
     * 静默当成「空历史」（比抛错更危险）。周期落盘不再产生还原点副本（[merge] 的
     * `keepRestorePoint = false`）后，「写坏时有最近副本兜底」这条路也没了，故从根上消除截断。
     *
     * `.tmp` 不匹配备份规约（[parseBackupTs] 要求 `<profileId>_` 前缀 + `.json` 结尾），
     * 故不会被 [backups] / [uniqueBackupFile] 误认，也不会被 [pruneBackups] 删除。
     *
     * rename 失败（目标已存在且平台不支持覆盖式 rename 等）时降级为直接写，并清掉临时文件 ——
     * 降级路径等价于旧行为，不引入新的失败面。
     *
     * ⚠️ 清临时文件放在 `finally`：**降级写本身也可能失败**（磁盘满 / 权限），旧写法会让
     * `tmp.delete()` 被跳过、在 `users/` 里留下 `<id>.json.tmp` 垃圾。该路径由导入回滚的
     * 故障注入暴露（2026-09-21 M5）。
     */
    private fun writeAtomic(payload: Map<String, Any?>) {
        val text = MiniJson.encodePretty(payload, indent = 2)
        val tmp = File(usersDir, "$profileId$JSON_SUFFIX.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(historyFile)) return
        } catch (e: Exception) {
            // 落到下面的降级分支
        }
        try {
            historyFile.writeText(text, Charsets.UTF_8)
        } finally {
            tmp.delete()
        }
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

        // 缺页明细：offset 一律转 Long。**这是判等闸成立的前提** —— `writePayload` 里
        // `existing == payload` 要求「解码已有文件 == 新 payload」逐结构相等，而 MiniJson
        // 把整数解码成 Long（见 MiniJson 的映射约定）。写成 Int 会让判等永远为假 ⇒
        // 每次 merge 都写盘 + 造备份，把还原点环刷满。
        val gaps = LinkedHashMap<String, Any?>()
        for ((k, v) in loadedScanGaps) gaps[k] = v.map { it.toLong() }

        val detail = loadedCaptureDetail?.let {
            linkedMapOf<String, Any?>(
                KEY_DETAIL_STARTED_AT to it.startedAt,
                KEY_DETAIL_ENDED_AT to it.endedAt,
                KEY_DETAIL_ADDED to it.added.toLong(),
            )
        }
        val payload = linkedMapOf<String, Any?>(
            KEY_RECORDS to recs,
            KEY_VIEW_TOTALS to totals,
            KEY_TOTAL to loadedTotal?.toLong(),
            KEY_SCAN_GAPS to gaps,
            // 渠道标记（账号级）：数组形式；PC 侧**未知键静默忽略**，互通不受影响
            KEY_SOURCE_PACKAGES to loadedSourcePackages.sorted(),
            KEY_LAST_CAPTURE_DETAIL to detail,
        )
        // 跨端键透传：磁盘上本类**不拥有**的键原样并回（PC `_carry_foreign_keys` 同义，单源规则）。
        // 放在末尾 ⇒ 键序确定（自有键在前、外来键在后），`existing == payload` 判等闸稳定。
        for ((key, value) in loadedForeignKeys) {
            if (key !in payload) payload[key] = value
        }
        return payload
    }

    /**
     * 从磁盘解出的顶层 map 里**摘出本类不拥有**的键 —— 跨端键透传的来源（见 [loadedForeignKeys]）。
     *
     * 键统一转成 `String`：`map` 来自 [MiniJson] 解码（对象键本就是 String，这里再兜一层以防
     * 解码实现变化）；值**原样保留**（不解读、不校验），这样「解码已有文件 == 新 payload」的
     * 判等闸对跨端键同样成立（值对象逐结构相等）。
     *
     * 只摘不拥有键：`records` / `view_totals` / `total` / `scan_gaps` / `source_packages` /
     * `last_capture_detail`（[OWNED_KEYS]）由本类自己生产、自己写回，不在这里重复带出。
     */
    private fun foreignKeysOf(map: Map<*, *>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            val key = k.toString()
            if (key !in OWNED_KEYS) out[key] = v
        }
        return out
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
     * 裁剪超出保留规则的旧备份（mobile 特有；见 [MAX_BACKUPS] / [BUDGET_BYTES] 的常量注释）。
     *
     * **保留集 = 从最新往回取，直到触到任一上限**：份数 ≤ [MAX_BACKUPS] **且** 总字节 ≤
     * [BUDGET_BYTES]。两条**保底**：刚生成的那份（最新的）**永不删**，[protected] 也永不删。
     *
     * 为什么「刚生成的那份永不删」是**必须**的（两条理由缺一不可）：
     * 1. 备份的全部意义是「还能退回去」——删光 = 还原功能直接消失，与设计目的相反；
     * 2. 更荒谬的是：本方法发生在**刚生成备份之后**（[backupFile]）。若预算严格生效，刚生成的
     *    那份会**立刻被自己删掉**，每次清空 / 停抓都白做，还原点列表永远是空的。
     *
     * **为什么刻意不设「永久锚点」**（2026-09-17 改判）：曾实现「保留最早的非空备份」，立意是
     * 「回到装之前」。但用户澄清诉求是**回退到出问题之前**（= 最近的一个健康状态，本就在滚动
     * 窗口内），而非时间上最早的那份；而锚点会实打实占掉一个槽位（当时上限 5 份，占 1 个 ⇒
     * 可回退步数从 5 降到 4）。⇒ 去掉。改判记录见 `09-设置模块设计.md` §15。
     *
     * **不解析备份内容**：只按「文件名时间戳」排序 + `File.length()` 求和。原实现在裁剪时要
     * 解析整份备份（为判断 `records > 0` 那个锚点），是写放大的隐形大头；去掉锚点后这步自然消失，
     * 条数只在 UI 展示（[backups]）时才解析。
     *
     * 只在**新备份生成后**（[backupFile]）或**还原完成后**（[restore]）调用。删除失败静默降级 ——
     * 下轮生成备份时会再试，最多多占一点空间，不影响正确性。
     *
     * @param protected 本次必须保住的那一份：[restore] 传刚被使用的还原点（它可能落在保留窗口
     *   之外，但「还原完就把这个还原点弄没了」会让用户无法再还原一次）。
     */
    private fun pruneBackups(protected: File? = null) {
        val all = backupFilesNewestFirst() // 新 → 旧（只看文件名与时间戳，不读内容）
        if (all.isEmpty()) return
        val entries = all.map { it to it.length() }
        if (entries.size <= MAX_BACKUPS && entries.sumOf { it.second } <= BUDGET_BYTES) return

        val keep = keepSet(entries, MAX_BACKUPS, BUDGET_BYTES, protected)
        all.filter { it !in keep }.forEach { it.delete() }
    }

    /**
     * `usersDir` 下所有**合规备份**，按备份时刻**新 → 旧**（同秒以 `_N` 为次级键，N 大者更新）。
     *
     * 与 [backups] 的区别：本方法**不读文件内容**（不统计条数），只做裁剪所需的排序 ——
     * 裁剪不需要知道每份几条，只看大小，故省掉 30 次 JSON 解析。
     *
     * 不合规的一律排除：`<profileId>.json`（历史本体，无时间戳）、`*.json.tmp`（[writeAtomic] 的
     * 临时文件，不以 `.json` 结尾）、其它账号的备份与归档。
     */
    private fun backupFilesNewestFirst(): List<File> {
        val parsed = (usersDir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .mapNotNull { f -> parseBackupTs(f.name)?.let { ts -> f to ts } }
        return parsed.sortedWith(
            compareByDescending<Pair<File, LocalDateTime>> { it.second }
                .thenByDescending { dupIndexOf(it.first.name) },
        ).map { it.first }
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

    /**
     * 读 `scan_gaps`（视图 → offset 数组）。
     *
     * 结构不符一律**回空 map**（与 [load] 同一条降级契约：手改坏的文件不该让页面崩）。
     * 非数字项跳过；`view` 键保留原样（与 PC 的视图标识口径一致）。
     */
    private fun toGapMap(v: Any?): Map<String, List<Int>> {
        val m = v as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()
        for ((k, value) in m) {
            val list = value as? List<*> ?: continue
            val offsets = ArrayList<Int>(list.size)
            for (item in list) toInt(item)?.let { offsets.add(it) }
            out[k.toString()] = offsets
        }
        return out
    }

    /**
     * 读 `last_capture_detail`。
     *
     * **任一必需字段缺失即返回 null**（而不是补默认值）—— 宁可显示「上次　暂无记录」，
     * 也不要显示一个「09:40–09:40 · 0 条」的半截记录：后者会被读成「真的抓过一场但是空的」。
     */
    private fun toCaptureDetail(v: Any?): CaptureDetail? {
        val m = v as? Map<*, *> ?: return null
        val started = m[KEY_DETAIL_STARTED_AT] as? String ?: return null
        val ended = m[KEY_DETAIL_ENDED_AT] as? String ?: return null
        return CaptureDetail(started, ended, toInt(m[KEY_DETAIL_ADDED]) ?: 0)
    }
}
