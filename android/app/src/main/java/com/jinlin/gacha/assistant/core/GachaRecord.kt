package com.jinlin.gacha.assistant.core

/**
 * 单条抽卡记录 —— Kotlin 镜像 PC `gacha_exporter/models.py::GachaRecord`（阶段2 记录展示层）。
 *
 * 本批只做**切分落地（帧 → 记录 → 记录页展示）**，不引入去重/保底统计：一个抓包里
 * 同一个十连会被多个视图多次冲到，这里原样逐帧追加，不去重、不合并（重复属预期，
 * 待后续批再接去重账本）。`batchSeq`/`positionInBatch` 为解析辅助字段，暂不参与语义。
 */
data class GachaRecord(
    val poolId: String,
    val itemId: String,
    val timestamp: Long,            // 毫秒时间戳
    val batchSeq: Int = 0,          // 解析辅助；「越小越新」（后续保底排序依赖此约定）
    val positionInBatch: Int = 0,   // 解析辅助；视图内绝对序号 0=最新一条
)