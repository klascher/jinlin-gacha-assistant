package com.jinlin.gacha.assistant.core.sync

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 一条抽卡记录 —— 5 键 snake_case，**原样搬运**（与账号文件 `users/<id>.json`、
 * [com.jinlin.gacha.assistant.core.GachaRecord] 同构）。
 *
 * 值类型与 [MiniJson] 解码结果一致：整数 → [Long]、字符串 → [String]。
 * `records[]` 在互通层**一字不改**，两端各自在语言侧映射（PC 直接用 key，Kotlin 用 key 取值）。
 */
typealias Record = Map<String, Any?>

/**
 * 跨端抽卡记录文件互通 —— **纯函数层**（解析 / 比对 / 计划 / 封装）——
 * Kotlin 逐行镜像 PC `gacha_exporter/storage/sync_file.py`。
 *
 * 设计依据
 * --------
 * - 契约：`U1互通层-契约冻结与实现定案-2026-09-21.md` §2~§4（函数契约 / 错误码 / 边界表）
 * - 格式：`数据互通-设计稿-2026-09-20.md`（v3.6）§3 / §4
 * - 同一份镜像的 PC 侧实现在仓库根 `gacha_exporter/storage/sync_file.py`（带 77 例真单测）
 *
 * 本对象**不做任何 IO**
 * --------------------
 * 不读文件、不写文件、不取当前时间、不碰 `Context` / `File` / 协程。调用方（M5 的薄 IO 层）负责：
 *
 * 1. 读出文件字节 → [parse]
 * 2. 从 `HistoryStore` 取出设备侧同名账号现值 → 组成 `List<DeviceAccount>`
 * 3. UI 收集用户选择 → `List<ImportChoice>`（初值可用 [defaultChoices]）
 * 4. [planImport] → [SyncPlan]
 * 5. 用户确认后按计划**一次提交**（整组原子回滚，见定案稿 §6）
 *
 * 这样切分的理由：本层纯 JVM、零 Android 依赖 ⇒ 可在 `testDebugUnitTest` 下跑真单测，
 * 并与 PC 侧**共用同一份黄金样本**做端到端交叉验证（定案稿 §7）。
 *
 * 与本地账号文件的关系
 * --------------------
 * 互通文件是**独立信封**（`jinlin-records` v1），**不是** `users/<id>.json` 的兼容格式。
 * 两者的 `records[]` 逐字同构（5 键），但顶层键与语义不同 ⇒ 两份账号文件**不可直接互换**。
 *
 * ⚠️ 与 PC 的两处**有意差异**（除下列外一律逐字对齐，含函数名 / 参数序 / 错误码 / 文案）：
 * - `parse` 之外多一个 `ByteArray` 重载（PC 的 `parse` 同时吃 `str`/`bytes`，Kotlin 用重载表达；
 *   非 UTF-8 一律 `BAD_FORMAT` + `detail="not-utf8"`，与 PC 的 `bytes.decode("utf-8")` 失败同义）。
 * - [asLong] 对 `Boolean` 返回 `null`（PC 的 `int(True) == 1`）；JSON 里 `total`/`view_totals`
 *   出现布尔值属畸形输入，两端都不该依赖这一条。
 *
 * ⚠️ **不做** UTF-8 BOM 剥离 —— PC `json.loads` 同样会拒绝带 BOM 的文本 ⇒ 两端行为一致
 * （`BAD_FORMAT`）。若日后要放宽，**必须两端同时放宽**，否则「同一份文件两端结论相同」不成立。
 */
object SyncFile {

    // -----------------------------------------------------------------------
    // 常量（错误码两端**同名同值**；不设 OK 常量，用 ok/error 表达）
    // -----------------------------------------------------------------------

    const val FORMAT_NAME = "jinlin-records"
    const val FORMAT_VERSION = 1L

    const val ERR_BAD_FORMAT = "BAD_FORMAT"
    const val ERR_VERSION_TOO_HIGH = "VERSION_TOO_HIGH"
    const val ERR_NO_ACCOUNTS = "NO_ACCOUNTS"
    const val ERR_CORRUPT = "CORRUPT"

