# Standalone SVG and table CSV — 28 October 2026

This checkpoint connects exactly two additional worker operations through the existing Convert UI:

| Conversion | Fixed worker operation | Options/output |
| --- | --- | --- |
| SVG → PDF | `svg-pdf-create` (`svg-to-pdf`) | Indexed selected-file grants; `combineIntoSinglePdf`; single/combined PDF or numbered PDF ZIP |
| PDF → CSV | `pdf-csv-export` (`pdf-to-csv`) | `pageNumbers`, session-only `sourcePassword`; one table CSV or ordered table ZIP |

The retained Batik renderer, Tabula lattice parser and CSV formula sanitizer live in the plain shared module. Spring facades retain the legacy sanitizer/parser contracts, and the CSV controller delegates to the same extraction service with its injected parser. The CI-owned signature resource remains canonical in the shared module.

SVG preflight permits local fragment references and embedded PNG/JPEG/GIF images. It refuses DOCTYPE/entities, stylesheet processing instructions, external base URIs, external images/styles/fonts, CSS imports and escaped CSS references. Existing sanitizer removes executable elements/handlers. Batik separately prohibits scripts and external resource loading. Limits:20 SVGs,8MiB each,50,000 DOM nodes,XML depth128,4MiB per embedded bitmap and25million aggregate embedded pixels; rendered page geometry is bounded. The finite native grants remain read-only private copies, never filesystem roots. Refusals have a fixed human-readable error; remote content is not fetched.

The SVG renderer needs Batik's codec module for embedded PNG decoding. Saved SVG output initially exposed a closed-source Form XObject stream after page import; single PDFs now retain their original rendered bytes, and combining uses PDFBox's cloning merger. Exports preserve vector PDF content.

CSV retains the existing ruled-table algorithm, quoting and literal-cell formula protection. Borderless/scan tables remain the existing parser limitation. No-table worker jobs fail rather than return a fictitious table; the legacy controller retains its204 response. Read exports check extraction permission, retain original bytes and do not require owner permission for unrestricted documents. Selected-page order is preserved; ZIP entry names are deterministic and contain no client paths. Reports are bounded to1,000 tables/64MiB characters and1,000 input pages. Both new native operations have a three-minute owned-process deadline and existing cancellation cleanup.

## Verification

- Required Task backend gates: **38 core tests** (`SvgToPdfTest`, both SVG controller classes and both CSV controller classes), **44 common tests** (sanitizer, CSV safety and Tabula parser), all passing. Logs: `.tooling/phase11-core-check.log`, `.tooling/phase11-common-check.log`.
- Worker install/nonweb guard and required Task check passed: `.tooling/phase11-worker-final.log`, `.tooling/phase11-worker-check.log`.
- Required frontend Task check passed typecheck/lint/format and **24 tests**, including real Convert processor SVG/CSV dispatch, output names and resource-refusal cleanup: `.tooling/phase11-frontend-check.log`.
- Actual strict-stdio worker goldens: **23 passed**. Saved SVG pixels/geometry, embedded bitmap, combined/separate PDF outputs, malicious XML/CSS/resource/pixel/byte limits, actual table cells/formula escaping, selected-page ZIP order and protected-reader refusal/owner extraction. Originals hash unchanged. Recipe: `packaging/worker/structured_smoke.py --java .tooling/jdk-25.0.4.1+1/bin/java.exe` with the existing private helper Python. Evidence: `.tooling/nonweb-structured-golden/results.json`, `.tooling/phase11-golden-final.log`.
- C# host compiled and native-origin boundary tests passed: `.tooling/phase11-host-check.log`. Production bundle built in **32.74s**, generated entry syntax checks passed: `.tooling/phase11-ui-build.log`. The first attempt hit Windows EPERM because the previous isolated host mapped `frontend/dist/assets`; only that verified prototype was stopped, then the final bundle was built separately at `.tooling/phase11-ui`.
- Actual native WebView Convert→CSV: selected CSV in the existing tool, input/run/cleanup all200 on the in-process virtual origin, Review/FileContext contained `converted.zip`. Its exact saved bytes were independently reopened: both tables and escaped formulas match; source SHA256 remains `cbf2736875460e0738cc8d5d51f56d462e97f1aa5b875fa54a7752e54ee5b644`; external browser resource requests `[]`. Evidence: `.tooling/phase11-shell/native-result.json`, `output-check.json`, `file-1.bin`. Virtual-origin200 responses do not use an HTTP listener.
- Final normal isolated host **PID50736**, title **Sootan PDF Editor**, no CDP, **zero owned listening sockets and zero idle workers**: `.tooling/phase11-shell/final-state.json`. Profile `.tooling/phase11-profile`; installed application and real Data remain untouched.

Worker library footprint: **88 JARs,72,184,407bytes**, compared with phase10's64,084,642bytes. This excludes Java, UI, fonts and helpers and is not an installer-size claim. Focused test coverage is not a whole-project coverage claim.

## Remaining work

Editable JSON remains the existing `PdfJsonConversionService`/font/Type3 service extraction, with its exact schema and roundtrip contract still unconnected. PDF/A remains the existing real PDFBox fallback with helper-dependent missing-font/transparency branches; no claimed compliance conversion or attachment PDF/A-3b flag has been enabled. Both need dedicated implementation/verification. Layouts, forms/editing, filtering/compression, final profile migration/packaging and safe per-user replacement remain pending. Firefox actual UI acceptance remains policy-blocked; no Firefox UI was attempted. No push, release publication or installed replacement occurred in this checkpoint.
