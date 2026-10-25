# Verification (Windows, 25 October 2026)

Upstream baseline: `684608d143779bf3a8ffc67639f077a1202c62ff`. JDK 25.0.4.1, Gradle 9.7.1, Node 24.14.0/npm 11.9.0, Task 3.54.0. JDK/Task were downloaded into ignored `.tooling` without global installation. npm reported an engine warning for a transitive `ini` package; use Node 24.15+ for new builds.

## Builds and checks

- Bundled production build: `gradlew.bat :stirling-pdf:bootJar -PbuildWithFrontend=true` **passed**, including core Vite production build. Final branding-resource repack reused the successful frontend output and passed in 47 seconds. This was a resource/log branding change, not another full frontend compile.
- `task frontend:check` with `STIRLING_CHECK_JOBS=2`: **passed** typecheck, oxlint, theme/icon/CSS checks, format check and **381 files / 4,110 tests**. First run under higher concurrency had UI timeouts and two fork-specific audits; core env/retained-translation audits were fixed and all four affected files passed 47 tests in isolation before the final full pass.
- `task backend:check`: formatting and compilation passed; full JUnit run **did not pass** on Windows. XML reports total 6,064 cases: 6,034 passed, 8 failed and 22 skipped. Retained security-related suites (sanitization, password/encryption, signatures, redaction, temp/file/path/SSRF/ZIP protections) had no failures. See the exact failures below.
- Working-tree `git diff --check`: passed.

The eight backend failures are Windows drive/path or Linux sandbox assumptions. Those sandbox implementation/test files were not changed by this fork; no independent baseline test run was performed, so these are not presented as proven pre-existing failures. Do not read the full backend gate as green. Linux container verification remains required.

### common backend failures

- `stirling.software.common.util.CalibreSandboxPolicyTest.processExecutorLaunchesCalibreConfined()`
- `stirling.software.common.util.CalibreSandboxPolicyTest.commandIsWrappedInTheLauncherAndConfinedToTheWorkingDirectory()`
- `stirling.software.common.util.LibreOfficeSandboxPolicyTest.confinesDirectConversionToItsOwnFiles()`
- `stirling.software.common.util.LibreOfficeSandboxPolicyTest.pathsOutsideTheWorkRootsAreNotGranted()`
- `stirling.software.common.util.LibreOfficeSandboxPolicyTest.convertToFilterValueIsNotTreatedAsInput()`
- `stirling.software.common.util.LibreOfficeSandboxPolicyTest.pathWithListSeparatorIsLeftToSharedPolicy()`
- `stirling.software.common.util.ProcessExecutorLibreOfficeRetryTest.freshProfileIsRetriedOnceItHasBeenInitialised()`

### core backend failures

- `stirling.software.SPDF.controller.api.pipeline.PipelineDirectoryProcessorOutputPathTest.keepsTheFinishedFolderForTheStandardPlaceholders()`

The pipeline test changed only to remove the deleted telemetry constructor argument. Its failing expected path concatenates a Windows drive-qualified job path underneath another path. The sandbox failures assume Unix launcher paths, colon-separated sandbox allow-lists or LibreOffice startup behavior. Tests and protections were retained; none were disabled to turn the gate green.

## Live smoke results

The bundled executable started with isolated configuration under `.tooling/runtime`, on **127.0.0.1:18080**. Spring reported **13.797 seconds** startup (15.504 seconds process lifetime). After representative processing, one working-set observation was **460,570,624 bytes**, with private memory **496,103,424 bytes**. This is a single local post-smoke observation, not a controlled idle benchmark or comparison with upstream.

Actual HTTP requests against that executable:

| Operation | Result | Observed elapsed |
| --- | --- | ---: |
| Merge | 2 + 2 pages yielded 4 pages | 2,445 ms (first operation) |
| Split | 2 pages yielded two PDF entries in ZIP | 146 ms |
| Sanitize | injected JavaScript OpenAction and title metadata removed | 74 ms |
| Add password | AES-256 output rejected opening without password | 172 ms |
| Remove password | decrypted output retained 2 pages | 87 ms |
| Certificate signing/validation | generated local PKCS12 signed successfully; verification returned `valid=true`, `coversEntireDocument=true` | HTTP 200 |

The self-signed certificate correctly returned `selfSigned=true`, `chainValid=false` and `trustValid=false`. A successful cryptographic signature was not represented as a trusted certificate chain. The upstream IRS fixture returned an empty signature list; the generated signed document was used to verify the signed path instead.

Headless installed Chrome opened the actual application, waited 18 seconds beyond initial network idle, then opened the sample PDF. It recorded **32 requests, zero external HTTP(S) requests and zero page errors**. External URLs were intercepted and would have been recorded even when blocked. The observed title and main branding were Sootan PDF Editor. This bounded startup/PDF-open check is not proof of all tools' network behavior. Font loading was not exercised by the blank-page sample; local font fallback/configuration tests passed in the frontend suite. Optional URL import, remote timestamp/trust checking and phone transfer are network-dependent tools.

Local evidence retained in ignored `.tooling`: `frontend-check3.log`, `backend-check2.log`, `test-summary.json`, `bundled-build.log`, `final-package.log`, `runtime-smoke.json`, `signature-smoke.json`, `browser-smoke.json`, `editor-smoke.png`, and `runtime.log`. JUnit XML/HTML reports are under `app/{common,core}/build/{test-results,reports/tests}/test`. The temporary server was stopped after verification.

## Integration follow-up

The remaining desktop search placeholder now replaces the inherited Stirling name at render time while preserving localized wording; no locale files changed. The obsolete account-link property/comment was removed. Typechecking, oxlint, theme/icon/CSS lint and format checks passed; the focused SuperSearch suite passed **13/13**, including three inherited-translation regression cases. The task runner ignored a requested test-file override, so its unintended broad test run was stopped and the explicit focused Vitest command was used. The previously recorded full-suite results remain separate.

A complete bundled rebuild passed in **3m 34s**. Installed Chrome against that rebuilt executable confirmed the actual placeholder **Search Sootan PDF Editor**; `.tooling/editor-placeholder.png` and `.tooling/placeholder-browser.json` record the result. The temporary server was stopped. Follow-up logs: `.tooling/placeholder-check2.log`, `.tooling/placeholder-test.log`, `.tooling/placeholder-build.log`.

## Measurements

The source measurement uses Git blob lengths for both the pinned baseline and staged final tree, excluding Git history, generated output, ignored dependencies and downloaded tooling. `python scripts/measure-core.py` reproduces it after staging edits. Primary code means Java/TypeScript/Python/Rust under app main Java, frontend/src, engine/src and frontend/src-tauri/src; frontend colocated tests are included. The all-language denominator includes tests and tooling anywhere in the tree.

| Measure | Upstream baseline | Core | Reduction |
| --- | ---: | ---: | ---: |
| Versioned files | 8,371 | 4,789 | 42.8% |
| Versioned bytes | 209,944,276 | 187,844,124 | 10.5% |
| Primary code bytes | 29,873,330 | 17,056,558 | 42.9% |
| All primary-language bytes, including tests | 41,211,293 | 22,687,636 | 44.9% |
| Executable JAR bytes | Not built | 306,789,091 | Not established |
| Frontend dist bytes | Not built | 154,249,375 | Not established |

Frontend output includes local WASM, fonts, translations and compressed variants; its directory size is not JavaScript size. JAR and frontend numbers are after-only measurements. Baseline executable/bundle, Docker image size, cold/idle benchmark comparisons, complex-document rendering/conversion performance and native OCR/Office smoke measurements were not established. Docker is unavailable on this host; the supplied full-tools image recipe has not been built here. No image-size, startup improvement, idle-memory improvement or processing-speed improvement is claimed.
