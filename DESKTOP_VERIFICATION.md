# Desktop verification — Windows x64, 26 October 2026

Version **3.2.5** is installed per user and running in its own visible **Sootan PDF Editor** window. The final normal launch uses http://127.0.0.1:7740/, backend PID43464 and native host PID38496. Test CDP is off; port9336 is closed. These PIDs are a point-in-time record.

## Delivered behavior

A fresh WinForms/WebView2 host wraps the retained MIT editor and loopback backend. No restricted upstream desktop code, Electron, accounts, telemetry, browser payload or global runtime installation was added. The existing library/sidebar, grouped tool rail, viewer toolbar and neutral surfaces remain, with compact document tabs and the Sootan green/slate mark. The shared Evergreen runtime queried through Microsoft's API is155.0.4283.62. SDK1.0.4314.35 is pinned with notices; only its Core703,328bytes, WinForms38,792bytes and x64 loader194,912bytes ship. Missing-runtime/offline instructions are in packaging/windows/README.md.

Tabs use FileContext and ViewerContext, select the active PDF for processing, retain stored records on close and reuse the existing unsaved-edit guard. Native file intake grants only explicit PDF selections through one-use same-origin URLs/read-only streams. There is no arbitrary filesystem/root bridge. Host objects are disabled, remote top-level navigation is blocked, permissions are denied, and messages require the trusted loopback origin. The WebView profile, stable port and workspace record live under the separate Data directory. Browser workspaces keep their existing per-tab sessionStorage; the single native workspace persists through localStorage.

## Actual native checks

- Native window observed with three synthetic PDFs supplied as paths containing spaces. Switching, Ctrl+Tab/Ctrl+Shift+Tab, Ctrl+W, active/inactive close and retained-library reopen passed. Closing selects a neighbor; original disk PDFs retain their recorded SHA256 hashes.
- Actual rectangle annotation armed the Save & Leave / Keep Working / Discard & Leave guard. Keep Working retained the edit, Save & Leave applied it and switched, and Discard & Leave discarded a synthetic pending edit and switched. A discovered saved-tab remount defect was fixed: a fresh viewer must load initial bytes even when the previous viewer's live-save optimization says to skip a reload. The corrected image reopened/rendered the saved annotated three-page PDF.
- Ctrl+O opened the Windows PDF picker and imported a path with spaces. Native PDF Save As wrote `Saved output with spaces.pdf` (249,326bytes, three valid pages); the installed native app reopened/rendered that saved copy through a second file-argument launch, then Ctrl+W closed its tab while the library/disk copy remained.
- A ZIP fixture containing the prior actual OCR PDF+TXT was triggered inside the real WebView and saved through the native dialog. The dialog advertised ZIP archives and saved `English sidecar export.zip` (249,127bytes) with both entries intact. This checks the host download path, not another OCR run. PDF/ZIP filters and default extensions are selected by the suggested filename.
- Orderly native Close and the Stop launcher stopped the owned host/backend, and the installed Stop test closed port7740. Relaunch retained all three tabs/active document at the same origin. Repeated launch/file arguments retained one backend and one host with no duplicate workspace tab. The final installed normal launch has no debug port and is left visibly running.
- Actual installed WebView DOM recorded three tabs and rendered blob images450x636. A frontend reload/startup trace recorded37 requests and zero external HTTP requests. This observes the app's frontend requests, not every background activity of Microsoft's separately installed Evergreen runtime.

Native screenshot: `.tooling/desktop-installed-evidence/native-three-tabs-dark.png`. It includes the real Windows title/menu/frame and three tabs, not headless Chrome. Dark theme was captured; no separate light-theme native capture is claimed. The earlier Windows Security prompt was handled manually by the user; automation did not act on the prompt or change security settings.

## Build and focused checks

