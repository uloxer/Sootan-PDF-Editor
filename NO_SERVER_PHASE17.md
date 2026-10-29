# Standalone phase17: multi-file overlays

The existing Overlay PDFs tool now dispatches `overlay-pdfs` to the lazy non-web `overlay` worker operation. The legacy controller delegates to the same plain `PdfOverlays` service. PDFBox foreground/background compositing remains vector-based; base page geometry and catalog structures remain in the original document.

Selected overlay PDFs use the existing indexed `attachment-N` private copies (1–100 files,50MiB each,200MiB total), not client paths. The native job owns cleanup/cancellation and applies its three-minute deadline. Base mutation requires owner access and refuses signatures; source pages require readable extraction permission. Selected source pages containing annotations/widgets are refused explicitly rather than silently losing their appearance. No helper, listener or network access was added.

Sequential starts at the first selected PDF and cycles through complete PDFs in order. Interleaved advances each PDF's page cursor in round-robin order. Fixed Repeat repeats the complete PDF according to each count, leaving remaining base pages unchanged when counts run out. The extracted implementation corrects legacy first-file/page-one mapping bugs; English help now describes whole-PDF counts. Rotated/nonzero-origin overlay pages are normalized as vector forms and centered in the displayed base page without scaling.

## Verification

- `gradlew spotlessApply :worker:installDist :worker:check :stirling-pdf:compileJava`: PASS, including non-web dependency guard.
- `task backend:check TEST_TASK=:stirling-pdf:test "TEST_ARGS=--tests *PdfOverlayControllerTest*"`: PASS,8 tests. Scoped coverage output is not a full-suite coverage claim.
- `packaging/worker/overlay_smoke.py --java .tooling/jdk-25.0.4.1+1/bin/java.exe` using packaged helper Python: PASS,4 actual worker jobs. Independently reopened PDFs verify seven-page ordering, all three modes, whole-document repeats, untouched trailing page, foreground/background, four base rotations, nonzero origins, centered rendered markers and original SHA256 hashes. Evidence: `.tooling/nonweb-overlay-golden/results.json`.
- Native host/worker C# compile: PASS (`.tooling/phase17-host.exe`); no app launch or installation required for this dispatch-only change.
- `task frontend:check TEST_FILES=src/editor/core/runtime/overlayDispatch.test.ts`: PASS, typecheck/lint/format and3 actual-tool dispatch tests verify ordered grants, numeric repeat counts, PDF filename and DELETE cleanup. Log: `.tooling/phase17-frontend.log`.

No production bundle/package rebuilt; installed3.2.5 and real Data/profile remain untouched. Source integrations await the final standalone package. Remaining broader parity/profile migration/packaging and Firefox acceptance are separate work; this batch makes no full-parity claim.
