package com.jinlin.gacha.assistant.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.core.ChannelSwitchGuard
import com.jinlin.gacha.assistant.core.VersionCompare
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.locator.Endpoint
import com.jinlin.gacha.assistant.network.MetadataClient
import com.jinlin.gacha.assistant.network.MetaErrorReason
import com.jinlin.gacha.assistant.network.MetadataException
import com.jinlin.gacha.assistant.network.UpdateCenter
import com.jinlin.gacha.assistant.network.metadataFetchedAtNow
import com.jinlin.gacha.assistant.network.UpdateInfo
import com.jinlin.gacha.assistant.persistence.AppLog
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.SettingsStore
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import com.jinlin.gacha.assistant.vpn.TargetPackages
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（Tab 4）—— App 的**控制面**。
 *
 * ### 版面（2026-09-14 修订，5 组）
 * 抓包 / 数据互通 / 元数据 / 数据维护 / 关于 + 页脚。
 * 依据 `mobile/docs/09-设置模块设计.md` §2（重点 §2.1 M1~M3 与 §12 的版面修订）：
 * - **账号组已整体移出**至统计页「用户管理」组（09 §12.1）——本页不再有账号相关任何 UI；
 * - **「未映射 ID」行已移出**，只在抓包页保留（09 §12.2）——「数据维护」组只剩「清理本地数据」，
 *   该行于 **2026-09-17 落地可点**（进 [DataCleanupScreen] 子页，即 U4；**抓包中置灰**）；
 * - 抓包组按 M1 删「网卡」、按 M3 用「录包 & 诊断文件管理」口径。
 *   **「异常自动落盘」开关已于 2026-09-20 落地**（U2 / 09 §7 S4·T8）：`DiagnoseDumper` 的开关已由
 *   常量改为可写属性，本页渲染为 `Switch`（抓包中可拨但**只下次生效**，见 G2）；
 *   **「App 日志」开关已于 2026-09-22 落地**（**新增的独立开关**，与上面那条互不影响）：
 *   开 = 会话之外也把 App 事件写进 `applog/app-<yyyyMMdd>.txt`（保留 7 天，见 [AppLog]）；
 *   关 = 停止写。**即改即生效** —— 它改的是纯写盘开关，不是运行中的 dumper。
 *   **「录包 & 诊断文件」入口仍属 S4/T10、尚未落地**。原先本行写「依赖 `FileProvider`」——
 *   ⚠️ **该前置已于 2026-09-21 随 U1 导出落地**（`res/xml/file_paths.xml` + Manifest 的
 *   `${applicationId}.fileprovider`），S4 届时只需往白名单里**扩路径**，不必再搭 Provider。
 * - **数据互通组已于 2026-09-21 落地**（U1）：两行分别进导出对话框（[SyncExportDialog]）与
 *   导入三步子页（[SyncImportScreen]）；**抓包中整组置灰**（契约 §6.3）。
 *
 * ### 交互契约（对齐 PC「即改即存即生效」）
 * 改动经 [SettingsStore] 落盘并即时反映到 UI，**无保存按钮**；涉及服务行为的项在**下次开始抓包**
 * 时生效（09 §5 G2）。本页除元数据拉取（S3b）、「接管范围」（2026-09-18 起可点，见
 * [TargetPackagesDialog]）、**「异常自动落盘」开关（U2，2026-09-20 起可拨）**、**「App 日志」开关（2026-09-22 起可拨）**、
 * 「清理本地数据」
 * 与**「数据互通」两行（U1，2026-09-21 起可点）**外，均为只读项。
 */

/** 常见问题页（与 PC `branding.py` 的 [FAQ_URL] 同源）。 */
private const val FAQ_URL = "https://github.com/klascher/jinlin-gacha-assistant/issues"

/**
 * 设置页（Tab 4）入口 —— **只负责「主体 / 数据清理 / 导入」三页的切换**。
 *
 * **不引 NavHost**：`JinlinApp` 是「扁平 Tab 无返回栈」约定（见其类注释），用
 * `rememberSaveable` 布尔 + 分支即可 —— 不新增依赖，也不引入返回栈语义。
 * 两个子页互斥：只能从主体进入，故不必担心「同时为 true」。
 */
