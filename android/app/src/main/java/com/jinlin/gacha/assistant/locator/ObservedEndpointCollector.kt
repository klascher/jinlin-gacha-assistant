package com.jinlin.gacha.assistant.locator

import java.util.concurrent.ConcurrentHashMap

/**
 * 阶段 1 轻量定位实现：**连接观察 + 特征确认**（对应规划 §3.2）。
 *
 * - 连接层：把目标进程建立的所有公网 TCP 终点收进候选池（登录即有，零操作）；
 * - 特征层：M2 接入后，对候选流跑 Kotlin 版 `looks_like_gacha`（信封 0x000c/0x0800
 *   + 可解出 pool_id），命中即 [confirm] 提升为「已确认」端点、持久化。
 *
 * [confirmedEndpoints] 为空时，抓包层把所有流量当「其它连接」透传（保住目标游戏
 * 网络正常，不被检测到异常），一旦确认端点后即走精细重组分支——自动定位、免来回切换。
 */
class ObservedEndpointCollector : EndpointLocator {

    private val candidateSet: MutableSet<Endpoint> = ConcurrentHashMap.newKeySet()
    private val confirmedSet: MutableSet<Endpoint> = ConcurrentHashMap.newKeySet()

    override fun confirmedEndpoints(): Set<Endpoint> = confirmedSet.toSet()

    override fun candidates(): Set<Endpoint> = candidateSet.toSet()

    override fun onObserved(tcpDst: Endpoint) {
        candidateSet.add(tcpDst)
    }

    /** M2 接入：把候选提升为已确认抽卡端点（特征确认 / 用户手动指定）。 */
    fun confirm(endpoint: Endpoint) {
        confirmedSet.add(endpoint)
    }

    /** 持久化（C/S -> App 本地存储）。阶段 1 内存态即可，阶段 2 落 SharedPreferences。 */
    fun persist() = Unit
}