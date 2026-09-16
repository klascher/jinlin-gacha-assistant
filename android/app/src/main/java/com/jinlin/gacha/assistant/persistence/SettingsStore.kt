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
 * `last_capture` 与 PC 同名同结构（`{profile_id: iso_str}`）；`auto_diagnose` / `metadata_*`
 * 是 mobile 新增。
 *
 * ### 两处「不实现」的说明
 * - **`active_profile` 不放这里**：当前账号的唯一真相源是 [ProfileStore]（`profiles.json`
 *   的 `active_id`，与 PC 布局一致）。两处各存一份会形成双真相源并漂移。
 * - **`endpoints` 不持久化**：按 09 设计稿 §8-Q3 决策不做（冷启动端点归零，重抓即恢复）。
 */
data class Settings(
    /** 异常自动落盘（抓包组开关；默认 false，保持既有行为）。 */
    val autoDiagnose: Boolean = false,
    /** 每账号最近一次抓包完成时间（展示用）。 */
    val lastCapture: Map<String, String> = emptyMap(),
    /** 元数据版本（形如 `v1.0.4`）；空串 = 未拉取。 */
    val metadataVersion: String = "",
    /** 元数据最后拉取时间（展示用）。 */
    val metadataFetchedAt: String = "",
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
        private const val KEY_LAST_CAPTURE = "last_capture"
        private const val KEY_METADATA_VERSION = "metadata_version"
        private const val KEY_METADATA_FETCHED_AT = "metadata_fetched_at"

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

    /** 切换「异常自动落盘」（抓包组开关；下次开始抓包时生效，见 G2）。 */
    fun setAutoDiagnose(on: Boolean) = update { it.copy(autoDiagnose = on) }

    /** 记录某账号最近一次抓包完成时间。 */
    fun setLastCapture(profileId: String, at: String) =
        update { it.copy(lastCapture = it.lastCapture + (profileId to at)) }

    /** 写入元数据版本与拉取时间（元数据组手动拉取成功后调用）。 */
    fun setMetadata(version: String, fetchedAt: String) =
        update { it.copy(metadataVersion = version, metadataFetchedAt = fetchedAt) }

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
            lastCapture = toStringMap(map[KEY_LAST_CAPTURE]),
            metadataVersion = (map[KEY_METADATA_VERSION] as? String) ?: "",
            metadataFetchedAt = (map[KEY_METADATA_FETCHED_AT] as? String) ?: "",
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
        KEY_LAST_CAPTURE to LinkedHashMap<String, Any?>(s.lastCapture),
        KEY_METADATA_VERSION to s.metadataVersion,
        KEY_METADATA_FETCHED_AT to s.metadataFetchedAt,
    )

    private fun toStringMap(v: Any?): Map<String, String> {
        val m = v as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, value) in m) out[k.toString()] = value?.toString() ?: ""
        return out
    }
}