    /** 记录**身份键**：缺任一 ⇒ 算不出身份 ⇒ 整份文件 `CORRUPT`（定案稿 §4）。 */
    val IDENTITY_KEYS: List<String> = listOf("pool_id", "item_id", "timestamp")

    /**
     * 坐标键：缺 ⇒ 按 `0` 补，**不报错**（坐标只在事件内部比较，补 0 不影响完整性判定）。
     *
     * ⚠️ 只在**键缺失**时补；显式 `null` 原样保留（对齐 PC `dict.get(k, 0)` 的语义）。
     */
    val COORD_KEYS: List<String> = listOf("batch_seq", "position_in_batch")

    const val MODE_NEW = "NEW"
    const val MODE_MERGE = "MERGE"

    /**
     * 「不导入这个用户」第三态：该账号整份不进计划（手机侧也不动）。
     *
     * ⚠️ 刻意**不叫** `DISCARD` —— `PlannedAccount.discard` 是「合并时败方独有被丢弃的条数」，
     * 前提是**该账号要导入**；两者同词不同义，读代码会错。
     */
    const val MODE_SKIP = "SKIP"

    const val CHOSEN_FILE = "FILE"
    const val CHOSEN_DEVICE = "DEVICE"

    const val NEWER_FILE = "FILE"
    const val NEWER_DEVICE = "DEVICE"
    const val NEWER_TIE = "TIE"

    /** 合法事件规模：1 = 单抽、10 = 十连（与 [com.jinlin.gacha.assistant.core.dedup.VALID_EVENT_SIZES] 同源语义）。 */
    val VALID_EVENT_SIZES: List<Int> = listOf(1, 10)

    /** 文件账号缺 `name` 时的降级名（归并键缺失不丢记录，仅展示层兜底）。 */
    const val FALLBACK_NAME = "未命名"

    // -----------------------------------------------------------------------
    // 数据结构契约（定案稿 §2）
    // -----------------------------------------------------------------------

    /** 解析 / 校验错误：`code` 取上方四个错误码之一；`detail` 仅供诊断显示（不面向用户）。 */
    data class SyncError(val code: String, val detail: String? = null)

    /** 文件侧单个账号 —— [parse] 的产物（定案稿 §2.2）。 */
    data class ParsedAccount(
        /** **归并键**；缺失 ⇒ [FALLBACK_NAME]。 */
        val name: String,
        /** 仅供参考，**不作键**（两端各自随机 12 位 hex，必然不同）。 */
        val accountId: String?,
        /** 逐字同构 5 键。 */
        val records: List<Record>,
        /** 允许 `null`；坏值 ⇒ 降级 `null`。 */
        val total: Long?,
        /** 允许缺 / `{}`；坏值键**跳过**（键一律转字符串）。 */
        val viewTotals: Map<String, Long>,
        /** 文件里没有 ⇒ `null`（导入端自行重建，不影响解析成功）。 */
        val events: List<Any?>?,
        /** 同上；导入端**只用于预览展示**，不落库。 */
        val integrity: Map<String, Any?>?,
    )

    /**
     * [parse] 的结果（定案稿 §2.1）。
     *
     * 失败时 [accounts] 恒为空列表，**不返回半份数据**。
     */
    data class ParsedFile(
        val ok: Boolean,
        val error: SyncError?,
        val source: Map<String, Any?>?,
        val exportedAt: String?,
        val accounts: List<ParsedAccount>,
    )

