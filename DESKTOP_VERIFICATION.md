# Desktop implementation checkpoint — 26 October 2026

**Native acceptance is paused, not complete.** The isolated3.2.5 app-image is running, but a Windows Security firewall permission prompt for its OpenJDK runtime overlays the window. No security prompt was acted on and no Windows security settings were changed. User dismissal is required before native interaction can continue. The installed3.2.4 app remains untouched and responds at http://127.0.0.1:10107/.

## Implemented and checked

- Fresh WinForms/WebView2 host around the existing MIT editor/backend, without restoring restricted desktop code or adding Electron/browser payload. Shared Evergreen runtime queried successfully through the Microsoft runtime API:155.0.4283.62.
- Pinned Microsoft WebView2 SDK1.0.4314.35 from official NuGet; SHA256 `90778deb1b77e36292828b4b5833c9255c27533f40e0ddd55891ae718944edc4`. Only Core703,328bytes, WinForms38,792bytes and x64 loader194,912bytes ship, plus the22,528byte host and SDK notices. The shared browser runtime is separate and not bundled. No runtime installation was performed.
- Compact document tabs use FileContext and ViewerContext.activeFileId. Switching selects that PDF for processing; active close chooses a neighbor; inactive close leaves active state alone. Removal explicitly uses `removeFiles(ids,false)` to retain library entries. Existing save/discard/cancel navigation guards protect active edits. Arrow/Home/End/Delete and Ctrl+Tab/Ctrl+Shift+Tab/Ctrl+W are implemented.
- Existing library, grouped tool rail, viewer toolbar, neutral layout and Sootan green/slate brand remain. One backend/WebView workspace serves all documents.
- Native File/Open and Windows download Save dialogs; file arguments route selected PDFs through one-use same-origin URLs and read-only file streams. No whole-folder virtual mapping or arbitrary filesystem bridge exists. Host objects are disabled; remote top-level navigation is blocked; native messages require the trusted loopback origin. User-initiated external HTTP links may open the default browser. Native close/Stop routes through the existing frontend unsaved-edit guard.
- WebView profile and persistent port live in the separate Data directory. Closing the host stops its owned backend/descendants; repeated launches route files to the existing host through a current-user-only pipe. These lifecycle paths still require actual interaction acceptance.
- `task frontend:check TEST_FILES="src/editor/core/components/layout/DocumentTabs.test.tsx src/editor/core/components/layout/DesktopBridge.test.tsx src/editor/core/components/session/WorkbenchSessionPersistence.test.tsx" STIRLING_CHECK_JOBS=2`: PASS, all relevant lint/type/format checks and39 tests (8tabs,6grant boundary,25session persistence). Log: `.tooling/desktop-frontend-final-check.log`.
- Native origin policy tests and launcher data-path regression passed during the app-image build. First3.2.5 frontend/JAR build passed50s; package image built with `python3.11 packaging/windows/build.py --skip-build --image-only`. Logs: desktop-jar.log, desktop-image.log. The latest source preserves native file timestamps for reopen/dedup; that small frontend correction passed the final gate but still needs the final bundled build after UI acceptance resumes.

## Actual isolated window observed

The native SootanHost.exe window displayed three tabs supplied as file arguments with spaces: Hill notes.pdf, Slate report.pdf, Swona map.pdf. Read-only CDP inside that actual WebView confirmed exactly Swona active and rendered PDF images450x636pixels; this was not headless Chrome. Computer-use captured the actual Windows title/menu/frame and tabs, but its screenshot also contained the security overlay and is not a clean delivery screenshot. No UI inputs occurred after discovering that prompt.

At checkpoint: isolated backend PID29904 at http://127.0.0.1:27378/, host PID37764; test-only CDP port9335. Verify live state before resuming. Program: `.tooling/windows-package/Sootan PDF Editor/`; isolated state/profile: `.tooling/Desktop Test Data/` and its WebView2 subdirectory. Synthetic originals: `.tooling/Desktop PDFs With Spaces/`, with original-hashes.json. Read-only DOM record: desktop-dom-readonly.json. Original installed program/data remain `%LOCALAPPDATA%/Sootan PDF Editor` and `%LOCALAPPDATA%/Sootan PDF Editor Data`.

Preliminary app-image470,012,686logical bytes; JAR217,659,226bytes. These are checkpoint measurements, not final installer measurements. Installer creation was intentionally deferred until app-image acceptance; no3.2.5 installer or installed upgrade is claimed.

## Required next actions

After the user dismisses the Windows Security prompt, reobserve the real window. Test native switching/closing/reopening, Ctrl shortcuts, retained library entries, unsaved guard, file Open/Save with spaces, output reopen, close/relaunch/session persistence and original-file hashes. Capture clean native screenshots with multiple tabs. Collect any necessary fixes, then do one final bundled build and app-image check. Back up actual Data contents before upgrading only this per-user product, verify preserved hashes, run the existing installed OCR/security/merge/split/rotate/viewer harness, and leave the native app visibly running. Record final installer/hash/size and update current install instructions.

Current3.2.4 installation/recovery history remains in INSTALLED_VERIFICATION.md. Unsigned/local-use redistribution and optional-helper limitations remain unchanged. No first-ever cold-cache reset, external release or publication is claimed.
