package com.coucou.android.core

/** A run of text with formatting styles for chat answers. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val linkUrl: String? = null,
)

enum class CalloutKind(val defaultLabel: String, val icon: String) {
    NOTE("Catatan", "[i]"),
    TIP("Tip", "[*]"),
    IMPORTANT("Penting", "[!]"),
    WARNING("Peringatan", "[!]"),
    CAUTION("Perhatian", "[!]"),
    QUOTE("Kutipan", "\""),
}

sealed interface MdBlock {
    data class Para(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Callout(val kind: CalloutKind, val title: String?, val spans: List<MdSpan>) : MdBlock
    data class Bullet(val spans: List<MdSpan>, val order: Int? = null) : MdBlock
    data class Code(val text: String, val lang: String = "") : MdBlock
    data class Image(val url: String, val alt: String = "") : MdBlock
    object Divider : MdBlock
}

/**
 * Enhanced light Markdown parser that eliminates ugly raw symbols like `>`, `#`, fences,
 * and parses them into rich UI block components (Callouts, Cards, Code with syntax label, Images).
 */
object MarkdownLite {
    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()

        fun flushPara() {
            if (para.isNotEmpty()) {
                blocks.add(MdBlock.Para(inline(para.joinToString("\n"))))
                para.clear()
            }
        }

        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                // Fenced code blocks ```lang
                trimmed.startsWith("```") -> {
                    flushPara()
                    val lang = trimmed.removePrefix("```").trim().lowercase()
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        codeLines.add(lines[i++])
                    }
                    blocks.add(MdBlock.Code(codeLines.joinToString("\n"), lang))
                }

                // Callouts & Blockquotes (> [!NOTE] or > text)
                trimmed.startsWith(">") -> {
                    flushPara()
                    var kind = CalloutKind.QUOTE
                    var title: String? = null
                    val quoteLines = mutableListOf<String>()

                    var firstLineClean = trimmed.removePrefix(">").trim()
                    // Check GitHub style alert: [!NOTE], [!TIP], [!IMPORTANT], [!WARNING], [!CAUTION]
                    val alertMatch = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]""", RegexOption.IGNORE_CASE).find(firstLineClean)
                    if (alertMatch != null) {
                        val alertType = alertMatch.groupValues[1].uppercase()
                        kind = when (alertType) {
                            "NOTE" -> CalloutKind.NOTE
                            "TIP" -> CalloutKind.TIP
                            "IMPORTANT" -> CalloutKind.IMPORTANT
                            "WARNING" -> CalloutKind.WARNING
                            "CAUTION" -> CalloutKind.CAUTION
                            else -> CalloutKind.NOTE
                        }
                        firstLineClean = firstLineClean.substring(alertMatch.range.last + 1).trim()
                    }

                    if (firstLineClean.isNotEmpty()) {
                        quoteLines.add(firstLineClean)
                    }

                    // Collect subsequent quote lines
                    while (i + 1 < lines.size && lines[i + 1].trim().startsWith(">")) {
                        i++
                        val nextClean = lines[i].trim().removePrefix(">").trim()
                        if (nextClean.isNotEmpty()) {
                            quoteLines.add(nextClean)
                        }
                    }

                    blocks.add(MdBlock.Callout(kind, title, inline(quoteLines.joinToString(" "))))
                }

                // Headings (# H1, ## H2, ### H3, #### H4)
                trimmed.startsWith("#") && trimmed.contains(" ") && trimmed.takeWhile { it == '#' }.length <= 4 -> {
                    flushPara()
                    val level = trimmed.takeWhile { it == '#' }.length
                    val headingText = trimmed.drop(level).trim()
                    blocks.add(MdBlock.Heading(level, inline(headingText)))
                }

                // Horizontal Rule / Divider (---, ***, ___)
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    flushPara()
                    blocks.add(MdBlock.Divider)
                }

                // Markdown Image ![alt](url)
                trimmed.startsWith("![") && trimmed.contains("](") && trimmed.endsWith(")") -> {
                    flushPara()
                    val alt = trimmed.substringAfter("![").substringBefore("](")
                    val url = trimmed.substringAfter("](").substringBeforeLast(")")
                    blocks.add(MdBlock.Image(url, alt))
                }

                // Standalone Image URL or Screenshot URL
                isStandaloneImageUrl(trimmed) -> {
                    flushPara()
                    blocks.add(MdBlock.Image(trimmed, "Screenshot/Gambar"))
                }

                // Bullet Lists (- , * , • )
                isBullet(trimmed) -> {
                    flushPara()
                    val content = trimmed.substring(2).trimStart()
                    blocks.add(MdBlock.Bullet(inline(content)))
                }

                // Numbered Lists (1. , 2. )
                isNumberedList(trimmed) != null -> {
                    flushPara()
                    val num = isNumberedList(trimmed)
                    val content = trimmed.substringAfter(". ").trimStart()
                    blocks.add(MdBlock.Bullet(inline(content), order = num))
                }

                // Blank line flushes paragraph
                line.isBlank() -> flushPara()

                // Normal paragraph lines
                else -> {
                    // Check if line contains inline image tag
                    val inlineImgRegex = Regex("""!\[(.*?)\]\((https?://[^\s)]+)\)""")
                    val match = inlineImgRegex.find(line)
                    if (match != null && line.trim() == match.value) {
                        flushPara()
                        val alt = match.groupValues[1]
                        val url = match.groupValues[2]
                        blocks.add(MdBlock.Image(url, alt))
                    } else {
                        para.add(line)
                    }
                }
            }
            i++
        }
        flushPara()
        return blocks
    }

    private fun isBullet(t: String) = t.length > 2 && (t[0] == '-' || t[0] == '*' || t[0] == '•') && t[1] == ' '

    private fun isNumberedList(t: String): Int? {
        val dot = t.indexOf(". ")
        if (dot in 1..4) {
            return t.substring(0, dot).toIntOrNull()
        }
        return null
    }

    private fun isStandaloneImageUrl(t: String): Boolean {
        if (!t.startsWith("http://") && !t.startsWith("https://")) return false
        val lower = t.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.contains("/screenshot") || lower.contains("/uploads/")
    }

    /** Parses inline markdown: **bold**, *italic*, `code`, and [link](url). */
    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val plain = StringBuilder()

        fun emitPlain() {
            if (plain.isNotEmpty()) {
                out.add(MdSpan(plain.toString()))
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            // Bold **text**
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    emitPlain()
                    out.add(MdSpan(text.substring(i + 2, end), bold = true))
                    i = end + 2
                    continue
                }
            }

            // Inline Code `text`
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    emitPlain()
                    out.add(MdSpan(text.substring(i + 1, end), code = true))
                    i = end + 1
                    continue
                }
            }

            // Links [title](url)
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        emitPlain()
                        val linkTitle = text.substring(i + 1, closeBracket)
                        val linkUrl = text.substring(closeBracket + 2, closeParen)
                        out.add(MdSpan(linkTitle, bold = true, linkUrl = linkUrl))
                        i = closeParen + 1
                        continue
                    }
                }
            }

            plain.append(text[i])
            i++
        }
        emitPlain()
        return out
    }
}

/** The codes the computer gives for an answer that did not complete. */
object ChatReasons {
    val KNOWN = listOf(
        "off", "not_allowed", "busy", "rate", "too_long", "empty", "no_key", "unreachable", "auth", "provider",
        "canceled", "internal", "connection", "interrupted",
    )

    fun key(code: String?): String = if (code != null && code in KNOWN) code else "internal"
}