    /**
     * [compare] 的结果（定案稿 §2.4）—— Step2 预览所需的全部数字。
     *
     * ⚠️ [both] / [deviceOnly] / [fileOnly] 是**多重集**计数（见 [compare]），
     * 恒满足 `both + deviceOnly == deviceCount` 与 `both + fileOnly == fileCount`。
     */
    data class CompareResult(
        val both: Int,
        val deviceOnly: Int,
        val fileOnly: Int,
        val deviceCount: Int,
        val fileCount: Int,
        /** 该侧**最大 `timestamp`**；`null` = 该侧无记录（或没有可用的数值 `timestamp`）。 */
        val deviceMaxTs: Long?,
        val fileMaxTs: Long?,
        /** `FILE` | `DEVICE` | `TIE`，用于 Step2 默认选中项。 */
        val newer: String,
    )

    /**
     * UI → [planImport] 的输入（定案稿 §2.5）。
     *
     * @param index 文件里 `accounts[]` 的下标（**账号身份用下标，不用 name** —— 文件内同名是合法的）。
     *   `null` = 「按位置对应」，不做下标校验（对齐 PC 的 `choice.get("index") not in (None, i)`）；
     *   [defaultChoices] 生产的初值恒带正确下标。
     * @param mode [MODE_NEW] / [MODE_MERGE]。
     * @param target `MERGE` 必填（目标设备账号 id）；`NEW` 为 `null`。
     * @param chosen `MERGE` 必填；`NEW` 忽略（恒 [CHOSEN_FILE]）。
     */
    data class ImportChoice(
        val index: Int? = null,
        val mode: String,
        val target: String? = null,
        val chosen: String = CHOSEN_FILE,
    )

    /**
     * 设备侧某个账号的现值 —— 调用方（M5）从 `HistoryStore` 组装。
     *
     * 字段与文件侧 `ParsedAccount` 的三件套同名同义，只是 `id` 是本端自己的账号 id。
     */
    data class DeviceAccount(
        val id: String,
        val name: String,
        val records: List<Record>,
        val total: Long? = null,
        val viewTotals: Map<String, Long> = emptyMap(),
    )

    /** 最终要落盘的**三件套**（定案稿 §2.6）。 */
    data class WriteBundle(
        val records: List<Record>,
        val total: Long?,
        val viewTotals: Map<String, Long>,
    )

    /** 计划里的单个账号（定案稿 §2.6）。 */
    data class PlannedAccount(
        val index: Int,
        val name: String,
        val mode: String,
        val target: String?,
        val chosen: String,
        val write: WriteBundle,
        /** 将被丢弃的条数（`MERGE` = 败方**独有**条数；`NEW` = 0）。**仅供确认弹窗显示**。 */
        val discard: Int,
    )

    /** [planImport] 的结果 = **落库执行单**（定案稿 §2.6）。 */
    data class SyncPlan(
        val ok: Boolean,
        val error: SyncError?,
        val accounts: List<PlannedAccount>,
    )

    // -----------------------------------------------------------------------
    // 小工具
    // -----------------------------------------------------------------------

