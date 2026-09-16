package com.jinlin.gacha.assistant.vpn

import com.jinlin.gacha.assistant.locator.Endpoint
import com.jinlin.gacha.assistant.locator.ObservedEndpointCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FlowMatcher 过滤 + ObservedEndpointCollector confirm 幂等（T9，04 方案 §4）。
 *
 * 覆盖：私网/回环/链路本地/组播/保留段过滤（isPublicIpv4 全分支）、边界值
 * （172.16~31 私网 vs 172.32 公网）、非 TCP 忽略、候选去重、confirm 幂等 +
 * 未观测端点可直接 confirm + 并发 confirm 端点数一致。
 *
 * FlowMatcher 依赖 CaptureLog（android.util.Log 由 isReturnDefaultValues 变空操作），
 * 纯 JVM 可跑。
 */
class FlowMatcherTest {

    private val collector = ObservedEndpointCollector()
    private val matcher = FlowMatcher(collector)

    /** 只填 observe 消费的字段（protocol/dstIp/dstPort），其余置空。 */
    private fun pkt(proto: Int, dstIp: String, dstPort: Int): ParsedIpPacket = ParsedIpPacket(
        protocol = proto,
        srcIp = "10.8.0.2", dstIp = dstIp,
        srcPort = 44444, dstPort = dstPort,
        raw = ByteArray(0), length = 0,
        isTcp = proto == IPPacket.PROTO_TCP, isUdp = proto == IPPacket.PROTO_UDP,
    )

    private fun observeTcp(dstIp: String, dstPort: Int) = matcher.observe(pkt(IPPacket.PROTO_TCP, dstIp, dstPort))

    // ---- 过滤：应进候选池 ----

    @Test(timeout = 5_000)
    fun `f1 公网端点进候选池`() {
        observeTcp("203.0.113.7", 18085)
        assertEquals(setOf(Endpoint("203.0.113.7", 18085)), collector.candidates())
        assertTrue(collector.confirmedEndpoints().isEmpty())
    }

    @Test(timeout = 5_000)
    fun `f2 172-32 是公网 172-16 至 31 是私网`() {
        // 172.32 不在私网段（16..31）→ 公网进池
        observeTcp("172.32.0.1", 80)
        assertEquals(setOf(Endpoint("172.32.0.1", 80)), collector.candidates())
        // 私网段上下界 → 排除
        val c2 = ObservedEndpointCollector()
        val m2 = FlowMatcher(c2)
        m2.observe(pkt(IPPacket.PROTO_TCP, "172.16.0.1", 80))
        m2.observe(pkt(IPPacket.PROTO_TCP, "172.31.255.255", 80))
        assertTrue(c2.candidates().isEmpty())
    }

    // ---- 过滤：全排除分支 ----

    @Test(timeout = 5_000)
    fun `f3 私网回环链路本地组播保留段全排除`() {
        val excluded = listOf(
            "10.0.0.1",            // 10/8
            "10.255.255.255",
            "127.0.0.1",           // 回环
            "0.0.0.0",             // 0/8 保留
            "172.16.0.1",          // 172.16/12 下界
            "172.31.255.255",      // 172.16/12 上界
            "192.168.1.1",         // 192.168/16
            "169.254.1.1",         // 链路本地
            "224.0.0.1",           // 组播下界
            "239.255.255.255",     // 组播上界
            "240.0.0.1",           // 保留
            "255.255.255.255",     // 广播
        )
        for (ip in excluded) observeTcp(ip, 443)
        assertTrue("全部私网/特殊段都不应进候选池：${collector.candidates()}", collector.candidates().isEmpty())
    }

    @Test(timeout = 5_000)
    fun `f4 非 ipv4 点分格式与非法输入排除`() {
        // 不是 4 段 / 段非数字 → mapNotNull 过滤后 size != 4 → 排除
        observeTcp("2001:db8::1", 443)   // IPv6 字符串：split('.') 得 1 段
        observeTcp("1.2.3", 443)
        observeTcp("1.2.3.4.5", 443)
        observeTcp("a.b.c.d", 443)
        assertTrue(collector.candidates().isEmpty())
    }

    @Test(timeout = 5_000)
    fun `f5 非 tcp 协议忽略`() {
        matcher.observe(pkt(IPPacket.PROTO_UDP, "203.0.113.7", 53))
        assertTrue(collector.candidates().isEmpty())
    }

    @Test(timeout = 5_000)
    fun `f6 同端点重复观测 候选池仍只有一个`() {
        observeTcp("203.0.113.7", 18085)
        observeTcp("203.0.113.7", 18085)
        observeTcp("203.0.113.7", 18085)
        assertEquals(1, collector.candidates().size)
    }

    @Test(timeout = 10_000)
    fun `f7 端点数越过 seen 上限 512 清空路径不崩 候选池完整`() {
        // 07 质量遗留 #5 防护：600 个不同公网端点越过 SEEN_MAX=512，触发整表清空
        for (i in 1..600) {
            observeTcp("8.${(i ushr 8) and 0xff}.${i and 0xff}.1", 10000 + (i % 1000))
        }
        assertEquals("locator 必须收到全部端点（seen 清空不影响候选池）", 600, collector.candidates().size)
    }

    // ---- confirm 语义 ----

    @Test(timeout = 5_000)
    fun `c1 confirm 幂等 重复确认只有一个`() {
        observeTcp("203.0.113.7", 18085)
        val ep = Endpoint("203.0.113.7", 18085)
        collector.confirm(ep)
        collector.confirm(ep)
        collector.confirm(ep)
        assertEquals(setOf(ep), collector.confirmedEndpoints())
    }

    @Test(timeout = 5_000)
    fun `c2 未观测端点可直接 confirm（手动指定路径）`() {
        val manual = Endpoint("1.2.3.4", 9999)
        collector.confirm(manual)
        assertEquals(setOf(manual), collector.confirmedEndpoints())
        assertTrue("confirm 不要求先有候选", collector.candidates().isEmpty())
    }

    @Test(timeout = 5_000)
    fun `c3 candidates 与 confirmed 快照互不串改`() {
        observeTcp("203.0.113.7", 18085)
        val confirmed = Endpoint("203.0.113.7", 18085)
        collector.confirm(confirmed)
        // 快照返回副本：取快照后再新增/确认，旧快照不被污染（若是活视图则断言失败）
        val candSnap = collector.candidates()
        val confSnap = collector.confirmedEndpoints()
        collector.confirm(Endpoint("1.2.3.4", 9999))
        assertEquals(1, candSnap.size)
        assertEquals(1, confSnap.size)
        // confirm 不把新端点写进候选表（见 c2），仅确认表新增
        assertEquals(1, collector.candidates().size)
        assertEquals(2, collector.confirmedEndpoints().size)
    }

    @Test(timeout = 5_000)
    fun `c4 并发 confirm 同一端点 结果集合大小为 1`() {
        val ep = Endpoint("203.0.113.7", 18085)
        val threads = 16
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                ready.await()
                repeat(100) { collector.confirm(ep) }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(setOf(ep), collector.confirmedEndpoints())
    }
}
