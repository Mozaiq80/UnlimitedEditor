# TextEdit (T.E. / UnlimitedEditor)

순수 Kotlin과 커스텀 Canvas 기반으로 만든 초경량 안드로이드(및 삼성 DeX) 텍스트 에디터입니다.

## 주요 기능

- 대용량 가상화 및 뷰포트 구문 강조 (lineWrapCache, prefixCounts)
- 삼성 DeX 및 키보드 단축키 (Ctrl+방향키, Ctrl+Shift+방향키, Ctrl+Backspace/Delete, Esc/F10, F6, F1)
- Notepad++ 스타일 세션 복원 (열린 탭, session_drafts, 커서/스크롤)
- 정규식 검색/치환 (Ctrl+F/H, $1/$2) 및 줄 필터링 (Ctrl+Shift+F)
- 좌우 비교 및 병합 DiffEngine (Ctrl+D, /, Alt+/, Ctrl+Z/Y)
- NAS WebDAV 동기화 (Ctrl+Shift+O/S/U) 및 외부 발행 (Ctrl+P)

## 빌드 (PowerShell)

Set-Location C:\Projects\UnlimitedEditor
.\gradlew.bat assembleDebug --console=plain

빌드 산출물: app\build\outputs\apk\debug\app-debug.apk