@Composable
fun SettingsScreen() {
    val colors = LocalJinlinColors.current
    var showCleanup by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showAnnouncements by rememberSaveable { mutableStateOf(false) }
    when {
        showCleanup -> DataCleanupScreen(colors = colors, onBack = { showCleanup = false })
        // U1 导入是**三步子页**（选文件 → 方式/目标 → 预览 → 结果），故走子页而非对话框
        showImport -> SyncImportScreen(colors = colors, onBack = { showImport = false })
        // 公告子页（§15 §4.3⑤）：列出全部可见公告（不只未读），给公告一个「看历史」入口
        showAnnouncements -> AnnouncementListScreen(colors = colors, onBack = { showAnnouncements = false })
        else -> SettingsContent(
            onOpenCleanup = { showCleanup = true },
            onOpenImport = { showImport = true },
            onOpenAnnouncements = { showAnnouncements = true },
        )
    }
}

/**
 * 设置页主体（版面与交互不变）。
 *
 * @param onOpenCleanup 「数据维护 → 清理本地数据」的落地回调。2026-09-17 起该行可点（进
 *   [DataCleanupScreen] 子页），此前是 `enabled = false` 的「开发中」占位（U4）。
 * @param onOpenImport 「数据互通 → 导入记录」的落地回调（2026-09-21 U1 落地，进
 *   [SyncImportScreen] 子页）。导出侧走**对话框**（[SyncExportDialog]），不占子页。
 */
