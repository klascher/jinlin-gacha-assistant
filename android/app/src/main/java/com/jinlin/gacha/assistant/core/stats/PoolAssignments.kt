package com.jinlin.gacha.assistant.core.stats

/**
 * 陌生卡池分组指派 —— Kotlin 逐字镜像 PC `gacha_exporter/storage/pool_assignments.py`
 * 的 `apply_pool_assignments` 半边。
 *
 * ### 为什么只移植「apply」半边
 * PC `pool_assignments.py` 一体两面：加载/落盘（`load_pool_assignments` /
 * `save_pool_assignments`）由移动端 `persistence.UnknownIdStore` 负责（文件位不同），
 * 本对象只保留**纯合成**的 `apply` —— 它决定统计引擎吃哪张 `pools` 表，纯函数、
 * 零 IO、零 Android 依赖，可 junit 对拍。
 *
 * ### 语义（与 PC 一致）
 * 陌生 pool_id（元数据/角色缓存未收录）默认兜底为独立卡池（solo）。实际新卡池几乎总是
 * UP 或示例保底组4（与其他池共享保底），故提供本地指派：把陌生池并入某个 `has_state` 共享组，
 * 统计时与组内其他池一起跑共享保底。**统计算法不变**——指派只是为陌生池合成一个带
 * `pityGroup` 的 [Pool] 注入 [StatsEngine] 的 `pools` 映射，分组解析
 * （[PoolGroups.resolveGroups]）自然消费。
 *
 * 仅对未收录的陌生池生效；元数据收录该池后真实配置自动覆盖，指派条目失效（无害）。
 */
object PoolAssignments {

    /** 合成陌生池的 order：排在组内所有已收录池之后（陌生池总是新池，展示在最下）。 */
    const val UNKNOWN_POOL_ORDER = 999

    /**
     * 把指派合入 pools 映射，返回新的映射（**不改动原 dict**）。
     *
     * 仅为「role.json 未收录 且 指向已定义 pity_group」的陌生池合成 Pool：
     * - `type` 取该组内已收录池的 type（如 UP/遴选），决定 `isFeatured` 等；
     * - `order` 给大值，组内展示排在已收录池之后。
     * 指向未定义组 / 已被元数据收录的条目直接忽略（收录后真实配置优先）。
     *
     * @param pools 元数据原生卡池表（role.json 收录）
     * @param pityGroups 元数据已定义的保底分组（id → [PityGroup]）
     * @param assignments 本地指派 { pool_id → group_id }
     */
    fun apply(
        pools: Map<String, Pool>,
        pityGroups: Map<String, PityGroup>,
        assignments: Map<String, String>,
    ): Map<String, Pool> {
        val effective = LinkedHashMap(pools)
        for ((pid, gkey) in assignments) {
            if (pid in effective || !pityGroups.containsKey(gkey)) continue
            val ref = pools.values.firstOrNull { it.pityGroup == gkey }
            val poolType = ref?.type ?: "UP"
            effective[pid] = Pool(
                id = pid,
                name = "未知($pid)",
                type = poolType,
                order = UNKNOWN_POOL_ORDER,
                pityGroup = gkey,
            )
        }
        return effective
    }
}