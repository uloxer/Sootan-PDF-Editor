# Standalone document maintenance — phase 4

Verified 28 October 2026. This is a review checkpoint, not an installed replacement or complete advanced-tool migration. The installed 3.2.5 program and real Data/profile were untouched.

## Connected batch

| Existing endpoint/tool | Private worker ID | Connected behavior |
| --- | --- | --- |
| `update-metadata` / Change Metadata | `metadata-update` | Standard fields, local-time dates, paired custom fields, delete-all Info/XMP/PieceInfo. Omitted standard fields clear their values, matching the existing controller. |
| `unlock-pdf-forms` | `forms-unlock` | Clear field read-only/Lock; rewrite XFA readOnly access attributes. Malformed XFA fails rather than returning a partly unlocked output. |
| `flatten` | `flatten` | `flattenOnlyForms=true` paints AcroForm appearances while preserving page text/vector content. XFA forms-only flattening is explicitly refused. `false` is the existing tool's deliberate full-page JPEG raster mode: requested DPI clamps to 72–300, default 300. |

`PdfMetadataEditor`, `PdfForms` and `PdfPageFlattener` hold shared PDFBox algorithms; the HTTP controllers call them too. The controller retains its configured DPI/OOM guards and document factory. The worker has a fixed 300-DPI ceiling and rejects pages above 100 million raster pixels. Full-page output preserves source Info metadata rather than applying server-configured producer defaults. It fails the job on page-render failure rather than silently omitting that page. Full-page flattening retains the existing MediaBox placement behavior; it does not promise editable text, bookmarks or form structure after rasterization.

All worker mutations retain the existing signed-document/owner-permission guard. Inputs remain private read-only copies. No new filesystem grant, socket, shell, helper, dependency or HTTP service was introduced. The exact host and frontend allowlists advertise these three modes only when the worker distribution is present. The frontend normalizes the existing metadata hook's bracket-map fields into bounded JSON. Firefox receives no native capabilities.

## Verification

- `task backend:check TEST_TASK=:stirling-pdf:test "TEST_ARGS=--tests *MetadataControllerTest --tests *FlattenControllerTest --tests *UnlockPDFFormsControllerTest" STIRLING_CHECK_JOBS=2`: pass, 26 tests (13 + 8 + 5). Log `.tooling/phase4-backend-check.log`; XML under `app/core/build/test-results/test/`. The selected-suite JaCoCo summary remains below whole-project targets; this was not a whole-project coverage run.
- `:worker:installDist :worker:verifyNonWebRuntime`: pass; worker runtime excludes Spring/Tomcat/Jetty. Final plain-module checks recorded in `.tooling/phase4-final-java.log`.
- `task frontend:check "TEST_FILES=src/editor/core/runtime/desktopWorker.test.ts" STIRLING_CHECK_JOBS=2`: pass, six boundary tests plus typecheck/lint/format checks. Actual run also supplied a nonexistent processing test path; only the six reported bridge tests are claimed. Log `.tooling/phase4-frontend-check-final.log`. Initial dynamic-import test timed out at five seconds; static collection-time imports fixed that test-only issue.
- `task frontend:build:core`: pass, 29.88 seconds; existing chunk warnings. Log `.tooling/phase4-ui-build.log`.
- `packaging/worker/document_smoke.py --java <private-java>` using private helper Python: eight saved-output/refusal cases pass. Checks metadata/custom/date/remove-all, XFA unlock, XFA forms-flatten refusal, saved AcroForm flatten retains text/value appearance, 72-DPI raster dimensions, reader-permission refusal, unknown-option refusal and original input hashes. Evidence `.tooling/nonweb-document-golden/results.json`.
- Native C# host compile: pass. Actual WebView2 editor UI changed Title to **Swona native standalone** and pressed **Change**. Input/run/cleanup virtual requests returned 200. Extracted FileContext output reopened with the changed title and original form value. Source bytes/hash and rendered page pixels were unchanged; external resource requests were empty. Evidence `.tooling/phase4-shell/native-result.json`, extracted `file-0.pdf`/`file-1.pdf`, physical 1440×960 screenshot `.tooling/phase4-shell/native-metadata-full.png`. Test CDP port 9445 was isolated and subsequently disabled for the normal prototype.

## Remaining parity, in concrete batches

The classified `packaging/worker/migration-inventory.json` contains 119 literal source mappings, not 119 proven installed tools. Categories: 14 connected mappings (five existing local page mappings, six initial worker mappings, three maintenance mappings); four local equivalents without endpoint parity; two bootstrap/orchestration mappings; 11 pending inspection contracts; three pending security-mode mappings; six pending form-authoring/export mappings; nine helper/external modes requiring audit; 70 other processing mappings requiring mode audit. Counts classify source, not a claim that every optional branch worked in the installed package.

1. Inspection contracts: Analysis basic/document/page/font/annotation/form/security reports; Get ALL Info, JavaScript listing and PDF verification. Metadata prefill already uses the existing local metadata reader and needed no inspection endpoint for this batch.
2. PDFBox page decoration/layout: crop, scale, numbers, watermark, text/image stamp, overlay, image overlay, n-up/booklet/poster/single-page; preserve each DTO's quality/layout modes.
3. Attachments/portfolios: list/extract/add/rename/delete/batch and create/flatten portfolio; new selected non-PDF grants must remain bounded.
4. Extraction/conversion: image extraction, PDF/image modes and quality, text/CSV/HTML/Office/vector/JSON/text-editor families. Audit actual pure-Java versus helper branches individually; Office is not blanket-classified as unavailable.
5. Other retained document functions: bookmarks/chapter/section/size splitting, compression/decompression/repair, redaction, text editing, image removal/scanner/color effects, filters, auto-name/split and form field authoring/CSV/XLSX export. Annotation removal has not been advertised as a separate connected operation.
6. Security modes: AES-128, selective protection/password changes and preservation; visible PKCS12 signatures, trust/chain/TSA/hardware modes and deliberate signature removal. Existing AES-256/full removal/invisible signing/integrity-only validation remain connected as documented in phase 3.

Legacy optional Ghostscript/unpaper/ebook/remote renderer branches remain distinct from verified installed parity. No feature is deleted from the retained source as a substitute for migration. Final nonserver launcher/package migration, explicit old-origin library/profile migration, safe installed upgrade and Firefox static worker adaptation/actual acceptance remain later work. Firefox UI remains policy-blocked and was not attempted. Unsigned/local-use redistribution caveats are unchanged.
