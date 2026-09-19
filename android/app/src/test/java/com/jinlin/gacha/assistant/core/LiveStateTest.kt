package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 健康度判据 JVM 对拍（U5 `10-去重模块设计.md` §17.4 / §17.10）。
 *
 * [LiveHealth] 是**三处落点的唯一判据来源**（抓包页实时点 / 停止确认弹窗 / 记录页完整性卡）。
 * 判错的两个方向都要付代价：
 * - **误报红**：正常重抓被说成「数据有缺口」⇒ 用户白折腾一次重登；
 * - **漏报**：真有缺口却一声不吭 ⇒ 这就是本设计要修的那个缺陷（用户自己比对游戏才发现少了）。
 *
 * 故这里把「什么情况算正常」逐例钉死 —— 尤其**「解析到数据但全被判重跳过」必须是 🟢**。
 *
 * ⚠️ 输入是 [DedupSnapshot]（构造是**纯数据**）。其中 `hasHeadGap` / `hasMidGap` 由
 * `RecordFrameConsumer.snapshot()` 用 O(1) 增量值算好后填进来，本测试直接构造该布尔值，
 * 分类本身的对拍在 `core/dedup/GapClassifyTest`。
 */
class LiveStateTest {

    /** 本场「全部卡池」已见 5 页 / 应有 5 页的干净快照（后面各例只改自己关心的字段）。 */
    private fun cleanSnapshot(
        parsedCount: Int = 25,
        newCount: Int = 25,
        missingPageCount: Int = 0,
        overlapped: Boolean = false,
        hadHistory: Boolean = true,
    ) = DedupSnapshot(
        allPoolsSeen = true,
        droppedPages = 0,
        parsedCount = parsedCount,
        newCount = newCount,
        overlapped = overlapped,
        missingPageCount = missingPageCount,
        hadHistory = hadHistory,
        gapSeenPages = 5,
        gapExpectedPages = 5,
        minSeenOffset = 0,
        maxSeenOffset = 20,
        authoritativeTotal = 25,
    )

    /** ① 什么都没发生时是 ⚪「未采集」，**不是** 🟢「数据完整」——没信号 ≠ 数据完整。 */
    @Test
    fun `nothing collected yields uncollected not ok`() {
        val h = DedupSnapshot().health
        assertEquals(HealthState.UNCOLLECTED, h.state)
        assertEquals(HealthReason.UNCOLLECTED, h.reason)
    }

    /**
     * ② **解析到数据但本场新增为 0（正常重抓）必须判 🟢** —— 防「每次重抓都报没抓到」。
     *
     * 用户翻回已抓过的页时，客户端会重发请求（限流重发 / 重进页面），响应里每条都被
     * 位置判重跳过 ⇒ `parsedCount > 0` 而 `newCount == 0`。这是**完全正常**的一轮。
     */
    @Test
    fun `repeated capture all deduped stays ok`() {
        val h = cleanSnapshot(parsedCount = 37, newCount = 0).health
        assertEquals(HealthState.OK, h.state)
        assertEquals(HealthReason.NONE, h.reason)
    }

    /** ③ A 类首屏缺口 ⇒ 🔴（本设计要修的原缺陷就是它被静默）。 */
    @Test
    fun `head gap is a problem`() {
        val h = cleanSnapshot(
            parsedCount = 20,
            newCount = 20,
            missingPageCount = 1,
        ).copy(
            hasHeadGap = true,
            headGapPages = listOf(0),
            minSeenOffset = 5,
            gapSeenPages = 4,
        ).health
        assertEquals(HealthState.PROBLEM, h.state)
        assertEquals(HealthReason.HEAD_GAP, h.reason)
    }

    /** ④ 翻错列表（丢弃过非「全部卡池」页、且从未见全部卡池）⇒ 🔴。 */
    @Test
    fun `wrong list is a problem`() {
        val h = DedupSnapshot(
            allPoolsSeen = false,
            droppedPages = 3,
            parsedCount = 0,
        ).health
        assertEquals(HealthState.PROBLEM, h.state)
        assertEquals(HealthReason.WRONG_LIST, h.reason)
    }