@Composable
private fun SettingsContent(
    onOpenCleanup: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenAnnouncements: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalJinlinColors.current
    val settingsStore = remember { SettingsStore.get(context) }
    val settings by settingsStore.state.collectAsState()
    val svc by GachaVpnService.state.collectAsState()
    val endpoints by GachaVpnService.endpoints.collectAsState()
    val (versionName, versionCode) = remember { appVersion(context) }

    // —— 元数据手动拉取状态（S3b）——
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    // 数据版本初显：联网拉取前，回退显示出厂缓存里的版本（「无缓存才显示未拉取」）。
    val seededVersion = remember { readCachedMetaVersion(context) }

    // 「接管范围」弹窗开关（渠道服支持，2026-09-18）。不引 NavHost：弹窗无返回栈语义。
    var showTargets by remember { mutableStateOf(false) }

    // 「导出记录」对话框开关（U1，2026-09-21）。导入侧是子页（见 [SettingsScreen]），不在这里。
    var showExport by remember { mutableStateOf(false) }

    // —— 手动检查更新（§15 §4.3④）：行内文案 + **发现新版弹引导弹窗** ——
    // 2026-09-28 真机反馈：原版只在行尾换一句「发现新版本 x.y.z」，用户不知道下一步去哪。
    var updateCheckState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var foundUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // —— 抓包（只读项）——
        item { GroupHeader(stringResource(R.string.settings_section_capture), colors) }
        item {
            SectionCard(colors) {
                InfoRow(
                    label = stringResource(R.string.settings_capture_status),
                    value = captureStatusText(svc.running, svc.recording),
                    colors = colors,
                )
                RowDivider(colors)
                // 接管范围（2026-09-18 起可点）：官服 com.bmystu.peng.gw 与渠道服
                // （如 OPPO com.bmystu.peng1.nearme.gamecenter）包名不同，写死一个 ⇒
                // 另一半玩家一律被判「游戏未安装」。改为列出本机检出的候选让用户勾选。
                // 抓包中置灰：白名单在 establish() 时定型，跑着改会「以为换了其实没换」。
                ActionRow(
                    label = stringResource(R.string.settings_capture_scope),
                    trailing = when {
                        svc.running -> stringResource(R.string.account_need_stop)
                        settings.targetPackage == null ->
                            stringResource(R.string.settings_scope_auto) + " ▸"
                        else -> stringResource(
                            R.string.settings_scope_selected,
                            TargetPackages.channelLabel(settings.targetPackage.orEmpty()),
                        ) + " ▸"
                    },
                    enabled = !svc.running,
                    colors = colors,
                    onClick = { showTargets = true },
                )
                RowDivider(colors)
                // U2：异常自动落盘开关（09 §3.2 抓包组；落在「接管范围」之后、「已知端点」之前）。
                // 语义 = 只管「异常自动触发」，手动「抓诊断」恒落盘 ⇒ 副行写明，避免误解。
                SwitchRow(
                    label = stringResource(R.string.settings_auto_diagnose),
                    sub = stringResource(R.string.settings_auto_diagnose_hint),
                    checked = settings.autoDiagnose,
                    enabled = true,
                    colors = colors,
                    onCheckedChange = { on ->
                        settingsStore.setAutoDiagnose(on)
                        // 抓包中改 → 只影响下一场（G2「不热改运行中的 dumper」）：给一次明确反馈，
                        // 否则用户会以为「拨了没反应」。设计原文：「提示『下次开始抓包生效』」。
                        if (svc.running) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_auto_diagnose_next_effect),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                RowDivider(colors)
                // App 级日志开关（2026-09-22 新增）。与上面那条的分工：上面只管「抓包过程中的异常
                // 要不要自动落盘」，本条只管「App 日志文件写不写」—— **会话之外也写**（开抓包之前 /
                // 停抓之后都有）。故**即改即生效**，不弹「下次开始抓包生效」那句提示。
                SwitchRow(
                    label = stringResource(R.string.settings_app_log),
                    sub = stringResource(R.string.settings_app_log_hint, AppLog.KEEP_DAYS),
                    checked = settings.appLogEnabled,
                    enabled = true,
                    colors = colors,
                    onCheckedChange = { on ->
                        // 两行缺一不可：先落设置（真相源），再驱动落盘器（开→马上写；关→写线程排空后停）
                        settingsStore.setAppLogEnabled(on)
                        AppLog.setEnabled(context, on)
                    },
                )
                RowDivider(colors)
                InfoRow(
                    label = stringResource(R.string.settings_known_endpoints),
                    value = endpointsText(context, endpoints),
                    colors = colors,
                )
            }
        }

        // —— 数据互通（U1，2026-09-21 落地）——
        // 导出 = 对话框；导入 = 三步子页。**抓包中整组置灰**（§6.3：写侧唯一性靠「抓包中不写」
        // 维持，导入要落盘 ⇒ 必须在抓包中禁用；导出是只读，但既然同组一起走同一约定更不易记错）。
        item { GroupHeader(stringResource(R.string.settings_section_sync), colors) }
        item {
            SectionCard(colors) {
                ActionRow(
                    label = stringResource(R.string.settings_export),
                    trailing = if (svc.running) stringResource(R.string.account_need_stop) else "▸",
                    enabled = !svc.running,
                    colors = colors,
                    onClick = { showExport = true },
                )
                RowDivider(colors)
                ActionRow(
                    label = stringResource(R.string.settings_import),
                    trailing = if (svc.running) stringResource(R.string.account_need_stop) else "▸",
                    enabled = !svc.running,
                    colors = colors,
                    onClick = onOpenImport,
                )
            }
        }

        // —— 元数据（S3b：手动拉取已接线；版本/时间为只读，拉取失败红字可重试）——
        item { GroupHeader(stringResource(R.string.settings_section_metadata), colors) }
        item {
            SectionCard(colors) {
                InfoRow(
                    label = stringResource(R.string.settings_metadata_version),
                    value = settings.metadataVersion.ifEmpty {
                        seededVersion.ifEmpty {
                            stringResource(R.string.settings_not_fetched)
                        }
                    },
                    colors = colors,
                )
                RowDivider(colors)
                InfoRow(
                    label = stringResource(R.string.settings_metadata_fetched),
                    value = settings.metadataFetchedAt.ifEmpty {
                        stringResource(R.string.settings_not_fetched)
                    },
                    colors = colors,
                )
                RowDivider(colors)
                ActionRow(
                    label = stringResource(R.string.settings_metadata_fetch),
                    trailing = if (fetching) stringResource(R.string.settings_metadata_fetching) else "▸",
                    enabled = !fetching,
                    colors = colors,
                    onClick = {
                        if (fetching) return@ActionRow
                        fetchError = null
                        fetching = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                MetadataClient.fetch(MetaLoader.cacheFile(context.filesDir))
                            }
                            fetching = false
                            result.fold(
                                onSuccess = { meta ->
                                    val version = meta.version.ifEmpty { "?" }
                                    SettingsStore.get(context)
                                        .setMetadata(version, metadataFetchedAtNow())
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_metadata_updated, version),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = { e ->
                                    fetchError = metadataErrorText(context, e)
                                },
                            )
                        }
                    },
                )
                fetchError?.let { err ->
                    Text(
                        text = err,
                        color = ColorWarpedRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
            }
        }

        // —— 数据维护（「清理本地数据」2026-09-17 起落地为 DataCleanupScreen；「未映射 ID」行已移出，只在抓包页保留）——
        item { GroupHeader(stringResource(R.string.settings_section_maintenance), colors) }
        item {
            SectionCard(colors) {
                // 抓包中入口即置灰（整页不可用）：清理会删 pcap，而两类 pcap 正被写线程持有句柄；
                // 状态用 StateFlow **跟随**而非「进入时取一次」—— 抓包可由通知栏起停，采样会漏。
                ActionRow(
                    label = stringResource(R.string.settings_clear_data),
                    trailing = if (svc.running) {
                        stringResource(R.string.account_need_stop)
                    } else {
                        "▸"
                    },
                    enabled = !svc.running,
                    colors = colors,
                    onClick = onOpenCleanup,
                )
            }
        }

        // —— 关于（§15 §4.3④：2 行扩到 4 行 —— 应用版本 / 公告 / 检查更新 / 常见问题）——
        item { GroupHeader(stringResource(R.string.settings_section_about), colors) }
        item {
            SectionCard(colors) {
                InfoRow(
                    label = stringResource(R.string.settings_app_version),
                    value = stringResource(
                        R.string.settings_version_value,
                        versionName,
                        versionCode,
                    ),
                    colors = colors,
                )
                RowDivider(colors)
                ActionRow(
                    label = stringResource(R.string.settings_announcements),
                    trailing = "▸",
                    enabled = true,
                    colors = colors,
                    onClick = onOpenAnnouncements,
                )
                RowDivider(colors)
                ActionRow(
                    label = stringResource(R.string.settings_check_update),
                    trailing = when (val st = updateCheckState) {
                        UpdateCheckState.Idle -> "▸"
                        UpdateCheckState.Checking ->
                            stringResource(R.string.update_checking)
                        is UpdateCheckState.Latest ->
                            stringResource(R.string.update_check_latest)
                        is UpdateCheckState.Found ->
                            stringResource(R.string.update_check_found, st.version)
                        UpdateCheckState.Failed ->
                            stringResource(R.string.update_check_failed)
                    },
                    enabled = updateCheckState != UpdateCheckState.Checking,
                    colors = colors,
                    onClick = {
                        if (updateCheckState == UpdateCheckState.Checking) return@ActionRow
                        updateCheckState = UpdateCheckState.Checking
                        scope.launch {
                            // 复用会话数据源：手动检查结果同时刷新横幅/弹窗/子页的数据（§5 手动不限频）
                            UpdateCenter.get(context).checkNow()
                            val info = UpdateCenter.get(context).updateInfo.value
                            updateCheckState = when {
                                info == null -> UpdateCheckState.Failed
                                // 直接比版本号（不用 UpdateGate）：手动检查要连 silent 的新版也报出来
                                //（§3-C：silent 下「仅设置页检查更新能看到」）
                                VersionCompare.isNewer(info.latestVersion, versionName) ->
                                    UpdateCheckState.Found(info.latestVersion)
                                else -> UpdateCheckState.Latest
                            }
                            // 发现新版：弹更新弹窗引导去官网下载（「稍后」只关弹窗、**不记
                            // acknowledged** —— 不影响下次启动的正常提示；「去下载」照记）
                            if (updateCheckState is UpdateCheckState.Found) foundUpdateInfo = info
                        }
                    },
                )
                RowDivider(colors)
                ActionRow(
                    label = stringResource(R.string.settings_faq),
                    trailing = "▸",
                    enabled = true,
                    colors = colors,
                    onClick = { openUrl(context, FAQ_URL) },
                )
            }
        }

        // —— 页脚（仅赞助一行；09 §3.6 无第二行说明）——
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_footer_sponsor),
                    fontSize = 12.sp,
                    color = colors.onSurfaceMuted,
                )
            }
        }
    }

    // 「接管范围」弹窗（渠道服支持，2026-09-18）：列出本机检出的目标游戏候选供**单选**。
    // 保存走 SettingsStore（即改即存），下次**开始抓包**时生效（G2：服务行为改动不入运行中会话）。
    if (showTargets) {
        TargetPackagesDialog(
            colors = colors,
            selected = settings.targetPackage,
            running = svc.running,
            onDismiss = { showTargets = false },
            onConfirm = { picked ->
                val previous = settings.targetPackage
                settingsStore.setTargetPackage(picked)
                showTargets = false
                // 换渠道**软提示**（渠道服支持，2026-09-18）：此处只提示不拦（用户还在设置页，
                // 提醒到位即可）；真正的硬确认在抓包页点「开始抓包」时（那一刻才会真的混数据）。
                // 判定只报「换成了一个该用户没抓过的渠道」——同渠道/新用户都不打扰。
                val others = if (previous == picked) {
                    emptyList()
                } else {
                    runCatching {
                        val store = HistoryStore(
                            File(context.filesDir, ProfileStore.USERS_DIR),
                            ProfileStore.get(context).state.value.activeId,
                        )
                        store.load()
                        ChannelSwitchGuard.evaluate(picked, store.sourcePackages).otherChannels
                    }.getOrDefault(emptyList())
                }
                val msg = if (others.isEmpty()) {
                    context.getString(R.string.target_packages_saved)
                } else {
                    context.getString(
                        R.string.channel_switch_saved_warn,
                        others.joinToString("、") { TargetPackages.channelLabel(it) },
                    )
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            },
        )
    }

    // 「导出记录」对话框（U1，2026-09-21）：选范围 → 落 `filesDir/exports/` → 系统分享。
    // 只读动作：读设备快照 + 写**导出目录**，不碰任何账号历史。
    if (showExport) {
        SyncExportDialog(colors = colors, onDismiss = { showExport = false })
    }

    // 「发现新版本」引导弹窗（2026-09-28 真机反馈）：手动检查发现新版后弹出，
    // 「去下载」开官网安卓下载页。prompt 模式可关；「稍后」**不记** acknowledged。
    foundUpdateInfo?.let { info ->
        UpdateDialog(
            info = info,
            mode = UpdateDialogMode.Prompt,
            currentVersion = versionName,
            colors = colors,
            onDownload = {
                SettingsStore.get(context).setAcknowledgedVersion(info.latestVersion)
                openUrl(context, DOWNLOAD_URL)
                foundUpdateInfo = null
            },
            onLater = { foundUpdateInfo = null },
        )
    }
}

