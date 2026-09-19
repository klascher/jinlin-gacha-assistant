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
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.locator.Endpoint
import com.jinlin.gacha.assistant.network.MetadataClient
import com.jinlin.gacha.assistant.network.MetaErrorReason
import com.jinlin.gacha.assistant.network.MetadataException
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.SettingsStore
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import com.jinlin.gacha.assistant.vpn.TargetPackages
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
 * - 抓包组按 M1 删「网卡」、按 M3 用「录包 & 诊断文件管理」口径（**该两项与「异常自动落盘」开关
 *   同属 S4，尚未落地**：开关需把 `DiagnoseDumper.AUTO_TRIGGER_ENABLED` 由常量改为可写属性，
 *   属 09 §7 分期表的 S4/T8，本页因此暂不渲染，避免做出「能拨但无效」的假开关）。
 *
 * ### 交互契约（对齐 PC「即改即存即生效」）
 * 改动经 [SettingsStore] 落盘并即时反映到 UI，**无保存按钮**；涉及服务行为的项在**下次开始抓包**
 * 时生效（09 §5 G2）。本页除元数据拉取（S3b）、「接管范围」（2026-09-18 起可点，见
 * [TargetPackagesDialog]）与「清理本地数据」外，均为只读项。
 */

/** 常见问题页（与 PC `branding.py` 的 [FAQ_URL] 同源）。 */
private const val FAQ_URL = "https://github.com/klascher/jinlin-gacha-assistant/issues"

/**
 * 设置页（Tab 4）入口 —— **只负责「主体 / 数据清理子页」的切换**。
 *
 * **不引 NavHost**：`JinlinApp` 是「扁平 Tab 无返回栈」约定（见其类注释），用一个
 * `rememberSaveable` 布尔 + if/else 即可 —— 不新增依赖，也不引入返回栈语义。
 */
@Composable
fun SettingsScreen() {
    val colors = LocalJinlinColors.current
    var showCleanup by rememberSaveable { mutableStateOf(false) }
    if (showCleanup) {
        DataCleanupScreen(colors = colors, onBack = { showCleanup = false })
    } else {
        SettingsContent(onOpenCleanup = { showCleanup = true })
    }
}

/**
 * 设置页主体（版面与交互不变）。
 *
 * @param onOpenCleanup 「数据维护 → 清理本地数据」的落地回调。2026-09-17 起该行可点（进
 *   [DataCleanupScreen] 子页），此前是 `enabled = false` 的「开发中」占位（U4）。
 */
@Composable
private fun SettingsContent(onOpenCleanup: () -> Unit) {
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
                InfoRow(
                    label = stringResource(R.string.settings_known_endpoints),
                    value = endpointsText(context, endpoints),
                    colors = colors,
                )
            }
        }

        // —— 数据互通（S4 实现，本批置灰）——
        item { GroupHeader(stringResource(R.string.settings_section_sync), colors) }
        item {
            SectionCard(colors) {
                ActionRow(
                    label = stringResource(R.string.settings_export),
                    trailing = stringResource(R.string.settings_coming_soon),
                    enabled = false,
                    colors = colors,
                )
                RowDivider(colors)
                ActionRow(
                    label = stringResource(R.string.settings_import),
                    trailing = stringResource(R.string.settings_coming_soon),
                    enabled = false,
                    colors = colors,
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
                                        .setMetadata(version, fetchedAtNow())
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

        // —— 关于 ——
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
}

// —— 派生文案 / 平台信息 ——

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

/** App 自身版本（`versionName` + `versionCode`）；取不到时回 "?" / 0。 */
private fun appVersion(context: Context): Pair<String, Long> = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName.orEmpty() to info.longVersionCode
}.getOrDefault("?" to 0L)

// —— S3b 元数据拉取辅助 ——

/** 用系统浏览器打开外部链接；无匹配应用时 Toast 提示而不崩。 */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_url_no_handler, Toast.LENGTH_SHORT).show()
    }
}

/** 联网前数据显示：读缓存里的版本（出厂种子或上次拉取）；无缓存时空串。 */
private fun readCachedMetaVersion(context: Context): String =
    MetaLoader.read(MetaLoader.cacheFile(context.filesDir))?.version.orEmpty()

/** 「最后拉取」时间文本（本地时区，对齐现有展示风格）。 */
private fun fetchedAtNow(): String = LocalDateTime.now().format(FETCHED_AT_FMT)
private val FETCHED_AT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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
