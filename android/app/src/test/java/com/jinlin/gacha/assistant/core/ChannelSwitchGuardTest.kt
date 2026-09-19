package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChannelSwitchGuard] 单测 —— **纯 JVM**（不碰 Context / PackageManager / 文件）。
 *
 * 覆盖「换渠道但没换用户」的四种判定分支 + 术语口径（`otherChannels` 是「其它渠道」，
 * 不含本次渠道 —— 提示语要拿它点名）。
 */
class ChannelSwitchGuardTest {

    private val gw = "com.bmystu.peng.gw"
    private val mi = "com.bmystu.peng.mi"

    @Test
    fun `no warning when the channel is already in history`() {
        // 一直用同一个渠道：不提醒（提醒了是噪音）
        val v = ChannelSwitchGuard.evaluate(gw, listOf(gw))
        assertFalse(v.warn)
        assertTrue(v.otherChannels.isEmpty())
    }

    @Test
    fun `no warning when history is empty`() {
        // 新用户/首次抓：还没有可混的东西
        val v = ChannelSwitchGuard.evaluate(gw, emptySet())
        assertFalse(v.warn)
        assertTrue(v.otherChannels.isEmpty())
    }

    @Test
    fun `no warning when the channel is undecided`() {
        // 渠道还没定下来（检出多个、用户未选）⇒ 无从判定，交给选择流程处理
        val v = ChannelSwitchGuard.evaluate(null, listOf(mi))
        assertFalse(v.warn)
    }

    @Test
    fun `warns when switching to a channel the profile never captured`() {
        val v = ChannelSwitchGuard.evaluate(mi, listOf(gw))
        assertTrue("换渠道且该用户抓过别的渠道 ⇒ 必须提醒", v.warn)
        assertEquals(listOf(gw), v.otherChannels)
    }

    @Test
    fun `other channels are the previously captured ones, sorted`() {
        // 本次 = B站渠道（历史里没有）⇒ 告警，且 otherChannels 不含本次渠道、顺序稳定
        val v = ChannelSwitchGuard.evaluate("com.bmystu.peng.bilibili", listOf(mi, gw))
        assertTrue(v.warn)
        assertEquals(listOf(gw, mi), v.otherChannels)
    }

    @Test
    fun `warning is off once the channel has been captured before`() {
        // 已经混过（历史里两个渠道都在）⇒ 再切回来不再提醒：此时提醒与否都改变不了既有事实
        val v = ChannelSwitchGuard.evaluate(gw, listOf(gw, mi))
        assertFalse(v.warn)
    }
}
