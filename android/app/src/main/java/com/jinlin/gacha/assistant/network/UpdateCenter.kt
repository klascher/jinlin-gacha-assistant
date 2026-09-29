package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.core.Announcement
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import com.jinlin.gacha.assistant.persistence.AnnouncementReadStore
import com.jinlin.gacha.assistant.persistence.SettingsStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 更新与公告的**会话级状态持有者**（§15 §4.2 / §5）—— 进程级单例，UI 各处（横幅 / 弹窗 /
 * 设置页 / 公告子页）从这里取同一份数据。
 *
 * ### 数据生命周期（§5 请求频率行，2026-09-28 定案）
 * **每次启动都重新拉取、结果仅本次会话内存有效**——不落盘、不限频：
 * - 不落盘 ⇒ 横幅/弹窗/force 置灰的数据源就是本次启动那次拉取，服务端改 `update_type`
 *   （如把 `force` 撤回成 `prompt`）**本次启动即生效**；
 * - 不限频 ⇒ 「同日第二次启动」也能看到新公告/新决策（限频方案已被评审否决）。
 *
 * ### 启动闩锁
 * [beginStartupCheck] 用 CAS 保证**每个进程只自动检查一次**（首帧后由 JinlinApp 触发），
 * 切 Tab / 旋转屏幕不会重入；[checkNow] 不受闩锁限制（设置页「检查更新」与公告子页随时可调）。
 *
 * 拉取顺序 **先公告、后更新**（§4.4，对齐 PC `__main__.py` 的 singleShot 顺序）；两者均静默失败
 * （公告 → 空列表、版本 → null，见各自 Client），不影响主程序。
 *
 * ### 元数据自动拉取（2026-09-29 起，对齐 PC `remote_data.load_role_data` 的启动拉取）
 * [checkNow] 内**并行**拉一次卡池元数据（`MetadataClient.fetch`）：成功即覆盖
 * `role_cache.json` 并写 `metadata_version` / `metadata_fetched_at`（与设置页手动拉取同口径）；
 * **失败静默**（刻意偏离 PC 的「卡池信息未更新」Warning 弹窗——安卓有出厂种子兜底，
 * 离线时每次启动弹窗太打扰，2026-09-29 用户裁定），回退现有缓存/出厂种子。
 * 顺序上刻意偏离 PC（PC 为 role→公告→版本）：元数据启动时无 UI 立即消费，并行拉取
 * 不拖慢公告/更新弹窗时序（最坏延迟 = max 而非 sum）。
 *
 * @param rootDir 数据根目录（生产 = `filesDir`，已读集合存这里；测试 = 临时目录）
 * @param settings 设置仓库（元数据拉取成功后写两键；与手动拉取共用同一口径）
 */
class UpdateCenter internal constructor(private val rootDir: File, private val settings: SettingsStore) {

    companion object {
        /** UI 共用的进程级实例。 */
        @Volatile
        private var shared: UpdateCenter? = null

        fun get(context: android.content.Context): UpdateCenter =
            shared ?: synchronized(this) {
                shared ?: UpdateCenter(context.filesDir, SettingsStore.get(context)).also { shared = it }
            }

        /** 仅供单测：重置进程级单例。 */
        internal fun resetSharedForTest() {
            shared = null
        }
    }

    private val readStore = AnnouncementReadStore(rootDir)

    private val _announcements = MutableStateFlow<List<Announcement>>(emptyList())

