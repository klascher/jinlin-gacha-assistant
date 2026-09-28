package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.network.UpdateInfo
import com.jinlin.gacha.assistant.ui.theme.ColorWarpedRed
import com.jinlin.gacha.assistant.ui.theme.JinlinColors

/** [UpdateDialog] 的模式（§15 §4.3②；语义照搬 PC `update_dialog.py`）。 */
internal enum class UpdateDialogMode {
    /** 提示不强制：可关、「去下载」+「稍后」，两者都记 acknowledged（同版本不再弹）。 */
    Prompt,

    /** 强制：不可关（返回键/点外部均不关，PC `announcement_dialog.py` 拦截腔调）、仅「去下载」。 */
    Force,
}

/**
 * 官网安卓下载页（§3-E：用户自行去网页下载 APK，端内不自装）。
 * 写法对齐 `SettingsScreen.kt` 的 `FAQ_URL`——**不写主站根地址**。
 */
internal const val DOWNLOAD_URL = "https://github.com/klascher/jinlin-gacha-assistant/releases"

/**
 * 更新弹窗（§15 §4.3②）—— 标题「发现新版本」+ 版本对照 + 更新说明（极简 Markdown）+ 按钮。
 *
 * ### ready=false（APK 还没传上 OSS）
 * 按钮变「新版准备中」置灰 + 文案说明稍后重试。调用方（[UpdateGate] 决策层）已保证
 * **force + ready=false 会降级为 prompt 行为**（不拦抓包）——本弹窗只负责把「下不到」说清楚，
 * 死锁守卫不在 UI 层。
 *
 * ### 关闭语义
 * - `Prompt`：点「去下载」/「稍后」都由调用方记 `acknowledged_version`（同版本不再弹）；
 *   点外部/返回键 = 「稍后」同义（可关）。
 * - `Force`：不可关，唯一出口「去下载」；调用方随之禁用「开始抓包」（记录/统计/导出/设置保留）。
 */
@Composable
internal fun UpdateDialog(
    info: UpdateInfo,
    mode: UpdateDialogMode,
    currentVersion: String,
    colors: JinlinColors,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    val force = mode == UpdateDialogMode.Force
    Dialog(
        onDismissRequest = { if (!force) onLater() },
        properties = DialogProperties(
            dismissOnBackPress = !force,
            dismissOnClickOutside = !force,
        ),
    ) {
        Surface(
            shape = androidx.compose.material3.MaterialTheme.shapes.large,
            color = colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.update_dialog_version, currentVersion, info.latestVersion),
                    fontSize = 14.sp,
                    color = colors.onSurfaceDim,
                )
                if (force) {
                    Text(
                        text = stringResource(R.string.update_dialog_force_hint),
                        fontSize = 12.sp,
                        color = ColorWarpedRed,
                    )
                }
                // 更新说明（极简 Markdown 白名单；内容超出内部滚动）
                if (info.changelog.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MiniMarkdownText(text = info.changelog, colors = colors)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    if (!force) {
                        OutlinedButton(onClick = onLater) {
                            Text(stringResource(R.string.update_dialog_later))
                        }
                    }
                    Button(
                        enabled = info.ready,
                        onClick = onDownload,
                    ) {
                        Text(
                            if (info.ready) stringResource(R.string.update_dialog_download)
                            else stringResource(R.string.update_dialog_not_ready)
                        )
                    }
                }
                if (!info.ready) {
                    Text(
                        text = stringResource(R.string.update_dialog_not_ready_hint),
                        fontSize = 12.sp,
                        color = colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}
