package com.jinlin.gacha.assistant.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.sync.SyncFile
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.SyncBackend
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 跨端记录互通 —— **导出对话框**（设置 → 数据互通 → 导出记录）。
 *
 * 镜像 PC `gacha_exporter/gui/sync_dialog.py::ExportSyncDialog`；出口与 PC 的差异见下。
 *
 * ### 两种出口（2026-09-22 用户裁定新增）
 * | 保存方式 | 走什么 | 为什么需要它 |
 * |---|---|---|
 * | **存到手机** | 系统保存框（SAF `ACTION_CREATE_DOCUMENT`）⇒ 写进用户挑的目标 | **零权限、全版本可用、不依赖任何第三方 App** |
 * | **分享出去** | 落 `filesDir/exports/` ⇒ `FileProvider` + `ACTION_SEND`（原路径不变） | 直接把文件发给别人（微信 / 网盘） |
 *
 * ⚠️ **为什么两条都要**：原先只有「分享」，一旦设备上没有能接收 `ACTION_SEND` 的应用
 * （模拟器、精简 ROM 常见），用户就**完全拿不到文件**；而「手机导出 → PC 导入」的对拍
 * 恰恰需要先能把文件取出来 ⇒ 手机侧不能只有"分享给谁"这一条路。
 *
 * ⚠️ **为什么「存到手机」选 SAF，而不是 `MediaStore.Downloads` 直写**：
 * `minSdk = 28` ⇒ `MediaStore` 要写两套（API 29+ 走 `RELATIVE_PATH`，API 28 走 legacy
 * 且需 `WRITE_EXTERNAL_STORAGE`），多一条权限、多一个分支；SAF 自 API 19 起可用、
 * **零权限、单一路径**，而且与 PC 的 `QFileDialog`（用户自己挑位置）**体验同构**。
 *
 * ### 与 PC 逐条对齐的地方
 * - **范围二选一**（当前账号 / 全部账号），文案取自同一句话（`sync_export_range_*`）；
 * - **默认文件名跟随范围**（`jinlin-records-all-<stamp>.json` /
 *   `jinlin-records-<账号名>-<stamp>.json`，账号名过 [safeFileName]）—— 与 PC
 *   `default_export_name` 同形；
 * - 「当前账号」取**当前激活账号**，拿不到时回退列表首个（对齐 PC `_active_account()`）；
 * - 信封构造与序列化**全部交给纯函数层**（[SyncFile.buildExportAccount] /
 *   [SyncFile.buildEnvelope] / [SyncFile.dumps]）—— 本文件**不重写任何判据**，
 *   也不自己拼 JSON（§6.3 边界：UI 层不碰格式）；
 * - 🔑 两条出口共用 [prepareExport] 产出的**同一份字节** ⇒ 换个出口不会换文件内容。
 *
 * ### ⚠️ 边界
 * - **抓包中不可导出**：入口行在 [SettingsScreen] 已置灰；本对话框自身不再判（与
 *   「清空 / 还原」同约定：入口置灰 + 数据层兜底，导出是**只读**动作，不构成丢数据风险，
 *   故不额外加锁 —— 真正要防的是写侧）。
 * - 读设备快照走 [SyncBackend.loadDeviceAccounts]，是**只读**；本对话框**不改任何记录**。
 * - 「存到手机」只写**用户挑中的那一个文件**，不碰 `filesDir/exports/`；只有「分享」
 *   才会先清空该目录（§10-b 的覆盖式清理）⇒ 两条出口互不影响。
 * - ⚠️ **等系统保存框期间不关闭对话框**：`rememberLauncherForActivityResult` 注册的
 *   launcher 随本 Composable 离开组合而注销，先关对话框就**收不到保存结果** ⇒ 保持打开
 *   （`busy = true`，按钮与返回键都挡住），拿到结果后再关。
 *
 * @param onDismiss 关闭回调（取消 / 导出完成后均走它）
 */
