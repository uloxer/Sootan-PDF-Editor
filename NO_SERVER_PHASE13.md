# Server-free PDF/A conversion — Phase 13

2026-10-29. Local worker operation `pdfa-convert` connects Convert PDF → PDF/A without a server or external helpers. The existing PDFBox algorithm is shared in `PdfArchive`; legacy controller signatures/reflection targets delegate to it. The ICC profile has one canonical shared-module resource at the unchanged classpath name.

Supported requests: PDF/A-1b (`pdfa-1`), PDF/A-2b (`pdfa`, `pdfa-2`, `pdfa-2b`), PDF/A-3b (`pdfa-3`, `pdfa-3b`). Every returned output passes VeraPDF against the explicit requested flavour, independently of its declared metadata. Strict validation is mandatory in the local UI even if legacy request `strict` is false. Noncompliance returns no output and a fixed human-readable error.

Source fonts must already be embedded. Inputs requiring LibreOffice font embedding or PDF/A-1 transparent-image conversion are refused. PDF/X, level A, PDF/UA and automatic PDF/A-3b attachment conversion remain unavailable; no helper or compliance claim is fabricated. Existing attachment conversion refusal remains explicit. Local output is limited to 100 pages/128 MiB; the private host retains its input limits and three-minute owned-job deadline. Owner/signed mutation guards and original read-only copies remain intact.

Real conversion exposed an inherited recursive COS sanitizer bug on page Parent cycles. Identity traversal now bounds cycles/depth/object count. PDF/A-1 highlight preprocessing retains the existing algorithm. Bounded XMP parsing disables external DTD/schema/entity access before XMPBox parsing. No new font fetching or web/runtime dependencies.

Verification:
- Final `task backend:check TEST_TASK=:stirling-pdf:test "TEST_ARGS=--tests *ConvertPDFToPDFA* --tests *LocalPdfArchiveTest*"`: 92 tests passed; `.tooling/phase13-backend-final.log`. Includes actual requested 1b/2b/3b validation, wrong-flavour rejection and helper/mode refusal. Scoped coverage is not a full-project coverage result.
- `task frontend:check TEST_FILES=src/editor/core/runtime/conversions.test.ts`: type/lint/format plus 13 tests passed; `.tooling/phase13-frontend-final.log`.
- Eight actual stdio worker jobs passed: three B-level outputs, embedded Unicode text/render preserved, missing-helper/mode refusal, reader refusal/owner conversion and unchanged original hashes; `.tooling/nonweb-pdfa-golden/results.json`, `.tooling/phase13-golden.log`. This preceded final highlight/XMP hardening, covered by the final affected backend gate.
- Final worker installDist/nonweb dependency guard and C# host compile passed; `.tooling/phase13-worker-final.log`, `.tooling/phase13-host-compile.log`.

No native UI rebuild was needed for the typed dispatch proof; the running isolated Phase 12 host (PID 58920) remains normal/no-debug with no listening socket. The compiled Phase 13 host is `.tooling/phase13-host.exe`; it was not installed/launched. Actual installed 3.2.5 and its Data/profile remain untouched. No push/release. Remaining interactive text-editor sessions, tool parity, profile migration and final standalone packaging remain queued; Firefox UI acceptance remains policy-blocked.
