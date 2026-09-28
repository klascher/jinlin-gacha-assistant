package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 设置项快照（不可变值对象）。**改动一律走 [SettingsStore.update]**，页面只读渲染。
 *
 * 键名对齐 PC `gacha_exporter/storage/settings.py`（同名同构，未来 PC↔Android 可直接互认）：
 * `last_capture` 与 PC 同名同结构（`{profile_id: iso_str}`）；`auto_diagnose` / `app_log_enabled` /
 * `metadata_*` /
 * `target_packages` / `guide_seen_version` 是 mobile 新增 —— PC 侧读到未知键会原样忽略，
 * 不影响其读写（PC 只在自己的机器上跑，两边不会同时写同一个文件）。
 * ⚠️ 「加新键安全」这条**只对新增键成立**：改**已有键的值类型**（例如把 `last_capture`
 * 从字符串改成对象）会让 PC 的 `isinstance(val, str)` 守卫静默回空串，属真兼容问题。
 *
 * ### 两处「不实现」的说明
 * - **`active_profile` 不放这里**：当前账号的唯一真相源是 [ProfileStore]（`profiles.json`
 *   的 `active_id`，与 PC 布局一致）。两处各存一份会形成双真相源并漂移。
 * - **`endpoints` 不持久化**：按 09 设计稿 §8-Q3 决策不做（冷启动端点归零，重抓即恢复）。
 */
data class Settings(
    /** 「抓包过程中异常写入日志」（抓包组开关；默认 false，保持既有行为）。 */
    val autoDiagnose: Boolean = false,
    /**
     * **App 级日志**开关（2026-09-22 新增，mobile 独有）。**默认 false**。
     *
     * 与 [autoDiagnose] 是**两件事**，互不影响：
     * - [autoDiagnose] 只管**抓包过程中**异常判据命中时，要不要自动落 pcap + 报告；
     * - 本项只管 **App 日志文件写不写**（`applog/app-<yyyyMMdd>.txt`，见 `AppLog`）——
     *   **会话之外也写**（开抓包之前 / 停抓之后都有），且**即改即生效**。
     *
     * 键名 `app_log_enabled`（PC 读到未知键会原样忽略，不影响其读写）。
     */
    val appLogEnabled: Boolean = false,
    /** 每账号最近一次抓包完成时间（展示用）。 */
    val lastCapture: Map<String, String> = emptyMap(),
    /** 元数据版本（形如 `v1.0.4`）；空串 = 未拉取。 */
    val metadataVersion: String = "",
    /** 元数据最后拉取时间（展示用）。 */
    val metadataFetchedAt: String = "",
    /**
     * 用户**选定**要接管的**目标游戏包名**（**单选**；`vpn/TargetPackages` 用；mobile 新增）。
     *
     * **null = 从未配置过** ⇒ 由 `TargetPackages.decide` 走「自动检出（仅 1 个）> 官服兜底」；
     * 检出多个时**不替用户决定**，而是要求用户到设置页选一个。刻意不赋予
     * 「null = 一个都不接管」这层语义：设置页保存时拒绝空选，故二者不会撞车。
     *
     * 换渠道服（官服 `com.bmystu.peng.gw` ↔ 渠道服如 OPPO
     * `com.bmystu.peng1.nearme.gamecenter`）在这里改即可，不必重新打包。
     *
     * ### ⚠️ 为什么是**单选**，而不是多选（2026-09-18 晚由多选改回）
     * 多选会让**两个渠道的流量同时进 tun**，而整场抓包只有**一套**视图识别表
     * （`core/dedup/ViewTracker` 的 `seq % 127` 配对表是全局单例）⇒ 两个游戏各自独立的
     * seq 空间会撞进同一张表，响应可能配到另一个游戏的请求上（视图/页数错配）。
     * 且记录**不按渠道隔离**，「当前人在玩哪个渠道」才是正确语义 ⇒ 换渠道时来设置页改选。
     * 抓包中不可改：白名单在建立 tun 时定死（见 `GachaVpnService.startVpn`）。
     */
    val targetPackage: String? = null,
    /**
     * 首次引导**已读到的版本号**（U5，2026-09-18；原拟名 `onboarding_seen`）。
     *
     * 缺省 `0` = 没看过 ⇒ **首次进入抓包页**弹引导卡（三步骤）；看过（或点「不再提示」）
     * 写 `1` ⇒ 不再弹；将来引导文案改版把值提到 `2` 即可**重新弹一次**
     * （比纯布尔值多留一层余量）。
     *
     * ⚠️ 落点为什么不是「安装后立刻弹」或「点开始抓包时弹」：前者与操作场景脱节；
     * 后者那刻用户**可能已经站在卡池页里了**，弹了等于叫他全部重来。
     */
    val guideSeenVersion: Int = 0,
    /**
     * `prompt` 更新弹窗**已确认过的服务端版本号**（§15 更新设计，mobile 独有键）。
     *
     * 语义照搬 PC `storage/settings.py` 的 `acknowledged_version`：启动检查发现
     * `latest != acknowledged` 才弹 `prompt` 更新弹窗（用户点「去下载」或「稍后」都记），
     * 同版本不再反复打扰；版本追平后由检查逻辑自然清场。
     * **只影响 `prompt`**：`force` 弹窗每次启动都弹（PC 同款）、`silent` 完全不弹。
     */
    val acknowledgedVersion: String = "",
)

