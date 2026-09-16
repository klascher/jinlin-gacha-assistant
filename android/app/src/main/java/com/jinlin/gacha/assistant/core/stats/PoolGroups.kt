package com.jinlin.gacha.assistant.core.stats

/**
 * 保底分组解析 —— Kotlin 逐字镜像 PC `gacha_exporter/loaders/pool_groups.py::resolve_groups`。
 *
 * 把每个 `pool_id` 解析到保底分组：`Pool.pityGroup` 指向 [pityGroups] 里已定义的组则采用；
 * **未配（空串）或引用未定义组 → 兜底 solo**（该池自成一组：`id="solo:<poolId>"`、
 * `hardPity=70`、`hasState=false`）。这样即使元数据缺 `pity_groups` 段（旧数据 /
 * 远程未更新）也能降级工作。
 *
 * 纯 JVM，零 Android 依赖。
 */
object PoolGroups {

    /** 兜底 solo 分组的默认硬保底（镜像 PC `DEFAULT_HARD_PITY`）。 */
    const val DEFAULT_HARD_PITY = 70

    /** 兜底 solo 分组默认不跑状态机（镜像 PC `DEFAULT_HAS_STATE`）。 */
    const val DEFAULT_HAS_STATE = false

    /**
     * 解析结果：`(poolToGroup, groupConfigs)`。
     *
     * @property poolToGroup `pool_id → group_id`（每个 pool 都有归属，兜底 solo 后不留空）
     * @property groupConfigs `group_id → PityGroup`（含兜底 solo 组的配置，可直接查）
     */
    data class Resolved(
        val poolToGroup: Map<String, String>,
        val groupConfigs: Map<String, PityGroup>,
    )

    /**
     * 解析每个 pool 的保底分组归属（镜像 PC `resolve_groups`）。
     *
     * > 与 PC 的唯一差异：PC 在引用未定义组时会打一条 `warning` 日志；移动端本包是纯 JVM
     * > 无日志设施，故省去——**判定结果完全一致**（同样是兜底 solo）。
     *
     * @param pools pool_id → [Pool]（[Pool.pityGroup] 指向组 key）
     * @param pityGroups group_id → [PityGroup]（元数据已定义的组）
     */
    fun resolveGroups(
        pools: Map<String, Pool>,
        pityGroups: Map<String, PityGroup>,
    ): Resolved {
        val poolToGroup = LinkedHashMap<String, String>()
        val groupConfigs = LinkedHashMap(pityGroups)

        for ((pid, pool) in pools) {
            val gkey = pool.pityGroup
            if (gkey.isNotEmpty() && pityGroups.containsKey(gkey)) {
                poolToGroup[pid] = gkey
            } else {
                val soloKey = "solo:$pid"
                poolToGroup[pid] = soloKey
                if (!groupConfigs.containsKey(soloKey)) {
                    groupConfigs[soloKey] = PityGroup(
                        id = soloKey,
                        label = pool.name,
                        hardPity = DEFAULT_HARD_PITY,
                        hasState = DEFAULT_HAS_STATE,
                    )
                }
            }
        }
        return Resolved(poolToGroup, groupConfigs)
    }
}
