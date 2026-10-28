# Phase two checkpoint — 28 October 2026

The shared frontend now dispatches local PDF page operations through the existing tool hooks and FileContext lifecycle. The Windows prototype has a themed, borderless caption. This checkpoint does not replace the installed 3.2.5 app or its separate Data directory.

## Local processing contract

`frontend/src/editor/core/runtime/corePdfOperations.ts` exports `runCorePdfOperation(operation, context)`:

- `merge`: ordered `files: File[]`.
- `rotate`: files and a relative `angle` in multiples of 90 degrees.
- `select`: one source and one-based ordered `pages`; duplicates are intentional.
- `split`: one source and ordered groups of one-based pages.
- `compose`: files and `{source, page, rotation?}[]`; source is a zero-based file index, page is one-based, rotation is absolute in multiples of 90 degrees.
- Context: optional AbortSignal and `progress(done, total)` callback. Output: `{files: File[], warnings: string[]}`. Errors expose `INVALID_INPUT`, `UNSUPPORTED_FEATURE`, `UNSUPPORTED_OPTION`, or `CANCELLED` plus a user-readable message. Operations create new files; source bytes are never written.

The object writer preserves content streams rather than rasterizing pages. Full-document rotation/reordering retains page references, nested page-tree inherited attributes, form values and bookmark destinations. Partial composition/merge rejects interactive forms and outlines when preservation cannot be assured. Signature-bearing or encrypted inputs are refused for local rewriting; password-based reading is unchanged. Blank-page insertion, table-of-contents generation, signature removal and unsupported rearrangement modes fail explicitly.

`runtime/processing.ts` exports `requestPdfProcessing(endpoint, FormData, AxiosRequestConfig)` and returns `{data: Blob, status, headers}`. Existing single-file, multi-file and extraction hooks use it. Server mode retains the existing Axios transport. Offline dispatch supports merge-pdfs, rotate-pdf, rearrange-pages, remove-pages and split-pages (ZIP). Existing Multi Tool save calls the typed compose operation through pdfExportService, rather than an unused side adapter. Offline form filling selects the local PDFium-backed provider instead of PDFBox HTTP.

## Capability boundary and phase-three handoff

`runtime/runtime.ts` owns the explicit runtime seed and core capability inventory. Core tool IDs: read, annotate, formFill, sign (drawn signature), merge, split, extractPages, reorganizePages, removePages, rotate and multiTool. The extension filters its catalog to these IDs; the desktop retains disabled future tools. No failed server request implicitly changes runtime mode.

Phase three should add a desktop-only processing adapter behind `requestPdfProcessing`, retaining its Blob/error/cancellation contract. The advanced adapter must advertise only actually connected worker capabilities; it must never become available in extension mode. The worker boundary needs request IDs, an explicit operation enum and validated options, selected-input bytes or narrowly granted files, typed output metadata/bytes, progress, cancellation, and human-readable failures. It must run lazily without Spring Web/Tomcat/HTTP or a listener. No worker transport exists in this checkpoint. Preserve server compatibility and the existing PDF permission gates; do not send passwords or arbitrary filesystem paths through a broad host bridge.

## Native caption and form rendering

The caption uses fixed minimize/maximize/close/drag/edge-resize actions accepted only from the trusted app origin. The native frame has no Windows caption; workarea bounds come from the current monitor. Existing close approval, Alt+F4 and Ctrl+O paths remain in place. Firefox chrome is untouched.

A startup timing defect drew forms twice: immutable Render plugin configuration captured native widget rendering before asynchronous access inspection enabled HTML fields. Tiled rendering now chooses one owner from verified document encryption state, overriding that stale flag. Interactive unencrypted tiles omit native widgets; native rendering remains for encrypted/read-only mode and full-page print/export. No widgets or appearances are removed from original PDFs. The shared flag covers text, buttons, checkboxes and other widgets.

## Verification