    /** 本次会话拉到的**全部可见公告**（不只未读；服务端已过滤排序，端内不再排）。 */
    val announcements: StateFlow<List<Announcement>> = _announcements.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)

    /** 本次会话拉到的版本信息；`null` = 本次会话检查不到（未检查 / 失败均归此，静默）。 */
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _checked = MutableStateFlow(false)

    /** 本次会话是否已至少完成一次检查（公告子页靠它决定要不要自动拉一次）。 */
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    private val startupStarted = AtomicBoolean(false)

    /** 启动闩锁：返回 true = 本进程第一次，调用方应接着 `checkNow()`。 */
    fun beginStartupCheck(): Boolean = startupStarted.compareAndSet(false, true)

    /**
     * 拉取公告与版本（先公告后更新，§4.4），并**并行**拉一次卡池元数据（2026-09-29 起）。
     * 失败静默：公告空列表、版本 null、元数据不动缓存。可随时重复调用。
     *
     * **整个方法体在 [Dispatchers.IO] 执行**：各 Client 的 `defaultHttp` 是阻塞
     * HttpURLConnection I/O，而调用方协程多在主线程（`LaunchedEffect` / 设置页按钮）——
     * `suspend` 只挂起不换线程，不切调度器会在真机上抛 `NetworkOnMainThreadException`
     * 闪退（2026-09-28 红米 K60 实机抓到；MuMu ROM 网络栈绕过 BlockGuard 才侥幸没炸）。
     * StateFlow 赋值线程安全，回主线程的订阅更新无需额外处理。
     *
     * @param http HTTP 注入缝（缺省生产实现，现有调用点零改动；单测注入假实现全离线断言）
     */
    suspend fun checkNow(http: HttpGetter = { u, h -> MetadataClient.defaultHttp(u, h) }) =
        withContext(Dispatchers.IO) {
            coroutineScope {
                // 元数据并行拉（顺序刻意偏离 PC role→公告→版本：启动时无 UI 立即消费，
                // 不拖慢公告/更新弹窗时序）。fetch 自身失败已封装为 Result.failure，
                // runCatching 再兜 fetch 之外的意外异常；两条路都不打扰用户。
                val meta = async { runCatching { MetadataClient.fetch(MetaLoader.cacheFile(rootDir), http = http) } }
                // 公告/版本同样兜意外异常：各 Client 只把 Err outcome / 非 2xx 映射为静默
                // （空列表 / null），http 抛非 IOException 时会穿透——runCatching 收口，
                // 保证 checkNow 整体「怎么都不抛」（§12.7 测试口径）。
                _announcements.value =
                    runCatching { AnnouncementClient.fetch(http = http) }.getOrDefault(emptyList())
                _updateInfo.value = runCatching { VersionClient.fetch(http = http) }.getOrNull()
                // 成功：写 metadata_version / metadata_fetched_at（与手动拉取同口径；
                // SettingsStore.update 是 @Synchronized，此处本就在 IO 线程，落盘不占主线程）。
                // 失败：什么都不动，回退现有缓存/出厂种子（「最后拉取时间」不更新即最小信号）。
                // 双层 Result 各取一层：外层 getOrNull 兜 fetch 之外的意外异常（runCatching），
                // 内层 getOrNull 兜 MetadataException（网络/鉴权/格式等，fetch 已封装）。
                meta.await().getOrNull()?.getOrNull()?.let { m ->
                    settings.setMetadata(m.version.ifEmpty { "?" }, metadataFetchedAtNow())
                }
                _checked.value = true
            }
        }

    /**
     * 未读公告：按已读集合过滤（`pinned` 与 `update` 同规则）。
     *
     * 语义（2026-09-28 用户裁定）：`pinned` = **首次强制确认**——弹窗拦到用户点
     * 「确认并同意」为止，确认后记已读、**此后不再弹**（同一次会话内内容不更新）。
     * （原 §15 设计「pinned 每次启动都弹」已作废：真机实测用户确认后仍反复弹，被判为 bug。）
     */
    fun unread(): List<Announcement> = _announcements.value.filter { !readStore.isRead(it.id) }

    /** 批量标记已读（公告弹窗关闭时：确认过的 `pinned` + 勾了「不再提示」的 `update`）。 */
    fun markRead(ids: Collection<String>) {
        ids.forEach { readStore.markRead(it) }
    }
}

/** 「最后拉取」时间文本（本地时区；设置页手动拉取与启动自动拉取共用同一格式，防两处漂移）。 */
internal fun metadataFetchedAtNow(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
