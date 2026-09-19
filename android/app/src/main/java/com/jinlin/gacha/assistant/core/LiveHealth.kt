package com.jinlin.gacha.assistant.core

/**
 * 抓包数据健康度 —— U5「实时健康指示 + 首屏缺口拦截」的**唯一判据来源**（2026-09-18）。
 *
 * ### 为什么把判据抽成纯函数而不是写在 Compose 里
 *
 * 同一个判定要在**三个落点**用：抓包页实时点（🟢/🟡/🔴）、停止确认弹窗、记录页完整性卡
 * （🟢/🟡/⚪）。若各写一份，三处迟早漂移 —— 而这里判错的代价是「把正常重抓报成故障」
 * 或「真有缺口却一声不吭」（后者正是本设计要修的那个缺陷）。抽成纯函数后：
 * 判据只有一处、可用 `LiveStateTest` 逐例断言、UI 退化为「状态 → 颜色/文案」的映射表。
 *
 * ### 判据顺序本身就是正确性的一部分
 *
 * 从上往下**先强后弱**：先排除「根本没在采集」（⚪），再报「用户翻错列表」「读不到数据」
 * 这两个**确定性问题**（🔴），然后才是按位置分类的缺口（🔴 首屏 / 🟡 中段 / 🟡 尾部），
 * 最后才是「没有缺口但衔接无法校验」这种**软提示**（🟡）。
 *
 * ⚠️ **不要把 [HealthReason.UNCLEAR_HISTORY] 单独报红**：`!overlapped` 在完全正常的情况下
 * 也会成立（新账号首抓、攒抽后只翻第一页、清空后重抓）。它必须先过
 * `hadHistory` 这道闸（PC `main_window.py:807-815` 一直有、Android 移植时漏掉的那闸），
 * 且只报 🟡 —— 用户 2026-09-18 裁定原话：「可以报黄，但至少逻辑得判断下，
 * 不要让新号首抓也落到历史衔接不正确上」。
 */
enum class HealthState {
    /** 🟢 正常：在收数据、没有已知缺口。 */
    OK,

    /** 🟡 待补：有缺口但**能靠继续翻补上**，或衔接无法校验（软提示）。 */
    PENDING,

    /** 🔴 异常：确定出了问题（翻错列表 / 读不到数据 / 首屏缺口）。 */
    PROBLEM,

    /** ⚪ 未采集到判据：本场几乎什么都没收到 —— 「没有信号」不等于「数据完整」。 */
    UNCOLLECTED,
}

/** 造成当前 [HealthState] 的**主要成因**（决定 UI 用哪句文案）。 */
enum class HealthReason {
    /** 一条记录都没读到，也没采纳过「全部卡池」：还没开游戏 / 没翻页 / 抓包没起来。 */
    UNCOLLECTED,

    /** 翻错列表：丢弃过非「全部卡池」的页，且从未见到「全部卡池」请求。 */
    WRONG_LIST,

    /** 已采纳「全部卡池」，但一条记录都没读到（视图对但没数据流）。 */
    NO_DATA,

    /** A 类首屏缺口：缺的页早于本场已见的最小 offset —— 继续翻**拿不到**（页缓存）。 */
    HEAD_GAP,

    /** B 类中段空洞：缺口夹在已见页中间 —— 翻回去就能补。 */
    MID_GAP,

    /** C 类尾部未翻：缺口晚于已见的最大 offset —— 继续往下翻。 */
    TAIL_GAP,

    /** 有历史、本场也有新增，却一条都没撞上历史 ⇒ 新旧数据的衔接**无法校验**。 */
    UNCLEAR_HISTORY,

    /** 无异常。 */
    NONE,
}

/** 判据结果：状态 + 主要成因（两者一起决定抓包页点色与记录页卡片文案）。 */
data class Health(val state: HealthState, val reason: HealthReason)

object LiveHealth {

