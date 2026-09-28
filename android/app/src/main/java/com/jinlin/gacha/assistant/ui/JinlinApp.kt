package com.jinlin.gacha.assistant.ui

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Scaffold
import com.jinlin.gacha.assistant.core.UpdateGate
import com.jinlin.gacha.assistant.network.UpdateCenter
import com.jinlin.gacha.assistant.persistence.SettingsStore
import com.jinlin.gacha.assistant.ui.capture.CaptureScreen
import com.jinlin.gacha.assistant.ui.screens.AnnouncementDialog
import com.jinlin.gacha.assistant.ui.screens.RecordsScreen
import com.jinlin.gacha.assistant.ui.screens.StatsScreen
import com.jinlin.gacha.assistant.ui.screens.SettingsScreen
import com.jinlin.gacha.assistant.ui.screens.UpdateDialog
import com.jinlin.gacha.assistant.ui.screens.UpdateDialogMode
import com.jinlin.gacha.assistant.ui.screens.DOWNLOAD_URL
import com.jinlin.gacha.assistant.ui.screens.appVersion
import com.jinlin.gacha.assistant.ui.screens.openUrl
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors

/**
 * App 根脚手架：单 MainActivity 内底部 4 Tab（对齐 08-页面设计.md §2）。
 * 扁平 Tab 无返回栈需求，用「选中态 + when」切换，不引 navigation-compose（省依赖）。
 */
enum class MainTab(val label: String, val glyph: String) {
    Capture("抓包", "◎"),
    Records("记录", "◫"),
    Stats("统计", "◍"),
    Settings("设置", "⚙"),
}

@Composable
fun JinlinApp() {
    val colors = LocalJinlinColors.current
    var tab by rememberSaveable { mutableStateOf(MainTab.Capture) }

    // 启动更新与公告流程（§15 §4.4）：首帧渲染后触发、先公告后更新，见 [StartupUpdateFlow]。
    StartupUpdateFlow()

    Scaffold(
        containerColor = colors.bg,
        bottomBar = {
            NavigationBar(
                containerColor = colors.surface,
                contentColor = colors.onSurface,
            ) {
                MainTab.entries.forEach { t ->
                    val selected = tab == t
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = {
                            Text(
                                text = t.glyph,
                                color = if (selected) colors.gold else colors.onSurfaceDim,
                            )
                        },
                        label = {
                            Text(
                                text = t.label,
                                color = if (selected) colors.gold else colors.onSurfaceDim,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                MainTab.Capture -> CaptureScreen()
                MainTab.Records -> RecordsScreen()
                MainTab.Stats -> StatsScreen()
                MainTab.Settings -> SettingsScreen()
            }
        }
    }
}

/**
 * 启动更新与公告流程（§15 §4.3③/§4.4）—— 对齐 PC `__main__.py:307-341` 的启动时序：
 *
 * - **触发时机**：`LaunchedEffect(Unit)`（首帧渲染后协程才跑，PC `QTimer.singleShot(0)` 同理）；
 * - **进程内一次**：闩锁在 [UpdateCenter]（CAS，`rememberSaveable` 都不必 —— 单例本身就是会话态），
 *   切 Tab / 旋转不会重入；每次启动（进程冷启）都重新拉取，结果仅本次会话内存有效（§5）；
 * - **先公告、后更新**：公告弹窗全部走完（含无可弹公告）才进更新段，两段不叠窗（PC 同序）；
 * - **检查结果的应用**照 PC `_maybe_check_update`：`force` 不可关弹窗 + 抓包页禁「开始抓包」；
 *   `prompt` 仅 `latest != acknowledged` 才弹（两个按钮都记 acknowledged，记完弹窗自然消失）；
 *   `silent` / 无新版什么都不做（设置页「检查更新」仍可手动看）。
 */
@Composable
private fun StartupUpdateFlow() {
    val context = LocalContext.current
    val colors = LocalJinlinColors.current
    val center = remember { UpdateCenter.get(context) }
    val settings by SettingsStore.get(context).state.collectAsState()
    val checked by center.checked.collectAsState()
    val announcements by center.announcements.collectAsState()
    val updateInfo by center.updateInfo.collectAsState()
    // 公告段闩锁：公告弹窗关闭（或无可弹）后置 true，更新段才开演（避免两窗同屏叠放）
    var announcementsStageDone by remember { mutableStateOf(false) }

    // 首帧后启动检查（进程内一次，闩锁见 UpdateCenter.beginStartupCheck）
    LaunchedEffect(Unit) {
        if (center.beginStartupCheck()) center.checkNow()
    }

    // —— 第一段：未读公告弹窗（pinned 强制确认 + update 可「不再提示」）——
    if (checked && !announcementsStageDone) {
        val unread = remember(announcements) { center.unread() }
        if (unread.isEmpty()) {
            // 没有未读：直接放行更新段（composition 期间不改状态，用 LaunchedEffect）
            LaunchedEffect(announcements) { announcementsStageDone = true }
        } else {
            AnnouncementDialog(
                items = unread,
                colors = colors,
                onClose = { ids ->
                    center.markRead(ids)
                    announcementsStageDone = true
                },
            )
        }
    }

    // —— 第二段：更新决策（PC `_maybe_check_update` 同款顺序）——
    val (versionName, _) = remember { appVersion(context) }
    val decision = remember(updateInfo, settings.acknowledgedVersion, versionName) {
        UpdateGate.decide(
            latestVersion = updateInfo?.latestVersion,
            currentVersion = versionName,
            updateType = updateInfo?.updateType,
            ready = updateInfo?.ready ?: false,
            acknowledgedVersion = settings.acknowledgedVersion,
        )
    }
    if (checked && announcementsStageDone) {
        when (decision) {
            // force：不可关弹窗（ready 已由 UpdateGate 保证 true）；记 acknowledged 不适用 ——
            // PC force 弹窗每次启动都弹，唯一出口「去下载」
            is UpdateGate.Decision.ForceBlocking -> {
                updateInfo?.let { info ->
                    UpdateDialog(
                        info = info,
                        mode = UpdateDialogMode.Force,
                        currentVersion = versionName,
                        colors = colors,
                        onDownload = { openUrl(context, DOWNLOAD_URL) },
                        onLater = {},
                    )
                }
            }
            // prompt（含 force+ready=false 降级）：latest != acknowledged 才弹；
            // 两个按钮都记 acknowledged ⇒ decision 重算为 showDialog=false，弹窗自然消失
            is UpdateGate.Decision.Prompt -> {
                val prompt = decision as UpdateGate.Decision.Prompt
                val info = updateInfo
                if (prompt.showDialog && info != null) {
                    UpdateDialog(
                        info = info,
                        mode = UpdateDialogMode.Prompt,
                        currentVersion = versionName,
                        colors = colors,
                        onDownload = {
                            SettingsStore.get(context).setAcknowledgedVersion(info.latestVersion)
                            openUrl(context, DOWNLOAD_URL)
                        },
                        onLater = {
                            SettingsStore.get(context).setAcknowledgedVersion(info.latestVersion)
                        },
                    )
                }
            }
            // UpToDate / Silent：什么都不做（设置页「检查更新」可见）
            else -> {}
        }
    }
}
