package com.jinlin.gacha.assistant.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.sync.SyncFile
import com.jinlin.gacha.assistant.persistence.HistoryEpoch
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.SyncBackend
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 跨端记录互通 —— **导入三步 UI**（设置 → 数据互通 → 导入记录）。
 *
 * 镜像 PC `gacha_exporter/gui/sync_dialog.py::ImportSyncDialog`，但 PC 是**单页**
 * （Step1 + Step2 并页、账号用顶部切换器），Android 按契约做成**三步**。
 *
 * ### 三步是什么
 * ```
 * ①「选择文件」→ 系统文件选择器（SAF）→ parse()
 * ② Step1「方式 / 目标」  逐账号；跨账号用「◀ / ▶」切换
 * ③ Step2「预览」         对照表 + 三集合 + 冲突 + 选哪一份 + 丢弃提示
 * ④ Step3「结果」         逐账号条数 + 备份说明
 * ```
 *
 * ### 🔑 几条硬约束（契约条款号）
 * - **§6.2-c 保留配置**：`choices` 是**唯一**配置源，`◀ / ▶` 只改下标、**绝不清空**任何已选；
 * - **§6.2-a 一次提交**：只有 Step2 的「确认导入」会落盘，且是**整份文件全部账号一次交**
 *   （[SyncBackend.applyPlan]），不存在「边选边写」；
 * - **§6.2-d 一次刷新**：提交成功后调**一次** [HistoryEpoch.bump]（记录页 / 统计页重算）；
 * - **§6.4 裁定 (a)**：Step2 的「返回上一步」回**本账号**的 Step1；跨账号切换在 Step1 顶部；
 * - **§5 新建路径**：隐藏「目标用户 / 使用哪一份 / 丢弃提示 / 三集合三行」，对照表降为单列；
 * - **§10-a**：错误一律 [AlertDialog]（**不用 Toast**）；**§10-d**：`parse()` 失败
 *   根本进不到 Step1，直接弹框、**零写盘**；
 * - **§6.3 抓包中整组禁用**：入口行已在 [SettingsScreen] 置灰，此处仍**跟随**状态
 *   （抓包可从通知栏起停）并在「确认导入」时**再读一次** `isRunning()` 兜底。
 *
 * ### 🩹 2026-09-22 两处交互修正（用户报告，全量环境编译后反馈）
 * - **Step1「下一步」门槛**：此前 `primaryEnabled = true` 恒真 ⇒ 任意账号页都能直接提交，
 *   后面账号的导入方式会被默认值静默带走。改为**浏览完全部账号**才可点（`visited` 集合），
 *   未满足时在按钮上方给出「已看 N/M」原因；
 * - **Step2 预览只显示当前账号**：此前只吃 `accounts[idx]` ⇒ 看不到其余账号会被怎么导。
 *   现在顶部新增**全部账号总览**（新建 / 合并到谁 / 重合与丢弃条数 / 冲突标记），
 *   当前账号的完整对照与「选哪一份」照旧在下方。
 *
 * ### ➕ 2026-09-22 第三态「不导入这个用户」（契约 `MODE_SKIP`）
 * Step1 的「导入方式」多一项：该账号**整份不进计划** —— 文件里的不写进应用，应用里原有的也不动，
 * 其余账号照常导入（仍是**一次提交 / 整组原子**）。判据全在纯函数层（[SyncFile.planImport]）。
 * ⚠️ 「不导入」必须在**总览与详情两处**都说到（漏一处就会出现「总览说跳过、详情还在让你选哪一份」）；
 * ⚠️ 全部账号都选「不导入」时**一个字节都不写**：不进事务壳（它的「计划为空」守卫是表达
 * 调用方 bug 的**防御性断言**，不用来表达用户的合法选择），直接进结果页说明。
 * ⚠️ 切换方式时**不清空** `target` / `chosen`（§6.2-c）—— 用户「合并 → 不导入 → 再改回合并」时原目标必须还在。
 *
 * ### ⚠️ 判据不重写
 * 「什么算冲突 / 谁更新 / 三集合各多少 / 丢弃几条」**全部**来自纯函数层
 * （[SyncFile.compare] / [SyncFile.planImport]）。本文件只负责**把数字画出来**。
 *
 * @param onBack 退出本页（取消选文件、解析失败确认后、结果页「关闭」均走它）
 */
