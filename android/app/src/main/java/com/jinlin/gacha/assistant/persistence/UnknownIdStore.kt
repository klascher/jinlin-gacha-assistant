package com.jinlin.gacha.assistant.persistence

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 未映射 ID 快照：**屏蔽集合 + 卡池指派**一次给出（合成不可变快照，避免拆两个 Flow
 * 在变更瞬间界面撕裂——同 [ProfileSnapshot] 的动机）。
 *
 * @property shielded 被屏蔽的陌生 item_id 集合（保底计算中按 4/5 星、不触发归零）
 * @property assignments 陌生 pool_id → 保底分组 key（并组共享保底；空 = 独立）
 */
data class UnknownSnapshot(
    val shielded: Set<String> = emptySet(),
    val assignments: Map<String, String> = emptyMap(),
)

/**
 * 屏蔽 + 指派持久化 —— Kotlin 镜像 PC `gacha_exporter/storage/pool_assignments.py`
 * 与 `char_shields.py` 的**存储半边**（纯合成半边在 `core/stats/PoolAssignments`）。
 *
 * ### 为什么合并成一个 Store
 * PC 分两个文件两个模块；移动端为 UI 便捷统一成一个进程级单例 + 组合 [UnknownSnapshot]，
 * 但**文件布局逐字对齐 PC**，字段格式互通：
 * - `char_shields.json` → `{"shielded":["<id>",…]}`
 * - `pool_assignments.json` → `{"assignments":{"<pid>":"<gkey>",…}}`
 *
 * ### 作用域：跨账号全局
 * 屏蔽/指派是**角色/卡池维度**而非账号维度（同 PC 注释），故不放进 [ProfileStore] 的
 * 账号文件，而放数据根目录（生产 = `filesDir`）。
 *
 * 仅对「元数据未收录」的陌生 ID 生效；元数据收录后真实配置自动覆盖，条目失效（无害）。
 *
 * @param rootDir 数据根目录（生产 = `filesDir`；测试 = 临时目录）
 */
class UnknownIdStore(private val rootDir: File) {

    companion object {
        const val SHIELDS_FILENAME = "char_shields.json"
        const val ASSIGNMENTS_FILENAME = "pool_assignments.json"

        private const val KEY_SHIELDED = "shielded"
        private const val KEY_ASSIGNMENTS = "assignments"

        /** UI 与 Service 共用的进程级实例。 */
        @Volatile
        private var shared: UnknownIdStore? = null

        fun get(context: android.content.Context): UnknownIdStore =
            shared ?: synchronized(this) {
                shared ?: UnknownIdStore(context.filesDir).also { shared = it }
            }

        /** 仅供单测：重置进程级单例。 */
        internal fun resetSharedForTest() {
            shared = null
        }
    }

    private val shieldsFile: File get() = File(rootDir, SHIELDS_FILENAME)
    private val assignmentsFile: File get() = File(rootDir, ASSIGNMENTS_FILENAME)

    private val _shielded = LinkedHashSet<String>()
    private val _assignments = LinkedHashMap<String, String>()

    private val _state = MutableStateFlow(UnknownSnapshot())

    /** 未映射状态真相源（Compose 侧 `collectAsState()`）。 */
    val state: StateFlow<UnknownSnapshot> = _state.asStateFlow()

    init {
        load()
        publish()
    }

    // —— 查询 ——

    /** 是否已屏蔽某陌生角色 id。 */
    fun isShielded(itemId: String): Boolean = itemId in _shielded

    /** 某陌生池的当前指派组 key（空 = 未指派/独立）。 */
    fun assignmentOf(poolId: String): String? = _assignments[poolId]

    // —— 变更 ——

    /** 切换某陌生角色 id 的屏蔽态。 */
    @Synchronized
    fun toggleShield(itemId: String) {
        if (!_shielded.remove(itemId)) _shielded.add(itemId)
        persistShields()
        publish()
    }

    /**
     * 设置某陌生池的指派组 key；`groupKey` 为空/空白 = 清除指派（回独立）。
     * 只存映射，是否合法（组存在、池未收录）由纯合成 `PoolAssignments.apply` 消费时判断。
     */
    @Synchronized
    fun setPoolAssignment(poolId: String, groupKey: String?) {
        val clean = groupKey?.trim().orEmpty()
        if (clean.isEmpty()) {
            if (_assignments.remove(poolId) == null) return
        } else {
            if (_assignments[poolId] == clean) return
            _assignments[poolId] = clean
        }
        persistAssignments()
        publish()
    }

    // —— 加载 / 落盘 ——

    /** 读两文件；缺/坏 → 空态，不抛错（对齐 PC load_shields/load_pool_assignments）。 */
    private fun load() {
        _shielded.clear()
        _assignments.clear()

        // 屏蔽（兼容 {"shielded":[...]} 与裸数组两种写法，同 PC）
        try {
            val root = MiniJson.decode(shieldsFile.readText(Charsets.UTF_8))
            val raw = when (root) {
                is Map<*, *> -> root[KEY_SHIELDED]
                is List<*> -> root
                else -> null
            }
            (raw as? List<*>)?.forEach { id -> id?.toString()?.let { _shielded.add(it) } }
        } catch (e: Exception) {
            // 缺/坏 → 空屏蔽，不影响运行
        }

        // 指派（兼容 {"assignments":{...}} 与裸 dict 两种写法，同 PC）
        try {
            val root = MiniJson.decode(assignmentsFile.readText(Charsets.UTF_8))
            var raw: Any? = root
            if (root is Map<*, *> && root.containsKey(KEY_ASSIGNMENTS)) raw = root[KEY_ASSIGNMENTS]
            (raw as? Map<*, *>)?.forEach { (k, v) ->
                val pid = k?.toString() ?: return@forEach
                val gkey = v?.toString()?.trim().orEmpty()
                if (gkey.isNotEmpty()) _assignments[pid] = gkey
            }
        } catch (e: Exception) {
            // 缺/坏 → 空指派，不影响运行
        }
    }

    /** 落盘屏蔽集；失败只降级（不抛、不影响内存态）。 */
    private fun persistShields() {
        try {
            rootDir.mkdirs()
            shieldsFile.writeText(
                MiniJson.encodePretty(linkedMapOf(KEY_SHIELDED to _shielded.sorted()), indent = 2),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            // 只降级
        }
    }

    /** 落盘指派表；失败只降级（不抛、不影响内存态）。 */
    private fun persistAssignments() {
        try {
            rootDir.mkdirs()
            val sorted = LinkedHashMap<String, Any?>()
            _assignments.toSortedMap().forEach { (pid, gkey) -> sorted[pid] = gkey }
            assignmentsFile.writeText(
                MiniJson.encodePretty(linkedMapOf(KEY_ASSIGNMENTS to sorted), indent = 2),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            // 只降级
        }
    }

    private fun publish() {
        _state.value = UnknownSnapshot(
            shielded = _shielded.toSet(),
            assignments = _assignments.toMap(),
        )
    }
}