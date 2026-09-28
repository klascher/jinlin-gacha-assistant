package com.jinlin.gacha.assistant.core.markdown

/**
 * 极简 Markdown 白名单解析器（**纯函数**，输出与 Compose 无关的中性结构 ⇒ 纯 JVM 可单测）。
 *
 * 背景（§15 设计稿 §3-D）：PC 用 `QTextBrowser.setMarkdown`（全量 Markdown）；
 * 安卓工程无任何 Markdown 库且明确省依赖 ⇒ 端内极简渲染器，语法白名单锁死 5 种，
 * 并把它写成公告/更新说明的**书写规范**（`server_admin` 写入提示同源约束）：
 *
 * | 语法 | 渲染 |
 * |---|---|
 * | `## 标题` / `### 标题` | 标题（加粗加大，UI 层决定字号） |
 * | `**粗体**` | 加粗 |
 * | `- 条目` | 列表项（UI 层加「• 」前缀；不做嵌套缩进） |
 * | `[文字](url)` | 链接（UI 层接 `openUrl()`） |
 * | 空行 | 段落分隔 |
 *
 * **白名单外的标记按纯文本原样显示**（`>` 引用、表格、图片、代码块）——
 * 退化成「能读但不好看」，不崩、不丢字。配套规则：公告/更新说明正文
 * **不用表格 / 图片 / 代码块**（这 3 种在 PC 上好看，在安卓上会露标记）。
 */
object MiniMarkdown {

    // —— 中性输出结构 ——

    /** 块级节点。 */
    sealed interface Node {
        /** `##` / `###` 标题；[level] 为井号数（2 或 3）。 */
        data class Heading(val level: Int, val spans: List<Span>) : Node

        /** 普通段落（一个非空行 = 一段）。 */
        data class Paragraph(val spans: List<Span>) : Node

        /** `- ` 列表项（不做嵌套缩进）。 */
        data class ListItem(val spans: List<Span>) : Node
    }

    /** 行内片段。 */
    sealed interface Span {
        /** 普通文本（含白名单外标记的原样文字）。 */
        data class Text(val text: String) : Span

        /** `**粗体**`；未闭合的 `**` 按普通文本保留。 */
        data class Bold(val text: String) : Span

        /** `[文字](url)`；url 为空 / 含空白 / 括号不配对时整体按普通文本保留。 */
        data class Link(val text: String, val url: String) : Span
    }

    // —— 解析 ——

    /**
     * 解析公告/更新说明文本。空输入 / 纯空白输入返回空列表。
     * 每个非空行产出一个块级节点；空行只作分隔，不产生节点。
     */
    fun parse(text: String?): List<Node> {
        if (text.isNullOrBlank()) return emptyList()
        val nodes = ArrayList<Node>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("## ") -> nodes.add(Node.Heading(2, parseSpans(line.substring(3))))
                line.startsWith("### ") -> nodes.add(Node.Heading(3, parseSpans(line.substring(4))))
                line.startsWith("- ") -> nodes.add(Node.ListItem(parseSpans(line.substring(2))))
                else -> nodes.add(Node.Paragraph(parseSpans(line)))
            }
        }
        return nodes
    }

    // —— 行内解析 ——

    /**
     * 行内解析：扫描最近的 `**`（粗体起点）或 `[`（链接起点），二者取更靠前者；
     * 未闭合/非法结构按字面文本保留（不丢字、不崩）。
     */
    private fun parseSpans(line: String): List<Span> {
        val spans = ArrayList<Span>()
        var i = 0
        val plain = StringBuilder()
        while (i < line.length) {
            val boldStart = line.indexOf("**", i)
            val linkStart = line.indexOf('[', i)
            // 两者都找到取更靠前者；都找不到为 -1（'*' 与 '[' 是不同字符，起点不会重合）
            val next = when {
                boldStart >= 0 && (linkStart < 0 || boldStart < linkStart) -> boldStart
                else -> linkStart
            }
            if (next < 0) {
                plain.append(line, i, line.length)
                break
            }
            if (next == boldStart) {
                val close = line.indexOf("**", next + 2)
                if (close >= 0) {
                    if (next > i) plain.append(line, i, next)
                    flush(plain, spans)
                    val body = line.substring(next + 2, close)
                    if (body.isNotEmpty()) spans.add(Span.Bold(body))
                    i = close + 2
                    continue
                }
            } else {
                val link = parseLink(line, next)
                if (link != null) {
                    if (next > i) plain.append(line, i, next)
                    flush(plain, spans)
                    spans.add(Span.Link(link.first, link.second))
                    i = link.third
                    continue
                }
            }
            // 未闭合/非法：[i, next] 整段（含标记字符）按普通文本保留，继续扫描
            plain.append(line, i, next + 1)
            i = next + 1
        }
        flush(plain, spans)
        return spans
    }

    /** 解析 `[文字](url)`；成功返回 (label, url, end=消费到的下标)，失败返回 null。 */
    private fun parseLink(line: String, start: Int): Triple<String, String, Int>? {
        val closeBracket = line.indexOf(']', start + 1)
        if (closeBracket < 0 || closeBracket + 1 >= line.length || line[closeBracket + 1] != '(') {
            return null
        }
        val closeParen = line.indexOf(')', closeBracket + 2)
        if (closeParen < 0) return null
        val label = line.substring(start + 1, closeBracket)
        val url = line.substring(closeBracket + 2, closeParen).trim()
        // url 含空白或为空 → 多半是写坏的普通文本，按字面保留（不是合法链接）
        if (label.isEmpty() || url.isEmpty() || url.any { it.isWhitespace() }) return null
        return Triple(label, url, closeParen + 1)
    }

    private fun flush(plain: StringBuilder, spans: ArrayList<Span>) {
        if (plain.isNotEmpty()) {
            spans.add(Span.Text(plain.toString()))
            plain.clear()
        }
    }
}
