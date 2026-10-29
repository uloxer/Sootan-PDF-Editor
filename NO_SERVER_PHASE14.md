# Server-free Text Editor — Phase 14

2026-10-29. The current editor is v2 PDFium-WASM: `useDocumentLoader` opens/lazily reads the document in WASM and `exportToBlob` saves it locally. It does not call the legacy JSON metadata/page/font/partial/cache routes. Following coordinator review, this batch connects its actual missing dependency instead of adding unused native JSON sessions.

`PdfCharcodes` shares the existing PDFBox font locator/hash/ToUnicode/encoding algorithm. The legacy controller retains its transport, nested request/response names and statuses. Worker operation `editor-charcodes` receives a bounded private binary snapshot plus fixed locator/text options and returns the existing JSON response. Native capability `text-editor-pdf` enables the real tool. No socket, generic filesystem route, permanent Java process or new native document cache is introduced. Optional Type3/CFF/GS helpers are not advertised.

The editor retains its existing WASM document/page/font/image/undo state. Reset/dispose aborts pending local charcode requests; every request deletes its private job in the existing finally path, and native host exit cancels owned descendants. Original bytes remain immutable. Existing viewer/editor permission and signed-save guards remain in place; the worker refuses extraction where restricted. The existing OCR button uses the connected English-only OCR worker and its real capability; server bootstrap/language calls are avoided offline.

The native Unicode probe exposed Windows system-code-page conversion of worker stdin. Headers now use explicit UTF-8 bytes. The corrected real native bridge returned all 10 glyph codes for `SwonaΩcafé` with upload/run/cleanup 200 and zero remaining job directories.

Verification:
- Affected `task backend:check TEST_TASK=:stirling-pdf:test "TEST_ARGS=--tests *PdfTextEditorCharcodeControllerTest*"`: 22 tests passed; `.tooling/phase14-backend-check.log`.
- `task frontend:check` with BackendResolver selection: type/lint/format and 12 tests passed; `.tooling/phase14-frontend-check.log`. No old suites repeated.
- Shared worker install/nonweb guard, C# host compile and one production core Windows UI build passed; `.tooling/phase14-compile.log`, `phase14-host-compile.log`, `phase14-ui-build.log`.
- Actual native WebView2 editor changed `Swona café Ω` to `Swona Ω café`, saved to FileContext and independently reopened/rendered the PDF with embedded font and unchanged original SHA256. `.tooling/phase14-shell/native-result.json`, `output-check.json`, `charcode-bridge.json`. The edit used WASM; the charcode bridge was separately exercised in the same native window. No external startup/resource requests were observed. Sky observed/focused the exact native window but input geometry was unavailable; DOM actions targeted only that actual WebView2, never Firefox.

The isolated normal host is PID 60344, profile `.tooling/phase14-profile`, bundle `frontend/dist`; debug was removed after acceptance. Installed 3.2.5/Data/profile untouched; no push/install/release. Legacy JSON session compatibility remains unadvertised because the current UI does not use it. Broader tool parity, profile migration and final standalone packaging remain queued; Firefox UI remains policy-blocked. Optional OCR dispatch was wired to the already verified worker, not rerun in this narrow text-edit batch.
