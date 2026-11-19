# TextEdit (T.E. / UnlimitedEditor)

A lightweight Android (and Samsung DeX) text editor app built with pure Kotlin and a custom Canvas.

## Key Features

- Viewport-only syntax highlighting and incremental wrap caching (lineWrapCache, prefixCounts)
- Samsung DeX and hardware keyboard shortcuts (Ctrl+Arrows, Ctrl+Shift+Arrows, Ctrl+Backspace/Delete, Esc/F10, F6, F1)
- Notepad++-style session restoration (open tabs, session_drafts, cursor and scroll state)
- Regex search/replace (Ctrl+F/H, capture groups) and line filtering (Ctrl+Shift+F)
- Side-by-side diff and merge via DiffEngine (Ctrl+D, Up/Down, Alt+Left/Right, Ctrl+Z/Y)
- WebDAV sync (Ctrl+Shift+O/S/U) and external publishing (Ctrl+P)

## Build (PowerShell)

Set-Location C:\Projects\UnlimitedEditor
.\gradlew.bat assembleDebug --console=plain

Output APK: app\build\outputs\apk\debug\app-debug.apk
