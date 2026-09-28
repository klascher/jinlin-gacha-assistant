package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.txt` 诊断报告渲染的 JVM 对拍（U11；`mobile/docs/10-去重模块设计.md` 16.4 节）。
 *
 * ### 为什么这份测试值得写
 *
 * 这个 `.txt` 是 `CaptureLog` 内存环的**唯一读者** —— 在它之前，环里的打点只有 logcat
 * 一个出口：用户能把日志交出来的唯一办法是拿 USB 线 + adb。所以报告本身是**用户反馈的
 * 载体**，它的失败模式不是「程序崩」，而是**用户把文件发过来、作者却看不出所以然**：
 * - 段落**缺失**和段落**写「未接入」**是两回事（前者像 bug，后者是诚实的边界声明）；
 * - `pcap` 落盘失败时**留白**与**写明未生成**也是两回事（「一个包都没抓到」恰恰是
 *   最需要这份报告的场景）；
 * - 行尾若是 CRLF，跨机器对拍就会飘 —— 故这里连**行尾**也钉住。
 *
 * ⚠️ 只测**纯函数** [DiagnoseDumper.renderReport]：不碰文件 IO、不碰 Android `Context`。
 * 写盘那半（`writeReport` / 与 pcap 成对产出）**只能在构建机 / 实机上验**，
 * 本测试**不能**替代它 —— 证据等级不同。
 */
class DiagnoseReportTest {

    private val stats = DiagnoseDumper.Stats(
        serverFrames = 12,
        gachaHits = 10,
        clearedTails = 3,
        droppedInputs = 1,
        feedErrors = 0,
        skippedGaps = 2,
        sweptFlows = 4,
    )

    private fun render(
        reason: String = "manual",
        pcapName: String? = "dump_manual_20260918_120000.pcap",
        sections: List<Pair<String, String>> = emptyList(),
        logLines: List<String> = listOf("12:00:00 GachaVpn  去重会话开始：历史 128 条"),
        logCapacity: Int = 200,
    ): String = DiagnoseDumper.renderReport(
        reason = reason,
        pcapName = pcapName,
        generatedAt = "2026-09-18 12:00:12",
        packets = 137,
        stats = stats,
        sections = sections,
        logLines = logLines,
        logCapacity = logCapacity,
    )

    /** ① 报告自带身份与成对关系 —— 用户发来时得能对上同目录的 pcap。 */
    @Test
    fun `report declares itself and names the paired pcap`() {
        val t = render()
        assertTrue(t.startsWith("# 金鳞抽卡导出小助手 · 诊断报告"))
        assertTrue(t.contains("[触发]"))
        assertTrue(t.contains("pcap 文件: dump_manual_20260918_120000.pcap"))
        assertTrue(t.contains("生成时刻: 2026-09-18 12:00:12"))
    }

    /** ② pcap 落盘失败时**写明**，不留白、不静默 —— 报告仍有效，这是它的价值时刻。 */
    @Test
    fun `report says the pcap is missing instead of leaving it blank`() {
        val t = render(pcapName = null)
        assertTrue(t.contains("pcap 文件: 未生成"))
        assertTrue(t.contains("本报告仍然有效"))
    }

    /** ③ 计数与切分统计全覆盖 —— 「隧道开着但没抓到」全靠这几个数字分辨。 */
    @Test
    fun `report prints packet count and every reassembly stat`() {
        val t = render()
        assertTrue(t.contains("已观察 IP 包: 137"))
        for (field in listOf(
            "serverFrames=12", "gachaHits=10", "clearedTails=3",
            "droppedInputs=1", "feedErrors=0", "skippedGaps=2", "sweptFlows=4",
        )) {
            assertTrue("报告里缺少切分统计字段 $field", t.contains(field))
        }
    }

    /** ④ 钩子未接入时**写明「未接入」**（含怎么补），不留空白冒充「无异常」。 */
    @Test
    fun `report explains itself when no session context is hooked in`() {
        val t = render(sections = emptyList())
        assertTrue(t.contains("[会话 / 完整性 / 合并]"))
        assertTrue(t.contains("本次未接入"))
        assertTrue(t.contains("点「抓诊断」之前做了什么操作"))
    }

    /** ⑤ 钩子给了段落就**按给定顺序原样输出**，标题不改写、正文不吞行。 */
    @Test
    fun `report prints hooked sections in the given order verbatim`() {
        val sections = listOf(
            "会话" to "账号: acc-1\n接管包名: com.bmystu.peng.gw",
            "完整性" to "健康判定: OK / NONE\n仍缺 0 页",
        )
        val t = render(sections = sections)
        // 不含空段的占位文案（说明真的用了钩子）
        assertTrue(t.contains("[会话]"))
        assertTrue(t.contains("[完整性]"))
        assertFalse(t.contains("本次未接入"))
        // 标题顺序 = 入参顺序
        assertTrue(t.indexOf("[会话]") < t.indexOf("[完整性]"))
        // 正文逐行保留（多行不折成一行）
        assertTrue(t.contains("账号: acc-1\n接管包名: com.bmystu.peng.gw"))
        // 段落正文与日志段之间有明确分界
        assertTrue(t.contains("健康判定: OK / NONE\n仍缺 0 页\n\n[日志]"))
    }

    /** ⑥ 段落正文末尾多余的换行不该在报告里留出空段。 */
    @Test
    fun `report trims trailing newlines of a hooked section body`() {
        val t = render(sections = listOf("会话" to "账号: acc-1\n\n"))
        assertTrue(t.contains("[会话]\n账号: acc-1\n\n[日志]"))
    }

    /** ⑦ 日志为空时**写明「（无）」** —— 空段与「没这一段」在排障时是两回事。 */
    @Test
    fun `report says the log is empty rather than omitting the section`() {
        val t = render(logLines = emptyList())
        assertTrue(t.contains("[日志]"))
        assertTrue(t.contains("（无）"))
    }

    /** ⑧ 日志上限取自 `CaptureLog.capacity()`（一处定义），报告文案跟着走、不写死。 */
    @Test
    fun `report quotes whatever log capacity it was given`() {
        assertTrue(render(logCapacity = 200).contains("最多 200 行"))
        assertTrue(render(logCapacity = 500).contains("最多 500 行"))
    }

    /** ⑨ 日志行**逐字保留**（含方括号、等号、中文标点），报告结构不被内容破坏。 */
    @Test
    fun `report keeps log lines verbatim`() {
        val line = "12:00:03 TargetPkg 规则 ^com\\.bmystu\\.peng\\d*\\..+\$ · 可见启动器应用 137 个 → 命中 2 个 [a(b)、c]"
        val t = render(logLines = listOf(line))
        assertTrue(t.contains(line))
        // 日志段之后仍只有日志本身：以日志行收尾
        assertTrue(t.trimEnd('\n').endsWith("命中 2 个 [a(b)、c]"))
    }

    /** ⑩ 已知触发原因给人话标签，**未知原因原样露出**（不折成「其他」把信息吞掉）。 */
    @Test
    fun `report maps known triggers and keeps unknown ones verbatim`() {
        assertTrue(render(reason = "manual").contains("触发原因: 用户手动触发（通知栏「抓诊断」）"))
        assertTrue(render(reason = "auto").contains("触发原因: 自动判据命中"))
        assertTrue(render(reason = "something_new").contains("触发原因: something_new"))
    }

    /**
     * ⑪ 行尾固定 `LF` 且以换行收尾。
     *
     * 不用 `appendLine`（它取平台行分隔符 —— 在 Windows 上跑单测会变 CRLF，断言随机器而变）。
     */
    @Test
    fun `report uses lf endings and ends with a newline`() {
        val t = render(sections = listOf("会话" to "账号: acc-1"))
        assertFalse("报告里不应出现 CR", t.contains("\r"))
        assertTrue(t.endsWith("\n"))
        assertEquals(t.trimEnd('\n').split("\n").size, t.count { it == '\n' })
    }
}
