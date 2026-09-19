package com.jinlin.gacha.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.jinlin.gacha.assistant.core.DedupSnapshot
import com.jinlin.gacha.assistant.core.GachaRecord
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.persistence.CaptureDetail
import com.jinlin.gacha.assistant.persistence.HistoryEpoch
import com.jinlin.gacha.assistant.persistence.HistoryStore
import com.jinlin.gacha.assistant.persistence.ProfileStore
import com.jinlin.gacha.assistant.persistence.RoleCacheSeed
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors
import com.jinlin.gacha.assistant.ui.theme.JinlinColors
import com.jinlin.gacha.assistant.vpn.GachaVpnService
import com.jinlin.gacha.assistant.vpn.ownsSession
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 属于**当前账号**的会话内存态（本次抓到的抽 / 去重快照 / 开始时刻）。
 * 见 [rememberOwnedSession] —— 这是取它的**唯一入口**。
 */
internal data class OwnedSession(
    val records: List<GachaRecord>,
    val dedup: DedupSnapshot,
    val startedAt: Long,
)

/**
 * 取「属于当前账号」的会话内存态；不属于（或本场已结束）时返回**空态**。
 *
 * ### 为什么需要这一层（2026-09-19 实机反馈的缺陷）
 * `GachaVpnService` 的 records / dedupState / sessionStartedAt 是**进程级单例**，语义上却属于
 * **某一个账号**，原先没有任何归属标记 ⇒ 切换账号后、**还没开始抓包**时，新账号在记录页会看到
 * 上一个账号的记录（新账号历史为空 ⇒ 并集的"历史去重"不起作用，旧会话记录整批显示），
 * 完整性卡也显示上一个账号的信息。
 *
 * ### 为什么是「消费侧过滤」而不是「切账号时清一次」
 * 改 `activeId` 的入口不止一处（切换 / 删除活动账号后重指 / 进程冷启），逐个补清理属于
 * 「漏一处就复发」；判据放在这里，任何改 `activeId` 的路径都自动覆盖。
 *
 * ⚠️ 消费侧**必须**走本函数，不要在别处直接 `GachaVpnService.records` —— 那样等于绕过归属校验。
 *
 * @param activeProfileId 当前账号 id（`ProfileStore` 的 activeId；空串 = 无账号）
 */
@Composable
internal fun rememberOwnedSession(activeProfileId: String): OwnedSession {
    val svc by GachaVpnService.state.collectAsState()
    val rawRecords by GachaVpnService.records.collectAsState()
    val rawDedup by GachaVpnService.dedupState.collectAsState()
    // 空态用 `DedupSnapshot()`：判据层会落到 ⚪「本场没抓到抽卡数据」——
    // 这正是「新账号 / 刚切过来还没抓」应有的样子（而不是继承别人的完整性信息）。
    val owned = svc.ownsSession(activeProfileId)
    return OwnedSession(
        records = if (owned) rawRecords else emptyList(),
        dedup = if (owned) rawDedup else DedupSnapshot(),
        startedAt = if (owned) svc.sessionStartedAt else 0L,
    )
}

/**
 * 记录页（Tab 2）—— 展示**账号全量历史 + 本次会话新增**，实时更新。
 *
 * 数据源两路并集：
 * - 历史 = `HistoryStore.records`（落库 json，按账号隔离；停抓时 `merge` 已把本次会话并进
 *   历史落盘，故这里天然含上一次会话——以 `running` 翻转触发重读最新磁盘）。
 * - 本次会话新增 = `GachaVpnService.records`（进程级 StateFlow，实时追加，每次「开始抓包」清空；
 *   自 2026-09-14 起这批已过去重）。
 * 两路按记录身份 `(pool_id,item_id,timestamp)` 去重，避免「停抓后历史已含会话」时重复计。
 *
 * 顶部的 [HealthCard] 是 U5（2026-09-18）的**主落点**：把八类去重信号压成三态一眼可见，
 * 整卡可点进 [HealthDetail] 子页看缺口明细与补救步骤。筛选/搜索/导出留 2.2。
 */