- Final Windows JAR: `gradlew.bat :stirling-pdf:bootJar -PbuildWithFrontend=true -PwindowsPackage -PjpdfiumPlatforms=windows-x64 --console=plain` — PASS. Log `.tooling/desktop-final-jar.log`. This includes the native intake timestamp/dedup correction, saved-tab fix and native workspace persistence.
- `task frontend:check TEST_FILES="src/editor/core/services/workbenchSession.test.ts src/editor/core/components/layout/DocumentTabs.test.tsx src/editor/core/components/layout/DesktopBridge.test.tsx src/editor/core/components/session/WorkbenchSessionPersistence.test.tsx" STIRLING_CHECK_JOBS=2` — PASS,56 tests across4 files; relevant type/lint/theme/icons/CSS/format gates passed. Log `.tooling/desktop-final-gate.log`.
- Native host origin/extension regression and launcher separate-data-path tests — PASS during app-image recipe. Log `.tooling/desktop-final-image.log`. The standard recipe is `python3.11 packaging/windows/build.py`; this verification used the same module with WORK/OUT redirected to windows-build-final/windows-package-final and `--skip-build --image-only`, then the recipe's jpackage EXE command over the accepted image. Local exact invocation `.tooling/build-desktop-final.py`; installer log `.tooling/desktop-final-installer.log`.
- Installed HTTP smoke — PASS in38.31seconds: searchable English OCR with sidecar, deskew/orientation; unavailable clean/cleanFinal/removeImagesAfter return400; merge4pages, split2single-page PDFs, rotate90; remove JavaScript/metadata; AES256 rejects absent/wrong password and decrypts with correct password; cryptographically valid self-signed signature correctly remains untrusted; original assets respond with br/gzip negotiation. Evidence `.tooling/desktop-installed-evidence/http-smoke.py`, `http-smoke.log`, `http-results.json`, `native-dom.json`.

No unrelated full6000-test rerun is claimed. Earlier platform-specific failures remain documented. English+osd OCR, Java/security/image/font payloads and optional-helper limitations are unchanged; Office/Ghostscript/unpaper are not bundled.

## Actual protected per-user upgrade

Verified the previous own installed backend37528/10107 before stopping it through its own launcher. Copied the actual10 persistent Data files (130,878bytes) to `.tooling/desktop-installed-evidence/preserved-data`, checked backup hashes, then ran only this product's3.2.5 installer with `/quiet /norestart /log`. Upgrade exit0. All10 files were byte/hash-identical immediately after upgrade. Subsequent normal runtime activity changed only the settings `appVersion` field and the application log; credential/key lines remain identical. These normal runtime changes are distinguished from installer preservation. Secret values were not printed or committed.

MSI ProductCode `{537B9F7F-836B-3CB2-A6E9-A9BBB37E81E6}`, Version3.2.5, AssignmentType0; one HKCU Installer product and one current3.2.5 uninstall entry. Its HKLM Add/Remove entry does not change the MSI per-user assignment. Sootan Start/Stop shortcuts point at the current per-user program. Installed JAR SHA256 matches the accepted app-image. Program `%LOCALAPPDATA%/Sootan PDF Editor`; data/profile `%LOCALAPPDATA%/Sootan PDF Editor Data`. No association takeover, service, UAC/AV setting change or unrelated application removal occurred.

Evidence: `upgrade.log`, `installation.json`, `registered-products.json`, `hkcu-registration.json`, `shortcuts.json`, `data-before.json`, `preservation-result.json`, `runtime-settings-changes.json` under `.tooling/desktop-installed-evidence/`. Historical3.2.3 shared-directory data loss/recovery and3.2.4 uninstall/reinstall proof remain disclosed in INSTALLED_VERIFICATION.md; this update does not claim the old lost key was recovered.

## Final local artifacts

| Artifact | Logical bytes | SHA256 |
| --- | ---: | --- |
| `.tooling/windows-package-final/installer/Sootan PDF Editor-3.2.5.exe` |332,832,256|`9d1c4377fd7180b1c670cbe0bbb45bba57b2c4d0b93d5f8c613a71d2f8222a97`|
| `.tooling/windows-package-final/Sootan PDF Editor/app/sootan.jar` |217,659,611|`bf571beb9b8f4571a1266573114d1f17252b1cd775f8ad4ff9a5151b1cd2ebe6`|
| App-image directory |470,014,018|Per-file manifest below|
| Actual installed program directory |470,013,735|Installed JAR matches above|

Image per-file manifest `.tooling/desktop-installed-evidence/image-manifest.json`, SHA256 `57270bcece41075b68b16790eee6d663feae617e4484929c5ef66d1406890ba0`; `artifacts.json` and final `sizes.json` record measurements. A directory has no single file hash; the manifest hashes its files. The shared WebView2 runtime/profile is separate from these package sizes. Compared with3.2.4, the installer adds271,360bytes and app-image974,620bytes. Private Java67,182,947bytes and OCR helpers182,919,561bytes remain the main extra payload alongside the JAR. No speculative additional size reduction is claimed.

Artifacts remain ignored/local and unsigned; the existing local-use/not-cleared-for-redistribution helper caveat remains. No external release or publication occurred. Current source commits and documentation are for coordinator review before pushing.
