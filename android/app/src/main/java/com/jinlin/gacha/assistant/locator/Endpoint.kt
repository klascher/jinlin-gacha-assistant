package com.jinlin.gacha.assistant.locator

/**
 * 远端端点（抽卡服务器）。IP + 端口一体，**不写死任何具体值**——
 * 全部由 [EndpointLocator] 提供，端口/地址变更无需改抓包代码。
 */
data class Endpoint(val ip: String, val port: Int) {
    override fun toString(): String = "$ip:$port"
}