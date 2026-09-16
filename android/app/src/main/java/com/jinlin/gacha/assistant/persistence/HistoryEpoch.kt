package com.jinlin.gacha.assistant.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 历史**内容版本号**（进程级）—— 专治「清空 / 还原历史后页面不刷新」。
 *
 * ### 为什么需要它
 * 记录页与统计页都把「读盘结果」缓存在 `remember` 里，key 只含 `activeId` 与 `running`：
 *
 * - `Screens.kt` 记录页：`remember(prof.activeId, running) { HistoryStore(...).records }`
 * - `ui/stats/StatsProvider.kt`：`remember(meta, activeId, isRunning, uState) { … }`
 *
 * 「清空历史 / 还原历史」**不改变** `activeId`，抓包也没启停（`running` 不变）→ 四个 key
 * 全不变 → Compose 认为无需重算，**页面继续显示已被清掉的旧数据**（重建 Activity 才恢复）。
 * `HistoryStore` 本身是纯 JVM 类（不依赖 Android / 协程），不该由它反向通知 UI，故把
 * 「内容变过」这件事单独抽成这个版本号：
 *
 * - **写侧**：任何绕过常规刷新路径改动历史的操作（清空 / 还原）在完成后调 [bump]；
 * - **读侧**：把 [state] 加进 `remember` 的 key 里。
 *
 * 常规路径（开始/停止抓包）无需 bump —— 那时 `running` 翻转已足以触发重算。
 *
 * > 与 `UnknownIdStore` 同属「进程级 UI 联动小工具」，不放 `core/` 是因为它是 UI 关注点，
 * > 不属于协议 / 去重 / 统计任何一层。
 */
object HistoryEpoch {

    private val _value = MutableStateFlow(0L)

    /** 当前版本号（Compose 侧 `collectAsState()` 后作为 `remember` 的 key）。 */
    val state: StateFlow<Long> = _value.asStateFlow()

    /** 标记历史内容已变更（清空 / 还原后调用）。 */
    fun bump() {
        _value.value += 1
    }
}