@Composable
fun RecordsScreen() {
    val context = LocalContext.current
    val colors = LocalJinlinColors.current
    val prof by ProfileStore.get(context).state.collectAsState()
    val svc by GachaVpnService.state.collectAsState()
    val running = svc.running
    // 会话内存态（本次新增记录 / 完整性快照 / 开始时刻）按**账号归属**取：切换账号后，
    // 新账号不得看到上一个账号的本场数据（2026-09-19 实机反馈）。不要在这里直接读
    // `GachaVpnService.records` —— 那等于绕过归属校验，见 `rememberOwnedSession`。
    val session = rememberOwnedSession(prof.activeId)
    // 角色/卡池 id → 展示名（元数据按账号缓存；未知回退「未知(id)」，同统计页口径）。
    val nameMaps = rememberNameMaps(context, prof.activeId)

    // 历史内容版本（清空 / 还原后 bump）。清空不改 activeId、也不启停抓包，若不算进 key，
    // 本页会一直显示被清掉之前的旧列表。
    val epoch by HistoryEpoch.state.collectAsState()

    // 历史（落库）：按账号；停抓时 merge 已落盘，故以 running 翻转触发重读，读到本次会话并后的样子。
    val history: List<GachaRecord> = remember(prof.activeId, running, epoch) {
        HistoryStore(File(context.filesDir, ProfileStore.USERS_DIR), prof.activeId).records
    }
    // 上一场明细（`last_capture_detail`）：**只**由停抓收尾那条 merge 写入（周期落盘不写），
    // 故它就是「上次抓包」的真相；与 history 同源、同刷新时机（账号 / running / epoch）。
    val lastCapture: CaptureDetail? = remember(prof.activeId, running, epoch) {
        HistoryStore(File(context.filesDir, ProfileStore.USERS_DIR), prof.activeId).captureDetail
    }
    val historyKeys: Set<Triple<String, String, Long>> = remember(history) {
        history.map { Triple(it.poolId, it.itemId, it.timestamp) }.toSet()
    }
    // 并集：本次会话新增（按身份过滤，去掉已并进历史的）+ 历史；均最新在前（会话 reverse / 历史本就 ts 倒序）。
    // ⚠️ `session.records` 已按账号过滤（见 `rememberOwnedSession`）——**这条并集依赖它**：
    // 若把别的账号的会话记录放进来，新账号历史为空 ⇒ 下面的身份去重不起作用 ⇒ 旧记录整批显示。
    val allRecords: List<GachaRecord> = buildList {
        addAll(session.records.filter { Triple(it.poolId, it.itemId, it.timestamp) !in historyKeys }.reversed())
        addAll(history)
    }
    // 顶栏可见拆分：已存历史 = 磁盘落盘部分；本次 = 并集中非历史部分（本次新抓、尚未并入历史）。
    val historyCount = history.size
    val sessionCount = allRecords.size - historyCount

    // 子页切换（U5 §17.8.2）：`rememberSaveable` 保住旋转屏 / 进程重建后的页内位置；
    // 本页只有一层下钻，故不引 NavHost（与设置页「数据清理」子页同一做法）。
    var showHealth by rememberSaveable { mutableStateOf(false) }
    if (showHealth) {
        HealthDetail(
            dedup = session.dedup,
            lastCapture = lastCapture,
            storedCount = historyCount,
            running = running,
            sessionStartedAt = session.startedAt,
            onBack = { showHealth = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "记录",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )

        // 三行 header（U5 §17.12.4）：总计 / 上次 / 本次。三行并列本身已消歧，故一律不写「本场」。
        RecordHeader(
            total = allRecords.size,
            lastCapture = lastCapture,
            sessionCount = sessionCount,
            running = running,
            sessionStartedAt = session.startedAt,
        )

        // 数据完整性卡（U5 主落点）：整卡可点进子页。它取代了原来的 DedupNotice 小字块 ——
        // 通知栏的「停止」按钮弹不出窗，这张卡是本场缺口的**最后留痕处**。
        HealthCard(session.dedup) { showHealth = true }

        if (allRecords.isEmpty()) {
            EmptyRecords(colors)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(allRecords) { index, rec ->
                    RecordRow(rec, index = index, names = nameMaps)
                }
            }
        }
    }
}

/** 空态：还没抓到抽时的引导（对齐 CaptureScreen 空态风格）。 */
@Composable
private fun EmptyRecords(colors: JinlinColors) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "暂无抽卡记录",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = "没有抽卡记录。开启抓包后在游戏抽卡记录页切到『全部卡池』列表再逐页翻到底，" +
                    "即可实时收录；本页会展示历史与本次新增的全部记录（池 / 角色 / 时间）。" +
                    "抓到的新记录在抓包停止（或抓取中周期落盘）时自动保存，重开 App 仍可见。",
                fontSize = 12.sp,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

/** 角色/卡池 id → 展示名映射（未知 id 不在表中时回退「未知(id)」，同统计页 `未知(…)` 口径）。 */
private data class NameMaps(
    val pool: Map<String, String>,
    val character: Map<String, String>,
)

