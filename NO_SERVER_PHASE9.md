# Standalone text and bitmap conversion - phase 9

Verified 28 October 2026. Local review checkpoint; no push, installer replacement or real Data/library/profile changes.

## Connected modes

| Existing endpoint | Worker capability | Retained behavior |
| --- | --- | --- |
| convert/pdf/text | pdf-text-export | UTF-8 TXT using existing PDFTextStripper defaults; RTF and alternate Office engine explicitly unavailable in this mode |
| convert/pdf/img | pdf-image-export | PNG/JPEG/TIFF/BMP/GIF, DPI, color, page selection/order, annotations; single stacked image or page ZIP, with multiframe TIFF for single-TIFF output |
| convert/img/pdf | image-pdf-create | Selected bitmap files combined in order, fit/fill/aspect, autoRotate, color; each TIFF frame becomes a PDF page |
| misc/extract-images | image-extract | Existing embedded-XObject extraction/content fingerprint dedup and requested output format, ZIP |

Plain PdfImageConversion retains the existing PDFBox renderer's subsampling, annotation filter, centering/stacking and TIFF ZLib sequence behavior. PdfUtils delegates rendering and the unchanged page-placement primitive; ImageProcessingUtils delegates its existing color/EXIF orientation algorithm to plain PdfImageInput. The legacy EXIF algorithm is retained, including its existing orientation-tag interpretation; this batch does not claim a new EXIF correction algorithm. Legacy image-import/controller paths remain compatible. PDF-to-TXT shares the exact original stripper. PdfImageExtraction holds the extracted dictionary-aware fingerprint algorithm and image writer; the controller facade and original dedup tests use that implementation.

There is no PDF rasterization for ordinary document transforms. These modes explicitly export bitmaps or import selected bitmaps. JPEG retains the existing ImageIO writer/JPEGFactory defaults; no new quality control is invented where the existing DTO has none. GIF input retains the existing first-frame behavior; TIFF import preserves all frames. Image alpha handling follows existing PNG/other-output canvas semantics. The frontend's grayscale spelling is normalized to the existing greyscale algorithm.

Actual Convert custom processing dispatches through the native adapter, including multiple image grants. Conversion pairs are validated when executing and selecting each target; the real dropdown disables unavailable RTF/WebP/PDF-A choices, and the Office-engine selector is absent in offline mode. An unsupported explicit pair throws a useful error instead of being swallowed or silently producing TXT. Fixed MIME and Content-Disposition preserve a ZIP result as converted.zip rather than a misleading image suffix. The shared extract-images hook uses its existing operation path. Viewer/rendering remains WASM.

## Boundaries

Bitmap input reuses the100-file,50 MiB/file,200 MiB total indexed read-only grants from phase8, with no client paths. ImageIO headers are checked before allocation:100 million pixels per bitmap/frame and combined imported frames; TIFF is bounded to1000 frames. Rendering checks each page and the combined canvas before allocation, with100-million-pixel bounds. Worker PDF exports cap input100 MiB/10000 pages, DPI500 and encoded raster output256 MiB; configured legacy DPI ceilings remain honored by the compatibility facade. Dimension rounding follows PDFBox float scale/floor arithmetic, which can differ by one pixel from exact rational DPI arithmetic. OOM during page rendering becomes a bounded job error. These limits do not make every pathological PDF cheap to decode.

Readable user credentials can export only with extraction permission. Signed read-only export is allowed; no owner requirement is incorrectly applied to it. Image import creates a new document. Original file hashes remain unchanged. Text is fixed output.txt; single bitmap bytes are fixed output.bin with allowlisted image MIME; ZIP is output.zip. ZIP member names use safe basename/number/format strings. No HTML/SVG URL, shell command, directory grant or executable attachment is accepted. Private helpers remain unchanged and are not used by these pure Java modes.

## Verification

