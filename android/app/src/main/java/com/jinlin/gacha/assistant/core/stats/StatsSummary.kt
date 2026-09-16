package com.jinlin.gacha.assistant.core.stats

/**
 * 封面摘要行 —— Kotlin 镜像 PC `gui/pity_panel.py:417-454` 顶部摘要的**派生公式**。
 *
 * PC 里这行是 GUI 现算的（不在 `StatsEngine` 内），但它完全是纯计算 → 移动端把它
 * 提成一个纯函数，供 Tab1「保底总览」与 Tab3「统计页」共用且**可单测**。
 *
 * 原文案（PC）：
 * ```
 * 总抽数 {total}    6星 {six_total}    出货池 {shipped}/{total_pools}    6星率 {rate:.1f}%    不歪率 {up_rate}
 * ```
 *
 * **移动端未移植**：PC 的「手补 N」后缀——它依赖 `manual_bound_by_group`（手动补录
 * 边界），移动端暂无手动补录功能，故 [StatsSummary] 不含该字段。
 */
data class StatsSummary(
    /** 总抽数（= 记录总条数，含手动补录）。 */
    val total: Int,
    /** 6 星总数（各分组 `sixCount` 之和）。 */
    val sixTotal: Int,
    /** 出货池数（**至少出过一个 6 星**的池数，跨分组去重后按 per_pool 计数）。 */
    val shippedPools: Int,
    /** 卡池总数（= 元数据 pool 数 `pools.size`，**非**记录里出现过的池数）。 */
    val totalPools: Int,
    /** 6 星率（百分比数值，如 `1.4` 表示 1.4%）；`total==0` 时为 `0.0`。 */
    val sixRatePercent: Double,
    /** 总不歪率分子：小保底命中 UP 数（仅 `hasState` 组）。 */
    val nonWarpWins: Int,
    /** 总不歪率分母：全部 UP 出货数（仅 `hasState` 组）。 */
    val nonWarpAllUp: Int,
    /** 总不歪率（百分比数值）；`nonWarpAllUp==0` 时为 `null`（PC 显示 `-`）。 */
    val nonWarpRatePercent: Double?,
) {
    companion object {
        /**
         * 由统计引擎现算（只调一次 [StatsEngine.sharedPityTracking]，其输出同时喂给
         * 不歪率状态机 [computeNonWarpRates]，不重复计算）。
         */
        fun from(engine: StatsEngine): StatsSummary =
            from(
                total = engine.total,
                tracking = engine.sharedPityTracking(),
                totalPools = engine.pools.size,
            )

        /**
         * 由已算好的分片构造（便于 [StatsEngine] 之外复用，也便于单测）。
         *
         * @param total 总抽数
         * @param tracking [StatsEngine.sharedPityTracking] 的输出
         * @param totalPools 卡池总数（元数据 pool 数）
         */
        fun from(
            total: Int,
            tracking: Map<String, PityGroupStat>,
            totalPools: Int,
        ): StatsSummary {
            val sixTotal = tracking.values.sumOf { it.sixCount }
            val shipped = tracking.values.sumOf { g ->
                g.perPool.values.count { it.sixCount > 0 }
            }
            val rate = if (total != 0) sixTotal.toDouble() / total * 100.0 else 0.0

            // 总不歪率：仅 hasState 组（computeNonWarpRates 内部已跳过非状态组）
            val nonWarp = computeNonWarpRates(tracking)
            val totWins = nonWarp.values.sumOf { it.wins }
            val totUp = nonWarp.values.sumOf { it.allUp }

            return StatsSummary(
                total = total,
                sixTotal = sixTotal,
                shippedPools = shipped,
                totalPools = totalPools,
                sixRatePercent = rate,
                nonWarpWins = totWins,
                nonWarpAllUp = totUp,
                nonWarpRatePercent = if (totUp != 0) totWins.toDouble() / totUp * 100.0 else null,
            )
        }
    }
}
