package com.jinlin.gacha.assistant.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TargetPackages] 单测 —— **纯 JVM**（`matches` / `pickEffective` 都不碰 `PackageManager`）。
 *
 * 规则是 2026-09-18 用户拍板的「**前缀 + 可选数字 + 必须带点**」：
 * `^com\.bmystu\.peng\d*\..+$`。
 *
 * 本测试把**反例**写全，因为放宽匹配的代价全在反例上：
 * - 放宽到 `startsWith("com.bmystu.peng")` 会误吞同发行商其它 App（`penguin` 系列）；
 * - 放宽到「不要求带点」会把裸根包名也算进来。
 *
 * ⚠️ 证据等级：本文件在**本机未执行**（仅开发环境无 Android 工具链）。判据本身不是推演 ——
 * 规则是纯字符串函数，但**「测试通过」这一结论须由全量环境跑 `:app:testDebugUnitTest` 给出**。
 *
 * 未覆盖的部分（需要真实 `PackageManager`，只在全量环境/实机可信）：
 * `detect()` 的枚举结果、`isInstalled()` 的可见性判定、`resolve()` 里「已选包是否仍安装」的过滤。
 *
 * **2026-09-18 追加（U11）**：三条打点文案也在这里测 —— `detectLogLine` / `nearMissLogLine` /
 * `resolveLogLine` 都是纯函数，不碰 `PackageManager`。⚠️ 它们**只覆盖文案拼装**；
 * 「日志真的写出去」仍须实机 logcat（或未来 `diagnose` 目录下的 .txt）来证。
 */
class TargetPackagesTest {

    @Test
    fun `official and known channel packages are matched`() {
        assertTrue(TargetPackages.matches(TargetPackages.OFFICIAL))
        assertTrue(TargetPackages.matches(TargetPackages.OPPO))
        // 小米 `com.bmystu.peng.mi`（2026-09-18 用户确认）—— **零数字**后缀，与官服 `.gw` 同构；
        // 它是「`\d*` 不能收紧成 `\d+`」这一条的实测样本。
        assertTrue(TargetPackages.matches(TargetPackages.XIAOMI))
        // B站 `com.bmystu.peng.bilibili`（2026-09-18 用户提供）—— 同属**零数字**后缀：
        // `<queries>` 里也补了它的 <package>，故这里连同常量一起钉住（防清单与规则脱节）。
        assertTrue(TargetPackages.matches(TargetPackages.BILIBILI))
    }

    @Test
    fun `numeric suffixed channels are matched without code change`() {
        // 规则预留：未来出现 peng2 / peng3 / 多位数渠道服时无需改码
        assertTrue(TargetPackages.matches("com.bmystu.peng2.vivo"))
        assertTrue(TargetPackages.matches("com.bmystu.peng3.huawei.gamecenter"))
        assertTrue(TargetPackages.matches("com.bmystu.peng10.mi"))
    }

    @Test
    fun `longer root words are rejected`() {
        // 关键反例：同发行商其它 App 的根包名是 peng 的「更长单词」，必须挡住
        assertFalse(TargetPackages.matches("com.bmystu.penguin"))
        assertFalse(TargetPackages.matches("com.bmystu.penguin.hd"))
        assertFalse(TargetPackages.matches("com.bmystu.pengx.cha"))
    }

    @Test
    fun `bare root without a following dot is rejected`() {
        // 用户明确要求「必须带点」：裸包名不接
        assertFalse(TargetPackages.matches(TargetPackages.ROOT))
        assertFalse(TargetPackages.matches("com.bmystu.peng1"))
        assertFalse(TargetPackages.matches("com.bmystu.peng2"))
    }

    @Test
    fun `truncated or foreign roots are rejected`() {
        assertFalse(TargetPackages.matches("com.bmystu.pen.gw"))
        assertFalse(TargetPackages.matches("com.bmystu.peng"))
        assertFalse(TargetPackages.matches("com.bmystu.peng."))
        assertFalse(TargetPackages.matches("xcom.bmystu.peng.gw"))
        assertFalse(TargetPackages.matches("com.other.peng.gw"))
        assertFalse(TargetPackages.matches(""))
    }

    @Test
    fun `padded values are rejected`() {
        // 包名来自 PackageManager 时不会有空白；但 settings.json 可能被手改，
        // 白名单场景下「带空格仍命中」比「不命中」危险，故钉住这条。
        assertFalse(TargetPackages.matches(" com.bmystu.peng.gw"))
        assertFalse(TargetPackages.matches("com.bmystu.peng.gw "))
    }

