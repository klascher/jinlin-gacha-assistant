package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 一个账号档案（对齐 PC `models.Profile`：`id` / `name` / `created_at`）。 */
data class Profile(
    val id: String,
    val name: String,
    val createdAt: String,
)

/**
 * 账号快照：**当前账号 id + 有序列表**一次给出。
 *
 * 为什么不是 `StateFlow<List<Profile>>`（09 设计稿 §5 的原写法）：UI 需要同时知道
 * 「谁是当前账号」与「列表顺序」；拆成两个 Flow 会在切换瞬间出现撕裂（列表已更新、
 * activeId 还没到位）。合成一个不可变快照可避免。
 */
data class ProfileSnapshot(
    val activeId: String = "",
    val items: List<Profile> = emptyList(),
)

/**
 * 多账号注册表 —— Kotlin 镜像 PC `gacha_exporter/storage/profiles.py::ProfileManager`。
 *
 * ### 文件布局（与 PC **逐字一致**，这是 PC↔Android 互通的硬约束）
 * - `profiles.json`：
 *   ```json
 *   {"active_id":"<id>","order":["<id>",…],
 *    "profiles":{"<id>":{"id":"<id>","name":"默认账号","created_at":"2026-09-14T11:30:00"}}}
 *   ```
 * - `users/<id>.json`：记录本体（由 [HistoryStore] 读写）
 *
 * > ⚠️ 09 设计稿 §4.2 写的是 `{"active":"…","profiles":[…]}`（`profiles` 为**数组**）。
 * > PC 实际是 `active_id` + `order` + `profiles` **字典**。按设计稿自身「以 PC 代码为准
 * > 逐字复核」（§9）的要求，这里以 PC 为准。
 *
 * ### 与 PC 一致的其它细节
 * - 账号 id = **12 位 hex**（PC `uuid4().hex[:12]`）。注释原文：不含下划线/连字符，
 *   以免与归档名 `<id>_<stamp>.json` 冲突——故设计稿 §4.2 的 `p_` 前缀写法**不采用**。
 * - 首次启动无注册表 → 建「默认账号」（PC `_DEFAULT_NAME = "默认账号"`）。
 * - 删除**最后一个账号被拒绝**（返回 null）；删除时历史归档为 `<id>_<yyyyMMdd_HHmmss>.json`。
 * - 读注册表失败 / active 失效 → 自愈（order 按 profiles 重建、active 回退首个）。
 *
 * @param rootDir 数据根目录（生产 = `filesDir`；测试 = 临时目录）
 */
class ProfileStore(private val rootDir: File) {

    companion object {
        const val REGISTRY_NAME = "profiles.json"
        const val USERS_DIR = "users"

        /** 默认账号名（与 PC `_DEFAULT_NAME` 一致）。 */
        const val DEFAULT_NAME = "默认账号"

        private const val KEY_ACTIVE_ID = "active_id"
        private const val KEY_ORDER = "order"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_CREATED_AT = "created_at"

        private val STAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** ISO 秒精度格式（对齐 PC `isoformat(timespec="seconds")`，**总带秒**）。 */
        private val ISO_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /**
         * mobile 特有迁移：去重落库首版（S2 早期）把账号 id 硬编码为 `default`，
         * 其记录文件 `users/default.json` 由 [bootstrap] 接管为新的默认账号，避免已抓数据"消失"。
         */
        private const val LEGACY_USER_FILE = "default.json"

        /** UI 与 Service 共用的进程级实例。 */
        @Volatile
        private var shared: ProfileStore? = null

        fun get(context: android.content.Context): ProfileStore =
            shared ?: synchronized(this) {
                shared ?: ProfileStore(context.filesDir).also { shared = it }
            }

        /** 仅供单测：重置进程级单例。 */
        internal fun resetSharedForTest() {
            shared = null
        }
    }

    private val registryFile: File get() = File(rootDir, REGISTRY_NAME)

    /**
     * 账号历史目录（`<root>/users`）—— 与 PC `ProfileManager.users_dir` 同名同义，是
     * 「账号历史落在哪」的**单源**（`SyncBackend` 用它构造 `HistoryStore`，不再自己拼路径）。
     */
    val usersDir: File get() = File(rootDir, USERS_DIR)

    private var activeId: String = ""
    private val order = mutableListOf<String>()
    private val profiles = LinkedHashMap<String, Profile>()

    private val _state = MutableStateFlow(ProfileSnapshot())

    /** 账号真相源（Compose 侧 `collectAsState()`）。 */
    val state: StateFlow<ProfileSnapshot> = _state.asStateFlow()

    init {
        usersDir.mkdirs()
        if (!registryFile.exists()) bootstrap() else load()
        publish()
    }

    // —— 查询 ——

    /** 当前账号 id（空串 = 无账号，理论不会出现：bootstrap 至少建一个）。 */
    val active: String get() = activeId

    /** 当前账号档案。 */
    fun activeProfile(): Profile? = profiles[activeId]

    /** 按显示顺序返回全部账号。 */
    fun list(): List<Profile> = order.mapNotNull { profiles[it] }

    /** 某账号的历史文件（供 [HistoryStore] 使用）。 */
    fun userFile(profileId: String): File = File(usersDir, "$profileId.json")

    // —— 变更 ——

    /**
     * 新建账号（名称去空白，空则用默认名）；加入注册表尾部。
     * 不自动切换当前账号（对齐 PC：新建后需显式 [setActive]）。
     */
    @Synchronized
    fun create(name: String): Profile {
        val clean = name.trim().ifEmpty { DEFAULT_NAME }
        val p = Profile(newId(), clean, nowIso())
        profiles[p.id] = p
        order += p.id
        persist()
        publish()
        return p
    }

