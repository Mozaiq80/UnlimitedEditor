package com.custom.unlimitededitor

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var editorView: LargeTextEditorView
    private lateinit var menuBar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var quickScroll: HorizontalScrollView
    private lateinit var quickBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var menuDivider: View
    private lateinit var tabDivider: View
    private lateinit var quickDivider: View
    private lateinit var bottomDivider: View

    private lateinit var statusText: TextView
    private lateinit var encodingText: TextView
    private lateinit var btnFileMenu: TextView
    private lateinit var btnUndo: TextView
    private lateinit var btnSelectMode: TextView

    private val flatButtons = ArrayList<TextView>()
    private val tabs: ArrayList<TabSession> get() = EditorSessionStore.tabs
    private var currentTabIndex: Int
        get() = EditorSessionStore.currentTabIndex
        set(value) { EditorSessionStore.currentTabIndex = value }

    private var pendingCompareMode = false

    private var lastSearchQuery = ""
    private var lastReplaceQuery = ""
    private var lastSearchRegex = false
    private var lastSearchCaseSensitive = false
    private var lastFilterPattern = ""
    private var lastFilterRegex = false
    private var lastFilterCaseSensitive = false
    private var lastFilterInvert = false
    private var lastPublishTargetIdx = 0

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }

            if (pendingCompareMode) {
                pendingCompareMode = false
                compareWithExternalUri(it)
            } else {
                openUriInNewTab(it)
            }
        } ?: run { pendingCompareMode = false }
    }

    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let { saveCurrentTabToUri(it) }
    }

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            val name = resolveFileName(it).substringBeforeLast('.')
            val mdTag = "![$name]($it)"
            editorView.insertOrReplaceText(mdTag, isComposing = false)
            Toast.makeText(this, "마크다운 이미지 태그가 삽입되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EditorSettings.load(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requestExitApp()
            }
        })

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun makeFlatBtn(label: String, sizeSp: Float = 13f, bold: Boolean = false, onClick: (View) -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = sizeSp
                if (bold) typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(24, 12, 24, 12)
                isFocusable = true
                isFocusableInTouchMode = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 2, 4, 2) }
                setOnClickListener { onClick(it) }
                setOnFocusChangeListener { _, hasFocus ->
                    val active = (this == btnSelectMode && ::editorView.isInitialized && editorView.isSelectionMode)
                    styleFlatButton(this, isActive = active, isFocused = hasFocus)
                }
                setOnKeyListener { v, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_ENTER ||
                         keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                         keyCode == KeyEvent.KEYCODE_SPACE ||
                         keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
                    ) {
                        v.performClick()
                        true
                    } else false
                }
                flatButtons.add(this)
            }
        }

        fun makeDivider(): View = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        }

        val menuScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        menuBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 6, 10, 6)
            gravity = Gravity.CENTER_VERTICAL
        }

        btnFileMenu = makeFlatBtn("파일 \u25BE", bold = true) { showFileDropdown(it) }
        val btnEditMenu = makeFlatBtn("편집 \u25BE", bold = true) { showEditDropdown(it) }
        val btnSearchMenu = makeFlatBtn("검색 \u25BE", bold = true) { showSearchDropdown(it) }
        val btnBookmarkMenu = makeFlatBtn("북마크 \u25BE", bold = true) { showBookmarkDropdown(it) }
        val btnToolsMenu = makeFlatBtn("도구 \u25BE", bold = true) { showToolsDropdown(it) }
        val btnSettingsDirect = makeFlatBtn("설정", bold = true) { showSettingsDialog() }

        listOf(btnFileMenu, btnEditMenu, btnSearchMenu, btnBookmarkMenu, btnToolsMenu, btnSettingsDirect).forEach {
            menuBar.addView(it)
        }
        menuScroll.addView(menuBar)
        menuDivider = makeDivider()

        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 6, 10, 6)
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroll.addView(tabBar)
        tabDivider = makeDivider()

        editorView = LargeTextEditorView(this).apply {
            onStatusChanged = { line, total, col, hasSel ->
                val selTag = if (hasSel) " [선택]" else ""
                statusText.text = "줄 $line/$total  칸 $col$selTag"
                btnSelectMode.text = if (isSelectionMode) "선택:ON" else "선택"
                styleFlatButton(btnSelectMode, isActive = isSelectionMode, isFocused = btnSelectMode.isFocused)
            }
            onModifiedChanged = {
                rebuildTabBar()
            }
        }

        quickDivider = makeDivider()
        quickScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        quickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 6, 8, 6)
            gravity = Gravity.CENTER_VERTICAL
        }

        btnUndo = makeFlatBtn("실행취소", 12.5f) { if (!editorView.undo()) Toast.makeText(this, "취소할 작업 없음", Toast.LENGTH_SHORT).show() }
        val btnRedo = makeFlatBtn("다시실행", 12.5f) { if (!editorView.redo()) Toast.makeText(this, "다시 실행할 작업 없음", Toast.LENGTH_SHORT).show() }
        btnSelectMode = makeFlatBtn("선택", 12.5f) { editorView.toggleSelectionMode() }
        val btnCopy = makeFlatBtn("복사", 12.5f) {
            if (editorView.copySelection()) Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "먼저 범위를 선택하세요", Toast.LENGTH_SHORT).show()
        }
        val btnPaste = makeFlatBtn("붙여넣기", 12.5f) { editorView.pasteClipboard() }
        val btnBookmarkQuick = makeFlatBtn("북마크", 12.5f) {
            val added = editorView.toggleBookmarkAt()
            Toast.makeText(this, if (added) "현재 줄 북마크 추가" else "현재 줄 북마크 해제", Toast.LENGTH_SHORT).show()
        }
        val btnTabQuick = makeFlatBtn("Tab", 12.5f) { editorView.insertOrReplaceText(EditorSettings.getTabString(), false) }
        val btnLeft = makeFlatBtn("\u25C0", 12.5f) { editorView.moveCursorBy(0, -1) }
        val btnRight = makeFlatBtn("\u25B6", 12.5f) { editorView.moveCursorBy(0, 1) }
        val btnUp = makeFlatBtn("\u25B2", 12.5f) { editorView.moveCursorBy(-1, 0) }
        val btnDown = makeFlatBtn("\u25BC", 12.5f) { editorView.moveCursorBy(1, 0) }
        val btnHome = makeFlatBtn("Home", 12.5f) { editorView.moveCursorHomeEnd(false) }
        val btnEnd = makeFlatBtn("End", 12.5f) { editorView.moveCursorHomeEnd(true) }

        listOf(btnUndo, btnRedo, btnSelectMode, btnCopy, btnPaste, btnBookmarkQuick, btnTabQuick, btnLeft, btnRight, btnUp, btnDown, btnHome, btnEnd).forEach {
            quickBar.addView(it)
        }
        quickScroll.addView(quickBar)

        bottomDivider = makeDivider()
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18, 8, 14, 10)
            gravity = Gravity.CENTER_VERTICAL
        }

        statusText = TextView(this).apply {
            text = "줄 1/1  칸 1"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            setOnClickListener { showJumpDialog() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        encodingText = makeFlatBtn(EditorSettings.defaultEncoding, 12f, bold = true) {
            toggleEncoding()
        }

        val btnKeyboard = makeFlatBtn("키보드", 12f, bold = true) {
            editorView.requestFocus()
            editorView.showKeyboard()
        }

        bottomBar.addView(statusText)
        bottomBar.addView(encodingText)
        bottomBar.addView(btnKeyboard)

        root.addView(menuScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(menuDivider)
        root.addView(tabScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(tabDivider)
        root.addView(editorView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(quickDivider)
        root.addView(quickScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(bottomDivider)
        root.addView(bottomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        applyRuntimePreferences()

        if (tabs.isNotEmpty()) {
            switchToTab(currentTabIndex.coerceIn(0, tabs.lastIndex))
        } else if (!restoreSavedTabs()) {
            val defaultCs = try { Charset.forName(EditorSettings.defaultEncoding) } catch (_: Exception) { Charsets.UTF_8 }
            val appPrefs = getSharedPreferences("unlimited_editor_prefs", Context.MODE_PRIVATE)
            if (!appPrefs.getBoolean("welcome_shown_v2", false)) {
                appPrefs.edit().putBoolean("welcome_shown_v2", true).commit()
                tabs.add(
                    TabSession(
                        title = "시작하기.txt",
                        encoding = defaultCs,
                        lines = arrayListOf(
                            "상단 [설정 (Ctrl+,)] 버튼에서 Jota+ 스타일의 모든 옵션을 자유롭게 설정하세요.",
                            "",
                            "\u00B7 화면/글꼴: 글꼴(고딕/고정폭/명조), 크기, 줄간격, 자동줄바꿈, 구문강조, 공백개행 기호",
                            "\u00B7 검색/필터: 정규식(Regex) 찾기바꾸기(Ctrl+F), 패턴 줄 필터링(Ctrl+Shift+F)",
                            "\u00B7 발행/연동: WebDAV 탐색(Ctrl+Shift+O), 워드프레스GitHubNotionHTML 발행(Ctrl+P)"
                        )
                    )
                )
            } else {
                tabs.add(TabSession(title = "제목없음.txt", encoding = defaultCs))
            }
            switchToTab(0)
        }
        syncChromeColors()
        editorView.requestFocus()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        editorView.post {
            editorView.applyAllSettings()
            rebuildTabBar()
        }
    }

    override fun onPause() {
        super.onPause()
        saveOpenTabsState()
    }

    private fun applyRuntimePreferences() {
        editorView.applyAllSettings()
        editorView.keepScreenOn = EditorSettings.keepScreenOn
        val vis = if (EditorSettings.showQuickBar) View.VISIBLE else View.GONE
        quickDivider.visibility = vis
        quickScroll.visibility = vis
        syncChromeColors()
    }

    private fun showSettingsDialog() {
        val scroll = ScrollView(this).apply {
            setPadding(36, 20, 36, 20)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addSectionHeader(title: String) {
            container.addView(TextView(this).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0284C7"))
                setPadding(0, 24, 0, 10)
            })
        }

        fun addSwitchRow(label: String, desc: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = label
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(this).apply {
                text = desc
                textSize = 11.5f
                setTextColor(Color.GRAY)
            })
            val sw = Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _: android.widget.CompoundButton, isChecked: Boolean -> onChanged(isChecked) }
            }
            row.addView(textCol)
            row.addView(sw)
            container.addView(row)
        }

        fun addSpinnerRow(label: String, options: List<String>, selectedIdx: Int, onSelected: (Int) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(selectedIdx.coerceIn(0, options.lastIndex))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        onSelected(position)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            row.addView(tv)
            row.addView(spinner)
            container.addView(row)
        }

        addSectionHeader("1. 화면 및 글꼴")
        addSpinnerRow("화면 테마", EditorTheme.values().map { it.label }, EditorSettings.themeIndex) {
            EditorSettings.themeIndex = it
        }
        addSpinnerRow("글꼴 종류", listOf("기본 고딕 (Sans-serif)", "고정폭 코딩체 (Monospace)", "명조체 (Serif)"), EditorSettings.fontFamilyIndex) {
            EditorSettings.fontFamilyIndex = it
        }

        val sizeLabel = TextView(this).apply {
            text = "글자 크기: ${EditorSettings.textSizePx.toInt()}px"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 4)
        }
        val sizeSeek = SeekBar(this).apply {
            max = 72
            progress = (EditorSettings.textSizePx.toInt() - 24).coerceIn(0, 72)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    EditorSettings.textSizePx = (p + 24).toFloat()
                    sizeLabel.text = "글자 크기: ${EditorSettings.textSizePx.toInt()}px"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(sizeLabel)
        container.addView(sizeSeek)

        val spacingLabel = TextView(this).apply {
            text = "줄 간격 배율: %.2fx".format(EditorSettings.lineSpacing)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 4)
        }
        val spacingSeek = SeekBar(this).apply {
            max = 90
            progress = ((EditorSettings.lineSpacing - 1.10f) * 100).toInt().coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    EditorSettings.lineSpacing = 1.10f + (p / 100f)
                    spacingLabel.text = "줄 간격 배율: %.2fx".format(EditorSettings.lineSpacing)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(spacingLabel)
        container.addView(spacingSeek)

        addSwitchRow("자동 줄바꿈 (Word Wrap)", "끄면 긴 문장을 가로 스크롤로 표시", EditorSettings.wordWrap) {
            EditorSettings.wordWrap = it
        }
        addSwitchRow("구문 강조 (Syntax Highlighting)", "Kotlin/Python/JS/HTML/JSON/SQL/Markdown 색상 강조", EditorSettings.syntaxHighlight) {
            EditorSettings.syntaxHighlight = it
        }
        addSwitchRow("줄번호 표시", "왼쪽 거터에 줄번호 및 북마크 마커 표시", EditorSettings.showLineNumbers) {
            EditorSettings.showLineNumbers = it
        }
        addSwitchRow("공백  탭  줄바꿈 기호 표시", "스페이스(\u00B7), 탭(\u2192), 줄바꿈(\u21B5) 시각화", EditorSettings.showWhitespace) {
            EditorSettings.showWhitespace = it
        }
        addSwitchRow("화면 꺼짐 방지", "문서 열람/편집 중 화면 자동 잠금 방지", EditorSettings.keepScreenOn) {
            EditorSettings.keepScreenOn = it
        }

        addSectionHeader("2. 편집 및 입력")
        addSwitchRow("자동 들여쓰기 (Auto-Indent)", "엔터 입력 시 윗줄의 들여쓰기 간격 유지", EditorSettings.autoIndent) {
            EditorSettings.autoIndent = it
        }
        val tabOptions = listOf("공백 2칸", "공백 4칸", "공백 8칸", "실제 Tab 문자 (\\t)")
        val currTabIdx = when {
            EditorSettings.useHardTab -> 3
            EditorSettings.tabWidth == 2 -> 0
            EditorSettings.tabWidth == 8 -> 2
            else -> 1
        }
        addSpinnerRow("Tab 키 동작", tabOptions, currTabIdx) { idx ->
            when (idx) {
                0 -> { EditorSettings.useHardTab = false; EditorSettings.tabWidth = 2 }
                1 -> { EditorSettings.useHardTab = false; EditorSettings.tabWidth = 4 }
                2 -> { EditorSettings.useHardTab = false; EditorSettings.tabWidth = 8 }
                3 -> { EditorSettings.useHardTab = true }
            }
        }
        addSwitchRow("하단 확장 입력 바 표시", "실행취소선택방향키 퀵 바 표시", EditorSettings.showQuickBar) {
            EditorSettings.showQuickBar = it
        }
        addSwitchRow("볼륨키 페이지 스크롤", "볼륨 위/아래 버튼으로 한 페이지씩 이동", EditorSettings.volumeKeyScroll) {
            EditorSettings.volumeKeyScroll = it
        }

        addSectionHeader("3. 파일, 인코딩 및 NAS WebDAV")
        addSpinnerRow("새 파일 기본 인코딩", listOf("UTF-8", "EUC-KR"), if (EditorSettings.defaultEncoding == "EUC-KR") 1 else 0) {
            EditorSettings.defaultEncoding = if (it == 1) "EUC-KR" else "UTF-8"
        }
        addSpinnerRow("저장 시 줄바꿈 코드", listOf("LF (\\n, 표준)", "CRLF (\\r\\n, Windows)"), if (EditorSettings.useCrLf) 1 else 0) {
            EditorSettings.useCrLf = (it == 1)
        }
        addSwitchRow("시작 시 이전 탭 자동 복원", "저장된 파일은 물론 미저장 메모 탭까지 모두 복원", EditorSettings.restoreTabsOnStart) {
            EditorSettings.restoreTabsOnStart = it
        }

        val davUrlInput = EditText(this).apply {
            hint = "WebDAV 서버 주소 (예: https://도메인:5006/폴더)"
            setText(EditorSettings.webDavBaseUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val davUserInput = EditText(this).apply {
            hint = "WebDAV 아이디"
            setText(EditorSettings.webDavUsername)
        }
        val davPassInput = EditText(this).apply {
            hint = "WebDAV 비밀번호"
            setText(EditorSettings.webDavPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(davUrlInput)
        container.addView(davUserInput)
        container.addView(davPassInput)

        val btnOpenPublishCfg = Button(this).apply {
            text = "외부 발행 계정 설정 (워드프레스  GitHub  Notion)"
            setOnClickListener { showPublishAccountsDialog() }
        }
        container.addView(btnOpenPublishCfg)

        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("TextEdit 환경설정")
            .setView(scroll)
            .setPositiveButton("적용 및 저장") { _, _ ->
                EditorSettings.webDavBaseUrl = davUrlInput.text.toString().trim()
                EditorSettings.webDavUsername = davUserInput.text.toString().trim()
                EditorSettings.webDavPassword = davPassInput.text.toString()
                EditorSettings.save(this)
                applyRuntimePreferences()
                Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // --- 외부 발행 플랫폼 계정 설정 (WordPress / GitHub / Notion) ---
    private fun showPublishAccountsDialog(onSaved: (() -> Unit)? = null) {
        val scroll = ScrollView(this).apply { setPadding(36, 16, 36, 16) }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun addHeader(title: String) {
            container.addView(TextView(this).apply {
                text = title
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0284C7"))
                setPadding(0, 18, 0, 6)
            })
        }

        addHeader("1. 워드프레스 (WordPress REST API)")
        val wpUrl = EditText(this).apply {
            hint = "사이트 주소 (예: https://myblog.com)"
            setText(EditorSettings.wpSiteUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val wpUser = EditText(this).apply {
            hint = "워드프레스 사용자 아이디"
            setText(EditorSettings.wpUsername)
        }
        val wpPass = EditText(this).apply {
            hint = "애플리케이션 비밀번호 (xxxx xxxx xxxx ...)"
            setText(EditorSettings.wpAppPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(wpUrl)
        container.addView(wpUser)
        container.addView(wpPass)

        addHeader("2. GitHub 블로그 (Jekyll / Hugo / Astro)")
        val ghRepo = EditText(this).apply {
            hint = "저장소 (예: username/username.github.io)"
            setText(EditorSettings.ghOwnerRepo)
        }
        val ghBranch = EditText(this).apply {
            hint = "브랜치 (기본: main)"
            setText(EditorSettings.ghBranch)
        }
        val ghToken = EditText(this).apply {
            hint = "Personal Access Token (ghp_...)"
            setText(EditorSettings.ghToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val ghPostsDir = EditText(this).apply {
            hint = "포스트 저장 폴더 (예: _posts)"
            setText(EditorSettings.ghPostsDir)
        }
        val ghImagesDir = EditText(this).apply {
            hint = "이미지 업로드 폴더 (예: assets/images)"
            setText(EditorSettings.ghImagesDir)
        }
        container.addView(ghRepo)
        container.addView(ghBranch)
        container.addView(ghToken)
        container.addView(ghPostsDir)
        container.addView(ghImagesDir)

        addHeader("3. Notion (노션 API)")
        val notionToken = EditText(this).apply {
            hint = "Integration Token (ntn_... 또는 secret_...)"
            setText(EditorSettings.notionToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val notionPageId = EditText(this).apply {
            hint = "상위 Page ID (32자리 ID)"
            setText(EditorSettings.notionParentPageId)
        }
        container.addView(notionToken)
        container.addView(notionPageId)

        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("외부 발행 계정 설정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                EditorSettings.wpSiteUrl = wpUrl.text.toString().trim()
                EditorSettings.wpUsername = wpUser.text.toString().trim()
                EditorSettings.wpAppPassword = wpPass.text.toString().trim()

                EditorSettings.ghOwnerRepo = ghRepo.text.toString().trim()
                EditorSettings.ghBranch = ghBranch.text.toString().trim().ifEmpty { "main" }
                EditorSettings.ghToken = ghToken.text.toString().trim()
                EditorSettings.ghPostsDir = ghPostsDir.text.toString().trim()
                EditorSettings.ghImagesDir = ghImagesDir.text.toString().trim()

                EditorSettings.notionToken = notionToken.text.toString().trim()
                EditorSettings.notionParentPageId = notionPageId.text.toString().trim()

                EditorSettings.save(this)
                Toast.makeText(this, "발행 계정 정보가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // --- 다중 플랫폼 외부 발행 / 게시 다이얼로그 (Ctrl+P) ---
    private fun showPublishDialog() {
        val tab = tabs[currentTabIndex]
        val defaultTitle = MarkdownConverter.extractTitle(tab.lines, tab.title)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(38, 20, 38, 12)
        }

        val targetOptions = PublishTargetType.values().map { it.label }
        val targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, targetOptions)
            setSelection(lastPublishTargetIdx.coerceIn(0, targetOptions.lastIndex))
        }

        val titleInput = EditText(this).apply {
            hint = "게시글 제목"
            setText(defaultTitle)
        }

        val statusGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val rbDraft = RadioButton(this).apply {
            id = View.generateViewId()
            text = "임시저장 (Draft)"
            isChecked = true
            setPadding(0, 0, 24, 0)
        }
        val rbPublish = RadioButton(this).apply {
            id = View.generateViewId()
            text = "즉시발행 (Publish)"
        }
        statusGroup.addView(rbDraft)
        statusGroup.addView(rbPublish)

        val imgCount = MarkdownConverter.extractImages(tab.lines.joinToString("\n")).size
        val infoTv = TextView(this).apply {
            text = "감지된 마크다운 이미지: ${imgCount}개 (발행 시 자동 업로드 및 경로 치환)"
            textSize = 12f
            setTextColor(Color.parseColor("#0369A1"))
            setPadding(4, 6, 4, 10)
        }

        val progressTv = TextView(this).apply {
            text = ""
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#15803D"))
            setPadding(4, 4, 4, 8)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnAccountCfg = Button(this).apply {
            text = "계정토큰 설정"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnTestConn = Button(this).apply {
            text = "WP 연결 테스트"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(btnAccountCfg)
        btnRow.addView(btnTestConn)

        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 180)
        }
        logScroll.addView(progressTv)

        layout.addView(TextView(this).apply { text = "발행 대상 플랫폼"; textSize = 12f; setTextColor(Color.GRAY) })
        layout.addView(targetSpinner)
        layout.addView(TextView(this).apply { text = "게시글 제목"; textSize = 12f; setTextColor(Color.GRAY); setPadding(0, 10, 0, 0) })
        layout.addView(titleInput)
        layout.addView(statusGroup)
        layout.addView(infoTv)
        layout.addView(btnRow)
        layout.addView(logScroll)

        val dialog = AlertDialog.Builder(this)
            .setTitle("외부 발행 / 게시 (${tab.title})")
            .setView(layout)
            .setPositiveButton("발행 실행", null)
            .setNegativeButton("닫기", null)
            .create()

        btnAccountCfg.setOnClickListener {
            showPublishAccountsDialog()
        }

        btnTestConn.setOnClickListener {
            btnTestConn.isEnabled = false
            progressTv.setTextColor(Color.parseColor("#0284C7"))
            progressTv.text = "워드프레스 서버 연결 및 인증 확인 중..."
            lifecycleScope.launch(Dispatchers.IO) {
                val res = PublishManager.testWordPressConnection()
                withContext(Dispatchers.Main) {
                    btnTestConn.isEnabled = true
                    progressTv.setTextColor(if (res.success) Color.parseColor("#15803D") else Color.parseColor("#B91C1C"))
                    progressTv.text = res.message
                }
            }
        }

        dialog.setOnShowListener {
            val btnRun = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnRun.setOnClickListener {
                val targetType = PublishTargetType.values()[targetSpinner.selectedItemPosition]
                lastPublishTargetIdx = targetSpinner.selectedItemPosition
                val postTitle = titleInput.text.toString().trim().ifEmpty { defaultTitle }
                val isDraft = rbDraft.isChecked
                val rawMd = tab.lines.joinToString("\n")

                btnRun.isEnabled = false
                progressTv.setTextColor(Color.parseColor("#0284C7"))
                progressTv.text = "발행 준비 중..."

                lifecycleScope.launch(Dispatchers.IO) {
                    val outcome = PublishManager.publish(
                        this@MainActivity,
                        PublishRequest(
                            target = targetType,
                            title = postTitle,
                            rawMarkdown = rawMd,
                            originalFileName = tab.title,
                            isDraft = isDraft,
                            webDavFileUrl = tab.webDavUrl
                        )
                    ) { msg ->
                        runOnUiThread { progressTv.text = msg }
                    }

                    withContext(Dispatchers.Main) {
                        btnRun.isEnabled = true
                        if (outcome.success) {
                            progressTv.setTextColor(Color.parseColor("#15803D"))
                            progressTv.text = outcome.message
                            if (!outcome.publishedUrl.isNullOrEmpty()) {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Published URL", outcome.publishedUrl))
                                Toast.makeText(this@MainActivity, "게시 완료! URL이 클립보드에 복사되었습니다.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, outcome.message, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            progressTv.setTextColor(Color.parseColor("#B91C1C"))
                            progressTv.text = outcome.message
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showWebDavConfigDialog(onSaved: (() -> Unit)? = null) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val urlInput = EditText(this).apply {
            hint = "WebDAV URL (예: https://도메인:5006/notes)"
            setText(EditorSettings.webDavBaseUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val userInput = EditText(this).apply {
            hint = "NAS 사용자 아이디"
            setText(EditorSettings.webDavUsername)
        }
        val passInput = EditText(this).apply {
            hint = "NAS 비밀번호"
            setText(EditorSettings.webDavPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(urlInput)
        layout.addView(userInput)
        layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("NAS WebDAV 서버 설정")
            .setView(layout)
            .setPositiveButton("저장 및 연결") { _, _ ->
                EditorSettings.webDavBaseUrl = urlInput.text.toString().trim()
                EditorSettings.webDavUsername = userInput.text.toString().trim()
                EditorSettings.webDavPassword = passInput.text.toString()
                EditorSettings.save(this)
                onSaved?.invoke()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showWebDavBrowserDialog(isSaveMode: Boolean) {
        if (EditorSettings.webDavBaseUrl.isBlank()) {
            showWebDavConfigDialog {
                if (EditorSettings.webDavBaseUrl.isNotBlank()) {
                    showWebDavBrowserDialog(isSaveMode)
                }
            }
            return
        }

        val rootUrl = WebDavClient.normalizeDirUrl(EditorSettings.webDavBaseUrl)
        var currentDirUrl = rootUrl

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 18, 32, 12)
        }

        val topCtrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pathTv = TextView(this).apply {
            text = currentDirUrl
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0284C7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnUpDir = Button(this).apply { text = "\u2B06 상위" }
        val btnCfg = Button(this).apply { text = "서버설정" }
        topCtrlRow.addView(pathTv)
        topCtrlRow.addView(btnUpDir)
        topCtrlRow.addView(btnCfg)
        rootLayout.addView(topCtrlRow)

        val saveFileNameInput = EditText(this).apply {
            hint = "저장할 원격 파일명 (예: memo.txt)"
            setText(tabs[currentTabIndex].title)
            visibility = if (isSaveMode) View.VISIBLE else View.GONE
        }
        if (isSaveMode) {
            rootLayout.addView(saveFileNameInput)
        }

        val statusTv = TextView(this).apply {
            text = "목록 불러오는 중..."
            textSize = 12f
            setPadding(4, 8, 4, 8)
        }
        rootLayout.addView(statusTv)

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.42f).toInt()
            )
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
        }
        listScroll.addView(listContainer)
        rootLayout.addView(listScroll)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (isSaveMode) "WebDAV 폴더에 업로드" else "WebDAV 파일 열기")
            .setView(rootLayout)
            .setNegativeButton("닫기", null)

        if (isSaveMode) {
            builder.setPositiveButton("현재 폴더에 저장") { _, _ ->
                val rawName = saveFileNameInput.text.toString().trim()
                if (rawName.isEmpty()) {
                    Toast.makeText(this, "파일명을 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val encoded = WebDavClient.encodeSegment(rawName)
                val targetFileUrl = "${WebDavClient.normalizeDirUrl(currentDirUrl)}$encoded"
                uploadCurrentTabToWebDav(targetFileUrl, rawName)
            }
        }

        val dialog = builder.create()

        fun loadDir(targetUrl: String) {
            currentDirUrl = WebDavClient.normalizeDirUrl(targetUrl)
            pathTv.text = currentDirUrl
            statusTv.text = "불러오는 중..."
            listContainer.removeAllViews()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val items = WebDavClient.listDirectory(
                        currentDirUrl,
                        EditorSettings.webDavUsername,
                        EditorSettings.webDavPassword
                    )
                    withContext(Dispatchers.Main) {
                        statusTv.text = "폴더 ${items.count { it.isDirectory }}개, 파일 ${items.count { !it.isDirectory }}개"
                        listContainer.removeAllViews()
                        if (items.isEmpty()) {
                            listContainer.addView(TextView(this@MainActivity).apply {
                                text = "(비어 있는 폴더입니다)"
                                setPadding(16, 24, 16, 24)
                            })
                        }
                        for (item in items) {
                            val rowTv = TextView(this@MainActivity).apply {
                                val sizeStr = if (item.isDirectory) "" else "  (${formatByteSize(item.sizeBytes)})"
                                text = if (item.isDirectory) "\uD83D\uDCC1 ${item.name}/" else "\uD83D\uDCC4 ${item.name}$sizeStr"
                                textSize = 13f
                                typeface = if (item.isDirectory) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                                setTextColor(if (item.isDirectory) Color.parseColor("#0369A1") else Color.parseColor("#1E293B"))
                                setPadding(14, 14, 14, 14)
                                isFocusable = true
                                setOnClickListener {
                                    if (item.isDirectory) {
                                        loadDir(item.fullUrl)
                                    } else if (isSaveMode) {
                                        saveFileNameInput.setText(item.name)
                                    } else {
                                        dialog.dismiss()
                                        openWebDavFileInNewTab(item.fullUrl, item.name)
                                    }
                                }
                            }
                            val div = View(this@MainActivity).apply {
                                setBackgroundColor(Color.parseColor("#E2E8F0"))
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            }
                            listContainer.addView(rowTv)
                            listContainer.addView(div)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "연결 오류: ${e.message}"
                    }
                }
            }
        }

        btnUpDir.setOnClickListener {
            val trimmed = currentDirUrl.trimEnd('/')
            val parent = trimmed.substringBeforeLast('/', "")
            if (parent.startsWith("http://") || parent.startsWith("https://")) {
                if (parent.length >= "https://a".length) {
                    loadDir("$parent/")
                }
            }
        }

        btnCfg.setOnClickListener {
            dialog.dismiss()
            showWebDavConfigDialog { showWebDavBrowserDialog(isSaveMode) }
        }

        dialog.show()
        loadDir(currentDirUrl)
    }

    private fun formatByteSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / (1024f * 1024f))
        }
    }

    private fun openWebDavFileInNewTab(fileUrl: String, fileName: String) {
        statusText.text = "WebDAV 다운로드 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val bytes = WebDavClient.downloadBytes(
                    fileUrl,
                    EditorSettings.webDavUsername,
                    EditorSettings.webDavPassword
                )
                val newSession = TabSession(title = fileName, webDavUrl = fileUrl)
                editorView.loadFromStreamIntoSession(newSession, ByteArrayInputStream(bytes))
                val elapsed = System.currentTimeMillis() - start

                withContext(Dispatchers.Main) {
                    tabs.add(newSession)
                    switchToTab(tabs.size - 1)
                    saveOpenTabsState()
                    encodingText.text = "${newSession.encoding.name()} (DAV ${elapsed}ms)"
                    editorView.requestFocus()
                    Toast.makeText(this@MainActivity, "WebDAV 열기 완료: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "WebDAV 열기 실패"
                    Toast.makeText(this@MainActivity, "WebDAV 다운로드 오류: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun uploadCurrentTabToWebDav(fileUrl: String, fileName: String) {
        val tab = tabs[currentTabIndex]
        val charset = editorView.detectedEncoding
        val lineEnd = if (EditorSettings.useCrLf) "\r\n" else "\n"
        statusText.text = "WebDAV 업로드 중..."

        val contentBytes = editorView.getAllTextSequence().joinToString(lineEnd).toByteArray(charset)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                WebDavClient.uploadBytes(
                    fileUrl,
                    contentBytes,
                    EditorSettings.webDavUsername,
                    EditorSettings.webDavPassword
                )
                withContext(Dispatchers.Main) {
                    tab.webDavUrl = fileUrl
                    tab.title = fileName
                    tab.isModified = false
                    editorView.refreshLanguageDetection()
                    editorView.invalidate()
                    saveOpenTabsState()
                    rebuildTabBar()
                    statusText.text = "WebDAV 저장 완료 (${charset.name()})"
                    Toast.makeText(this@MainActivity, "NAS 저장 완료: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "WebDAV 저장 실패"
                    Toast.makeText(this@MainActivity, "WebDAV 업로드 오류: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveOpenTabsState() {
        val prefs = getSharedPreferences("unlimited_editor_prefs", Context.MODE_PRIVATE)
        val draftDir = File(filesDir, "session_drafts")
        if (!EditorSettings.restoreTabsOnStart) {
            draftDir.deleteRecursively()
            prefs.edit().putString("saved_open_tabs", "[]").putInt("saved_active_tab", 0).commit()
            return
        }

        editorView.saveToActiveTab()
        if (!draftDir.exists()) draftDir.mkdirs()
        draftDir.listFiles()?.forEach { it.delete() }

        val arr = JSONArray()
        for ((idx, tab) in tabs.withIndex()) {
            val isSingleEmptyDefault = (tabs.size == 1 && tab.uri == null && tab.webDavUrl == null && !tab.isModified &&
                tab.lines.size == 1 && tab.lines[0].isEmpty())
            if (isSingleEmptyDefault) continue

            val obj = JSONObject().apply {
                put("title", tab.title)
                put("encoding", tab.encoding.name())
                put("cursorLine", tab.cursorLine)
                put("cursorCol", tab.cursorCol)
                put("scrollY", tab.scrollYOffset.toDouble())
                put("isModified", tab.isModified)
                if (tab.uri != null) put("uri", tab.uri.toString())
                if (tab.webDavUrl != null) put("webDavUrl", tab.webDavUrl)
            }

            val needsDraftSnapshot = (tab.uri == null) || tab.isModified
            if (needsDraftSnapshot) {
                var approxBytes = 0
                var withinLimit = true
                for (l in tab.lines) {
                    approxBytes += l.length * 2 + 2
                    if (approxBytes > 2_000_000) {
                        withinLimit = false
                        break
                    }
                }
                if (withinLimit) {
                    try {
                        val draftFile = File(draftDir, "draft_$idx.txt")
                        draftFile.bufferedWriter(Charsets.UTF_8).use { w ->
                            for (i in tab.lines.indices) {
                                w.write(tab.lines[i])
                                if (i + 1 < tab.lines.size) w.newLine()
                            }
                        }
                        obj.put("draftFile", draftFile.name)
                    } catch (_: Exception) { }
                }
            }
            arr.put(obj)
        }

        prefs.edit()
            .putString("saved_open_tabs", arr.toString())
            .putInt("saved_active_tab", currentTabIndex)
            .commit()
    }

    private fun restoreSavedTabs(): Boolean {
        if (!EditorSettings.restoreTabsOnStart) return false
        val prefs = getSharedPreferences("unlimited_editor_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("saved_open_tabs", "[]") ?: "[]"
        val arr = JSONArray(raw)
        if (arr.length() == 0) return false

        val savedActive = prefs.getInt("saved_active_tab", 0)
        val draftDir = File(filesDir, "session_drafts")

        lifecycleScope.launch(Dispatchers.IO) {
            val restored = ArrayList<TabSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val title = obj.getString("title")
                val uriStr = obj.optString("uri", "")
                val davUrl = obj.optString("webDavUrl", "")
                val draftName = obj.optString("draftFile", "")
                val wasModified = obj.optBoolean("isModified", false)
                val encName = obj.optString("encoding", EditorSettings.defaultEncoding)
                val charset = try { Charset.forName(encName) } catch (_: Exception) { Charsets.UTF_8 }

                try {
                    val uri = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null
                    val session = TabSession(
                        title = title,
                        uri = uri,
                        webDavUrl = davUrl.ifEmpty { null },
                        encoding = charset
                    )
                    var loaded = false

                    if (draftName.isNotEmpty()) {
                        val draftFile = File(draftDir, draftName)
                        if (draftFile.exists()) {
                            draftFile.inputStream().use { stream ->
                                editorView.loadFromStreamIntoSession(session, stream)
                            }
                            session.encoding = charset
                            session.isModified = wasModified
                            loaded = true
                        }
                    }

                    if (!loaded && uri != null) {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            editorView.loadFromStreamIntoSession(session, stream)
                        }
                        loaded = true
                    }

                    if (loaded) {
                        session.cursorLine = obj.optInt("cursorLine", 0)
                        session.cursorCol = obj.optInt("cursorCol", 0)
                        session.scrollYOffset = obj.optDouble("scrollY", 0.0).toFloat()
                        restored.add(session)
                    }
                } catch (_: Exception) { }
            }

            withContext(Dispatchers.Main) {
                tabs.clear()
                if (restored.isNotEmpty()) {
                    tabs.addAll(restored)
                    switchToTab(savedActive.coerceIn(0, tabs.lastIndex))
                } else {
                    val defaultCs = try { Charset.forName(EditorSettings.defaultEncoding) } catch (_: Exception) { Charsets.UTF_8 }
                    tabs.add(TabSession(title = "제목없음.txt", encoding = defaultCs))
                    switchToTab(0)
                }
            }
        }
        return true
    }

    private fun toggleEncoding() {
        editorView.detectedEncoding = if (editorView.detectedEncoding == Charsets.UTF_8) {
            try { Charset.forName("EUC-KR") } catch (e: Exception) { Charsets.UTF_8 }
        } else Charsets.UTF_8
        encodingText.text = editorView.detectedEncoding.name()
        Toast.makeText(this, "인코딩 전환: ${editorView.detectedEncoding.name()}", Toast.LENGTH_SHORT).show()
    }

    private fun showFileDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "새 탭 만들기 (Ctrl+N)")
        popup.menu.add(0, 2, 1, "기기 파일 열기 (Ctrl+O)")
        popup.menu.add(0, 3, 2, "WebDAV에서 열기 / 탐색 (Ctrl+Shift+O)")
        popup.menu.add(0, 4, 3, "최근 파일 목록 (Ctrl+R)")
        popup.menu.add(0, 5, 4, "저장 (Ctrl+S)")
        popup.menu.add(0, 6, 5, "다른 이름으로 저장 (Ctrl+Shift+S)")
        popup.menu.add(0, 7, 6, "WebDAV에 업로드 (Ctrl+Shift+U)")
        popup.menu.add(0, 8, 7, "외부 발행 / 게시 - WPGitHubNotion (Ctrl+P)")
        popup.menu.add(0, 9, 8, "현재 탭 닫기 (Ctrl+W)")
        popup.menu.add(0, 10, 9, "종료 (Ctrl+Q)")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> createNewBlankTab()
                2 -> { pendingCompareMode = false; openFileLauncher.launch(arrayOf("text/*", "*/*")) }
                3 -> showWebDavBrowserDialog(isSaveMode = false)
                4 -> showRecentFilesDialog()
                5 -> quickSaveCurrentTab()
                6 -> saveFileLauncher.launch(tabs[currentTabIndex].title)
                7 -> showWebDavBrowserDialog(isSaveMode = true)
                8 -> showPublishDialog()
                9 -> requestCloseTab(currentTabIndex)
                10 -> requestExitApp()
            }
            true
        }
        popup.show()
    }

    private fun showEditDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "실행 취소 (Ctrl+Z)")
        popup.menu.add(0, 2, 1, "다시 실행 (Ctrl+Y)")
        popup.menu.add(0, 3, 2, "범위 선택 모드 토글 (Ctrl+M)")
        popup.menu.add(0, 4, 3, "전체 선택 (Ctrl+A)")
        popup.menu.add(0, 5, 4, "복사 (Ctrl+C)")
        popup.menu.add(0, 6, 5, "잘라내기 (Ctrl+X)")
        popup.menu.add(0, 7, 6, "붙여넣기 (Ctrl+V)")
        popup.menu.add(0, 8, 7, "이미지 삽입 - 사진 선택 (Ctrl+Shift+I)")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> editorView.undo()
                2 -> editorView.redo()
                3 -> editorView.toggleSelectionMode()
                4 -> editorView.selectAll()
                5 -> editorView.copySelection()
                6 -> editorView.cutSelection()
                7 -> editorView.pasteClipboard()
                8 -> imagePickerLauncher.launch(arrayOf("image/*"))
            }
            true
        }
        popup.show()
    }

    private fun showSearchDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "찾기 및 바꾸기 / 정규식 (Ctrl+F / Ctrl+H)")
        popup.menu.add(0, 2, 1, "줄 필터링 - 패턴 일치 줄 추리기 (Ctrl+Shift+F)")
        popup.menu.add(0, 3, 2, "줄 / 칸 번호 이동 (Ctrl+G)")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showFindReplaceDialog()
                2 -> showLineFilterDialog()
                3 -> showJumpDialog()
            }
            true
        }
        popup.show()
    }

    private fun showBookmarkDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "현재 줄 북마크 추가/해제 (Ctrl+B)")
        popup.menu.add(0, 2, 1, "다음 북마크로 이동 (F2)")
        popup.menu.add(0, 3, 2, "이전 북마크로 이동 (Shift+F2)")
        popup.menu.add(0, 4, 3, "북마크 전체 목록 (Ctrl+Shift+B)")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val added = editorView.toggleBookmarkAt()
                    Toast.makeText(this, if (added) "북마크 추가됨" else "북마크 해제됨", Toast.LENGTH_SHORT).show()
                }
                2 -> jumpToNextBookmark(forward = true)
                3 -> jumpToNextBookmark(forward = false)
                4 -> showBookmarkListDialog()
            }
            true
        }
        popup.show()
    }

    private fun showToolsDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "환경설정 (Ctrl+,)")
        popup.menu.add(0, 2, 1, "외부 발행 계정 설정 (WP  GitHub  Notion)")
        popup.menu.add(0, 3, 2, "WebDAV 서버 설정")
        popup.menu.add(0, 4, 3, "두 파일 비교 / 병합 (Ctrl+D)")
        popup.menu.add(0, 5, 4, "문서 통계 (Ctrl+I)")
        popup.menu.add(0, 6, 5, "인코딩 전환 UTF-8/EUC-KR (Ctrl+E)")
        popup.menu.add(0, 7, 6, "테마 순환 변경 (Ctrl+T)")
        popup.menu.add(0, 8, 7, "줄번호 표시 전환 (Ctrl+L)")
        popup.menu.add(0, 9, 8, "글자 크게 (Ctrl++)")
        popup.menu.add(0, 10, 9, "글자 작게 (Ctrl+-)")
        popup.menu.add(0, 11, 10, "전체 단축키 일람 (F1)")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showSettingsDialog()
                2 -> showPublishAccountsDialog()
                3 -> showWebDavConfigDialog()
                4 -> showComparePickerDialog()
                5 -> showStatsDialog()
                6 -> toggleEncoding()
                7 -> {
                    val next = editorView.cycleTheme()
                    syncChromeColors()
                    Toast.makeText(this, "테마: ${next.label}", Toast.LENGTH_SHORT).show()
                }
                8 -> {
                    val on = editorView.toggleLineNumbers()
                    Toast.makeText(this, if (on) "줄번호 표시" else "줄번호 숨김", Toast.LENGTH_SHORT).show()
                }
                9 -> editorView.changeFontSize(4f)
                10 -> editorView.changeFontSize(-4f)
                11 -> showShortcutHelpDialog()
            }
            true
        }
        popup.show()
    }

    private fun createNewBlankTab() {
        val defaultCs = try { Charset.forName(EditorSettings.defaultEncoding) } catch (_: Exception) { Charsets.UTF_8 }
        val newTab = TabSession(title = "제목없음_${tabs.size + 1}.txt", encoding = defaultCs)
        tabs.add(newTab)
        switchToTab(tabs.size - 1)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        if (currentTabIndex in tabs.indices) {
            editorView.saveToActiveTab()
        }
        currentTabIndex = index
        val target = tabs[index]
        editorView.bindTabSession(target)
        encodingText.text = target.encoding.name()
        rebuildTabBar()
    }

    private fun requestCloseTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        if (tab.isModified) {
            AlertDialog.Builder(this)
                .setTitle("저장하지 않은 변경사항")
                .setMessage("'${tab.title}' 파일에 수정된 내용이 있습니다. 닫기 전에 저장할까요?")
                .setPositiveButton("저장 후 닫기") { _, _ ->
                    switchToTab(index)
                    quickSaveCurrentTab()
                }
                .setNeutralButton("저장 안 함") { _, _ -> forceCloseTab(index) }
                .setNegativeButton("취소", null)
                .show()
        } else {
            forceCloseTab(index)
        }
    }

    private fun forceCloseTab(index: Int) {
        if (tabs.size <= 1) {
            val defaultCs = try { Charset.forName(EditorSettings.defaultEncoding) } catch (_: Exception) { Charsets.UTF_8 }
            tabs[0] = TabSession(title = "제목없음.txt", encoding = defaultCs)
            switchToTab(0)
            saveOpenTabsState()
            return
        }
        tabs.removeAt(index)
        val nextIdx = min(index, tabs.size - 1)
        currentTabIndex = -1
        switchToTab(nextIdx)
        saveOpenTabsState()
    }

    private fun rebuildTabBar() {
        tabBar.removeAllViews()
        val theme = editorView.currentTheme

        for (i in tabs.indices) {
            val tab = tabs[i]
            val isSelected = (i == currentTabIndex)
            val isMod = tab.isModified
            val isDav = tab.webDavUrl != null

            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 10, 12, 10)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(5, 2, 5, 2) }

                background = GradientDrawable().apply {
                    cornerRadius = 16f
                    when {
                        isSelected && isMod -> {
                            setColor(Color.parseColor("#FFF7ED"))
                            setStroke(4, Color.parseColor("#EA580C"))
                        }
                        isSelected -> {
                            setColor(theme.bg)
                            setStroke(4, theme.accent)
                        }
                        isMod -> {
                            setColor(Color.parseColor("#FFEDD5"))
                            setStroke(2, Color.parseColor("#FB923C"))
                        }
                        else -> {
                            setColor(theme.gutterBg)
                            setStroke(2, theme.chromeBorder)
                        }
                    }
                }
                setOnClickListener { switchToTab(i) }
            }

            val titleTv = TextView(this).apply {
                val prefix = (if (isMod) "\u25CF " else "") + (if (isDav) "[DAV] " else "")
                text = "$prefix${tab.title}"
                textSize = 12.5f
                if (isSelected || isMod) typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    when {
                        isMod -> Color.parseColor("#C2410C")
                        isSelected -> theme.accent
                        else -> theme.text
                    }
                )
            }

            val closeBtn = TextView(this).apply {
                text = "\u2715"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#B91C1C"))
                setPadding(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(14, 0, 0, 0) }
                background = GradientDrawable().apply {
                    cornerRadius = 20f
                    setColor(Color.parseColor("#FEE2E2"))
                    setStroke(1, Color.parseColor("#FCA5A5"))
                }
                setOnClickListener { requestCloseTab(i) }
            }

            chip.addView(titleTv)
            chip.addView(closeBtn)
            tabBar.addView(chip)
        }

        val addTabBtn = TextView(this).apply {
            text = " \uFF0B 새 탭 "
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.accent)
            setPadding(18, 10, 18, 10)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(6, 2, 4, 2) }
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(theme.bg)
                setStroke(2, theme.accent)
            }
            setOnClickListener { createNewBlankTab() }
        }
        tabBar.addView(addTabBtn)
    }

    private fun requestExitApp() {
        saveOpenTabsState()
        val modifiedTabs = tabs.filter { it.isModified }
        if (modifiedTabs.isEmpty() || EditorSettings.restoreTabsOnStart) {
            finishAffinity()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("TextEdit 종료")
            .setMessage("저장하지 않은 수정 파일이 ${modifiedTabs.size}개 있습니다.\n저장하지 않고 종료할까요?")
            .setPositiveButton("저장하지 않고 종료") { _, _ -> finishAffinity() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun jumpToNextBookmark(forward: Boolean) {
        val list = editorView.getBookmarksSorted()
        if (list.isEmpty()) {
            Toast.makeText(this, "등록된 북마크가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val currZeroBased = tabs[currentTabIndex].cursorLine
        val target = if (forward) {
            list.firstOrNull { it > currZeroBased } ?: list.first()
        } else {
            list.lastOrNull { it < currZeroBased } ?: list.last()
        }
        editorView.goToLineAndCol(target + 1, 1)
        Toast.makeText(this, "북마크 이동: ${target + 1}줄", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarkListDialog() {
        val list = editorView.getBookmarksSorted()
        if (list.isEmpty()) {
            Toast.makeText(this, "등록된 북마크가 없습니다. 줄번호 영역을 터치하면 추가됩니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { lineIdx ->
            "${lineIdx + 1}줄: ${editorView.getLinePreview(lineIdx)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("북마크 목록 (선택 시 이동)")
            .setItems(labels) { _, which ->
                editorView.goToLineAndCol(list[which] + 1, 1)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun saveRecentFile(name: String, uri: Uri) {
        val prefs = getSharedPreferences("unlimited_editor_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("recent_files", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val newArr = JSONArray()
        newArr.put(JSONObject().apply {
            put("name", name)
            put("uri", uri.toString())
        })
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("uri") != uri.toString() && newArr.length() < 15) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString("recent_files", newArr.toString()).apply()
    }

    private fun getRecentFiles(): List<RecentFileItem> {
        val prefs = getSharedPreferences("unlimited_editor_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("recent_files", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val result = ArrayList<RecentFileItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(RecentFileItem(obj.getString("name"), obj.getString("uri")))
        }
        return result
    }

    private fun showRecentFilesDialog() {
        val recents = getRecentFiles()
        if (recents.isEmpty()) {
            Toast.makeText(this, "최근 열어본 파일 기록이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = recents.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("최근 파일 목록")
            .setItems(names) { _, which ->
                val uri = Uri.parse(recents[which].uriString)
                openUriInNewTab(uri, fallbackName = recents[which].name)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showComparePickerDialog() {
        val options = ArrayList<String>()
        val otherTabIndices = ArrayList<Int>()
        for (i in tabs.indices) {
            if (i != currentTabIndex) {
                options.add("열린 탭과 비교/병합: ${tabs[i].title}")
                otherTabIndices.add(i)
            }
        }
        options.add("기기에서 다른 파일 불러와 비교...")

        AlertDialog.Builder(this)
            .setTitle("기준 파일(${tabs[currentTabIndex].title})과 비교할 대상")
            .setItems(options.toTypedArray()) { _, which ->
                val leftTab = tabs[currentTabIndex]
                if (which < otherTabIndices.size) {
                    val rightIdx = otherTabIndices[which]
                    val rightTab = tabs[rightIdx]
                    val leftSnapshot = leftTab.lines.toList()
                    val rightSnapshot = rightTab.lines.toList()
                    DiffEngine.showWinMergeDialog(
                        this,
                        leftTab.title,
                        rightTab.title,
                        leftTab.lines,
                        rightTab.lines
                    ) { leftUpd, rightUpd ->
                        if (leftUpd) {
                            leftTab.undoStack.addLast(EditRecord(0, leftSnapshot, leftTab.lines.toList(), leftTab.cursorLine, leftTab.cursorCol, 0, 0))
                            leftTab.redoStack.clear()
                            leftTab.isModified = true
                        }
                        if (rightUpd) {
                            rightTab.undoStack.addLast(EditRecord(0, rightSnapshot, rightTab.lines.toList(), rightTab.cursorLine, rightTab.cursorCol, 0, 0))
                            rightTab.redoStack.clear()
                            rightTab.isModified = true
                        }
                        switchToTab(currentTabIndex)
                    }
                } else {
                    pendingCompareMode = true
                    openFileLauncher.launch(arrayOf("text/*", "*/*"))
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun compareWithExternalUri(uri: Uri) {
        val rightName = resolveFileName(uri)
        statusText.text = "비교 분석 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            val tempSession = TabSession(title = rightName)
            contentResolver.openInputStream(uri)?.use { stream ->
                editorView.loadFromStreamIntoSession(tempSession, stream)
            }
            withContext(Dispatchers.Main) {
                val leftTab = tabs[currentTabIndex]
                val leftSnapshot = leftTab.lines.toList()
                statusText.text = "줄 1/${editorView.getTotalLines()}  칸 1"
                DiffEngine.showWinMergeDialog(
                    this@MainActivity,
                    leftTab.title,
                    rightName,
                    leftTab.lines,
                    null
                ) { leftUpd, _ ->
                    if (leftUpd) {
                        leftTab.undoStack.addLast(EditRecord(0, leftSnapshot, leftTab.lines.toList(), leftTab.cursorLine, leftTab.cursorCol, 0, 0))
                        leftTab.redoStack.clear()
                        leftTab.isModified = true
                        switchToTab(currentTabIndex)
                    }
                }
            }
        }
    }

    private fun resolveFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) return cursor.getString(nameIdx)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.txt"
    }

    private fun openUriInNewTab(uri: Uri, fallbackName: String? = null) {
        val fileName = fallbackName ?: resolveFileName(uri)
        statusText.text = "파일 읽는 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val newSession = TabSession(title = fileName, uri = uri)
                contentResolver.openInputStream(uri)?.use { stream ->
                    editorView.loadFromStreamIntoSession(newSession, stream)
                }
                val elapsed = System.currentTimeMillis() - start
                withContext(Dispatchers.Main) {
                    saveRecentFile(fileName, uri)
                    tabs.add(newSession)
                    switchToTab(tabs.size - 1)
                    saveOpenTabsState()
                    encodingText.text = "${newSession.encoding.name()} (${elapsed}ms)"
                    editorView.requestFocus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "파일을 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun quickSaveCurrentTab() {
        val tab = tabs[currentTabIndex]
        val davUrl = tab.webDavUrl
        if (!davUrl.isNullOrEmpty()) {
            uploadCurrentTabToWebDav(davUrl, tab.title)
            return
        }
        val targetUri = tab.uri
        if (targetUri != null) saveCurrentTabToUri(targetUri)
        else saveFileLauncher.launch(tab.title)
    }

    private fun saveCurrentTabToUri(uri: Uri) {
        val tab = tabs[currentTabIndex]
        val charset = editorView.detectedEncoding
        val lineEnd = if (EditorSettings.useCrLf) "\r\n" else "\n"
        statusText.text = "저장 중..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    BufferedWriter(OutputStreamWriter(out, charset), 65536).use { writer ->
                        val seq = editorView.getAllTextSequence().iterator()
                        while (seq.hasNext()) {
                            writer.write(seq.next())
                            if (seq.hasNext()) writer.write(lineEnd)
                        }
                    }
                }
                val savedName = resolveFileName(uri)
                withContext(Dispatchers.Main) {
                    tab.uri = uri
                    tab.webDavUrl = null
                    tab.title = savedName
                    tab.isModified = false
                    editorView.refreshLanguageDetection()
                    editorView.invalidate()
                    saveRecentFile(savedName, uri)
                    saveOpenTabsState()
                    rebuildTabBar()
                    statusText.text = "저장 완료 (${charset.name()})"
                    Toast.makeText(this@MainActivity, "${savedName} 저장 완료", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "저장 실패: 다른 이름으로 저장을 시도하세요.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showShortcutHelpDialog() {
        val helpText = """
            [메뉴 및 환경설정]
             Ctrl + , : 환경설정 열기
             Esc / F10 : 본문 \u21C4 상단 메뉴 바 전환
             F6 : 본문 \u2192 메뉴 바 \u2192 하단 바 \u2192 상태바 순환
             F1 : 단축키 일람 보기

            [파일  WebDAV  외부 발행]
             Ctrl + N / O / R : 새 탭 / 기기 열기 / 최근 파일
             Ctrl + Shift + O : WebDAV 서버에서 열기 / 탐색
             Ctrl + S / Ctrl + Shift + S : 저장 (WebDAV 포함) / 다른 이름 저장
             Ctrl + Shift + U : WebDAV 서버에 업로드
             Ctrl + P : 외부 발행 / 게시 (워드프레스GitHubNotionHTML)
             Ctrl + W / Ctrl + Q : 현재 탭 닫기 / 프로그램 종료
             Ctrl + Tab / Ctrl + Shift + Tab : 다음 / 이전 탭 전환

            [편집 및 검색]
             Ctrl + Z / Ctrl + Y : 실행 취소 / 다시 실행
             Ctrl + M : 범위 선택 모드 토글
             Ctrl + A / C / X / V : 전체선택 / 복사 / 잘라내기 / 붙여넣기
             Ctrl + Shift + I : 이미지 삽입 (사진 선택기)
             Ctrl + F / Ctrl + H : 찾기 및 바꾸기 (정규식 지원)
             Ctrl + Shift + F : 줄 필터링 (패턴 일치 줄 추리기)
             Ctrl + G : 줄 / 칸 번호 이동

            [북마크 및 도구]
             Ctrl + B : 현재 줄 북마크 토글
             F2 / Shift + F2 : 다음 / 이전 북마크 이동
             Ctrl + Shift + B : 북마크 전체 목록
             Ctrl + D : 두 파일 비교 / 병합 (WinMerge)
             Ctrl + I : 문서 통계
             Ctrl + E : 인코딩 전환 (UTF-8 \u21C4 EUC-KR)
             Ctrl + T : 테마 순환 변경
             Ctrl + L : 줄번호 표시 전환
             Ctrl + + / Ctrl + - : 글자 크기 확대 / 축소
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("TextEdit 전체 단축키 안내")
            .setMessage(helpText)
            .setPositiveButton("확인", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_F1 -> { showShortcutHelpDialog(); return true }
                KeyEvent.KEYCODE_F2 -> { jumpToNextBookmark(forward = !event.isShiftPressed); return true }
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_F10 -> {
                    if (editorView.hasFocus()) btnFileMenu.requestFocus()
                    else editorView.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_F6 -> {
                    val focused = currentFocus
                    when {
                        focused == editorView -> btnFileMenu.requestFocus()
                        focused != null && menuBar.indexOfChild(focused) >= 0 -> {
                            if (EditorSettings.showQuickBar) btnUndo.requestFocus()
                            else encodingText.requestFocus()
                        }
                        focused != null && quickBar.indexOfChild(focused) >= 0 -> encodingText.requestFocus()
                        else -> editorView.requestFocus()
                    }
                    return true
                }
            }

            if (event.isCtrlPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_COMMA -> { showSettingsDialog(); return true }
                    KeyEvent.KEYCODE_N -> { createNewBlankTab(); return true }
                    KeyEvent.KEYCODE_O -> {
                        if (event.isShiftPressed) showWebDavBrowserDialog(isSaveMode = false)
                        else { pendingCompareMode = false; openFileLauncher.launch(arrayOf("text/*", "*/*")) }
                        return true
                    }
                    KeyEvent.KEYCODE_P -> { showPublishDialog(); return true }
                    KeyEvent.KEYCODE_R -> { showRecentFilesDialog(); return true }
                    KeyEvent.KEYCODE_S -> {
                        if (event.isShiftPressed) saveFileLauncher.launch(tabs[currentTabIndex].title)
                        else quickSaveCurrentTab()
                        return true
                    }
                    KeyEvent.KEYCODE_U -> {
                        if (event.isShiftPressed) {
                            showWebDavBrowserDialog(isSaveMode = true)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_W -> { requestCloseTab(currentTabIndex); return true }
                    KeyEvent.KEYCODE_Q -> { requestExitApp(); return true }
                    KeyEvent.KEYCODE_TAB -> {
                        if (tabs.size > 1) {
                            val next = if (event.isShiftPressed) (currentTabIndex - 1 + tabs.size) % tabs.size
                            else (currentTabIndex + 1) % tabs.size
                            switchToTab(next)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) editorView.redo() else editorView.undo(); return true }
                    KeyEvent.KEYCODE_Y -> { editorView.redo(); return true }
                    KeyEvent.KEYCODE_M -> { editorView.toggleSelectionMode(); return true }
                    KeyEvent.KEYCODE_A -> { editorView.selectAll(); return true }
                    KeyEvent.KEYCODE_C -> { editorView.copySelection(); return true }
                    KeyEvent.KEYCODE_X -> { editorView.cutSelection(); return true }
                    KeyEvent.KEYCODE_V -> { editorView.pasteClipboard(); return true }
                    KeyEvent.KEYCODE_F -> {
                        if (event.isShiftPressed) showLineFilterDialog()
                        else showFindReplaceDialog()
                        return true
                    }
                    KeyEvent.KEYCODE_H -> { showFindReplaceDialog(); return true }
                    KeyEvent.KEYCODE_G -> { showJumpDialog(); return true }
                    KeyEvent.KEYCODE_B -> {
                        if (event.isShiftPressed) showBookmarkListDialog()
                        else {
                            val added = editorView.toggleBookmarkAt()
                            Toast.makeText(this, if (added) "북마크 추가됨" else "북마크 해제됨", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_D -> { showComparePickerDialog(); return true }
                    KeyEvent.KEYCODE_I -> {
                        if (event.isShiftPressed) imagePickerLauncher.launch(arrayOf("image/*"))
                        else showStatsDialog()
                        return true
                    }
                    KeyEvent.KEYCODE_E -> { toggleEncoding(); return true }
                    KeyEvent.KEYCODE_T -> {
                        val next = editorView.cycleTheme()
                        syncChromeColors()
                        Toast.makeText(this, "테마: ${next.label}", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    KeyEvent.KEYCODE_L -> {
                        val on = editorView.toggleLineNumbers()
                        Toast.makeText(this, if (on) "줄번호 표시" else "줄번호 숨김", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> { editorView.changeFontSize(4f); return true }
                    KeyEvent.KEYCODE_MINUS -> { editorView.changeFontSize(-4f); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun styleFlatButton(btn: TextView, isActive: Boolean = false, isFocused: Boolean = false) {
        val theme = editorView.currentTheme
        btn.background = GradientDrawable().apply {
            cornerRadius = 14f
            when {
                isActive -> {
                    setColor(theme.accent)
                    if (isFocused) setStroke(5, theme.text)
                }
                isFocused -> {
                    setColor(theme.cursorLineBg)
                    setStroke(5, theme.accent)
                }
                else -> {
                    setColor(theme.bg)
                    setStroke(2, theme.chromeBorder)
                }
            }
        }
        btn.setTextColor(
            when {
                isActive -> theme.bg
                isFocused -> theme.accent
                btn == encodingText -> theme.accent
                else -> theme.text
            }
        )
    }

    private fun syncChromeColors() {
        val theme = editorView.currentTheme
        menuBar.setBackgroundColor(theme.chromeBg)
        tabBar.setBackgroundColor(theme.chromeBg)
        quickBar.setBackgroundColor(theme.chromeBg)
        bottomBar.setBackgroundColor(theme.chromeBg)
        menuDivider.setBackgroundColor(theme.chromeBorder)
        tabDivider.setBackgroundColor(theme.chromeBorder)
        quickDivider.setBackgroundColor(theme.chromeBorder)
        bottomDivider.setBackgroundColor(theme.chromeBorder)
        statusText.setTextColor(theme.text)

        flatButtons.forEach { btn ->
            val active = (btn == btnSelectMode && editorView.isSelectionMode)
            styleFlatButton(btn, isActive = active, isFocused = btn.isFocused)
        }
        rebuildTabBar()

        window.statusBarColor = theme.chromeBg
        window.navigationBarColor = theme.chromeBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (theme.isLightStatusBar) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (theme.isLightStatusBar) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
    }

    private fun showFindReplaceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val selectedInit = editorView.getSelectedText().takeIf { it.isNotEmpty() && !it.contains('\n') }
        val findInput = EditText(this).apply {
            hint = "찾을 단어 또는 정규식 패턴 (예: \\d{3}-\\d{4})"
            setText(selectedInit ?: lastSearchQuery)
            setSelection(text.length)
        }
        val replaceInput = EditText(this).apply {
            hint = "바꿀 단어 (정규식 시 \$1, \$2 그룹 치환 가능)"
            setText(lastReplaceQuery)
        }

        val optionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 4)
        }
        val cbRegex = CheckBox(this).apply {
            text = "정규식(Regex) 사용"
            isChecked = lastSearchRegex
            setPadding(0, 0, 24, 0)
        }
        val cbCase = CheckBox(this).apply {
            text = "대소문자 구분"
            isChecked = lastSearchCaseSensitive
        }
        optionRow.addView(cbRegex)
        optionRow.addView(cbCase)

        layout.addView(findInput)
        layout.addView(replaceInput)
        layout.addView(optionRow)

        AlertDialog.Builder(this)
            .setTitle("찾기 및 바꾸기")
            .setView(layout)
            .setPositiveButton("다음 찾기") { _, _ ->
                val q = findInput.text.toString()
                val r = replaceInput.text.toString()
                val useRe = cbRegex.isChecked
                val ignoreCase = !cbCase.isChecked
                lastSearchQuery = q
                lastReplaceQuery = r
                lastSearchRegex = useRe
                lastSearchCaseSensitive = cbCase.isChecked

                if (useRe && !editorView.isValidRegex(q)) {
                    Toast.makeText(this, "정규식 문법 오류가 있습니다.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!editorView.findNext(q, useRegex = useRe, ignoreCase = ignoreCase)) {
                    Toast.makeText(this, "찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("바꾸기") { _, _ ->
                val q = findInput.text.toString()
                val r = replaceInput.text.toString()
                val useRe = cbRegex.isChecked
                val ignoreCase = !cbCase.isChecked
                lastSearchQuery = q
                lastReplaceQuery = r
                lastSearchRegex = useRe
                lastSearchCaseSensitive = cbCase.isChecked

                if (useRe && !editorView.isValidRegex(q)) {
                    Toast.makeText(this, "정규식 문법 오류가 있습니다.", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                editorView.replaceCurrent(q, r, useRegex = useRe, ignoreCase = ignoreCase)
            }
            .setNegativeButton("모두 바꾸기") { _, _ ->
                val q = findInput.text.toString()
                val r = replaceInput.text.toString()
                val useRe = cbRegex.isChecked
                val ignoreCase = !cbCase.isChecked
                lastSearchQuery = q
                lastReplaceQuery = r
                lastSearchRegex = useRe
                lastSearchCaseSensitive = cbCase.isChecked

                if (useRe && !editorView.isValidRegex(q)) {
                    Toast.makeText(this, "정규식 문법 오류가 있습니다.", Toast.LENGTH_SHORT).show()
                    return@setNegativeButton
                }
                val cnt = editorView.replaceAll(q, r, useRegex = useRe, ignoreCase = ignoreCase)
                if (cnt >= 0) {
                    Toast.makeText(this, "${cnt}건 변경되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showLineFilterDialog() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 12)
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val patternInput = EditText(this).apply {
            hint = "필터 패턴 (예: ERROR|WARN 또는 키워드)"
            setText(lastFilterPattern)
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRun = Button(this).apply {
            text = "필터 실행"
        }
        inputRow.addView(patternInput)
        inputRow.addView(btnRun)

        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 8)
        }
        val cbRegex = CheckBox(this).apply {
            text = "정규식"
            isChecked = lastFilterRegex
            setPadding(0, 0, 16, 0)
        }
        val cbCase = CheckBox(this).apply {
            text = "대소문자"
            isChecked = lastFilterCaseSensitive
            setPadding(0, 0, 16, 0)
        }
        val cbInvert = CheckBox(this).apply {
            text = "불일치 줄만(제외)"
            isChecked = lastFilterInvert
        }
        optRow.addView(cbRegex)
        optRow.addView(cbCase)
        optRow.addView(cbInvert)

        val countTv = TextView(this).apply {
            text = "패턴을 입력하고 [필터 실행]을 누르세요. (항목 터치 시 해당 줄로 이동)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0284C7"))
            setPadding(4, 4, 4, 10)
        }

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.42f).toInt()
            )
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }
        val resultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        listScroll.addView(resultContainer)

        rootLayout.addView(inputRow)
        rootLayout.addView(optRow)
        rootLayout.addView(countTv)
        rootLayout.addView(listScroll)

        var currentMatches: List<Pair<Int, String>> = emptyList()

        val dialog = AlertDialog.Builder(this)
            .setTitle("줄 필터링 (${tabs[currentTabIndex].title})")
            .setView(rootLayout)
            .setPositiveButton("새 탭으로 추출") { _, _ ->
                if (currentMatches.isEmpty()) {
                    Toast.makeText(this, "추출할 필터 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    val defaultCs = try { Charset.forName(EditorSettings.defaultEncoding) } catch (_: Exception) { Charsets.UTF_8 }
                    val extractedLines = ArrayList<String>(currentMatches.size)
                    for ((_, lineText) in currentMatches) {
                        extractedLines.add(lineText)
                    }
                    val baseTitle = tabs[currentTabIndex].title.substringBeforeLast('.')
                    val newTab = TabSession(
                        title = "${baseTitle}_필터결과.txt",
                        encoding = defaultCs,
                        lines = extractedLines,
                        isModified = true
                    )
                    tabs.add(newTab)
                    switchToTab(tabs.size - 1)
                    Toast.makeText(this, "${extractedLines.size}줄을 새 탭으로 추출했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("닫기", null)
            .create()

        fun executeFilter() {
            val pat = patternInput.text.toString()
            val useRe = cbRegex.isChecked
            val ignoreCase = !cbCase.isChecked
            val invert = cbInvert.isChecked

            lastFilterPattern = pat
            lastFilterRegex = useRe
            lastFilterCaseSensitive = cbCase.isChecked
            lastFilterInvert = invert

            resultContainer.removeAllViews()
            if (pat.isEmpty()) {
                currentMatches = emptyList()
                countTv.text = "검색할 패턴을 입력하세요."
                return
            }
            if (useRe && !editorView.isValidRegex(pat)) {
                currentMatches = emptyList()
                countTv.text = "정규식 문법 오류가 있습니다."
                return
            }

            currentMatches = editorView.filterLines(pat, useRegex = useRe, ignoreCase = ignoreCase, invertMatch = invert)
            val total = editorView.getTotalLines()
            val previewLimit = 500
            val suffix = if (currentMatches.size > previewLimit) " (상위 ${previewLimit}줄만 미리보기 표시, 새 탭 추출 시 전체 반영)" else ""
            countTv.text = "전체 ${total}줄 중 ${currentMatches.size}줄 일치$suffix"

            val shown = if (currentMatches.size > previewLimit) currentMatches.subList(0, previewLimit) else currentMatches
            for ((lineIdx, lineText) in shown) {
                val itemTv = TextView(this).apply {
                    text = "${(lineIdx + 1).toString().padStart(4, ' ')} | $lineText"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#1E293B"))
                    setPadding(12, 10, 12, 10)
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        editorView.goToLineAndCol(lineIdx + 1, 1)
                    }
                }
                val divider = View(this).apply {
                    setBackgroundColor(Color.parseColor("#E2E8F0"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                }
                resultContainer.addView(itemTv)
                resultContainer.addView(divider)
            }
        }

        btnRun.setOnClickListener { executeFilter() }
        patternInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                executeFilter()
                true
            } else false
        }

        dialog.show()
        if (lastFilterPattern.isNotEmpty()) {
            executeFilter()
        }
    }

    private fun showJumpDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val lineInput = EditText(this).apply {
            hint = "이동할 줄 번호 (1 ~ ${editorView.getTotalLines()})"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val colInput = EditText(this).apply {
            hint = "이동할 칸 번호 (선택사항, 기본 1)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(lineInput)
        layout.addView(colInput)

        AlertDialog.Builder(this)
            .setTitle("줄 / 칸 번호 이동")
            .setView(layout)
            .setPositiveButton("이동") { _, _ ->
                val l = lineInput.text.toString().toIntOrNull()
                val c = colInput.text.toString().toIntOrNull() ?: 1
                if (l != null) editorView.goToLineAndCol(l, c)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showStatsDialog() {
        AlertDialog.Builder(this)
            .setTitle("문서 통계")
            .setMessage(editorView.getDocumentStats())
            .setPositiveButton("확인", null)
            .show()
    }
}