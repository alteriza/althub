package com.althub.alteriza

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

private sealed class BBBlock {
    data class Paragraph(val raw: String) : BBBlock()
    data class Quote(val author: String?, val raw: String) : BBBlock()
    data class Code(val code: String) : BBBlock()
    data class ImageBlock(val url: String) : BBBlock()
    data class ListBlock(val items: List<String>) : BBBlock()
    data class Heading(val level: Int, val text: String) : BBBlock()
    data object Divider : BBBlock()
}

@Composable
fun BBCodeView(
    bbcode: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(bbcode) { parseBlocks(bbcode) }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (block in blocks) {
            when (block) {
                is BBBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 19.sp
                        2 -> 16.sp
                        else -> 14.sp
                    }
                    Text(
                        text = block.text.uppercase(),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = size,
                        letterSpacing = 0.8.sp
                    )
                }

                is BBBlock.Divider -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF2E2E2E))
                    )
                }

                is BBBlock.Code -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0A0A))
                            .border(1.dp, Color(0xFF2D2D2D))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "CODE // RAW",
                            color = Color(0xFFFF6B00),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = block.code,
                            color = Color(0xFFE0E0E0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                is BBBlock.Quote -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF181818))
                            .border(1.dp, Color(0xFF333333))
                            .padding(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(6.dp)
                                    .background(Color(0xFFFF6B00))
                            )
                            Text(
                                text = (block.author ?: "QUOTE").uppercase(),
                                color = Color(0xFFFF6B00),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val annotated = remember(block.raw) { parseInline(block.raw) }
                        ClickableText(
                            text = annotated,
                            style = TextStyle(
                                color = Color(0xFFD6D6D6),
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        runCatching { uriHandler.openUri(ann.item) }
                                    }
                            }
                        )
                    }
                }

                is BBBlock.ListBlock -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (item in block.items) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "■",
                                    color = Color(0xFFFF6B00),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                val annotated = remember(item) { parseInline(item) }
                                ClickableText(
                                    text = annotated,
                                    style = TextStyle(
                                        color = Color(0xFFE5E5E5),
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp
                                    ),
                                    onClick = { offset ->
                                        annotated.getStringAnnotations("URL", offset, offset)
                                            .firstOrNull()?.let { ann ->
                                                runCatching { uriHandler.openUri(ann.item) }
                                            }
                                    }
                                )
                            }
                        }
                    }
                }

                is BBBlock.ImageBlock -> {
                    BBRemoteImage(url = block.url)
                }

                is BBBlock.Paragraph -> {
                    val annotated = remember(block.raw) { parseInline(block.raw) }
                    if (annotated.text.isNotBlank()) {
                        ClickableText(
                            text = annotated,
                            style = TextStyle(
                                color = Color(0xFFE5E5E5),
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        runCatching { uriHandler.openUri(ann.item) }
                                    }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BBRemoteImage(url: String) {
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2E2E2E)),
            contentScale = ContentScale.FillWidth
        )
    }
}