    /** 构造失败结果（`accounts` 恒为空数组，**不返回半份数据**）。 */
    private fun err(code: String, detail: String? = null): ParsedFile =
        ParsedFile(
            ok = false,
            error = SyncError(code, detail),
            source = null,
            exportedAt = null,
            accounts = emptyList(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun asList(value: Any?): List<Any?>? = value as? List<Any?>

    /**
     * 宽容取整：`null` 原样返回；转不动也返回 `null`（**不报错**）。对齐 PC `_as_int`。
     *
     * ⚠️ 浮点按**截断**处理（对齐 Python `int(1.5) == 1`，不是四舍五入）。
     */
    fun asLong(value: Any?): Long? = when (value) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> value.toLong()
        is Float -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    /**
     * `view_totals` 规范化：键转字符串，值转 [Long]；坏值**跳过**该键（不报错）。
     *
     * 非对象（缺失 / `null` / 数组 / 标量）一律返回空表 —— 对齐 PC `_view_totals`。
     */
    fun viewTotalsOf(raw: Any?): Map<String, Long> {
        val map = asMap(raw) ?: return emptyMap()
        val out = LinkedHashMap<String, Long>(map.size)
        for ((key, value) in map) {
            val iv = asLong(value) ?: continue
            out[key.toString()] = iv
        }
        return out
    }

    /**
     * 记录身份键 `(pool_id, item_id, timestamp)`。对齐 PC `identity`。
     *
     * ⚠️ 十连里同名角色（同池同角色同时刻）会让身份键**重复**（实测数据常见）⇒ 统计三集合时
     * 必须按**多重集**计数（见 [compare]），不能退化成 set。
     */
    fun identity(record: Record): List<Any?> =
        listOf(record["pool_id"], record["item_id"], record["timestamp"])

    // -----------------------------------------------------------------------
    // ① parse —— 文件文本 → ParsedFile
    // -----------------------------------------------------------------------

    /**
     * 解析互通文件文本。**不要传已解析的对象** —— 本函数是两端逐字对齐的入口。
     *
     * 边界（定案稿 §4 逐条落实）：
     * - 空文本 / 非 JSON / 根不是对象 / `format` 不符 ⇒ [ERR_BAD_FORMAT]
     * - `version` **键缺失** ⇒ 视为 1（宽容）；`version > 1` ⇒ [ERR_VERSION_TOO_HIGH]
     * - `accounts` 缺失或 `[]` ⇒ [ERR_NO_ACCOUNTS]
     * - 某账号缺 `records` / 某条记录缺身份键 ⇒ [ERR_CORRUPT]
     * - 记录缺坐标键 ⇒ **按 0 补**；`name` 缺失 ⇒ 降级名；`total` 坏值 ⇒ `null`
     */
    fun parse(text: String): ParsedFile {
        val root = try {
            asMap(MiniJson.decode(text))
        } catch (e: Exception) {
            // 任何解析异常都归为格式错（对齐 PC：except Exception -> BAD_FORMAT）
            return err(ERR_BAD_FORMAT, "json: ${e.message}")
        } ?: return err(ERR_BAD_FORMAT, "root-not-object")

        if (root["format"] != FORMAT_NAME) {
            return err(ERR_BAD_FORMAT, "format=${root["format"]}")
        }

        // ⚠️ 必须区分「键缺失」（宽容取 1）与「键存在但值坏」（BAD_FORMAT）——
        // 直接用 `root["version"] ?: FORMAT_VERSION` 会把 {"version": null} 误判为宽容。
        val version: Long? =
            if (root.containsKey("version")) asLong(root["version"]) else FORMAT_VERSION
        if (version == null) {
            return err(ERR_BAD_FORMAT, "version=${root["version"]}")
        }
        if (version > FORMAT_VERSION) {
            return err(ERR_VERSION_TOO_HIGH, "version=$version")
        }

        val rawAccounts = asList(root["accounts"])
        if (rawAccounts == null || rawAccounts.isEmpty()) {
            return err(ERR_NO_ACCOUNTS, null)
        }

        val accounts = ArrayList<ParsedAccount>(rawAccounts.size)
        rawAccounts.forEachIndexed { ai, rawAccount ->
            val account = asMap(rawAccount)
                ?: return err(ERR_CORRUPT, "account#$ai: not-object")

            val rawRecords = asList(account["records"])
                ?: return err(ERR_CORRUPT, "account#$ai: no-records")

            val records = ArrayList<Record>(rawRecords.size)
            rawRecords.forEachIndexed { ri, rawRecord ->
                val rr = asMap(rawRecord)
                    ?: return err(ERR_CORRUPT, "account#$ai record#$ri: not-object")
                if (IDENTITY_KEYS.any { !rr.containsKey(it) }) {
                    return err(ERR_CORRUPT, "account#$ai record#$ri: no-identity")
                }
                // 5 键逐字同构：身份键原样，坐标键缺则补 0（显式 null 原样保留）
                val record = LinkedHashMap<String, Any?>(IDENTITY_KEYS.size + COORD_KEYS.size)
                for (k in IDENTITY_KEYS) record[k] = rr[k]
                for (k in COORD_KEYS) record[k] = if (rr.containsKey(k)) rr[k] else 0L
                records.add(record)
            }

            val rawName = account["name"]
            val name = if (rawName is String && rawName.isNotEmpty()) rawName else FALLBACK_NAME

            accounts.add(
                ParsedAccount(
                    name = name,
                    accountId = account["account_id"] as? String,
                    records = records,
                    total = asLong(account["total"]),
                    viewTotals = viewTotalsOf(account["view_totals"]),
                    events = asList(account["events"]),
                    integrity = asMap(account["integrity"]),
                ),
            )
        }

        return ParsedFile(
            ok = true,
            error = null,
            source = asMap(root["source"]),
            exportedAt = root["exported_at"] as? String,
            accounts = accounts,
        )
    }

    /**
     * [parse] 的字节重载（对齐 PC `parse` 同时接受 `bytes`）。
     *
     * 非 UTF-8 一律 `BAD_FORMAT` + `"not-utf8"` —— **严格解码**（`REPORT` 而非替换成 U+FFFD），
     * 与 PC `bytes.decode("utf-8")` 抛 `UnicodeDecodeError` 同义。
     */
    fun parse(bytes: ByteArray): ParsedFile {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            return err(ERR_BAD_FORMAT, "not-utf8")
        }
        return parse(text)
    }

    // -----------------------------------------------------------------------
    // ② compare —— 设备侧 × 文件侧 → 三集合预览
    // -----------------------------------------------------------------------

    /**
     * 比对两侧记录，产出 Step2 预览所需的全部数字。
     *
     * ⚠️ **三集合用多重集而非集合**：十连里同名角色（同池同角色同时刻）合法存在，
     * 退化成 `set` 会把 [CompareResult.both] 少算，并破坏
     * `both + deviceOnly == deviceCount` 的守恒。
     * 实现 = 逐身份键取 `min(count_device, count_file)` 再求和（对齐 PC 的 `Counter`）。
     *
     * 🔑 输入是**「设备侧那个账号的 records」×「文件侧同名账号的 records」**，与 UI、与文件整体无关。
     */
    fun compare(deviceRecords: List<Record>, fileRecords: List<Record>): CompareResult {
        val deviceCounter = deviceRecords.groupingBy { identity(it) }.eachCount()
        val fileCounter = fileRecords.groupingBy { identity(it) }.eachCount()

        // ⚠️ 必须遍历**键的并集（Set）**：用 `keys + keys` 这类可重复序列会把同一个键算多次。
        var both = 0
        for (key in deviceCounter.keys.union(fileCounter.keys)) {
            both += minOf(deviceCounter[key] ?: 0, fileCounter[key] ?: 0)
        }

        val deviceCount = deviceRecords.size
        val fileCount = fileRecords.size

        val deviceMaxTs = deviceRecords.mapNotNull { asLong(it["timestamp"]) }.maxOrNull()
        val fileMaxTs = fileRecords.mapNotNull { asLong(it["timestamp"]) }.maxOrNull()

        val newer = when {
            deviceMaxTs == null && fileMaxTs == null -> NEWER_TIE
            fileMaxTs == null -> NEWER_DEVICE
            deviceMaxTs == null -> NEWER_FILE
            fileMaxTs > deviceMaxTs -> NEWER_FILE
            deviceMaxTs > fileMaxTs -> NEWER_DEVICE
            else -> NEWER_TIE
        }

        return CompareResult(
            both = both,
            deviceOnly = deviceCount - both,
            fileOnly = fileCount - both,
            deviceCount = deviceCount,
            fileCount = fileCount,
            deviceMaxTs = deviceMaxTs,
            fileMaxTs = fileMaxTs,
            newer = newer,
        )
    }

    /**
     * 按 `name` 找设备侧同名账号，返回下标；找不到返回 `-1`。
     *
     * 归并键是 `name`（**不是** `accountId` —— 两端各自随机，必然不同）。
     */
    fun findDeviceAccount(deviceAccounts: List<DeviceAccount>, name: String): Int =
        deviceAccounts.indexOfFirst { it.name == name }

    // -----------------------------------------------------------------------
    // ③ defaultChoices —— UI Step1 的初始选择
    // -----------------------------------------------------------------------

    /**
     * 为文件里每个账号给出**初始选择**（用户可在 UI 改）。
     *
     * - 设备侧**无**同名账号 ⇒ `mode=NEW`（`target=null`，`chosen` 恒 `FILE`）
     * - 设备侧**有**同名账号 ⇒ `mode=MERGE`，`chosen` 取 [compare] 的 `newer`
     *   （`FILE` → 用文件、`DEVICE` → 用手机、`TIE` → 默认**用文件**：用户的动作是拿来导入）
     */
    fun defaultChoices(
        parsed: ParsedFile,
        deviceAccounts: List<DeviceAccount>,
    ): List<ImportChoice> = parsed.accounts.mapIndexed { index, account ->
        val di = findDeviceAccount(deviceAccounts, account.name)
        if (di < 0) {
            ImportChoice(index = index, mode = MODE_NEW, target = null, chosen = CHOSEN_FILE)
        } else {
            val device = deviceAccounts[di]
            val result = compare(device.records, account.records)
            ImportChoice(
                index = index,
                mode = MODE_MERGE,
                target = device.id,
                chosen = if (result.newer == NEWER_DEVICE) CHOSEN_DEVICE else CHOSEN_FILE,
            )
        }
    }

    // -----------------------------------------------------------------------
    // ④ planImport —— UI 选择 → 落库执行单（整份文件全部账号）
    // -----------------------------------------------------------------------

    /**
     * 把「文件 + 设备现值 + 用户选择」解算成**落库执行单**。
     *
     * 落库取值矩阵（定案稿 §2.6）：
     * - `mode=NEW` ⇒ `write` 全部来自**文件**
     * - `mode=MERGE` & `chosen=FILE` ⇒ `write` 全部来自**文件**
     * - `mode=MERGE` & `chosen=DEVICE` ⇒ `write` 全部来自**设备现值**（实为 no-op，但**必须在计划里**，
     *   否则整组回滚时集合不完整）
     * - `mode=SKIP` ⇒ **该账号不进计划**：文件里的不写进应用，应用里原有的也不动；
     *   `target` / `chosen` **一律忽略且不校验**（但 UI 不清空它们，见定案稿 §6.2-c）
     *
     * ⚠️ `SKIP` 是唯一**不产出账号项**的 mode ⇒ `plan.accounts.size` 不再等于文件账号数。
     * [PlannedAccount.index] 保留**原文件下标**（文件 3 个账号跳过第 2 个 ⇒ 计划里是 `0` 与 `2`）。
     *
     * @throws IllegalArgumentException `choices` 与文件账号数量/顺序不符、缺字段、`MERGE` 无合法 `target`。
     *   属**调用方 bug**，不是文件错误 ⇒ 不套用文件错误码（两端同名同义：PC 抛 `ValueError`）。
     *   校验放在本层，保证「计划一旦产出即可无条件执行」。
     *
     * ⚠️ `write` 里的 `records` / `viewTotals` 是**副本**（不与输入共享引用）——
     * 否则调用方一改计划就会污染 `parsed`。⚠️ 副本是**浅**的（与 PC 的 `dict(r)` 一致）：
     * 逐条记录 Map 仍共享，调用方**不得**就地改单条记录。
     */
    fun planImport(
        parsed: ParsedFile,
        deviceAccounts: List<DeviceAccount>,
        choices: List<ImportChoice>,
    ): SyncPlan {
        val fileAccounts = parsed.accounts
        if (choices.size != fileAccounts.size) {
            throw IllegalArgumentException(
                "choices 数量 ${choices.size} 与文件账号数 ${fileAccounts.size} 不符",
            )
        }

        val deviceById = deviceAccounts.associateBy { it.id }

        val planned = ArrayList<PlannedAccount>(fileAccounts.size)
        fileAccounts.forEachIndexed { fileIndex, account ->
            val choice = choices[fileIndex]
            if (choice.index != null && choice.index != fileIndex) {
                throw IllegalArgumentException(
                    "choices[$fileIndex].index=${choice.index} 与位置不符",
                )
            }

            val mode = choice.mode
            val chosen = choice.chosen
            var target = choice.target

            if (mode != MODE_NEW && mode != MODE_MERGE && mode != MODE_SKIP) {
                throw IllegalArgumentException("choices[$fileIndex].mode=$mode 非法")
            }

            // SKIP ⇒ 该账号整份不导入：不进计划。
            // ⚠️ 必须在上面 mode 白名单校验**之后** —— 放前面会把「非法 mode」静默吞掉，
            // 正好违反「校验放在纯函数层，保证计划一旦产出即可无条件执行」的初衷。
            if (mode == MODE_SKIP) return@forEachIndexed

            val fileBundle = WriteBundle(
                records = account.records.toList(),
                total = account.total,
                viewTotals = account.viewTotals.toMap(),
            )

            val write: WriteBundle
            val discard: Int
            if (mode == MODE_NEW) {
                write = fileBundle
                discard = 0
                target = null
            } else {
                if (chosen != CHOSEN_FILE && chosen != CHOSEN_DEVICE) {
                    throw IllegalArgumentException("choices[$fileIndex].chosen=$chosen 非法")
                }
                val targetId = target
                    ?: throw IllegalArgumentException(
                        "choices[$fileIndex].target=null 不在设备账号里",
                    )
                val device = deviceById[targetId]
                    ?: throw IllegalArgumentException(
                        "choices[$fileIndex].target=$targetId 不在设备账号里",
                    )
                val deviceBundle = WriteBundle(
                    records = device.records.toList(),
                    total = device.total,
                    viewTotals = device.viewTotals.toMap(),
                )
                val result = compare(deviceBundle.records, fileBundle.records)
                if (chosen == CHOSEN_FILE) {
                    write = fileBundle
                    discard = result.deviceOnly // 丢弃手机侧独有
                } else {
                    write = deviceBundle
                    discard = result.fileOnly // 丢弃文件侧独有
                }
            }

            planned.add(
                PlannedAccount(
                    index = fileIndex,
                    name = account.name,
                    mode = mode,
                    target = target,
                    chosen = chosen,
                    write = write,
                    discard = discard,
                ),
            )
        }

        return SyncPlan(ok = true, error = null, accounts = planned)
    }

    // -----------------------------------------------------------------------
    // ⑤ 导出侧：事件分析 / 自检 / 信封封装
    // -----------------------------------------------------------------------

    /**
     * 按 `timestamp` 分组，产出 `events[]`（定案稿 §3.2 规则，两端逐字一致）。
     *
     * ```
     * 按 ts 给 records 分组（按 records 的**原始顺序**首次出现为准），对每一组：
     *   coord    = 组内 position_in_batch 互不相同 ? "absolute" : "page"
     *   complete = (组内条数 == 1 或 10)
     * ```
     *
     * `ids` 指向 `records[]` 的下标（从 0 开始）。
     *
     * ⚠️ 输出里的 `count` / `ids` 用 [Long]（与 `MiniJson` 解码结果同型）⇒
     * 「重算 == 文件里读回来的」可以直接用结构判等，无需归一化。
     *
     * ⚠️ 本函数**不写进本端账号文件** —— `events[]` 只是给导入端读的元数据，
     * 导入完成后本端按自己的方式重建（PC `HistoryStore._analyze_events()` /
     * Android 的 `PositionAlign`）。
     */
    fun analyzeEvents(records: List<Record>): List<Map<String, Any?>> {
        val groups = LinkedHashMap<Any?, MutableList<Int>>()
        records.forEachIndexed { i, record ->
            groups.getOrPut(record["timestamp"]) { ArrayList() }.add(i)
        }

        val events = ArrayList<Map<String, Any?>>(groups.size)
        for ((ts, ids) in groups) {
            val positions = ids.map { records[it]["position_in_batch"] }
            val count = ids.size
            events.add(
                linkedMapOf<String, Any?>(
                    "ts" to ts,
                    "count" to count.toLong(),
                    "ids" to ids.map { it.toLong() },
                    "coord" to if (positions.toSet().size == count) "absolute" else "page",
                    "complete" to (count in VALID_EVENT_SIZES),
                ),
            )
        }
        return events
    }

    /**
     * 导出方自检结论（定案稿 §3.3）。**只能由导出方生成** —— 导入方无法事后反推。
     *
     * 字段顺序与 PC 一致（`indent=2` 序列化后逐字节可比）：`record_count` /
     * `authoritative_total` / `count_matches` / `event_count` / `incomplete_events` /
     * `partial_records` / `has_anchor`。
     */
    fun computeIntegrity(
        records: List<Record>,
        events: List<Map<String, Any?>>,
        total: Long?,
    ): Map<String, Any?> {
        val incomplete = events.filter { it["complete"] != true }
        var partial = 0L
        for (e in incomplete) partial += asLong(e["count"]) ?: 0L

        return linkedMapOf<String, Any?>(
            "record_count" to records.size.toLong(),
            "authoritative_total" to total,
            "count_matches" to (total != null && total == records.size.toLong()),
            "event_count" to events.size.toLong(),
            "incomplete_events" to incomplete.size.toLong(),
            "partial_records" to partial,
            "has_anchor" to (total != null),
        )
    }

    /**
     * 封装一个导出账号（`records[]` 逐字搬运，`events` / `integrity` 现算）。
     *
     * @param records 该账号要导出的记录（**原样**，含坐标的「脏」也如实保留）。
     * @param total 跟随导出的权威总抽数（丢了它导入端就失去 Δ 基准）。
     * @param viewTotals 视图总抽数；`null` 视为 `{}`。
     * @param sourceTotal 计算 `integrity.has_anchor` 用；缺省同 `total`。
     */
    fun buildExportAccount(
        accountId: String,
        name: String,
        records: List<Record>,
        total: Long?,
        viewTotals: Map<String, Long>? = null,
        sourceTotal: Long? = null,
    ): Map<String, Any?> {
        val recs = records.toList()
        val events = analyzeEvents(recs)
        val anchor = sourceTotal ?: total
        return linkedMapOf<String, Any?>(
            "account_id" to accountId,
            "name" to name,
            "total" to total,
            "view_totals" to LinkedHashMap(viewTotals ?: emptyMap()),
            "records" to recs,
            "events" to events,
            "integrity" to computeIntegrity(recs, events, anchor),
        )
    }

    /**
     * 封装整份互通信封（顶层 4 键 + `accounts`）。**不取时钟** —— `exportedAt` 由调用方注入。
     */
    fun buildEnvelope(
        accounts: List<Map<String, Any?>>,
        exportedAt: String,
        source: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf(
        "format" to FORMAT_NAME,
        "version" to FORMAT_VERSION,
        "exported_at" to exportedAt,
        "source" to LinkedHashMap(source),
        "accounts" to accounts.toList(),
    )

    /**
     * 序列化为文件文本（`indent=2` + 末尾换行，与 PC `json.dumps(..., indent=2,
     * ensure_ascii=False) + "\n"` 一致）。
     *
     * ⚠️ 两端必须用**同一套序列化参数**，否则「两端产出的文件逐字节相同」不成立。
     * 本函数复用 [MiniJson.encodePretty]（只负责格式化，不负责判等 —— 判等一律用结构比较）。
     * ⚠️ 已知一处形态差异（**本层不会产生**）：PC 会把 `1.0` 写成 `1.0`，
     * MiniJson 会写成 `1`。互通载荷里 `total` / `viewTotals` / 记录字段**全是整数**，
     * 故实际输出逐字节一致。
     */
    fun dumps(envelope: Map<String, Any?>): String = MiniJson.encodePretty(envelope, 2) + "\n"
}
