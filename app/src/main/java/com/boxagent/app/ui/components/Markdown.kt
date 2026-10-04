package com.boxagent.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * The markdown subset assistants actually produce: headings, bullet and
 * numbered lists, fenced code, **bold**, *italic* and `code`. Anything
 * else renders as plain text — never as stray syntax errors.
 */
sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val text: String) : MdBlock
    data class Item(val marker: String, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

object MarkdownLite {
    private val BULLET = Regex("^\\s*[-*•]\\s+(.*)")
    private val NUMBERED = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)")
    private val HEADING = Regex("^#{1,6}\\s+(.*)")

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
            para.setLength(0)
        }
        val lines = src.replace("\r\n", "\n").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    out += MdBlock.Code(code.toString())
                }
                HEADING.matches(line) -> { flush(); out += MdBlock.Heading(HEADING.find(line)!!.groupValues[1]) }
                BULLET.matches(line) -> { flush(); out += MdBlock.Item("•", BULLET.find(line)!!.groupValues[1]) }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.find(line)!!
                    out += MdBlock.Item(m.groupValues[1] + ".", m.groupValues[2])
                }
                line.isBlank() -> flush()
                // Wrapped text of a list item (markdown's lazy continuation).
                out.lastOrNull() is MdBlock.Item && para.isEmpty() && lines[i - 1].isNotBlank() -> {
                    val item = out.removeAt(out.lastIndex) as MdBlock.Item
                    out += item.copy(text = item.text + " " + line.trim())
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
            i++
        }
        flush()
        return out
    }

    /** Inline spans: **bold**, *italic*, `code`. Unclosed markers stay literal. */
    fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val (marker, style) = when {
                rest.startsWith("**") -> "**" to SpanStyle(fontWeight = FontWeight.SemiBold)
                rest.startsWith("`") -> "`" to SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
                rest.startsWith("*") && rest.length > 1 && !rest[1].isWhitespace() ->
                    "*" to SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                else -> null to null
            }
            if (marker != null && style != null) {
                val end = text.indexOf(marker, i + marker.length)
                if (end > i + marker.length) {
                    withStyle(style) { append(text.substring(i + marker.length, end)) }
                    i = end + marker.length
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val c = MaterialTheme.colorScheme
    val codeBg = c.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarkdownLite.parse(text).forEach { b ->
            when (b) {
                is MdBlock.Para -> Text(MarkdownLite.inline(b.text, codeBg), style = style)
                is MdBlock.Heading -> Text(
                    MarkdownLite.inline(b.text, codeBg),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Item -> Row {
                    Text(b.marker, style = style, color = c.onSurfaceVariant, modifier = Modifier.width(22.dp))
                    Text(MarkdownLite.inline(b.text, codeBg), style = style)
                }
                is MdBlock.Code -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(codeBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