@Composable
internal fun SyncExportDialog(
    colors: JinlinColors,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember { ProfileStore.get(context) }
    val (versionName, _) = remember { appVersion(context) }

    var loading by remember { mutableStateOf(true) }
    var accounts by remember { mutableStateOf<List<SyncFile.DeviceAccount>>(emptyList()) }
    var activeId by remember { mutableStateOf("") }
    var allScope by remember { mutableStateOf(false) }
    var saveToDevice by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    /** 「存到手机」待写内容（默认文件名 → 文本）；系统保存框是异步的，先备好、回调里再落。 */
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 快照在读盘线程取：账号多时读 N 个 json，不能压在组合期做。
    LaunchedEffect(Unit) {
        activeId = profiles.state.value.activeId
        accounts = withContext(Dispatchers.IO) { SyncBackend.loadDeviceAccounts(profiles) }
        loading = false
    }

    // 「当前账号」：优先激活账号，拿不到回退列表首个（对齐 PC `_active_account()`）
    val active = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
    val currentCount = active?.records?.size ?: 0
    val totalCount = accounts.sumOf { it.records.size }
    val enabled = !loading && !busy && accounts.isNotEmpty()

    // —— 「存到手机」出口：系统保存框（SAF）。零权限；取消即静默收场，失败回落 Toast。
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME),
    ) { uri: Uri? ->
        val ready = pending
        pending = null
        if (uri == null) {
            // 用户在系统保存框里取消（与 PC 上取消 QFileDialog 同义）：不写、不提示
            busy = false
            onDismiss()
        } else {
            scope.launch {
                val err = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: error(context.getString(R.string.sync_export_save_failed))
                        SyncBackend.writeExportStream(out, ready?.second.orEmpty())
                    }.exceptionOrNull()
                }
                busy = false
                val msg = if (err == null) {
                    context.getString(R.string.sync_export_saved)
                } else {
                    err.message ?: context.getString(R.string.sync_export_save_failed)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }
    }

    /** 点「导出」：按保存方式分流；两条出口共用 [prepareExport] 的同一份字节。 */
    fun startExport() {
        val picked = if (allScope) accounts else listOfNotNull(active)
        val exportScope = if (allScope) SCOPE_ALL else SCOPE_CURRENT
        busy = true
        if (saveToDevice) {
            scope.launch {
                val ready = withContext(Dispatchers.IO) {
                    runCatching { prepareExport(versionName, exportScope, picked) }.getOrNull()
                }
                if (ready == null) {
                    busy = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.sync_export_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pending = ready
                try {
                    saver.launch(ready.first)
                } catch (e: ActivityNotFoundException) {
                    // 设备上没有文件管理器（SAF 无接收者）⇒ 明确引导改用「分享出去」
                    pending = null
                    busy = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.sync_export_no_manager),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } else {
            scope.launch {
                val err = withContext(Dispatchers.IO) {
                    runCatching { shareExport(context, prepareExport(versionName, exportScope, picked)) }
                        .exceptionOrNull()
                }
                busy = false
                err?.let { e ->
                    Toast.makeText(
                        context,
                        e.message ?: context.getString(R.string.sync_share_no_app),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sync_export_range_title)) },
        text = {
            Column {
                if (loading) {
                    Text(
                        text = stringResource(R.string.clean_scanning),
                        fontSize = 13.sp,
                        color = colors.onSurfaceDim,
                    )
                } else {
                    ChoiceRow(
                        label = stringResource(
                            R.string.sync_export_range_current,
                            active?.name ?: "—",
                            currentCount,
                        ),
                        checked = !allScope,
                        enabled = enabled && active != null,
                        colors = colors,
                        onSelect = { allScope = false },
                    )
                    ChoiceRow(
                        label = stringResource(
                            R.string.sync_export_range_all,
                            accounts.size,
                            totalCount,
                        ),
                        checked = allScope,
                        enabled = enabled,
                        colors = colors,
                        onSelect = { allScope = true },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // 分组标签用弱化小字（不用 GroupHeader —— 那是页面级 gold 粗体语言，
                    // 在对话框里会跟对话框标题抢层级）。
                    Text(
                        text = stringResource(R.string.sync_export_target_label),
                        fontSize = 12.sp,
                        color = colors.onSurfaceDim,
                    )
                    ChoiceRow(
                        label = stringResource(R.string.sync_export_target_save),
                        checked = saveToDevice,
                        enabled = enabled,
                        colors = colors,
                        onSelect = { saveToDevice = true },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.sync_export_target_share),
                        checked = !saveToDevice,
                        enabled = enabled,
                        colors = colors,
                        onSelect = { saveToDevice = false },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sync_export_hint),
                        fontSize = 11.sp,
                        color = colors.onSurfaceMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { startExport() }, enabled = enabled) {
                Text(
                    text = stringResource(R.string.sync_export_action),
                    color = if (enabled) colors.gold else colors.onSurfaceMuted,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.account_action_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 导出实现（IO 线程）
// ---------------------------------------------------------------------------

/**
 * 构造导出内容 —— **两条出口共用的唯一来源**。
 *
 * @return `文件名 → 文本`（文件名给人看，`format` 给机器校验，两者是两码事）
 */
private fun prepareExport(
    versionName: String,
    exportScope: String,
    picked: List<SyncFile.DeviceAccount>,
): Pair<String, String> {
    // —— ① 信封（纯函数层构造，本层不做任何格式判断）——
    val envelope = SyncFile.buildEnvelope(
        accounts = picked.map { a ->
            SyncFile.buildExportAccount(
                accountId = a.id,
                name = a.name,
                records = a.records,
                total = a.total,
                viewTotals = a.viewTotals,
                sourceTotal = a.total,
            )
        },
        exportedAt = SyncBackend.nowIso(),
        source = SyncBackend.exportSource(versionName),
    )

    // —— ② 默认文件名（跟随范围，与 PC 同形）——
    val stamp = LocalDateTime.now().withNano(0).format(EXPORT_STAMP_FMT)
    val fileName = defaultExportName(
        exportScope,
        picked.firstOrNull()?.name.orEmpty(),
        stamp,
    )

    // dumps() 不做换行翻译 ⇒ 与 PC 产出的文件逐字节同构（对齐 write_export_file 的 newline=""）
    return fileName to SyncFile.dumps(envelope)
}

/**
 * 「分享出去」出口：落 `filesDir/exports/` 后交系统分享。
 *
 * @throws IllegalStateException 设备上没有能接收 `ACTION_SEND` 的应用（落盘**已成功**，
 *   仅分享这一步失败；文案见 `sync_share_no_app`）。
 */
private fun shareExport(context: Context, ready: Pair<String, String>) {
    val (fileName, text) = ready

    // 落盘（§10-b：先清空旧文件，再写新文件）
    val dir = File(context.filesDir, EXPORT_DIR).apply { mkdirs() }
    dir.listFiles()?.forEach { if (it.isFile) it.delete() }

    val file = File(dir, fileName)
    // writeExportFile 不做换行翻译 ⇒ 与 PC 产出的文件逐字节同构（对齐 write_export_file 的 newline=""）
    SyncBackend.writeExportFile(file, text)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = EXPORT_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.settings_export)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (e: ActivityNotFoundException) {
        throw IllegalStateException(context.getString(R.string.sync_share_no_app), e)
    }
}

// ---------------------------------------------------------------------------
// 文件名（镜像 PC `sync_dialog.default_export_name` + `utils/helpers.safe_filename`）
// ---------------------------------------------------------------------------

/** 导出范围：当前账号 / 全部账号（镜像 PC `SCOPE_CURRENT` / `SCOPE_ALL`）。 */
internal const val SCOPE_CURRENT = "current"
internal const val SCOPE_ALL = "all"

/** 导出落盘目录名（契约 §10-b 裁定的 `filesDir/exports/`；FileProvider 白名单见 `res/xml/file_paths.xml`）。 */
internal const val EXPORT_DIR = "exports"

/** 导出 MIME —— 分享 Intent 的 type，也是系统保存框（SAF）声明的 mimeType。 */
private const val EXPORT_MIME = "application/json"

/** 文件名时间戳（对齐 PC `stamp` 的 `YYYYMMDD_HHMM`）。 */
private val EXPORT_STAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

/**
 * 文件名非法字符（镜像 PC `utils/helpers.safe_filename` 的
 * `re.sub(r'[\\/:*?"<>|\[\]\s]+', "_", name).strip("_")`）。
 */
private val UNSAFE_FILENAME = Regex("""[\\/:*?"<>|\[\]\s]+""")

/** 把账号名压成安全文件名（非法字符 → `_`，首尾下划线去掉）。 */
internal fun safeFileName(name: String): String = UNSAFE_FILENAME.replace(name, "_").trim('_')

/**
 * 导出默认文件名 —— 与 PC `default_export_name` **同形**。
 *
 * - 全部：`jinlin-records-all-<stamp>.json`
 * - 当前：`jinlin-records-<账号名>-<stamp>.json`（账号名过 [safeFileName]，空则 `account`）
 *
 * ⚠️ 前缀 `jinlin-records` 是**产品名**；信封内部的 `format` 字段同为 `jinlin-records`
 * （[SyncFile.FORMAT_NAME]）—— 两者是两码事：文件名给人看，`format` 给机器校验。
 */
internal fun defaultExportName(exportScope: String, accountName: String, stamp: String): String {
    if (exportScope == SCOPE_CURRENT) {
        val safe = safeFileName(accountName).ifEmpty { "account" }
        return "jinlin-records-$safe-$stamp.json"
    }
    return "jinlin-records-all-$stamp.json"
}
