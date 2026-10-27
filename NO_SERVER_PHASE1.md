# No-server phase1 — 27 October 2026

Phase1 provides a working isolated offline Windows shell and shared boot boundary. It does **not** replace the installed3.2.5 product. The existing installed program and real Data directory were not modified. Firefox packaging, additional local operations and the lazy non-web Java worker remain later phases.

## Implemented boundary

`packaging/windows/SootanHost.cs` accepts `--offline <isolated-data-directory> <built-UI-directory> [PDF paths...]`. It serves read-only bundled assets through WebView2 resource responses at virtual `https://sootan.local`; this is an internal origin, not an HTTP server or network endpoint. Only files under the supplied application bundle resolve, with traversal/origin checks and SPA fallback. Explicit user-selected PDF grants retain their separate one-use route. A discovered overlapping-handler bug was fixed by making the selected-file handler reject every non-grant resource before touching its response.

PerMonitorV2 awareness is set before WinForms initialization and DPI autoscaling is explicit. The redundant native File menu is removed; Ctrl+O, native close/Alt+F4 and Stop remain. Actual observed DPR is1.25; moving between different monitors was not tested and display/security settings were not changed.

Shared interface: `frontend/src/editor/core/runtime/runtime.ts` exports `getRuntime(): RuntimeCapabilities` (`kind: server|offline-desktop|extension`, `serverOperations`, `nativeFileDialogs`, `renderer: wasm`), `OFFLINE_APP_CONFIG`, `supportsLocalEndpoint()` and the unavailable-operation message. The host injects the offline seed before application scripts. A missing seed preserves existing server behavior; a network failure never silently selects offline mode. AppProviders supplies the existing AppConfigProvider with seeded config, non-blocking bootstrap and autoFetch=false. Config/footer/endpoint lookups return local capability data, and unexpected API-client calls are cancelled before network dispatch.

Local view/annotation, sign and form-fill catalog markers remain available. API-only tools are marked NOT_SUPPORTED_LOCALLY. The mixed redaction catalog uses the auto-redact marker, so that catalog entry remains disabled while the existing viewer manual-redaction action is local; later mode-aware availability should distinguish those paths. Merge/rotate and other API tools are not yet local adapters. The same boot boundary is available for a Firefox entry, but extension CSP/worker packaging has not been verified. No new restrictive CSP was introduced in this phase.

## Measured actual native UI

Reusable automation: `node packaging/benchmarks/ui.cjs <SootanHost.exe> <frontend/dist>`. It starts the actual isolated WebView with test-only loopback CDP, opens ten existing fixtures, waits for PDF bitmap resolution to reach CSS width × DPR, and records paints after animation frames. It closes its own test host afterward.

- Fresh process/profile to first DPR-sharp painted PDF: **4,902.76ms**, one sample. This is an upper bound including CDP polling/attachment; OS disk caches were not reset. No cold-launch p50/p95 or statistical speedup is claimed.
-20 tab switches: p50 **581.42ms**, p95 **648.97ms**.
-20 zoom changes: p50 **84.88ms**, p95 **100.80ms**.
-Host plus descendant WebView working sets at1/5/10 open documents: **608,964,608 /710,844,416 /777,310,208bytes**. These are process working-set sums, including potentially double-counted shared pages, not private committed memory. They are one-run baselines, not a native-renderer memory comparison.
-Reload observed35 bundled requests, zero API requests, zero external requests and zero failed requests. WASM, worker and font resources required by these fixtures loaded locally. This does not individually certify every bundled font or Microsoft's separate Evergreen background behavior.

Raw UI samples: `.tooling/offline-ui-benchmark/results.json`. Final normal prototype PID42940 has only WebView descendants, **no Java and no TCP listeners**; test CDP is off. Proof: `normal-process-proof.json`. It uses an isolated profile under `.tooling/offline-ui-benchmark/` and restores ten tabs. Screenshot: `.tooling/offline-ui-benchmark/offline-native.png`, showing the actual native frame, retained library/tool rail, document tabs and rendered vector page. The isolated prototype is left visible for review; the installed app is untouched.

## Bounded native PDFium comparison

Run `node packaging/benchmarks/render-wasm.mjs`, private Python `packaging/benchmarks/render-native.py`, then `packaging/benchmarks/compare.py`. Both rasterize identical fixture bytes/page0 at1000×1400, zoom1500×2100, white BGRA background and flag1. Each records a first iteration plus20 warm iterations. The long-document fixture is opened in full but only page0 rasterized. This compares raster kernels: Node V8 WASM from @embedpdf/pdfium2.15.1 against private native PDFium156.0.8076.0 via ctypes. It excludes transport/compositor overhead, form environments and edit/annotation parity. Different builds/font environments can explain pixel differences; the benchmark does not attribute a cause.
| Fixture | WASM open p95 ms | Native open p95 ms | WASM zoom p95 ms | Native zoom p95 ms | Different pixels % |
|---|---:|---:|---:|---:|---:|
|text|3.95|2.35|5.84|3.08|0.4135|
|scan|132.32|60.36|80.17|55.94|0.0000|
|long|1.86|2.16|2.73|2.96|0.2205|
|vector|9.77|13.23|20.39|27.24|0.1659|
|fonts|2.46|2.63|3.50|3.99|0.3241|
|forms|2.12|2.30|3.32|2.95|0.1081|
|encrypted|12.03|9.35|2.43|2.78|0.0000|

**Keep WASM.** Native exceeds the25% p95 improvement threshold for text/scan but regresses other cases; vector open/zoom are roughly35%/34% slower. Pixel parity also fails on several fixtures, and <=10% memory regression plus editing parity are unproven. A complete native viewer adapter is therefore not warranted by phase1 evidence. No lifecycle redesign was made without a separate cause/benefit measurement.

Paired raw timings, comparison and fixture SHA256 values are committed in `packaging/benchmarks/results/phase1-2026-10-27.json`. Raw paired BGRA outputs remain under `.tooling/render-benchmark/`. Scan/encrypted fixtures reference the existing ignored synthetic smoke corpus; a fresh checkout without them records explicit skips. No user PDFs or secret configuration values are part of the benchmark.

## Verification and handoff

- `task frontend:check TEST_FILES="src/editor/core/runtime/runtime.test.ts src/editor/core/contexts/AppConfigContext.test.tsx src/editor/core/hooks/useEndpointConfig.test.tsx" STIRLING_CHECK_JOBS=2`: PASS32tests plus relevant type/lint/theme/icon/CSS/format gates. `.tooling/offline-phase1-check.log`.
- `SOOTAN_WINDOWS_PACKAGE=true`, `VITE_API_BASE_URL=/`, `task frontend:build:core`: PASS33.57seconds. `.tooling/offline-phase1-build.log`. The first rebuild encountered the running prototype's open bundle file; that own prototype was stopped before rebuilding.
-Host compiled with the existing .NET Framework compiler and pinned WebView2 SDK; native origin/bundle traversal/SPA route/download tests PASS. Actual resource-load failure was corrected and the successful UI benchmark reran over the final host/bundle.
-Original server runtime defaults are covered by existing AppConfig/endpoint tests. No backend Java code changed and no unrelated full suite was rerun. No installer replacement, push, release or deployment occurred.

Phase2 should use the explicit runtime capability boundary to introduce local operation adapters and a Firefox entry, preserving FileContext/ViewerContext and existing editing protections. Phase3 can add the lazy non-web advanced-processing worker; it must not re-enable an HTTP listener or treat the current cancellation boundary as an operation implementation.