/**
 * 按账号加载元数据名映射（镜像统计页 `statsReport` 的缓存口径：`RoleCacheSeed.ensureSeeded` →
 * `MetaLoader.read(cacheFile)`；`remember(activeId)` 账号切换即重读，重组不重复读盘）。
 *
 * 记录页原先直接展示 poolId/itemId（待办 8）——有元数据时显示角色/卡池名称，缺缓存回落
 * 成 id，别让整页因元数据缺失而空掉。
 */
@Composable
private fun rememberNameMaps(context: android.content.Context, activeId: String): NameMaps {
    return remember(activeId) {
        RoleCacheSeed.ensureSeeded(context)
        val meta = MetaLoader.read(MetaLoader.cacheFile(context.filesDir))
        NameMaps(
            pool = meta?.pools?.mapValues { (_, p) -> p.name } ?: emptyMap(),
            character = meta?.characters?.mapValues { (_, c) -> c.name } ?: emptyMap(),
        )
    }
}

/** 单条记录行：#全局序号 · `池名：[角色名]` · 时间；附「位序」（落库坐标 = pos − Δ，与历史同坐标系）。 */
@Composable
private fun RecordRow(rec: GachaRecord, index: Int, names: NameMaps) {
    val colors = LocalJinlinColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#${index + 1}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
                modifier = Modifier.width(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${names.pool[rec.poolId] ?: "未知(${rec.poolId})"}：[${names.character[rec.itemId] ?: "未知(${rec.itemId})"}]",
                    fontSize = 13.sp,
                    color = colors.onSurface,
                )
                Text(
                    text = "位序 #${rec.positionInBatch}",
                    fontSize = 11.sp,
                    color = colors.onSurfaceDim,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatTime(rec.timestamp),
                fontSize = 11.sp,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 记录页顶部三行 header（U5 `10-去重模块设计.md` §17.12.4）：**总计 / 上次 / 本次**。
 *
 * 三行并列本身就消歧，故一律**不写「本场」**（那个叫法只在设计稿内部用；面向用户一律「本次」）。
 *
 * - **总计** = 本页并集条数（历史 + 本次未落库新增）；有服务器权威 total 时二者应相等。
 * - **上次** = 上一场**已落库**抓包的起止 + 该场**新增**条数（**非累计** —— 否则与「总计」语义重叠）；
 *   从未停抓过 → 「暂无记录」。
 * - **本次** = 本场状态：抓包中给「开始时刻 + 已收条数」，未抓包给「未在抓包」。
 *
 * ⚠️ 起止时间**一律带月-日**（[formatCaptureRange]），刻意不写「今天」——本页长期停留，
 * 隔夜再看「今天」就错了。
 */
@Composable
private fun RecordHeader(
    total: Int,
    lastCapture: CaptureDetail?,
    sessionCount: Int,
    running: Boolean,
    sessionStartedAt: Long,
) {
    val colors = LocalJinlinColors.current
    val today = LocalDate.now()

    val lastStart = lastCapture?.startedAt?.let(::parseCaptureTime)
    val lastEnd = lastCapture?.endedAt?.let(::parseCaptureTime)
    val lastValue = if (lastCapture != null && lastStart != null && lastEnd != null) {
        stringResource(
            R.string.record_last_value,
            formatCaptureRange(lastStart, lastEnd),
            lastCapture.added,
        )
    } else {
        stringResource(R.string.record_last_none)
    }

    val nowValue = if (running) {
        val clock = if (sessionStartedAt > 0L) {
            formatClock(
                Instant.ofEpochMilli(sessionStartedAt).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                today,
            )
        } else "—"
        stringResource(R.string.record_now_value, clock, sessionCount)
    } else {
        stringResource(R.string.record_now_idle)
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HeaderLine(stringResource(R.string.record_label_total), stringResource(R.string.record_total_value, total), colors)
        HeaderLine(stringResource(R.string.record_label_last), lastValue, colors)
        HeaderLine(stringResource(R.string.record_label_now), nowValue, colors)
    }
}

/** 三行 header 的其中一行：左侧定宽标签 + 右侧值（定宽让三行的值左对齐成列）。 */
@Composable
private fun HeaderLine(label: String, value: String, colors: JinlinColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 11.sp, color = colors.onSurfaceDim, modifier = Modifier.width(36.dp))
        Text(text = value, fontSize = 12.sp, color = colors.onSurfaceMuted)
    }
}

/** 毫秒时间戳 → 本机时区 "yyyy-MM-dd HH:mm:ss"。 */
private fun formatTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(TIME_FMT)

// 统计页（Tab 3）已迁出到独立文件 StatsScreen.kt（2026-09-14：顶部「用户管理」组 + 统计内容占位）。
// 设置页（Tab 4）已迁出到独立文件 SettingsScreen.kt（S1 实施，见 mobile/docs/09-设置模块设计.md）。