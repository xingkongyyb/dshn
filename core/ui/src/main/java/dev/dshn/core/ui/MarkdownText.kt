package dev.dshn.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Minimal Markdown block model covering what coding agents typically emit. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class ListItem(val ordered: Boolean, val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")
private val ruleRe = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val bulletRe = Regex("""^(\s*)([-*+])\s+(.*)$""")
private val orderedRe = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val quoteRe = Regex("""^\s*>\s?(.*)$""")
private val tableSepRe = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split("|").map { it.trim() }
}

fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotEmpty()) {
            out += MdBlock.Paragraph(para.toString().trimEnd())
            para.clear()
        }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flushPara()
                val fence = trimmed.take(3)
                val lang = trimmed.removePrefix(fence).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += MdBlock.Code(lang, code.toString())
                i++
                continue
            }
            line.isBlank() -> flushPara()
            headingRe.matches(trimmed) -> {
                flushPara()
                val m = headingRe.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
            }
            ruleRe.matches(line) && para.isEmpty() -> {
                out += MdBlock.Rule
            }
            line.contains('|') && i + 1 < lines.size && tableSepRe.matches(lines[i + 1]) -> {
                flushPara()
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }
            bulletRe.matches(line) -> {
                flushPara()
                val m = bulletRe.find(line)!!
                val depth = m.groupValues[1].replace("\t", "  ").length / 2
                out += MdBlock.ListItem(false, "•", depth, m.groupValues[3])
            }
            orderedRe.matches(line) -> {
                flushPara()
                val m = orderedRe.find(line)!!
                val depth = m.groupValues[1].replace("\t", "  ").length / 2
                out += MdBlock.ListItem(true, "${m.groupValues[2]}.", depth, m.groupValues[3])
            }
            quoteRe.matches(line) -> {
                flushPara()
                val sb = StringBuilder()
                while (i < lines.size && quoteRe.matches(lines[i])) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(quoteRe.find(lines[i])!!.groupValues[1])
                    i++
                }
                out += MdBlock.Quote(sb.toString())
                continue
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flushPara()
    return out
}

private val linkRe = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
private val urlRe = Regex("""https?://[^\s<>()\[\]]+""")

/** Inline Markdown: **bold**, *italic*, ~~strike~~, `code`, [links](url) and bare URLs. */
fun inlineMarkdown(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    appendInline(this, text, codeBg, linkColor)
}

private fun appendInline(b: AnnotatedString.Builder, text: String, codeBg: Color, linkColor: Color) {
    var i = 0
    val len = text.length
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            b.append(plain.toString())
            plain.clear()
        }
    }
    while (i < len) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < len && text[i + 1] in "*_`~[]\\#|" -> {
                plain.append(text[i + 1])
                i += 2
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.86.em)) {
                        append(" " + text.substring(i + 1, end) + " ")
                    }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }
            (c == '*' && i + 1 < len && text[i + 1] == '*') || (c == '_' && i + 1 < len && text[i + 1] == '_') -> {
                val marker = if (c == '*') "**" else "__"
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flush()
                    b.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        appendInline(this, text.substring(i + 2, end), codeBg, linkColor)
                    }
                    i = end + 2
                } else {
                    plain.append(marker); i += 2
                }
            }
            c == '~' && i + 1 < len && text[i + 1] == '~' -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInline(this, text.substring(i + 2, end), codeBg, linkColor)
                    }
                    i = end + 2
                } else {
                    plain.append("~~"); i += 2
                }
            }
            c == '*' && i + 1 < len && !text[i + 1].isWhitespace() -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace()) {
                    flush()
                    b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(this, text.substring(i + 1, end), codeBg, linkColor)
                    }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }
            c == '[' -> {
                val m = linkRe.matchAt(text, i)
                if (m != null) {
                    flush()
                    val url = m.groupValues[2]
                    b.withLink(
                        LinkAnnotation.Url(
                            url,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        )
                    ) { append(m.groupValues[1]) }
                    i += m.value.length
                } else {
                    plain.append(c); i++
                }
            }
            c == 'h' && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val m = urlRe.matchAt(text, i)
                if (m != null) {
                    val url = m.value.trimEnd('.', ',', ';', ':', '。', '，')
                    flush()
                    b.withLink(
                        LinkAnnotation.Url(
                            url,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        )
                    ) { append(url) }
                    i += url.length
                } else {
                    plain.append(c); i++
                }
            }
            else -> {
                plain.append(c); i++
            }
        }
    }
    flush()
}

