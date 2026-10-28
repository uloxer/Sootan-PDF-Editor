# Standalone attachments and portfolios - phase 8

Verified 28 October 2026. Local review checkpoint: no push, installer replacement or real Data/profile changes.

## Connected family

The existing AttachmentService and PortfolioService algorithms now live in plain PdfAttachments/PdfPortfolio, with compatible Spring service wrappers. AttachmentInfo retains its original package and JSON fields in pdf-services. The actual attachment-manager service dispatches through the desktop worker; the existing PDFium viewer attachment/portfolio reader remains intact.

| Existing endpoint | Worker operation/action | Output |
| --- | --- | --- |
| list-attachments | attachments/list | Original AttachmentInfo JSON array |
| extract-attachments | attachments/extract | ZIP |
| extract-single-attachment | attachments/single | Exact bytes, application/octet-stream |
| add-attachments | attachments/add | Modified PDF |
| rename-attachment | attachments/rename | Modified PDF |
| delete-attachment | attachments/delete | Modified PDF |
| batch-process-attachments | attachments/batch | Modified PDF |
| create-portfolio | portfolio-create | Cover PDF plus original embedded members and Collection dictionary |
| flatten-portfolio | portfolio-flatten | Removes Collection wrapper, retains embedded members |

Batch retains deletion-before-rename-before-addition ordering and skips unknown rename/delete targets as the existing contract documents. Duplicate add/rename targets are refused instead of overwriting another member's bytes. Nested name-tree contents are retained when adding. Filename, Unicode filename, MIME, dates and file-spec relationships remain with each member. A demonstrated legacy deletion defect left catalog AF referencing the deleted member; deletion now removes that reference, and rename/delete maintain Collection's initial-file name. Reopened fixtures prove AFRelationship survives rename. Other document metadata, forms, bookmarks, page content and geometry remain intact; no rasterization or attachment execution occurs.

Read-only list/extract/single accepts authenticated user credentials subject to extraction permission, including signed PDFs. Mutations retain owner/signature guards. The signed fixture in this batch is a deliberate signature-field/ByteRange marker, not a CMS integrity test; earlier signature verification goldens cover cryptography. PDF/A-3b conversion explicitly remains unavailable pending the conversion family: its option is refused with a useful frontend message before copying files, never ignored. Ordinary add/batch defaults work.

## Selected-file boundary

The trusted native origin streams selected non-PDF bytes to finite attachment-0 through attachment-99 grants under its UUID job directory. selectedFiles metadata contains only integer index, display name and MIME; client names never become filesystem paths. Both frontend/native/Java boundaries enforce100 files,50 MiB each and200 MiB total; each private copy becomes read-only. Java validates each real regular file's containment and rejects symlinks, invalid/repeated indices, empty files and malformed MIME metadata. No directory/root grant exists.

Embedding streams close after use. ZIP extraction flattens path separators, removes control characters, replaces empty/dot names and resolves collisions deterministically with suffixes, including case-insensitive collisions. It creates only fixed output.zip in the job, never writes ZIP member paths to disk. Embedded name trees have cycle/node/member bounds. Worker extraction fails on size limits rather than silently returning a partial ZIP; the retained legacy service default keeps its existing skip behavior. Single-file bytes use fixed output.bin, without executing or opening them. Existing process-tree cancellation/job cleanup applies to all grants.

## Verification

- Required backend task gate:62 controller/service/model tests passed before the associated-file fix; final affected AttachmentServiceTest/PortfolioServiceTest gate38 passed after it. Controller facades did not change afterward. `.tooling/phase8-backend-check.log`, `phase8-backend-final.log`; XML in app/core/build/test-results/test. Selected coverage summaries miss whole-project thresholds; these are not full-suite coverage claims.
- Required frontend gate:62 passed (13 bridge,9 attachment service,2 manager,38 migrated mapper), plus typecheck/lint/format. `.tooling/phase8-frontend-pass.log`. Earlier attempts identified redundant Blob casts, formatter mismatch and the legacy exact two-argument server call; corrected before final pass. A preliminary command targeted the root Vite tsconfig and failed; the required core task uses its correct configuration and passes.
- Worker installDist/nonweb guard passes: `.tooling/phase8-worker-final.log`. Final library directory:39 JARs,37936811 bytes, excluding Java/runtime/fonts/helpers. Added existing commons-lang3/security-toolkit dependencies reuse retained filename handling; no web framework or helper executable added.
- `packaging/worker/attachments_smoke.py` passes32 process-boundary cases. `.tooling/nonweb-attachments-golden/results.json`: exact extracted bytes/MIME/schema, ZIP traversal/collisions, rename/delete/batch, source hashes, forms/bookmarks/text/rotation/CropBox, AF/Collection references, encrypted roles, signed-marker reads/mutation refusal and duplicate portfolio naming/flattening. Reproduced orphan-AF evidence: associated-before-fix.json.
- Final C# host compiles. Production UI build passes35.33 seconds: `.tooling/phase8-ui-build.log`.
- Actual native Add Attachments stages Notes with spaces.txt and saves via batch, then re-lists the embedded29-byte member. All seven observed input/grant/run/cleanup requests returned200. FileContext's output independently reopens with exact member bytes, text/plain MIME and retained form/metadata/page geometry. Original PDF SHA256 remains eeff73cadd067d5bdf393807f53091a2f75433914ba3cb33ac575ae8f1b81ee4; observed external resource requests empty. `.tooling/phase8-shell/native-result.json`, output-check.json and file-1.pdf. Physical1440x960 screenshot: native-attachments-full.png.
- Test-only CDP9449 was disabled afterward. Normal isolated hostPID48040 is running with zero owned listening sockets and zero idle Java/Python workers: `.tooling/phase8-shell/final-state.json`. Installed3.2.5 remains untouched.

## Remaining parity

The inventory connects these nine ordinary attachment/portfolio mappings. PDF/A conversion (including the explicit PDF/A-3b attachment option), text/image extraction and PDF/image conversion, remaining layouts/booklets, redaction/text editing, form authoring/export and PEM/JKS/device signing remain queued. Office/Ghostscript/unpaper branches retain their existing unbundled-helper limitations. Final nonserver launcher/package, old-origin library/profile migration and a data-preserving installed upgrade remain pending. Firefox actual UI remains policy-blocked and was not attempted; static worker adaptation remains separate. Unsigned/local-use redistribution caveats are unchanged.

