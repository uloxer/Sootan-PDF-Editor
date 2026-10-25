# Core inventory

## Retained

The existing MIT core editor, local file library and HTTP PDF APIs remain. Tools include viewing/text editing/annotations, merge/split, page rearrangement/rotation/cropping/insertion/deletion, compression, images, OCR, Office conversions, signatures, redaction, watermarks, forms, metadata, encryption/passwords, validation/repair, sanitization and local pipelines. Availability of native conversions depends on installed tools. Some AI-assisted tools already had only stubs in core; the removed engine does not become available through this fork.

PDFBox, JPDFium/EmbedPDF, qpdf, LibreOffice/unoserver, Ghostscript, OCRmyPDF/Tesseract, image codecs, OpenCV, Bouncy Castle, HTML sanitization and java-security-toolkit are retained where used. Fonts and all translations are retained for document fidelity. Existing temporary-file, path, upload, command and SSRF defenses are retained. No antivirus or replacement security service is added.

## Removed

- Restricted source: `app/proprietary`, `app/saas`, `engine`, `frontend/src/processor`, and editor `proprietary`, `desktop`, `saas`, `cloud` layers. Root MIT and third-party notices are preserved.
- Built-in accounts/login/MFA/API keys/SSO and the administration portal. The user explicitly accepted a local-use core without replacement authentication. The PDF HTTP API remains unauthenticated.
- Subscription/payment/licensing/entitlement/account-linking and hosted commercial functionality supplied by those layers; Tauri desktop distribution/native wrapper and hosted AI engine.
- PostHog SDK/configuration/service, periodic telemetry aggregation, PDF operation reporting/interceptor, frontend tracking hooks, Scarf pixel, analytics consent startup, browser-ID header and WAU tracking, installation-ID generation.
- npm PostHog, Stripe, Supabase, Tauri, Userback and Google API type packages; Java PostHog and Telegram bot dependencies and bot runtime. Local diagnostics and PDF processing logs remain.
- Google Drive picker integration, upstream remote updater requests, automatic update/onboarding prompts, account/integration provider setup UI, portal build targets and upstream deployment workflows.

Build aliases and a few inert core extension/configuration contracts remain to avoid rewriting working PDF tools. They do not load the deleted SDKs or send tracking requests. Historic configuration fields are not an enabled analytics implementation.

## Offline behavior

Locally supported PDF tools use local resources; fonts, PDF engines and translations are bundled. Commercial analytics/licensing/update/Drive requests are removed. Network-dependent tools such as URL-to-PDF, remote signature timestamp/certificate checks, or peer-to-peer phone scanning inherently require a network when explicitly used; they are not offline operations. No external request is required to open an ordinary local PDF. Do not treat this as a guarantee that every optional tool can run disconnected.

## Size choices

Whole separately licensed modules were removed first. Existing lazy tool loading, minification, chunk splitting and compressed web resources remain. Native imaging, OCR/Office dependencies and large CJK fonts were deliberately retained: removing them would reduce fidelity or supported tools. The full-tools container is consequently larger than a viewer-only image. Runtime tuning beyond removal of background tracking must be justified by measured data.

The translation audit retains a snapshot of inherited unused English keys after removing licensed layers; new unused keys are still rejected, and missing-key checks remain active. This preserves existing locale resources while avoiding a broad translation rewrite.