/**
 * 设置持久化 —— 自写 JSON（`filesDir/settings.json`）+ 进程级 [StateFlow]。
 *
 * 选型理由（09 设计稿 §4.1 方案 B，用户已拍板）：
 * - **零新依赖**：不引 DataStore；复用 `core/dedup/MiniJson`（与 [HistoryStore] 同款）；
 * - **与 PC 同构**：键名对齐 PC `storage/settings.py`，互通零成本；
 * - **纯 JVM 可测**：构造器只收 [File]（不收 `Context`），`testDebugUnitTest` 下可直接跑。
 *
 * 行为契约（对齐 PC「改动即存即生效」）：
 * - 写路径唯一：`update { }` → 新快照 → 更新 StateFlow → 落盘；
 * - **落盘失败只降级**：捕获异常、保留内存值（UI 不回滚），下次启动回到上次成功落盘的值；
 * - 读失败（文件损坏 / 结构不符）→ 回默认值，**不抛、不删原文件**；
 * - 值未变化时不写盘（幂等闸）。
 *
 * @param file 设置文件（生产 = `File(filesDir, "settings.json")`；测试 = 临时目录）
 */
class SettingsStore(private val file: File) {

    companion object {
        const val FILE_NAME = "settings.json"

        private const val KEY_AUTO_DIAGNOSE = "auto_diagnose"
        /** App 级日志开关（2026-09-22 新增，mobile 独有）。 */
        private const val KEY_APP_LOG = "app_log_enabled"
        private const val KEY_LAST_CAPTURE = "last_capture"
        private const val KEY_METADATA_VERSION = "metadata_version"
        private const val KEY_METADATA_FETCHED_AT = "metadata_fetched_at"
        private const val KEY_TARGET_PACKAGE = "target_package"
        /**
         * **多选时代的旧键**（2026-09-18 单选化后不再写入）—— 只读兼容：取首项。
         * 保留的理由：开发机/早期构建可能已落过该键，静默丢弃会让「已经选过的渠道」失效。
         */
        private const val KEY_TARGET_PACKAGES_LEGACY = "target_packages"
        private const val KEY_GUIDE_SEEN_VERSION = "guide_seen_version"
        /** prompt 更新弹窗已确认的服务端版本号（§15；PC 同名键 `acknowledged_version`）。 */
        private const val KEY_ACKNOWLEDGED_VERSION = "acknowledged_version"

        /** UI 与 Service 共用的进程级实例（保证两边看到同一份 StateFlow）。 */
        @Volatile
        private var shared: SettingsStore? = null

        /** 取进程级单例；首次调用以 `filesDir/settings.json` 建并加载。 */
        fun get(context: android.content.Context): SettingsStore =
            shared ?: synchronized(this) {
                shared ?: SettingsStore(File(context.filesDir, FILE_NAME)).also { shared = it }
            }

        /** 仅供单测：重置进程级单例，避免用例间串状态。 */
        internal fun resetSharedForTest() {
            shared = null
        }
    }

    private val _state = MutableStateFlow(loadFromDisk())

    /** 唯一设置真相源（Compose 侧 `collectAsState()`）。 */
    val state: StateFlow<Settings> = _state.asStateFlow()

    /** 当前快照（非 Compose 侧同步读取用）。 */
    val current: Settings get() = _state.value

    /** 异常自动落盘开关快捷读。 */
    val autoDiagnose: Boolean get() = _state.value.autoDiagnose

    /** App 日志开关快捷读（2026-09-22 新增）。 */
    val appLogEnabled: Boolean get() = _state.value.appLogEnabled