    /** ⑤ 视图对了但一条都没读到 ⇒ 🔴（抓包链路故障 / 还没触发请求）。 */
    @Test
    fun `all pools seen but no data is a problem`() {
        val h = DedupSnapshot(allPoolsSeen = true, parsedCount = 0).health
        assertEquals(HealthState.PROBLEM, h.state)
        assertEquals(HealthReason.NO_DATA, h.reason)
    }

    /** ⑥ 干净一轮（5/5 页、无缺口、覆阅既有历史）⇒ 🟢。 */
    @Test
    fun `clean pass is ok`() {
        // 干净一轮必须**撞上历史边界（overlapped=true）**才是 🟢：若 hadHistory=true 且
        // newCount>0 却 !overlapped，会被 ⑦ 的「衔接无法校验」判成 🟡（见 `unclear history…`）。
        // 覆阅既有历史的正常一轮 = 本场既有新增、也翻到过旧历史页 ⇒ overlapped=true。
        val h = cleanSnapshot(overlapped = true).health
        assertEquals(HealthState.OK, h.state)
        assertEquals(HealthReason.NONE, h.reason)
    }

    /**
     * ⑦ **发现 4：`hadHistory == false` 时绝不出现「历史衔接」判据**（本批唯一的判定性单测）。
     *
     * 新账号首抓 / 清空后重抓时，本场当然一条都撞不上历史（因为**根本没有历史**）。
     * PC `main_window.py:807-815` 的重叠自检有 `had_history` 第一道闸，Android 移植时漏掉
     * ⇒ 会误报。用户 2026-09-18 原话：「*可以报黄，但至少逻辑得判断下，不要让新号首抓
     * 也落到历史衔接不正确上*」。
     */
    @Test
    fun `no history never triggers unclear history`() {
        val h = cleanSnapshot(hadHistory = false, overlapped = false, newCount = 10).health
        assertEquals(HealthState.OK, h.state)
        assertNotEquals(HealthReason.UNCLEAR_HISTORY, h.reason)
    }

    /** ⑧ 有历史、有新增、却没撞上历史 ⇒ 🟡（衔接无法校验）——**必须先过 `hadHistory` 闸**。 */
    @Test
    fun `unclear history needs hadHistory gate and is only pending`() {
        val h = cleanSnapshot(hadHistory = true, overlapped = false, newCount = 10).health
        assertEquals(HealthState.PENDING, h.state)
        assertEquals(HealthReason.UNCLEAR_HISTORY, h.reason)

        // 同一条快照把 hadHistory 翻成 false ⇒ 立刻回到 🟢（这就是发现 4 的闸）
        val gated = cleanSnapshot(hadHistory = false, overlapped = false, newCount = 10).health
        assertEquals(HealthState.OK, gated.state)
    }

    /** ⑨ B 类中段空洞 ⇒ 🟡（翻回去就能补，与历史无关，重叠与否都报）。 */
    @Test
    fun `mid gap is pending even when overlapped`() {
        val h = cleanSnapshot(missingPageCount = 1, overlapped = true).copy(
            hasMidGap = true,
            midGapPages = listOf(10),
        ).health
        assertEquals(HealthState.PENDING, h.state)
        assertEquals(HealthReason.MID_GAP, h.reason)
    }

    /**
     * ⑩ C 类尾部未翻：未撞历史 ⇒ 🟡；**已撞历史（overlapped）⇒ 静默回 🟢**。
     *
     * 后半条是 2026-09-16 那次修复的语义（用户反馈「翻到历史了还提示缺页」）：
     * 此时缺的页全是已落库的旧历史页，再翻到底也生不出新数据，照旧报缺只会误报。
     */
    @Test
    fun `tail gap is pending only before reaching history`() {
        val fresh = cleanSnapshot(missingPageCount = 3, overlapped = false, hadHistory = false).health
        assertEquals(HealthState.PENDING, fresh.state)
        assertEquals(HealthReason.TAIL_GAP, fresh.reason)

        val reached = cleanSnapshot(missingPageCount = 3, overlapped = true, hadHistory = false).health
        assertEquals(HealthState.OK, reached.state)
        assertEquals(HealthReason.NONE, reached.reason)
    }

