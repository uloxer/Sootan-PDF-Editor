# Standalone page formatting — phase 6

Verified 28 October 2026. Local review checkpoint; no push, installer replacement or real Data/profile changes.

## Connected modes

| Existing tool endpoint | Worker ID | Behavior |
| --- | --- | --- |
| `crop` | `page-crop` | Manual rectangle and selected pages; existing150 DPI automatic content detection. Export stays clipped vector/text forms, not a rendered-page PDF. |
| `scale-pages` | `page-scale` | Existing A0–A6/LETTER/LEGAL/KEEP, portrait/landscape and content scale factor; existing LayerUtility centering and visible stamp-appearance preservation. |
| `add-page-numbers` | `page-numbers` | Selected pages, starting number, padding, margin/position, Helvetica/Courier/Times, color/size and filename/current/total placeholders. |
| `add-watermark` | `watermark` | Text/image, alphabet, size/spacing/rotation/color/opacity. Existing explicit convertPDFToImage option renders300 DPI lossless images; normal output retains text/vector content. |

The retained controllers call plain `PdfPageCrop`, `PdfPageScale`, `PdfPageNumbers` and `PdfWatermarks`. Page selection is extracted from the existing common range/n-expression parser; a compatibility test compares its results against the unchanged common parser. Crop/scale retain legacy LayerUtility coordinate behavior: source rotation can be baked into imported forms instead of retaining the page Rotate entry. Numbering and normal watermark output retain original page boxes/rotation. No independent visual comparison against an old installed-controller binary was performed; existing controller regression tests and independently reopened/rendered fixtures provide this batch's evidence.

Crop is clipping, not secure hidden-data removal. Ghostscript destructive crop remains unavailable and explicit requests are refused. Crop/scale refuse forms, bookmarks and unsupported annotations instead of silently discarding/misaligning them; scale retains the existing visible rubber-stamp handling. These refusals propagate only a fixed `UNSUPPORTED_DOCUMENT_STRUCTURE` code to a useful frontend message, without exposing arbitrary worker exception text. Existing owner/signature mutation guards remain.

The native bridge streams selected bitmap bytes into a read-only fixed image.bin grant, limits MIME formats to PNG/JPEG/GIF/BMP/TIFF and bytes to32 MiB. Java checks the actual ImageIO format and dimensions before decode, with25 million-pixel ceiling. Auto-crop/explicit raster rendering has100 million-pixel ceiling; formatting limits documents to10000 pages, page-number padding and watermark density are bounded. Original input files remain unchanged.

Text watermarks use the existing six Noto regular faces (roman/Arabic/Japanese/Korean/Chinese/Thai). The host controls SOOTAN_WORKER_FONTS, default app/fonts; no web-selected path or font fetch exists. Native watermark capability is advertised only when all six files exist. This prototype points at retained source fonts; the final standalone packaging recipe must place those faces under app/fonts. No extra helper/runtime was added. The existing small javaluator3.0.6 dependency is reused for the retained page-expression grammar.

## Verification

- Required backend task gate with CropControllerTest, ScalePagesControllerTest, PageNumbersControllerTest, WatermarkControllerTest and StandalonePageSelectionTest:117 passed. `.tooling/phase6-backend-check.log`; XML in app/core/build/test-results/test. Selected-suite coverage is not whole-project coverage.
- Worker installDist and non-web dependency guard pass. `.tooling/phase6-worker-final.log`.
- Required frontend task gate:19 tests across bridge/crop/scale/watermark hooks, plus typecheck/lint/format pass. Initial tsgolint process crashed with Go `fatal error: fault`; one bounded process-local GOMAXPROCS=2 retry passed. `.tooling/phase6-frontend-check.log`, `phase6-frontend-check-final.log`.
- Production bundle built in36.75 seconds and passed Node syntax check. `.tooling/phase6-ui-build.log`. C# host compiled.
- packaging/worker/formatting_smoke.py with private Java/helper Python and retained fonts:12 saved-output/refusal cases passed. Independent pikepdf/PDFium checks cover manual/automatic selected crop, landscape scale dimensions, page-number selected text/padding/filename, Latin/CJK/image watermarks, page count/text/rotated CropBox preservation, originals' hashes, out-of-range geometry/selection, forms, malformed bitmap and excessive density. `.tooling/nonweb-formatting-golden/results.json` and per-job render.png.
- Actual native Add Watermark UI saved **Swona standalone proof** through input/run/cleanup virtual requests, all 200. FileContext output independently reopened with original text, two pages, Rotate90 and nonzero CropBox preserved; original bytes/hash preserved. Observed external resource requests empty. `.tooling/phase6-shell/native-result.json`, extracted file-0.pdf/file-1.pdf, physical 1440×960 native-watermark-full.png. Test CDP9447 was disabled afterward; normal prototype state is final-state.json.

## Remaining parity

Stamp is a separate QR/date/positioning primitive and remains pending, alongside inspection/Get ALL Info contracts, attachments/portfolios, extraction/conversion, remaining layouts, redaction/text editing, form authoring/export and local PEM/JKS/device modes. Full inspection is not advertised as partial JSON. The classified source inventory marks four additional connected endpoints; retained source is not deleted as a substitute for migration. Final nonserver launcher/package, old-origin library/profile migration and safe installed upgrade remain pending. Firefox UI remains policy-blocked and was not attempted; its static worker adaptation remains separate. Unsigned/local-use redistribution caveats remain unchanged.
