# Editable JSON checkpoint — 29 October 2026

The existing JSON serializer/rebuilder, complete schema, COS mapper, fallback-font logic and Type3 glyph algorithms now live in the plain PDF module. Typed context adapters supply selected input, owned temporary files, document loading, configuration, progress and optional helpers. The Spring facade retains its public upload signatures and configured legacy helper behavior.

Convert connects two finite native operations:

| UI pair | Endpoint alias | Worker contract |
| --- | --- | --- |
| PDF → Editable PDF JSON | `pdf-to-text-editor` | `json-export`; session `sourcePassword`, complete export (`lightweight=false`); `output.json`, `application/json` |
| Editable PDF JSON → PDF | `text-editor-to-pdf` | `json-import`; one indexed read-only JSON grant in `selectedFiles`; `output.pdf` |

These use the existing embedded font/image/raw-stream schema. They preserve its serialization scope; original-document-backed interactive text editing remains pending. No lazy images or API URLs are accepted for import. The PDF Text Editor tool remains unavailable until its real session workflow is connected.

Worker limits:50MiB input,100pages,512fonts,JSON depth64/one million tokens/100,000 model objects;16MiB per binary field/decoded PDF stream and64MiB aggregate;25million bitmap pixels;128MiB output. XML entity declarations and active PDF action names are refused. JSON parsing, selected files and font resources cannot select filesystem roots or remote resources. Fonts resolve only from the controlled bundled directory. Host deadline is three minutes; existing job cancellation/process ownership applies. PDF export requires extraction permission, including readable signed inputs; originals remain read-only.

Two real defects were fixed during independent acceptance:

- The retained ordinary-font encoder stripped zero/control bytes from PDFBox's already encoded CID data. It now preserves that byte sequence, retaining variable-width CMap semantics without padding or a schema change.
- Windows `Files.copy` propagated a read-only selected grant's flag to scratch PDF files, causing cleanup to fail after successful export. Input is now streamed into the owned writable scratch file. The original grant is unchanged.

## Evidence

- Full family compatibility gate: **631 JSON/Type3/controller tests passed** before the CID fix: `.tooling/phase12-core-check.log`. No assertions/thresholds were removed.
- After CID fix: **35 targeted CID/Unicode/charcode tests passed**, worker/nonweb gate passed: `.tooling/phase12-cid-check.log`, `.tooling/phase12-worker-check.log`.
- Frontend required Task gate passed type/lint/format and **42 tests**, including actual Convert JSON export/import processor dispatch: `.tooling/phase12-frontend-check.log`.
- **12 actual strict-stdio worker goldens passed**: real Unicode text edit, saved/reloaded forms/images/rotation, Type3 schema and exact rebuilt Type3-page pixels, malformed/deep/oversized/active JSON refusal, protected reader/owner roles and original hashes. `.tooling/phase12-golden-final.log`, `.tooling/nonweb-json-golden/results.json`; recipe `packaging/worker/json_smoke.py`.
- After the native-only read-only failure, only the affected saved/reloaded CID regression was rerun with a read-only source: **one passed**. `.tooling/phase12-readonly-check.log`. Earlier suites were not repeated.
- Production UI built in **30.94s**; generated entry syntax and C# host compilation passed: `.tooling/phase12-ui-build.log`, `.tooling/phase12-host-compile.log`.
- Actual native Convert→JSON passed after the scratch fix: input/run/cleanup200, Review/FileContext `converted.json` **43,629bytes**. Its saved bytes independently contain Unicode, embedded image, form, annotations, rotation and Type3 glyphs. Source SHA256 remains `7e1f404d39fc7c11f06aa9dee23de9796410263d072064ec0dc8aa59ffe89bf8`; external resource requests `[]`. Evidence `.tooling/phase12-shell/native-result.json`, `output-check.json`; initial failure retained in `native-first-failure.json` and `repro-job/result.json`.
- Final isolated normal host **PID58920**, title **Sootan PDF Editor**, **zero owned listening sockets/idle workers**, debug disabled: `.tooling/phase12-shell/final-state.json`. Profile `.tooling/phase12-profile`, bundle `.tooling/phase12-ui`. Installed3.2.5 and real Data remain untouched.

Worker libraries: **88 JARs,72,428,437bytes**; Java/UI/fonts/helpers excluded. This is not an installer-size claim. Focused coverage is not a full-suite coverage claim. No push/install/release occurred.

## Remaining contracts

Interactive text-editor work must connect existing metadata/page/font/image/updated-page export and charcode routes, using an owned retained-document session or a shared local frontend model; one-shot processes do not retain the old cache. Core entrypoints remain `extractDocumentMetadata`, `extractSinglePage`, `extractPageFonts`, `exportUpdatedPages`, `clearCachedDocument` through the facade. Optional library-synthesized Type3 web-font candidates and external CFF/Ghostscript normalization remain unconnected in the worker; raw Type3 glyph/resources roundtrip is verified.

Real PDF/A conversion/validation, remaining layouts/forms/editing/filtering/compression families, profile/library migration and final standalone packaging remain queued. Firefox actual UI is still policy-blocked and was not attempted. Existing unsigned/local-use redistribution limitations remain unchanged.