// —— 派生文案 / 平台信息 ——

/** 「检查更新」行内状态（§15 §4.3④；2026-09-28 起 Found 额外弹引导弹窗去官网下载）。 */
private sealed interface UpdateCheckState {
    /** 未检查 / 上次结果已无关（进页默认）。 */
    data object Idle : UpdateCheckState

    /** 检查中（行置灰防连点）。 */
    data object Checking : UpdateCheckState

    /** 已是最新（含 silent 之外的版本追平）。 */
    data object Latest : UpdateCheckState

    /** 发现新版本（含 silent —— 手动检查要连静默新版也报出来，§3-C）。 */
    data class Found(val version: String) : UpdateCheckState

    /** 检查不到（网络 / 服务未就绪；静默契约的表现形式 = 行内一句话，不弹错）。 */
    data object Failed : UpdateCheckState
}

@Composable
private fun captureStatusText(running: Boolean, recording: Boolean): String = when {
    recording -> stringResource(R.string.settings_status_recording)
    running -> stringResource(R.string.settings_status_running)
    else -> stringResource(R.string.settings_status_idle)
}

/** 已确认端点展示；无则「正在识别…」（Q3：端点不持久化，冷启动后需重新抓包识别）。 */
private fun endpointsText(context: Context, endpoints: List<Endpoint>): String {
    if (endpoints.isEmpty()) return context.getString(R.string.settings_endpoints_identifying)
    return endpoints.joinToString("、") {
        context.getString(R.string.settings_endpoint_confirmed, "${it.ip}:${it.port}")
    }
}