    @Test
    fun `nested suffix after the dot is allowed`() {
        // `.gw` 与 `1.nearme.gamecenter` 都是「点之后随便什么」——多段也命中
        assertTrue(TargetPackages.matches("com.bmystu.peng.gw.gamecenter"))
    }

    // —— decide：生效优先级（用户已选 > 自动检出**仅 1 个** > 官服兜底）+ 多命中**不猜** ——
    // 2026-09-18 晚由多选改单选：原 pickEffective 返回列表、检出多个就全用上（会让两个渠道
    // 同时进 tun，视图识别互相错配）⇒ 改为单值 Decision，多命中交给用户选。

    @Test
    fun `decide prefers the user selection`() {
        val got = TargetPackages.decide(
            picked = TargetPackages.OPPO,
            detected = listOf(TargetPackages.OFFICIAL, TargetPackages.OPPO),
            officialInstalled = true,
        )
        assertEquals(TargetPackages.Decision.Single(TargetPackages.OPPO), got)
    }

    @Test
    fun `decide adopts a single detected package`() {
        val got = TargetPackages.decide(
            picked = null,
            detected = listOf(TargetPackages.BILIBILI),
            officialInstalled = true,
        )
        assertEquals(TargetPackages.Decision.Single(TargetPackages.BILIBILI), got)
    }

    @Test
    fun `decide asks the user when several are detected`() {
        // ⚠️ 核心用例：检出多个**不替用户决定**（静默挑一个会让用户「以为抓的是 A、其实是 B」）
        val got = TargetPackages.decide(
            picked = null,
            detected = listOf(TargetPackages.OFFICIAL, TargetPackages.XIAOMI),
            officialInstalled = true,
        )
        assertEquals(TargetPackages.Decision.Multiple, got)
    }

    @Test
    fun `decide falls back to official when detection finds nothing`() {
        val got = TargetPackages.decide(
            picked = null,
            detected = emptyList(),
            officialInstalled = true,
        )
        assertEquals(TargetPackages.Decision.Single(TargetPackages.OFFICIAL), got)
    }

    @Test
    fun `decide reports none when nothing is installed`() {
        // None = 「目标游戏未安装」：调用侧必须据此拒绝启动抓包
        val got = TargetPackages.decide(
            picked = null,
            detected = emptyList(),
            officialInstalled = false,
        )
        assertEquals(TargetPackages.Decision.None, got)
    }

    @Test
    fun `decide falls back to detection when the picked package is gone`() {
        // `resolve` 已把「选定但已卸载」的包过滤成 null ⇒ 这里按「没选过」处理，回落到自动检出
        val got = TargetPackages.decide(
            picked = null,
            detected = listOf(TargetPackages.OFFICIAL),
            officialInstalled = true,
        )
        assertEquals(TargetPackages.Decision.Single(TargetPackages.OFFICIAL), got)
    }

    // —— U11 打点（2026-09-18）：三个纯函数 ——
    // ⚠️ 本组只覆盖**文案拼装**；「日志真的写出去」属 Android 侧，须实机 logcat / 未来 diagnose .txt。

    /** 造一枚候选（`Candidate` 是 data class，直接构造即可）。 */
    private fun cand(pkg: String, label: String): TargetPackages.Candidate =
        TargetPackages.Candidate(packageName = pkg, label = label)

    @Test
    fun `detectLogLine lists matched packages with their labels`() {
        val line = TargetPackages.detectLogLine(
            visible = 137,
            matched = listOf(
                cand(TargetPackages.OFFICIAL, "夜幕之下"),
                cand(TargetPackages.XIAOMI, "夜幕之下"),
            ),
        )
        assertTrue(line.contains("可见启动器应用 137 个"))
        assertTrue(line.contains("命中 2 个"))
        // 包名 + 应用名成对出现：多渠道显示名常一字不差，只有包名能区分
        assertTrue(line.contains("com.bmystu.peng.gw(夜幕之下)"))
        assertTrue(line.contains(TargetPackages.XIAOMI))
    }