@Composable
internal fun SyncImportScreen(colors: JinlinColors, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember { ProfileStore.get(context) }
    // 抓包状态**跟随**（非进入时取一次）：抓包可由通知栏起停，采样会漏（同 DataCleanupScreen）
    val running = GachaVpnService.state.collectAsState().value.running

    var parsed by remember { mutableStateOf<SyncFile.ParsedFile?>(null) }
    var fileName by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<SyncFile.SyncError?>(null) }
    var deviceAccounts by remember { mutableStateOf<List<SyncFile.DeviceAccount>>(emptyList()) }
    var choices by remember { mutableStateOf<List<SyncFile.ImportChoice>>(emptyList()) }
    var idx by remember { mutableStateOf(0) }
    var step by remember { mutableStateOf(STEP_MODE) }
    // 已浏览过的账号下标（2026-09-22）：Step1 的「下一步」须**浏览完全部用户**才可点 ——
    // 只看一眼首个账号就提交，后面账号的导入方式会被默认值静默带走。
    // 初值含 0（进入即看到第 1 个账号）；渲染时再并上当前 `idx`（`◀ / ▶` 之外的路径也能兜住）。
    var visited by remember { mutableStateOf(setOf(0)) }
    var busy by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SyncBackend.ApplyResult?>(null) }

    // —— ① 选文件（SAF）→ 读 → parse ——
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onBack()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: ByteArray(0)
                    queryDisplayName(context, uri) to SyncFile.parse(bytes)
                }
            }
            outcome.fold(
                onSuccess = { pair ->
                    val (name, pf) = pair
                    fileName = name
                    val err = pf.error
                    if (!pf.ok || err != null) {
                        // §10-d：parse 阶段即失败 ⇒ 直接弹错误框，**不产生任何写盘**
                        parseError = err ?: SyncFile.SyncError(SyncFile.ERR_BAD_FORMAT)
                        return@fold
                    }
                    val dev = withContext(Dispatchers.IO) { SyncBackend.loadDeviceAccounts(profiles) }
                    parsed = pf
                    deviceAccounts = dev
                    choices = SyncFile.defaultChoices(pf, dev)
                    idx = 0
                    visited = setOf(0)
                    step = STEP_MODE
                },
                onFailure = { e ->
                    parseError = SyncFile.SyncError(SyncFile.ERR_BAD_FORMAT, e.message)
                },
            )
        }
    }
    // 进入即拉起选择器；重组不会重放（key = Unit），只有 Activity 重建才会再弹一次。
    // ⚠️ MIME 用通配：记录文件的 MIME 因来源而异，写死 `application/json` 会把部分文件管理器
    // 里的 `.json` 挡在外面。**这个字面量不得抄进任何 KDoc / 块注释** —— Kotlin 的块注释可嵌套，
    // 注释里出现「斜杠紧跟星号」会开启嵌套层级、把后续源码整段吞掉（报错位置还会偏离真因）。
    LaunchedEffect(Unit) { picker.launch(arrayOf("*/*")) }

    // 返回键：Step2 → 本账号 Step1；其余（Step1 / 结果）→ 退出本页
    BackHandler(enabled = true) {
        if (step == STEP_PREVIEW) step = STEP_MODE else onBack()
    }

    val pf = parsed
    val accounts = pf?.accounts.orEmpty()
    // 展示名随文件内容变（重名加 (1)/(2)）；`accounts` 由 orEmpty() 每次新建 ⇒ key 用 `pf`
    val names = remember(pf) { displayNames(accounts) }
    val choice = choices.getOrNull(idx)

    /** 改**当前账号**的选择（`choices` 是唯一配置源；只替换一项，其余原样保留）。 */
    fun updateChoice(transform: (SyncFile.ImportChoice) -> SyncFile.ImportChoice) {
        val list = choices.toMutableList()
        val cur = list.getOrNull(idx) ?: return
        list[idx] = transform(cur)
        choices = list
    }

    /**
     * 切到**另一个账号**的 Step1 页（`◀ / ▶`）。
     *
     * 只改下标 + 把目标下标记入 `visited`；**绝不清空**任何已选（契约 §6.2-c，二者正交）。
     * 越界 / 原地目标原样忽略（`◀` 在首个、`▶` 在末个时按钮本就置灰，这里是第二道闸）。
     */
    fun goTo(target: Int) {
        if (target !in accounts.indices || target == idx) return
        idx = target
        visited = visited + target
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PageBackHeader(
                title = stringResource(R.string.settings_import),
                colors = colors,
                onBack = onBack,
            )
        }

        // —— 抓包中横幅（与 DataCleanupScreen 同款说明；底部按钮同时置灰）——
        if (running) {
            item {
                Text(
                    text = stringResource(R.string.clean_need_stop_banner),
                    fontSize = 13.sp,
                    color = ColorWarpedRed,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        if (pf == null) {
            // 选择器已弹起 / 正在解析：只留返回栏（取消即返回，这一瞬几乎不可见）
            return@LazyColumn
        }

        when (step) {
            STEP_MODE -> modeStepItems(
                colors = colors,
                accounts = accounts,
                names = names,
                idx = idx,
                // 已浏览数 = 历史访问过的 ∪ 当前（并上当前可兜住「初始第 1 个账号」与任何非 ◀▶ 路径）
                browsed = (visited + idx).count { it in accounts.indices },
                fileName = fileName,
                choice = choice,
                deviceAccounts = deviceAccounts,
                onPrev = { goTo(idx - 1) },
                onNext = { goTo(idx + 1) },
                onPickModeNew = {
                    updateChoice { it.copy(mode = SyncFile.MODE_NEW, target = null) }
                },
                onPickModeMerge = {
                    updateChoice {
                        it.copy(
                            mode = SyncFile.MODE_MERGE,
                            target = it.target ?: defaultTargetFor(accounts[idx].name, deviceAccounts),
                        )
                    }
                },
                onPickTarget = { pid -> updateChoice { it.copy(target = pid) } },
                onPickModeSkip = {
                    // 「不导入」：只改 mode，**不清空** `target` / `chosen`（契约 §6.2-c）——
                    // 用户改回「合并」时原目标必须还在（镜像 PC `_store_current`）。
                    updateChoice { it.copy(mode = SyncFile.MODE_SKIP) }
                },
                // 2026-09-23：把「使用哪一份」从 Step2 移入 Step1 —— 随 ◀/▶ 逐账号可分别设定，
                // 不再「只对最后一个账号可改」（待办 §0.4）。
                onPickChosen = { chosen -> updateChoice { it.copy(chosen = chosen) } },
                onNextStep = { step = STEP_PREVIEW },
            )

            STEP_PREVIEW -> previewStepItems(
                colors = colors,
                accounts = accounts,
                names = names,
                idx = idx,
                fileName = fileName,
                source = pf.source,
                exportedAt = pf.exportedAt,
                choices = choices,
                deviceAccounts = deviceAccounts,
                onPickChosen = { chosen -> updateChoice { it.copy(chosen = chosen) } },
                onBackStep = { step = STEP_MODE },
                onSubmit = {
                    // 数据层兜底：UI 置灰挡不住「点确认的这一瞬间恰好开了抓包」
                    if (GachaVpnService.isRunning()) {
                        applyError = context.getString(R.string.sync_disabled_capturing)
                        return@previewStepItems
                    }
                    // 全部账号都选了「不导入」⇒ **一个字节都不写，也不进事务壳**：
                    // 事务壳的「计划为空」守卫是**防御性断言**（表达调用方 bug），
                    // 不用它表达用户的合法选择（定案稿 §6 裁定）。直接进结果页说明。
                    if (choices.isNotEmpty() && choices.all { it.mode == SyncFile.MODE_SKIP }) {
                        result = null
                        step = STEP_DONE
                        return@previewStepItems
                    }
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                // 校验在纯函数层（不合法抛 IllegalArgumentException）——
                                // 保证「计划一旦产出，IO 层即可无条件执行」（§2.6）
                                SyncBackend.applyPlan(
                                    profiles,
                                    SyncFile.planImport(pf, deviceAccounts, choices),
                                )
                            }
                        }
                        busy = false
                        outcome.fold(
                            onSuccess = { res ->
                                if (res.ok) {
                                    // §6.2-d 一次刷新：提交成功后统一 bump 一次
                                    HistoryEpoch.bump()
                                    result = res
                                    step = STEP_DONE
                                } else {
                                    applyError = res.error ?: context.getString(R.string.sync_err_bad_format)
                                }
                            },
                            onFailure = { e ->
                                // 走到这里只可能是 `planImport` 的调用方 bug（choices 不合法）——
                                // `applyPlan` 内部把写盘异常全兜成 ok=false + 人话 error ⇒ 磁盘未动
                                applyError = e.message ?: context.getString(R.string.sync_err_apply)
                            },
                        )
                    }
                },
            )

            else -> doneStepItems(
                colors = colors,
                result = result,
                // 「未导入」条数只能从 UI 层 `choices` 数 —— SKIP 账号不在计划里，`plan` 里找不到它们
                skipped = choices.count { it.mode == SyncFile.MODE_SKIP },
                onClose = onBack,
            )
        }
    }

    // —— 解析错误（§10-a：AlertDialog，不用 Toast）——
    parseError?.let { err ->
        AlertDialog(
            onDismissRequest = { parseError = null; onBack() },
            title = { Text(stringResource(R.string.settings_import)) },
            text = { Text(parseErrorText(err)) },
            confirmButton = {
                TextButton(onClick = { parseError = null; onBack() }) {
                    Text(stringResource(R.string.account_action_confirm), color = colors.gold)
                }
            },
        )
    }

    // —— 落库失败（整组已回滚，磁盘无半份结果）——
    applyError?.let { msg ->
        AlertDialog(
            onDismissRequest = { applyError = null },
            title = { Text(stringResource(R.string.settings_import)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { applyError = null }) {
                    Text(stringResource(R.string.account_action_confirm), color = colors.gold)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Step 常量
// ---------------------------------------------------------------------------

private const val STEP_MODE = 1
private const val STEP_PREVIEW = 2
private const val STEP_DONE = 3

// ---------------------------------------------------------------------------
// Step1：方式 / 目标（逐账号）
// ---------------------------------------------------------------------------

/**
 * Step1 的列表内容（抽成扩展函数只为让主壳可读；**不是**独立 Composable，
 * 因为要往主 [LazyColumn] 里追加 item）。
 *
 * @param browsed 已浏览过的账号页数（含当前页）。**浏览完全部账号**才放开「下一步」——
 *   逐账号页只看一眼首个账号就提交，后面账号的导入方式会被默认值静默带走（2026-09-22）。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.modeStepItems(
    colors: JinlinColors,
    accounts: List<SyncFile.ParsedAccount>,
    names: List<String>,
    idx: Int,
    browsed: Int,
    fileName: String,
    choice: SyncFile.ImportChoice?,
    deviceAccounts: List<SyncFile.DeviceAccount>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickModeNew: () -> Unit,
    onPickModeMerge: () -> Unit,
    onPickTarget: (String) -> Unit,
    onPickModeSkip: () -> Unit,
    onPickChosen: (String) -> Unit,
    onNextStep: () -> Unit,
) {
    val account = accounts.getOrNull(idx) ?: return
    // 三态（2026-09-22 加 `MODE_SKIP`）：`checked` 判据必须**逐态**比较，不能再用 `!merge` ——
    // 否则「不导入」路径下「作为新用户导入」会**同时**显示成选中（两个 ◉）。
    val mode = choice?.mode ?: SyncFile.MODE_NEW
    val merge = mode == SyncFile.MODE_MERGE
    val skip = mode == SyncFile.MODE_SKIP
    // §5：设备上一个账号都没有时，只保留「作为新用户导入」
    val mergeAvailable = deviceAccounts.isNotEmpty()

    item {
        Text(
            text = fileName,
            fontSize = 11.sp,
            color = colors.onSurfaceMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // —— 账号切换器（跨账号；切换**不清空**任何已选，§6.2-c）——
    item {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "◀",
                fontSize = 18.sp,
                color = if (idx > 0) colors.gold else colors.onSurfaceMuted,
                modifier = Modifier
                    .clickable(enabled = idx > 0) { onPrev() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sync_import_step1_title, idx + 1, accounts.size),
                    fontSize = 14.sp,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.sync_account_with_count,
                        names.getOrNull(idx).orEmpty(),
                        account.records.size,
                    ),
                    fontSize = 12.sp,
                    color = colors.onSurfaceDim,
                )
            }
            Text(
                text = "▶",
                fontSize = 18.sp,
                color = if (idx < accounts.size - 1) colors.gold else colors.onSurfaceMuted,
                modifier = Modifier
                    .clickable(enabled = idx < accounts.size - 1) { onNext() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    // —— 导入方式 ——
    item { GroupHeader(stringResource(R.string.sync_import_mode_label), colors) }
    item {
        SectionCard(colors) {
            ChoiceRow(
                label = stringResource(R.string.sync_import_mode_new),
                checked = mode == SyncFile.MODE_NEW,
                enabled = true,
                colors = colors,
                onSelect = onPickModeNew,
            )
            RowDivider(colors)
            ChoiceRow(
                label = stringResource(R.string.sync_import_mode_merge),
                checked = merge,
                enabled = mergeAvailable,
                colors = colors,
                onSelect = onPickModeMerge,
            )
            RowDivider(colors)
            // 第三态「不导入这个用户」：该账号整份不进计划（契约 `MODE_SKIP`）。
            // 设备上一个账号都没有时它同样可选（比「新建一个空账号」更干净）。
            ChoiceRow(
                label = stringResource(R.string.sync_import_mode_skip),
                checked = skip,
                enabled = true,
                colors = colors,
                onSelect = onPickModeSkip,
            )
        }
    }

    // —— 目标用户（仅合并路径，§5）——
    if (merge && mergeAvailable) {
        item { GroupHeader(stringResource(R.string.sync_import_target_label), colors) }
        item {
            SectionCard(colors) {
                deviceAccounts.forEachIndexed { i, dev ->
                    if (i > 0) RowDivider(colors)
                    ChoiceRow(
                        label = stringResource(
                            R.string.sync_account_with_count,
                            dev.name,
                            dev.records.size,
                        ),
                        checked = dev.id == choice?.target,
                        enabled = true,
                        colors = colors,
                        onSelect = { onPickTarget(dev.id) },
                    )
                }
            }
        }
    }

    // —— 「使用哪一份」（仅合并路径；2026-09-23 从 Step2 移入，随 ◀/▶ 逐账号可分别设定）——
    if (merge && mergeAvailable) {
        // 合并目标的设备账号现值（镜像 previewStepItems 的 `_device_account` 口径）
        val targetDev = deviceAccounts.firstOrNull { it.id == choice?.target } ?: deviceAccounts.firstOrNull()
        val cmp = if (targetDev != null) SyncFile.compare(targetDev.records, account.records) else null
        item { GroupHeader(stringResource(R.string.sync_choose_prompt), colors) }
        item {
            SectionCard(colors) {
                ChoiceRow(
                    label = stringResource(R.string.sync_choose_file, account.records.size),
                    checked = choice?.chosen != SyncFile.CHOSEN_DEVICE,
                    enabled = true,
                    colors = colors,
                    onSelect = { onPickChosen(SyncFile.CHOSEN_FILE) },
                )
                RowDivider(colors)
                ChoiceRow(
                    label = stringResource(R.string.sync_choose_device, cmp?.deviceCount ?: targetDev?.records?.size ?: 0),
                    checked = choice?.chosen == SyncFile.CHOSEN_DEVICE,
                    enabled = true,
                    colors = colors,
                    onSelect = { onPickChosen(SyncFile.CHOSEN_DEVICE) },
                )
            }
        }
        if (cmp != null && cmp.both == 0 && cmp.deviceCount > 0 && cmp.fileCount > 0) {
            item {
                Text(
                    text = stringResource(R.string.sync_conflict_warn),
                    fontSize = 12.sp,
                    color = ColorWarpedRed,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    // —— 空账号提示（§4：`records: []` 合法，但要说清楚）——
    if (account.records.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.sync_empty_account),
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // —— 门槛（2026-09-22）：浏览完全部用户才能进 Step2；未满足时在按钮上方说明原因 ——
    val browsedAll = browsed >= accounts.size
    if (!browsedAll) {
        item {
            Text(
                text = stringResource(R.string.sync_browse_all_hint, browsed, accounts.size),
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    item {
        StepButtons(
            colors = colors,
            primaryText = stringResource(R.string.sync_next),
            primaryEnabled = browsedAll,
            onPrimary = onNextStep,
        )
    }
}

// ---------------------------------------------------------------------------
// Step2：预览
// ---------------------------------------------------------------------------

/**
 * Step2 的列表内容：**全部账号总览** + 当前账号详情。
 *
 * 2026-09-22 修：此前只吃 `accounts[idx]` 一个账号，用户在预览里看不到其余账号会被怎么导。
 * 现在顶部先列**文件里每个账号**的去向（新建 / 合并到谁 / 重合与丢弃条数 / 冲突标记），
 * 再展开当前账号的完整对照与「选哪一份」。
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
private fun androidx.compose.foundation.lazy.LazyListScope.previewStepItems(
    colors: JinlinColors,
    accounts: List<SyncFile.ParsedAccount>,
    names: List<String>,
    idx: Int,
    fileName: String,
    source: Map<String, Any?>?,
    exportedAt: String?,
    choices: List<SyncFile.ImportChoice>,
    deviceAccounts: List<SyncFile.DeviceAccount>,
    onPickChosen: (String) -> Unit,
    onBackStep: () -> Unit,
    onSubmit: () -> Unit,
) {
    val account = accounts.getOrNull(idx) ?: return
    val name = names.getOrNull(idx).orEmpty()
    val choice = choices.getOrNull(idx)
    val mode = choice?.mode ?: SyncFile.MODE_NEW
    val merge = mode == SyncFile.MODE_MERGE
    // 第三态「不导入」：该账号**不参与 compare**，详情区换成一行说明（契约 `MODE_SKIP`）
    val skip = mode == SyncFile.MODE_SKIP
    // 「合并目标」的当前设备账号（镜像 PC `_device_account()`：NEW / SKIP 路径返回 null）
    val device = if (merge) {
        deviceAccounts.firstOrNull { it.id == choice?.target } ?: deviceAccounts.firstOrNull()
    } else {
        null
    }
    // 判据一律取自纯函数层（不在这里重算三集合 / 冲突）
    val cmp = if (device != null) SyncFile.compare(device.records, account.records) else null
    // 仅一方非空不算冲突（§10-c）；两侧都非空且零重叠才提示
    val conflict = cmp != null && cmp.both == 0 && cmp.deviceCount > 0 && cmp.fileCount > 0
    val chosen = choice?.chosen ?: SyncFile.CHOSEN_FILE
    val discard = when {
        cmp == null -> 0
        chosen == SyncFile.CHOSEN_FILE -> cmp.deviceOnly
        else -> cmp.fileOnly
    }
    // R3：文件没给总抽数基准（`has_anchor=false`）⇒ 提示
    val hasAnchor = account.integrity?.let { m ->
        if (m.containsKey("has_anchor")) m["has_anchor"] == true else account.total != null
    } ?: (account.total != null)

    item {
        Text(
            text = stringResource(R.string.sync_import_step2_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
    }
    item {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = fileName,
                fontSize = 12.sp,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.sync_import_from,
                    platformLabel(source),
                    fmtIso(exportedAt),
                ),
                fontSize = 11.sp,
                color = colors.onSurfaceMuted,
            )
        }
    }

    // —— 总览（2026-09-22）：文件里**每个**账号的去向，而不只是当前这一页 ——
    // 判据与下方详情区同源（compare / mode / chosen），此处不重算任何数字。
    item { GroupHeader(stringResource(R.string.sync_overview_title), colors) }
    item {
        SectionCard(colors) {
            accounts.forEachIndexed { i, acc ->
                if (i > 0) RowDivider(colors)
                val ch = choices.getOrNull(i)
                val chMode = ch?.mode ?: SyncFile.MODE_NEW
                val isSkip = chMode == SyncFile.MODE_SKIP
                val isMerge = chMode == SyncFile.MODE_MERGE
                // 合并目标解析口径与下方详情区一致（镜像 PC `_device_account()`：NEW / SKIP 路径为 null）
                val dev = if (isMerge) {
                    deviceAccounts.firstOrNull { it.id == ch?.target } ?: deviceAccounts.firstOrNull()
                } else {
                    null
                }
                val r = if (dev != null) SyncFile.compare(dev.records, acc.records) else null
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                R.string.sync_account_with_count,
                                names.getOrNull(i).orEmpty(),
                                acc.records.size,
                            ),
                            fontSize = 13.sp,
                            color = if (i == idx) colors.gold else colors.onSurface,
                        )
                        if (i == idx) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.sync_overview_current),
                                fontSize = 11.sp,
                                color = colors.gold,
                            )
                        }
                    }
                    Text(
                        text = when {
                            // 「不导入」路径：**不参与 compare**，只说清「文件里多少条不写入」
                            isSkip -> stringResource(R.string.sync_overview_skip, acc.records.size)
                            // 新建路径：复用详情区同句（§8.2「同句」）
                            dev == null || r == null ->
                                stringResource(R.string.sync_new_as_account, acc.name, acc.records.size)
                            else -> stringResource(
                                R.string.sync_overview_merge,
                                dev.name,
                                r.both,
                                if (ch?.chosen == SyncFile.CHOSEN_DEVICE) r.fileOnly else r.deviceOnly,
                            )
                        },
                        fontSize = 12.sp,
                        color = colors.onSurfaceDim,
                    )
                    // 仅一方非空不算冲突（§10-c）—— 与详情区同一判据
                    if (r != null && r.both == 0 && r.deviceCount > 0 && r.fileCount > 0) {
                        Text(
                            text = stringResource(R.string.sync_overview_conflict),
                            fontSize = 12.sp,
                            color = ColorWarpedRed,
                        )
                    }
                }
            }
        }
    }

    // —— 以下为**当前账号**的完整详情（原「第 N/M 个账号」标题在底部，2026-09-22 提到详情之前）——
    item {
        GroupHeader(
            stringResource(R.string.sync_import_step1_title, idx + 1, accounts.size),
            colors,
        )
    }
    item {
        Text(
            text = stringResource(R.string.sync_account_with_count, name, account.records.size),
            fontSize = 12.sp,
            color = colors.onSurfaceDim,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // —— 对照表（新建路径单列，§5；「不导入」路径整块不画）——
    if (!skip) {
        item {
            SectionCard(colors) {
                CompareTable(
                    colors = colors,
                    device = device,
                    account = account,
                    cmp = cmp,
                )
            }
        }
    }

    // —— 「不导入」路径：把「对照表 / 三集合 / 选哪一份 / 丢弃」整块换成一行说明 ——
    if (skip) {
        item {
            Text(
                text = stringResource(R.string.sync_skip_hint),
                fontSize = 13.sp,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // —— 新建路径：单列说明 ——
    if (!merge && !skip) {
        item {
            Text(
                text = stringResource(
                    R.string.sync_new_as_account,
                    account.name,
                    account.records.size,
                ),
                fontSize = 13.sp,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // —— 合并路径：三集合 + 冲突 + 选哪一份 + 丢弃 ——
    if (merge && cmp != null) {
        item {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = stringResource(R.string.sync_set_both, cmp.both),
                    fontSize = 12.sp,
                    color = colors.onSurfaceDim,
                )
                Text(
                    text = stringResource(R.string.sync_set_device_only, cmp.deviceOnly),
                    fontSize = 12.sp,
                    color = colors.onSurfaceDim,
                )
                Text(
                    text = stringResource(R.string.sync_set_file_only, cmp.fileOnly),
                    fontSize = 12.sp,
                    color = colors.onSurfaceDim,
                )
            }
        }
        if (conflict) {
            item {
                Text(
                    text = stringResource(R.string.sync_conflict_warn),
                    fontSize = 12.sp,
                    color = ColorWarpedRed,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item { GroupHeader(stringResource(R.string.sync_choose_prompt), colors) }
        item {
            SectionCard(colors) {
                ChoiceRow(
                    label = stringResource(R.string.sync_choose_file, cmp.fileCount),
                    checked = chosen == SyncFile.CHOSEN_FILE,
                    enabled = true,
                    colors = colors,
                    onSelect = { onPickChosen(SyncFile.CHOSEN_FILE) },
                )
                RowDivider(colors)
                ChoiceRow(
                    label = stringResource(R.string.sync_choose_device, cmp.deviceCount),
                    checked = chosen == SyncFile.CHOSEN_DEVICE,
                    enabled = true,
                    colors = colors,
                    onSelect = { onPickChosen(SyncFile.CHOSEN_DEVICE) },
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.sync_discard_warn, discard),
                fontSize = 12.sp,
                color = ColorWarpedRed,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // —— R3：无总抽数基准（与路径无关；但「不导入」路径不写盘，该警告无意义）——
    if (!hasAnchor && !skip) {
        item {
            Text(
                text = stringResource(R.string.sync_no_anchor_warn),
                fontSize = 12.sp,
                color = ColorWarpedRed,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    item {
        StepButtons(
            colors = colors,
            secondaryText = stringResource(R.string.sync_back),
            onSecondary = onBackStep,
            primaryText = stringResource(R.string.sync_confirm_import),
            primaryEnabled = !GachaVpnService.state.value.running,
            onPrimary = onSubmit,
        )
    }
}

// ---------------------------------------------------------------------------
// Step3：结果
// ---------------------------------------------------------------------------

/**
 * Step3 的列表内容：逐账号条数 + 备份说明。
 *
 * @param skipped 按用户选择**未导入**的账号数。只能从 UI 层 `choices` 数出来 —— `SKIP` 账号
 *   不在计划里（契约 `MODE_SKIP`），`plan` 里根本找不到它们。
 *   全部账号都被跳过时 [result] 为 `null`（**一个字节都没写**）：不画明细、也不显示备份说明。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.doneStepItems(
    colors: JinlinColors,
    result: SyncBackend.ApplyResult?,
    skipped: Int,
    onClose: () -> Unit,
) {
    val written = result?.written.orEmpty()
    item {
        Text(
            text = stringResource(R.string.sync_done_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
    }
    if (written.isEmpty()) {
        // 全 SKIP（或没有要写的账号）⇒ 未落盘：说清「没有导入任何内容」，不画空明细卡
        item {
            Text(
                text = stringResource(R.string.sync_done_nothing),
                fontSize = 14.sp,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    } else {
        item {
            SectionCard(colors) {
                written.forEachIndexed { i, w ->
                    if (i > 0) RowDivider(colors)
                    // `sync_done_line` = 「%1$s：%2$d 条」—— 逐账号一行（与 PC 结果提示同句）
                    Text(
                        text = stringResource(R.string.sync_done_line, w.name, w.records),
                        fontSize = 14.sp,
                        color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
    // 「未导入」条数（部分账号被跳过时才有）
    if (skipped > 0) {
        item {
            Text(
                text = stringResource(R.string.sync_done_skipped, skipped),
                fontSize = 13.sp,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    // 备份说明只在**确实写过盘**时才有意义（全 SKIP 时没有备份，说了会误导）
    if (written.isNotEmpty()) {
        item {
            Text(
                text = stringResource(R.string.sync_done_backup),
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    item {
        StepButtons(
            colors = colors,
            primaryText = stringResource(R.string.account_action_close),
            primaryEnabled = true,
            onPrimary = onClose,
        )
    }
}

// ---------------------------------------------------------------------------
// 子组件
// ---------------------------------------------------------------------------

/** 页内返回栏（与 [DataCleanupScreen] 顶部同款）。 */
@Composable
private fun PageBackHeader(title: String, colors: JinlinColors, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBack() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "‹", fontSize = 22.sp, color = colors.gold)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
    }
}

/**
 * 底部步进按钮组（单按钮右对齐 / 双按钮「次要在左、主要靠右」）。
 *
 * 用 [Text] + `clickable` 而非 `TextButton` —— 与页内其它可点行的点击热区口径一致。
 */
@Composable
private fun StepButtons(
    colors: JinlinColors,
    primaryText: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secondaryText != null && onSecondary != null) {
            Text(
                text = secondaryText,
                fontSize = 14.sp,
                color = colors.onSurfaceDim,
                modifier = Modifier
                    .clickable { onSecondary() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (primaryEnabled) colors.gold else colors.onSurfaceMuted,
            modifier = Modifier
                .clickable(enabled = primaryEnabled) { onPrimary() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * 「应用里 / 文件里」对照表（新建路径合并为单列）。
 *
 * 判据来自纯函数层：`newer == FILE` 时给「文件里」列打上「← 新」。
 */
@Composable
private fun CompareTable(
    colors: JinlinColors,
    device: SyncFile.DeviceAccount?,
    account: SyncFile.ParsedAccount,
    cmp: SyncFile.CompareResult?,
) {
    val fileMax = maxTimestamp(account.records)
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (device == null || cmp == null) {
            // 单列：新建路径（§5）
            TableRow2(
                colors = colors,
                c0 = "",
                c1 = stringResource(R.string.sync_col_file),
                c1Bold = true,
            )
            TableRow2(
                colors = colors,
                c0 = stringResource(R.string.sync_row_latest),
                c1 = fmtTs(fileMax),
            )
            TableRow2(
                colors = colors,
                c0 = stringResource(R.string.sync_row_count),
                c1 = account.records.size.toString(),
            )
        } else {
            val newerSuffix = if (cmp.newer == SyncFile.NEWER_FILE) " ← 新" else ""
            TableRow2(
                colors = colors,
                c0 = "",
                c1 = stringResource(R.string.sync_col_device),
                c1Bold = true,
                c2 = stringResource(R.string.sync_col_file) + newerSuffix,
                c2Bold = true,
            )
            TableRow2(
                colors = colors,
                c0 = stringResource(R.string.sync_row_latest),
                c1 = fmtTs(cmp.deviceMaxTs),
                c2 = fmtTs(fileMax),
            )
            TableRow2(
                colors = colors,
                c0 = stringResource(R.string.sync_row_count),
                c1 = cmp.deviceCount.toString(),
                c2 = cmp.fileCount.toString(),
            )
        }
    }
}

/** 对照表的一行：1 列标签 + 1~2 列值（`c2 == null` 即单列）。 */
@Composable
private fun TableRow2(
    colors: JinlinColors,
    c0: String,
    c1: String,
    c2: String? = null,
    c1Bold: Boolean = false,
    c2Bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = c0,
            fontSize = 12.sp,
            color = colors.onSurfaceDim,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = c1,
            fontSize = 12.sp,
            fontWeight = if (c1Bold) FontWeight.Bold else FontWeight.Normal,
            color = if (c1Bold) colors.gold else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (c2 != null) {
            Text(
                text = c2,
                fontSize = 12.sp,
                fontWeight = if (c2Bold) FontWeight.Bold else FontWeight.Normal,
                color = if (c2Bold) colors.gold else colors.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

private val PREVIEW_TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 账号展示名：**文件内同名是合法的**（§4 边界表）⇒ 重名加「(1)/(2)」后缀区分。
 * 镜像 PC `sync_dialog.display_names`。
 */
private fun displayNames(accounts: List<SyncFile.ParsedAccount>): List<String> {
    val counts = HashMap<String, Int>()
    for (a in accounts) counts[a.name] = (counts[a.name] ?: 0) + 1
    val seen = HashMap<String, Int>()
    return accounts.map { a ->
        if ((counts[a.name] ?: 0) > 1) {
            val n = (seen[a.name] ?: 0) + 1
            seen[a.name] = n
            "${a.name} ($n)"
        } else {
            a.name
        }
    }
}

/** 合并路径的默认目标账号：优先**同名**账号，其次列表首个（镜像 `defaultChoices` 的取向）。 */
private fun defaultTargetFor(name: String, deviceAccounts: List<SyncFile.DeviceAccount>): String? =
    deviceAccounts.firstOrNull { it.name == name }?.id ?: deviceAccounts.firstOrNull()?.id

/** 毫秒时间戳 → 「MM-dd HH:mm」；空值 / 脏值显示 `—`（不抛），镜像 PC `_fmt_ts`。 */
private fun fmtTs(ts: Long?): String {
    if (ts == null) return "—"
    return try {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
            .format(PREVIEW_TS_FMT)
    } catch (e: Exception) {
        "—"
    }
}

/** ISO 时间串 → 「MM-dd HH:mm」；解析不出就原样返回（不抛），镜像 PC `_fmt_iso`。 */
private fun fmtIso(text: String?): String {
    if (text.isNullOrEmpty()) return "—"
    return try {
        LocalDateTime.parse(text).format(PREVIEW_TS_FMT)
    } catch (e: Exception) {
        text
    }
}

/** 信封 `source.platform`（缺失 / 非字符串 ⇒ 「未知来源」，镜像 PC 的 `platform or '未知来源'`）。 */
private fun platformLabel(source: Map<String, Any?>?): String {
    val p = source?.get("platform")
    return if (p is String && p.isNotEmpty()) p else "未知来源"
}

/** 最大 `timestamp`；无记录返回 `null`（镜像 PC `max(..., default=None)`）。 */
private fun maxTimestamp(records: List<Map<String, Any?>>): Long? {
    var best: Long? = null
    for (r in records) {
        val ts = SyncFile.asLong(r["timestamp"]) ?: continue
        if (best == null || ts > best) best = ts
    }
    return best
}

/** 错误码 → 用户文案（§8 文案：与 PC **同句**）。 */
@Composable
private fun parseErrorText(error: SyncFile.SyncError): String = when (error.code) {
    SyncFile.ERR_VERSION_TOO_HIGH -> stringResource(R.string.sync_err_version)
    SyncFile.ERR_NO_ACCOUNTS -> stringResource(R.string.sync_err_no_accounts)
    SyncFile.ERR_CORRUPT -> {
        val i = corruptAccountIndex(error.detail)
        if (i == null) {
            stringResource(R.string.sync_err_bad_format)
        } else {
            stringResource(R.string.sync_err_corrupt, i + 1)
        }
    }
    else -> stringResource(R.string.sync_err_bad_format)
}

/**
 * 从 `CORRUPT` 的 detail（形如 `account#3 record#7: no-identity`）里取出**0 基**账号下标。
 *
 * 纯函数层恒带 `account#`（见 [SyncFile.parse] 的三处 `err(ERR_CORRUPT, …)`），
 * 解析不出返回 `null` ⇒ 调用方退回不带下标的文案。
 */
private fun corruptAccountIndex(detail: String?): Int? {
    val d = detail ?: return null
    val marker = "account#"
    val start = d.indexOf(marker)
    if (start < 0) return null
    val digits = d.substring(start + marker.length).takeWhile { it.isDigit() }
    return digits.toIntOrNull()
}

/** 取 SAF `content://` URI 的显示名；取不到返回空串（仅用于展示，不参与任何判据）。 */
private fun queryDisplayName(context: Context, uri: Uri): String = try {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) c.getString(col).orEmpty() else ""
        }
        .orEmpty()
} catch (e: Exception) {
    ""
}