private fun displayWidth(s: String): Int = s.fold(0) { acc, ch -> acc + if (ch.code > 0x2E80) 2 else 1 }

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onCopyCode: ((String) -> Unit)? = null
) {
    val semantic = LocalDshnColors.current
    val linkColor = MaterialTheme.colorScheme.primary
    val inlineCodeBg = if (semantic.isDark) Color(0x33FFFFFF) else Color(0x14000000)
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> {
                    val annotated = remember(block.text, inlineCodeBg, linkColor) {
                        inlineMarkdown(block.text, inlineCodeBg, linkColor)
                    }
                    Text(annotated, style = style, color = color)
                }
                is MdBlock.Heading -> {
                    val annotated = remember(block.text, inlineCodeBg, linkColor) {
                        inlineMarkdown(block.text, inlineCodeBg, linkColor)
                    }
                    Text(
                        annotated,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp)
                            else -> MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)
                        },
                        color = color,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                is MdBlock.ListItem -> {
                    val annotated = remember(block.text, inlineCodeBg, linkColor) {
                        inlineMarkdown(block.text, inlineCodeBg, linkColor)
                    }
                    Row(Modifier.padding(start = (block.depth * 16).dp)) {
                        Text(
                            block.marker,
                            style = style,
                            color = if (block.ordered) color else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(if (block.ordered) 26.dp else 16.dp)
                        )
                        Text(annotated, style = style, color = color)
                    }
                }
                is MdBlock.Quote -> {
                    val annotated = remember(block.text, inlineCodeBg, linkColor) {
                        inlineMarkdown(block.text, inlineCodeBg, linkColor)
                    }
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(semantic.thinkingBar, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            annotated,
                            style = style,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MdBlock.Code -> CodeBlock(block.lang, block.code, onCopyCode)
                is MdBlock.Table -> MarkdownTable(block, inlineCodeBg, linkColor)
                MdBlock.Rule -> HorizontalDivider(color = semantic.cardBorder, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String, onCopy: ((String) -> Unit)?) {
    val semantic = LocalDshnColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = semantic.codeBg,
        border = BorderStroke(1.dp, semantic.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (onCopy != null) {
                    IconButton(onClick = { onCopy(code) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = tr("复制代码", "Copy code"),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, codeBg: Color, linkColor: Color) {
    val semantic = LocalDshnColors.current
    val colCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return
    val widths = remember(table) {
        (0 until colCount).map { c ->
            val maxLen = (listOf(table.header) + table.rows).maxOf { r -> displayWidth(r.getOrNull(c).orEmpty()) }
            (maxLen * 7.6f + 24f).coerceIn(56f, 260f).dp
        }
    }
    val totalWidth = remember(widths) { widths.fold(0.dp) { acc, w -> acc + w } }
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, semantic.cardBorder),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(semantic.subtleBg)) {
                for (c in 0 until colCount) {
                    Text(
                        inlineMarkdown(table.header.getOrNull(c).orEmpty(), codeBg, linkColor),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .width(widths[c])
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            for (row in table.rows) {
                HorizontalDivider(modifier = Modifier.width(totalWidth), color = semantic.cardBorder)
                Row {
                    for (c in 0 until colCount) {
                        Text(
                            inlineMarkdown(row.getOrNull(c).orEmpty(), codeBg, linkColor),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(widths[c])
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
