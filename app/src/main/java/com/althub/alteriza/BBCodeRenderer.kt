package com.althub.alteriza

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.althub.alteriza.ui.theme.BbBlack
import com.althub.alteriza.ui.theme.BbBorder
import com.althub.alteriza.ui.theme.BbBorderBright
import com.althub.alteriza.ui.theme.BbMuted
import com.althub.alteriza.ui.theme.BbOrange
import com.althub.alteriza.ui.theme.BbSurfaceElevated
import com.althub.alteriza.ui.theme.BbWhite

private sealed class BbBlock {
    data class Paragraph(val raw: String) : BbBlock()
    data class Heading(val raw: String, val level: Int) : BbBlock()
    data class CodeBlock(val code: String) : BbBlock()
    data class QuoteBlock(val raw: String) : BbBlock()
    data class BulletList(val items: List<String>) : BbBlock()
    data object HorizontalRule : BbBlock()
}

@Composable
fun BBCodeView(
    bbcode: String,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val blocks = remember(bbcode) { parseBbBlocks(bbcode) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is BbBlock.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorderBright)
                    )
                }

                is BbBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 16.sp
                        2 -> 14.sp
                        else -> 13.sp
                    }
                    val annotated = remember(block.raw) { parseInlineBbCode(block.raw, BbWhite) }
                    Text(
                        text = annotated,
                        style = TextStyle(
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize,
                            letterSpacing = 0.6.sp
                        )
                    )
                }

                is BbBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BbBlack)
                            .border(1.dp, BbBorderBright)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = block.code,
                            color = BbOrange,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                is BbBlock.QuoteBlock -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BbSurfaceElevated)
                            .border(1.dp, BbBorder)
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(28.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(10.dp))
                        val annotated = remember(block.raw) { parseInlineBbCode(block.raw, BbMuted) }
                        @Suppress("DEPRECATION")
                        ClickableText(
                            text = annotated,
                            style = TextStyle(
                                color = BbMuted,
                                fontFamily = FontFamily.Monospace,
                                fontStyle = FontStyle.Italic,
                                fontSize = 11.sp,
                                lineHeight = 17.sp
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()
                                    ?.let { ann -> runCatching { uriHandler.openUri(ann.item) } }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is BbBlock.BulletList -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        block.items.forEach { itemRaw ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(5.dp)
                                        .background(BbOrange)
                                )
                                Spacer(Modifier.width(8.dp))
                                val annotated = remember(itemRaw) { parseInlineBbCode(itemRaw, BbWhite) }
                                @Suppress("DEPRECATION")
                                ClickableText(
                                    text = annotated,
                                    style = TextStyle(
                                        color = BbWhite,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    ),
                                    onClick = { offset ->
                                        annotated.getStringAnnotations("URL", offset, offset)
                                            .firstOrNull()
                                            ?.let { ann -> runCatching { uriHandler.openUri(ann.item) } }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                is BbBlock.Paragraph -> {
                    val annotated = remember(block.raw) { parseInlineBbCode(block.raw, BbWhite) }
                    @Suppress("DEPRECATION")
                    ClickableText(
                        text = annotated,
                        style = TextStyle(
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        ),
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()
                                ?.let { ann -> runCatching { uriHandler.openUri(ann.item) } }
                        }
                    )
                }
            }
        }
    }
}

