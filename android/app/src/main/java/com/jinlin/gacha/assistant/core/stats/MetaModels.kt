package com.jinlin.gacha.assistant.core.stats

/**
 * 统计元数据模型 —— Kotlin 逐字镜像 PC `gacha_exporter/models.py` 的
 * `Character` / `Pool` / `PityGroup`（只取统计引擎消费所需的字段与语义）。
 *
 * 这三者是 [StatsEngine] 的**输入契约**：由元数据层（PC = role.json / 移动端 = 将来的
 * 元数据拉取 S3）负责把 JSON 解析成这些对象再注入，引擎本身不读文件、不联网——
 * 因此本包**纯 JVM、零 Android 依赖**，可 junit 对拍。
 *
 * 与 PC 的字段名映射（`snake_case` → `camelCase`）：
 * `up_character`→[Pool.upCharacter]、`pity_group`→[Pool.pityGroup]、
 * `hard_pity`→[PityGroup.hardPity]、`has_state`→[PityGroup.hasState]。
 */

/**
 * 角色信息（镜像 PC `models.Character`）。
 *
 * @param rarity 星级；**0 = 未知**（PC `Character.default` 用的哨兵值）。
 *   注意与「陌生 ID 默认按 6 星」不同：rarity=0 只在**角色已收录但星级缺失**时出现；
 *   完全不认识的 item_id 根本不在 `characters` 表里，走陌生 ID 分支（见 [StatsEngine]）。
 */
data class Character(
    val id: String,
    val name: String,
    val rarity: Int,
    val limited: Boolean = false,
) {
    companion object {
        /** 构造未知角色的默认值（镜像 PC `Character.default`）。 */
        fun default(itemId: String): Character =
            Character(id = itemId, name = "未知($itemId)", rarity = 0, limited = false)
    }
}

/**
 * 卡池信息（镜像 PC `models.Pool`）。
 *
 * @param type 卡池类型：`"新手"` / `"常驻"` / `"UP"` / `"遴选"`（其它值为 [default] 的 `"未知"`）。
 * @param upCharacter 该池 UP 角色的**名字**（非 id）；[isFeatured] 为真且命中角色名相等才判 UP。
 * @param pityGroup 所属保底分组 key（对应 [PityGroup.id]）；**空串 = 未配 → 兜底 solo**
 *   （见 [PoolGroups.resolveGroups]）。
 */
data class Pool(
    val id: String,
    val name: String,
    val type: String,
    val order: Int = 0,
    val rarity: Int = 0,
    val upCharacter: String = "",
    val pityGroup: String = "",
) {
    /** 是否为 UP 池（镜像 PC `Pool.is_up`：`type == "UP"`）。 */
    val isUp: Boolean get() = type == "UP"

    /** 是否为**有 UP 角色**的池（镜像 PC `Pool.is_featured`：UP 或遴选）。 */
    val isFeatured: Boolean get() = type == "UP" || type == "遴选"

    companion object {
        /** 构造未知卡池的默认值（镜像 PC `Pool.default`；注意其 type=`"未知"`、`isFeatured=false`）。 */
        fun default(poolId: String): Pool =
            Pool(id = poolId, name = "未知($poolId)", type = "未知", order = 0)
    }
}

/**
 * 保底分组 —— 同组卡池共享保底计数与小/大保底状态（镜像 PC `models.PityGroup`）。
 *
 * @param hardPity 该组硬保底抽数（默认 70）；决定 [StatsEngine.sharedPityTracking] 的
 *   `distance_to_pity`。
 * @param hasState `false` 的组（如新手/常驻）**不跑小/大保底状态机**，也不进非歪率统计。
 */
data class PityGroup(
    val id: String,
    val label: String,
    val hardPity: Int = 70,
    val hasState: Boolean = false,
) {
    companion object {
        /** 构造独立分组（镜像 PC `PityGroup.solo`：id=`"solo:<poolId>"`、`hasState=false`）。 */
        fun solo(poolId: String, label: String, hardPity: Int = 70): PityGroup =
            PityGroup(id = "solo:$poolId", label = label, hardPity = hardPity, hasState = false)
    }
}
