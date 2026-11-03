package site.whitezaak.wearpod.presentation.screens

/**
 * About 页的轻量 HTML 块解析。
 *
 * About 文档由服务端提供，只需要支持 `h1..h3 / p / ul / li / hr` 这几类块级元素；
 * 解析成独立块后交给 Wear 的 [androidx.wear.compose.foundation.lazy.ScalingLazyColumn]
 * 按块渲染，避免整页塞进一个 TextView 导致行距/段落间距与 Show Notes 不一致。
 *
 * 纯 JVM 实现，方便单测；不依赖 android.text.Html。
 */
internal sealed interface AboutBlock {
    data class Heading(val level: Int, val text: String) : AboutBlock
    data class Paragraph(val text: String) : AboutBlock
    data class Bullet(val text: String) : AboutBlock
    data object Divider : AboutBlock
}

private val headRegex = Regex("(?is)<head\\b[^>]*>.*?</head>")
private val scriptRegex = Regex("(?is)<script\\b[^>]*>.*?</script>")
private val styleRegex = Regex("(?is)<style\\b[^>]*>.*?</style>")
private val commentRegex = Regex("(?s)<!--.*?-->")
private val bodyRegex = Regex("(?is)<body\\b[^>]*>(.*?)</body>")
private val blockRegex = Regex(
    "(?is)<h([1-3])\\b[^>]*>(.*?)</h\\1>|<p\\b[^>]*>(.*?)</p>|<li\\b[^>]*>(.*?)</li>|<hr\\b[^>]*?/?>"
)
private val brRegex = Regex("(?i)<br\\s*/?>")
private val tagRegex = Regex("(?is)<[^>]+>")
private val horizontalWhitespaceRegex = Regex("[\\t ]+")
private val blankLineRegex = Regex("\\n{3,}")
private val newlinePaddingRegex = Regex("[\\t ]*\\n[\\t ]*")
private val entityRegex = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

internal fun parseAboutHtml(html: String): List<AboutBlock> {
    val cleaned = html
        .replace(commentRegex, "")
        .replace(scriptRegex, "")
        .replace(styleRegex, "")
        .replace(headRegex, "")
    val body = bodyRegex.find(cleaned)?.groupValues?.get(1) ?: cleaned
    val blocks = mutableListOf<AboutBlock>()

    var cursor = 0
    blockRegex.findAll(body).forEach { match ->
        appendTextBlock(blocks, body.substring(cursor, match.range.first))

        val level = match.groupValues[1].toIntOrNull()
        when {
            level != null -> {
                val text = cleanInline(match.groupValues[2], collapseNewlines = true)
                if (text.isNotEmpty()) blocks += AboutBlock.Heading(level, text)
            }

            match.value.startsWith("<p", ignoreCase = true) -> {
                val text = cleanInline(match.groupValues[3], collapseNewlines = false)
                if (text.isNotEmpty()) blocks += AboutBlock.Paragraph(text)
            }

            match.value.startsWith("<li", ignoreCase = true) -> {
                val text = cleanInline(match.groupValues[4], collapseNewlines = true)
                if (text.isNotEmpty()) blocks += AboutBlock.Bullet(text)
            }

            else -> blocks += AboutBlock.Divider
        }
        cursor = match.range.last + 1
    }
    appendTextBlock(blocks, body.substring(cursor))
    return blocks
}

private fun appendTextBlock(blocks: MutableList<AboutBlock>, raw: String) {
    val text = cleanInline(raw, collapseNewlines = false)
    if (text.isNotEmpty()) blocks += AboutBlock.Paragraph(text)
}

private fun cleanInline(raw: String, collapseNewlines: Boolean): String {
    var text = raw
        .replace(brRegex, "\n")
        .replace(tagRegex, "")
    text = decodeEntities(text)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(horizontalWhitespaceRegex, " ")
        .replace(newlinePaddingRegex, "\n")
        .replace(blankLineRegex, "\n\n")
        .trim()
    if (collapseNewlines) {
        text = text.replace(Regex("\\s*\\n\\s*"), " ").trim()
    }
    return text
}

private fun decodeEntities(raw: String): String = entityRegex.replace(raw) { match ->
    when (val entity = match.groupValues[1]) {
        "nbsp" -> " "
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "mdash" -> "—"
        "ndash" -> "–"
        "hellip" -> "…"
        "copy" -> "©"
        else -> decodeNumericEntity(entity) ?: match.value
    }
}

private fun decodeNumericEntity(entity: String): String? {
    val codePoint = when {
        entity.startsWith("#x", ignoreCase = true) ->
            entity.substring(2).toIntOrNull(16)
        entity.startsWith("#") ->
            entity.substring(1).toIntOrNull()
        else -> null
    } ?: return null
    return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
}
