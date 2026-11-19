package com.custom.unlimitededitor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LargeTextEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var lines = ArrayList<String>(100_000)
    private val lineWrapCache = ArrayList<IntArray>(100_000)
    private var prefixCounts = IntArray(0)
    private var prefixValidCount = 0
    private var totalVisualLines = 0
    private var maxLinePixelWidth = 1000f

    private var undoStack = ArrayDeque<EditRecord>()
    private var redoStack = ArrayDeque<EditRecord>()
    private var bookmarks = LinkedHashSet<Int>()
    private var isRestoringHistory = false
    private var activeTab: TabSession? = null
    private var detectedLang = SyntaxHighlighter.Lang.PLAIN

    var currentTheme = EditorTheme.JOTA_LIGHT
        private set
    var detectedEncoding: Charset = Charsets.UTF_8
    var onStatusChanged: ((line: Int, totalLines: Int, col: Int, hasSelection: Boolean) -> Unit)? = null
    var onModifiedChanged: ((isModified: Boolean) -> Unit)? = null

    private var cursorLine = 0
    private var cursorCol = 0
    private var selAnchorLine = -1
    private var selAnchorCol = -1
    var isSelectionMode = false
        private set

    private var composingStart = -1
    private var composingEnd = -1

    private var textSizePx = 44f
    private var lineSpacingMultiplier = 1.48f
    private val leftPadding = 24f
    private val rightPadding = 28f
    var showLineNumbers = true
        private set
    var wordWrap = true
        private set
    var showWhitespace = false
        private set
    var syntaxHighlight = true
        private set

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val whitespacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    private val bookmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint().apply { strokeWidth = 2f }
    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply { strokeWidth = 4.5f }

    private var scrollXOffset = 0f
    private var scrollYOffset = 0f
    private val scroller = OverScroller(context)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointerDownX = 0f
    private var pointerDownY = 0f
    private var pointerMoved = false
    private var pointerButtonState = 0
    private var isMouseDragSelecting = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!pointerMoved && !isMouseDragSelecting) {
                requestFocus()
                moveCursorToTouch(e.x, e.y, keepSelection = false)
                selectCurrentWord()
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isMouseDragSelecting) return true
            if (pointerMoved) {
                scrollByOffset(if (wordWrap) 0f else distanceX, distanceY)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!pointerMoved || isMouseDragSelecting) return false
            val maxY = max(0f, totalVisualLines * getLineHeight() - height + 300f).toInt()
            val maxX = if (wordWrap) 0 else max(0f, maxLinePixelWidth - width + getGutterWidth() + 200f).toInt()
            scroller.fling(
                scrollXOffset.toInt(), scrollYOffset.toInt(),
                if (wordWrap) 0 else (-velocityX).toInt(), (-velocityY).toInt(),
                0, maxX, 0, maxY
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        applyAllSettings()
    }

    fun applyAllSettings() {
        val themes = EditorTheme.values()
        val targetTheme = themes[EditorSettings.themeIndex.coerceIn(0, themes.lastIndex)]
        textSizePx = EditorSettings.textSizePx
        lineSpacingMultiplier = EditorSettings.lineSpacing
        wordWrap = EditorSettings.wordWrap
        showLineNumbers = EditorSettings.showLineNumbers
        showWhitespace = EditorSettings.showWhitespace
        syntaxHighlight = EditorSettings.syntaxHighlight
        textPaint.typeface = EditorSettings.getTypeface()
        if (wordWrap) scrollXOffset = 0f

        refreshLanguageDetection()
        applyTheme(targetTheme)
        updateFontMetrics()
        rebuildAllWraps()
        invalidate()
    }

    fun refreshLanguageDetection() {
        val title = activeTab?.title ?: ""
        val firstLine = lines.firstOrNull { it.isNotBlank() } ?: ""
        detectedLang = SyntaxHighlighter.detectLanguage(title, firstLine)
    }

    fun bindTabSession(session: TabSession) {
        saveToActiveTab()
        activeTab = session
        lines = session.lines
        if (lines.isEmpty()) lines.add("")
        detectedEncoding = session.encoding
        cursorLine = min(lines.size - 1, max(0, session.cursorLine))
        cursorCol = min(lines[cursorLine].length, max(0, session.cursorCol))
        scrollYOffset = session.scrollYOffset
        scrollXOffset = 0f
        bookmarks = session.bookmarks
        undoStack = session.undoStack
        redoStack = session.redoStack
        refreshLanguageDetection()
        clearSelection()
        clearComposing()
        rebuildAllWraps()
        invalidate()
    }

    fun saveToActiveTab() {
        activeTab?.let { tab ->
            tab.encoding = detectedEncoding
            tab.cursorLine = cursorLine
            tab.cursorCol = cursorCol
            tab.scrollYOffset = scrollYOffset
        }
    }

    private fun markModified() {
        activeTab?.let {
            if (!it.isModified) {
                it.isModified = true
                onModifiedChanged?.invoke(true)
            }
        }
    }

    private fun getLineHeight(): Float = textSizePx * lineSpacingMultiplier

    private fun getGutterWidth(): Float {
        if (!showLineNumbers) return leftPadding
        val digits = max(3, lines.size.toString().length)
        return gutterPaint.measureText("0".repeat(digits)) + 56f
    }

    private fun updateFontMetrics() {
        textPaint.textSize = textSizePx
        whitespacePaint.textSize = textSizePx * 0.72f
        gutterPaint.textSize = textSizePx * 0.78f
    }

    fun applyTheme(theme: EditorTheme) {
        currentTheme = theme
        setBackgroundColor(theme.bg)
        textPaint.color = theme.text
        whitespacePaint.color = theme.gutterText
        whitespacePaint.alpha = 130
        gutterPaint.color = theme.gutterText
        bookmarkPaint.color = theme.accent
        dividerPaint.color = theme.chromeBorder
        cursorPaint.color = theme.accent
        invalidate()
    }

    fun cycleTheme(): EditorTheme {
        val values = EditorTheme.values()
        val nextIdx = (currentTheme.ordinal + 1) % values.size
        EditorSettings.themeIndex = nextIdx
        EditorSettings.save(context)
        val next = values[nextIdx]
        applyTheme(next)
        return next
    }

    fun changeFontSize(delta: Float) {
        textSizePx = min(96f, max(24f, textSizePx + delta))
        EditorSettings.textSizePx = textSizePx
        EditorSettings.save(context)
        updateFontMetrics()
        rebuildAllWraps()
        ensureCursorVisible()
        invalidate()
    }

    fun toggleLineNumbers(): Boolean {
        showLineNumbers = !showLineNumbers
        EditorSettings.showLineNumbers = showLineNumbers
        EditorSettings.save(context)
        rebuildAllWraps()
        invalidate()
        return showLineNumbers
    }

    fun toggleSelectionMode(): Boolean {
        isSelectionMode = !isSelectionMode
        if (isSelectionMode && !hasSelection()) {
            selAnchorLine = cursorLine
            selAnchorCol = cursorCol
        }
        notifyStatus()
        invalidate()
        return isSelectionMode
    }

    fun toggleBookmarkAt(lineIdx: Int = cursorLine): Boolean {
        if (lineIdx < 0 || lineIdx >= lines.size) return false
        val added = if (bookmarks.contains(lineIdx)) {
            bookmarks.remove(lineIdx)
            false
        } else {
            bookmarks.add(lineIdx)
            true
        }
        invalidate()
        return added
    }

    fun getBookmarksSorted(): List<Int> = bookmarks.filter { it in lines.indices }.sorted()

    fun getLinePreview(lineIdx: Int): String =
        lines.getOrNull(lineIdx)?.trim()?.take(40)?.ifEmpty { "(빈 줄)" } ?: ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) rebuildAllWraps()
    }

    private fun computeWrapForLine(text: String, availWidth: Float): IntArray {
        if (!wordWrap) {
            return intArrayOf(0, text.length)
        }
        if (text.isEmpty() || availWidth <= 0f) return intArrayOf(0, 0)
        val temp = IntArray(max(8, (text.length / 10) * 2 + 4))
        var count = 0
        var start = 0
        var buf = temp
        while (start < text.length) {
            val n = textPaint.breakText(text, start, text.length, true, availWidth, null)
            val end = if (n <= 0) start + 1 else start + n
            if (count + 2 > buf.size) {
                buf = buf.copyOf(buf.size * 2)
            }
            buf[count++] = start
            buf[count++] = end
            start = end
        }
        return buf.copyOf(count)
    }

    private fun rebuildAllWraps() {
        val availWidth = max(120f, width - getGutterWidth() - rightPadding)
        lineWrapCache.clear()
        maxLinePixelWidth = 600f
        for (i in lines.indices) {
            val l = lines[i]
            lineWrapCache.add(computeWrapForLine(l, availWidth))
            if (!wordWrap && l.isNotEmpty()) {
                val w = textPaint.measureText(l)
                if (w > maxLinePixelWidth) maxLinePixelWidth = w
            }
        }
        prefixValidCount = 0
        ensurePrefixUpTo(lines.size)
        notifyStatus()
    }

    private fun updateSingleLineWrap(lineIdx: Int) {
        val availWidth = max(120f, width - getGutterWidth() - rightPadding)
        val l = lines[lineIdx]
        lineWrapCache[lineIdx] = computeWrapForLine(l, availWidth)
        if (!wordWrap) {
            val w = textPaint.measureText(l)
            if (w > maxLinePixelWidth) maxLinePixelWidth = w
        }
        if (prefixValidCount > lineIdx) prefixValidCount = lineIdx
        ensurePrefixUpTo(lines.size)
    }

    private fun invalidateWrapsFrom(lineIdx: Int) {
        if (prefixValidCount > lineIdx) prefixValidCount = lineIdx
        ensurePrefixUpTo(lines.size)
    }

    private fun ensurePrefixUpTo(targetExclusive: Int) {
        val n = lines.size
        if (prefixCounts.size < n + 1) {
            prefixCounts = prefixCounts.copyOf(max(n + 1, prefixCounts.size * 2 + 16))
        }
        if (prefixValidCount == 0) prefixCounts[0] = 0
        val limit = min(n, targetExclusive)
        for (i in prefixValidCount until limit) {
            val subLines = max(1, lineWrapCache[i].size / 2)
            prefixCounts[i + 1] = prefixCounts[i] + subLines
        }
        if (limit > prefixValidCount) prefixValidCount = limit
        if (prefixValidCount == n) totalVisualLines = prefixCounts[n]
    }

    private fun visualToLogical(vIdx: Int): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        var low = 0
        var high = lines.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val startV = prefixCounts[mid]
            val endV = prefixCounts[mid + 1]
            if (vIdx < startV) high = mid - 1
            else if (vIdx >= endV) low = mid + 1
            else return mid to (vIdx - startV)
        }
        val last = lines.size - 1
        return last to max(0, (lineWrapCache[last].size / 2) - 1)
    }

    private fun logicalToVisual(lIdx: Int, col: Int): Int {
        if (lines.isEmpty()) return 0
        val safeLine = min(lines.size - 1, max(0, lIdx))
        val wraps = lineWrapCache[safeLine]
        val subCount = max(1, wraps.size / 2)
        for (s in 0 until subCount) {
            val sEnd = wraps[s * 2 + 1]
            if (col <= sEnd || s == subCount - 1) return prefixCounts[safeLine] + s
        }
        return prefixCounts[safeLine]
    }

    fun loadFromStreamIntoSession(session: TabSession, inputStream: InputStream) {
        val bytes = inputStream.readBytes()
        val charset = detectCharset(bytes)
        session.encoding = charset
        session.lines.clear()
        session.undoStack.clear()
        session.redoStack.clear()
        session.bookmarks.clear()

        BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), charset), 65536).useLines { seq ->
            seq.forEach { session.lines.add(it) }
        }
        if (session.lines.isEmpty()) session.lines.add("")
        session.cursorLine = 0
        session.cursorCol = 0
        session.scrollYOffset = 0f
        session.isModified = false
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.isEmpty()) {
            return try { Charset.forName(EditorSettings.defaultEncoding) } catch (_: Exception) { Charsets.UTF_8 }
        }
        val sampleLen = min(bytes.size, 65536)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, sampleLen))
            Charsets.UTF_8
        } catch (e: Exception) {
            try { Charset.forName("EUC-KR") } catch (e2: Exception) { Charsets.UTF_8 }
        }
    }

    fun getAllTextSequence(): Sequence<String> = lines.asSequence()
    fun getTotalLines(): Int = lines.size

    fun getDocumentStats(): String {
        var totalChars = 0L
        var noSpaceChars = 0L
        for (line in lines) {
            totalChars += line.length
            for (ch in line) {
                if (!ch.isWhitespace()) noSpaceChars++
            }
        }
        return "논리 줄 수: %,d줄\n표시 줄 수: %,d줄\n전체 글자 수(공백 포함): %,d자\n글자 수(공백 제외): %,d자\n북마크 수: %d개\n인코딩 / 개행: %s / %s\n구문 강조 모드: %s"
            .format(
                lines.size, totalVisualLines, totalChars, noSpaceChars, bookmarks.size,
                detectedEncoding.name(), if (EditorSettings.useCrLf) "CRLF (\\r\\n)" else "LF (\\n)",
                if (syntaxHighlight) detectedLang.name else "OFF"
            )
    }

    fun goToLineAndCol(targetLineOneBased: Int, targetColOneBased: Int = 1) {
        if (lines.isEmpty()) return
        clearSelection()
        cursorLine = min(lines.size - 1, max(0, targetLineOneBased - 1))
        cursorCol = min(lines[cursorLine].length, max(0, targetColOneBased - 1))
        clearComposing()
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    private fun buildCompiledRegex(query: String, ignoreCase: Boolean): Regex? {
        return try {
            if (ignoreCase) Regex(query, setOf(RegexOption.IGNORE_CASE)) else Regex(query)
        } catch (_: Exception) {
            null
        }
    }

    fun isValidRegex(query: String): Boolean {
        return try {
            Regex(query)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun findNext(query: String, useRegex: Boolean = false, ignoreCase: Boolean = true): Boolean {
        if (query.isEmpty() || lines.isEmpty()) return false
        val regex = if (useRegex) (buildCompiledRegex(query, ignoreCase) ?: return false) else null

        for (offset in 0 until lines.size) {
            val idx = (cursorLine + offset) % lines.size
            val startSearch = if (offset == 0) min(lines[idx].length, cursorCol) else 0
            val lineText = lines[idx]

            if (regex != null) {
                val match = regex.find(lineText, startSearch)
                if (match != null) {
                    val mStart = match.range.first
                    val mEnd = match.range.last + 1
                    if (offset == 0 && mStart == mEnd && mStart == cursorCol) {
                        if (cursorCol < lineText.length) {
                            val nextMatch = regex.find(lineText, cursorCol + 1)
                            if (nextMatch != null) {
                                selAnchorLine = idx
                                selAnchorCol = nextMatch.range.first
                                cursorLine = idx
                                cursorCol = nextMatch.range.last + 1
                                clearComposing()
                                ensureCursorVisible()
                                notifyStatus()
                                invalidate()
                                return true
                            }
                        }
                        continue
                    }
                    selAnchorLine = idx
                    selAnchorCol = mStart
                    cursorLine = idx
                    cursorCol = mEnd
                    clearComposing()
                    ensureCursorVisible()
                    notifyStatus()
                    invalidate()
                    return true
                }
            } else {
                val found = lineText.indexOf(query, startSearch, ignoreCase = ignoreCase)
                if (found >= 0) {
                    selAnchorLine = idx
                    selAnchorCol = found
                    cursorLine = idx
                    cursorCol = found + query.length
                    clearComposing()
                    ensureCursorVisible()
                    notifyStatus()
                    invalidate()
                    return true
                }
            }
        }
        return false
    }

    fun replaceCurrent(query: String, replacement: String, useRegex: Boolean = false, ignoreCase: Boolean = true): Boolean {
        if (query.isEmpty()) return false
        if (hasSelection()) {
            val selected = getSelectedText()
            if (useRegex) {
                val regex = buildCompiledRegex(query, ignoreCase) ?: return false
                val entireMatch = regex.matchEntire(selected)
                if (entireMatch != null) {
                    val replacedText = try {
                        regex.replace(selected, replacement)
                    } catch (_: Exception) {
                        replacement
                    }
                    insertOrReplaceText(replacedText, isComposing = false)
                    return findNext(query, useRegex = true, ignoreCase = ignoreCase)
                }
            } else if (selected.equals(query, ignoreCase = ignoreCase)) {
                insertOrReplaceText(replacement, isComposing = false)
                return findNext(query, useRegex = false, ignoreCase = ignoreCase)
            }
        }
        return findNext(query, useRegex = useRegex, ignoreCase = ignoreCase)
    }

    fun replaceAll(query: String, replacement: String, useRegex: Boolean = false, ignoreCase: Boolean = true): Int {
        if (query.isEmpty() || lines.isEmpty()) return 0
        val regex = if (useRegex) (buildCompiledRegex(query, ignoreCase) ?: return -1) else null
        var count = 0
        val oldCopy = ArrayList<String>(lines.size)

        for (i in lines.indices) {
            val src = lines[i]
            oldCopy.add(src)
            if (regex != null) {
                val matches = regex.findAll(src).count()
                if (matches > 0) {
                    count += matches
                    lines[i] = try {
                        regex.replace(src, replacement)
                    } catch (_: Exception) {
                        regex.replace(src) { replacement }
                    }
                }
            } else if (src.contains(query, ignoreCase = ignoreCase)) {
                var idx = 0
                val sb = StringBuilder()
                while (true) {
                    val found = src.indexOf(query, idx, ignoreCase = ignoreCase)
                    if (found < 0) {
                        sb.append(src.substring(idx))
                        break
                    }
                    sb.append(src.substring(idx, found)).append(replacement)
                    idx = found + query.length
                    count++
                }
                lines[i] = sb.toString()
            }
        }

        if (count > 0) {
            pushUndo(EditRecord(0, oldCopy, ArrayList(lines), cursorLine, cursorCol, cursorLine, min(cursorCol, lines[cursorLine].length)))
            clearSelection()
            rebuildAllWraps()
            markModified()
            invalidate()
        }
        return count
    }

    fun filterLines(
        pattern: String,
        useRegex: Boolean = false,
        ignoreCase: Boolean = true,
        invertMatch: Boolean = false
    ): List<Pair<Int, String>> {
        if (pattern.isEmpty() || lines.isEmpty()) return emptyList()
        val regex = if (useRegex) (buildCompiledRegex(pattern, ignoreCase) ?: return emptyList()) else null
        val result = ArrayList<Pair<Int, String>>()
        for (i in lines.indices) {
            val text = lines[i]
            val matched = if (regex != null) {
                regex.containsMatchIn(text)
            } else {
                text.contains(pattern, ignoreCase = ignoreCase)
            }
            val include = if (invertMatch) !matched else matched
            if (include) {
                result.add(i to text)
            }
        }
        return result
    }

    fun hasSelection(): Boolean =
        selAnchorLine >= 0 && selAnchorCol >= 0 && (selAnchorLine != cursorLine || selAnchorCol != cursorCol)

    fun clearSelection() {
        selAnchorLine = -1
        selAnchorCol = -1
        isSelectionMode = false
    }

    private fun getNormalizedSelection(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (!hasSelection()) return null
        val a = selAnchorLine to selAnchorCol
        val b = cursorLine to cursorCol
        return if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a to b else b to a
    }

    fun selectAll() {
        if (lines.isEmpty()) return
        selAnchorLine = 0
        selAnchorCol = 0
        cursorLine = lines.size - 1
        cursorCol = lines[cursorLine].length
        clearComposing()
        notifyStatus()
        invalidate()
    }

    fun selectCurrentWord() {
        val line = lines.getOrNull(cursorLine) ?: return
        if (line.isEmpty()) return
        val pos = min(cursorCol, line.length - 1)
        var start = pos
        var end = pos + 1
        while (start > 0 && !line[start - 1].isWhitespace()) start--
        while (end < line.length && !line[end].isWhitespace()) end++
        selAnchorLine = cursorLine
        selAnchorCol = start
        cursorCol = end
        notifyStatus()
        invalidate()
    }

    fun getSelectedText(): String {
        val norm = getNormalizedSelection() ?: return ""
        val (start, end) = norm
        if (start.first == end.first) return lines[start.first].substring(start.second, end.second)
        val sb = StringBuilder()
        sb.append(lines[start.first].substring(start.second)).append('\n')
        for (l in start.first + 1 until end.first) sb.append(lines[l]).append('\n')
        sb.append(lines[end.first].substring(0, end.second))
        return sb.toString()
    }

    fun copySelection(): Boolean {
        val text = getSelectedText()
        if (text.isEmpty()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TextEdit", text))
        return true
    }

    fun cutSelection(): Boolean {
        if (!copySelection()) return false
        deleteSelectionInternal()
        return true
    }

    fun pasteClipboard(): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val pasteText = clip.getItemAt(0).coerceToText(context)?.toString() ?: return false
        insertOrReplaceText(pasteText, isComposing = false)
        return true
    }

    private fun deleteSelectionInternal() {
        val norm = getNormalizedSelection() ?: return
        val (start, end) = norm
        val oldSlice = lines.subList(start.first, end.first + 1).toList()
        val merged = lines[start.first].substring(0, start.second) + lines[end.first].substring(end.second)

        for (i in end.first downTo start.first + 1) {
            lines.removeAt(i)
            lineWrapCache.removeAt(i)
        }
        lines[start.first] = merged
        updateSingleLineWrap(start.first)
        invalidateWrapsFrom(start.first)

        pushUndo(EditRecord(start.first, oldSlice, listOf(merged), cursorLine, cursorCol, start.first, start.second))
        cursorLine = start.first
        cursorCol = start.second
        clearSelection()
        markModified()
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    private fun pushUndo(record: EditRecord) {
        if (isRestoringHistory) return
        if (undoStack.size >= 100) undoStack.removeFirst()
        undoStack.addLast(record)
        redoStack.clear()
    }

    fun undo(): Boolean {
        val rec = undoStack.removeLastOrNull() ?: return false
        isRestoringHistory = true
        applyEditRecord(rec.startLine, rec.newLines.size, rec.oldLines, rec.prevCursorLine, rec.prevCursorCol)
        redoStack.addLast(rec)
        isRestoringHistory = false
        return true
    }

    fun redo(): Boolean {
        val rec = redoStack.removeLastOrNull() ?: return false
        isRestoringHistory = true
        applyEditRecord(rec.startLine, rec.oldLines.size, rec.newLines, rec.afterCursorLine, rec.afterCursorCol)
        undoStack.addLast(rec)
        isRestoringHistory = false
        return true
    }

    private fun applyEditRecord(startLine: Int, removeCount: Int, insertLines: List<String>, targetLine: Int, targetCol: Int) {
        val availWidth = max(120f, width - getGutterWidth() - rightPadding)
        for (i in 0 until removeCount) {
            if (startLine < lines.size) {
                lines.removeAt(startLine)
                lineWrapCache.removeAt(startLine)
            }
        }
        for (i in insertLines.indices) {
            val l = insertLines[i]
            lines.add(startLine + i, l)
            lineWrapCache.add(startLine + i, computeWrapForLine(l, availWidth))
        }
        if (lines.isEmpty()) {
            lines.add("")
            lineWrapCache.add(intArrayOf(0, 0))
        }
        invalidateWrapsFrom(startLine)
        clearSelection()
        clearComposing()
        cursorLine = min(lines.size - 1, max(0, targetLine))
        cursorCol = min(lines[cursorLine].length, max(0, targetCol))
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    private fun moveCursorToTouch(x: Float, y: Float, keepSelection: Boolean) {
        if (lines.isEmpty()) return
        if (totalVisualLines <= 0) rebuildAllWraps()
        if (totalVisualLines <= 0) return

        clearComposing()
        if (keepSelection) {
            if (selAnchorLine < 0) {
                selAnchorLine = cursorLine
                selAnchorCol = cursorCol
            }
        } else {
            clearSelection()
        }

        val lh = getLineHeight()
        val vIdx = min(totalVisualLines - 1, max(0, ((y + scrollYOffset) / lh).toInt()))
        val (lIdx, subIdx) = visualToLogical(vIdx)
        cursorLine = lIdx

        val wraps = lineWrapCache[lIdx]
        val sStart = wraps[subIdx * 2]
        val sEnd = wraps[subIdx * 2 + 1]
        val textX = max(0f, x - getGutterWidth() + scrollXOffset)
        val sub = lines[lIdx].substring(sStart, sEnd)
        val rawOffset = textPaint.breakText(sub, true, textX, null)
        val snappedOffset = if (rawOffset < sub.length) {
            val wBefore = textPaint.measureText(sub, 0, rawOffset)
            val wChar = textPaint.measureText(sub, rawOffset, rawOffset + 1)
            if (textX - wBefore > wChar * 0.5f) rawOffset + 1 else rawOffset
        } else {
            rawOffset
        }
        cursorCol = min(lines[lIdx].length, sStart + snappedOffset)
        saveToActiveTab()
        notifyStatus()
        invalidate()
    }

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun ensureCursorVisible() {
        val lh = getLineHeight()
        val vIdx = logicalToVisual(cursorLine, cursorCol)
        val cursorY = vIdx * lh
        if (cursorY < scrollYOffset) {
            scrollYOffset = max(0f, cursorY - lh)
        } else if (cursorY + lh > scrollYOffset + height - 80f) {
            scrollYOffset = max(0f, cursorY - height + lh * 3)
        }
        if (!wordWrap) {
            val line = lines.getOrNull(cursorLine) ?: ""
            val cursorX = textPaint.measureText(line.substring(0, min(cursorCol, line.length)))
            val viewW = max(100f, width - getGutterWidth() - 40f)
            if (cursorX < scrollXOffset) scrollXOffset = max(0f, cursorX - 60f)
            else if (cursorX > scrollXOffset + viewW) scrollXOffset = cursorX - viewW + 60f
        }
    }

    private fun notifyStatus() {
        onStatusChanged?.invoke(cursorLine + 1, lines.size, cursorCol + 1, hasSelection())
    }

    private fun clearComposing() {
        composingStart = -1
        composingEnd = -1
    }

    fun insertOrReplaceText(rawText: String, isComposing: Boolean) {
        if (hasSelection()) deleteSelectionInternal()
        if (lines.isEmpty()) {
            lines.add("")
            lineWrapCache.add(intArrayOf(0, 0))
        }
        val prevLine = cursorLine
        val prevCol = cursorCol
        val current = lines[cursorLine]

        val text = if (!isComposing && rawText == "\n" && EditorSettings.autoIndent) {
            val indentLen = current.takeWhile { it == ' ' || it == '\t' }.length
            val prefixIndent = current.substring(0, min(indentLen, cursorCol))
            "\n$prefixIndent"
        } else rawText

        val replaceStart = if (composingStart >= 0 && composingEnd >= composingStart && composingEnd <= current.length) composingStart else cursorCol
        val replaceEnd = if (composingStart >= 0 && composingEnd >= composingStart && composingEnd <= current.length) composingEnd else cursorCol

        val before = current.substring(0, replaceStart)
        val after = current.substring(replaceEnd)

        if (text.contains('\n')) {
            val parts = text.split('\n')
            val newLines = ArrayList<String>(parts.size)
            newLines.add(before + parts.first())
            for (p in 1 until parts.size - 1) newLines.add(parts[p])
            val lastPart = parts.last()
            newLines.add(lastPart + after)

            val availWidth = max(120f, width - getGutterWidth() - rightPadding)
            lines[cursorLine] = newLines[0]
            lineWrapCache[cursorLine] = computeWrapForLine(newLines[0], availWidth)
            for (i in 1 until newLines.size) {
                lines.add(cursorLine + i, newLines[i])
                lineWrapCache.add(cursorLine + i, computeWrapForLine(newLines[i], availWidth))
            }
            invalidateWrapsFrom(cursorLine)

            cursorLine += parts.size - 1
            cursorCol = lastPart.length
            clearComposing()
            pushUndo(EditRecord(prevLine, listOf(current), newLines, prevLine, prevCol, cursorLine, cursorCol))
            markModified()
        } else {
            val updated = before + text + after
            lines[cursorLine] = updated
            cursorCol = replaceStart + text.length
            updateSingleLineWrap(cursorLine)

            if (isComposing && text.isNotEmpty()) {
                composingStart = replaceStart
                composingEnd = replaceStart + text.length
            } else {
                if (!isComposing) {
                    pushUndo(EditRecord(cursorLine, listOf(current), listOf(updated), prevLine, prevCol, cursorLine, cursorCol))
                    markModified()
                }
                clearComposing()
            }
        }
        if (prevLine == 0) refreshLanguageDetection()
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    fun deleteBackward() {
        if (hasSelection()) {
            deleteSelectionInternal()
            return
        }
        if (lines.isEmpty()) return
        val prevLine = cursorLine
        val prevCol = cursorCol

        if (composingStart >= 0 && composingEnd > composingStart) {
            val current = lines[cursorLine]
            lines[cursorLine] = current.removeRange(composingStart, min(current.length, composingEnd))
            cursorCol = composingStart
            clearComposing()
            updateSingleLineWrap(cursorLine)
        } else if (cursorCol > 0) {
            val current = lines[cursorLine]
            val updated = current.removeRange(cursorCol - 1, cursorCol)
            lines[cursorLine] = updated
            cursorCol--
            updateSingleLineWrap(cursorLine)
            pushUndo(EditRecord(cursorLine, listOf(current), listOf(updated), prevLine, prevCol, cursorLine, cursorCol))
            markModified()
        } else if (cursorLine > 0) {
            val prev = lines[cursorLine - 1]
            val curr = lines.removeAt(cursorLine)
            lineWrapCache.removeAt(cursorLine)
            cursorLine--
            cursorCol = prev.length
            val merged = prev + curr
            lines[cursorLine] = merged
            updateSingleLineWrap(cursorLine)
            invalidateWrapsFrom(cursorLine)
            pushUndo(EditRecord(cursorLine, listOf(prev, curr), listOf(merged), prevLine, prevCol, cursorLine, cursorCol))
            markModified()
        }
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    fun deleteForward() {
        if (hasSelection()) {
            deleteSelectionInternal()
            return
        }
        if (lines.isEmpty()) return
        val prevLine = cursorLine
        val prevCol = cursorCol
        val current = lines[cursorLine]
        if (cursorCol < current.length) {
            val updated = current.removeRange(cursorCol, cursorCol + 1)
            lines[cursorLine] = updated
            updateSingleLineWrap(cursorLine)
            pushUndo(EditRecord(cursorLine, listOf(current), listOf(updated), prevLine, prevCol, cursorLine, cursorCol))
            markModified()
        } else if (cursorLine + 1 < lines.size) {
            val next = lines.removeAt(cursorLine + 1)
            lineWrapCache.removeAt(cursorLine + 1)
            val merged = current + next
            lines[cursorLine] = merged
            updateSingleLineWrap(cursorLine)
            invalidateWrapsFrom(cursorLine)
            pushUndo(EditRecord(cursorLine, listOf(current, next), listOf(merged), prevLine, prevCol, cursorLine, cursorCol))
            markModified()
        }
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    private fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    fun moveCursorByWord(forward: Boolean, extendSelection: Boolean = false) {
        if (lines.isEmpty()) return
        clearComposing()
        val keepSel = isSelectionMode || extendSelection
        if (keepSel) {
            if (selAnchorLine < 0) {
                selAnchorLine = cursorLine
                selAnchorCol = cursorCol
            }
        } else {
            clearSelection()
        }

        val line = lines[cursorLine]
        if (forward) {
            if (cursorCol >= line.length) {
                if (cursorLine < lines.size - 1) {
                    cursorLine++
                    cursorCol = 0
                }
            } else {
                var c = cursorCol
                if (isWordChar(line[c])) {
                    while (c < line.length && isWordChar(line[c])) c++
                } else if (!line[c].isWhitespace()) {
                    while (c < line.length && !isWordChar(line[c]) && !line[c].isWhitespace()) c++
                }
                while (c < line.length && line[c].isWhitespace()) c++
                cursorCol = c
            }
        } else {
            if (cursorCol <= 0) {
                if (cursorLine > 0) {
                    cursorLine--
                    cursorCol = lines[cursorLine].length
                }
            } else {
                var c = cursorCol
                while (c > 0 && line[c - 1].isWhitespace()) c--
                if (c > 0) {
                    if (isWordChar(line[c - 1])) {
                        while (c > 0 && isWordChar(line[c - 1])) c--
                    } else {
                        while (c > 0 && !isWordChar(line[c - 1]) && !line[c - 1].isWhitespace()) c--
                    }
                }
                cursorCol = c
            }
        }
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    fun deleteWordBackward() {
        if (hasSelection()) {
            deleteSelectionInternal()
            return
        }
        if (cursorCol == 0) {
            deleteBackward()
            return
        }
        selAnchorLine = cursorLine
        selAnchorCol = cursorCol
        moveCursorByWord(forward = false, extendSelection = true)
        deleteSelectionInternal()
    }

    fun deleteWordForward() {
        if (hasSelection()) {
            deleteSelectionInternal()
            return
        }
        val line = lines.getOrNull(cursorLine) ?: return
        if (cursorCol >= line.length) {
            deleteForward()
            return
        }
        selAnchorLine = cursorLine
        selAnchorCol = cursorCol
        moveCursorByWord(forward = true, extendSelection = true)
        deleteSelectionInternal()
    }

    fun moveCursorBy(dLine: Int, dCol: Int, extendSelection: Boolean = false) {
        if (lines.isEmpty()) return
        clearComposing()
        val keepSel = isSelectionMode || extendSelection
        if (keepSel) {
            if (selAnchorLine < 0) {
                selAnchorLine = cursorLine
                selAnchorCol = cursorCol
            }
        } else {
            clearSelection()
        }

        if (dLine != 0) {
            cursorLine = min(lines.size - 1, max(0, cursorLine + dLine))
            cursorCol = min(cursorCol, lines[cursorLine].length)
        }
        if (dCol < 0) {
            if (cursorCol > 0) cursorCol--
            else if (cursorLine > 0) { cursorLine--; cursorCol = lines[cursorLine].length }
        } else if (dCol > 0) {
            if (cursorCol < lines[cursorLine].length) cursorCol++
            else if (cursorLine < lines.size - 1) { cursorLine++; cursorCol = 0 }
        }
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    fun moveCursorHomeEnd(toEnd: Boolean, extendSelection: Boolean = false) {
        if (lines.isEmpty()) return
        clearComposing()
        val keepSel = isSelectionMode || extendSelection
        if (keepSel) {
            if (selAnchorLine < 0) {
                selAnchorLine = cursorLine
                selAnchorCol = cursorCol
            }
        } else {
            clearSelection()
        }
        cursorCol = if (toEnd) lines[cursorLine].length else 0
        ensureCursorVisible()
        notifyStatus()
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            private val editable = SpannableStringBuilder()
            override fun getEditable(): Editable = editable

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                insertOrReplaceText(text?.toString() ?: "", isComposing = true)
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composingStart >= 0) {
                    clearComposing()
                    invalidate()
                }
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                insertOrReplaceText(text?.toString() ?: "", isComposing = false)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { deleteBackward() }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.keyCode == KeyEvent.KEYCODE_DEL) { deleteBackward(); return true }
                    if (event.keyCode == KeyEvent.KEYCODE_ENTER) { insertOrReplaceText("\n", isComposing = false); return true }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    private fun scrollByOffset(dx: Float, dy: Float) {
        val maxY = max(0f, totalVisualLines * getLineHeight() - height + 300f)
        scrollYOffset = min(maxY, max(0f, scrollYOffset + dy))
        if (!wordWrap) {
            val maxX = max(0f, maxLinePixelWidth - width + getGutterWidth() + 200f)
            scrollXOffset = min(maxX, max(0f, scrollXOffset + dx))
        }
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXOffset = scroller.currX.toFloat()
            scrollYOffset = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isMouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
            (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        val moveSlop = if (isMouse) 10f else max(touchSlop, 12).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hasFocus()) requestFocus()
                pointerDownX = event.x
                pointerDownY = event.y
                pointerMoved = false
                pointerButtonState = event.buttonState
                val isLeftClick = (pointerButtonState and MotionEvent.BUTTON_PRIMARY) != 0 || pointerButtonState == 0
                isMouseDragSelecting = isMouse && isLeftClick && !(showLineNumbers && event.x < getGutterWidth() - 10f)
                if (isMouseDragSelecting) {
                    moveCursorToTouch(event.x, event.y, keepSelection = false)
                    selAnchorLine = cursorLine
                    selAnchorCol = cursorCol
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pointerMoved && (abs(event.x - pointerDownX) > moveSlop || abs(event.y - pointerDownY) > moveSlop)) {
                    pointerMoved = true
                }
                if (isMouseDragSelecting && pointerMoved) {
                    val lh = getLineHeight()
                    if (event.y < lh) scrollByOffset(0f, -lh * 0.6f)
                    else if (event.y > height - lh) scrollByOffset(0f, lh * 0.6f)
                    moveCursorToTouch(event.x, event.y, keepSelection = true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val isRightClick = (pointerButtonState and MotionEvent.BUTTON_SECONDARY) != 0
                if (isMouseDragSelecting) {
                    if (!pointerMoved) {
                        clearSelection()
                        moveCursorToTouch(event.x, event.y, keepSelection = false)
                        showKeyboard()
                    }
                    isMouseDragSelecting = false
                    performClick()
                } else if (!pointerMoved && !isRightClick) {
                    requestFocus()
                    if (showLineNumbers && event.x < getGutterWidth() - 10f) {
                        val lh = getLineHeight()
                        val vIdx = min(max(0, totalVisualLines - 1), max(0, ((event.y + scrollYOffset) / lh).toInt()))
                        if (totalVisualLines > 0) {
                            val (lIdx, _) = visualToLogical(vIdx)
                            toggleBookmarkAt(lIdx)
                        }
                    } else {
                        moveCursorToTouch(event.x, event.y, keepSelection = isSelectionMode)
                        showKeyboard()
                    }
                    performClick()
                }
            }
        }
        if (!isMouseDragSelecting) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_POINTER) != 0 &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val lh = getLineHeight()
            scrollByOffset(hScroll * 80f, -vScroll * lh * 3f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.isAltPressed == true) {
            return super.onKeyDown(keyCode, event)
        }
        val shift = event?.isShiftPressed == true
        if (event?.isCtrlPressed == true) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursorByWord(forward = false, extendSelection = shift); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursorByWord(forward = true, extendSelection = shift); true }
                KeyEvent.KEYCODE_DEL -> { deleteWordBackward(); true }
                KeyEvent.KEYCODE_FORWARD_DEL -> { deleteWordForward(); true }
                KeyEvent.KEYCODE_MOVE_HOME -> {
                    if (shift && selAnchorLine < 0) { selAnchorLine = cursorLine; selAnchorCol = cursorCol }
                    else if (!shift) clearSelection()
                    cursorLine = 0
                    cursorCol = 0
                    ensureCursorVisible()
                    notifyStatus()
                    invalidate()
                    true
                }
                KeyEvent.KEYCODE_MOVE_END -> {
                    if (shift && selAnchorLine < 0) { selAnchorLine = cursorLine; selAnchorCol = cursorCol }
                    else if (!shift) clearSelection()
                    cursorLine = max(0, lines.size - 1)
                    cursorCol = lines[cursorLine].length
                    ensureCursorVisible()
                    notifyStatus()
                    invalidate()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
        val lh = getLineHeight()
        val pageJump = max(lh, height - lh * 2)
        val pageLines = max(1, (pageJump / lh).toInt())
        return when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> { moveCursorBy(pageLines, 0, shift); true }
            KeyEvent.KEYCODE_PAGE_UP -> { moveCursorBy(-pageLines, 0, shift); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (EditorSettings.volumeKeyScroll) { scrollByOffset(0f, pageJump); true }
                else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (EditorSettings.volumeKeyScroll) { scrollByOffset(0f, -pageJump); true }
                else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DEL -> { deleteBackward(); true }
            KeyEvent.KEYCODE_FORWARD_DEL -> { deleteForward(); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { insertOrReplaceText("\n", isComposing = false); true }
            KeyEvent.KEYCODE_TAB -> { insertOrReplaceText(EditorSettings.getTabString(), isComposing = false); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursorBy(0, -1, shift); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursorBy(0, 1, shift); true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveCursorBy(-1, 0, shift); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursorBy(1, 0, shift); true }
            KeyEvent.KEYCODE_MOVE_HOME -> { moveCursorHomeEnd(false, shift); true }
            KeyEvent.KEYCODE_MOVE_END -> { moveCursorHomeEnd(true, shift); true }
            else -> {
                val unicode = event?.unicodeChar ?: 0
                if (unicode > 0 && !Character.isISOControl(unicode)) {
                    insertOrReplaceText(unicode.toChar().toString(), isComposing = false)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalVisualLines == 0 || lines.isEmpty()) return

        val lh = getLineHeight()
        val gutterW = getGutterWidth()

        val firstVisible = max(0, (scrollYOffset / lh).toInt())
        val visibleCount = (height / lh).toInt() + 2
        val lastVisible = min(totalVisualLines - 1, firstVisible + visibleCount)

        val normSel = getNormalizedSelection()
        var (lIdx, subIdx) = visualToLogical(firstVisible)

        canvas.save()
        canvas.clipRect(if (showLineNumbers) gutterW - 14f else 0f, 0f, width.toFloat(), height.toFloat())

        val drawRows = ArrayList<Triple<Int, Int, Float>>(visibleCount + 2)

        for (vIdx in firstVisible..lastVisible) {
            if (lIdx >= lines.size) break
            val wraps = lineWrapCache[lIdx]
            val sStart = wraps[subIdx * 2]
            val sEnd = wraps[subIdx * 2 + 1]
            val topY = vIdx * lh - scrollYOffset
            val baselineY = topY + textSizePx
            val logicalText = lines[lIdx]
            val isLastSub = subIdx == (wraps.size / 2) - 1

            drawRows.add(Triple(lIdx, subIdx, topY))

            if (lIdx == cursorLine) {
                bgPaint.color = currentTheme.cursorLineBg
                canvas.drawRect(if (showLineNumbers) gutterW - 12f else 0f, topY, width.toFloat(), topY + lh, bgPaint)
            }

            if (normSel != null) {
                val (selS, selE) = normSel
                if (lIdx >= selS.first && lIdx <= selE.first) {
                    val lineSelStart = if (lIdx == selS.first) selS.second else 0
                    val lineSelEnd = if (lIdx == selE.first) selE.second else logicalText.length
                    val drawStart = max(sStart, lineSelStart)
                    val drawEnd = min(sEnd, lineSelEnd)
                    if (drawEnd > drawStart || (lIdx < selE.first && sEnd == logicalText.length)) {
                        val x1 = gutterW - scrollXOffset + textPaint.measureText(logicalText.substring(sStart, max(sStart, drawStart)))
                        val x2 = if (lIdx < selE.first && sEnd == logicalText.length) width.toFloat()
                                 else gutterW - scrollXOffset + textPaint.measureText(logicalText.substring(sStart, max(sStart, drawEnd)))
                        bgPaint.color = currentTheme.selectionBg
                        canvas.drawRect(x1, topY + 2f, max(x1 + 12f, x2), topY + lh - 2f, bgPaint)
                    }
                }
            }

            val segment = logicalText.substring(sStart, sEnd)
            val baseX = gutterW - scrollXOffset
            if (syntaxHighlight && detectedLang != SyntaxHighlighter.Lang.PLAIN) {
                SyntaxHighlighter.drawHighlightedSegment(
                    canvas, logicalText, sStart, sEnd, baseX, baselineY, textPaint, currentTheme, detectedLang
                )
            } else {
                canvas.drawText(segment, baseX, baselineY, textPaint)
            }

            if (showWhitespace) {
                var runX = baseX
                for (cIdx in segment.indices) {
                    val ch = segment[cIdx]
                    val cw = textPaint.measureText(segment, cIdx, cIdx + 1)
                    if (ch == ' ') {
                        canvas.drawText("\u00B7", runX + cw * 0.25f, baselineY, whitespacePaint)
                    } else if (ch == '\t') {
                        canvas.drawText("\u2192", runX + 2f, baselineY, whitespacePaint)
                    }
                    runX += cw
                }
                if (isLastSub) {
                    canvas.drawText("\u21B5", runX + 4f, baselineY, whitespacePaint)
                }
            }

            if (lIdx == cursorLine && cursorCol >= sStart && cursorCol <= sEnd) {
                if (cursorCol < sEnd || isLastSub) {
                    val beforeCursor = logicalText.substring(sStart, cursorCol)
                    val cursorX = baseX + textPaint.measureText(beforeCursor)
                    canvas.drawLine(cursorX, topY + 6f, cursorX, topY + lh - 6f, cursorPaint)

                    if (composingStart >= sStart && composingEnd <= sEnd && composingEnd > composingStart) {
                        val compStartX = baseX + textPaint.measureText(logicalText.substring(sStart, composingStart))
                        canvas.drawLine(compStartX, baselineY + 6f, cursorX, baselineY + 6f, cursorPaint)
                    }
                }
            }

            subIdx++
            if (subIdx >= max(1, wraps.size / 2)) {
                lIdx++
                subIdx = 0
            }
        }
        canvas.restore()

        if (showLineNumbers) {
            bgPaint.color = currentTheme.gutterBg
            canvas.drawRect(0f, 0f, gutterW - 14f, height.toFloat(), bgPaint)
            canvas.drawLine(gutterW - 14f, 0f, gutterW - 14f, height.toFloat(), dividerPaint)

            for ((rowLine, rowSub, topY) in drawRows) {
                if (rowSub == 0) {
                    val baselineY = topY + textSizePx
                    if (bookmarks.contains(rowLine)) {
                        canvas.drawCircle(14f, topY + lh * 0.5f, 8f, bookmarkPaint)
                        gutterPaint.color = currentTheme.accent
                    } else {
                        gutterPaint.color = currentTheme.gutterText
                    }
                    canvas.drawText("${rowLine + 1}", gutterW - 22f, baselineY, gutterPaint)
                }
            }
        }
    }
}