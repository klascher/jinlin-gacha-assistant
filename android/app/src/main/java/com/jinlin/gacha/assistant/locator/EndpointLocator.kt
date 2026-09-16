package com.jinlin.gacha.assistant.locator

/**
 * 抽卡服务器地址来源（可扩展抽象）。
 *
 * 把「服务器从哪里来」建模成可插拔接口，抓包层永远只消费它返回的端点集合，
 * **不硬编码 ip/port**。对应规划 §3.2：阶段 1 用「连接观察」定位；后续可替换为
 * 完整复刻 PC `AddressDiscovery`（全量抓包 + 特征识别）的实现，接同一接口。
 */
interface EndpointLocator {

    /** 已「确认」为抽卡服务器的端点集合——窄重组层只对该集合的连接做精细重组。 */
    fun confirmedEndpoints(): Set<Endpoint>

    /** 观测到的候选端点（本 uid 的全部公网 TCP 终点）——积累供定位/排查，定位过程用。 */
    fun candidates(): Set<Endpoint>

    /** 连接观察：每次看到目标进程的公网 TCP 终点都喂进来积累候选。 */
    fun onObserved(tcpDst: Endpoint)
}