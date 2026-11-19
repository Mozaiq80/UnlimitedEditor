package com.custom.unlimitededitor

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

object DiffEngine {

    enum class RowType { EQUAL, CHANGED, ADD, DEL }

    data class SideBySideRow(
        val type: RowType,
        val leftLineIdx: Int?,
        val rightLineIdx: Int?,
        val leftText: String,
        val rightText: String,
        val blockId: Int
    )

    data class DiffResult(
        val rows: List<SideBySideRow>,
        val blockStartRowIndices: List<Int>,
        val changedCount: Int,
        val addedCount: Int,
        val deletedCount: Int,
        val truncated: Boolean
    )

    private data class MergeSnapshot(
        val leftLines: List<String>,
        val rightLines: List<String>?,
        val activeBlockIdx: Int,
        val leftDirty: Boolean,
        val rightDirty: Boolean
    )

    fun compare(leftLines: List<String>, rightLines: List<String>, maxCompareLines: Int = 3000): DiffResult {
        val truncated = leftLines.size > maxCompareLines || rightLines.size > maxCompareLines
        val a = if (leftLines.size > maxCompareLines) leftLines.subList(0, maxCompareLines) else leftLines
        val b = if (rightLines.size > maxCompareLines) rightLines.subList(0, maxCompareLines) else rightLines

        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }

        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
            }
        }

        data class RawOp(val op: Char, val lIdx: Int?, val rIdx: Int?, val text: String)
        val ops = ArrayList<RawOp>(max(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                ops.add(RawOp('=', i, j, a[i]))
                i++; j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(RawOp('-', i, null, a[i]))
                i++
            } else {
                ops.add(RawOp('+', null, j, b[j]))
                j++
            }
        }
        while (i < n) { ops.add(RawOp('-', i, null, a[i])); i++ }
        while (j < m) { ops.add(RawOp('+', null, j, b[j])); j++ }

        val rows = ArrayList<SideBySideRow>(ops.size)
        val blockStarts = ArrayList<Int>()
        var changedCnt = 0
        var addedCnt = 0
        var deletedCnt = 0
        var idx = 0

        while (idx < ops.size) {
            if (ops[idx].op == '=') {
                val op = ops[idx]
                rows.add(SideBySideRow(RowType.EQUAL, op.lIdx, op.rIdx, op.text, op.text, -1))
                idx++
            } else {
                val delList = ArrayList<RawOp>()
                val addList = ArrayList<RawOp>()
                while (idx < ops.size && ops[idx].op != '=') {
                    if (ops[idx].op == '-') delList.add(ops[idx]) else addList.add(ops[idx])
                    idx++
                }
                val blockId = blockStarts.size
                blockStarts.add(rows.size)
                val pairCount = max(delList.size, addList.size)
                for (k in 0 until pairCount) {
                    val d = delList.getOrNull(k)
                    val ad = addList.getOrNull(k)
                    when {
                        d != null && ad != null -> {
                            rows.add(SideBySideRow(RowType.CHANGED, d.lIdx, ad.rIdx, d.text, ad.text, blockId))
                            changedCnt++
                        }
                        d != null -> {
                            rows.add(SideBySideRow(RowType.DEL, d.lIdx, null, d.text, "", blockId))
                            deletedCnt++
                        }
                        ad != null -> {
                            rows.add(SideBySideRow(RowType.ADD, null, ad.rIdx, "", ad.text, blockId))
                            addedCnt++
                        }
                    }
                }
            }
        }

        return DiffResult(rows, blockStarts, changedCnt, addedCnt, deletedCnt, truncated)
    }

    private fun buildInlineHighlight(src: String, other: String, highlightColor: Int): CharSequence {
        if (src.isEmpty() || other.isEmpty() || src == other) return src
        var prefix = 0
        val minLen = min(src.length, other.length)
        while (prefix < minLen && src[prefix] == other[prefix]) prefix++

        var suffix = 0
        while (suffix < (minLen - prefix) &&
            src[src.length - 1 - suffix] == other[other.length - 1 - suffix]
        ) {
            suffix++
        }
        val diffEnd = src.length - suffix
        if (diffEnd <= prefix) return src

        return SpannableString(src).apply {
            setSpan(BackgroundColorSpan(highlightColor), prefix, diffEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun showWinMergeDialog(
        context: Context,
        leftTitle: String,
        rightTitle: String,
        leftSourceLines: MutableList<String>,
        rightSourceLines: MutableList<String>?,
        onMerged: (leftUpdated: Boolean, rightUpdated: Boolean) -> Unit
    ) {
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var currentResult = compare(leftSourceLines, rightSourceLines ?: emptyList())
        var activeBlockIdx = if (currentResult.blockStartRowIndices.isNotEmpty()) 0 else -1
        var onlyDiffMode = false
        var leftDirty = false
        var rightDirty = false

        val mergeUndoStack = ArrayDeque<MergeSnapshot>()
        val mergeRedoStack = ArrayDeque<MergeSnapshot>()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun makeActionBtn(label: String, bgHex: String = "#FFFFFF", textHex: String = "#0F172A", onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(textHex))
                setPadding(20, 12, 20, 12)
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 2, 4, 2) }
                background = GradientDrawable().apply {
                    cornerRadius = 12f
                    setColor(Color.parseColor(bgHex))
                    setStroke(2, Color.parseColor("#CBD5E1"))
                }
                setOnClickListener { onClick() }
            }
        }

        val toolbarScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            setBackgroundColor(Color.parseColor("#E2E8F0"))
        }
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 10, 12, 10)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }

        val summaryTv = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            isFocusable = false
        }

        val scrollView = ScrollView(context).apply {
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }
        scrollView.addView(rowsContainer)

        val rowViews = ArrayList<LinearLayout>()

        fun getActiveBlockLineRangeText(): String {
            if (activeBlockIdx < 0) return ""
            val blockRows = currentResult.rows.filter { it.blockId == activeBlockIdx }
            val lNums = blockRows.mapNotNull { it.leftLineIdx?.plus(1) }
            val rNums = blockRows.mapNotNull { it.rightLineIdx?.plus(1) }
            val lStr = when {
                lNums.isEmpty() -> "없음"
                lNums.size == 1 -> "${lNums.first()}줄"
                else -> "${lNums.first()}~${lNums.last()}줄"
            }
            val rStr = when {
                rNums.isEmpty() -> "없음"
                rNums.size == 1 -> "${rNums.first()}줄"
                else -> "${rNums.first()}~${rNums.last()}줄"
            }
            return " (좌 $lStr / 우 $rStr)"
        }

        fun updateSummary() {
            val blockInfo = if (currentResult.blockStartRowIndices.isEmpty()) "차이 없음"
            else "\u25B6 선택 블록 ${activeBlockIdx + 1}/${currentResult.blockStartRowIndices.size}${getActiveBlockLineRangeText()}"
            val truncInfo = if (currentResult.truncated) " [상위 3,000줄]" else ""
            summaryTv.text = "$blockInfo  |  변경 ${currentResult.changedCount}  추가 ${currentResult.addedCount}  삭제 ${currentResult.deletedCount}$truncInfo   |   \u2191\u2193 이동 / Alt+\u2190\u2192 병합 / Ctrl+Z 취소"
        }

        fun renderRows() {
            rowsContainer.removeAllViews()
            rowViews.clear()
            updateSummary()

            if (currentResult.rows.isEmpty() || (currentResult.blockStartRowIndices.isEmpty() && onlyDiffMode)) {
                rowsContainer.addView(TextView(context).apply {
                    text = "두 파일의 내용이 완전히 동일합니다."
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(24, 60, 24, 60)
                })
                return
            }

            var renderedFirstRowOfActiveBlock = false

            for (rIdx in currentResult.rows.indices) {
                val row = currentResult.rows[rIdx]
                if (onlyDiffMode && row.type == RowType.EQUAL) continue

                val isSelectedBlock = (row.blockId >= 0 && row.blockId == activeBlockIdx)
                val isFirstOfSelected = isSelectedBlock && !renderedFirstRowOfActiveBlock
                if (isFirstOfSelected) renderedFirstRowOfActiveBlock = true

                val outerWrap = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusable = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    if (isSelectedBlock) {
                        setBackgroundColor(Color.parseColor("#0284C7"))
                        setPadding(8, if (isFirstOfSelected) 6 else 0, 8, 4)
                    }
                    if (row.blockId >= 0) {
                        setOnClickListener {
                            activeBlockIdx = row.blockId
                            renderRows()
                        }
                    }
                }

                if (isFirstOfSelected) {
                    val activeBanner = TextView(context).apply {
                        text = "\u25B6 현재 선택된 차이 블록 #${activeBlockIdx + 1}${getActiveBlockLineRangeText()}   [Alt+\u2190 좌측반영 | Alt+\u2192 우측반영]"
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#0369A1"))
                        setPadding(12, 4, 12, 4)
                    }
                    outerWrap.addView(activeBanner)
                }

                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isFocusable = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                val leftBg: Int
                val rightBg: Int
                val leftTextColor: Int
                val rightTextColor: Int

                when (row.type) {
                    RowType.EQUAL -> {
                        leftBg = Color.parseColor("#FFFFFF")
                        rightBg = Color.parseColor("#FFFFFF")
                        leftTextColor = Color.parseColor("#334155")
                        rightTextColor = Color.parseColor("#334155")
                    }
                    RowType.CHANGED -> {
                        leftBg = if (isSelectedBlock) Color.parseColor("#FEF08A") else Color.parseColor("#FEF9C3")
                        rightBg = if (isSelectedBlock) Color.parseColor("#FEF08A") else Color.parseColor("#FEF9C3")
                        leftTextColor = Color.parseColor("#713F12")
                        rightTextColor = Color.parseColor("#713F12")
                    }
                    RowType.DEL -> {
                        leftBg = if (isSelectedBlock) Color.parseColor("#FECACA") else Color.parseColor("#FEE2E2")
                        rightBg = Color.parseColor("#E2E8F0")
                        leftTextColor = Color.parseColor("#7F1D1D")
                        rightTextColor = Color.parseColor("#94A3B8")
                    }
                    RowType.ADD -> {
                        leftBg = Color.parseColor("#E2E8F0")
                        rightBg = if (isSelectedBlock) Color.parseColor("#BBF7D0") else Color.parseColor("#DCFCE7")
                        leftTextColor = Color.parseColor("#94A3B8")
                        rightTextColor = Color.parseColor("#14532D")
                    }
                }

                val marker = if (isSelectedBlock) "\u25B6" else " "
                val lNum = row.leftLineIdx?.plus(1)?.toString()?.padStart(4, ' ') ?: "    "
                val rNum = row.rightLineIdx?.plus(1)?.toString()?.padStart(4, ' ') ?: "    "

                val leftContent: CharSequence = if (row.type == RowType.CHANGED) {
                    buildInlineHighlight(row.leftText, row.rightText, Color.parseColor("#FACC15"))
                } else row.leftText

                val rightContent: CharSequence = if (row.type == RowType.CHANGED) {
                    buildInlineHighlight(row.rightText, row.leftText, Color.parseColor("#FACC15"))
                } else row.rightText

                val leftCell = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 11.5f
                    if (isSelectedBlock) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(8, 7, 8, 7)
                    setBackgroundColor(leftBg)
                    setTextColor(leftTextColor)
                    text = TextUtils.concat("$marker$lNum ", leftContent)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                }

                val centerDivider = View(context).apply {
                    setBackgroundColor(if (isSelectedBlock) Color.parseColor("#0284C7") else Color.parseColor("#94A3B8"))
                    layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT)
                }

                val rightCell = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 11.5f
                    if (isSelectedBlock) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(8, 7, 8, 7)
                    setBackgroundColor(rightBg)
                    setTextColor(rightTextColor)
                    text = TextUtils.concat("$marker$rNum ", rightContent)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                }

                rowLayout.addView(leftCell)
                rowLayout.addView(centerDivider)
                rowLayout.addView(rightCell)
                outerWrap.addView(rowLayout)
                rowsContainer.addView(outerWrap)
                rowViews.add(outerWrap)
            }
        }

        fun scrollToActiveBlock() {
            if (activeBlockIdx < 0 || activeBlockIdx >= currentResult.blockStartRowIndices.size) return
            val targetRow = currentResult.blockStartRowIndices[activeBlockIdx]
            scrollView.post {
                val childIdx = if (onlyDiffMode) {
                    currentResult.rows.take(targetRow).count { it.type != RowType.EQUAL }
                } else targetRow
                rowViews.getOrNull(childIdx)?.let { view ->
                    scrollView.smoothScrollTo(0, max(0, view.top - 100))
                }
            }
        }

        fun jumpToBlock(delta: Int) {
            val totalBlocks = currentResult.blockStartRowIndices.size
            if (totalBlocks == 0) return
            activeBlockIdx = (activeBlockIdx + delta + totalBlocks) % totalBlocks
            renderRows()
            scrollToActiveBlock()
        }

        fun undoMerge(): Boolean {
            val snap = mergeUndoStack.removeLastOrNull() ?: run {
                Toast.makeText(context, "취소할 병합 작업이 없습니다.", Toast.LENGTH_SHORT).show()
                return false
            }
            mergeRedoStack.addLast(
                MergeSnapshot(
                    leftLines = leftSourceLines.toList(),
                    rightLines = rightSourceLines?.toList(),
                    activeBlockIdx = activeBlockIdx,
                    leftDirty = leftDirty,
                    rightDirty = rightDirty
                )
            )
            leftSourceLines.clear()
            leftSourceLines.addAll(snap.leftLines)
            if (rightSourceLines != null && snap.rightLines != null) {
                rightSourceLines.clear()
                rightSourceLines.addAll(snap.rightLines)
            }
            leftDirty = snap.leftDirty
            rightDirty = snap.rightDirty
            currentResult = compare(leftSourceLines, rightSourceLines ?: emptyList())
            activeBlockIdx = if (currentResult.blockStartRowIndices.isEmpty()) -1
            else snap.activeBlockIdx.coerceIn(0, currentResult.blockStartRowIndices.size - 1)
            renderRows()
            scrollToActiveBlock()
            Toast.makeText(context, "병합 실행 취소 (Ctrl+Z)", Toast.LENGTH_SHORT).show()
            return true
        }

        fun redoMerge(): Boolean {
            val snap = mergeRedoStack.removeLastOrNull() ?: run {
                Toast.makeText(context, "다시 실행할 병합 작업이 없습니다.", Toast.LENGTH_SHORT).show()
                return false
            }
            mergeUndoStack.addLast(
                MergeSnapshot(
                    leftLines = leftSourceLines.toList(),
                    rightLines = rightSourceLines?.toList(),
                    activeBlockIdx = activeBlockIdx,
                    leftDirty = leftDirty,
                    rightDirty = rightDirty
                )
            )
            leftSourceLines.clear()
            leftSourceLines.addAll(snap.leftLines)
            if (rightSourceLines != null && snap.rightLines != null) {
                rightSourceLines.clear()
                rightSourceLines.addAll(snap.rightLines)
            }
            leftDirty = snap.leftDirty
            rightDirty = snap.rightDirty
            currentResult = compare(leftSourceLines, rightSourceLines ?: emptyList())
            activeBlockIdx = if (currentResult.blockStartRowIndices.isEmpty()) -1
            else snap.activeBlockIdx.coerceIn(0, currentResult.blockStartRowIndices.size - 1)
            renderRows()
            scrollToActiveBlock()
            Toast.makeText(context, "병합 다시 실행 (Ctrl+Y)", Toast.LENGTH_SHORT).show()
            return true
        }

        fun mergeActiveBlock(copyRightToLeft: Boolean) {
            if (activeBlockIdx < 0 || activeBlockIdx >= currentResult.blockStartRowIndices.size) return
            if (!copyRightToLeft && rightSourceLines == null) {
                Toast.makeText(context, "외부 비교 파일은 읽기 전용입니다. 좌측 탭으로만 가져올 수 있습니다.", Toast.LENGTH_SHORT).show()
                return
            }

            val blockRows = currentResult.rows.filter { it.blockId == activeBlockIdx }
            if (blockRows.isEmpty()) return

            if (mergeUndoStack.size >= 50) mergeUndoStack.removeFirst()
            mergeUndoStack.addLast(
                MergeSnapshot(
                    leftLines = leftSourceLines.toList(),
                    rightLines = rightSourceLines?.toList(),
                    activeBlockIdx = activeBlockIdx,
                    leftDirty = leftDirty,
                    rightDirty = rightDirty
                )
            )
            mergeRedoStack.clear()

            if (copyRightToLeft) {
                val leftIndices = blockRows.mapNotNull { it.leftLineIdx }
                val rightTexts = blockRows.filter { it.rightLineIdx != null }.map { it.rightText }

                val insertPos = if (leftIndices.isNotEmpty()) {
                    val first = leftIndices.first()
                    for (idx in leftIndices.asReversed()) leftSourceLines.removeAt(idx)
                    first
                } else {
                    val firstRowIdx = currentResult.blockStartRowIndices[activeBlockIdx]
                    val prevLeft = currentResult.rows.take(firstRowIdx).mapNotNull { it.leftLineIdx }.lastOrNull()
                    (prevLeft ?: -1) + 1
                }
                leftSourceLines.addAll(min(insertPos, leftSourceLines.size), rightTexts)
                leftDirty = true
                Toast.makeText(context, "선택한 차이점을 좌측 파일에 반영했습니다.", Toast.LENGTH_SHORT).show()
            } else {
                val targetRight = rightSourceLines!!
                val rightIndices = blockRows.mapNotNull { it.rightLineIdx }
                val leftTexts = blockRows.filter { it.leftLineIdx != null }.map { it.leftText }

                val insertPos = if (rightIndices.isNotEmpty()) {
                    val first = rightIndices.first()
                    for (idx in rightIndices.asReversed()) targetRight.removeAt(idx)
                    first
                } else {
                    val firstRowIdx = currentResult.blockStartRowIndices[activeBlockIdx]
                    val prevRight = currentResult.rows.take(firstRowIdx).mapNotNull { it.rightLineIdx }.lastOrNull()
                    (prevRight ?: -1) + 1
                }
                targetRight.addAll(min(insertPos, targetRight.size), leftTexts)
                rightDirty = true
                Toast.makeText(context, "선택한 차이점을 우측 파일에 반영했습니다.", Toast.LENGTH_SHORT).show()
            }

            currentResult = compare(leftSourceLines, rightSourceLines ?: emptyList())
            activeBlockIdx = if (currentResult.blockStartRowIndices.isEmpty()) -1
            else min(activeBlockIdx, currentResult.blockStartRowIndices.size - 1)
            renderRows()
            scrollToActiveBlock()
        }

        val btnPrev = makeActionBtn("\u25B2 이전 차이") { jumpToBlock(-1) }
        val btnNext = makeActionBtn("\u25BC 다음 차이") { jumpToBlock(1) }
        lateinit var btnFilter: TextView
        btnFilter = makeActionBtn("차이만 보기") {
            onlyDiffMode = !onlyDiffMode
            btnFilter.text = if (onlyDiffMode) "전체 보기" else "차이만 보기"
            renderRows()
        }
        val btnMergeLeft = makeActionBtn("\u25C0 좌측에 반영", "#E0F2FE", "#0369A1") { mergeActiveBlock(copyRightToLeft = true) }
        val btnMergeRight = makeActionBtn("우측에 반영 \u25B6", "#DCFCE7", "#15803D") { mergeActiveBlock(copyRightToLeft = false) }
        val btnUndoMerge = makeActionBtn("실행취소") { undoMerge() }
        val btnClose = makeActionBtn("닫기", "#FEE2E2", "#B91C1C") { dialog.dismiss() }

        listOf(btnPrev, btnNext, btnFilter, btnMergeLeft, btnMergeRight, btnUndoMerge, btnClose).forEach { toolbar.addView(it) }
        toolbarScroll.addView(toolbar)

        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(12, 8, 12, 8)
        }
        val leftHeader = TextView(context).apply {
            text = " 좌측(기준): $leftTitle"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rightHeader = TextView(context).apply {
            text = " 우측(비교): $rightTitle"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerBar.addView(leftHeader)
        headerBar.addView(rightHeader)

        root.addView(toolbarScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(summaryTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(headerBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView)

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            if (event.isAltPressed || event.isCtrlPressed) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { jumpToBlock(1); return@setOnKeyListener true }
                    KeyEvent.KEYCODE_DPAD_UP -> { jumpToBlock(-1); return@setOnKeyListener true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { mergeActiveBlock(copyRightToLeft = true); return@setOnKeyListener true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { mergeActiveBlock(copyRightToLeft = false); return@setOnKeyListener true }
                }
            }

            if (!event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { jumpToBlock(1); return@setOnKeyListener true }
                    KeyEvent.KEYCODE_DPAD_UP -> { jumpToBlock(-1); return@setOnKeyListener true }
                }
            }

            if (event.isCtrlPressed) {
                when (keyCode) {
                    KeyEvent.KEYCODE_Z -> {
                        if (event.isShiftPressed) redoMerge() else undoMerge()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_Y -> {
                        redoMerge()
                        return@setOnKeyListener true
                    }
                }
            }

            if (keyCode == KeyEvent.KEYCODE_F8) {
                jumpToBlock(if (event.isShiftPressed) -1 else 1)
                return@setOnKeyListener true
            }

            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dialog.dismiss()
                return@setOnKeyListener true
            }

            false
        }

        dialog.setOnDismissListener {
            if (leftDirty || rightDirty) {
                onMerged(leftDirty, rightDirty)
            }
        }

        renderRows()
        dialog.show()
        root.requestFocus()
    }
}