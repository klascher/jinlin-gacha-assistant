package com.jinlin.gacha.assistant.core.meta

import com.jinlin.gacha.assistant.core.stats.Character
import com.jinlin.gacha.assistant.core.stats.PityGroup
import com.jinlin.gacha.assistant.core.stats.Pool

/**
 * 一份完整的角色/卡池元数据 —— [com.jinlin.gacha.assistant.core.stats.StatsEngine]
 * 的构造输入（引擎只吃这三张表 + 版本号，不读文件、不联网）。
 *
 * 对应 PC `loaders/remote_data.load_role_data` 的返回值
 * `(characters, pools, pity_groups, remote_ok)`；移动端在此多带一个 [version]
 * （PC 由 GUI 从缓存 dict 另取），供设置页「数据版本」只读行显示（09 §3.4）。
 *
 * 三个集合的**迭代顺序**由缓存 JSON 的书写顺序决定（[MetaParser] 用 `LinkedHashMap`
 * 保序），使 UI 展示次序与 PC 一致。
 *
 * @param version 服务端 `/role` 下发的版本号，形如 `"1.0.6"`——**不带 `v` 前缀**
 *   （09 §3.4 写的 `v1.0.4` 与真实数据不符，以数据为准；是否补 `v` 由展示层决定）。
 *   缺失时为空串。
 */
data class Meta(
    val characters: Map<String, Character>,
    val pools: Map<String, Pool>,
    val pityGroups: Map<String, PityGroup>,
    val version: String = "",
)
