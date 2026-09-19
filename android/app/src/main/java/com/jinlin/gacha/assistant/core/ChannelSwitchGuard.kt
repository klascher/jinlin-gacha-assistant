package com.jinlin.gacha.assistant.core

/**
 * 「换了渠道、但没换用户」的判定（渠道服支持，2026-09-18）—— **纯函数**，可纯 JVM 单测。
 *
 * ### 为什么需要它
 * 记录**不按渠道隔离**：`users/<id>.json` 的历史只按**用户**分开，记录本身没有渠道字段
 * （刻意的 —— `records` schema 要与 PC 逐字一致）。于是同一个用户先抓官服、再切渠道服，
 * 两边的抽卡记录会**混进同一份历史**，保底计数、非歪率、分布全都会串。
 *
 * ### 判定规则（只报「不同」，不报「相同」）
 * - 本次渠道在该账号历史里出现过 ⇒ **不提醒**（要么一直用同一个，要么早就混过了，再提醒是噪音）；
 * - 本次渠道不在历史里、且历史非空 ⇒ **提醒**（这次抓下去就会混）；
 * - 历史为空（新账号/首次抓）⇒ **不提醒**（还没有可混的东西）。
 *
 * ⚠️ 术语：这里的「账号」指**本 App 内的用户档案**（`ProfileStore`），**不是游戏账号** ——
 * 协议里读不到游戏账号标识（项目既定：不绑定游戏 uid），所以提示语必须说「用户」，
 * 说「账号」会让用户以为在说游戏里的号。
 */
object ChannelSwitchGuard {

    /**
     * 判定结果。
     *
     * @param warn 是否应提醒用户
     * @param otherChannels 该用户历史上抓过的**其它**渠道（用于提示语点名；已排序）
     */
    data class Verdict(val warn: Boolean, val otherChannels: List<String>)

    /**
     * @param currentPackage 本次将接管的渠道包名（null = 还没定下来 ⇒ 无法判定，不提醒）
     * @param historyPackages 该用户历史里的渠道标记（`HistoryStore.sourcePackages`）
     */
    fun evaluate(currentPackage: String?, historyPackages: Collection<String>): Verdict {
        if (currentPackage == null) return Verdict(warn = false, otherChannels = emptyList())
        if (historyPackages.isEmpty()) return Verdict(warn = false, otherChannels = emptyList())
        if (currentPackage in historyPackages) return Verdict(warn = false, otherChannels = emptyList())
        return Verdict(
            warn = true,
            otherChannels = historyPackages.filter { it != currentPackage }.sorted(),
        )
    }
}
