package com.jinlin.gacha.assistant.vpn

import com.jinlin.gacha.assistant.core.CaptureLog
import com.jinlin.gacha.assistant.locator.Endpoint
import com.jinlin.gacha.assistant.locator.EndpointLocator
import java.util.concurrent.ConcurrentHashMap

/**
 * 接入层目标判定器：决定每个 IP 包的去向（对应规划 §2 接入层）。
 *
 * 方案 A 下全部游戏连接都转发（否则游戏联网失败）；「只盯抽卡端点」仅在切帧侧落实，
 * 由 `GachaRecognizer` 特征命中后 `EndpointLocator.confirm` 提升端点，正式切帧据此消费。
 * 本类不承担目标分类，只保留连接观察，把候选端点喂给定位器（M2 特征确认用）。
 */
class FlowMatcher(private val locator: EndpointLocator) {

    /**
     * 已打点端点（去重用，避免每个包都打 L3）。接入层自持去重与其定位职责解耦，不用改 locator 接口。
     *
     * **上限防护（07 质量遗留 #5，2026-09-10）**：原实现无上限——每个新公网 ip:port 永久留一条
     * Endpoint，长时间挂机 + CDN 多端点场景下单调增长。现达 [SEEN_MAX] 即整表清空重去重：
     * seen 只服务 L3 去重打点，清空的代价仅是极罕见情况下重复打一条日志，可接受。
     */
    private val seen = ConcurrentHashMap.newKeySet<Endpoint>()

    /**
     * 连接观察：把目标进程发往**公网**的 TCP 终点喂进候选池（仅 C->S 方向的 dst 才是
     * 服务器侧；Dst 过滤私网/回环/组播，避免把手机自己的回包端点混进候选，否则 S->C
     * 回包会被误判方向）。候选供「连接观察 + 特征确认」定位用（规划 §3.2）。
     */
    fun observe(pkt: ParsedIpPacket) {
        if (pkt.protocol == IPPacket.PROTO_TCP && isPublicIpv4(pkt.dstIp)) {
            val ep = Endpoint(pkt.dstIp, pkt.dstPort)
            if (seen.add(ep)) {
                if (seen.size > SEEN_MAX) {
                    seen.clear()
                    seen.add(ep)
                }
                // 07 设计 L3：新公网端点一次性提示（seen 去重，不重复刷）
                CaptureLog.i("FlowObs", "抓到 ${ep.ip}:${ep.port} 的流量（候选端点 #${seen.size}）")
            }
            locator.onObserved(ep)
        }
    }

    /** 仅把明显是公网 IPv4 的地址当候选（移动端一般处私网 10.x/192.168，双端可判方向）。 */
    private fun isPublicIpv4(ip: String): Boolean {
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (a, b) = octets[0] to octets[1]
        // 私网/回环/链路本地/组播/保留段一律排除
        if (a == 10 || a == 127 || a == 0) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 169 && b == 254) return false
        if (a in 224..255) return false
        return true
    }

    private companion object {
        /** L3 去重表上限：游戏场景远达不到（正常端点数 < 50）；达到即清空重去重。 */
        private const val SEEN_MAX = 512
    }
}