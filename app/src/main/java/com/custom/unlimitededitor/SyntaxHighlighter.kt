package com.custom.unlimitededitor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object SyntaxHighlighter {

    enum class Lang {
        PLAIN, KOTLIN_JAVA, PYTHON, JS_TS, C_CPP, HTML_XML, JSON, SQL, SHELL_PS, LUA, MARKDOWN
    }

    private val COMMON_KEYWORDS = hashSetOf(
        "if", "else", "for", "while", "do", "when", "switch", "case", "default", "break", "continue",
        "return", "try", "catch", "finally", "throw", "throws", "new", "in", "is", "as",
        "fun", "function", "def", "class", "interface", "object", "struct", "enum", "import", "package",
        "from", "export", "val", "var", "let", "const", "private", "public", "protected", "internal",
        "static", "final", "override", "open", "abstract", "suspend", "async", "await", "yield",
        "true", "false", "null", "nil", "None", "True", "False", "this", "super", "self", "void",
        "int", "long", "float", "double", "boolean", "bool", "char", "byte", "short", "String", "Unit", "Any",
        "local", "then", "end", "elseif", "repeat", "until", "and", "or", "not",
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "ALTER", "DROP", "JOIN", "LEFT", "RIGHT", "INNER", "ON", "GROUP", "BY", "ORDER", "LIMIT",
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
        "create", "table", "alter", "drop", "join", "left", "right", "inner", "on", "group", "by", "order", "limit"
    )

    fun detectLanguage(title: String, firstNonEmptyLine: String): Lang {
        val ext = title.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java" -> Lang.KOTLIN_JAVA
            "py", "pyw" -> Lang.PYTHON
            "js", "jsx", "ts", "tsx", "mjs" -> Lang.JS_TS
            "c", "h", "cpp", "hpp", "cc", "cs" -> Lang.C_CPP
            "html", "htm", "xml", "svg", "xhtml" -> Lang.HTML_XML
            "json", "jsonc" -> Lang.JSON
            "sql" -> Lang.SQL
            "sh", "bash", "zsh", "ps1", "psm1", "bat", "cmd" -> Lang.SHELL_PS
            "lua" -> Lang.LUA
            "md", "markdown" -> Lang.MARKDOWN
            else -> {
                val trimmed = firstNonEmptyLine.trim()
                when {
                    trimmed.startsWith("#!") -> Lang.SHELL_PS
                    trimmed.startsWith("<?xml") || trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") -> Lang.HTML_XML
                    trimmed.startsWith("{") || trimmed.startsWith("[") -> Lang.JSON
                    trimmed.startsWith("# ") || trimmed.startsWith("## ") -> Lang.MARKDOWN
                    else -> Lang.PLAIN
                }
            }
        }
    }

    private fun getColors(theme: EditorTheme): IntArray {
        // [0]=keyword, [1]=string, [2]=comment, [3]=number, [4]=type/tag, [5]=mdHeader
        return when (theme) {
            EditorTheme.DARK -> intArrayOf(
                Color.parseColor("#FF7B72"), // keyword
                Color.parseColor("#A5D6FF"), // string
                Color.parseColor("#8B949E"), // comment
                Color.parseColor("#79C0FF"), // number
                Color.parseColor("#D2A8FF"), // type/tag
                Color.parseColor("#58A6FF")  // markdown header
            )
            EditorTheme.EINK -> intArrayOf(
                Color.parseColor("#000000"),
                Color.parseColor("#333333"),
                Color.parseColor("#666666"),
                Color.parseColor("#222222"),
                Color.parseColor("#111111"),
                Color.parseColor("#000000")
            )
            else -> intArrayOf(
                Color.parseColor("#0369A1"), // keyword (deep blue)
                Color.parseColor("#15803D"), // string (forest green)
                Color.parseColor("#64748B"), // comment (slate gray)
                Color.parseColor("#B45309"), // number (amber)
                Color.parseColor("#7E22CE"), // type/tag (purple)
                Color.parseColor("#0284C7")  // markdown header
            )
        }
    }

    /**
     * 보이는 한 줄(segment)만 즉석 스캔하여 Canvas에 바로 그림 (추가 객체 할당 최소화)
     */
    fun drawHighlightedSegment(
        canvas: Canvas,
        fullLine: String,
        segStart: Int,
        segEnd: Int,
        baseX: Float,
        baselineY: Float,
        paint: Paint,
        theme: EditorTheme,
        lang: Lang
    ) {
        val segment = fullLine.substring(segStart, segEnd)
        if (lang == Lang.PLAIN || segment.isEmpty()) {
            canvas.drawText(segment, baseX, baselineY, paint)
            return
        }

        val defaultColor = paint.color
        val defaultBold = paint.isFakeBoldText
        val defaultItalic = paint.textSkewX
        val palette = getColors(theme)

        if (lang == Lang.MARKDOWN) {
            val trimmed = fullLine.trimStart()
            when {
                trimmed.startsWith("#") -> {
                    paint.color = palette[5]
                    paint.isFakeBoldText = true
                    canvas.drawText(segment, baseX, baselineY, paint)
                    paint.color = defaultColor
                    paint.isFakeBoldText = defaultBold
                    return
                }
                trimmed.startsWith(">") -> {
                    paint.color = palette[2]
                    paint.textSkewX = -0.2f
                    canvas.drawText(segment, baseX, baselineY, paint)
                    paint.color = defaultColor
                    paint.textSkewX = defaultItalic
                    return
                }
                trimmed.startsWith("```") || trimmed.startsWith("    ") || trimmed.startsWith("\t") -> {
                    paint.color = palette[1]
                    canvas.drawText(segment, baseX, baselineY, paint)
                    paint.color = defaultColor
                    return
                }
            }
        }

        var curX = baseX
        var i = 0
        val len = segment.length

        fun emit(start: Int, end: Int, color: Int, bold: Boolean = false) {
            if (end <= start) return
            val part = segment.substring(start, end)
            paint.color = color
            paint.isFakeBoldText = bold || defaultBold
            canvas.drawText(part, curX, baselineY, paint)
            curX += paint.measureText(part)
            paint.color = defaultColor
            paint.isFakeBoldText = defaultBold
        }

        while (i < len) {
            val ch = segment[i]

            // 한 줄 주석 감지 (//, #, --)
            val isSlashComment = (ch == '/' && i + 1 < len && segment[i + 1] == '/') &&
                (lang == Lang.KOTLIN_JAVA || lang == Lang.JS_TS || lang == Lang.C_CPP || lang == Lang.JSON)
            val isHashComment = (ch == '#') && (lang == Lang.PYTHON || lang == Lang.SHELL_PS)
            val isDashComment = (ch == '-' && i + 1 < len && segment[i + 1] == '-') && (lang == Lang.SQL || lang == Lang.LUA)

            if (isSlashComment || isHashComment || isDashComment) {
                emit(i, len, palette[2], false)
                break
            }

            // 문자열 리터럴 ("...", '...', `...`)
            if (ch == '"' || ch == '\'' || ch == '`') {
                val quote = ch
                var j = i + 1
                while (j < len) {
                    if (segment[j] == '\\' && j + 1 < len) {
                        j += 2
                        continue
                    }
                    if (segment[j] == quote) {
                        j++
                        break
                    }
                    j++
                }
                emit(i, j, palette[1], false)
                i = j
                continue
            }

            // HTML/XML 태그 (<tag ...>)
            if (lang == Lang.HTML_XML && ch == '<') {
                var j = i + 1
                while (j < len && segment[j] != '>') j++
                if (j < len) j++
                emit(i, j, palette[4], false)
                i = j
                continue
            }

            // 숫자
            if (ch.isDigit() && (i == 0 || !segment[i - 1].isLetterOrDigit() && segment[i - 1] != '_')) {
                var j = i + 1
                while (j < len && (segment[j].isLetterOrDigit() || segment[j] == '.' || segment[j] == '_')) j++
                emit(i, j, palette[3], false)
                i = j
                continue
            }

            // 식별자 및 예약어
            if (ch.isLetter() || ch == '_' || (lang == Lang.SHELL_PS && ch == '$')) {
                var j = i + 1
                while (j < len && (segment[j].isLetterOrDigit() || segment[j] == '_')) j++
                val word = segment.substring(i, j)
                when {
                    lang == Lang.SHELL_PS && word.startsWith("$") -> emit(i, j, palette[4], false)
                    COMMON_KEYWORDS.contains(word) -> emit(i, j, palette[0], true)
                    word[0].isUpperCase() && word.length > 1 && lang != Lang.SQL -> emit(i, j, palette[4], false)
                    else -> emit(i, j, defaultColor, false)
                }
                i = j
                continue
            }

            // 일반 기호/공백 묶음 처리
            var j = i + 1
            while (j < len) {
                val c = segment[j]
                if (c.isLetterOrDigit() || c == '_' || c == '"' || c == '\'' || c == '`' ||
                    c == '/' || c == '#' || c == '-' || c == '<' || c == '$'
                ) break
                j++
            }
            emit(i, j, defaultColor, false)
            i = j
        }
    }
}