- `task frontend:check TEST_FILES="src/editor/core/runtime/corePdfOperations.test.ts src/editor/core/runtime/runtime.test.ts src/editor/core/tools/formFill/providers/PdfiumFormProvider.test.ts src/editor/core/components/viewer/viewerFormRendering.test.ts src/editor/core/components/viewer/pdfViewerAccess.test.ts" STIRLING_CHECK_JOBS=2`: typecheck, lint, theme/icons/CSS, format and 22 tests passed on final source, including stale render-flag ownership and encrypted/signature refusal.
- `task frontend:build:core` with SOOTAN_WINDOWS_PACKAGE=true: passed, including final widget fix. Existing circular zip/pdflib chunk and large-chunk warnings remain.
- Native SootanHostTests compile/run: passed origin, route traversal and PDF/ZIP download-extension boundaries; shared WebView2 runtime 156.0.4314.50.
- Actual isolated native prototype: themed caption and maximize/restore observed; first page rendered; zero external resource entries. Underlying tile inspection after the fix contains no native field appearance, while its HTML field remains visible.
- Physical PNG evidence: `.tooling/phase2-shell/form-single-full-resolution.png`, 1920×1020, captured directly with PrintWindow at physical DPI. No image pixels were edited.

## Firefox artifact and explicit limitation

`python3.11 packaging/firefox/build.py` packages the production UI, workers, WASM, fonts and translations. Stable ID: `sootan-pdf-editor@uloxer.local`; no host permissions or all-URLs permission. Inline bootstrap scripts become a local external file. CSP allows self and wasm-unsafe-eval, without unsafe-eval or remote scripts. Toolbar action opens the editor tab; extension routing uses HashRouter.

The unsigned XPI is a development checkpoint, not an accepted working Firefox release. Temporary-addon installation succeeded in isolated Firefox 159 using GeckoDriver 0.37.1; browser signing/security settings were not changed. Computer Use then stopped Firefox interaction because URL policy enforcement is unsupported for that Windows browser. No alternative browser automation was used to bypass this. Actual extension rendering, worker/font CSP execution, selection, editing, save and no-network acceptance remain blocked/unverified. In particular, the upstream PDFium engine currently creates a blob worker; Firefox MV3's strict worker-src self policy requires a bundled static-worker adaptation before the extension can be declared usable. Do not weaken CSP to unsafe-eval or claim the installed XPI renders correctly.

GeckoDriver official asset SHA256: dfed9315abe8d2fbc1b6161a2ee8002452e79cf05ee92fdc653a4e26bc35edd8, matched Mozilla release API digest. This test driver is not part of the app package.

The installed editor/data remain untouched. Unsigned Windows/local helper redistribution limitations in WINDOWS_PACKAGING.md continue to apply. No installer, publication or push is performed for this checkpoint.

Additional native acceptance on the final widget implementation: changed the field to `Sootan single appearance 2026`, focused/blurred, zoomed in/out, saved through the local Form Fill sidebar, then reloaded the entire WebView. The value persisted and the sharp page rendered. Evidence: `.tooling/phase2-shell/form-save-reopen.json`. Original fixture SHA256 before/after: `b3928cdfde2eee283aff60af833fe724be988412641563210575691e889a05ab`.

Alt+F4 with a new unsaved field edit displayed the existing Unsaved Changes guard; Keep Working retained the editor. That synthetic edit was restored to the saved value and saved. A caption state request now synchronizes the maximize/restore accessibility label after page reload; the final native host and bundle passed that reload check. Native drag/edge resize, double-click maximize, cross-monitor transitions and minimize restoration are not fully accepted here: the edge-resize attempt encountered computer-tool coordinate bounds inconsistent with its screenshot. No workaround or repeated input was attempted. These remain explicit desktop acceptance gaps, rather than inferred passes from source.


Final checkpoint artifacts (28 October): native prototype `.tooling/phase2-shell/SootanHost.exe`, isolated profile `.tooling/phase2-profile`, bundled UI `frontend/dist`, virtual origin `https://sootan.local/` (no HTTP app listener). Test-only CDP is explicitly enabled on loopback 9443; production defaults remain off. Final visible prototype PID at handoff: 57316. The installed editor was not replaced.

Final unsigned XPI: `.tooling/firefox-package/sootan-pdf-editor-3.2.5-unsigned.xpi`, 58,081,949 bytes, SHA256 `35894b1a399e5637263e26159f3a3d15393446720883e547e5621aa34d85de51`. This is the source-coherent package, subject to the Firefox limitation above.
