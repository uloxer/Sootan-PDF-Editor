# Sootan PDF Editor

A local-use, MIT-licensed core fork of Stirling-PDF, based on upstream commit `684608d143779bf3a8ffc67639f077a1202c62ff`. The familiar editor and open-source PDF processing APIs are retained. Separately licensed application layers, accounts, commercial cloud integration, and analytics have been removed.

**This build has no login, accounts, MFA, API keys, or application access-control filter.** Run it on your own computer. The Java server binds to `127.0.0.1` by default; the Windows launcher also enforces loopback binding. Core's existing CORS behavior is retained and is not a substitute for authentication. Do not expose this application directly to an untrusted network. Existing PDF sanitization, encryption, redaction, signature verification, temporary-file management, and file-handling protections remain.

## Windows x64 per-user installation

Build the unsigned per-user installer from this checkout:

```powershell
task windows:package
```

Run the resulting `Sootan PDF Editor-3.2.2.exe` installer as your normal user. The Start launcher opens your browser on a random loopback port. Use the tray menu **Quit**, or **Stop Sootan PDF Editor**, to stop the server; closing the browser alone leaves the tray app running. Private Java, Python and English OCR are included. Docker is not required. Optional Office and other external conversion helpers are not bundled. See [Windows packaging](packaging/windows/README.md) for build/install/uninstall instructions and limitations. This local-use binary has not been cleared for redistribution; see [third-party notices](packaging/windows/THIRD-PARTY.md).

## Build from source

Install JDK 25, Node.js 24 (24.15 or newer), npm, and Task 3.54.0. No proprietary build mode is supported.

```sh
task install
task check
task build
```

The bundled executable is in `app/core/build/libs/`. Run its generated JAR with `java -jar path-to-generated.jar`, then open http://127.0.0.1:8080. In PowerShell, list the exact output with `Get-ChildItem app/core/build/libs/*.jar` and pass that path to `java -jar`. For development, run `task dev`.

For an explicit Gradle build on Windows:

```powershell
$env:STIRLING_FLAVOR = 'core'
.\gradlew.bat :stirling-pdf:bootJar -PbuildWithFrontend=true
```

On Linux/macOS use `./gradlew` instead. Native PDFium libraries are bundled for the build host; use `-PjpdfiumPlatforms=linux-x64` or the matching target for a different platform. OCR and conversions need the existing external tools (qpdf, Ghostscript, Tesseract/OCRmyPDF, LibreOffice/unoserver, ImageMagick, and related tools); the Windows package supplies English OCR only. A bare Java installation does not provide all native conversions. Keep the bundled font and translation resources for non-Latin documents.

## Features and limitations

See [CORE_FEATURES.md](CORE_FEATURES.md) for retained tools, removals, dependency rationale and offline limitations. See [VERIFICATION.md](VERIFICATION.md) for measured results and unverified items. [SOOTAN_AUDIT.md](SOOTAN_AUDIT.md) records the starting baseline. Existing notices remain in [LICENSE](LICENSE), bundled dependency notices, including `app/core/src/main/resources/static/3rdPartyLicenses.json`.

## Upstream security patches

1. Keep `upstream` read-only and `origin` pointing to your fork. Fetch upstream tags/commits and inspect advisories and dependency changes against the pinned baseline.
2. Review each candidate's licence and path. Apply applicable MIT changes from `app/core`, `app/common`, shared build files and `frontend/src/editor/core`; do not copy code from excluded licence paths.
3. Cherry-pick focused fixes onto a temporary branch from `privacy-core`, resolve conflicts, and rerun `task check`, `task build`, representative PDF security tests and offline network smoke checks.
4. Review dependency notices and measured sizes, then merge into your fork. Update the recorded upstream baseline only after the applicable fixes and regressions have been reviewed. No automatic upstream merges or deployments are configured.
