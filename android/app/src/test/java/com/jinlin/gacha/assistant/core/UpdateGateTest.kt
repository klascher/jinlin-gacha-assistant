package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UpdateGate] 单测 —— 逐条对齐 PC `__main__.py::_maybe_check_update`（§15 §3-C「语义照搬 PC」）+
 * 安卓专属死锁守卫（force + ready=false → 降级 prompt，不拦抓包）。
 */
class UpdateGateTest {

    private val cur = "0.1.0"

    private fun decide(
        latest: String? = "0.2.0",
        type: String? = "prompt",
        ready: Boolean = true,
        ack: String? = null,
    ) = UpdateGate.decide(latest, cur, type, ready, ack)

    // —— 无新版 ——

    @Test
    fun `same version is up to date`() {
        assertEquals(UpdateGate.Decision.UpToDate, decide(latest = "0.1.0"))
    }

    @Test
    fun `older latest is up to date`() {
        assertEquals(UpdateGate.Decision.UpToDate, decide(latest = "0.0.9"))
    }

    @Test
    fun `illegal or missing latest is up to date`() {
        // 判不出 = 不更新（宁可漏提示，不可误提示）
        assertEquals(UpdateGate.Decision.UpToDate, decide(latest = null))
        assertEquals(UpdateGate.Decision.UpToDate, decide(latest = "garbage"))
    }

    // —— silent ——

    @Test
    fun `silent never prompts or banners`() {
        assertEquals(UpdateGate.Decision.Silent, decide(type = "silent"))
        // 即使有热修后缀新版也静默（设置页手动检查仍能看到）
        assertEquals(UpdateGate.Decision.Silent, decide(latest = "0.1.0-20260901rc1", type = "silent"))
    }

    // —— prompt ——

    @Test
    fun `prompt shows dialog when not acknowledged`() {
        val d = decide(type = "prompt", ack = null)
        assertEquals(UpdateGate.Decision.Prompt(showDialog = true, ready = true), d)
    }

    @Test
    fun `prompt suppresses dialog for acknowledged version`() {
        val d = decide(type = "prompt", ack = "0.2.0")
        assertEquals(UpdateGate.Decision.Prompt(showDialog = false, ready = true), d)
    }

    @Test
    fun `new version re-prompts after acknowledged`() {
        val d = decide(latest = "0.3.0", type = "prompt", ack = "0.2.0")
        assertEquals(UpdateGate.Decision.Prompt(showDialog = true, ready = true), d)
    }

    @Test
    fun `prompt with unready package still prompts but flags not ready`() {
        val d = decide(type = "prompt", ready = false)
        assertEquals(UpdateGate.Decision.Prompt(showDialog = true, ready = false), d)
    }

    @Test
    fun `unknown update_type falls back to prompt`() {
        // PC 同款兜底：非 silent/force 一律按 prompt（宁少打扰，不误强拦）
        assertEquals(
            UpdateGate.Decision.Prompt(showDialog = true, ready = true),
            decide(type = "weird"),
        )
        assertEquals(
            UpdateGate.Decision.Prompt(showDialog = true, ready = true),
            decide(type = null),
        )
    }

    // —— force ——

    @Test
    fun `force with ready package blocks capture and always shows dialog`() {
        // force 不看 acknowledged：每次启动都弹（PC 同款）
        assertEquals(UpdateGate.Decision.ForceBlocking, decide(type = "force", ack = "0.2.0"))
    }

    @Test
    fun `force without ready package degrades to prompt and never blocks`() {
        // 死锁守卫：包没传上去就不拦抓包（误拦卡死全体用户 > 漏拦少一次强制）
        assertEquals(
            UpdateGate.Decision.Prompt(showDialog = true, ready = false),
            decide(type = "force", ready = false),
        )
        // 已确认过该版本时降级形态也不弹窗（prompt 行为完整降级）
        assertEquals(
            UpdateGate.Decision.Prompt(showDialog = false, ready = false),
            decide(type = "force", ready = false, ack = "0.2.0"),
        )
    }

    // —— 热修后缀纳入判断 ——

    @Test
    fun `suffix-only bump counts as newer`() {
        val d = decide(latest = "0.1.0-20260901rc1", type = "force")
        assertEquals(UpdateGate.Decision.ForceBlocking, d)
    }
}
