package com.custom.unlimitededitor

import org.json.JSONArray
import org.json.JSONObject

object MarkdownConverter {

    data class ImageRef(
        val fullMatch: String,
        val alt: String,
        val path: String
    )

    private val IMAGE_REGEX = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

    fun extractImages(markdown: String): List<ImageRef> {
        return IMAGE_REGEX.findAll(markdown).map { m ->
            ImageRef(
                fullMatch = m.value,
                alt = m.groupValues[1],
                path = m.groupValues[2].trim()
            )
        }.toList()
    }

    fun extractTitle(lines: List<String>, fallbackTitle: String): String {
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("# ")) {
                return trimmed.removePrefix("# ").trim()
            }
        }
        return fallbackTitle.substringBeforeLast('.').ifEmpty { "제목 없음" }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun formatInline(raw: String): String {
        var s = escapeHtml(raw)
        // 이미지 ![alt](url)
        s = Regex("""!\[([^\]]*)\]\(([^)]+)\)""").replace(s) { m ->
            val alt = m.groupValues[1]
            val url = m.groupValues[2]
            """<img src="$url" alt="$alt" style="max-width:100%;height:auto;" />"""
        }
        // 링크 [text](url)
        s = Regex("""\[([^\]]+)\]\(([^)]+)\)""").replace(s) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            """<a href="$url" target="_blank" rel="noopener">$label</a>"""
        }
        // 인라인 코드 `code`
        s = Regex("""`([^`]+)`""").replace(s) { m ->
            "<code>${m.groupValues[1]}</code>"
        }
        // 굵게 **text**
        s = Regex("""\*\*([^*]+)\*\*""").replace(s) { m ->
            "<strong>${m.groupValues[1]}</strong>"
        }
        // 기울임 *text*
        s = Regex("""\*([^*]+)\*""").replace(s) { m ->
            "<em>${m.groupValues[1]}</em>"
        }
        return s
    }

    fun toHtml(markdown: String): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inCodeBlock = false
        var codeLang = ""
        var inUl = false
        var inOl = false

        fun closeLists() {
            if (inUl) { sb.append("</ul>\n"); inUl = false }
            if (inOl) { sb.append("</ol>\n"); inOl = false }
        }

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                closeLists()
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeLang = trimmed.removePrefix("```").trim()
                    val langAttr = if (codeLang.isNotEmpty()) """ class="language-${escapeHtml(codeLang)}"""" else ""
                    sb.append("<pre><code$langAttr>")
                } else {
                    inCodeBlock = false
                    sb.append("</code></pre>\n")
                }
                continue
            }

            if (inCodeBlock) {
                sb.append(escapeHtml(line)).append("\n")
                continue
            }

            if (trimmed.isEmpty()) {
                closeLists()
                continue
            }

            if (trimmed == "---" || trimmed == "***") {
                closeLists()
                sb.append("<hr />\n")
                continue
            }

            when {
                trimmed.startsWith("### ") -> {
                    closeLists()
                    sb.append("<h3>").append(formatInline(trimmed.removePrefix("### "))).append("</h3>\n")
                }
                trimmed.startsWith("## ") -> {
                    closeLists()
                    sb.append("<h2>").append(formatInline(trimmed.removePrefix("## "))).append("</h2>\n")
                }
                trimmed.startsWith("# ") -> {
                    closeLists()
                    sb.append("<h1>").append(formatInline(trimmed.removePrefix("# "))).append("</h1>\n")
                }
                trimmed.startsWith("> ") -> {
                    closeLists()
                    sb.append("<blockquote><p>").append(formatInline(trimmed.removePrefix("> "))).append("</p></blockquote>\n")
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    if (inOl) { sb.append("</ol>\n"); inOl = false }
                    if (!inUl) { sb.append("<ul>\n"); inUl = true }
                    sb.append("<li>").append(formatInline(trimmed.substring(2))).append("</li>\n")
                }
                trimmed.matches(Regex("""^\d+\.\s+.*""")) -> {
                    if (inUl) { sb.append("</ul>\n"); inUl = false }
                    if (!inOl) { sb.append("<ol>\n"); inOl = true }
                    val itemText = trimmed.replaceFirst(Regex("""^\d+\.\s+"""), "")
                    sb.append("<li>").append(formatInline(itemText)).append("</li>\n")
                }
                else -> {
                    closeLists()
                    sb.append("<p>").append(formatInline(line)).append("</p>\n")
                }
            }
        }
        closeLists()
        if (inCodeBlock) sb.append("</code></pre>\n")
        return sb.toString()
    }

    /**
     * Notion API /v1/pages children 블록 배열 생성 (최대 100개)
     */
    fun toNotionBlocks(markdown: String): JSONArray {
        val arr = JSONArray()
        val lines = markdown.lines()
        var inCode = false
        var codeLang = "plain text"
        val codeBuf = StringBuilder()

        fun richTextArray(text: String): JSONArray {
            val safe = text.take(1900)
            return JSONArray().put(
                JSONObject().put("type", "text").put("text", JSONObject().put("content", safe))
            )
        }

        for (line in lines) {
            if (arr.length() >= 95) break
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    codeLang = trimmed.removePrefix("```").trim().lowercase().ifEmpty { "plain text" }
                    codeBuf.clear()
                } else {
                    inCode = false
                    val block = JSONObject()
                        .put("object", "block")
                        .put("type", "code")
                        .put("code", JSONObject()
                            .put("rich_text", richTextArray(codeBuf.toString().trimEnd()))
                            .put("language", mapNotionCodeLang(codeLang))
                        )
                    arr.put(block)
                }
                continue
            }

            if (inCode) {
                codeBuf.append(line).append("\n")
                continue
            }

            if (trimmed.isEmpty()) continue

            val imgMatch = IMAGE_REGEX.matchEntire(trimmed)
            if (imgMatch != null) {
                val url = imgMatch.groupValues[2].trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val imgBlock = JSONObject()
                        .put("object", "block")
                        .put("type", "image")
                        .put("image", JSONObject()
                            .put("type", "external")
                            .put("external", JSONObject().put("url", url))
                        )
                    arr.put(imgBlock)
                }
                continue
            }

            val block = when {
                trimmed.startsWith("### ") -> JSONObject()
                    .put("object", "block").put("type", "heading_3")
                    .put("heading_3", JSONObject().put("rich_text", richTextArray(trimmed.removePrefix("### "))))
                trimmed.startsWith("## ") -> JSONObject()
                    .put("object", "block").put("type", "heading_2")
                    .put("heading_2", JSONObject().put("rich_text", richTextArray(trimmed.removePrefix("## "))))
                trimmed.startsWith("# ") -> JSONObject()
                    .put("object", "block").put("type", "heading_1")
                    .put("heading_1", JSONObject().put("rich_text", richTextArray(trimmed.removePrefix("# "))))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> JSONObject()
                    .put("object", "block").put("type", "bulleted_list_item")
                    .put("bulleted_list_item", JSONObject().put("rich_text", richTextArray(trimmed.substring(2))))
                else -> JSONObject()
                    .put("object", "block").put("type", "paragraph")
                    .put("paragraph", JSONObject().put("rich_text", richTextArray(line)))
            }
            arr.put(block)
        }
        return arr
    }

    private fun mapNotionCodeLang(lang: String): String {
        return when (lang) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "py", "python" -> "python"
            "js", "javascript" -> "javascript"
            "ts", "typescript" -> "typescript"
            "json" -> "json"
            "sql" -> "sql"
            "html", "xml" -> "html"
            "sh", "bash" -> "bash"
            "ps1", "powershell" -> "powershell"
            "lua" -> "lua"
            "c" -> "c"
            "cpp", "c++" -> "c++"
            else -> "plain text"
        }
    }
}