    /**
     * 由去重快照算出健康度。**纯函数**（无 IO、无平台依赖）⇒ 可在 JVM 单测里逐例构造。
     *
     * 输入全部来自 [DedupSnapshot]，其中 O(1) 的实时判据由 `ViewTracker` 的增量计数喂；
     * 缺口分类（`hasHeadGap` / `hasMidGap`）由 `snapshot()` 在**已有**的 `missingPages()`
     * 之上顺手算出，不新增一次 O(N) 调用。
     */
    fun evaluate(s: DedupSnapshot): Health {
        // ① 未采集：没读到任何记录，也没采纳过「全部卡池」，且没丢弃过页
        //    —— 这套组合的语义是「本场还什么都没发生」，不是「数据完整」。
        if (!s.allPoolsSeen && s.droppedPages == 0 && s.parsedCount == 0) {
            return Health(HealthState.UNCOLLECTED, HealthReason.UNCOLLECTED)
        }

        // ② 翻错列表：明确丢弃过非「全部卡池」的页，且从未见「全部卡池」
        //    —— 继续翻也不会收录，必须让用户切列表（这是确定性问题）。
        if (!s.allPoolsSeen && s.droppedPages > 0) {
            return Health(HealthState.PROBLEM, HealthReason.WRONG_LIST)
        }

        // ③ 视图对了但读不到记录（抓包链路故障 / 还没触发请求）
        if (s.allPoolsSeen && s.parsedCount == 0) {
            return Health(HealthState.PROBLEM, HealthReason.NO_DATA)
        }

        // ④ A 类首屏缺口：必报（本设计要修的原缺陷就是它被静默）
        //    判据是 O(1) 的 minSeenOffset > 0，见 ViewTracker 的增量计数。
        if (s.hasHeadGap) {
            return Health(HealthState.PROBLEM, HealthReason.HEAD_GAP)
        }

        // ⑤ B 类中段空洞：重叠与否都报（它翻回去就能补，与历史无关）
        if (s.hasMidGap) {
            return Health(HealthState.PENDING, HealthReason.MID_GAP)
        }

        // ⑥ C 类尾部未翻：未重叠时报黄；**已重叠则静默**（保持 2026-09-16 的修复
        //    「翻到历史了就不说没翻到」—— 那些缺页全是已落库旧历史，翻到底也生不出新数据）。
        if (s.missingPageCount > 0 && !s.overlapped) {
            return Health(HealthState.PENDING, HealthReason.TAIL_GAP)
        }

        // ⑦ 衔接未校验（PC 的重叠自检）：**必须先过 hadHistory 闸**，
        //    且要求本场确有新增（对齐 PC 的 total_added > 0）。
        if (s.hadHistory && !s.overlapped && s.newCount > 0) {
            return Health(HealthState.PENDING, HealthReason.UNCLEAR_HISTORY)
        }

        // ⑧ 其余（含「解析到数据但全被判重跳过」= 正常重抓）都是正常。
        //    ⚠️ 这条必须成立，否则正常重抓会被报成故障（DedupPipeline 的注释专为此写）。
        return Health(HealthState.OK, HealthReason.NONE)
    }
}

/** 便捷读法：`snapshot.health`。 */
val DedupSnapshot.health: Health get() = LiveHealth.evaluate(this)

/**
 * **本场已与历史衔接** —— 「翻到过重叠边界」且「会话开始前确有历史」（2026-09-19 实机反馈新增）。
 *
 * ### 为什么要单独立一个口径
 * 用户从**最新一页往旧**翻，翻到与历史重叠就停（正常用法：没人会每次翻到最后一页）。
 * 此时「本场已见 N 页 / 服务器一共 M 页」里的 `M − N`（看上去「还差 92 页」）**全都已在历史里**
 * —— 它们**不是缺口**，继续翻也只是复阅旧数据。
 *
 * 判据（[LiveHealth.evaluate] ⑥）早已按这个语义放过（`!overlapped` 时不报 C 类尾部缺口），
 * 但**展示层**当初没跟着走：记录页卡片的「已拿 15/107 页」、完整性子页的「缺口 · 92 页」与
 * 「回到「全部卡池」把上面列出的页翻出来」都还在按原始口径渲染 ⇒ 与「数据完整」自相矛盾，
 * 且把用户赶回去翻一堆本来就不缺的页（2026-09-19 实机反馈的原话）。
 *
 * ⇒ 凡「缺口 / 覆盖」类文案，一律先过这个闸：为真时**不报缺口、不劝继续翻**，
 * 改说「已接上历史，其余页历史中已有」。
 *
 * ⚠️ 两个「不是」：① 它**不代表**历史一定完整（历史自己可能有洞，本场无从判定）；
 * ② 它**不改变** A 类首屏缺口 / B 类中段空洞的判定（那两类是**本场**断档，必须照报）。
 */
val DedupSnapshot.gapCoveredByHistory: Boolean get() = overlapped && hadHistory