- Required core backend gate:69 passed across selected ConvertImgPDFController, ConvertPDFToOffice and ExtractImages suites. `.tooling/phase9-backend-check.log`.
- Required common backend gate:84 passed across PdfUtils and ImageProcessingUtils suites. `.tooling/phase9-common-check.log`. Selected-suite coverage summaries miss whole-project targets; no full-suite coverage claim.
- Required frontend gate:55 passed (4 exact-pair/actual Convert processor tests,13 bridge,38 migrated mappers), plus typecheck/lint/format. `.tooling/phase9-frontend-final.log`. Initial formatter check identified runtime.ts; corrected before final pass.
- Worker installDist/nonweb dependency guard and C# compile pass. `.tooling/phase9-worker-final.log`. Final library directory:41 JARs,38853272 bytes excluding Java/runtime/fonts/helpers; metadata-extractor/xmpcore reuse existing EXIF support, no new external helper or web framework.
- `packaging/worker/conversion_smoke.py`:20 cases pass, independently reading text, bitmap signatures/pixels/dimensions, reverse selected-page ZIP/grayscale, TIFF frame counts, exact embedded-image extraction/dedup, imported PDF geometry/rendering, malformed huge image header, unsupported options, protected extraction and signed-marker read. `.tooling/nonweb-conversion-golden/results.json`. Rotated PDFTextStripper output uses existing per-glyph line breaks; the fixture assertion normalizes whitespace rather than changing extraction. The signed marker is not a CMS integrity fixture.
- Production UI build41.81 seconds; generated entry syntax passes. `.tooling/phase9-ui-build.log`.
- Actual native Convert PDF to PNG/multiple at300 DPI creates converted.zip in FileContext/Review. Input/run/cleanup all200; original source hash unchanged; observed external resource requests empty. Independently reopened ZIP contains2 PNGs416x833 and1333x999, matching PDFBox's float-DPI floor. `.tooling/phase9-shell/native-result.json`, output-check.json and file-0.bin. Physical1440x960 screenshot: native-convert-full.png.
- Test-only CDP9450 is disabled afterward. Normal isolated hostPID9644 runs with zero owned listening sockets/idle Java/Python workers: `.tooling/phase9-shell/final-state.json`. Installed3.2.5 and real Data/library remain untouched.

## Exact next conversion queue

- `OfficeConversionService` uses in-process stirling.software.officeconvert.OfficeConvert/PdfToPptx/legacy.PdfToPpt, with DOCX/RTF/ODT/TXT/PPTX/ODP/XLSX/ODS and PPT branches. `OfficeToPdfService` can directly handle selected inputs through ConvertOfficeController. These are genuine pure Java candidates, not globally unavailable Office features; their individual modes still need extraction/parity checks. Legacy alternate branches use LibreOffice/unoconvert.
- `ConvertPDFToPDFA.convertWithPdfBoxMethod` has a real PDFBox path, optional QPDF normalization, and conditionally invokes LibreOffice for missing fonts or PDF-A1 transparency. It is not blanket Ghostscript-only. Its plain lifecycle/fonts/validation extraction remains pending; PDF-X currently needs Ghostscript. Attachment PDF-A3b remains explicitly refused until connected.
- `ConvertSvgToPDF`/`SvgToPdf` uses Batik (with existing SvgSanitizer); migrate its network/resource restrictions before advertising it. `ExtractCSVController` uses a separate TabulaTableParser and CSV sanitization; editable JSON uses PdfJsonConversionService. These are not replaced with fake text output.
- `ConvertHtmlToPDF` uses FileToPdf/WeasyPrint; `ConvertPDFToHtml` uses PDFToFile/pdftohtml; PDF-to-XML uses the LibreOffice writer import path. These helper branches require separate actual availability checks. WebP currently uses the Python conversion branch and remains disabled in this bitmap-only adapter.

Remaining layouts/booklets, redaction/text editing, form authoring/export, other signing modes, final nonserver package/launcher and old-origin library/profile migration remain pending. Firefox actual UI remains policy-blocked and was not attempted; static worker adaptation remains separate. Unsigned/local-use redistribution caveats are unchanged.