    /** ⑪ 判据顺序：首屏缺口优先于中段 / 尾部 —— 三条同时成立时报最该修的那条。 */
    @Test
    fun `head gap wins over mid and tail`() {
        val h = cleanSnapshot(missingPageCount = 3).copy(
            hasHeadGap = true,
            hasMidGap = true,
            headGapPages = listOf(0),
            midGapPages = listOf(10),
            tailGapPages = listOf(30),
            minSeenOffset = 5,
        ).health
        assertEquals(HealthState.PROBLEM, h.state)
        assertEquals(HealthReason.HEAD_GAP, h.reason)
    }

    /** ⑫ 判据顺序：翻错列表优先于「读不到数据」（用户第一步就该切回「全部卡池」）。 */
    @Test
    fun `wrong list wins over no data`() {
        val h = DedupSnapshot(allPoolsSeen = false, droppedPages = 2, parsedCount = 0).health
        assertEquals(HealthReason.WRONG_LIST, h.reason)
    }

    /**
     * ★ 2026-09-19 实机反馈新增：[DedupSnapshot.gapCoveredByHistory] —— 「已接上历史」的**唯一口径**。
     *
     * 用户正常用法是「从最新一页往下翻，撞上历史就停」（没人会每次翻到最后一页）⇒
     * 本场见 15 页 / 共 107 页，那 92 页**全在历史里**。判据（⑥ `!overlapped`）早已放过，
     * 但展示层当初没跟：曾出现「数据完整」+「已拿 15/107 页」+「缺口 92 页」+「回到全部卡池」
     * 四句互相打架的文案。⇒ 派生这个口径给三处 UI 统一用（记录页卡片 / 完整性子页 / 建议区）。
     *
     * 三个分支都要钉死：**只有「重叠 + 会话前有历史」才算接上历史**。
     */
    @Test
    fun `gap covered by history needs both overlap and prior history`() {
        // ① 重叠 + 有历史 ⇒ 已接上历史，且状态必须是 🟢（不得报 C 类尾部缺口）
        val yes = cleanSnapshot(overlapped = true, hadHistory = true, missingPageCount = 92)
        assertEquals(true, yes.gapCoveredByHistory)
        assertEquals(HealthState.OK, yes.health.state)

        // ② 会话前没有历史：`overlapped` 无意义（新账号首抓 / 清空后重抓不构成「接上历史」）
        val newAccount = cleanSnapshot(overlapped = true, hadHistory = false, missingPageCount = 92)
        assertEquals(false, newAccount.gapCoveredByHistory)

        // ③ 没重叠（压根没翻到历史）⇒ 那 92 页**确实**还没翻，必须照报（黄 = 继续翻就能补）
        val noOverlap = cleanSnapshot(overlapped = false, hadHistory = true, missingPageCount = 92)
        assertEquals(false, noOverlap.gapCoveredByHistory)
        assertEquals(HealthState.PENDING, noOverlap.health.state)
        assertEquals(HealthReason.TAIL_GAP, noOverlap.health.reason)
    }

    /**
     * ★ 接上历史**不能掩盖本场自己的断档**：A 类首屏 / B 类中段缺口照报。
     *
     * 这是 [DedupSnapshot.gapCoveredByHistory] 的**边界**：它只管「尾部未见页是否已在历史里」，
     * 与本场断档是两回事 —— 否则「翻到重叠」会变成一张万能免罪符。
     */
    @Test
    fun `head and mid gaps still reported after connecting to history`() {
        val covered = cleanSnapshot(overlapped = true, hadHistory = true, missingPageCount = 92)

        val head = covered.copy(hasHeadGap = true)
        assertEquals(HealthState.PROBLEM, head.health.state)
        assertEquals(HealthReason.HEAD_GAP, head.health.reason)

        val mid = covered.copy(hasMidGap = true)
        assertEquals(HealthState.PENDING, mid.health.state)
        assertEquals(HealthReason.MID_GAP, mid.health.reason)
    }
}
