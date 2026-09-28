package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.R
import com.jinlin.gacha.assistant.network.UpdateCenter
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 公告子页（§15 §4.3⑤）—— 「关于 → 公告」的永久入口，列出**全部**可见公告（不只未读），
 * 服务端顺序（pinned 优先、ts 降序）展示。给公告一个「看历史」的入口 —— PC 没有，安卓成本很低。
 *
 * 数据来自 [UpdateCenter]（会话内存）；本次会话还没检查过就先拉一次（手动路径，不受启动闩锁限制）。
 * 失败静默 → 空态「暂无公告」（不弹错，对齐 §5 降级契约）。
 */
@Composable
internal fun AnnouncementListScreen(
    colors: JinlinColors,
    onBack: () -> Unit,
) {
    val center = UpdateCenter.get(LocalContext.current)
    val announcements by center.announcements.collectAsState()
    val checked by center.checked.collectAsState()

    // 会话内还没检查过（用户没经过启动流程，如进程被杀后直达本页）→ 先拉一次
    LaunchedEffect(Unit) {
        if (!checked) center.checkNow()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.health_back)) }
                Text(
                    text = stringResource(R.string.ann_list_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
            }
        }
        if (announcements.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.ann_list_empty),
                    fontSize = 13.sp,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        items(announcements.size) { index ->
            val ann = announcements[index]
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                if (ann.type == "pinned") R.string.ann_type_pinned
                                else R.string.ann_type_update
                            ),
                            fontSize = 11.sp,
                            color = if (ann.type == "pinned") colors.gold else colors.onSurfaceMuted,
                        )
                        Text(
                            text = formatAnnouncementTs(ann.ts),
                            fontSize = 11.sp,
                            color = colors.onSurfaceMuted,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                    Text(
                        text = ann.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                    MiniMarkdownText(text = ann.content, colors = colors)
                }
            }
        }
    }
}

/** 公告时间戳 → 本地日期文本（公告只有 ts，展示到日即可）。 */
private fun formatAnnouncementTs(ts: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