private fun parseBlocks(input: String): List<BBBlock> {
    val result = mutableListOf<BBBlock>()
    val blockRegex = Regex(
        """\[(code)](.*?)\[/code]|\[quote(?:=([^\]]+))?](.*?)\[/quote]|\[img](.*?)\[/img]|\[list](.*?)\[/list]|\[h([1-3])](.*?)\[/h\7]|\[hr\s*/?]""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    var cursor = 0
    for (match in blockRegex.findAll(input)) {
        if (match.range.first > cursor) {
            val plain = input.substring(cursor, match.range.first).trim()
            if (plain.isNotEmpty()) {
                result.add(BBBlock.Paragraph(plain))
            }
        }
        val full = match.value.lowercase()
        when {
            full.startsWith("[code]") -> {
                result.add(BBBlock.Code(match.groupValues[2].trim('\n', '\r')))
            }
            full.startsWith("[quote") -> {
                val author = match.groupValues[3].trim().ifEmpty { null }
                result.add(BBBlock.Quote(author, match.groupValues[4].trim()))
            }
            full.startsWith("[img]") -> {
                val u = match.groupValues[5].trim()
                if (u.isNotEmpty()) result.add(BBBlock.ImageBlock(u))
            }
            full.startsWith("[list]") -> {
                val rawItems = match.groupValues[6]
                    .split(Regex("""\[\*]""", RegexOption.IGNORE_CASE))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (rawItems.isNotEmpty()) result.add(BBBlock.ListBlock(rawItems))
            }
            full.startsWith("[h") -> {
                val lvl = match.groupValues[7].toIntOrNull() ?: 2
                result.add(BBBlock.Heading(lvl, match.groupValues[8].trim()))
            }
            full.startsWith("[hr") -> {
                result.add(BBBlock.Divider)
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < input.length) {
        val tail = input.substring(cursor).trim()
        if (tail.isNotEmpty()) {
            result.add(BBBlock.Paragraph(tail))
        }
    }
    return result
}

private fun parseInline(text: String): AnnotatedString {
    val tagRegex = Regex(
        """\[(/?)(b|i|u|s|color|size|url)(?:=([^\]]+))?]""",
        RegexOption.IGNORE_CASE
    )
    return buildAnnotatedString {
        var idx = 0
        val boldStack = ArrayDeque<Int>()
        val italicStack = ArrayDeque<Int>()
        val underlineStack = ArrayDeque<Int>()
        val strikeStack = ArrayDeque<Int>()
        val colorStack = ArrayDeque<Pair<Int, Color>>()
        val sizeStack = ArrayDeque<Pair<Int, Float>>()
        val urlStack = ArrayDeque<Pair<Int, String?>>()

        for (m in tagRegex.findAll(text)) {
            if (m.range.first > idx) {
                append(text.substring(idx, m.range.first))
            }
            val isClosing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val param = m.groupValues[3].trim().removeSurrounding("\"").removeSurrounding("'")
            val pos = length

            if (!isClosing) {
                when (tag) {
                    "b" -> boldStack.addLast(pos)
                    "i" -> italicStack.addLast(pos)
                    "u" -> underlineStack.addLast(pos)
                    "s" -> strikeStack.addLast(pos)
                    "color" -> parseColor(param)?.let { colorStack.addLast(pos to it) }
                    "size" -> param.toFloatOrNull()?.let { sizeStack.addLast(pos to it.coerceIn(10f, 32f)) }
                    "url" -> urlStack.addLast(pos to param.ifEmpty { null })
                }
            } else {
                when (tag) {
                    "b" -> boldStack.removeLastOrNull()?.let { start ->
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, pos)
                    }
                    "i" -> italicStack.removeLastOrNull()?.let { start ->
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, pos)
                    }
                    "u" -> underlineStack.removeLastOrNull()?.let { start ->
                        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, pos)
                    }
                    "s" -> strikeStack.removeLastOrNull()?.let { start ->
                        addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, pos)
                    }
                    "color" -> colorStack.removeLastOrNull()?.let { (start, col) ->
                        addStyle(SpanStyle(color = col), start, pos)
                    }
                    "size" -> sizeStack.removeLastOrNull()?.let { (start, sz) ->
                        addStyle(SpanStyle(fontSize = sz.sp), start, pos)
                    }
                    "url" -> urlStack.removeLastOrNull()?.let { (start, explicitUrl) ->
                        val linkTarget = explicitUrl ?: toAnnotatedString().text.substring(start, pos)
                        addStyle(
                            SpanStyle(
                                color = Color(0xFFFF6B00),
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.SemiBold
                            ),
                            start,
                            pos
                        )
                        addStringAnnotation("URL", linkTarget, start, pos)
                    }
                }
            }
            idx = m.range.last + 1
        }
        if (idx < text.length) {
            append(text.substring(idx))
        }
    }
}

private fun parseColor(raw: String): Color? {
    val clean = raw.trim().lowercase()
    return when (clean) {
        "red" -> Color(0xFFFF5252)
        "green" -> Color(0xFF4CAF50)
        "blue" -> Color(0xFF448AFF)
        "yellow" -> Color(0xFFFFD740)
        "orange" -> Color(0xFFFF6B00)
        "white" -> Color.White
        "gray", "grey" -> Color(0xFF9E9E9E)
        else -> runCatching {
            val hex = clean.removePrefix("#")
            when (hex.length) {
                6 -> Color(("FF$hex").toLong(16))
                8 -> Color(hex.toLong(16))
                else -> null
            }
        }.getOrNull()
    }
}