    @Test
    fun `detectLogLine is an exact self contained line when nothing matched`() {
        // 整行钉死：规则原文就在日志里（读到日志的人不必回头翻源码），且「命中 0」不带任何列表
        assertEquals(
            "规则 ^com\\.bmystu\\.peng\\d*\\..+$ · 可见启动器应用 0 个 → 命中 0 个",
            TargetPackages.detectLogLine(visible = 0, matched = emptyList()),
        )
    }

    @Test
    fun `nearMissLogLine drops matched packages and foreign apps`() {
        val line = TargetPackages.nearMissLogLine(
            listOf("com.android.chrome", "com.tencent.mm", "com.bmystu.penguin", TargetPackages.OFFICIAL),
        )
        assertFalse(line.contains("com.android.chrome"))
        assertFalse(line.contains("com.tencent.mm"))
        // 命中规则的不算「未命中」样本，否则这条诊断行会自己打自己脸
        assertFalse(line.contains(TargetPackages.OFFICIAL))
        // 同发行商但根包名更长的（penguin）：规则挡对了，日志要留痕
        assertTrue(line.contains("com.bmystu.penguin"))
    }

    @Test
    fun `nearMissLogLine points at queries when nothing is visible`() {
        // 整行钉死：样本为空时必须点破「包看不见」这条可能，否则这条打点等于没写
        assertEquals(
            "可见列表里没有未命中的 com.bmystu 系列包 ⇒ 目标 App 未安装，或对 <queries> 不可见",
            TargetPackages.nearMissLogLine(listOf("com.android.chrome", "com.tencent.mm")),
        )
    }

    @Test
    fun `nearMissLogLine caps the sample at ten`() {
        val many = (1..15).map { "com.bmystu.other" + it.toString().padStart(2, '0') }
        val line = TargetPackages.nearMissLogLine(many)
        assertTrue(line.contains("包名 15 个"))
        assertTrue(line.contains("只列前 10"))
        assertTrue(line.contains("com.bmystu.other01"))
        assertTrue(line.contains("com.bmystu.other10"))
        assertFalse(line.contains("com.bmystu.other11"))
    }

    @Test
    fun `resolveLogLine is an exact line`() {
        // 整行钉死：三段（选定 / 检出 / 官服）与结论同行，才能对照「接不了管」的三种成因
        assertEquals(
            "用户未选定 / 自动检出 1 个 [com.bmystu.peng.gw] / 官服已装 true ⇒ 接管 1 个 [com.bmystu.peng.gw]",
            TargetPackages.resolveLogLine(
                picked = null,
                pickedInstalled = false,
                detected = listOf(TargetPackages.OFFICIAL),
                officialInstalled = true,
                decision = TargetPackages.Decision.Single(TargetPackages.OFFICIAL),
            ),
        )
    }

    @Test
    fun `resolveLogLine names the empty outcome`() {
        val line = TargetPackages.resolveLogLine(
            picked = null,
            pickedInstalled = false,
            detected = emptyList(),
            officialInstalled = false,
            decision = TargetPackages.Decision.None,
        )
        assertTrue(line.contains("接管 0 个"))
        // 与 UI 那句 Toast 同义 —— 日志要能直接对上用户看到的现象
        assertTrue(line.contains("未安装目标游戏"))
    }

    @Test
    fun `resolveLogLine surfaces a picked package that is no longer installed`() {
        val line = TargetPackages.resolveLogLine(
            picked = TargetPackages.XIAOMI,
            pickedInstalled = false,
            detected = emptyList(),
            officialInstalled = false,
            decision = TargetPackages.Decision.None,
        )
        // 用户选过、后来卸载了：不能静默丢，否则「我明明选了」无从解释
        assertTrue(line.contains("已不在设备上"))
        assertTrue(line.contains(TargetPackages.XIAOMI))
    }

    @Test
    fun `resolveLogLine explains the multiple-outcome`() {
        // 单选化的核心可观测点：日志必须说清「检出多个、需用户去选」，而不是含糊的「接管 0 个」
        val line = TargetPackages.resolveLogLine(
            picked = null,
            pickedInstalled = false,
            detected = listOf(TargetPackages.OFFICIAL, TargetPackages.BILIBILI),
            officialInstalled = true,
            decision = TargetPackages.Decision.Multiple,
        )
        assertTrue(line.contains("自动检出 2 个"))
        assertTrue(line.contains("需到设置页「接管范围」选一个"))
        assertTrue(line.contains("不替用户决定"))
    }
}
