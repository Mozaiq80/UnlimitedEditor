package com.custom.unlimitededitor

import android.content.Context
import android.graphics.Typeface

object EditorSettings {
    private const val PREF_NAME = "textedit_settings_v6"

    var themeIndex: Int = 0
    var fontFamilyIndex: Int = 1 // 0: Sans, 1: Monospace, 2: Serif
    var textSizePx: Float = 42f
    var lineSpacing: Float = 1.45f
    var wordWrap: Boolean = true
    var showLineNumbers: Boolean = true
    var showWhitespace: Boolean = false
    var syntaxHighlight: Boolean = true
    var keepScreenOn: Boolean = false

    var autoIndent: Boolean = true
    var tabWidth: Int = 4
    var useHardTab: Boolean = false
    var showQuickBar: Boolean = true
    var volumeKeyScroll: Boolean = false

    var defaultEncoding: String = "UTF-8"
    var useCrLf: Boolean = false
    var restoreTabsOnStart: Boolean = true

    var webDavBaseUrl: String = ""
    var webDavUsername: String = ""
    var webDavPassword: String = ""

    // 외부 발행 플랫폼 설정
    var wpSiteUrl: String = ""
    var wpUsername: String = ""
    var wpAppPassword: String = ""

    var ghOwnerRepo: String = ""
    var ghBranch: String = "main"
    var ghToken: String = ""
    var ghPostsDir: String = "_posts"
    var ghImagesDir: String = "assets/images"

    var notionToken: String = ""
    var notionParentPageId: String = ""

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        themeIndex = p.getInt("themeIndex", 0)
        fontFamilyIndex = p.getInt("fontFamilyIndex", 1)
        textSizePx = p.getFloat("textSizePx", 42f)
        lineSpacing = p.getFloat("lineSpacing", 1.45f)
        wordWrap = p.getBoolean("wordWrap", true)
        showLineNumbers = p.getBoolean("showLineNumbers", true)
        showWhitespace = p.getBoolean("showWhitespace", false)
        syntaxHighlight = p.getBoolean("syntaxHighlight", true)
        keepScreenOn = p.getBoolean("keepScreenOn", false)

        autoIndent = p.getBoolean("autoIndent", true)
        tabWidth = p.getInt("tabWidth", 4)
        useHardTab = p.getBoolean("useHardTab", false)
        showQuickBar = p.getBoolean("showQuickBar", true)
        volumeKeyScroll = p.getBoolean("volumeKeyScroll", false)

        defaultEncoding = p.getString("defaultEncoding", "UTF-8") ?: "UTF-8"
        useCrLf = p.getBoolean("useCrLf", false)
        restoreTabsOnStart = p.getBoolean("restoreTabsOnStart", true)

        webDavBaseUrl = p.getString("webDavBaseUrl", "") ?: ""
        webDavUsername = p.getString("webDavUsername", "") ?: ""
        webDavPassword = p.getString("webDavPassword", "") ?: ""

        wpSiteUrl = p.getString("wpSiteUrl", "") ?: ""
        wpUsername = p.getString("wpUsername", "") ?: ""
        wpAppPassword = p.getString("wpAppPassword", "") ?: ""

        ghOwnerRepo = p.getString("ghOwnerRepo", "") ?: ""
        ghBranch = p.getString("ghBranch", "main") ?: "main"
        ghToken = p.getString("ghToken", "") ?: ""
        ghPostsDir = p.getString("ghPostsDir", "_posts") ?: "_posts"
        ghImagesDir = p.getString("ghImagesDir", "assets/images") ?: "assets/images"

        notionToken = p.getString("notionToken", "") ?: ""
        notionParentPageId = p.getString("notionParentPageId", "") ?: ""
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt("themeIndex", themeIndex)
            .putInt("fontFamilyIndex", fontFamilyIndex)
            .putFloat("textSizePx", textSizePx)
            .putFloat("lineSpacing", lineSpacing)
            .putBoolean("wordWrap", wordWrap)
            .putBoolean("showLineNumbers", showLineNumbers)
            .putBoolean("showWhitespace", showWhitespace)
            .putBoolean("syntaxHighlight", syntaxHighlight)
            .putBoolean("keepScreenOn", keepScreenOn)
            .putBoolean("autoIndent", autoIndent)
            .putInt("tabWidth", tabWidth)
            .putBoolean("useHardTab", useHardTab)
            .putBoolean("showQuickBar", showQuickBar)
            .putBoolean("volumeKeyScroll", volumeKeyScroll)
            .putString("defaultEncoding", defaultEncoding)
            .putBoolean("useCrLf", useCrLf)
            .putBoolean("restoreTabsOnStart", restoreTabsOnStart)
            .putString("webDavBaseUrl", webDavBaseUrl)
            .putString("webDavUsername", webDavUsername)
            .putString("webDavPassword", webDavPassword)
            .putString("wpSiteUrl", wpSiteUrl)
            .putString("wpUsername", wpUsername)
            .putString("wpAppPassword", wpAppPassword)
            .putString("ghOwnerRepo", ghOwnerRepo)
            .putString("ghBranch", ghBranch)
            .putString("ghToken", ghToken)
            .putString("ghPostsDir", ghPostsDir)
            .putString("ghImagesDir", ghImagesDir)
            .putString("notionToken", notionToken)
            .putString("notionParentPageId", notionParentPageId)
            .apply()
    }

    fun getTypeface(): Typeface = when (fontFamilyIndex) {
        0 -> Typeface.SANS_SERIF
        2 -> Typeface.SERIF
        else -> Typeface.MONOSPACE
    }

    fun getTabString(): String = if (useHardTab) "\t" else " ".repeat(tabWidth.coerceIn(2, 8))
}