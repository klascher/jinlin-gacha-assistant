package com.jinlin.gacha.assistant.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinlin.gacha.assistant.core.markdown.MiniMarkdown
import com.jinlin.gacha.assistant.core.markdown.MiniMarkdown.Node
import com.jinlin.gacha.assistant.core.markdown.MiniMarkdown.Span
import com.jinlin.gacha.assistant.ui.theme.JinlinColors

/**
 * 极简 Markdown 渲染（§15 §3-D）—— [MiniMarkdown.parse] 的中性结构 → Compose。
 *
 * 白名单 5 语法：`##`/`###` 标题（加粗加大）、`**粗体**`、`- 条目`（「• 」前缀，不做嵌套）、
 * `[文字](url)`（`LinkAnnotation.Url`，点击走 [openUrl] 开浏览器）、空行分段。
 * 白名单外标记已在解析层按纯文本保留 —— 渲染层只管把「能读但不好看」的文本画出来，不会崩。
 *
 * 三个消费方共用：更新弹窗（changelog）/ 公告弹窗 / 公告子页。
 */
@Composable
internal fun MiniMarkdownText(
    text: String,
    colors: JinlinColors,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nodes = remember(text) { MiniMarkdown.parse(text) }
    val linkStyle = SpanStyle(color = colors.gold)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (node in nodes) {
            when (node) {
                is Node.Heading -> Text(
                    text = inlineSpans(context, node.spans, linkStyle),
                    fontSize = if (node.level == 2) 17.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                is Node.Paragraph -> Text(
                    text = inlineSpans(context, node.spans, linkStyle),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = colors.onSurface,
                )
                is Node.ListItem -> Row {
                    Text(text = "• ", fontSize = 13.sp, color = colors.onSurface)
                    Text(
                        text = inlineSpans(context, node.spans, linkStyle),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 行内片段 → AnnotatedString（粗体加粗；链接走 `LinkAnnotation.Url`，点击开浏览器）。 */
@Composable
private fun inlineSpans(context: Context, spans: List<Span>, linkStyle: SpanStyle) = buildAnnotatedString {
    for (span in spans) {
        when (span) {
            is Span.Text -> append(span.text)
            is Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is Span.Link -> withLink(
                LinkAnnotation.Url(
                    span.url,
                    TextLinkStyles(style = linkStyle),
                    linkInteractionListener = { link ->
                        (link as? LinkAnnotation.Url)?.let { openUrl(context, it.url) }
                    },
                ),
            ) { append(span.text) }
        }
    }
}
