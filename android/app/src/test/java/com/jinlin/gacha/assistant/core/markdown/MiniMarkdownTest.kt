package com.jinlin.gacha.assistant.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MiniMarkdown] 单测 —— §15 §3-D 白名单 5 语法逐条 + 白名单外按纯文本保留（不崩、不丢字）。
 */
class MiniMarkdownTest {

    // —— 块级 ——

    @Test
    fun `blank input returns empty`() {
        assertTrue(MiniMarkdown.parse(null).isEmpty())
        assertTrue(MiniMarkdown.parse("").isEmpty())
        assertTrue(MiniMarkdown.parse("  \n \n").isEmpty())
    }

    @Test
    fun `heading levels`() {
        val nodes = MiniMarkdown.parse("## 大标题\n### 小标题")
        assertEquals(
            listOf(
                MiniMarkdown.Node.Heading(2, listOf(MiniMarkdown.Span.Text("大标题"))),
                MiniMarkdown.Node.Heading(3, listOf(MiniMarkdown.Span.Text("小标题"))),
            ),
            nodes,
        )
    }

    @Test
    fun `list item strips dash`() {
        val nodes = MiniMarkdown.parse("- 第一条")
        assertEquals(
            listOf(MiniMarkdown.Node.ListItem(listOf(MiniMarkdown.Span.Text("第一条")))),
            nodes,
        )
    }

    @Test
    fun `blank lines separate paragraphs and produce no nodes`() {
        val nodes = MiniMarkdown.parse("第一段\n\n第二段")
        assertEquals(
            listOf(
                MiniMarkdown.Node.Paragraph(listOf(MiniMarkdown.Span.Text("第一段"))),
                MiniMarkdown.Node.Paragraph(listOf(MiniMarkdown.Span.Text("第二段"))),
            ),
            nodes,
        )
    }

    @Test
    fun `whitelist-external markers stay literal`() {
        // 表格 / 引用 / 代码块：不崩、不丢字，原样进 Text
        val nodes = MiniMarkdown.parse("> 引用\n| a | b |\n```code```")
        assertEquals(3, nodes.size)
        val texts = nodes.filterIsInstance<MiniMarkdown.Node.Paragraph>()
            .flatMap { it.spans }.filterIsInstance<MiniMarkdown.Span.Text>()
        assertTrue(texts.any { it.text.startsWith(">") })
        assertTrue(texts.any { it.text.startsWith("|") })
        assertTrue(texts.any { it.text.contains("code") })
    }

    // —— 行内：粗体 ——

    @Test
    fun `bold span`() {
        val nodes = MiniMarkdown.parse("前**加粗**后")
        assertEquals(
            listOf(
                MiniMarkdown.Node.Paragraph(
                    listOf(
                        MiniMarkdown.Span.Text("前"),
                        MiniMarkdown.Span.Bold("加粗"),
                        MiniMarkdown.Span.Text("后"),
                    )
                ),
            ),
            nodes,
        )
    }

    @Test
    fun `unclosed bold stays literal`() {
        val nodes = MiniMarkdown.parse("前**加粗")
        val spans = (nodes[0] as MiniMarkdown.Node.Paragraph).spans
        assertEquals(listOf(MiniMarkdown.Span.Text("前**加粗")), spans)
    }

    @Test
    fun `empty bold is dropped`() {
        // "****" 恰好是一对空粗体 → 丢弃不产生片段（"******" 则会剩一对落单的 ** 成文本）
        val nodes = MiniMarkdown.parse("****")
        val spans = (nodes[0] as MiniMarkdown.Node.Paragraph).spans
        assertTrue(spans.isEmpty())
    }

    // —— 行内：链接 ——

    @Test
    fun `link span`() {
        val nodes = MiniMarkdown.parse("见[使用说明](https://example.com/docs/使用说明-安卓.html)")
        assertEquals(
            listOf(
                MiniMarkdown.Node.Paragraph(
                    listOf(
                        MiniMarkdown.Span.Text("见"),
                        MiniMarkdown.Span.Link("使用说明", "https://example.com/docs/使用说明-安卓.html"),
                    )
                ),
            ),
            nodes,
        )
    }

    @Test
    fun `broken link stays literal`() {
        // 缺右括号 / url 含空格 → 整体按文本保留
        for (bad in listOf("看[这里](https://a b)", "看[这里](https://a", "看[这里] 不配对")) {
            val nodes = MiniMarkdown.parse(bad)
            val spans = (nodes[0] as MiniMarkdown.Node.Paragraph).spans
            assertTrue("「$bad」应全为文本", spans.all { it is MiniMarkdown.Span.Text })
        }
    }

    // —— 混排 ——

    @Test
    fun `bold and link mixed keeps order`() {
        val nodes = MiniMarkdown.parse("**A** [B](https://x) **C**")
        val spans = (nodes[0] as MiniMarkdown.Node.Paragraph).spans
        assertEquals(
            listOf(
                MiniMarkdown.Span.Bold("A"),
                MiniMarkdown.Span.Text(" "),
                MiniMarkdown.Span.Link("B", "https://x"),
                MiniMarkdown.Span.Text(" "),
                MiniMarkdown.Span.Bold("C"),
            ),
            spans,
        )
    }
}