    /** 重命名；名称为空则保持原名（对齐 PC `rename`）。 */
    @Synchronized
    fun rename(profileId: String, name: String) {
        val p = profiles[profileId] ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        profiles[profileId] = p.copy(name = clean)
        persist()
        publish()
    }

    /**
     * 删除账号：历史文件归档为 `<id>_<stamp>.json` 后从注册表移除。
     *
     * **仅剩一个账号时拒绝删除**（对齐 PC，返回 null）。删除的是当前账号时自动切到列表首个。
     *
     * @return 归档文件；被拒绝 / 无历史文件时为 null
     */
    @Synchronized
    fun delete(profileId: String): File? {
        if (!profiles.containsKey(profileId)) return null
        if (profiles.size <= 1) return null

        var archive: File? = null
        val uf = userFile(profileId)
        if (uf.exists()) {
            val target = uniqueArchive(profileId)
            archive = if (uf.renameTo(target)) {
                target
            } else {
                // 同目录 rename 失败（极少见）→ 退回复制 + 删除，避免丢数据
                uf.copyTo(target, overwrite = false).also { uf.delete() }
            }
        }
        order.remove(profileId)
        profiles.remove(profileId)
        if (activeId == profileId) activeId = order.firstOrNull() ?: ""
        persist()
        publish()
        return archive
    }

    /** 切换当前账号（id 不存在则忽略，对齐 PC `set_active`）。 */
    @Synchronized
    fun setActive(profileId: String) {
        if (!profiles.containsKey(profileId)) return
        if (activeId == profileId) return
        activeId = profileId
        persist()
        publish()
    }

    /** 外部改动后重新从磁盘加载。 */
    @Synchronized
    fun reload() {
        if (registryFile.exists()) load() else bootstrap()
        publish()
    }

    // —— 加载 / 落盘 ——

    /** 首次启动：建「默认账号」并落盘（对齐 PC `_bootstrap`）。 */
    private fun bootstrap() {
        usersDir.mkdirs()
        val def = Profile(newId(), DEFAULT_NAME, nowIso())
        // mobile 特有迁移：接管控件的 legacy 记录文件（见 LEGACY_USER_FILE 注释）
        val legacy = File(usersDir, LEGACY_USER_FILE)
        val target = File(usersDir, "${def.id}.json")
        if (legacy.exists() && !target.exists()) {
            legacy.renameTo(target)
        }
        order.clear()
        order += def.id
        profiles.clear()
        profiles[def.id] = def
        activeId = def.id
        persist()
    }

    /** 读注册表并自愈不一致（对齐 PC `_load` 的 order 重建与 active 回退）。 */
    private fun load() {
        val root = try {
            MiniJson.decode(registryFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        } as? Map<*, *>
        if (root == null) {
            bootstrap() // 损坏 → 重建（原文件已被 bootstrap 的 persist 覆盖，与 PC 行为一致）
            return
        }
        activeId = (root[KEY_ACTIVE_ID] as? String) ?: ""
        order.clear()
        (root[KEY_ORDER] as? List<*>)?.forEach { pid -> pid?.toString()?.let { order += it } }
        profiles.clear()
        (root[KEY_PROFILES] as? Map<*, *>)?.forEach { (k, v) ->
            val m = v as? Map<*, *> ?: return@forEach
            val id = m[KEY_ID]?.toString() ?: k.toString()
            profiles[id] = Profile(
                id = id,
                name = m[KEY_NAME]?.toString() ?: DEFAULT_NAME,
                createdAt = m[KEY_CREATED_AT]?.toString() ?: "",
            )
        }
        if (order.isEmpty()) order += profiles.keys
        if (profiles.isEmpty()) {
            bootstrap()
            return
        }
        if (activeId.isEmpty() || !profiles.containsKey(activeId)) {
            activeId = order.firstOrNull() ?: profiles.keys.first()
        }
    }

    /** 落盘注册表；失败只降级（不抛、不影响内存态）。 */
    private fun persist() {
        try {
            val profMap = LinkedHashMap<String, Any?>()
            for ((id, p) in profiles) {
                profMap[id] = linkedMapOf<String, Any?>(
                    KEY_ID to p.id,
                    KEY_NAME to p.name,
                    KEY_CREATED_AT to p.createdAt,
                )
            }
            val payload = linkedMapOf<String, Any?>(
                KEY_ACTIVE_ID to activeId,
                KEY_ORDER to order.toList(),
                KEY_PROFILES to profMap,
            )
            rootDir.mkdirs()
            registryFile.writeText(MiniJson.encodePretty(payload, indent = 2), Charsets.UTF_8)
        } catch (e: Exception) {
            // 只降级
        }
    }

    private fun publish() {
        _state.value = ProfileSnapshot(activeId, list())
    }

    // —— 辅助 ——

    /** 12 位 hex id（与 PC `uuid4().hex[:12]` 同形；不含下划线，避免与归档名歧义）。 */
    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    /**
     * ISO 秒精度时间串，**总带秒**。
     * 注意不能用 `LocalDateTime.toString()`——它在秒为 0 时会省略 `:00`（输出 `...T11:30`），
     * 与 PC `isoformat(timespec="seconds")` 的 `...T11:30:00` 不一致。
     */
    private fun nowIso(): String = LocalDateTime.now().withNano(0).format(ISO_FMT)

    /** 生成不与现有备份冲突的归档文件 `<id>_<stamp>[_N].json`。 */
    private fun uniqueArchive(profileId: String): File {
        val stamp = LocalDateTime.now().format(STAMP_FMT)
        var f = File(usersDir, "${profileId}_$stamp.json")
        var n = 1
        while (f.exists()) {
            f = File(usersDir, "${profileId}_${stamp}_$n.json")
            n++
        }
        return f
    }
}
