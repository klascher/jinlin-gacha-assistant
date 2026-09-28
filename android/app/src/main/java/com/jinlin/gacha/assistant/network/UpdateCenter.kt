package com.jinlin.gacha.assistant.network

import com.jinlin.gacha.assistant.core.Announcement
import com.jinlin.gacha.assistant.persistence.AnnouncementReadStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
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
 * @param rootDir 数据根目录（生产 = `filesDir`，已读集合存这里；测试 = 临时目录）
 */
class UpdateCenter private constructor(rootDir: File) {

    companion object {
        /** UI 共用的进程级实例。 */
        @Volatile
        private var shared: UpdateCenter? = null

        fun get(context: android.content.Context): UpdateCenter =
            shared ?: synchronized(this) {
                shared ?: UpdateCenter(context.filesDir).also { shared = it }
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
     * 拉取公告与版本（先公告后更新，§4.4）。失败静默：公告空列表、版本 null。可随时重复调用。
     *
     * **整个方法体在 [Dispatchers.IO] 执行**：两个 Client 的 `defaultHttp` 是阻塞
     * HttpURLConnection I/O，而调用方协程多在主线程（`LaunchedEffect` / 设置页按钮）——
     * `suspend` 只挂起不换线程，不切调度器会在真机上抛 `NetworkOnMainThreadException`
     * 闪退（2026-09-28 红米 K60 实机抓到；MuMu ROM 网络栈绕过 BlockGuard 才侥幸没炸）。
     * StateFlow 赋值线程安全，回主线程的订阅更新无需额外处理。
     */
    suspend fun checkNow() = withContext(Dispatchers.IO) {
        _announcements.value = AnnouncementClient.fetch()
        _updateInfo.value = VersionClient.fetch()
        _checked.value = true
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
