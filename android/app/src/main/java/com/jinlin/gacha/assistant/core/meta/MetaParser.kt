package com.jinlin.gacha.assistant.core.meta

import com.jinlin.gacha.assistant.core.stats.Character
import com.jinlin.gacha.assistant.core.stats.PityGroup
import com.jinlin.gacha.assistant.core.stats.Pool

/**
 * `role.json` / `role_cache.json` 的解析器 —— Kotlin 逐字镜像 PC
 * `gacha_exporter/models.py` 的 `parse_character` / `parse_pool` / `parse_pity_groups`。
 *
 * **输入形态**：`MiniJson.decode()` 的产物 —— object → `Map<String, Any?>`、
 * 整数 → [Long]、浮点 → [Double]、字符串 → [String]、布尔 → [Boolean]、null → null。
 * 故数值一律经 [asInt]（`Long` → `Int`）、布尔经 [asBool] 收敛。
 *
 * **与 PC 的两处刻意差异**（更健壮；**合法数据下解析结果完全一致**，已对拍）：
 *  - PC 用 `dict.get(k, default)` —— **键存在但值为 `null` 时返回 `None`** 而非默认值，
 *    且 `None` 会污染下游（`Character.name = None`）。Kotlin 一律「非目标类型 → 取默认值」，
 *    即 `name` 为 null 也回退 `未知(id)`，不产生 null 字段。
 *  - PC 对错类型会抛 `TypeError`（如 `rarity="x"` 触发 `int("x")`）；
 *    Kotlin 兜底为默认值不抛。
 */
object MetaParser {

    /**
     * 解析整份缓存/远程响应为 [Meta]（对齐 PC `load_role_data` 尾段的三次 `parse_*` 调用）。
     *
     * 调用方应先用 [MetaLoader.isValidCache] 判定有效性（对齐 PC `_load_cache`）。
     * 各段缺失或类型不符时**降级为空集合**，不抛错（PC 此时会因 `int(…)`/`.items()` 崩溃）。
     */
    fun parse(root: Map<String, Any?>): Meta {
        val characters = LinkedHashMap<String, Character>()
        for ((cid, cdata) in asMap(root["characters"])) {
            characters[cid] = parseCharacter(cid, asMap(cdata))
        }

        val pools = LinkedHashMap<String, Pool>()
        for ((pid, pdata) in asMap(root["pools"])) {
            pools[pid] = parsePool(pid, asMap(pdata))
        }

        return Meta(
            characters = characters,
            pools = pools,
            pityGroups = parsePityGroups(root["pity_groups"]),
            version = (root["version"] as? String) ?: "",
        )
    }

    /** 从 JSON dict 构造 [Character]（镜像 PC `parse_character`）。 */
    fun parseCharacter(itemId: String, data: Map<String, Any?>): Character =
        Character(
            id = itemId,
            name = (data["name"] as? String) ?: "未知($itemId)",
            rarity = asInt(data["rarity"], 0),
            limited = asBool(data["limited"], false),
        )

    /** 从 JSON dict 构造 [Pool]（镜像 PC `parse_pool`）。 */
    fun parsePool(poolId: String, data: Map<String, Any?>): Pool =
        Pool(
            id = poolId,
            name = (data["name"] as? String) ?: "未知($poolId)",
            type = (data["type"] as? String) ?: "未知",
            order = asInt(data["order"], 0),
            rarity = asInt(data["rarity"], 0),
            upCharacter = (data["up_character"] as? String) ?: "",
            pityGroup = (data["pity_group"] as? String) ?: "",
        )

    /**
     * 从顶层 `pity_groups` 段构造 `{group_id: PityGroup}`（镜像 PC `parse_pity_groups`）。
     *
     * 非 dict 入参返回空 map；非 dict 的条目跳过；`label` 缺失回退为 group id、
     * `hard_pity` 缺失回退 70、`has_state` 缺失回退 false。
     */
    fun parsePityGroups(data: Any?): Map<String, PityGroup> {
        val src = asMap(data)
        if (src.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, PityGroup>()
        for ((gid, gdata) in src) {
            val group = gdata as? Map<*, *> ?: continue // 非对象条目跳过（对齐 PC）
            result[gid] = PityGroup(
                id = gid,
                label = (group["label"] as? String) ?: gid,
                hardPity = asInt(group["hard_pity"], 70),
                hasState = asBool(group["has_state"], false),
            )
        }
        return result
    }

    // —— 类型收敛工具（MiniJson 的数字恒为 Long，须显式收窄成 Int）——

    /** 把 MiniJson 解出的 value 收成 `Map<String, Any?>`；非 object 一律空 map。 */
    private fun asMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) return emptyMap()
        val out = LinkedHashMap<String, Any?>(value.size)
        for ((k, v) in value) out[k.toString()] = v
        return out
    }

    private fun asInt(value: Any?, default: Int): Int =
        (value as? Number)?.toInt() ?: default

    private fun asBool(value: Any?, default: Boolean): Boolean =
        value as? Boolean ?: default
}
