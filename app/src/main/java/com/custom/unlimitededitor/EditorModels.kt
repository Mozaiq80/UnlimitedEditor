package com.custom.unlimitededitor

import android.graphics.Color
import android.net.Uri
import java.nio.charset.Charset

enum class EditorTheme(
    val label: String,
    val bg: Int,
    val text: Int,
    val chromeBg: Int,
    val chromeBorder: Int,
    val gutterBg: Int,
    val gutterText: Int,
    val cursorLineBg: Int,
    val selectionBg: Int,
    val accent: Int,
    val isLightStatusBar: Boolean
) {
    JOTA_LIGHT(
        "산뜻",
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#1E293B"),
        Color.parseColor("#F1F5F9"),
        Color.parseColor("#CBD5E1"),
        Color.parseColor("#E2E8F0"),
        Color.parseColor("#334155"),
        Color.parseColor("#F0F9FF"),
        Color.parseColor("#BAE6FD"),
        Color.parseColor("#0284C7"),
        true
    ),
    MINT_PAPER(
        "민트",
        Color.parseColor("#F4FAF8"),
        Color.parseColor("#1B3B36"),
        Color.parseColor("#E3F2EE"),
        Color.parseColor("#B8DCD2"),
        Color.parseColor("#D5ECE6"),
        Color.parseColor("#1F4E46"),
        Color.parseColor("#E1F3EE"),
        Color.parseColor("#B2E5D6"),
        Color.parseColor("#0D9488"),
        true
    ),
    SEPIA(
        "세피아",
        Color.parseColor("#FBF7EE"),
        Color.parseColor("#3D3122"),
        Color.parseColor("#F2E9D8"),
        Color.parseColor("#D8C9AE"),
        Color.parseColor("#E8DEC8"),
        Color.parseColor("#4A3B28"),
        Color.parseColor("#F3EAD8"),
        Color.parseColor("#E2CFAF"),
        Color.parseColor("#B45309"),
        true
    ),
    DARK(
        "다크",
        Color.parseColor("#16181D"),
        Color.parseColor("#E6EDF3"),
        Color.parseColor("#1F242D"),
        Color.parseColor("#30363D"),
        Color.parseColor("#222733"),
        Color.parseColor("#CBD5E1"),
        Color.parseColor("#212631"),
        Color.parseColor("#264F78"),
        Color.parseColor("#58A6FF"),
        false
    ),
    EINK(
        "전자잉크",
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#000000"),
        Color.parseColor("#F2F2F2"),
        Color.parseColor("#888888"),
        Color.parseColor("#EAEAEA"),
        Color.parseColor("#000000"),
        Color.parseColor("#EAEAEA"),
        Color.parseColor("#CCCCCC"),
        Color.parseColor("#000000"),
        true
    )
}

data class EditRecord(
    val startLine: Int,
    val oldLines: List<String>,
    val newLines: List<String>,
    val prevCursorLine: Int,
    val prevCursorCol: Int,
    val afterCursorLine: Int,
    val afterCursorCol: Int
)

class TabSession(
    var title: String,
    var uri: Uri? = null,
    var webDavUrl: String? = null,
    val lines: ArrayList<String> = arrayListOf(""),
    var encoding: Charset = Charsets.UTF_8,
    var cursorLine: Int = 0,
    var cursorCol: Int = 0,
    var scrollYOffset: Float = 0f,
    var isModified: Boolean = false,
    val bookmarks: LinkedHashSet<Int> = LinkedHashSet(),
    val undoStack: ArrayDeque<EditRecord> = ArrayDeque(),
    val redoStack: ArrayDeque<EditRecord> = ArrayDeque()
)

data class RecentFileItem(
    val name: String,
    val uriString: String
)

object EditorSessionStore {
    val tabs = ArrayList<TabSession>()
    var currentTabIndex: Int = 0
}