    /**
     * 唯一写路径：`transform` 产出新快照 → 更新 StateFlow → 落盘。
     * `transform` 结果与原值相等时**不动 StateFlow、不写盘**（幂等；避免无谓 IO 与重组）。
     */
    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        persist(next)
    }

    // —— 便捷写入口（页面直接调用，无需拼 transform）——

    /** 切换「抓包过程中异常写入日志」（抓包组开关；下次开始抓包时生效，见 G2）。 */
    fun setAutoDiagnose(on: Boolean) = update { it.copy(autoDiagnose = on) }

    /**
     * 切换「App 日志」（**即改即生效**，不是「下次生效」）。
     *
     * ⚠️ 本方法**只写设置**；真正起停落盘器还要同时调 `AppLog.setEnabled`（设置页那两行一起写）。
     * 不在这里联动的原因：本类是**纯 JVM 可测**的存储层，不该去 new 一个写盘器 / 持 `Context`。
     */
    fun setAppLogEnabled(on: Boolean) = update { it.copy(appLogEnabled = on) }

    /** 记录某账号最近一次抓包完成时间。 */
    fun setLastCapture(profileId: String, at: String) =
        update { it.copy(lastCapture = it.lastCapture + (profileId to at)) }

    /** 写入元数据版本与拉取时间（元数据组手动拉取成功后调用）。 */
    fun setMetadata(version: String, fetchedAt: String) =
        update { it.copy(metadataVersion = version, metadataFetchedAt = fetchedAt) }

    /**
     * 写入用户选定的接管包名（设置页「接管范围」弹窗保存时调用）。
     *
     * **单选**：一次一个；空白包名视作清空（回「从未配置过」）。
     */
    fun setTargetPackage(packageName: String) =
        update { it.copy(targetPackage = packageName.takeIf { p -> p.isNotBlank() }) }

    /**
     * 记录「首次引导已读到第几版」（U5）。看过引导卡即写当前版本号，此后不再弹；
     * 引导文案改版时把传入值提高即可重新弹一次。
     */
    fun setGuideSeenVersion(version: Int) =
        update { it.copy(guideSeenVersion = version) }

    /**
     * 记录 `prompt` 更新弹窗已确认的服务端版本号（点「去下载」/「稍后」都记，§15 §4.3②）。
     * 同版本不再弹；版本追平后由更新决策自然不再弹（无需清键）。
     */
    fun setAcknowledgedVersion(version: String) =
        update { it.copy(acknowledgedVersion = version) }

    /** 外部改动（如导入覆盖）后重新从磁盘加载。 */
    @Synchronized
    fun reload() {
        _state.value = loadFromDisk()
    }

    // —— 磁盘读写 ——

    /** 读盘；文件不存在 / 损坏 / 结构不符一律回默认值（绝不抛、绝不删）。 */
    private fun loadFromDisk(): Settings {
        if (!file.exists()) return Settings()
        val root = try {
            MiniJson.decode(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return Settings()
        }
        val map = root as? Map<*, *> ?: return Settings()
        return Settings(
            autoDiagnose = (map[KEY_AUTO_DIAGNOSE] as? Boolean) ?: false,
            appLogEnabled = (map[KEY_APP_LOG] as? Boolean) ?: false,
            lastCapture = toStringMap(map[KEY_LAST_CAPTURE]),
            metadataVersion = (map[KEY_METADATA_VERSION] as? String) ?: "",
            metadataFetchedAt = (map[KEY_METADATA_FETCHED_AT] as? String) ?: "",
            // 单选键优先；旧多选键只作兼容（取首项，单选化后不再写入）
            targetPackage = (map[KEY_TARGET_PACKAGE] as? String)?.takeIf { it.isNotBlank() }
                ?: toStringList(map[KEY_TARGET_PACKAGES_LEGACY]).firstOrNull(),
            // MiniJson 把整数解码成 Long ⇒ 用 Number 接（Int / Long / Double 都吃得下）
            guideSeenVersion = (map[KEY_GUIDE_SEEN_VERSION] as? Number)?.toInt() ?: 0,
            acknowledgedVersion = (map[KEY_ACKNOWLEDGED_VERSION] as? String) ?: "",
        )
    }

    /** 落盘；失败只吞掉（G1：不留弹错、不回滚内存值）。 */
    private fun persist(s: Settings) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(MiniJson.encodePretty(buildPayload(s), indent = 2), Charsets.UTF_8)
        } catch (e: Exception) {
            // 落盘失败只降级：内存值仍生效，下次启动回到上次成功落盘的值（与 PC 一致）
        }
    }

    private fun buildPayload(s: Settings): Map<String, Any?> = linkedMapOf(
        KEY_AUTO_DIAGNOSE to s.autoDiagnose,
        KEY_APP_LOG to s.appLogEnabled,
        KEY_LAST_CAPTURE to LinkedHashMap<String, Any?>(s.lastCapture),
        KEY_METADATA_VERSION to s.metadataVersion,
        KEY_METADATA_FETCHED_AT to s.metadataFetchedAt,
        KEY_TARGET_PACKAGE to s.targetPackage,
        KEY_GUIDE_SEEN_VERSION to s.guideSeenVersion.toLong(),
        KEY_ACKNOWLEDGED_VERSION to s.acknowledgedVersion,
    )

    /**
     * 读字符串数组；**不是数组**则回空列表，数组内的 null 项跳过（非字符串项转成文本）。
     *
     * 刻意不抛：`settings.json` 被手改坏不该让 App 起不来（与 [loadFromDisk] 同一条降级契约）。
     */
    private fun toStringList(v: Any?): List<String> {
        val list = v as? List<*> ?: return emptyList()
        val out = ArrayList<String>(list.size)
        for (item in list) {
            if (item != null) out.add(item.toString())
        }
        return out
    }

    private fun toStringMap(v: Any?): Map<String, String> {
        val m = v as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, value) in m) out[k.toString()] = value?.toString() ?: ""
        return out
    }
}