/**
 * App 自身版本（`versionName` + `versionCode`）；取不到时回 "?" / 0。
 *
 * `internal`（非 private）：U1 导出侧（[SyncExportDialog]）要用 `versionName` 填信封的
 * `source.app_version`，同包共用一份实现、不复制第二份。
 */
internal fun appVersion(context: Context): Pair<String, Long> = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName.orEmpty() to info.longVersionCode
}.getOrDefault("?" to 0L)

// —— S3b 元数据拉取辅助 ——

/**
 * 用系统浏览器打开外部链接；无匹配应用时 Toast 提示而不崩。
 * `internal`（非 private）：§15 的公告/更新链接渲染（[MarkdownText]）与「去下载」共用一份实现。
 */
internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_url_no_handler, Toast.LENGTH_SHORT).show()
    }
}

/** 联网前数据显示：读缓存里的版本（出厂种子或上次拉取）；无缓存时空串。 */
private fun readCachedMetaVersion(context: Context): String =
    MetaLoader.read(MetaLoader.cacheFile(context.filesDir))?.version.orEmpty()

/** 把 [MetadataException] 的 reason 映射成用户文案（对齐 11 §3 失败分层）。 */
private fun metadataErrorText(context: Context, e: Throwable): String {
    val mex = e as? MetadataException
    return when (mex?.reason) {
        MetaErrorReason.NETWORK -> context.getString(R.string.metadata_fetch_err_network)
        MetaErrorReason.AUTH -> context.getString(R.string.metadata_fetch_err_auth)
        MetaErrorReason.NOT_READY -> context.getString(R.string.metadata_fetch_err_not_ready)
        MetaErrorReason.FORMAT -> context.getString(R.string.metadata_fetch_err_format)
        MetaErrorReason.HTTP -> context.getString(R.string.metadata_fetch_err_http, mex.httpCode ?: 0)
        null -> context.getString(R.string.metadata_fetch_err_unknown)
    }
}
