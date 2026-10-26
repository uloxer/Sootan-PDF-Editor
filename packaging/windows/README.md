# Windows x64 package

Windows 10/11 x64 only. The unsigned EXE installs for the current user under `%LOCALAPPDATA%/Sootan PDF Editor`, without elevation, services or machine PATH changes. Private Java, Python and English OCR are included; Docker is not needed. Windows may show an unknown-publisher warning. This local-use artifact is **not cleared for redistribution**; read [THIRD-PARTY.md](THIRD-PARTY.md).

The Start launcher opens the default browser on a random loopback port. Use the tray menu **Quit**, or **Stop Sootan PDF Editor**, to stop the server. Closing the browser alone leaves the tray app running. Accounts/authentication are absent; do not expose the server remotely.

Config, logs and temporary files are writable under `%LOCALAPPDATA%/Sootan PDF Editor`; see `application.log` for tool errors. The test-only `SOOTAN_USER_DATA` override isolates this directory. Stop the app, then uninstall through Windows Settings > Apps as the same user. User data is retained; remove that directory separately only when its documents/settings are no longer needed. The app-image folder also runs directly from a writable path with spaces.

## Reproduce

Build on Windows x64 with JDK25 (`JAVA_HOME`), Node24/npm, Task3.54, uv, Python3.11+ (`python3.11`) and the Windows .NET Framework4 compiler present on supported Windows versions. These are build prerequisites. Run `task windows:package`, or `python3.11 packaging/windows/build.py`.

Download URLs/SHA256 pins are in downloads.json; mismatches fail the build. Embedded Python is3.13.12, OCRmyPDF17.13.0 and Tesseract5.5.3.20260724. Wheel versions/hashes are locked in ocr-requirements.txt. The tiny C# command wrapper uses Windows .NET Framework and forwards stdout/stderr; no new UI framework is installed. WiX/7-Zip stay in the workspace build cache.

Outputs are `.tooling/windows-package/Sootan PDF Editor/`, `.tooling/windows-package/installer/Sootan PDF Editor-3.2.2.exe` and `sizes.json`. The builder cleans only its own workspace output directories; stop the app before rebuilding. `--skip-build` reuses a verified Windows JAR for packaging-only changes. The installer uses version3.2.2 and stable upgrade UUID `adcbdd25-309f-43c7-81cd-557cd7601674`. Inputs and recipe are reproducible; byte-identical timestamps/product IDs are not claimed.

## Included and optional features

The existing editor, all bundled fonts/translations and Java PDF/security libraries remain. Windows JPDFium is included. Linux PDFium and duplicate static .gz/.br siblings are excluded, retaining original resources and dynamic HTTP compression. No similarly named Java libraries are arbitrarily removed. The private Java runtime retains image, XML signature, crypto, Windows certificate, PKCS11/smartcard, charset and locale modules; exact modules are in sizes.json/build.py.

English OCR supports searchable PDF, sidecar text, deskew and orientation correction, using OCRmyPDF auto/fpdf2 rendering and PDFium rasterization. Only `eng.traineddata` and `osd.traineddata` are included; osd detects orientation and is not an extra text language. Existing hocr/sandwich callers remain accepted. Cleaning requires optional unpaper and is disabled when absent. Remove-images-after requires optional Ghostscript and returns a clear error when absent. The Tesseract-only fallback rejects unsupported advanced options. Normal bundled OCR does not need Ghostscript.

LibreOffice/unoserver, Ghostscript, unpaper, standalone qpdf and other optional native tools are not bundled. Office conversion, certain compression/cleanup and other tool-dependent operations require the existing separately installed integrations. Bundled pikepdf/QPDF serves OCR, not a global qpdf executable. The launcher prepends private helpers to its child PATH and retains the inherited PATH for optional tools; it never changes global PATH.

See [WINDOWS_VERIFICATION.md](../../WINDOWS_VERIFICATION.md) for actual sizes, checks and limitations. Runtime/helper payloads make the complete installer larger than a standalone JAR; these are different deliverables. No Docker, arbitrary Office conversion or full native-tool coverage is claimed.
