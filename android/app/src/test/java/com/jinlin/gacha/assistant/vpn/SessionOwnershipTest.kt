package com.jinlin.gacha.assistant.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内存态的**归属判据**（[ownsSession]，2026-09-19 实机反馈）。
 *
 * ### 钉住的是什么
 * `GachaVpnService` 的 `records` / `dedupState` / `sessionStartedAt` 是**进程级单例**，
 * 语义上却属于**某一个账号**。原先没有归属标记 ⇒ 新增账号并切换后、**还没开始抓包**时，
 * 记录页显示的是上一个账号的记录、完整性卡显示上一个账号的信息（用户实测反馈）。
 *
 * 修法是「归属写进数据 + 消费侧一处判定」（见 [CaptureServiceState.sessionProfileId]），
 * 故这里逐例钉住这个判据 —— 它是「新账号不该看到别人数据」的唯一防线。
 *
 * ⚠️ 判据只回答「归属」，不回答「界面怎么显示」：消费侧拿到 `false` 后要用**空态**
 * （空记录 + 空 `DedupSnapshot`），而不是把别人的数据换个说法展示。
 */
class SessionOwnershipTest {

    @Test
    fun `session belongs to the profile that started it`() {
        val s = CaptureServiceState(running = true, sessionProfileId = "alice")
        assertTrue("本场由 alice 开抓 ⇒ alice 可见", s.ownsSession("alice"))
    }

    @Test
    fun `another profile never sees it`() {
        // 这正是本次修的缺陷：新增 bob 并切换后，bob 的历史为空，
        // 若不校验归属，alice 本场的记录会整批显示在 bob 的记录页上。
        val s = CaptureServiceState(running = true, sessionProfileId = "alice")
        assertFalse(s.ownsSession("bob"))
    }

    @Test
    fun `default state owns nothing`() {
        // 冷启动 / 停抓收尾后走 `_state.value = CaptureServiceState()`（全量复位）⇒ 谁的都不是。
        assertFalse(CaptureServiceState().ownsSession("alice"))
    }

    @Test
    fun `empty profile id owns nothing even if a session exists`() {
        // 极端兜底：任务里有会话、却没有当前账号（profiles.json 被手改坏等）
        // ⇒ 宁可显示「没抓到数据」，也不能把数据挂到无名账号头上。
        val s = CaptureServiceState(running = true, sessionProfileId = "alice")
        assertFalse(s.ownsSession(""))
    }

    @Test
    fun `empty session id owns nothing`() {
        assertFalse(CaptureServiceState(sessionProfileId = "").ownsSession("alice"))
    }

    @Test
    fun `ownership survives running flag flips`() {
        // running 与本判据无关：停抓那一刻 records 仍在内存里（下次开抓才清），
        // 但它必须仍然只对**开抓的那个账号**可见 —— 否则「停抓后切账号」又是同一个漏洞。
        val stopped = CaptureServiceState(running = false, sessionProfileId = "alice")
        assertTrue(stopped.ownsSession("alice"))
        assertFalse(stopped.ownsSession("bob"))
    }
}