private fun parseBbBlocks(input: String): List<BbBlock> {
    val normalized = input.replace("\r\n", "\n")
    val blockRegex = Regex(
        """\[(code|quote|list|h1|h2|h3)](.*?)\[/\1]|\[hr\s*/?]""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val result = mutableListOf<BbBlock>()
    var cursor = 0

    for (match in blockRegex.findAll(normalized)) {
        if (match.range.first > cursor) {
            val before = normalized.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) {
                result.add(BbBlock.Paragraph(before))
            }
        }
        val full = match.value.lowercase()
        if (full.startsWith("[hr")) {
            result.add(BbBlock.HorizontalRule)
        } else {
            val tag = match.groupValues[1].lowercase()
            val inner = match.groupValues[2].trim()
            when (tag) {
                "code" -> result.add(BbBlock.CodeBlock(inner))
                "quote" -> result.add(BbBlock.QuoteBlock(inner))
                "h1" -> result.add(BbBlock.Heading(inner, 1))
                "h2" -> result.add(BbBlock.Heading(inner, 2))
                "h3" -> result.add(BbBlock.Heading(inner, 3))
                "list" -> {
                    val items = inner.split(Regex("""\[\*]"""))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (items.isNotEmpty()) {
                        result.add(BbBlock.BulletList(items))
                    }
                }
            }
        }
        cursor = match.range.last + 1
    }

    if (cursor < normalized.length) {
        val tail = normalized.substring(cursor).trim()
        if (tail.isNotEmpty()) {
            result.add(BbBlock.Paragraph(tail))
        }
    }

    return result
}

private fun parseInlineBbCode(text: String, defaultColor: Color): AnnotatedString {
    val tagRegex = Regex(
        """\[(b|i|u|s)](.*?)\[/\1]|\[color=([^]]+)](.*?)\[/color]|\[url=([^]]+)](.*?)\[/url]|\[url](.*?)\[/url]""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    return buildAnnotatedString {
        var idx = 0
        for (m in tagRegex.findAll(text)) {
            if (m.range.first > idx) {
                append(text.substring(idx, m.range.first))
            }
            val simpleTag = m.groupValues[1].lowercase()
            val simpleBody = m.groupValues[2]
            val colorArg = m.groupValues[3]
            val colorBody = m.groupValues[4]
            val urlHref = m.groupValues[5]
            val urlLabel = m.groupValues[6]
            val plainUrl = m.groupValues[7]

            when {
                simpleTag.isNotEmpty() -> {
                    val style = when (simpleTag) {
                        "b" -> SpanStyle(fontWeight = FontWeight.Bold, color = BbWhite)
                        "i" -> SpanStyle(fontStyle = FontStyle.Italic)
                        "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                        "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = BbMuted)
                        else -> SpanStyle()
                    }
                    withStyle(style) {
                        append(parseInlineBbCode(simpleBody, defaultColor))
                    }
                }
                colorArg.isNotEmpty() -> {
                    val parsedColor = resolveBbColor(colorArg, defaultColor)
                    withStyle(SpanStyle(color = parsedColor)) {
                        append(parseInlineBbCode(colorBody, parsedColor))
                    }
                }
                urlHref.isNotEmpty() -> {
                    val cleanUrl = urlHref.trim().trim('"', '\'')
                    pushStringAnnotation(tag = "URL", annotation = cleanUrl)
                    withStyle(
                        SpanStyle(
                            color = BbOrange,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(urlLabel.ifBlank { cleanUrl })
                    }
                    pop()
                }
                plainUrl.isNotEmpty() -> {
                    val cleanUrl = plainUrl.trim()
                    pushStringAnnotation(tag = "URL", annotation = cleanUrl)
                    withStyle(
                        SpanStyle(
                            color = BbOrange,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(cleanUrl)
                    }
                    pop()
                }
            }
            idx = m.range.last + 1
        }
        if (idx < text.length) {
            append(text.substring(idx))
        }
    }
}

private fun resolveBbColor(raw: String, fallback: Color): Color {
    val clean = raw.trim().trim('"', '\'').lowercase()
    return when (clean) {
        "orange", "accent" -> BbOrange
        "white" -> BbWhite
        "muted", "gray", "grey" -> BbMuted
        "red" -> Color(0xFFFF4D4D)
        "green" -> Color(0xFF4ADE80)
        "yellow" -> Color(0xFFFACC15)
        "cyan", "blue" -> Color(0xFF38BDF8)
        else -> {
            if (clean.startsWith("#")) {
                runCatching {
                    Color(android.graphics.Color.parseColor(clean))
                }.getOrDefault(fallback)
            } else {
                fallback
            }
        }
    }
}